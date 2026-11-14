package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.file.Path;
import java.util.*;
import java.sql.SQLException;

public final class ForeignKeyDefinitionTest {
    public static void main(String[] args)throws Exception{
        var pairs=List.of(new ForeignKeyDefinition.ColumnPair("TENANT","TENANT"),new ForeignKeyDefinition.ColumnPair("PARENT_ID","ID"));
        var definition=new ForeignKeyDefinition("PUBLIC","CHILD","FK_CHILD_PARENT","PUBLIC","PARENT",pairs,"CASCADE","RESTRICT");
        ConnectionProfile profile=new ConnectionProfile("foreign-key","Isolated test",DatabaseType.H2,"",0,"mem:foreign_keys","sa","jdbc:h2:mem:foreign_keys","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE parent(tenant INT,id INT,PRIMARY KEY(tenant,id))",1);session.execute("CREATE TABLE child(id INT PRIMARY KEY,tenant INT,parent_id INT)",1);
            ForeignKeyDefinition.apply(session,definition.createSql("\""));
            var read=ForeignKeyDefinition.read(session.connection(),DatabaseType.H2,"PUBLIC","CHILD");
            equal(1,read.size());equal(pairs,read.get(0).columns());equal("CASCADE",read.get(0).onDelete());equal("PARENT",read.get(0).parentTable());
            session.execute("INSERT INTO parent VALUES(5,9)",1);session.execute("INSERT INTO child VALUES(1,5,9)",1);
            boolean rejected=false;try{session.execute("INSERT INTO child VALUES(2,5,99)",1);}catch(SQLException expected){rejected=true;}equal(true,rejected);
            session.execute("DELETE FROM parent WHERE tenant=5 AND id=9",1);equal(0,session.execute("SELECT * FROM child",10).rows().size());
            session.setAutoCommit(false);session.execute("INSERT INTO parent VALUES(6,10)",1);
            rejected=false;try{ForeignKeyDefinition.apply(session,definition.dropSql(DatabaseType.H2,"\""));}catch(SQLException expected){rejected=true;}equal(true,rejected);session.rollback();session.setAutoCommit(true);
            equal(1,ForeignKeyDefinition.read(session.connection(),DatabaseType.H2,"PUBLIC","CHILD").size());equal(0,session.execute("SELECT * FROM parent",10).rows().size());
            ForeignKeyDefinition.apply(session,definition.dropSql(DatabaseType.H2,"\""));equal(0,ForeignKeyDefinition.read(session.connection(),DatabaseType.H2,"PUBLIC","CHILD").size());
            var self=new ForeignKeyDefinition("PUBLIC","CHILD","FK_SELF","PUBLIC","CHILD",List.of(new ForeignKeyDefinition.ColumnPair("PARENT_ID","ID")),"SET NULL","NO ACTION");
            ForeignKeyDefinition.apply(session,self.createSql("\""));equal("CHILD",ForeignKeyDefinition.read(session.connection(),DatabaseType.H2,"PUBLIC","CHILD").get(0).parentTable());
        }
        String replacement=definition.replaceSql(definition,DatabaseType.MYSQL,"`");
        equal(1L,replacement.chars().filter(c->c==';').count());equal(true,replacement.contains("DROP FOREIGN KEY `FK_CHILD_PARENT`,\nADD CONSTRAINT"));
        var escaped=new ForeignKeyDefinition("db`x","child","fk`name","db`x","parent",List.of(new ForeignKeyDefinition.ColumnPair("a`b","id")),"RESTRICT","NO ACTION");equal(true,escaped.createSql("`").contains("`a``b`"));
        boolean rejected=false;try{new ForeignKeyDefinition("PUBLIC","CHILD","bad","PUBLIC","PARENT",pairs,"CASCADE; DROP TABLE parent","RESTRICT").createSql("\"");}catch(IllegalArgumentException expected){rejected=true;}equal(true,rejected);
        System.out.println("ForeignKeyDefinitionTest: OK (composite metadata order, real enforcement/cascade/drop/self-reference, transaction guard, one MySQL ALTER, quoting and action validation)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
