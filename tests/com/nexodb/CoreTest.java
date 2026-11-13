package com.nexodb;

import com.nexodb.db.QueryResult;
import com.nexodb.db.SchemaItem;
import com.nexodb.model.DatabaseType;
import com.nexodb.update.UpdateService;
import com.nexodb.util.CsvExporter;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.Types;

public final class CoreTest {
    public static void main(String[] args) throws Exception {
        assertEquals("jdbc:postgresql://db.local:5432/app", DatabaseType.POSTGRESQL.buildUrl("db.local", 5432, "app"));
        assertEquals("jdbc:postgresql://db.local:5433/postgres", DatabaseType.POSTGRESQL.buildUrl("db.local", 5433, ""));
        assertEquals("jdbc:postgresql://localhost:5432/postgres", DatabaseType.POSTGRESQL.buildUrl("", 0, null));
        assertEquals("jdbc:postgresql://db.local:5432/ventas%20%2F%20a%C3%B1o%3F%26",
                DatabaseType.POSTGRESQL.buildUrl("db.local", 5432, "ventas / año?&"));
        assertEquals("jdbc:sqlite:C:\\data\\app.db", DatabaseType.SQLITE.buildUrl("", 0, "C:\\data\\app.db"));
        assertEquals("jdbc:mysql://localhost:3306/", DatabaseType.MYSQL.buildUrl("localhost", 3306, ""));
        assertEquals("jdbc:mariadb://localhost:3306/?sslMode=disable", DatabaseType.MARIADB.buildUrl("localhost", 3306, ""));
        assertEquals("aitrol_db.access_roles", new SchemaItem(SchemaItem.Kind.TABLE, "aitrol_db", null, "access_roles", "").qualifiedName());
        UpdateService.UpdateInfo update = UpdateService.parseManifest("{\"version\":\"1.0.0\",\"downloadUrl\":\"https://example.com/nexodb.zip\",\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"notes\":\"Mejoras\"}", "windows-x64");
        if (!UpdateService.isNewer(update.version(), UpdateService.CURRENT_VERSION)) throw new AssertionError("Update version comparison failed");
        if (UpdateService.isNewer("0.3.9", UpdateService.CURRENT_VERSION)) throw new AssertionError("Older version reported as new");

        Path mysqlDriver = Path.of("build", "drivers", "mysql-connector-j-26.10.0.jar");
        if (!Files.isRegularFile(mysqlDriver)) throw new AssertionError("Bundled MySQL driver is missing");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{mysqlDriver.toUri().toURL()}, CoreTest.class.getClassLoader())) {
            Driver driver = (Driver) Class.forName("com.mysql.cj.jdbc.Driver", true, loader).getDeclaredConstructor().newInstance();
            if (!driver.acceptsURL("jdbc:mysql://localhost:3306/")) throw new AssertionError("MySQL driver rejected its JDBC URL");
        }
        Path mariaDbDriver = Path.of("build", "drivers", "mariadb-java-client-4.0.0.jar");
        if (!Files.isRegularFile(mariaDbDriver)) throw new AssertionError("Bundled MariaDB driver is missing");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{mariaDbDriver.toUri().toURL()}, CoreTest.class.getClassLoader())) {
            Driver driver = (Driver) Class.forName("org.mariadb.jdbc.Driver", true, loader).getDeclaredConstructor().newInstance();
            if (!driver.acceptsURL("jdbc:mariadb://localhost:3306/")) throw new AssertionError("MariaDB driver rejected its JDBC URL");
        }

        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(2);
        metadata.setColumnName(1, "id");
        metadata.setColumnLabel(1, "id");
        metadata.setColumnType(1, Types.INTEGER);
        metadata.setColumnName(2, "name");
        metadata.setColumnLabel(2, "name");
        metadata.setColumnType(2, Types.VARCHAR);
        rows.setMetaData(metadata);
        insert(rows, 1, "Ana, \"A\"");
        insert(rows, 2, "Luis");
        rows.beforeFirst();

        QueryResult result = QueryResult.from(rows, 1);
        if (result.rows().size() != 1 || !result.truncated()) throw new AssertionError("Row limit was not applied");

        rows.beforeFirst();
        QueryResult csvResult = QueryResult.from(rows, 2);
        Path csv = Files.createTempFile("nexodb-test-", ".csv");
        try {
            CsvExporter.write(csvResult, csv);
            String text = Files.readString(csv, StandardCharsets.UTF_8);
            if (!text.contains("\"Ana, \"\"A\"\"\"")) throw new AssertionError("CSV escaping failed: " + text);
        } finally {
            Files.deleteIfExists(csv);
        }
        System.out.println("CoreTest: OK");
    }

    private static void insert(CachedRowSet rows, int id, String name) throws Exception {
        rows.moveToInsertRow();
        rows.updateInt(1, id);
        rows.updateString(2, name);
        rows.insertRow();
        rows.moveToCurrentRow();
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
    }
}

