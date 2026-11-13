package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Real SQL writes only against a disposable H2 memory database, never a saved connection. */
public final class QueryEditIntegrationTest {
    public static void main(String[] args)throws Exception {
        Path driver=Path.of(System.getProperty("nexodb.test.h2Jar","build/h2-test.jar")).toAbsolutePath();
        ConnectionProfile profile=new ConnectionProfile("query-edit-test","Memory test",DatabaseType.H2,"",0,"mem:query_edit",
                "sa","jdbc:h2:mem:query_edit;MODE=MySQL;DB_CLOSE_DELAY=-1","org.h2.Driver",driver.toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])) {
            Connection db=session.connection();
            try(Statement statement=db.createStatement()) {
                statement.execute("CREATE TABLE people(id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(20,5), note VARCHAR(100), computed INT GENERATED ALWAYS AS (id+1))");
                statement.execute("INSERT INTO people(id,name,amount,note) VALUES(1,'Alice',12.50000,NULL),(2,'Bob',3.00000,'old')");
                statement.execute("CREATE TABLE pairs(a INT,b INT,payload VARCHAR(100),PRIMARY KEY(a,b))");
                statement.execute("INSERT INTO pairs VALUES(1,1,'a'),(1,2,'b')");
                statement.execute("CREATE TABLE no_key(payload VARCHAR(50))");statement.execute("CREATE VIEW people_view AS SELECT * FROM people");
                statement.execute("CREATE SCHEMA other");statement.execute("CREATE TABLE other.people(id INT PRIMARY KEY,name VARCHAR(100))");statement.execute("INSERT INTO other.people VALUES(1,'untouched')");
            }
            QueryResult first=session.execute("SELECT * FROM people WHERE id IN (1,2) ORDER BY id",100);
            requireTarget(first);equal(false,first.editing().target().columns().get(4).writable());
            QueryEditSupport.Saved saved=QueryEditSupport.save(db,first.editing().target(),first.rows().get(0),1,"O'Reilly'; DROP TABLE people; --");
            equal(false,saved.pendingCommit());equal("O'Reilly'; DROP TABLE people; --",value(session,1,"name"));equal(true,db.getAutoCommit());
            QueryResult aliases=session.execute("SELECT id AS identity_column,name AS caption FROM people WHERE id=1",100);requireTarget(aliases);
            QueryEditSupport.save(db,aliases.editing().target(),aliases.rows().get(0),1,"Alias works");equal("Alias works",value(session,1,"name"));
            for(String sql:List.of("SELECT name FROM people","SELECT * FROM no_key","SELECT * FROM people_view","SELECT DISTINCT id FROM people","SELECT p.* FROM people p JOIN pairs x ON p.id=x.a","SELECT count(*),id FROM people GROUP BY id","SELECT id+0 AS id,name FROM people"))
                if(session.execute(sql,100).editing().target()!=null)throw new AssertionError("Should be read only: "+sql);
            QueryResult composite=session.execute("SELECT * FROM pairs ORDER BY b",100);requireTarget(composite);
            QueryEditSupport.save(db,composite.editing().target(),composite.rows().get(1),2,"only second");
            equal("a",session.execute("SELECT payload FROM pairs WHERE a=1 AND b=1",10).rows().get(0).get(0));
            equal("only second",session.execute("SELECT payload FROM pairs WHERE a=1 AND b=2",10).rows().get(0).get(0));
            // Qualified target remains stable when the session's default schema changes.
            QueryResult original=session.execute("SELECT id,name FROM people WHERE id=1",10);db.setSchema("OTHER");
            QueryEditSupport.save(db,original.editing().target(),original.rows().get(0),1,"Original schema");
            String qualified=QueryEditSupport.qualifiedSelect("SELECT id,name FROM people p WHERE id=1",original.editing().target());
            equal("Original schema",session.execute(qualified,10).rows().get(0).get(1));equal("untouched",session.execute("SELECT name FROM people",10).rows().get(0).get(0));db.setSchema("PUBLIC");
            QueryResult before=session.execute("SELECT * FROM people ORDER BY id",10);
            try(Statement statement=db.createStatement()){statement.execute("UPDATE people SET name='concurrent' WHERE id=2");}
            expectFailure(()->QueryEditSupport.saveChanges(db,before.editing().target(),List.of(change(0,before,1,"must rollback"),change(1,before,1,"must not overwrite"))));
            equal("Original schema",value(session,1,"name"));equal("concurrent",value(session,2,"name"));equal(true,db.getAutoCommit());
            // A failed batch rolls back to its savepoint, preserving earlier manual transaction work.
            db.setAutoCommit(false);try(Statement statement=db.createStatement()){statement.execute("UPDATE people SET note='prior work' WHERE id=1");}
            expectFailure(()->QueryEditSupport.saveChanges(db,before.editing().target(),List.of(change(0,before,1,"must rollback"),change(1,before,1,"conflict"))));
            equal("prior work",value(session,1,"note"));equal("Original schema",value(session,1,"name"));
            QueryResult manual=session.execute("SELECT * FROM people ORDER BY id",10);
            equal(true,QueryEditSupport.save(db,manual.editing().target(),manual.rows().get(0),1,"pending").pendingCommit());
            equal(false,db.getAutoCommit());db.rollback();db.setAutoCommit(true);equal("Original schema",value(session,1,"name"));equal(null,value(session,1,"note"));
            QueryResult nullable=session.execute("SELECT * FROM people WHERE id=1",10);
            Map<Integer,Object> edits=new LinkedHashMap<>();edits.put(1,"");edits.put(2,"123456789012345.12345");edits.put(3,"NULL");
            QueryEditSupport.saveChanges(db,nullable.editing().target(),List.of(new QueryEditSupport.RowChange(0,nullable.rows().get(0),edits)));
            equal("",value(session,1,"name"));equal("123456789012345.12345",value(session,1,"amount").toString());equal("NULL",value(session,1,"note"));
            QueryResult literal=session.execute("SELECT * FROM people WHERE id=1",10);QueryEditSupport.save(db,literal.editing().target(),literal.rows().get(0),3,null);equal(null,value(session,1,"note"));
            expectFailure(()->QueryEditSupport.save(db,literal.editing().target(),literal.rows().get(0),0,"not a number"));
            expectFailure(()->QueryEditSupport.save(db,literal.editing().target(),literal.rows().get(0),4,9));
            QueryResult key=session.execute("SELECT id,name FROM people WHERE id=1",10);QueryEditSupport.save(db,key.editing().target(),key.rows().get(0),0,"10");equal(1,session.execute("SELECT id FROM people WHERE id=10",10).rows().size());
            session.execute("CREATE TABLE entries(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,title VARCHAR(100) DEFAULT 'new',note VARCHAR(100),amount DECIMAL(10,2) DEFAULT 5)",10);
            QueryResult empty=session.execute("SELECT * FROM entries ORDER BY id",100);requireTarget(empty);
            List<Object> defaults=new ArrayList<>(Collections.nCopies(4,QueryEditSupport.DefaultValue.INSTANCE));
            QueryEditSupport.saveChanges(db,empty.editing().target(),List.of(new QueryEditSupport.RowChange(0,defaults,Map.of(1,"a ' quoted value"),QueryEditSupport.ChangeKind.INSERT)));
            QueryResult inserted=session.execute("SELECT * FROM entries ORDER BY id",100);equal(1,inserted.rows().size());equal("a ' quoted value",inserted.rows().get(0).get(1));equal("5.00",inserted.rows().get(0).get(3).toString());
            QueryEditSupport.saveChanges(db,inserted.editing().target(),List.of(change(0,inserted,1,QueryEditSupport.DefaultValue.INSTANCE)));
            QueryResult reset=session.execute("SELECT * FROM entries ORDER BY id",100);equal("new",reset.rows().get(0).get(1));
            QueryEditSupport.saveChanges(db,reset.editing().target(),List.of(new QueryEditSupport.RowChange(1,defaults,Map.of(),QueryEditSupport.ChangeKind.INSERT)));
            QueryResult two=session.execute("SELECT * FROM entries ORDER BY id",100);equal(2,two.rows().size());
            session.execute("UPDATE entries SET note='concurrent change' WHERE id=2",10);
            expectFailure(()->QueryEditSupport.saveChanges(db,two.editing().target(),List.of(new QueryEditSupport.RowChange(0,two.rows().get(0),Map.of(),QueryEditSupport.ChangeKind.DELETE),new QueryEditSupport.RowChange(1,two.rows().get(1),Map.of(),QueryEditSupport.ChangeKind.DELETE))));
            equal(2,session.execute("SELECT * FROM entries",100).rows().size());
            db.setAutoCommit(false);QueryEditSupport.saveChanges(db,two.editing().target(),List.of(new QueryEditSupport.RowChange(0,defaults,Map.of(1,"manual"),QueryEditSupport.ChangeKind.INSERT)));db.rollback();db.setAutoCommit(true);equal(2,session.execute("SELECT * FROM entries",100).rows().size());
            QueryResult beforeDelete=session.execute("SELECT * FROM entries ORDER BY id",100);
            QueryEditSupport.saveChanges(db,beforeDelete.editing().target(),List.of(new QueryEditSupport.RowChange(0,beforeDelete.rows().get(0),Map.of(),QueryEditSupport.ChangeKind.DELETE),new QueryEditSupport.RowChange(1,beforeDelete.rows().get(1),Map.of(),QueryEditSupport.ChangeKind.DELETE)));
            equal(0,session.execute("SELECT * FROM entries",100).rows().size());
        }
        System.out.println("QueryEditIntegrationTest: OK (real writes, aliases, composite keys, NULL, precision, namespaces, atomic batches, conflicts and rollback)");
    }
    private static QueryEditSupport.RowChange change(int row,QueryResult result,int column,Object value){return new QueryEditSupport.RowChange(row,result.rows().get(row),Collections.singletonMap(column,value));}
    private static Object value(JdbcSession session,int id,String column)throws Exception{return session.execute("SELECT "+column+" FROM people WHERE id="+id,10).rows().get(0).get(0);}
    private static void requireTarget(QueryResult result){if(result.editing()==null||result.editing().target()==null)throw new AssertionError("Expected editable result: "+(result.editing()==null?"no metadata":result.editing().reason()));}
    private interface Attempt{void run()throws Exception;}
    private static void expectFailure(Attempt attempt)throws Exception{try{attempt.run();throw new AssertionError("Expected SQL failure");}catch(SQLException expected){}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
