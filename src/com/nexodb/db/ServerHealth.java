package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.util.*;

/** Optional, read-only server health details. Permission failures stay informational. */
public final class ServerHealth {
    public record Deadlock(List<String> sessions){public Deadlock{sessions=List.copyOf(sessions);}}
    public record Replica(String role,String state,String source,long lagSeconds,String received,String applied){}
    public record Replication(List<Replica> replicas,String notice){public Replication{replicas=List.copyOf(replicas);notice=Objects.toString(notice,"");}}
    private ServerHealth(){}

    public static List<Deadlock> deadlocks(List<ServerActivity.Session> sessions){
        Map<String,List<String>> edges=new LinkedHashMap<>();Set<String> available=new HashSet<>();for(var session:sessions)available.add(session.id());
        for(var session:sessions){List<String> blockers=Arrays.stream(session.blockedBy().split(",")).map(String::trim).filter(available::contains).distinct().toList();if(!blockers.isEmpty())edges.put(session.id(),blockers);}
        Set<String> signatures=new LinkedHashSet<>();List<Deadlock> out=new ArrayList<>();for(String start:edges.keySet())walk(start,edges,new ArrayList<>(),new HashSet<>(),signatures,out);return List.copyOf(out);
    }

    public static Replication readReplication(JdbcSession session){
        DatabaseType type=session.profile().type();if(type==DatabaseType.H2)return new Replication(List.of(),"H2 no dispone de replicación de servidor.");if(!Set.of(DatabaseType.MYSQL,DatabaseType.MARIADB,DatabaseType.POSTGRESQL,DatabaseType.SQLSERVER,DatabaseType.ORACLE).contains(type))return new Replication(List.of(),"La replicación no está disponible para este motor.");
        try{return switch(type){case MYSQL,MARIADB->mysql(session);case POSTGRESQL,SQLSERVER,ORACLE->standard(session,type);default->throw new IllegalStateException();};}
        catch(Exception error){return new Replication(List.of(),"Modo informativo: la cuenta no puede consultar el estado de replicación ("+safe(error)+").");}
    }

    static String replicationSql(DatabaseType type){return switch(type){
        case POSTGRESQL->"SELECT 'PRIMARY' role_name, COALESCE(state,'') state_name, COALESCE(application_name||COALESCE('@'||client_addr::text,''),'') source_name, COALESCE(EXTRACT(EPOCH FROM replay_lag)::bigint,0) lag_seconds, COALESCE(sent_lsn::text,'') received_value, COALESCE(replay_lsn::text,'') applied_value FROM pg_stat_replication UNION ALL SELECT 'REPLICA', COALESCE(status,''), COALESCE(sender_host::text,''), 0, COALESCE(written_lsn::text,''), COALESCE(flushed_lsn::text,'') FROM pg_stat_wal_receiver";
        case SQLSERVER->"SELECT COALESCE(rs.role_desc,'') role_name, COALESCE(drs.synchronization_state_desc,'') state_name, COALESCE(ar.replica_server_name,'') source_name, COALESCE(drs.secondary_lag_seconds,0) lag_seconds, COALESCE(CONVERT(varchar(40),drs.last_received_lsn),'') received_value, COALESCE(CONVERT(varchar(40),drs.last_hardened_lsn),'') applied_value FROM sys.dm_hadr_database_replica_states drs JOIN sys.availability_replicas ar ON ar.replica_id=drs.replica_id JOIN sys.dm_hadr_availability_replica_states rs ON rs.replica_id=drs.replica_id AND rs.group_id=drs.group_id";
        case ORACLE->"SELECT CASE WHEN destination IS NULL THEN 'LOCAL' ELSE 'DESTINO' END role_name, COALESCE(status,'') state_name, COALESCE(destination,'') source_name, 0 lag_seconds, COALESCE(TO_CHAR(archived_seq#),'') received_value, COALESCE(TO_CHAR(applied_seq#),'') applied_value FROM v$archive_dest_status WHERE status&lt;&gt;'INACTIVE'".replace("&lt;","<");
        default->throw new IllegalArgumentException("No standard replication query for "+type);
    };}

    static List<ServerActivity.Session> enrichMysqlWaits(JdbcSession session,List<ServerActivity.Session> sessions){
        if(sessions.isEmpty())return sessions;Map<String,String[]> waits=new HashMap<>();for(String sql:mysqlWaitSql())try{QueryResult result=session.execute(sql,5_000);Map<String,Integer> columns=index(result.columns());for(List<Object> row:result.rows()){String id=text(row,columns,"session_id");if(!id.isBlank())waits.put(id,new String[]{text(row,columns,"blocked_by"),text(row,columns,"object_name")});}if(!waits.isEmpty())break;}catch(Exception ignored){}
        if(waits.isEmpty())return sessions;List<ServerActivity.Session> out=new ArrayList<>();for(var item:sessions){String[] wait=waits.get(item.id());out.add(wait==null?item:new ServerActivity.Session(item.id(),item.user(),item.database(),item.state(),item.seconds(),item.query(),wait[0],wait[1],item.transaction(),item.ownSession()));}return List.copyOf(out);
    }
    static List<String> mysqlWaitSql(){return List.of(
        "SELECT CAST(rt.PROCESSLIST_ID AS CHAR) session_id, CAST(bt.PROCESSLIST_ID AS CHAR) blocked_by, COALESCE(rl.OBJECT_NAME,'') object_name FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks rl ON rl.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID JOIN performance_schema.data_locks bl ON bl.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID JOIN performance_schema.threads rt ON rt.THREAD_ID=rl.THREAD_ID JOIN performance_schema.threads bt ON bt.THREAD_ID=bl.THREAD_ID",
        "SELECT CAST(r.trx_mysql_thread_id AS CHAR) session_id, CAST(b.trx_mysql_thread_id AS CHAR) blocked_by, COALESCE(l.lock_table,'') object_name FROM information_schema.innodb_lock_waits w JOIN information_schema.innodb_trx r ON r.trx_id=w.requesting_trx_id JOIN information_schema.innodb_trx b ON b.trx_id=w.blocking_trx_id LEFT JOIN information_schema.innodb_locks l ON l.lock_id=w.requested_lock_id");}

    private static Replication mysql(JdbcSession session)throws Exception{QueryResult result;try{result=session.execute("SHOW REPLICA STATUS",10);}catch(Exception modern){result=session.execute("SHOW SLAVE STATUS",10);}if(result.rows().isEmpty())return new Replication(List.of(),"No se detectó una réplica configurada o la cuenta no puede verla.");Map<String,Integer> columns=index(result.columns());List<Replica> rows=new ArrayList<>();for(List<Object> row:result.rows()){String io=first(row,columns,"replica_io_running","slave_io_running"),sql=first(row,columns,"replica_sql_running","slave_sql_running"),state=("Yes".equalsIgnoreCase(io)&&"Yes".equalsIgnoreCase(sql))?"EN LÍNEA":"REVISAR · IO "+io+" · SQL "+sql;rows.add(new Replica("RÉPLICA",state,first(row,columns,"source_host","master_host"),number(first(row,columns,"seconds_behind_source","seconds_behind_master")),first(row,columns,"relay_source_log_file","relay_master_log_file"),first(row,columns,"exec_source_log_pos","exec_master_log_pos")));}return new Replication(rows,"");}
    private static Replication standard(JdbcSession session,DatabaseType type)throws Exception{QueryResult result=session.execute(replicationSql(type),1_000);Map<String,Integer> columns=index(result.columns());List<Replica> rows=new ArrayList<>();for(List<Object> row:result.rows())rows.add(new Replica(text(row,columns,"role_name"),text(row,columns,"state_name"),text(row,columns,"source_name"),number(text(row,columns,"lag_seconds")),text(row,columns,"received_value"),text(row,columns,"applied_value")));return new Replication(rows,rows.isEmpty()?"No se detectaron réplicas activas.":"");}
    private static void walk(String node,Map<String,List<String>> edges,List<String> path,Set<String> visiting,Set<String> signatures,List<Deadlock> out){int repeated=path.indexOf(node);if(repeated>=0){List<String> cycle=new ArrayList<>(path.subList(repeated,path.size()));String signature=signature(cycle);if(signatures.add(signature))out.add(new Deadlock(cycle));return;}if(!visiting.add(node))return;path.add(node);for(String next:edges.getOrDefault(node,List.of()))walk(next,edges,path,visiting,signatures,out);path.remove(path.size()-1);visiting.remove(node);}
    private static String signature(List<String> cycle){List<String> rotations=new ArrayList<>();for(int i=0;i<cycle.size();i++){StringBuilder value=new StringBuilder();for(int j=0;j<cycle.size();j++){if(j>0)value.append('>');value.append(cycle.get((i+j)%cycle.size()));}rotations.add(value.toString());}return rotations.stream().min(String::compareTo).orElse("");}
    private static Map<String,Integer> index(List<String> names){Map<String,Integer> out=new HashMap<>();for(int i=0;i<names.size();i++)out.put(names.get(i).toLowerCase(Locale.ROOT),i);return out;}
    private static String first(List<Object> row,Map<String,Integer> columns,String...names){for(String name:names){String value=text(row,columns,name);if(!value.isBlank())return value;}return "";}
    private static String text(List<Object> row,Map<String,Integer> columns,String name){Integer at=columns.get(name);return at==null||at>=row.size()?"":Objects.toString(row.get(at),"");}
    private static long number(String value){try{return Long.parseLong(Objects.toString(value,"0").split("\\.")[0]);}catch(RuntimeException error){return 0;}}
    private static String safe(Throwable error){Throwable root=error;while(root.getCause()!=null)root=root.getCause();String value=Objects.toString(root.getMessage(),root.getClass().getSimpleName()).replaceAll("(?i)(password|token|secret)[^ ]*","[oculto]");return value.length()>160?value.substring(0,160)+"…":value;}
}
