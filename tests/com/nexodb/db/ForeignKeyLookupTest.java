package com.nexodb.db;

import com.nexodb.model.*;
import com.nexodb.ui.ResultTableModel;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Real relational lookups and staged edits against an isolated in-memory database. */
public final class ForeignKeyLookupTest {
    public static void main(String[] args)throws Exception{
        Path driver=Path.of("build/h2-test.jar").toAbsolutePath();if(!Files.exists(driver)){System.out.println("ForeignKeyLookupTest: H2 fixture unavailable");return;}
        ConnectionProfile profile=new ConnectionProfile("lookup-test","Lookup test",DatabaseType.H2,"",0,"mem:lookup","sa","jdbc:h2:mem:lookup","org.h2.Driver",driver.toString(),false);
        try(JdbcSession s=JdbcSession.open(profile,new char[0])){
            s.execute("CREATE TABLE customers(id VARCHAR(30) PRIMARY KEY,name VARCHAR(100),note VARCHAR(100))",1);
            s.execute("INSERT INTO customers VALUES('0951561075','Cliente original','literal %_!'),('1308434412','Cliente nuevo','other')",1);
            s.execute("CREATE TABLE sales(id INT PRIMARY KEY,client VARCHAR(30),CONSTRAINT fk_client FOREIGN KEY(client) REFERENCES customers(id))",1);s.execute("INSERT INTO sales VALUES(1,'0951561075'),(2,NULL)",1);
            QueryResult result=s.execute("SELECT id,client FROM sales ORDER BY id",100);List<ForeignKeyLookup.Reference> refs=ForeignKeyLookup.describe(s.connection(),result);equal(1,refs.size());var ref=refs.get(0);equal("CUSTOMERS",ref.parentTable());equal(1,ref.parts().get(0).column());
            s.setReadOnly(true);var exact=ForeignKeyLookup.search(s.connection(),DatabaseType.H2,ref,result.rows().get(0),1,"0951561075","",true,0,25);equal(true,exact.exact());equal(1,exact.result().rows().size());equal("Cliente original",exact.result().rows().get(0).get(1));equal(true,s.getReadOnly());
            var fuzzy=ForeignKeyLookup.search(s.connection(),DatabaseType.H2,ref,result.rows().get(0),1,"nuevo","",true,0,25);equal(false,fuzzy.exact());equal("1308434412",fuzzy.result().rows().get(0).get(0));
            equal(1,ForeignKeyLookup.search(s.connection(),DatabaseType.H2,ref,result.rows().get(0),1,"%_!","",true,0,25).result().rows().size());
            equal(0,ForeignKeyLookup.search(s.connection(),DatabaseType.H2,ref,result.rows().get(0),1,"x' OR 1=1 --","",true,0,25).result().rows().size());
            equal(2,ForeignKeyLookup.search(s.connection(),DatabaseType.H2,ref,result.rows().get(1),1,"","",true,0,25).result().rows().size());
            QueryResult partial=s.execute("SELECT client AS customer FROM sales",100);if(partial.editing().target()!=null)throw new AssertionError("Partial projection must not be editable");equal(1,ForeignKeyLookup.describe(s.connection(),partial).size());
            QueryResult rearranged=s.execute("SELECT client,id FROM sales",100);equal(0,ForeignKeyLookup.describe(s.connection(),rearranged).get(0).parts().get(0).column());
            ResultTableModel model=new ResultTableModel();model.setResult(result);try{model.setReference(0,ForeignKeyLookup.selection(ref,fuzzy.result(),0));throw new AssertionError("Read-only selection modified data");}catch(IllegalStateException expected){}
            model.setEditable(true);model.setReference(0,ForeignKeyLookup.selection(ref,fuzzy.result(),0));equal(1,model.changeCount());equal("0951561075",s.execute("SELECT client FROM sales WHERE id=1",10).rows().get(0).get(0));
            s.setReadOnly(false);QueryEditSupport.saveChanges(s.connection(),result.editing().target(),model.changes());equal("1308434412",s.execute("SELECT client FROM sales WHERE id=1",10).rows().get(0).get(0));
            s.execute("CREATE TABLE parent_pair(tenant INT,id INT,label VARCHAR(100),PRIMARY KEY(tenant,id))",1);
            try(PreparedStatement insert=s.connection().prepareStatement("INSERT INTO parent_pair VALUES(?,?,?)")){for(int tenant=1;tenant<=2;tenant++)for(int id=1;id<=31;id++){insert.setInt(1,tenant);insert.setInt(2,id);insert.setString(3,"Pair "+tenant+"/"+id);insert.addBatch();}insert.executeBatch();}
            s.execute("CREATE TABLE child_pair(pk INT PRIMARY KEY,tenant INT,parent_id INT,FOREIGN KEY(tenant,parent_id) REFERENCES parent_pair(tenant,id))",1);s.execute("INSERT INTO child_pair VALUES(1,1,1)",1);
            QueryResult composite=s.execute("SELECT * FROM child_pair",100);var pair=ForeignKeyLookup.describe(s.connection(),composite).get(0);equal(2,pair.parts().size());equal(true,pair.complete());
            var page=ForeignKeyLookup.search(s.connection(),DatabaseType.H2,pair,composite.rows().get(0),2,"Pair","",true,0,25);equal(25,page.result().rows().size());equal(true,page.result().truncated());
            equal(6,ForeignKeyLookup.search(s.connection(),DatabaseType.H2,pair,composite.rows().get(0),2,"Pair","",true,25,25).result().rows().size());
            var all=ForeignKeyLookup.search(s.connection(),DatabaseType.H2,pair,composite.rows().get(0),2,"Pair","",false,0,0);equal(62,all.result().rows().size());
            Map<Integer,Object> selected=ForeignKeyLookup.selection(pair,all.result(),35);equal(2,selected.size());ResultTableModel pairs=new ResultTableModel();pairs.setResult(composite);pairs.setEditable(true);pairs.setReference(0,selected);equal(selected.get(1),pairs.getValueAt(0,1));equal(selected.get(2),pairs.getValueAt(0,2));QueryEditSupport.saveChanges(s.connection(),composite.editing().target(),pairs.changes());
            var incomplete=ForeignKeyLookup.describe(s.connection(),s.execute("SELECT parent_id FROM child_pair",100)).get(0);equal(false,incomplete.complete());try{ForeignKeyLookup.selection(incomplete,all.result(),0);throw new AssertionError("Partial composite selection accepted");}catch(IllegalArgumentException expected){}
            s.execute("CREATE TABLE nodes(id INT PRIMARY KEY,parent INT,FOREIGN KEY(parent) REFERENCES nodes(id))",1);s.execute("INSERT INTO nodes VALUES(1,NULL),(2,1)",1);equal("NODES",ForeignKeyLookup.describe(s.connection(),s.execute("SELECT * FROM nodes",100)).get(0).parentTable());
            equal(0,ForeignKeyLookup.describe(s.connection(),s.execute("SELECT c1.client,c2.client FROM sales c1 JOIN sales c2 ON c1.id=c2.id",100)).size());
            equal(2,s.execute("SELECT * FROM customers",10).rows().size());
        }
        System.out.println("ForeignKeyLookupTest: OK (actual FK origins, aliases/projections, exact/fuzzy/bound wildcard search, NULL, pagination/unlimited, composites, self references, no writes until save)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
