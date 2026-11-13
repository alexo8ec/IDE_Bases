package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Fresh schema snapshots in two metadata reads, rather than one column read per table. */
public final class SchemaMetadataReader {
    public record SchemaDef(Map<String, DbObject> objects) {
        public SchemaDef { objects = Collections.unmodifiableMap(new LinkedHashMap<>(objects)); }
    }
    public record DbObject(String type, String name, Map<String, ColumnDef> columns) {
        public DbObject { columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns)); }
    }
    public record Pair(SchemaDef source, SchemaDef target) {}
    private record Positioned(int position, ColumnDef column) {}
    private SchemaMetadataReader() {}

    public static boolean catalogNamespace(DatabaseType type) {
        return type == DatabaseType.MYSQL || type == DatabaseType.MARIADB || type == DatabaseType.SQLSERVER;
    }
    public static Pair readPair(JdbcSession source, String sourceName, JdbcSession target, String targetName) throws Exception {
        return readPair(source, sourceName, target, targetName, null);
    }
    public static Pair readPair(JdbcSession source, String sourceName, JdbcSession target, String targetName, Set<String> selected) throws Exception {
        if (source == target) return new Pair(read(source, sourceName, selected), read(target, targetName, selected));
        ExecutorService readers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "nexodb-schema-reader"); thread.setDaemon(true); return thread;
        });
        Future<SchemaDef> left = readers.submit(() -> read(source, sourceName, selected));
        Future<SchemaDef> right = readers.submit(() -> read(target, targetName, selected));
        try { return new Pair(left.get(), right.get()); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw error; }
        finally { left.cancel(true); right.cancel(true); readers.shutdownNow(); }
    }

    public static SchemaDef read(JdbcSession session, String database) throws SQLException {
        return read(session, database, null);
    }
    public static List<DbObject> listObjects(JdbcSession session, String database) throws SQLException {
        synchronized (session.connection()) {
            var metadata = session.connection().getMetaData();
            boolean catalog = catalogNamespace(session.profile().type());
            List<DbObject> names = new ArrayList<>();
            try (ResultSet rows = metadata.getTables(catalog ? database : null, catalog ? null : escapePattern(database, metadata.getSearchStringEscape()), "%", new String[]{"TABLE","VIEW"})) {
                while (rows.next()) names.add(new DbObject("VIEW".equalsIgnoreCase(rows.getString("TABLE_TYPE")) ? "VIEW" : "TABLE", rows.getString("TABLE_NAME"), Map.of()));
            }
            return List.copyOf(names);
        }
    }
    public static SchemaDef read(JdbcSession session, String database, Set<String> selected) throws SQLException {
        synchronized (session.connection()) {
            DatabaseMetaData metadata = session.connection().getMetaData();
            boolean catalogMode = catalogNamespace(session.profile().type());
            String catalog = catalogMode ? database : null;
            String schema = catalogMode ? null : escapePattern(database, metadata.getSearchStringEscape());
            Map<String, String> types = new LinkedHashMap<>();
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, List<Positioned>> columns = new HashMap<>();
            try (ResultSet rows = metadata.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                while (rows.next()) {
                    String name = rows.getString("TABLE_NAME");
                    String type = "VIEW".equalsIgnoreCase(rows.getString("TABLE_TYPE")) ? "VIEW" : "TABLE";
                    String key = key(type, name);
                    if (selected != null && !selected.contains(key)) continue;
                    types.put(key, type); names.put(key, name);
                    if (type.equals("TABLE")) columns.put(name.toLowerCase(Locale.ROOT), new ArrayList<>());
                }
            }
            // Close the table result set before issuing another request on this connection.
            List<String> patterns = selected != null && columns.size() <= 2
                    ? names.entrySet().stream().filter(e -> types.get(e.getKey()).equals("TABLE"))
                        .map(e -> escapePattern(e.getValue(), safeEscape(metadata))).toList() : List.of("%");
            if (!columns.isEmpty()) for (String pattern : patterns) try (ResultSet rows = metadata.getColumns(catalog, schema, pattern, "%")) {
                while (rows.next()) {
                    String table = rows.getString("TABLE_NAME");
                    List<Positioned> tableColumns = columns.get(table.toLowerCase(Locale.ROOT));
                    if (tableColumns == null) continue;
                    ColumnDef definition = new ColumnDef(rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME"),
                            rows.getInt("COLUMN_SIZE"), rows.getInt("DECIMAL_DIGITS"),
                            rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            rows.getString("COLUMN_DEF"), optional(rows, "IS_AUTOINCREMENT"));
                    tableColumns.add(new Positioned(rows.getInt("ORDINAL_POSITION"), definition));
                }
            }
            Map<String, DbObject> objects = new LinkedHashMap<>();
            for (String key : types.keySet()) {
                String name = names.get(key);
                Map<String, ColumnDef> definitions = new LinkedHashMap<>();
                List<Positioned> ordered = columns.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
                if (types.get(key).equals("TABLE")) ordered.stream().sorted(Comparator.comparingInt(Positioned::position))
                        .forEach(column -> definitions.put(column.column().name().toLowerCase(Locale.ROOT), column.column()));
                objects.put(key, new DbObject(types.get(key), name, definitions));
            }
            return new SchemaDef(objects);
        }
    }
    public static String key(String type, String name) { return type.toLowerCase(Locale.ROOT) + ":" + name.toLowerCase(Locale.ROOT); }
    private static String safeEscape(DatabaseMetaData metadata) { try { return metadata.getSearchStringEscape(); } catch (SQLException ignored) { return ""; } }
    private static String escapePattern(String value, String escape) {
        if (escape == null || escape.isEmpty()) return value;
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
    private static String optional(ResultSet rows, String name) { try { return rows.getString(name); } catch (SQLException ignored) { return ""; } }

    public record ColumnDef(String name, String type, int size, int decimals, boolean nullable,
                            String defaultValue, String autoIncrement) {
        public String sqlDefinition() {
            String upper = type == null ? "" : type.toUpperCase(Locale.ROOT);
            boolean sized = upper.contains("CHAR") || upper.contains("BINARY") || upper.equals("DECIMAL") || upper.equals("NUMERIC");
            StringBuilder value = new StringBuilder(type == null ? "" : type);
            if (sized && size > 0) value.append('(').append(size).append(decimals > 0 ? "," + decimals : "").append(')');
            value.append(nullable ? " NULL" : " NOT NULL");
            if (defaultValue != null) value.append(" DEFAULT ").append(defaultValue);
            if ("YES".equalsIgnoreCase(autoIncrement)) value.append(" AUTO_INCREMENT");
            return value.toString();
        }
        public String normalized() { return sqlDefinition().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT); }
    }
}
