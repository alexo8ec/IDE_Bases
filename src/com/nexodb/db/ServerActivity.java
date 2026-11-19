package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.sql.SQLException;
import java.util.*;

/** Read-only, dialect-aware server activity snapshot. No snapshot is persisted. */
public final class ServerActivity {
    public record Session(String id,String user,String database,String state,long seconds,String query,
                          String blockedBy,String object,String transaction,boolean ownSession) {}
    public record Snapshot(List<Session> sessions,String notice,long elapsedMillis) {
        public Snapshot {sessions=List.copyOf(sessions);notice=Objects.toString(notice,"");}
    }
    private ServerActivity(){}

    public static boolean supported(DatabaseType type){return switch(type){case MYSQL,MARIADB,POSTGRESQL,SQLSERVER,ORACLE,H2->true;default->false;};}
    public static Snapshot read(JdbcSession session,int limit)throws Exception{
        DatabaseType type=session.profile().type();if(!supported(type))return new Snapshot(List.of(),"El monitor no está disponible para este motor.",0);
        long started=System.nanoTime();QueryResult result=session.execute(activitySql(type),Math.max(1,Math.min(5_000,limit)));List<Session> rows=new ArrayList<>();
        Map<String,Integer> columns=index(result.columns());
        for(List<Object> row:result.rows()){
            String id=text(row,columns,"session_id"),user=text(row,columns,"user_name"),database=text(row,columns,"database_name"),state=text(row,columns,"state_name"),query=text(row,columns,"query_text"),blocked=text(row,columns,"blocked_by"),object=text(row,columns,"object_name"),transaction=text(row,columns,"transaction_state"),own=text(row,columns,"own_session");
            rows.add(new Session(id,user,database,state,number(text(row,columns,"elapsed_seconds")),query,blocked,object,transaction,"1".equals(own)||"true".equalsIgnoreCase(own)));
        }
        if(type==DatabaseType.MYSQL||type==DatabaseType.MARIADB)rows=new ArrayList<>(ServerHealth.enrichMysqlWaits(session,rows));
        rows.sort(Comparator.comparingLong(Session::seconds).reversed());return new Snapshot(rows,result.truncated()?"Se alcanzó el límite configurado; aumenta el máximo para ver más sesiones.":"",(System.nanoTime()-started)/1_000_000L);
    }

    public static void control(JdbcSession session,String id,boolean terminate)throws SQLException{
        String safe=Objects.toString(id,"").trim();DatabaseType type=session.profile().type();String sql=switch(type){
            case MYSQL,MARIADB->{requireNumeric(safe);yield "KILL "+(terminate?"CONNECTION ":"QUERY ")+safe;}
            case POSTGRESQL->{requireNumeric(safe);yield "SELECT "+(terminate?"pg_terminate_backend":"pg_cancel_backend")+"("+safe+")";}
            case SQLSERVER->{requireNumeric(safe);yield "KILL "+safe;}
            case ORACLE->{if(!safe.matches("[0-9]+,[0-9]+"))throw new SQLException("Oracle requiere SID,SERIAL# válidos.");yield "ALTER SYSTEM "+(terminate?"KILL SESSION ":"CANCEL SQL ")+"'"+safe+"' IMMEDIATE";}
            case H2->{requireNumeric(safe);yield terminate?"ALTER SYSTEM CLOSE SESSION "+safe:"ALTER SYSTEM CANCEL SESSION "+safe;}
            default->throw new SQLException("Este motor no admite control de sesiones desde AdisDB.");
        };session.execute(sql,1);
    }

    static String activitySql(DatabaseType type){return switch(type){
        case MYSQL,MARIADB->"SELECT CAST(ID AS CHAR) session_id, USER user_name, COALESCE(DB,'') database_name, COMMAND state_name, TIME elapsed_seconds, COALESCE(INFO,'') query_text, '' blocked_by, '' object_name, '' transaction_state, IF(ID=CONNECTION_ID(),1,0) own_session FROM information_schema.PROCESSLIST ORDER BY TIME DESC";
        case POSTGRESQL->"SELECT pid::text session_id, usename user_name, COALESCE(datname,'') database_name, COALESCE(state,'') state_name, GREATEST(0,EXTRACT(EPOCH FROM (clock_timestamp()-COALESCE(query_start,state_change))))::bigint elapsed_seconds, COALESCE(query,'') query_text, COALESCE(array_to_string(pg_blocking_pids(pid),','),'') blocked_by, COALESCE(wait_event,'') object_name, CASE WHEN xact_start IS NULL THEN '' ELSE 'Transacción abierta' END transaction_state, CASE WHEN pid=pg_backend_pid() THEN 1 ELSE 0 END own_session FROM pg_stat_activity WHERE backend_type='client backend' ORDER BY elapsed_seconds DESC";
        case SQLSERVER->"SELECT CAST(s.session_id AS varchar(20)) session_id, s.login_name user_name, COALESCE(DB_NAME(r.database_id),'') database_name, COALESCE(r.status,s.status,'') state_name, COALESCE(DATEDIFF(second,r.start_time,SYSDATETIME()),0) elapsed_seconds, COALESCE(t.text,'') query_text, COALESCE(CAST(NULLIF(r.blocking_session_id,0) AS varchar(20)),'') blocked_by, COALESCE(r.wait_resource,'') object_name, CASE WHEN s.open_transaction_count>0 THEN CONCAT(s.open_transaction_count,' transacción(es)') ELSE '' END transaction_state, CASE WHEN s.session_id=@@SPID THEN 1 ELSE 0 END own_session FROM sys.dm_exec_sessions s LEFT JOIN sys.dm_exec_requests r ON r.session_id=s.session_id OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t WHERE s.is_user_process=1 ORDER BY elapsed_seconds DESC";
        case ORACLE->"SELECT TO_CHAR(s.sid)||','||TO_CHAR(s.serial#) session_id, s.username user_name, COALESCE(s.service_name,'') database_name, s.status state_name, s.last_call_et elapsed_seconds, COALESCE(q.sql_text,'') query_text, COALESCE(TO_CHAR(s.blocking_session),'') blocked_by, COALESCE(s.event,'') object_name, CASE WHEN t.addr IS NULL THEN '' ELSE 'Transacción abierta' END transaction_state, CASE WHEN s.audsid=USERENV('SESSIONID') THEN 1 ELSE 0 END own_session FROM v$session s LEFT JOIN v$sql q ON q.sql_id=s.sql_id LEFT JOIN v$transaction t ON t.addr=s.taddr WHERE s.type='USER' ORDER BY s.last_call_et DESC";
        case H2->"SELECT CAST(SESSION_ID AS varchar) session_id, USER_NAME user_name, '' database_name, CASE WHEN EXECUTING_STATEMENT IS NULL THEN 'IDLE' ELSE 'RUNNING' END state_name, CASE WHEN EXECUTING_STATEMENT_START IS NULL THEN 0 ELSE DATEDIFF('SECOND',EXECUTING_STATEMENT_START,CURRENT_TIMESTAMP) END elapsed_seconds, COALESCE(EXECUTING_STATEMENT,'') query_text, '' blocked_by, '' object_name, CASE WHEN CONTAINS_UNCOMMITTED THEN 'Transacción abierta' ELSE '' END transaction_state, CASE WHEN SESSION_ID=SESSION_ID() THEN 1 ELSE 0 END own_session FROM INFORMATION_SCHEMA.SESSIONS";
        default->throw new IllegalArgumentException("Unsupported activity dialect: "+type);
    };}
    private static void requireNumeric(String id)throws SQLException{if(!id.matches("[0-9]+"))throw new SQLException("El identificador de sesión no es válido.");}
    private static Map<String,Integer> index(List<String> names){Map<String,Integer> out=new HashMap<>();for(int i=0;i<names.size();i++)out.put(names.get(i).toLowerCase(Locale.ROOT),i);return out;}
    private static String text(List<Object> row,Map<String,Integer> columns,String name){Integer at=columns.get(name);return at==null||at>=row.size()?"":Objects.toString(row.get(at),"");}
    private static long number(String value){try{return Long.parseLong(value.split("\\.")[0]);}catch(RuntimeException error){return 0;}}
}
