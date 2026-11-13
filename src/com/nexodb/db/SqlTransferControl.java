package com.nexodb.db;

import java.sql.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SqlTransferControl {
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile Statement active;
    public void cancel(){cancelled.set(true);Statement current=active;if(current!=null)try{current.cancel();}catch(SQLException ignored){}}
    public void check(){if(cancelled.get())throw new CancellationException();}
    public void active(Statement statement){active=statement;if(statement!=null)check();}
}
