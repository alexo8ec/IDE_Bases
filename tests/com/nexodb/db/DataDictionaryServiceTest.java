package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.file.Path;
import java.util.*;

public final class DataDictionaryServiceTest {
    public static void main(String[] args)throws Exception{
        ConnectionProfile profile=new ConnectionProfile("dictionary-test","Dictionary H2",DatabaseType.H2,"",0,"mem:dictionary;DB_CLOSE_DELAY=-1","sa","jdbc:h2:mem:dictionary;DB_CLOSE_DELAY=-1","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE customer(id INT PRIMARY KEY,name VARCHAR(80) NOT NULL)",1);session.execute("COMMENT ON TABLE customer IS 'Business customers'",1);session.execute("COMMENT ON COLUMN customer.name IS 'Display name'",1);session.execute("CREATE TABLE orders(id INT PRIMARY KEY,customer_id INT NOT NULL,total DECIMAL(12,2) DEFAULT 0,CONSTRAINT fk_orders_customer FOREIGN KEY(customer_id) REFERENCES customer(id))",1);session.execute("CREATE INDEX idx_orders_total ON orders(total)",1);session.execute("CREATE VIEW active_orders AS SELECT id,total FROM orders",1);
            var summary=DataDictionaryService.read(session,"PUBLIC");require(summary.tables().size()==3,"Summary object count");var orders=find(summary,"ORDERS");require(orders.columns().size()==3,"Column summary");require(!orders.detailed(),"Summary must stay scalable");var details=DataDictionaryService.details(session,orders);require(details.detailed(),"Details flag");require(details.columns().stream().anyMatch(c->c.name().equalsIgnoreCase("ID")&&c.primaryKey()),"Primary key missing");require(details.indexes().stream().anyMatch(i->i.name().equalsIgnoreCase("IDX_ORDERS_TOTAL")),"Index missing");require(details.relations().stream().anyMatch(r->r.parent().toUpperCase(Locale.ROOT).contains("CUSTOMER")&&r.columns().contains("CUSTOMER_ID")),"Relation missing");
            var complete=DataDictionaryService.complete(session,"PUBLIC",value->{});String markdown=DataDictionaryService.markdown(complete),csv=DataDictionaryService.csv(complete);require(markdown.contains("fk_orders_customer")||markdown.contains("FK_ORDERS_CUSTOMER"),"Markdown relations missing");require(markdown.contains("Business customers"),"Table comment missing");require(csv.contains("ORDERS")&&csv.contains("CUSTOMER_ID"),"CSV columns missing");require(!markdown.contains("SELECT id,total"),"Dictionary must not export view SQL or row data");
        }
        System.out.println("DataDictionaryServiceTest: OK (scalable summary, lazy keys/indexes/relations and complete exports)");
    }
    private static DataDictionaryService.Table find(DataDictionaryService.Dictionary dictionary,String name){return dictionary.tables().stream().filter(t->t.name().equalsIgnoreCase(name)).findFirst().orElseThrow();}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
