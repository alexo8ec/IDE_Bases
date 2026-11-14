package com.nexodb.db;

import com.nexodb.model.*;
import java.sql.*;
import java.util.*;

/** Android statically bundled drivers: no downloaded executable code or desktop URLClassLoader. */
public final class JdbcSession implements AutoCloseable {
    private final ConnectionProfile profile;private final Connection connection;private volatile Statement active;
    private JdbcSession(ConnectionProfile profile,Connection connection){this.profile=profile;this.connection=connection;}
    public static JdbcSession open(ConnectionProfile profile,char[] secret)throws Exception{
        if(profile.authenticationMode()!=AuthenticationMode.DATABASE)throw new SQLException("Android utiliza usuario y contraseña de la base de datos.");
        Properties options=new Properties();options.setProperty("user",profile.username());options.setProperty("password",new String(secret));
        String url=profile.jdbcUrl();Driver driver;
        if(profile.type()==DatabaseType.MYSQL||profile.type()==DatabaseType.MARIADB){driver=new org.mariadb.jdbc.Driver();url=url.replaceFirst("^jdbc:mysql:","jdbc:mariadb:");options.setProperty("connectTimeout","15000");options.setProperty("socketTimeout","60000");options.setProperty("restrictedAuth","mysql_native_password,caching_sha2_password,sha256_password,client_ed25519");}
        else if(profile.type()==DatabaseType.POSTGRESQL){driver=new org.postgresql.Driver();options.setProperty("connectTimeout","15");options.setProperty("socketTimeout","60");}
        else throw new SQLException("Este motor no tiene un controlador Android habilitado.");
        Connection connection=driver.connect(url,options);if(connection==null)throw new SQLException("URL de conexión no compatible.");connection.setReadOnly(profile.readOnly());return new JdbcSession(profile,connection);
    }
    public ConnectionProfile profile(){return profile;}public Connection connection(){return connection;}
    public QueryResult execute(String sql,int maxRows)throws SQLException{
        long start=System.nanoTime();try(Statement statement=connection.createStatement()){
            active=statement;statement.setMaxRows(maxRows==Integer.MAX_VALUE?0:maxRows+1);statement.setFetchSize(Math.min(maxRows,500));
            if(statement.execute(sql)){try(ResultSet rows=statement.getResultSet()){return QueryResult.from(rows,maxRows).withEditing(QueryEditSupport.describe(connection,profile.type(),sql,rows.getMetaData())).withElapsed((System.nanoTime()-start)/1_000_000);}}
            return QueryResult.update(statement.getUpdateCount(),(System.nanoTime()-start)/1_000_000);
        }finally{active=null;}
    }
    public boolean isOpen(){try{return !connection.isClosed();}catch(SQLException e){return false;}}
    public void cancel()throws SQLException{Statement statement=active;if(statement!=null)statement.cancel();}
    public void setReadOnly(boolean value)throws SQLException{connection.setReadOnly(value);}public boolean getReadOnly()throws SQLException{return connection.isReadOnly();}
    public void setAutoCommit(boolean value)throws SQLException{connection.setAutoCommit(value);}public boolean getAutoCommit()throws SQLException{return connection.getAutoCommit();}
    public void commit()throws SQLException{connection.commit();}public void rollback()throws SQLException{connection.rollback();}
    public void close()throws Exception{try{if(!connection.getAutoCommit())connection.rollback();}finally{connection.close();}}
}
