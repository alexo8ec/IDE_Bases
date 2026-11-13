package com.nexodb.model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public enum DatabaseType {
    POSTGRESQL("PostgreSQL", 5432, "org.postgresql.Driver"),
    MYSQL("MySQL", 3306, "com.mysql.cj.jdbc.Driver"),
    MARIADB("MariaDB", 3306, "org.mariadb.jdbc.Driver"),
    SQLSERVER("SQL Server", 1433, "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
    ORACLE("Oracle", 1521, "oracle.jdbc.OracleDriver"),
    SQLITE("SQLite", 0, "org.sqlite.JDBC"),
    H2("H2", 0, "org.h2.Driver"),
    MONGODB("MongoDB (Atlas SQL)", 27017, "com.mongodb.jdbc.MongoDriver"),
    GENERIC("JDBC genérico", 0, "");

    private final String displayName;
    private final int defaultPort;
    private final String driverClass;

    DatabaseType(String displayName, int defaultPort, String driverClass) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.driverClass = driverClass;
    }

    public String displayName() { return displayName; }
    public int defaultPort() { return defaultPort; }
    public String driverClass() { return driverClass; }

    public String buildUrl(String host, int port, String database) {
        String h = host == null || host.isBlank() ? "localhost" : host.trim();
        String db = database == null ? "" : database.trim();
        int p = port > 0 ? port : defaultPort;
        return switch (this) {
            case POSTGRESQL -> "jdbc:postgresql://" + h + ":" + p + "/"
                    + URLEncoder.encode(db.isBlank() ? "postgres" : db, StandardCharsets.UTF_8).replace("+", "%20");
            case MYSQL -> "jdbc:mysql://" + h + ":" + p + "/" + db;
            case MARIADB -> "jdbc:mariadb://" + h + ":" + p + "/" + db + (isLocalHost(h) ? "?sslMode=disable" : "");
            case SQLSERVER -> "jdbc:sqlserver://" + h + ":" + p + ";databaseName=" + db + ";encrypt=true;trustServerCertificate=true";
            case ORACLE -> "jdbc:oracle:thin:@//" + h + ":" + p + "/" + db;
            case SQLITE -> "jdbc:sqlite:" + db;
            case H2 -> "jdbc:h2:" + db;
            case MONGODB -> "jdbc:mongodb://" + h + ":" + p + "/" + db;
            case GENERIC -> "jdbc:";
        };
    }

    private static boolean isLocalHost(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    @Override
    public String toString() { return displayName; }
}

