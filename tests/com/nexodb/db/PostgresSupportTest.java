package com.nexodb.db;

import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;

import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public final class PostgresSupportTest {
    private static final List<String> attempts = new ArrayList<>();
    private static final Map<String, String> failures = new HashMap<>();
    private static Properties received;
    private static String discoverySql;
    private static int closed;
    private static boolean emptyDatabases;

    public static void main(String[] args) throws Exception {
        ConnectionProfile blank = profile("", "jdbc:postgresql://server:5433/?sslmode=require&connectTimeout=8");
        if (!PostgresSupport.needsDatabaseSelection(blank)) throw new AssertionError("Blank database must be discovered");
        try (JdbcSession session = JdbcSession.open(blank, "secret".toCharArray())) {
            equal("jdbc:postgresql://server:5433/postgres?sslmode=require&connectTimeout=8", attempts.get(0));
            equal("drnet_user", received.getProperty("user"));
            equal("secret", received.getProperty("password"));
            equal(List.of("app", "ventas"), PostgresSupport.databases(session.connection()));
            for (String required : List.of("datallowconn", "NOT datistemplate", "has_database_privilege(current_user, oid, 'CONNECT')")) {
                if (!discoverySql.contains(required)) throw new AssertionError("Discovery must filter permissions and templates: " + discoverySql);
            }
        }
        equal(1, closed);

        ConnectionProfile chosen = PostgresSupport.withDatabase(blank, "ventas / año?&");
        equal("jdbc:postgresql://server:5433/ventas%20%2F%20a%C3%B1o%3F%26?sslmode=require&connectTimeout=8", chosen.jdbcUrl());
        equal("ventas / año?&", chosen.database());
        equal(blank.username(), chosen.username());
        equal(blank.driverJar(), chosen.driverJar());
        equal(blank.readOnly(), chosen.readOnly());
        if (PostgresSupport.needsDatabaseSelection(chosen)) throw new AssertionError("Explicit database must be honored");
        if (PostgresSupport.needsDatabaseSelection(profile("", "jdbc:postgresql://server:5433/custom?sslmode=require"))) {
            throw new AssertionError("Custom JDBC database must be honored");
        }
        equal("jdbc:postgresql://server:5433/postgres?sslmode=disable",
                PostgresSupport.initialProfile(profile("", "jdbc:postgresql://server:5433?sslmode=disable")).jdbcUrl());

        for (String state : List.of("3D000", "42501")) {
            reset();
            failures.put("postgres", state);
            try (JdbcSession session = JdbcSession.open(blank, new char[0])) {
                equal("template1", session.connection().getCatalog());
                equal(2, attempts.size());
                equal("", received.getProperty("password"));
            }
        }
        reset();
        failures.put("postgres", "28P01");
        expectFailure(blank, "28P01", 1, null);
        reset();
        failures.put("app", "3D000");
        expectFailure(PostgresSupport.withDatabase(blank, "app"), "3D000", 1, null);
        reset();
        failures.put("postgres", "42501");
        failures.put("template1", "42501");
        expectFailure(blank, "42501", 2, "Indica una base existente");
        reset();
        emptyDatabases = true;
        try (JdbcSession session = JdbcSession.open(blank, new char[0])) {
            try {
                PostgresSupport.databases(session.connection());
                throw new AssertionError("Empty discovery needs a useful error");
            } catch (SQLException expected) {
                if (!expected.getMessage().contains("CONNECT")) throw expected;
            }
        }
        System.out.println("PostgresSupportTest: OK");
    }

    private static void reset() {
        attempts.clear();
        failures.clear();
    }

    private static void expectFailure(ConnectionProfile profile, String state, int count, String message) throws Exception {
        try (JdbcSession ignored = JdbcSession.open(profile, new char[0])) {
            throw new AssertionError("Expected connection failure");
        } catch (SQLException expected) {
            equal(state, expected.getSQLState());
            equal(count, attempts.size());
            if (message != null && !expected.getMessage().contains(message)) throw expected;
        }
    }

    private static ConnectionProfile profile(String database, String url) {
        // Any existing jar exercises the real loader; the test driver is resolved from its parent.
        return new ConnectionProfile("pg", "PostgreSQL", DatabaseType.POSTGRESQL, "server", 5433,
                database, "drnet_user", url, StubDriver.class.getName(),
                "build/drivers/mysql-connector-j-26.10.0.jar", true);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static final class StubDriver implements Driver {
        public Connection connect(String url, Properties properties) throws SQLException {
            attempts.add(url);
            received = properties;
            String database = url.substring(url.lastIndexOf('/') + 1).split("\\?", 2)[0];
            if (failures.containsKey(database)) throw new SQLException("Connection rejected", failures.get(database));
            boolean[] isClosed = {false};
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
                return switch (method.getName()) {
                    case "setReadOnly", "setAutoCommit" -> null;
                    case "getAutoCommit" -> true;
                    case "getCatalog" -> database;
                    case "isClosed" -> isClosed[0];
                    case "close" -> { isClosed[0] = true; closed++; yield null; }
                    case "createStatement" -> statement();
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, (p, method, args) -> {
                if (method.getName().equals("close")) return null;
                if (!method.getName().equals("executeQuery")) throw new UnsupportedOperationException(method.getName());
                discoverySql = (String) args[0];
                var rows = RowSetProvider.newFactory().createCachedRowSet();
                var metadata = new RowSetMetaDataImpl();
                metadata.setColumnCount(1);
                metadata.setColumnType(1, Types.VARCHAR);
                metadata.setColumnName(1, "datname");
                rows.setMetaData(metadata);
                if (!emptyDatabases) for (String name : List.of("ventas", "app")) {
                    rows.moveToInsertRow();
                    rows.updateString(1, name);
                    rows.insertRow();
                    rows.moveToCurrentRow();
                }
                rows.beforeFirst();
                return rows;
            });
        }

        public boolean acceptsURL(String url) { return url.startsWith("jdbc:postgresql:"); }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
