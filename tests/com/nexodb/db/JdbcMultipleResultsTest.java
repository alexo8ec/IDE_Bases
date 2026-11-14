package com.nexodb.db;

import com.nexodb.model.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.*;

/** Exercises JDBC's alternating result-set/update-count protocol, including late errors. */
public final class JdbcMultipleResultsTest {
    public static void main(String[] args) throws Exception {
        try (JdbcSession session=session(-1,0,rows(1),3,rows(2))) {
            List<QueryResult> results=session.executeAll("EXEC procedure_name",1000);
            equal(4,results.size());equal(0,results.get(0).updateCount());
            equal(1,results.get(1).rows().get(0).get(0));equal(3,results.get(2).updateCount());
            equal(2,results.get(3).rows().get(0).get(0));
        }
        try (JdbcSession session=session(-1,rows(7),rows(8))) {
            equal(7,session.execute("EXEC procedure_name",10).rows().get(0).get(0));
        }
        try (JdbcSession session=session(-1,rows())) {
            var results=session.executeAll("EXEC empty_procedure",10);
            equal(1,results.size());equal(true,results.get(0).hasRows());equal(0,results.get(0).rows().size());
        }
        try (JdbcSession session=session(1,rows(9),rows(10))) {
            try {session.executeAll("EXEC fails_late",10);throw new AssertionError("Expected late error");}
            catch (JdbcSession.ExecutionException error) {
                equal(1,error.results().size());equal(9,error.results().get(0).rows().get(0).get(0));
                equal("42000",error.getSQLState());equal(50001,error.getErrorCode());
            }
        }
        try (JdbcSession session=session(-1)) {equal(-1,session.executeAll("EXEC no_results",10).get(0).updateCount());}
        System.out.println("JdbcMultipleResultsTest: OK (multiple result sets, interleaved counts, empty result, partial error, compatibility)");
    }

    private static JdbcSession session(int failAt,Object... outputs) throws Exception {
        int[] index={0};
        Statement statement=(Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(proxy,method,args)->switch(method.getName()) {
            case "execute" -> {index[0]=0;yield outputs.length>0 && outputs[0] instanceof ResultSet;}
            case "getResultSet" -> outputs[index[0]];
            case "getUpdateCount" -> index[0]>=outputs.length ? -1 : outputs[index[0]] instanceof Integer count ? count : -1;
            case "getMoreResults" -> {
                equal(Statement.CLOSE_CURRENT_RESULT,args[0]);
                if (++index[0]==failAt) throw new SQLException("Procedure failed after a result","42000",50001);
                yield index[0]<outputs.length && outputs[index[0]] instanceof ResultSet;
            }
            default -> null;
        });
        Connection connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->switch(method.getName()) {
            case "createStatement" -> statement;
            case "isClosed" -> false;
            case "getAutoCommit" -> true;
            default -> null;
        });
        ConnectionProfile profile=new ConnectionProfile("multiple-results","Test",DatabaseType.SQLSERVER,"",0,"","sa","","", "",false);
        var constructor=JdbcSession.class.getDeclaredConstructor(ConnectionProfile.class,Connection.class,java.net.URLClassLoader.class);
        constructor.setAccessible(true);
        return constructor.newInstance(profile,connection,null);
    }

    private static CachedRowSet rows(int... values) throws Exception {
        var rows=RowSetProvider.newFactory().createCachedRowSet();
        var metadata=new RowSetMetaDataImpl();metadata.setColumnCount(1);metadata.setColumnName(1,"value");metadata.setColumnLabel(1,"value");metadata.setColumnType(1,Types.INTEGER);
        rows.setMetaData(metadata);
        for (int value:values) {rows.moveToInsertRow();rows.updateInt(1,value);rows.insertRow();rows.moveToCurrentRow();}
        rows.beforeFirst();return rows;
    }

    private static void equal(Object expected,Object actual) {if (!Objects.equals(expected,actual)) throw new AssertionError(expected+" != "+actual);}
}
