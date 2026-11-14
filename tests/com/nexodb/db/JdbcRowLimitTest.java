package com.nexodb.db;

import com.nexodb.model.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

public final class JdbcRowLimitTest {
    public static void main(String[] args)throws Exception{
        List<Integer> limits=new ArrayList<>();
        Statement driver=(Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(proxy,method,values)->{
            if(method.getName().equals("setMaxRows")){int limit=(Integer)values[0];if(limit>5_000_000)throw new SQLException("setMaxRows() out of range");limits.add(limit);}
            return null;
        });
        JdbcSession.configureRowLimit(driver,1000);JdbcSession.configureRowLimit(driver,Integer.MAX_VALUE);
        equal(List.of(1001,0),limits);
        ConnectionProfile profile=new ConnectionProfile("row-limit","Isolated test",DatabaseType.H2,"",0,"mem:row_limit","sa","jdbc:h2:mem:row_limit","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE entries(id INT PRIMARY KEY,label VARCHAR(30))",1);
            session.execute("INSERT INTO entries SELECT X,'Mite' FROM SYSTEM_RANGE(1,1205)",1);
            equal(1000,session.execute("SELECT * FROM entries",1000).rows().size());
            equal(1205,session.execute("SELECT * FROM entries",Integer.MAX_VALUE).rows().size());
            equal(7,session.execute("SELECT * FROM entries LIMIT 7",Integer.MAX_VALUE).rows().size());
        }
        System.out.println("JdbcRowLimitTest: OK (driver cap regression, JDBC unlimited, 1205 actual rows, explicit SQL LIMIT preserved)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
