package com.nexodb.model;

import java.util.Objects;

public record ConnectionProfile(
        String id,
        String name,
        DatabaseType type,
        String host,
        int port,
        String database,
        String username,
        String jdbcUrl,
        String driverClass,
        String driverJar,
        AuthenticationMode authenticationMode,
        SshConfig ssh,
        boolean readOnly
) {
    public ConnectionProfile {
        id = value(id);
        name = value(name);
        type = Objects.requireNonNullElse(type, DatabaseType.GENERIC);
        host = value(host);
        database = value(database);
        username = username == null ? "" : username;
        jdbcUrl = value(jdbcUrl);
        driverClass = value(driverClass);
        driverJar = value(driverJar);
        authenticationMode = Objects.requireNonNullElse(authenticationMode, AuthenticationMode.DATABASE);
        ssh = Objects.requireNonNullElse(ssh,SshConfig.disabled());
    }

    public ConnectionProfile(String id,String name,DatabaseType type,String host,int port,String database,
                             String username,String jdbcUrl,String driverClass,String driverJar,
                             AuthenticationMode authenticationMode,boolean readOnly) {
        this(id,name,type,host,port,database,username,jdbcUrl,driverClass,driverJar,authenticationMode,SshConfig.disabled(),readOnly);
    }

    public ConnectionProfile(String id, String name, DatabaseType type, String host, int port, String database,
                             String username, String jdbcUrl, String driverClass, String driverJar, boolean readOnly) {
        this(id, name, type, host, port, database, username, jdbcUrl, driverClass, driverJar,
                AuthenticationMode.DATABASE, SshConfig.disabled(), readOnly);
    }

    private static String value(String text) {
        return text == null ? "" : text.trim();
    }

    @Override
    public String toString() {
        return name.isBlank() ? type.displayName() : name;
    }
}

