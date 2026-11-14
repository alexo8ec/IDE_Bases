package com.nexodb.android;

import java.sql.*;

public final class JdbcCompat {
    private JdbcCompat(){}
    public static String schema(Connection connection)throws SQLException{
        try{return (String)connection.getClass().getMethod("getSchema").invoke(connection);}catch(ReflectiveOperationException ignored){
            if(connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres"))try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT current_schema()")){return r.next()?r.getString(1):null;}
            return null;
        }
    }
}
