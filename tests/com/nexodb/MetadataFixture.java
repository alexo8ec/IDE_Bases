package com.nexodb;

import com.nexodb.db.JdbcSession;
import com.nexodb.model.*;
import javax.sql.rowset.*;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.sql.*;

public final class MetadataFixture {
    public static JdbcSession session(String id, DatabaseType type, InvocationHandler handler) throws Exception {
        DatabaseMetaData metadata=(DatabaseMetaData) Proxy.newProxyInstance(MetadataFixture.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(p,m,a)->switch(m.getName()) {
            case "getSearchStringEscape" -> "\\";
            case "getIdentifierQuoteString" -> "`";
            default -> handler.invoke(p,m,a);
        });
        Connection connection=(Connection)Proxy.newProxyInstance(MetadataFixture.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->switch(m.getName()) {
            case "getMetaData" -> metadata;
            case "isClosed" -> false;
            case "getAutoCommit" -> true;
            case "getCatalog" -> "db1";
            case "close" -> null;
            default -> throw new AssertionError("Unexpected database operation: "+m.getName());
        });
        ConnectionProfile profile=new ConnectionProfile(id,id,type,"localhost",type.defaultPort(),"db1","tester",type.buildUrl("localhost",type.defaultPort(),"db1"),"","",true);
        Constructor<JdbcSession> constructor=JdbcSession.class.getDeclaredConstructor(ConnectionProfile.class,Connection.class,URLClassLoader.class);
        constructor.setAccessible(true); return constructor.newInstance(profile,connection,null);
    }
    public static CachedRowSet rows(String[] names,Object[]... data) throws Exception {
        RowSetMetaDataImpl schema=new RowSetMetaDataImpl(); schema.setColumnCount(names.length);
        for(int i=0;i<names.length;i++){schema.setColumnName(i+1,names[i]);schema.setColumnType(i+1,Types.VARCHAR);}
        CachedRowSet rows=RowSetProvider.newFactory().createCachedRowSet(); rows.setMetaData(schema);
        for(int r=data.length-1;r>=0;r--){rows.moveToInsertRow(); for(int c=0;c<names.length;c++) {
            if(data[r][c]==null) rows.updateNull(c+1); else rows.updateString(c+1,String.valueOf(data[r][c]));
        } rows.insertRow(); rows.moveToCurrentRow();}
        rows.beforeFirst(); return rows;
    }
    public static ResultSet track(ResultSet rows,Runnable closed) {
        return (ResultSet)Proxy.newProxyInstance(MetadataFixture.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
            if(m.getName().equals("close")) closed.run();
            try{return m.invoke(rows,a);}catch(InvocationTargetException e){throw e.getCause();}
        });
    }
    public static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
