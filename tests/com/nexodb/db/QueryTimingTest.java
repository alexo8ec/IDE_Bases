package com.nexodb.db;

import com.nexodb.model.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.*;

/** Verifies that server, fetch and metadata timing survives result transformations. */
public final class QueryTimingTest {
    public static void main(String[] args) throws Exception {
        try(JdbcSession session=session(rows(1,2,3))){
            QueryResult result=session.execute("SELECT value FROM timing_test",100);
            QueryResult.Timing timing=result.timing();
            if(timing.serverMillis()<10)throw new AssertionError("Server phase was not measured: "+timing);
            if(timing.totalMillis()<timing.serverMillis())throw new AssertionError("Total must include server time: "+timing);
            if(timing.fetchMillis()<0||timing.metadataMillis()<0)throw new AssertionError("Negative phase: "+timing);
            QueryResult copy=result.withHiddenColumns(0).withEditing(result.editing());
            equal(timing,copy.timing());
        }
        QueryResult legacy=new QueryResult(List.of("id"),List.of(List.of(1)),-1,7,false);
        equal(7L,legacy.timing().totalMillis());equal(7L,legacy.timing().serverMillis());
        System.out.println("QueryTimingTest: OK (phase timing, transformations and legacy compatibility)");
    }

    private static JdbcSession session(ResultSet rows) throws Exception {
        Statement statement=(Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(proxy,method,args)->switch(method.getName()){
            case "execute" -> {Thread.sleep(18);yield true;}
            case "getResultSet" -> rows;
            case "getUpdateCount" -> -1;
            default -> null;
        });
        Connection connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->switch(method.getName()){
            case "createStatement" -> statement;
            case "isClosed" -> false;
            case "getAutoCommit" -> true;
            default -> null;
        });
        ConnectionProfile profile=new ConnectionProfile("timing","Timing",DatabaseType.SQLSERVER,"",0,"","sa","","","",false);
        var constructor=JdbcSession.class.getDeclaredConstructor(ConnectionProfile.class,Connection.class,java.net.URLClassLoader.class);
        constructor.setAccessible(true);return constructor.newInstance(profile,connection,null);
    }

    private static CachedRowSet rows(int... values)throws Exception{
        var rows=RowSetProvider.newFactory().createCachedRowSet();var metadata=new RowSetMetaDataImpl();metadata.setColumnCount(1);metadata.setColumnName(1,"value");metadata.setColumnLabel(1,"value");metadata.setColumnType(1,Types.INTEGER);rows.setMetaData(metadata);
        for(int value:values){rows.moveToInsertRow();rows.updateInt(1,value);rows.insertRow();rows.moveToCurrentRow();}rows.beforeFirst();return rows;
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
