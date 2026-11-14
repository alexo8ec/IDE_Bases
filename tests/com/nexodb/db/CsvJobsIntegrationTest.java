package com.nexodb.db;

import com.nexodb.model.*;
import com.nexodb.util.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class CsvJobsIntegrationTest {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("nexodb-csv-jobs-");Path driver=Path.of("build/h2-test.jar").toAbsolutePath();ConnectionProfile profile=new ConnectionProfile("jobs-test","Test",DatabaseType.H2,"",0,"mem:jobs","sa","jdbc:h2:mem:jobs;DB_CLOSE_DELAY=-1",SharedH2Driver.class.getName(),driver.toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE people(id INT PRIMARY KEY,name VARCHAR(300),amount DECIMAL(20,8),note VARCHAR(200))",10);
            StringBuilder csv=new StringBuilder("\uFEFFid,name,amount,note\r\n");for(int i=1;i<=1205;i++)csv.append(i).append(",\"Mañana 😀, \"\"dato\"\"\nsegunda línea\",123456789.12345678,").append(i==1?"\\N":"").append("\r\n");Path file=root.resolve("people.csv");Files.writeString(file,csv);
            var preview=CsvImportService.preview(file,',',true);equal(4,preview.headers().size());equal(20,preview.rows().size());equal("Mañana 😀, \"dato\"\nsegunda línea",preview.rows().get(0).get(1));
            var options=new CsvImportService.Options("PUBLIC.PEOPLE",List.of(new CsvImportService.Mapping(0,new CsvImportService.Column("ID",Types.INTEGER)),new CsvImportService.Mapping(1,new CsvImportService.Column("NAME",Types.VARCHAR)),new CsvImportService.Mapping(2,new CsvImportService.Column("AMOUNT",Types.DECIMAL)),new CsvImportService.Mapping(3,new CsvImportService.Column("NOTE",Types.VARCHAR))),"\\N",false);
            equal(1205L,CsvImportService.importRows(session.connection(),preview,options,new SqlTransferControl(),n->{}));equal(null,session.execute("SELECT note FROM people WHERE id=1",10).rows().get(0).get(0));equal("",session.execute("SELECT note FROM people WHERE id=2",10).rows().get(0).get(0));equal("123456789.12345678",session.execute("SELECT amount FROM people WHERE id=1",10).rows().get(0).get(0).toString());
            Path bad=root.resolve("bad.csv");StringBuilder broken=new StringBuilder("id,name,amount,note\n");for(int i=2000;i<2240;i++)broken.append(i).append(",name,").append(i==2239?"bad":"1.25").append(",text\n");Files.writeString(bad,broken);
            try{CsvImportService.importRows(session.connection(),CsvImportService.preview(bad,',',true),options,new SqlTransferControl(),n->{});throw new AssertionError("Invalid number accepted");}catch(SQLException expected){}equal(1205,session.execute("SELECT * FROM people",2000).rows().size());
            Path cancel=root.resolve("cancel.csv");Files.writeString(cancel,broken.toString().replace("bad","2.00"));SqlTransferControl token=new SqlTransferControl();try{CsvImportService.importRows(session.connection(),CsvImportService.preview(cancel,',',true),options,token,n->token.cancel());throw new AssertionError("Cancellation ignored");}catch(java.util.concurrent.CancellationException expected){}equal(1205,session.execute("SELECT * FROM people",2000).rows().size());
            Files.writeString(file,"id,name,amount,note\n9,changed,1,x\n");try{CsvImportService.importRows(session.connection(),preview,options,new SqlTransferControl(),n->{});throw new AssertionError("Changed CSV accepted");}catch(java.io.IOException expected){}
            Path script=root.resolve("report.sql"),store=root.resolve("jobs.properties"),out=root.resolve("output");Files.writeString(script,"SELECT id,name,amount FROM people ORDER BY id;");
            AtomicReference<ConnectionProfile> selected=new AtomicReference<>(profile);String id="report-job";
            try(ScheduledTasks engine=new ScheduledTasks(store,p->selected.get(),p->new char[0],m->{},false)){
                engine.put(task(id,ScheduledTasks.Kind.REPORT,script,out,profile));equal(true,engine.runNow(id));equal(false,engine.runNow(id));await(engine,id);equal(true,engine.state(id).status().contains("1205"));
                try(var paths=Files.list(out)){Path html=paths.filter(p->p.toString().endsWith(".html")).findFirst().orElseThrow();String content=Files.readString(html);equal(true,content.contains("Mañana 😀"));equal(true,content.contains("1205"));}
                Files.writeString(script,"DELETE FROM people;");equal(true,engine.runNow(id));await(engine,id);equal(true,engine.state(id).status().contains("cambió"));equal(1205,session.execute("SELECT * FROM people",2000).rows().size());
                engine.put(task("wrong-report",ScheduledTasks.Kind.REPORT,script,out,profile));engine.runNow("wrong-report");await(engine,"wrong-report");equal(true,engine.state("wrong-report").status().contains("SELECT"));equal(1205,session.execute("SELECT * FROM people",2000).rows().size());
                Files.writeString(script,"INSERT INTO people(id,name) VALUES(9999,'job');");engine.put(task("sql-job",ScheduledTasks.Kind.SQL,script,out,profile));engine.runNow("sql-job");await(engine,"sql-job");equal(1,session.execute("SELECT * FROM people WHERE id=9999",10).rows().size());
                engine.put(task("profile-job",ScheduledTasks.Kind.SQL,script,out,profile));selected.set(new ConnectionProfile(profile.id(),profile.name(),profile.type(),"changed-host",profile.port(),profile.database(),profile.username(),profile.jdbcUrl(),profile.driverClass(),profile.driverJar(),false));engine.enable("profile-job",true);engine.runNow("profile-job");await(engine,"profile-job");equal(true,engine.state("profile-job").status().contains("conexión cambió"));
            }
            selected.set(profile);try(ScheduledTasks reopened=new ScheduledTasks(store,p->selected.get(),p->new char[0],m->{},false)){equal(4,reopened.list().size());equal(true,reopened.state(id).status().contains("cambió"));}
            equal(false,Files.readString(store).contains("password"));
        }
        System.out.println("CsvJobsIntegrationTest: OK (1205 UTF-8/multiline CSV rows, mapping, NULL vs empty, decimal precision, all-batch rollback/cancel, changed-file guard, real scheduled reports and SQL, persisted jobs, duplicate prevention and connection-change isolation)");
    }
    private static ScheduledTasks.Task task(String id,ScheduledTasks.Kind kind,Path script,Path out,ConnectionProfile profile)throws Exception{return new ScheduledTasks.Task(id,id,profile.id(),"PUBLIC",kind,script.toString(),SqlFiles.diskHash(script),out.toString(),60,Instant.now(),false);}
    private static void await(ScheduledTasks engine,String id)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);while(engine.state(id).running()&&System.nanoTime()<end)Thread.sleep(20);if(engine.state(id).running())throw new AssertionError("Task timed out: "+id);}
    /** Share the in-memory test server across isolated JDBC connections without a network listener. */
    public static final class SharedH2Driver implements Driver {
        private static final Driver delegate;
        static{try{var loader=new java.net.URLClassLoader(new java.net.URL[]{Path.of("build/h2-test.jar").toAbsolutePath().toUri().toURL()},CsvJobsIntegrationTest.class.getClassLoader());delegate=(Driver)Class.forName("org.h2.Driver",true,loader).getDeclaredConstructor().newInstance();}catch(Exception error){throw new ExceptionInInitializerError(error);}}
        public Connection connect(String url,Properties info)throws SQLException{return delegate.connect(url,info);}public boolean acceptsURL(String url)throws SQLException{return delegate.acceptsURL(url);}public java.sql.DriverPropertyInfo[] getPropertyInfo(String url,Properties info)throws SQLException{return delegate.getPropertyInfo(url,info);}public int getMajorVersion(){return delegate.getMajorVersion();}public int getMinorVersion(){return delegate.getMinorVersion();}public boolean jdbcCompliant(){return delegate.jdbcCompliant();}public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
