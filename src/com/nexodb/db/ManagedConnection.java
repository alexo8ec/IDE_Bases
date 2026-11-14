package com.nexodb.db;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Idle validation and session recovery. SQL statements are never replayed. */
public final class ManagedConnection implements InvocationHandler {
    @FunctionalInterface public interface Opener { Connection open() throws Exception; }
    /** Android's java.sql.Connection omits schema methods; expose them to the compatibility adapter. */
    public interface SchemaAccess { String getSchema() throws SQLException; void setSchema(String schema) throws SQLException; }
    private static final long IDLE_NANOS=TimeUnit.SECONDS.toNanos(15);
    private static final ScheduledThreadPoolExecutor TIMER=new ScheduledThreadPoolExecutor(2,r->{Thread t=new Thread(r,"nexodb-connection-keepalive");t.setDaemon(true);return t;});
    static { TIMER.setRemoveOnCancelPolicy(true); }
    private final Opener opener;private final Runnable cleanup;
    private final IdentityHashMap<Object,Handle> handles=new IdentityHashMap<>();
    private Connection physical,proxy;private ScheduledFuture<?> heartbeat;
    private boolean closed,broken,autoCommit,readOnly,sqlTransaction;private String catalog,schema;private Integer isolation;
    private long lastActivity=System.nanoTime();

    private ManagedConnection(Connection first,Opener opener,Runnable cleanup)throws SQLException{
        physical=first;this.opener=opener;this.cleanup=cleanup;autoCommit=first.getAutoCommit();readOnly=first.isReadOnly();
        try{catalog=first.getCatalog();}catch(SQLException ignored){}
        try{isolation=first.getTransactionIsolation();}catch(SQLException ignored){}
        schema=readSchema(first);
    }
    public static Connection wrap(Connection first,Opener opener,Runnable cleanup)throws SQLException{
        ManagedConnection owner=new ManagedConnection(first,opener,cleanup);
        owner.proxy=(Connection)Proxy.newProxyInstance(ManagedConnection.class.getClassLoader(),new Class<?>[]{Connection.class,SchemaAccess.class},owner);
        owner.heartbeat=TIMER.scheduleWithFixedDelay(owner::keepAlive,15,15,TimeUnit.SECONDS);
        return owner.proxy;
    }
    /** Also used by deterministic transport-failure tests without waiting for a timer. */
    static void maintain(Connection connection){((ManagedConnection)Proxy.getInvocationHandler(connection)).keepAliveNow();}
    private void keepAlive(){synchronized(proxy){if(System.nanoTime()-lastActivity<IDLE_NANOS)return;keepAliveNow();}}
    private void keepAliveNow(){synchronized(proxy){
        if(closed||inTransaction()||resourcesOpen())return;
        try{validateAndRecover();}catch(Exception ignored){broken=true;}
    }}
    private boolean resourcesOpen(){return handles.values().stream().anyMatch(h->h.resource&&!h.closed);}
    private boolean inTransaction(){return !autoCommit||sqlTransaction;}
    private void validateAndRecover()throws SQLException{
        boolean valid=false;
        try{valid=!broken&&!physical.isClosed()&&physical.isValid(5);}catch(SQLException ignored){}catch(AbstractMethodError|UnsupportedOperationException unsupported){return;}
        if(!valid)recover();lastActivity=System.nanoTime();
    }
    private void ready()throws SQLException{
        if(closed)throw new SQLException("La conexión está cerrada.","08003");
        try{if(physical.isClosed())broken=true;}catch(SQLException e){broken=true;}
        if(broken){recover();return;}
        if(!inTransaction()&&!resourcesOpen()&&System.nanoTime()-lastActivity>=IDLE_NANOS)validateAndRecover();
    }
    private void recover()throws SQLException{
        if(inTransaction())throw new SQLException("La conexión se interrumpió durante una transacción. No se reconectó ni se repitieron sentencias. Desconecta y vuelve a conectar para iniciar una sesión nueva.","08007");
        if(resourcesOpen())throw new SQLException("La conexión se interrumpió. Cierra la operación actual y vuelve a intentarlo.","08006");
        Connection replacement=null;
        try{
            replacement=opener.open();replacement.setAutoCommit(true);replacement.setReadOnly(readOnly);
            if(catalog!=null)replacement.setCatalog(catalog);
            if(schema!=null)writeSchema(replacement,schema);
            if(isolation!=null&&isolation!=Connection.TRANSACTION_NONE&&replacement.getTransactionIsolation()!=isolation)replacement.setTransactionIsolation(isolation);
            Connection previous=physical;physical=replacement;replacement=null;broken=false;handles.clear();lastActivity=System.nanoTime();
            try{previous.close();}catch(SQLException ignored){}
        }catch(Exception error){broken=true;throw new SQLException("No se pudo recuperar la conexión. Comprueba la red o el servidor y vuelve a intentarlo. No se ejecutó ninguna sentencia.","08001",error);}
        finally{if(replacement!=null)try{replacement.close();}catch(SQLException ignored){}}
    }
    @Override public Object invoke(Object ignored,Method method,Object[] args)throws Throwable{synchronized(proxy){
        String name=method.getName();
        if(method.getDeclaringClass()==Object.class)return identity(proxy,name,args);
        if(name.equals("isClosed"))return closed;
        if(name.equals("close")){close();return null;}
        if(name.equals("getAutoCommit"))return autoCommit;
        if(name.equals("isReadOnly"))return readOnly;
        if(name.equals("getCatalog"))return catalog;
        if(name.equals("getSchema"))return schema;
        ready();
        if(name.equals("setSchema")){writeSchema(physical,(String)args[0]);schema=(String)args[0];lastActivity=System.nanoTime();return null;}
        if(name.equals("unwrap")&&((Class<?>)args[0]).isInstance(proxy))return proxy;
        if(name.equals("isWrapperFor")&&((Class<?>)args[0]).isInstance(proxy))return true;
        try{
            Object value=method.invoke(physical,args);
            if(name.equals("setAutoCommit")){autoCommit=(Boolean)args[0];if(autoCommit)sqlTransaction=false;}
            if(name.equals("commit")||name.equals("rollback"))sqlTransaction=false;
            if(name.equals("setReadOnly"))readOnly=(Boolean)args[0];
            if(name.equals("setCatalog"))catalog=physical.getCatalog();
            if(name.equals("setTransactionIsolation"))isolation=(Integer)args[0];
            lastActivity=System.nanoTime();Object wrapped=wrapValue(value,null);
            if(value instanceof PreparedStatement&&args!=null&&args.length>0&&args[0] instanceof String sql)handles.get(value).preparedSql=sql;
            return wrapped;
        }catch(InvocationTargetException e){throw failure(e.getCause());}
    }}
    private Throwable failure(Throwable error){
        if(error instanceof SQLException sql){String state=sql.getSQLState();if(state!=null&&state.startsWith("08"))broken=true;else try{if(physical.isClosed())broken=true;}catch(SQLException ignored){broken=true;}
            if(broken&&inTransaction()){SQLException lost=new SQLException("La conexión se interrumpió durante una transacción; no se reconectó ni se repitió la operación. Desconecta y vuelve a conectar.","08007");lost.addSuppressed(sql);return lost;}
        }return error;
    }
    private Object wrapValue(Object value,Handle parent){
        if(value==null)return null;
        Class<?> type=value instanceof CallableStatement?CallableStatement.class:value instanceof PreparedStatement?PreparedStatement.class:value instanceof Statement?Statement.class:value instanceof ResultSet?ResultSet.class:value instanceof DatabaseMetaData?DatabaseMetaData.class:null;
        if(type==null)return value;
        // Metadata has no close method: do not retain each metadata view for the session's lifetime.
        if(type==DatabaseMetaData.class)return new Handle(null,type,parent).wrapper;
        Handle existing=handles.get(value);if(existing!=null&&!existing.closed)return existing.wrapper;
        Handle handle=new Handle(value,type,parent);handles.put(value,handle);return handle.wrapper;
    }
    private final class Handle implements InvocationHandler {
        final Object target,wrapper;final boolean resource;final Handle parent;boolean closed;String preparedSql;
        Handle(Object target,Class<?> type,Handle parent){this.target=target;this.parent=parent;resource=type!=DatabaseMetaData.class;wrapper=Proxy.newProxyInstance(ManagedConnection.class.getClassLoader(),new Class<?>[]{type},this);}
        @Override public Object invoke(Object ignored,Method method,Object[] args)throws Throwable{
            // Drivers implement cancellation concurrently with the executing statement.
            if(method.getName().equals("cancel")){try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
            synchronized(proxy){
                String name=method.getName();if(method.getDeclaringClass()==Object.class)return identity(wrapper,name,args);
                if(name.equals("getConnection"))return proxy;
                if(name.equals("close")&&closed)return null;
                if(closed)throw new SQLException("El resultado o la sentencia ya se cerró.");
                if(!resource)ready();
                try{
                    String sql=name.startsWith("execute")?(args!=null&&args.length>0&&args[0] instanceof String text?text:preparedSql):null;
                    String control=sql==null?"":sql.replaceFirst("(?s)\\A(?:\\s+|--[^\\r\\n]*(?:\\r?\\n|$)|#[^\\r\\n]*(?:\\r?\\n|$)|/\\*.*?\\*/)*","");
                    if(control.matches("(?is)^(BEGIN\\b|START\\s+TRANSACTION\\b|SET\\s+(?:SESSION\\s+)?AUTOCOMMIT\\s*=\\s*0\\b).*"))sqlTransaction=true;
                    Object result=method.invoke(resource?target:physical.getMetaData(),args);
                    if(control.matches("(?is)^(COMMIT\\b|ROLLBACK\\b|SET\\s+(?:SESSION\\s+)?AUTOCOMMIT\\s*=\\s*1\\b).*" )&&!control.matches("(?is)^ROLLBACK\\b.*\\bTO\\b.*"))sqlTransaction=false;
                    if(control.matches("(?is)^(SET|USE)\\b.*")){try{catalog=physical.getCatalog();schema=readSchema(physical);}catch(SQLException stateUnavailable){}}
                    lastActivity=System.nanoTime();return wrapValue(result,this);
                }catch(InvocationTargetException e){throw failure(e.getCause());}
                finally{if(name.equals("close")){closed=true;handles.remove(target);for(Handle handle:new ArrayList<>(handles.values()))if(handle.parent==this){handle.closed=true;handles.remove(handle.target);}}}
            }
        }
    }
    private static Object identity(Object proxy,String name,Object[] args){return switch(name){case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);default->"NexoDB managed JDBC resource";};}
    private void close()throws SQLException{
        if(closed)return;closed=true;heartbeat.cancel(false);try{physical.close();}finally{handles.clear();cleanup.run();}
    }
    private static String readSchema(Connection connection){try{return (String)connection.getClass().getMethod("getSchema").invoke(connection);}catch(Exception|AbstractMethodError ignored){return null;}}
    private static void writeSchema(Connection connection,String schema)throws Exception{connection.getClass().getMethod("setSchema",String.class).invoke(connection,schema);}
}
