package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class VisualToolsTest {
    public static void main(String[] args)throws Exception{
        Path driver=Path.of("build/h2-test.jar").toAbsolutePath();ConnectionProfile profile=new ConnectionProfile("visual-test","Test",DatabaseType.H2,"",0,"mem:visual","sa","jdbc:h2:mem:visual;DB_CLOSE_DELAY=-1","org.h2.Driver",driver.toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE customers(id INT PRIMARY KEY,name VARCHAR(100),note VARCHAR(100),\"what?\" VARCHAR(50))",10);session.execute("CREATE TABLE orders(id INT PRIMARY KEY,customer_id INT,amount DECIMAL(10,2),FOREIGN KEY(customer_id) REFERENCES customers(id))",10);
            session.execute("INSERT INTO customers VALUES(1,'snow','leopard','literal?'),(2,'Joe','100%_','nothing'),(3,'snow leopard','other','text')",10);session.execute("INSERT INTO orders VALUES(1,1,10),(2,1,20),(3,2,30)",10);
            String sql=VisualQuery.select(DatabaseType.H2,"\"","PUBLIC",List.of(new VisualQuery.Table("CUSTOMERS","c"),new VisualQuery.Table("ORDERS","o")),List.of(new VisualQuery.Join("c","ID","o","CUSTOMER_ID","LEFT")),List.of(new VisualQuery.Field("c","NAME",true,"","customer","ASC",""),new VisualQuery.Field("o","AMOUNT",true,"SUM","total","",">= 10")),false,50);
            var result=session.execute(sql,100);equal(2,result.rows().size());equal("Joe",result.rows().get(0).get(0));equal("30.00",result.rows().get(0).get(1).toString());
            equal(true,VisualQuery.select(DatabaseType.SQLSERVER,"\"","",List.of(new VisualQuery.Table("x","a")),List.of(),List.of(),false,10).startsWith("SELECT TOP 10"));
            equal(true,VisualQuery.select(DatabaseType.ORACLE,"\"","",List.of(new VisualQuery.Table("x","a")),List.of(),List.of(),false,10).contains("FETCH FIRST 10 ROWS ONLY"));
            check(session,"snow leopard",2);check(session,"\"snow leopard\"",1);check(session,"Joe OR snow",3);check(session,"snow -other",1);check(session,"100%_",1);check(session,"x' OR 1=1 --",0);
            var odd=DataSearch.condition(DatabaseType.H2,"\"",List.of("what?"),"literal?");String inline=DataSearch.inline(DatabaseType.H2,odd);equal(1,session.execute("SELECT * FROM customers WHERE "+inline,10).rows().size());
            equal(true,DataSearch.literal(DatabaseType.MYSQL,"a'\\;DROP").startsWith("CONVERT(X'"));
        }
        System.out.println("VisualToolsTest: OK (actual JOIN/aggregates/order execution, dialect limits, bound word/phrase/OR/exclusion search, wildcard escaping and identifier/literal safety)");
    }
    private static void check(JdbcSession s,String text,int count)throws Exception{var condition=DataSearch.condition(DatabaseType.H2,"\"",List.of("NAME","NOTE"),text);try(var statement=s.connection().prepareStatement("SELECT * FROM customers WHERE "+condition.sql())){for(int i=0;i<condition.parameters().size();i++)statement.setString(i+1,condition.parameters().get(i));int rows=0;try(var result=statement.executeQuery()){while(result.next())rows++;}equal(count,rows);}equal(count,s.execute("SELECT * FROM customers WHERE "+DataSearch.inline(DatabaseType.H2,condition),10).rows().size());}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
