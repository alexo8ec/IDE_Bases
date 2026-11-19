package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.util.*;

public final class ServerHealthTest {
    public static void main(String[] args){
        ServerActivity.Session a=session("11","22"),b=session("22","33"),c=session("33","11"),free=session("44","");
        var cycles=ServerHealth.deadlocks(List.of(a,b,c,free));require(cycles.size()==1,"Expected one deadlock cycle");require(new HashSet<>(cycles.get(0).sessions()).equals(Set.of("11","22","33")),"Wrong cycle members");require(ServerHealth.deadlocks(List.of(a,session("22",""))).isEmpty(),"A wait chain is not a deadlock");
        contains(DatabaseType.POSTGRESQL,"pg_stat_replication");contains(DatabaseType.SQLSERVER,"dm_hadr_database_replica_states");contains(DatabaseType.ORACLE,"v$archive_dest_status");require(ServerHealth.mysqlWaitSql().size()>=2,"MySQL wait fallback missing");require(ServerHealth.mysqlWaitSql().get(0).contains("data_lock_waits"),"MySQL 8 wait graph missing");
        System.out.println("ServerHealthTest: OK (deadlocks, waits and replication dialects)");
    }
    private static ServerActivity.Session session(String id,String blocked){return new ServerActivity.Session(id,"","","",5,"",blocked,"","",false);}
    private static void contains(DatabaseType type,String value){require(ServerHealth.replicationSql(type).contains(value),type+" replication SQL invalid");}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
