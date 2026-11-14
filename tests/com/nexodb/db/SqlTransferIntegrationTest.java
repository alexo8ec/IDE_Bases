package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Explicit local-only integration test: refuses every server except the isolated test datadir. */
public final class SqlTransferIntegrationTest {
    public static void main(String[] args)throws Exception{
        if(!Boolean.getBoolean("nexodb.test.transfer"))throw new IllegalStateException("Enable nexodb.test.transfer for the isolated local server only");
        Path jar=Path.of("build/drivers/mariadb-java-client-4.0.0.jar").toAbsolutePath();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},SqlTransferIntegrationTest.class.getClassLoader())){
            Driver driver=(Driver)Class.forName("org.mariadb.jdbc.Driver",true,loader).getDeclaredConstructor().newInstance();
            Properties properties=new Properties();properties.setProperty("user","root");
            try(Connection connection=driver.connect("jdbc:mariadb://127.0.0.1:43179/?sslMode=disable",properties)){
                try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT @@datadir")){r.next();equal(Path.of("build/sql-transfer-test-data").toRealPath(),Path.of(r.getString(1)).toRealPath());}
                Set<String> vocabulary=com.nexodb.util.SqlVocabulary.forDatabase(connection.getMetaData());
                equal(true,vocabulary.containsAll(List.of("YEAR","DECLARE","JSON_EXTRACT")));
                try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT WORD FROM INFORMATION_SCHEMA.KEYWORDS")){while(r.next()){String word=r.getString(1);if(word.matches("[A-Za-z_][A-Za-z_0-9]*"))equal(true,vocabulary.contains(word.toUpperCase(Locale.ROOT)));}}
                for(String database:List.of("nexodb_transfer_source","nexodb_transfer_target","nexodb_transfer_native","nexodb_transfer_structure","nexodb_transfer_data")){
                    // OneDrive marks test directories read-only; MariaDB cannot remove such a directory.
                    Path directory=Path.of("build/sql-transfer-test-data").toRealPath().resolve(database);
                    if(Files.isDirectory(directory)&&Files.getFileStore(directory).supportsFileAttributeView("dos"))Files.setAttribute(directory,"dos:readonly",false);
                    exec(connection,"DROP DATABASE IF EXISTS "+database);
                }
                exec(connection,"CREATE DATABASE nexodb_transfer_source CHARACTER SET utf8mb4");connection.setCatalog("nexodb_transfer_source");
                exec(connection,"SET SQL_MODE=''");exec(connection,"CREATE TABLE parent(id INT PRIMARY KEY)");exec(connection,"INSERT INTO parent VALUES(1)");
                exec(connection,"CREATE TABLE item(id INT PRIMARY KEY,parent_id INT,txt LONGTEXT,raw LONGBLOB,flags BIT(9),amount DECIMAL(22,8),day DATE,stamp TIMESTAMP NULL,calculated INT GENERATED ALWAYS AS(id+1) STORED,location POINT,FOREIGN KEY(parent_id) REFERENCES parent(id))");
                String text="Mañana 😀 ' comillas \\ línea\n\tNULL $$ ;; -- /* ! */";byte[] bytes=new byte[]{0,1,127,-1,39,92};
                try(PreparedStatement s=connection.prepareStatement("INSERT INTO item(id,parent_id,txt,raw,flags,amount,day,stamp,location) VALUES(?,1,?,?,b'101010101',1234567890123.12345678,'0000-00-00','2026-11-13 12:34:56',ST_GeomFromText('POINT(1 2)',4326))")){
                    for(int i=1;i<=1205;i++){s.setInt(1,i);s.setString(2,text+i);s.setBytes(3,bytes);s.addBatch();}s.executeBatch();
                }
                exec(connection,"CREATE TABLE audit(value INT)");exec(connection,"INSERT INTO audit VALUES(0)");
                exec(connection,"CREATE FUNCTION f(x INT) RETURNS INT DETERMINISTIC RETURN x+1");
                exec(connection,"CREATE PROCEDURE p() BEGIN DECLARE x INT; SET x=YEAR('2026-01-01'); SELECT x; END");
                exec(connection,"CREATE VIEW z_base AS SELECT id,txt,f(id) AS n FROM item");
                exec(connection,"CREATE VIEW a_dependent AS SELECT id,txt,n FROM z_base");
                exec(connection,"CREATE TRIGGER on_item AFTER INSERT ON item FOR EACH ROW UPDATE audit SET value=value+1");
                exec(connection,"CREATE EVENT scheduled ON SCHEDULE EVERY 1 DAY DISABLE DO UPDATE audit SET value=value+1");
                exec(connection,"CREATE TABLE zero_id(id INT AUTO_INCREMENT PRIMARY KEY,value VARCHAR(20))");exec(connection,"SET SQL_MODE='NO_AUTO_VALUE_ON_ZERO'");exec(connection,"INSERT INTO zero_id VALUES(0,'zero')");exec(connection,"SET SQL_MODE='STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE'");
                List<SqlBackupService.ObjectInfo> objects=SqlBackupService.objects(connection,"nexodb_transfer_source");equal(10,objects.size());
                Path file=Path.of("build/sql-transfer-full.sql");
                var report=SqlBackupService.write(connection,DatabaseType.MARIADB,"nexodb_transfer_source",objects,new SqlBackupService.Options(SqlBackupService.Mode.STRUCTURE_AND_DATA,true,false,true,true,true),file,new SqlTransferControl(),p->{});
                equal(1208L,report.rows());connection.setAutoCommit(true);
                var analysis=SqlImportService.analyze(file,new SqlTransferControl());equal(Set.of("nexodb_transfer_source"),analysis.databases());
                SqlImportService.restore(connection,analysis,new SqlImportService.Options("nexodb_transfer_target",true,false,true),new SqlTransferControl(),p->{});connection.setAutoCommit(true);
                verify(connection,"nexodb_transfer_target",text,bytes);
                equal(1205L,number(connection,"SELECT COUNT(*) FROM nexodb_transfer_source.item"));
                // Structure and data modes can be restored in sequence to a new database.
                connection.setCatalog("nexodb_transfer_source");
                SqlBackupService.write(connection,DatabaseType.MARIADB,"nexodb_transfer_source",objects,new SqlBackupService.Options(SqlBackupService.Mode.STRUCTURE,false,false,true,false,true),Path.of("build/sql-transfer-structure.sql"),new SqlTransferControl(),p->{});
                SqlImportService.restore(connection,SqlImportService.analyze(Path.of("build/sql-transfer-structure.sql"),new SqlTransferControl()),new SqlImportService.Options("nexodb_transfer_structure",true,false,true),new SqlTransferControl(),p->{});connection.setAutoCommit(true);
                equal(0L,number(connection,"SELECT COUNT(*) FROM nexodb_transfer_structure.item"));
                connection.setCatalog("nexodb_transfer_source");
                SqlBackupService.write(connection,DatabaseType.MARIADB,"nexodb_transfer_source",objects.stream().filter(o->o.kind()==SqlBackupService.Kind.TABLE).toList(),new SqlBackupService.Options(SqlBackupService.Mode.DATA,false,false,true,false,true),Path.of("build/sql-transfer-data.sql"),new SqlTransferControl(),p->{});
                // Disable fixture trigger while importing data only: a real target may intentionally have triggers.
                exec(connection,"DROP TRIGGER nexodb_transfer_structure.on_item");
                SqlImportService.restore(connection,SqlImportService.analyze(Path.of("build/sql-transfer-data.sql"),new SqlTransferControl()),new SqlImportService.Options("nexodb_transfer_structure",false,false,true),new SqlTransferControl(),p->{});connection.setAutoCommit(true);
                equal(1205L,number(connection,"SELECT COUNT(*) FROM nexodb_transfer_structure.item"));
                // Cancellation preserves an existing backup instead of publishing a partial file.
                Path canceled=Path.of("build/sql-transfer-canceled.sql");Files.writeString(canceled,"original");SqlTransferControl cancel=new SqlTransferControl();
                try{SqlBackupService.write(connection,DatabaseType.MARIADB,"nexodb_transfer_source",objects,new SqlBackupService.Options(SqlBackupService.Mode.DATA,false,false,true,true,true),canceled,cancel,p->cancel.cancel());throw new AssertionError("Canceled backup succeeded");}catch(CancellationException expected){}
                connection.setAutoCommit(true);equal("original",Files.readString(canceled));
                Path changed=Path.of("build/sql-transfer-changed.sql");Files.writeString(changed,"SELECT 1;");var review=SqlImportService.analyze(changed,new SqlTransferControl());Files.writeString(changed,"DELETE FROM item;");
                try{SqlImportService.restore(connection,review,new SqlImportService.Options("nexodb_transfer_target",false,false,true),new SqlTransferControl(),p->{});throw new AssertionError("Changed file accepted");}catch(java.io.IOException expected){}
                Path error=Path.of("build/sql-transfer-error.sql");Files.writeString(error,"INSERT INTO audit VALUES(500);\nINVALID SQL;\nDELETE FROM item;");
                try{SqlImportService.restore(connection,SqlImportService.analyze(error,new SqlTransferControl()),new SqlImportService.Options("nexodb_transfer_target",false,false,true),new SqlTransferControl(),p->{});throw new AssertionError("Invalid SQL accepted");}catch(SqlImportService.ImportFailure expected){equal(1L,expected.completed());equal(2,expected.line());}
                connection.setAutoCommit(true);equal(1206L,number(connection,"SELECT COUNT(*) FROM nexodb_transfer_target.item"));equal(1L,number(connection,"SELECT COUNT(*) FROM nexodb_transfer_target.audit"));
                // Interoperability with an independent native SQL dump, including DELIMITER and version comments.
                Path nativeDump=Path.of("build/sql-transfer-native.sql").toAbsolutePath();
                String dumpProgram=Path.of(System.getProperty("nexodb.test.mariaDbBin","C:/Program Files/MariaDB 12.3/bin"),"mariadb-dump.exe").toString();
                Process dump=new ProcessBuilder(dumpProgram,"--no-defaults","--skip-ssl","--host=127.0.0.1","--port=43179","--user=root","--databases","nexodb_transfer_source","--routines","--events","--triggers","--hex-blob","--result-file="+nativeDump).redirectErrorStream(true).start();
                String log=new String(dump.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(dump.waitFor()!=0)throw new AssertionError(log);
                var nativeAnalysis=SqlImportService.analyze(nativeDump,new SqlTransferControl());equal(Set.of("nexodb_transfer_source"),nativeAnalysis.databases());
                SqlImportService.restore(connection,nativeAnalysis,new SqlImportService.Options("nexodb_transfer_native",true,false,true),new SqlTransferControl(),p->{});connection.setAutoCommit(true);
                verify(connection,"nexodb_transfer_native",text,bytes);
                // Dedicated scheduled backup/report connections exercise the real native server.
                var jobProfile=new com.nexodb.model.ConnectionProfile("native-jobs","Isolated MariaDB",DatabaseType.MARIADB,"127.0.0.1",43179,"nexodb_transfer_source","root","jdbc:mariadb://127.0.0.1:43179/nexodb_transfer_source?sslMode=disable","org.mariadb.jdbc.Driver",jar.toString(),false);
                Path jobRoot=Files.createTempDirectory(Path.of("build"),"native-jobs-");
                try(ScheduledTasks tasks=new ScheduledTasks(jobRoot.resolve("jobs.properties"),id->jobProfile,id->new char[0],message->{},false)){
                    var task=new ScheduledTasks.Task("backup","native-backup",jobProfile.id(),"nexodb_transfer_source",ScheduledTasks.Kind.BACKUP,"","",jobRoot.toAbsolutePath().toString(),0,java.time.Instant.now(),false);tasks.put(task);tasks.runNow(task.id());long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);while(tasks.state(task.id()).running()&&System.nanoTime()<deadline)Thread.sleep(25);if(!tasks.state(task.id()).status().contains("Respaldo completo"))throw new AssertionError(tasks.state(task.id()).status());Path backup=Path.of(tasks.state(task.id()).output());equal(true,Files.size(backup)>0);equal(Set.of("nexodb_transfer_source"),SqlImportService.analyze(backup,new SqlTransferControl()).databases());
                    tasks.setLicenseCheck(()->false);tasks.put(new ScheduledTasks.Task("blocked","license-block",jobProfile.id(),"nexodb_transfer_source",ScheduledTasks.Kind.BACKUP,"","",jobRoot.toAbsolutePath().toString(),0,java.time.Instant.now(),false));tasks.runNow("blocked");deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(tasks.state("blocked").running()&&System.nanoTime()<deadline)Thread.sleep(25);equal(true,tasks.state("blocked").status().contains("licencia"));
                }
            }
        }
        System.out.println("SqlTransferIntegrationTest: OK (MariaDB native round trips, 1205 rows, Unicode, blobs/bits, decimal precision, zero dates, TIMESTAMP, generated columns, geometry, views, routines, triggers, events, modes, remapping, cancellation, changed files and stop/rollback on error)");
    }
    private static void verify(Connection c,String database,String text,byte[] bytes)throws Exception{
        c.setCatalog(database);equal(1205L,number(c,"SELECT COUNT(*) FROM item"));
        equal(0L,number(c,"SELECT id FROM zero_id"));
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT txt,HEX(raw),HEX(flags),amount,day,calculated,ST_SRID(location),ST_AsText(location) FROM item WHERE id=7")){r.next();equal(text+7,r.getString(1));equal("00017FFF275C",r.getString(2));equal("155",r.getString(3));equal("1234567890123.12345678",r.getString(4));equal("0000-00-00",r.getString(5));equal(8,r.getInt(6));equal(4326,r.getInt(7));equal("POINT(1 2)",r.getString(8));}
        equal(1205L,number(c,"SELECT COUNT(*) FROM a_dependent"));equal(0L,number(c,"SELECT value FROM audit"));equal(8L,number(c,"SELECT f(7)"));
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("CALL p()")){r.next();equal(2026,r.getInt(1));}
        exec(c,"INSERT INTO item(id,parent_id) VALUES(9999,1)");equal(1L,number(c,"SELECT value FROM audit"));
        equal(1L,number(c,"SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE() AND EVENT_NAME='scheduled' AND STATUS='DISABLED'"));
    }
    private static void exec(Connection c,String sql)throws Exception{try(Statement s=c.createStatement()){s.execute(sql);}}
    private static long number(Connection c,String sql)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
