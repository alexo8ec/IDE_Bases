package com.nexodb.debug;

import com.nexodb.db.*;
import com.nexodb.model.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Real server execution with a private clone and MEMORY mailbox; two dedicated JDBC connections. */
public final class MysqlDebugSession implements AutoCloseable {
    public enum State {STARTING,RUNNING,PAUSED,COMPLETED,CANCELLED,FAILED}
    public record Value(String name,String type,String scope,String value,boolean isNull,boolean truncated){}
    public record Event(State state,int line,int point,List<Value> variables,List<QueryResult> results,String message,SqlDiagnostic diagnostic){public Event{variables=List.copyOf(variables);results=List.copyOf(results);}public Event(State state,int line,int point,List<Value> variables,List<QueryResult> results,String message){this(state,line,point,variables,results,message,null);}}
    private SqlDiagnostic failureDiagnostic;
    private final ConnectionProfile profile;private final SchemaItem item;private final StoredObjectService.Definition definition;private final MysqlProcedure program;private final Consumer<Event> listener;
    private final char[] password;private final List<Object> arguments;private final Map<String,Object> userValues;
    private final ScheduledExecutorService controlTasks=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"nexodb-debug-control");t.setDaemon(true);return t;});
    private final String prefix="__nxdbg_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
    private final String mailbox,variables,clone,pause;private final String marker=prefix+"_marker";
    private volatile List<QueryResult> collectedResults=List.of();
    private final List<String> cleanup=new ArrayList<>();
    private volatile Set<Integer> breakpoints;private volatile boolean stopped,finished;private volatile State state=State.STARTING;private volatile Statement active;
    private volatile JdbcSession control,worker;private volatile long connectionId;private volatile Event last;private volatile Throwable controlFailure;
    private long seenSequence=-1;private String sentBreakpoints="";
    public MysqlDebugSession(ConnectionProfile profile,char[] password,SchemaItem item,StoredObjectService.Definition definition,MysqlProcedure program,List<Object> arguments,Map<String,Object> userValues,Set<Integer> breakpoints,Consumer<Event> listener){
        if(!StoredObjectService.supported(profile.type())||profile.readOnly())throw new IllegalArgumentException("La depuración requiere MySQL/MariaDB con un perfil que permita cambios.");
        if(arguments.size()!=program.parameters().size())throw new IllegalArgumentException("Faltan parámetros del procedimiento.");
        this.profile=profile;this.password=password.clone();this.item=item;this.definition=definition;this.program=program;this.arguments=new ArrayList<>(arguments);this.userValues=new LinkedHashMap<>(userValues);this.listener=listener;
        String ns=StoredObjectService.quote(StoredObjectService.namespace(item))+".";mailbox=ns+StoredObjectService.quote(prefix+"_c");variables=ns+StoredObjectService.quote(prefix+"_v");clone=ns+StoredObjectService.quote(prefix+"_p");pause=ns+StoredObjectService.quote(prefix+"_s");setBreakpoints(breakpoints);
        // Preflight syntax/size checks happen before opening connections or creating helpers.
        program.instrument(clone,pause);
    }
    public String helperPrefix(){return prefix;}public State state(){return state;}public boolean finished(){return finished;}public Event last(){return last;}
    public void start(){Thread thread=new Thread(this::run,"nexodb-debug-worker");thread.setDaemon(true);thread.start();}
    public void setBreakpoints(Set<Integer> lines){if(!program.executableLines().containsAll(lines))throw new IllegalArgumentException("Hay puntos de quiebre en líneas que no son ejecutables.");breakpoints=Set.copyOf(lines);}
    private String breakpointString(){String result=String.join(",",breakpoints.stream().sorted().map(Object::toString).toList());if(result.length()>16000)throw new IllegalArgumentException("Demasiados puntos de quiebre.");return result;}
    public void step(){resume("STEP");}public void resume(){resume("CONTINUE");}
    private void resume(String mode){if(state!=State.PAUSED||finished||controlTasks.isShutdown())return;controlTasks.execute(()->{try{if(finished)return;try(PreparedStatement s=control.connection().prepareStatement("UPDATE "+mailbox+" SET status='RUNNING',mode=?,bps=?,lease=UNIX_TIMESTAMP() WHERE id=1 AND status='PAUSED'")){s.setString(1,mode);s.setString(2,breakpointString());s.executeUpdate();}publish(State.RUNNING,last==null?0:last.line(),last==null?0:last.point(),last==null?List.of():last.variables(),List.of(),"Ejecutando…");}catch(Exception error){controlFailure=error;Statement statement=active;if(statement!=null)try{statement.cancel();}catch(SQLException ignored){}}});}
    public void stop(){if(finished||controlTasks.isShutdown())return;stopped=true;controlTasks.execute(()->{try{if(control!=null&&connectionId>0)StoredObjectService.exec(control.connection(),"KILL CONNECTION "+connectionId);}catch(Exception failure){Statement statement=active;if(statement!=null)try{statement.cancel();}catch(SQLException ignored){}}});}
    private void run(){List<QueryResult> results=new ArrayList<>();Throwable failure=null;
        try{
            publish(State.STARTING,0,0,List.of(),List.of(),"Abriendo conexiones de depuración…");
            control=JdbcSession.open(profile,password);worker=JdbcSession.open(workerProfile(profile),password);Arrays.fill(password,'\0');
            control.connection().setCatalog(StoredObjectService.namespace(item));worker.connection().setCatalog(StoredObjectService.namespace(item));control.setReadOnly(false);worker.setReadOnly(false);
            if(!StoredObjectService.read(control,item).hash().equals(definition.hash()))throw new SQLException("El procedimiento cambió desde que se abrió el depurador. Vuelve a abrirlo.");
            connectionId=Long.parseLong(StoredObjectService.scalar(worker.connection(),"SELECT CONNECTION_ID()"));
            create("TABLE",mailbox,"CREATE TABLE "+mailbox+"(id INT PRIMARY KEY,seq BIGINT NOT NULL DEFAULT 0,point_id INT NOT NULL DEFAULT 0,line_no INT NOT NULL DEFAULT 0,status VARCHAR(16) CHARACTER SET ascii NOT NULL,mode VARCHAR(16) CHARACTER SET ascii NOT NULL,bps VARCHAR(16384) CHARACTER SET ascii NOT NULL,lease BIGINT NOT NULL) ENGINE=MEMORY");
            create("TABLE",variables,"CREATE TABLE "+variables+"(name VARCHAR(256) COLLATE utf8mb4_bin PRIMARY KEY,val VARCHAR(4096),is_null TINYINT NOT NULL,truncated TINYINT NOT NULL) ENGINE=MEMORY DEFAULT CHARSET=utf8mb4");
            sentBreakpoints=breakpointString();try(PreparedStatement s=control.connection().prepareStatement("INSERT INTO "+mailbox+"(id,status,mode,bps,lease) VALUES(1,'RUNNING','STEP',?,UNIX_TIMESTAMP())")){s.setString(1,sentBreakpoints);s.executeUpdate();}
            create("PROCEDURE",pause,pauseSql());StoredObjectService.setMode(control.connection(),definition.sqlMode());
            create("PROCEDURE",clone,program.instrument(clone,pause));StoredObjectService.setMode(worker.connection(),definition.sqlMode());
            List<String> args=new ArrayList<>();for(int i=0;i<arguments.size();i++){String variable="@"+StoredObjectService.quote(prefix+"_a"+i);args.add(variable);bind(worker.connection(),variable,arguments.get(i));}
            for(var value:userValues.entrySet()){if(program.sessionVariables().stream().noneMatch(v->v.name().equals(value.getKey())))throw new IllegalArgumentException("Variable de sesión no reconocida.");bind(worker.connection(),"@"+StoredObjectService.quote(value.getKey().substring(1)),value.getValue());}
            if(stopped)throw new CancellationException();publish(State.RUNNING,0,0,List.of(),List.of(),"Ejecutando copia temporal…");controlTasks.scheduleWithFixedDelay(this::poll,0,100,TimeUnit.MILLISECONDS);
            try(Statement statement=worker.connection().createStatement()){
                // A single-row streaming fetch lets the controller display SELECT results while CALL is paused.
                active=statement;statement.setFetchSize(worker.connection().getMetaData().getDriverName().toLowerCase(Locale.ROOT).contains("mysql")?Integer.MIN_VALUE:1);boolean has=statement.execute("CALL "+clone+"("+String.join(",",args)+")");
                do{
                    if(has)try(ResultSet rows=statement.getResultSet()){
                        if(rows.getMetaData().getColumnCount()==1&&marker.equals(rows.getMetaData().getColumnLabel(1))){while(rows.next()){} }
                        else{
                            QueryResult result=QueryResult.from(rows,10000);
                            // Drain before close/getMoreResults: Connector/J can otherwise lose the next result.
                            if(result.truncated())while(rows.next()){}
                            if(results.size()>=100)throw new SQLException("La depuración alcanzó el límite de 100 conjuntos de resultados.");
                            results.add(result);resultsChanged(results);
                        }
                    }else if(statement.getUpdateCount()==-1)break;
                    has=statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                }while(true);
            }finally{active=null;}
            if(!args.isEmpty())results.add(worker.execute("SELECT "+String.join(",",args),1));
        }catch(Throwable error){failure=error;}
        finally{
            Arrays.fill(password,'\0');controlTasks.shutdown();try{controlTasks.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            List<Value> context=last==null?List.of():last.variables();int line=last==null?0:last.line(),point=last==null?0:last.point();
            try{if(control!=null&&control.isOpen()){try(Statement s=control.connection().createStatement();ResultSet r=s.executeQuery("SELECT point_id,line_no FROM "+mailbox+" WHERE id=1")){if(r.next()){point=r.getInt(1);line=r.getInt(2);}}context=snapshot(point);}}catch(Exception ignored){}
            if(worker!=null)try{worker.close();}catch(Exception ignored){}
            List<String> failedCleanup=new ArrayList<>();
            // Reverse dependency order: clone, helper, snapshot, mailbox.
            if(control!=null)for(int i=cleanup.size()-1;i>=0;i--)try(Statement s=control.connection().createStatement()){s.setQueryTimeout(5);s.execute(cleanup.get(i));}catch(Exception error){failedCleanup.add(cleanup.get(i));}
            if(control!=null)try{control.close();}catch(Exception ignored){}
            if(failure==null)failure=controlFailure;if(failure!=null&&!stopped){var detail=SqlDiagnostic.analyze(failure,program.source(),program.source(),0);failureDiagnostic=new SqlDiagnostic(Math.max(1,line),1,false,detail.explanation(),"Última instrucción alcanzada en el SP original. "+detail.suggestion(),detail.original(),detail.code(),detail.sqlState());}finished=true;String message=stopped?"Depuración detenida.":failure==null?"Procedimiento terminado.":failureDiagnostic.display();if(!failedCleanup.isEmpty())message+=" Objetos auxiliares pendientes de limpieza: "+String.join("; ",failedCleanup);
            publish(stopped?State.CANCELLED:failure==null?State.COMPLETED:State.FAILED,line,point,context,results,message);
        }
    }
    private void create(String kind,String name,String sql)throws SQLException{StoredObjectService.exec(control.connection(),sql);cleanup.add("DROP "+kind+" "+name);}
    private static ConnectionProfile workerProfile(ConnectionProfile profile){
        if(profile.type()!=DatabaseType.MYSQL)return profile;
        // Connector/J restores net_write_timeout on ResultSet.close(). A streamed CALL may
        // already have its next result open, making that automatic SET fail with S1000.
        // Disable only this driver's automatic timeout rewrite on the dedicated worker.
        String url=profile.jdbcUrl();int query=url.indexOf('?');String base=query<0?url:url.substring(0,query);List<String> options=new ArrayList<>();
        if(query>=0)for(String option:url.substring(query+1).split("&"))if(!option.isBlank()&&!option.split("=",2)[0].equalsIgnoreCase("netTimeoutForStreamingResults"))options.add(option);
        options.add("netTimeoutForStreamingResults=0");
        return new ConnectionProfile(profile.id(),profile.name(),profile.type(),profile.host(),profile.port(),profile.database(),profile.username(),base+"?"+String.join("&",options),profile.driverClass(),profile.driverJar(),profile.authenticationMode(),profile.readOnly());
    }
    private static void bind(Connection connection,String variable,Object value)throws SQLException{try(PreparedStatement s=connection.prepareStatement("SET "+variable+"=?")){s.setObject(1,value);s.execute();}}
    private String pauseSql(){int finalPoint=program.points().get(program.points().size()-1).id();return "CREATE PROCEDURE "+pause+"(IN nx_point INT,IN nx_line INT,IN nx_context LONGTEXT) SQL SECURITY INVOKER\nBEGIN\n"
            +" DECLARE nx_mode VARCHAR(16); DECLARE nx_bps LONGTEXT; DECLARE nx_status VARCHAR(16); DECLARE nx_lease BIGINT; DECLARE nx_keys LONGTEXT; DECLARE nx_key VARCHAR(256); DECLARE nx_path LONGTEXT; DECLARE nx_i INT DEFAULT 0; DECLARE nx_stop BOOL DEFAULT FALSE;\n"
            +" SELECT mode,bps INTO nx_mode,nx_bps FROM "+mailbox+" WHERE id=1; SET nx_stop=(nx_mode='STEP' OR FIND_IN_SET(nx_line,nx_bps)>0);\n"
            +" BEGIN\n DELETE FROM "+variables+"; SET nx_keys=JSON_KEYS(nx_context);\n"
            +" WHILE nx_i<JSON_LENGTH(nx_keys) DO SET nx_key=JSON_UNQUOTE(JSON_EXTRACT(nx_keys,CONCAT('$[',nx_i,']'))); SET nx_path=CONCAT('$.',JSON_QUOTE(nx_key));\n"
            +" INSERT INTO "+variables+"(name,val,is_null,truncated) VALUES(nx_key,JSON_UNQUOTE(JSON_EXTRACT(nx_context,CONCAT(nx_path,'.v'))),JSON_EXTRACT(nx_context,CONCAT(nx_path,'.n'))=true,JSON_EXTRACT(nx_context,CONCAT(nx_path,'.t'))=true); SET nx_i=nx_i+1; END WHILE;\n"
            // This private result boundary flushes completed SELECT output before waiting. It is filtered on the worker.
            +" IF nx_stop THEN SELECT nx_point AS "+StoredObjectService.quote(marker)+"; END IF;\n"
            +" UPDATE "+mailbox+" SET seq=seq+IF(nx_stop,1,0),point_id=nx_point,line_no=nx_line,status=IF(nx_stop,'PAUSED','RUNNING') WHERE id=1;\n"
            +" WHILE nx_stop DO SELECT status,lease INTO nx_status,nx_lease FROM "+mailbox+" WHERE id=1; IF nx_status<>'PAUSED' THEN SET nx_stop=FALSE; ELSEIF UNIX_TIMESTAMP()-nx_lease>20 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='El controlador de depuración dejó de responder'; ELSE DO SLEEP(0.05); END IF; END WHILE;\n END;\nEND";}
    private void poll(){if(finished||control==null||stopped||controlFailure!=null)return;try{
        String bps=breakpointString();try(PreparedStatement s=control.connection().prepareStatement("UPDATE "+mailbox+" SET lease=UNIX_TIMESTAMP(),bps=? WHERE id=1")){s.setString(1,bps);s.executeUpdate();}sentBreakpoints=bps;
        try(Statement s=control.connection().createStatement();ResultSet r=s.executeQuery("SELECT seq,point_id,line_no,status FROM "+mailbox+" WHERE id=1")){if(r.next()){long sequence=r.getLong(1);int point=r.getInt(2),line=r.getInt(3);if(sequence!=seenSequence&&r.getString(4).equals("PAUSED")){seenSequence=sequence;publish(State.PAUSED,line,point,snapshot(point),List.of(),"Pausado antes de ejecutar línea "+line);}}}
    }catch(Exception error){if(!finished){controlFailure=error;Statement statement=active;if(statement!=null)try{statement.cancel();}catch(SQLException ignored){}}}}
    private List<Value> snapshot(int point)throws SQLException{Map<String,MysqlProcedure.Variable> visible=new LinkedHashMap<>();program.points().stream().filter(p->p.id()==point).findFirst().ifPresent(p->p.variables().forEach(v->visible.put(v.name(),v)));List<Value> values=new ArrayList<>();try(Statement s=control.connection().createStatement();ResultSet r=s.executeQuery("SELECT name,val,is_null,truncated FROM "+variables+" ORDER BY name")){while(r.next()){String name=r.getString(1);var metadata=visible.get(name);values.add(new Value(name,metadata==null?"":metadata.type(),metadata==null?"":metadata.scope(),r.getString(2),r.getBoolean(3),r.getBoolean(4)));}}return values;}
    private synchronized void resultsChanged(List<QueryResult> results){collectedResults=List.copyOf(results);if(last!=null)publish(state,last.line(),last.point(),last.variables(),collectedResults,last.message());}
    private synchronized void publish(State state,int line,int point,List<Value> values,List<QueryResult> results,String message){this.state=state;Event event=new Event(state,line,point,values,results.isEmpty()?collectedResults:results,message,state==State.FAILED?failureDiagnostic:null);last=event;try{listener.accept(event);}catch(RuntimeException ignored){}}
    @Override public void close(){if(!finished)stop();else controlTasks.shutdownNow();Arrays.fill(password,'\0');}
}
