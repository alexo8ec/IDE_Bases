package com.nexodb.db;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ManagedConnectionTest {
    public static void main(String[] args)throws Exception {
        AtomicInteger opened=new AtomicInteger(),cleaned=new AtomicInteger();Wire first=new Wire(),next=new Wire();
        Connection managed=ManagedConnection.wrap(first.connection(),()->{opened.incrementAndGet();return next.connection();},cleaned::incrementAndGet);
        managed.setCatalog("chosen_database");managed.setSchema("reporting");managed.setReadOnly(true);managed.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        try(Statement active=managed.createStatement()){int validations=first.validations;ManagedConnection.maintain(managed);equal(validations,first.validations);}
        ManagedConnection.maintain(managed);equal(1,first.validations);
        first.valid=false;ManagedConnection.maintain(managed);equal(1,opened.get());equal("chosen_database",next.catalog);equal("reporting",next.schema);equal(true,next.readOnly);equal(Connection.TRANSACTION_SERIALIZABLE,next.isolation);equal(0,next.executions);
        managed.close();equal(1,cleaned.get());ManagedConnection.maintain(managed);equal(1,opened.get());

        Wire lost=new Wire();opened.set(0);cleaned.set(0);
        managed=ManagedConnection.wrap(lost.connection(),()->{opened.incrementAndGet();return new Wire().connection();},cleaned::incrementAndGet);
        managed.setAutoCommit(false);lost.valid=false;lost.closed=true;ManagedConnection.maintain(managed);equal(0,lost.validations);equal(0,opened.get());
        try{managed.createStatement();throw new AssertionError("A lost transaction must not reconnect");}catch(SQLException error){equal("08007",error.getSQLState());}
        managed.close();equal(1,cleaned.get());

        Wire explicit=new Wire();opened.set(0);managed=ManagedConnection.wrap(explicit.connection(),()->{opened.incrementAndGet();return new Wire().connection();},()->{});
        try(Statement statement=managed.createStatement()){statement.execute("-- Manual transaction\nBEGIN");}
        explicit.closed=true;ManagedConnection.maintain(managed);equal(0,explicit.validations);equal(0,opened.get());
        try{managed.createStatement();throw new AssertionError("Explicit BEGIN must not be silently discarded");}catch(SQLException error){equal("08007",error.getSQLState());}managed.close();

        Wire ambiguous=new Wire(),fresh=new Wire();opened.set(0);managed=ManagedConnection.wrap(ambiguous.connection(),()->{opened.incrementAndGet();return fresh.connection();},()->{});
        ambiguous.failAfterExecute=true;
        try(Statement statement=managed.createStatement()){try{statement.execute("UPDATE fixture SET value=2");throw new AssertionError("Expected connection reset");}catch(SQLException error){equal("08006",error.getSQLState());}}
        equal(1,ambiguous.executions);equal(0,fresh.executions);equal(0,opened.get());
        try(Statement statement=managed.createStatement()){equal(1,opened.get());equal(0,fresh.executions);}
        managed.close();
        System.out.println("ManagedConnectionTest: OK (idle heartbeat, open-resource/transaction exclusion, selected database/schema/read-only/isolation restoration, no SQL replay and cleanup)");
    }
    private static final class Wire {
        boolean valid=true,closed,autoCommit=true,readOnly,failAfterExecute;String catalog="initial",schema="public";int isolation=Connection.TRANSACTION_READ_COMMITTED,validations,executions;
        Connection connection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->switch(method.getName()){
            case "getAutoCommit"->autoCommit;case "setAutoCommit"->{autoCommit=(Boolean)args[0];yield null;}
            case "isReadOnly"->readOnly;case "setReadOnly"->{readOnly=(Boolean)args[0];yield null;}
            case "getCatalog"->catalog;case "setCatalog"->{catalog=(String)args[0];yield null;}
            case "getSchema"->schema;case "setSchema"->{schema=(String)args[0];yield null;}
            case "getTransactionIsolation"->isolation;case "setTransactionIsolation"->{isolation=(Integer)args[0];yield null;}
            case "isValid"->{validations++;yield valid&&!closed;}case "isClosed"->closed;case "close"->{closed=true;yield null;}
            case "createStatement"->Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{if(m.getName().equals("execute")){executions++;if(failAfterExecute){closed=true;throw new SQLException("Connection reset","08006");}return false;}return null;});
            default->null;
        });}
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
