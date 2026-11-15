package com.nexodb.db;

import com.nexodb.model.*;
import com.nexodb.ui.ResultTableModel;
import java.nio.file.*;
import java.util.*;

/** Verifies that each safely identifiable table in a JOIN can be edited independently. */
public final class JoinEditIntegrationTest {
    public static void main(String[] args)throws Exception {
        Path driver=Path.of(System.getProperty("nexodb.test.h2Jar","build/h2-test.jar")).toAbsolutePath();
        ConnectionProfile profile=new ConnectionProfile("join-edit","Join edit",DatabaseType.H2,"",0,"mem:join_edit;DB_CLOSE_DELAY=-1","sa",
                "jdbc:h2:mem:join_edit;DB_CLOSE_DELAY=-1","org.h2.Driver",driver.toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])) {
            session.execute("CREATE TABLE companies(id INT PRIMARY KEY,name VARCHAR(100))",10);
            session.execute("CREATE TABLE documents(id INT PRIMARY KEY,company_id INT,status VARCHAR(30),FOREIGN KEY(company_id) REFERENCES companies(id))",10);
            session.execute("INSERT INTO companies VALUES(1,'Acme'); INSERT INTO documents VALUES(10,1,'draft')",10);
            String sql="SELECT c.id AS company_id,c.name,d.id AS document_id,d.status FROM companies c LEFT JOIN documents d ON d.company_id=c.id";
            QueryResult result=session.execute(sql,100);
            equal(2,result.editing().targets().size());
            QueryEditSupport.Target company=target(result,"COMPANIES"),document=target(result,"DOCUMENTS");

            ResultTableModel model=new ResultTableModel();model.setResult(result);model.setTarget(company);model.setEditable(true);
            equal(true,model.isCellEditable(0,0));equal(true,model.isCellEditable(0,1));equal(false,model.isCellEditable(0,2));
            model.setValueAt("Acme updated",0,1);QueryEditSupport.saveChanges(session.connection(),company,model.changes());
            equal("Acme updated",session.execute("SELECT name FROM companies WHERE id=1",10).rows().get(0).get(0));

            result=session.execute(sql,100);model.setResult(result);model.setTarget(document);model.setEditable(true);
            equal(false,model.isCellEditable(0,1));equal(true,model.isCellEditable(0,2));equal(true,model.isCellEditable(0,3));
            model.setValueAt("signed",0,3);QueryEditSupport.saveChanges(session.connection(),document,model.changes());
            equal("signed",session.execute("SELECT status FROM documents WHERE id=10",10).rows().get(0).get(0));

            QueryResult partial=session.execute("SELECT c.id,c.name,d.status FROM companies c JOIN documents d ON d.company_id=c.id",100);
            equal(1,partial.editing().targets().size());equal("COMPANIES",partial.editing().targets().get(0).table().name().toUpperCase(Locale.ROOT));
        }
        System.out.println("JoinEditIntegrationTest: OK (target selection, column ownership, primary-key safety and writes)");
    }
    private static QueryEditSupport.Target target(QueryResult result,String name){return result.editing().targets().stream().filter(t->t.table().name().equalsIgnoreCase(name)).findFirst().orElseThrow();}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
