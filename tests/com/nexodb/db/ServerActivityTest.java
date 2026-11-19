package com.nexodb.db;

import com.nexodb.model.DatabaseType;

public final class ServerActivityTest {
    public static void main(String[] args){
        contains(DatabaseType.MYSQL,"PROCESSLIST");contains(DatabaseType.MARIADB,"PROCESSLIST");contains(DatabaseType.POSTGRESQL,"pg_stat_activity");contains(DatabaseType.SQLSERVER,"dm_exec_sessions");contains(DatabaseType.ORACLE,"v$session");contains(DatabaseType.H2,"INFORMATION_SCHEMA.SESSIONS");if(ServerActivity.supported(DatabaseType.SQLITE))throw new AssertionError("SQLite should use informational unsupported mode");
        System.out.println("ServerActivityTest: OK (dialect-aware, read-only server monitor queries)");
    }
    private static void contains(DatabaseType type,String expected){String sql=ServerActivity.activitySql(type);if(!sql.contains(expected)||!sql.toLowerCase().contains("session_id"))throw new AssertionError(type+" monitor SQL invalid");}
}
