package com.nexodb.db;

import com.nexodb.debug.*;
import com.nexodb.model.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Uses only the explicitly enabled, dedicated local MariaDB fixture. */
public final class StoredProgramIntegrationTest {
    public static ConnectionProfile profile(String database){return new ConnectionProfile("stored-program-test","Isolated debugger test",DatabaseType.MARIADB,"127.0.0.1",43189,database,"root","jdbc:mariadb://127.0.0.1:43189/"+database+"?sslMode=disable",DatabaseType.MARIADB.driverClass(),Path.of("build/drivers/mariadb-java-client-4.0.0.jar").toAbsolutePath().toString(),false);}
    public static final String BODY="""
            CREATE PROCEDURE debug_calc(IN p_limit INT, OUT p_result INT)
            BEGIN
                DECLARE v_i INT DEFAULT 0;
                DECLARE v_total INT DEFAULT 0;
                SET p_result = 0;
                WHILE v_i < p_limit DO
                    SET v_i = v_i + 1;
                    SET v_total = v_total + v_i;
                END WHILE;
                BEGIN
                    DECLARE v_i INT DEFAULT 99;
                    SET v_total = v_total + v_i;
                END;
                SET p_result = v_total;
                INSERT INTO debug_target VALUES(p_result);
                SELECT p_result AS resultado;
            END
            """;
    public static void main(String[] args)throws Exception{
        if(!Boolean.getBoolean("nexodb.test.storedPrograms"))throw new IllegalStateException("Dedicated local test switch required");
        try(JdbcSession session=JdbcSession.open(profile(""),new char[0])){
            equal(Path.of("build/stored-program-test-data").toRealPath(),Path.of(StoredObjectService.scalar(session.connection(),"SELECT @@datadir")).toRealPath());
            exec(session,"CREATE DATABASE IF NOT EXISTS nexodb_stored_test CHARACTER SET utf8mb4");session.connection().setCatalog("nexodb_stored_test");session.setReadOnly(false);
            exec(session,"DROP PROCEDURE IF EXISTS debug_calc");exec(session,"DROP TABLE IF EXISTS debug_target");exec(session,"CREATE TABLE debug_target(result INT) ENGINE=InnoDB");exec(session,BODY);
            SchemaItem item=new SchemaItem(SchemaItem.Kind.PROCEDURE,"nexodb_stored_test",null,"debug_calc","");var definition=StoredObjectService.read(session,item);var program=MysqlProcedure.compile(definition);
            int loop=line(program.source(),"SET v_total = v_total + v_i;",0),inner=line(program.source(),"SET v_total = v_total + v_i;",1),insert=line(program.source(),"INSERT INTO debug_target",0);
            BlockingQueue<MysqlDebugSession.Event> events=new LinkedBlockingQueue<>();var debugger=new MysqlDebugSession(profile("nexodb_stored_test"),new char[0],item,definition,program,Arrays.asList("3",null),Map.of(),Set.of(loop,inner,insert),events::add);debugger.start();
            var first=paused(events);equal("0",value(first,"v_i"));equal("3",value(first,"p_limit"));equal(true,first.variables().stream().filter(v->v.name().equals("p_result")).findFirst().orElseThrow().isNull());debugger.resume();
            for(int i=1;i<=3;i++){var point=paused(events);equal(loop,point.line());equal(""+i,value(point,"v_i"));debugger.resume();}
            var nested=paused(events);equal(inner,nested.line());equal("99",value(nested,"v_i"));debugger.resume();
            var beforeWrite=paused(events);equal(insert,beforeWrite.line());equal("3",value(beforeWrite,"v_i"));equal("105",value(beforeWrite,"p_result"));equal("0",StoredObjectService.scalar(session.connection(),"SELECT COUNT(*) FROM debug_target"));
            debugger.step();var afterWrite=paused(events);equal("1",StoredObjectService.scalar(session.connection(),"SELECT COUNT(*) FROM debug_target"));debugger.resume();var complete=finished(events);equal(MysqlDebugSession.State.COMPLETED,complete.state());equal("105",complete.results().get(0).rows().get(0).get(0).toString());assertClean(session,debugger.helperPrefix());equal(definition.hash(),StoredObjectService.read(session,item).hash());
            events.clear();var cancelled=new MysqlDebugSession(profile("nexodb_stored_test"),new char[0],item,definition,program,Arrays.asList("1",null),Map.of(),Set.of(insert),events::add);cancelled.start();paused(events);cancelled.resume();equal(insert,paused(events).line());cancelled.stop();equal(MysqlDebugSession.State.CANCELLED,finished(events).state());equal("1",StoredObjectService.scalar(session.connection(),"SELECT COUNT(*) FROM debug_target"));assertClean(session,cancelled.helperPrefix());
            for(String unsupported:List.of("CREATE PROCEDURE p() BEGIN SELECT ROW_COUNT(); END","CREATE PROCEDURE p() BEGIN PREPARE x FROM 'SELECT 1'; END","CREATE PROCEDURE p() BEGIN DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @x=1; END"))try{MysqlProcedure.compile(new StoredObjectService.Definition(unsupported,"","",""));throw new AssertionError("Unsupported semantics silently accepted");}catch(IllegalArgumentException expected){}
            lineByLine(session);
            cursor(session);
            controlFlow(session);
            triggerOrder(session);
            ddl(session);
        }
        System.out.println("StoredProgramIntegrationTest: OK (real MariaDB pause/step/continue, loop hits, shadowed locals, OUT parameters, NULL, no write before breakpoint, cancellation, original unchanged, helper cleanup and all object DDL)");
    }
    private static void lineByLine(JdbcSession session)throws Exception{
        exec(session,"DROP PROCEDURE IF EXISTS debug_inspect");exec(session,DebugExample.sql("`nexodb_stored_test`.`debug_inspect`"));
        var item=new SchemaItem(SchemaItem.Kind.PROCEDURE,"nexodb_stored_test",null,"debug_inspect","");var def=StoredObjectService.read(session,item);var program=MysqlProcedure.compile(def);var events=new LinkedBlockingQueue<MysqlDebugSession.Event>();var debug=new MysqlDebugSession(profile("nexodb_stored_test"),new char[0],item,def,program,List.of(),Map.of(),Set.of(),events::add);debug.start();
        try{
            var event=paused(events);equal(true,event.variables().stream().filter(v->v.name().equals("v_base")).findFirst().orElseThrow().isNull());
            debug.step();event=paused(events);equal("125.50",value(event,"v_base"));equal(true,event.variables().stream().filter(v->v.name().equals("v_copia")).findFirst().orElseThrow().isNull());equal(line(program.source(),"SET v_copia = v_base",0),event.line());
            debug.step();event=paused(events);equal("125.50",value(event,"v_copia"));
            boolean json=false,streamed=false;int steps=0;
            while(event.point()!=program.points().get(program.points().size()-1).id()){
                if(++steps>30)throw new AssertionError("Example did not step to END");
                if(event.line()==line(program.source(),"SET v_total = v_total + 10",0)){
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(debug.last().results().isEmpty()&&System.nanoTime()<deadline)Thread.sleep(20);
                    equal(MysqlDebugSession.State.PAUSED,debug.state());equal(2,debug.last().results().get(0).rows().size());equal("Ana",debug.last().results().get(0).rows().get(0).get(1));streamed=true;
                }
                var v=event.variables().stream().filter(x->x.name().equals("v_filas")).findFirst().orElseThrow();if(!v.isNull()){QueryResult table=DebugInspection.table(v);equal(2,table.rows().size());equal(List.of("id","cliente","importe"),table.columns());json=true;}
                debug.step();event=paused(events);
            }
            equal(true,json);equal(true,streamed);debug.resume();var done=finished(events);equal(MysqlDebugSession.State.COMPLETED,done.state());equal(2,done.results().size());equal("261.00",done.results().get(1).rows().get(0).get(2).toString());assertClean(session,debug.helperPrefix());
        }finally{if(!debug.finished()){debug.stop();finished(events);}}
        System.out.println("Line-by-line sample: OK (SELECT INTO, NULL before assignment, copied value, JSON table and real SELECT grid while paused)");
    }
    private static void controlFlow(JdbcSession session)throws Exception{
        exec(session,"DROP PROCEDURE IF EXISTS debug_flow");exec(session,"CREATE PROCEDURE debug_flow(INOUT p INT) BEGIN DECLARE k INT DEFAULT 0; DECLARE txt VARCHAR(100) DEFAULT 'mañana;日本語'; DECLARE raw_value VARBINARY(8) DEFAULT x'00FFA1'; DECLARE long_value TEXT DEFAULT REPEAT('ñ',5000); REPEAT SET k=k+1; UNTIL k=2 END REPEAT; CASE p WHEN 1 THEN SET p=3; WHEN 2 THEN SET p=4; ELSE SET p=5; END CASE; IF p=3 THEN SET @debug_session='updated'; ELSEIF p=4 THEN SET @debug_session='branch2'; ELSE SET @debug_session='other'; END IF; read_loop: LOOP SET p=p+1; IF p>=5 THEN LEAVE read_loop; END IF; END LOOP read_loop; SELECT p,k,txt,HEX(raw_value); END");
        var item=new SchemaItem(SchemaItem.Kind.PROCEDURE,"nexodb_stored_test",null,"debug_flow","");var def=StoredObjectService.read(session,item);var program=MysqlProcedure.compile(def);var events=new LinkedBlockingQueue<MysqlDebugSession.Event>();var debug=new MysqlDebugSession(profile("nexodb_stored_test"),new char[0],item,def,program,List.of("1"),Map.of("@debug_session","initial"),Set.of(),events::add);debug.start();var first=paused(events);equal("initial",value(first,"@debug_session"));equal("mañana;日本語",value(first,"txt"));equal("0x00FFA1",value(first,"raw_value"));equal(true,first.variables().stream().filter(v->v.name().equals("long_value")).findFirst().orElseThrow().truncated());debug.resume();var complete=finished(events);equal(MysqlDebugSession.State.COMPLETED,complete.state());equal("5",complete.results().get(0).rows().get(0).get(0).toString());equal("2",complete.results().get(0).rows().get(0).get(1).toString());equal("updated",value(complete,"@debug_session"));assertClean(session,debug.helperPrefix());
        exec(session,"DROP PROCEDURE IF EXISTS debug_error");exec(session,"CREATE PROCEDURE debug_error() BEGIN\n DECLARE x INT DEFAULT 7;\n SET x=9;\n SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='expected fixture error';\n END");item=new SchemaItem(SchemaItem.Kind.PROCEDURE,"nexodb_stored_test",null,"debug_error","");def=StoredObjectService.read(session,item);program=MysqlProcedure.compile(def);events.clear();debug=new MysqlDebugSession(profile("nexodb_stored_test"),new char[0],item,def,program,List.of(),Map.of(),Set.of(),events::add);debug.start();paused(events);debug.resume();var failed=finished(events);equal(MysqlDebugSession.State.FAILED,failed.state());if(!failed.message().contains("expected fixture error"))throw new AssertionError(failed.message());equal("9",value(failed,"x"));equal(line(program.source(),"SIGNAL SQLSTATE",0),failed.line());equal(failed.line(),failed.diagnostic().line());equal("45000",failed.diagnostic().sqlState());assertClean(session,debug.helperPrefix());
    }
    private static void triggerOrder(JdbcSession s)throws Exception{
        exec(s,"DROP TABLE IF EXISTS trigger_order_fixture");exec(s,"CREATE TABLE trigger_order_fixture(id INT)");for(String name:List.of("a","b","c"))exec(s,"CREATE TRIGGER order_"+name+" BEFORE INSERT ON trigger_order_fixture FOR EACH ROW SET @debug_order=CONCAT(COALESCE(@debug_order,''),'"+name.toUpperCase(Locale.ROOT)+"')");
        var item=new SchemaItem(SchemaItem.Kind.TRIGGER,"nexodb_stored_test",null,"order_b","");var def=StoredObjectService.read(s,item);StoredObjectService.save(s,item,def.sql().replace("'B'","'X'"),def);exec(s,"SET @debug_order=''");exec(s,"INSERT INTO trigger_order_fixture VALUES(1)");equal("AXC",StoredObjectService.scalar(s.connection(),"SELECT @debug_order"));
        def=StoredObjectService.read(s,item);StoredObjectService.save(s,item,def.sql().replace("BEFORE INSERT","AFTER INSERT"),def);exec(s,"SET @debug_order=''");exec(s,"INSERT INTO trigger_order_fixture VALUES(2)");equal("ACX",StoredObjectService.scalar(s.connection(),"SELECT @debug_order"));exec(s,"DROP TABLE trigger_order_fixture");
    }
    private static void cursor(JdbcSession session)throws Exception{
        exec(session,"DROP PROCEDURE IF EXISTS debug_cursor");exec(session,"CREATE PROCEDURE debug_cursor() BEGIN DECLARE done BOOL DEFAULT FALSE; DECLARE x INT; DECLARE total INT DEFAULT 0; DECLARE c CURSOR FOR SELECT result FROM debug_target; DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=TRUE; OPEN c; read_loop: LOOP FETCH c INTO x; IF done THEN LEAVE read_loop; END IF; SET total=total+x; END LOOP read_loop; CLOSE c; SELECT total; END");
        var item=new SchemaItem(SchemaItem.Kind.PROCEDURE,"nexodb_stored_test",null,"debug_cursor","");var def=StoredObjectService.read(session,item);var program=MysqlProcedure.compile(def);var events=new LinkedBlockingQueue<MysqlDebugSession.Event>();var debug=new MysqlDebugSession(profile("nexodb_stored_test"),new char[0],item,def,program,List.of(),Map.of(),Set.of(),events::add);debug.start();paused(events);debug.resume();var event=finished(events);equal(MysqlDebugSession.State.COMPLETED,event.state());equal("105",event.results().get(0).rows().get(0).get(0).toString());assertClean(session,debug.helperPrefix());
    }
    private static void ddl(JdbcSession s)throws Exception{
        for(SchemaItem.Kind kind:List.of(SchemaItem.Kind.VIEW,SchemaItem.Kind.PROCEDURE,SchemaItem.Kind.FUNCTION,SchemaItem.Kind.TRIGGER,SchemaItem.Kind.EVENT)){
            String name="object_"+kind.name().toLowerCase(Locale.ROOT);SchemaItem item=new SchemaItem(kind,"nexodb_stored_test",null,name,"");exec(s,"DROP "+kind+" IF EXISTS "+StoredObjectService.qualified(item));String sql=StoredObjectService.template(item);
            if(kind==SchemaItem.Kind.TRIGGER)sql=sql.replace("`tabla_destino`","`debug_target`");StoredObjectService.save(s,item,sql,null);var original=StoredObjectService.read(s,item);if(original.sql().isBlank())throw new AssertionError("Missing DDL: "+kind);
            String edit=kind==SchemaItem.Kind.VIEW?StoredObjectService.template(item).replace("SELECT 1","SELECT 2"):original.sql();StoredObjectService.save(s,item,edit,original);
            if(kind==SchemaItem.Kind.VIEW){equal("2",StoredObjectService.scalar(s.connection(),"SELECT id FROM "+StoredObjectService.qualified(item)));StoredObjectService.apply(s,StoredObjectService.renameSql(item,name+"_renamed"));item=new SchemaItem(kind,item.catalog(),null,name+"_renamed","");}
            if(kind==SchemaItem.Kind.EVENT){equal("DISABLED",StoredObjectService.scalar(s.connection(),"SELECT STATUS FROM information_schema.EVENTS WHERE EVENT_SCHEMA='nexodb_stored_test' AND EVENT_NAME='"+name+"'"));StoredObjectService.apply(s,StoredObjectService.renameSql(item,name+"_renamed"));item=new SchemaItem(kind,item.catalog(),null,name+"_renamed","");}
            StoredObjectService.apply(s,StoredObjectService.dropSql(item));
        }
        var proc=new SchemaItem(SchemaItem.Kind.PROCEDURE,"nexodb_stored_test",null,"debug_calc","");var original=StoredObjectService.read(s,proc);var pasted=StoredProgramScript.body(proc,original.sql()+"$$\nDELIMITER ;");if(pasted.sql().endsWith("$$"))throw new AssertionError("Client delimiter leaked into server DDL");StoredObjectService.save(s,proc,pasted.sql(),original);equal(original.hash(),StoredObjectService.read(s,proc).hash());try{StoredObjectService.save(s,proc,original.sql().replace("SET p_result = 0","THIS IS NOT VALID SQL"),original);throw new AssertionError("Invalid body accepted");}catch(SQLException expected){}equal(original.hash(),StoredObjectService.read(s,proc).hash());
        s.setAutoCommit(false);try{StoredObjectService.apply(s,StoredObjectService.dropSql(proc));throw new AssertionError("Implicit transaction commit allowed");}catch(SQLException expected){}finally{s.rollback();s.setAutoCommit(true);}
    }
    private static int line(String sql,String part,int occurrence){int at=-1;for(int i=0;i<=occurrence;i++)at=sql.indexOf(part,at+1);if(at<0)throw new AssertionError(part);return 1+(int)sql.substring(0,at).chars().filter(c->c=='\n').count();}
    private static String value(MysqlDebugSession.Event event,String name){return event.variables().stream().filter(v->v.name().equals(name)).findFirst().orElseThrow(()->new AssertionError("Missing variable "+name+" at "+event)).value();}
    private static MysqlDebugSession.Event paused(BlockingQueue<MysqlDebugSession.Event> events)throws Exception{for(;;){var e=events.poll(20,TimeUnit.SECONDS);if(e==null)throw new AssertionError("No real breakpoint event");if(e.state()==MysqlDebugSession.State.PAUSED)return e;if(e.state()==MysqlDebugSession.State.FAILED||e.state()==MysqlDebugSession.State.CANCELLED||e.state()==MysqlDebugSession.State.COMPLETED)throw new AssertionError(e.message());}}
    private static MysqlDebugSession.Event finished(BlockingQueue<MysqlDebugSession.Event> events)throws Exception{for(;;){var e=events.poll(20,TimeUnit.SECONDS);if(e==null)throw new AssertionError("Debugger did not terminate");if(Set.of(MysqlDebugSession.State.COMPLETED,MysqlDebugSession.State.CANCELLED,MysqlDebugSession.State.FAILED).contains(e.state()))return e;}}
    private static void assertClean(JdbcSession session,String prefix)throws SQLException{equal("0",StoredObjectService.scalar(session.connection(),"SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='nexodb_stored_test' AND ROUTINE_NAME LIKE '"+prefix+"%'"));equal("0",StoredObjectService.scalar(session.connection(),"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='nexodb_stored_test' AND TABLE_NAME LIKE '"+prefix+"%'"));}
    private static void exec(JdbcSession s,String sql)throws SQLException{StoredObjectService.exec(s.connection(),sql);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
