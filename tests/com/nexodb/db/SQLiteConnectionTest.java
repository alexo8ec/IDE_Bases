package com.nexodb.db;

import com.nexodb.model.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.swing.*;
import java.util.concurrent.atomic.AtomicReference;
import com.nexodb.ui.QueryPanel;

public final class SQLiteConnectionTest {
    public static void main(String[] args) throws Exception {
        Path root=Path.of("build/sqlite-test").toAbsolutePath();Files.createDirectories(root);
        Path file=root.resolve("prueba ñ # local.sqlite");
        Path driver=Path.of("build/sqlite-test.jar").toAbsolutePath();
        if(!Files.isRegularFile(driver))throw new AssertionError("Missing SQLite test driver");
        try(URLClassLoader loader=new URLClassLoader(new URL[]{driver.toUri().toURL()})) {
            Driver sqlite=(Driver)Class.forName("org.sqlite.JDBC",true,loader).getDeclaredConstructor().newInstance();
            try(Connection c=sqlite.connect("jdbc:sqlite:"+file,new Properties());Statement s=c.createStatement()) {s.execute("CREATE TABLE IF NOT EXISTS sample(id INTEGER PRIMARY KEY,name TEXT)");s.execute("DELETE FROM sample");s.execute("INSERT INTO sample VALUES(1,'Prueba ñ')");}
        }
        for(boolean readOnly:new boolean[]{true,false}) {
            ConnectionProfile raw=profile(file,driver,readOnly);
            ConnectionProfile prepared=SQLiteSupport.prepare(raw);
            if(!prepared.jdbcUrl().contains("%23") || !prepared.username().isEmpty())throw new AssertionError("Encoded path / credentials");
            try(JdbcSession session=JdbcSession.open(raw,new char[0])) {
                if(!readOnly){
                    try(Statement st=session.connection().createStatement()){st.execute("CREATE TABLE IF NOT EXISTS fk_parent(id INTEGER PRIMARY KEY,name TEXT)");st.execute("CREATE TABLE IF NOT EXISTS fk_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES fk_parent(id))");st.execute("INSERT OR IGNORE INTO fk_parent VALUES(1,'Related SQLite')");st.execute("INSERT OR IGNORE INTO fk_child VALUES(1,1)");}
                    QueryResult fk=session.execute("SELECT * FROM fk_child",25);var reference=ForeignKeyLookup.describe(session.connection(),fk);if(reference.size()!=1)throw new AssertionError("SQLite FK origin metadata");
                    if(ForeignKeyLookup.search(session.connection(),DatabaseType.SQLITE,reference.get(0),fk.rows().get(0),1,"1","",true,0,25).result().rows().size()!=1)throw new AssertionError("SQLite parent lookup");
                }
                QueryResult result=session.execute("SELECT * FROM sample",1000);
                if(result.rows().size()!=1)throw new AssertionError("SQLite SELECT");
                try(ResultSet tables=session.connection().getMetaData().getTables(null,null,"sample",new String[]{"TABLE"})){if(!tables.next())throw new AssertionError("Tables");}
                if(readOnly) {try(Statement s=session.connection().createStatement()){try{s.execute("DELETE FROM sample");throw new AssertionError("Readonly file writable");}catch(SQLException expected){}}}
                else {session.setReadOnly(true);session.setReadOnly(false);session.setAutoCommit(false);try(Statement s=session.connection().createStatement()){s.execute("UPDATE sample SET name='Changed'");}session.rollback();session.setAutoCommit(true);}
                AtomicReference<QueryPanel> panel=new AtomicReference<>();
                SwingUtilities.invokeAndWait(()->{QueryPanel p=new QueryPanel(text->{},busy->{});panel.set(p);p.setSession(session);});
                await(()->panel.get().activeDatabase().equals("main"));
                SwingUtilities.invokeAndWait(()->panel.get().runSql("SELECT * FROM sample"));
                await(()->{try{return field(panel.get(),"currentResult") instanceof QueryResult displayed && displayed.rows().size()==1;}catch(Exception error){throw new RuntimeException(error);}});
                SwingUtilities.invokeAndWait(()->{panel.get().setSession(null);panel.get().saveDraftBeforeClose();});
            }
        }
        Path missing=root.resolve("never-create-"+UUID.randomUUID()+".sqlite");
        try{JdbcSession.open(profile(missing,driver,false),new char[0]);throw new AssertionError("Missing file accepted");}catch(IllegalArgumentException expected){}
        if(Files.exists(missing))throw new AssertionError("Missing path created database");
        Path invalid=root.resolve("not-a-database.sqlite");Files.writeString(invalid,"ordinary text");
        try{SQLiteSupport.prepare(profile(invalid,driver,false));throw new AssertionError("Invalid file accepted");}catch(IllegalArgumentException expected){}
        System.out.println("SQLiteConnectionTest: OK (existing file, Unicode path, SELECT, metadata, read-only enforcement, rollback, missing/invalid file)");
    }
    static ConnectionProfile profile(Path file,Path driver,boolean readOnly){return new ConnectionProfile("sqlite-test","SQLite test",DatabaseType.SQLITE,"ignored",0,file.toString(),"ignored","jdbc:sqlite:"+file,"org.sqlite.JDBC",driver.toString(),readOnly);}
    private static Object field(Object target,String name)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();SwingUtilities.invokeAndWait(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(30);}throw new AssertionError("SQLite editor operation timed out");}
}
