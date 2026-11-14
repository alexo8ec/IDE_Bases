package com.nexodb.db;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public record QueryResult(List<String> columns, List<List<Object>> rows, int updateCount, long elapsedMillis, boolean truncated,
                          QueryEditSupport.Eligibility editing, List<Origin> origins) {
    public record Origin(String catalog,String schema,String table,String column) {}
    public QueryResult { origins=origins==null?List.of():List.copyOf(origins); }
    public QueryResult(List<String> columns,List<List<Object>> rows,int updateCount,long elapsedMillis,boolean truncated,QueryEditSupport.Eligibility editing){this(columns,rows,updateCount,elapsedMillis,truncated,editing,List.of());}
    public QueryResult(List<String> columns, List<List<Object>> rows, int updateCount, long elapsedMillis, boolean truncated) {
        this(columns, rows, updateCount, elapsedMillis, truncated, null);
    }
    public static QueryResult from(ResultSet resultSet, int maxRows) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        List<Origin> origins = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = metadata.getColumnLabel(i);
            columns.add(label == null || label.isBlank() ? metadata.getColumnName(i) : label);
            try {origins.add(new Origin(metadata.getCatalogName(i),metadata.getSchemaName(i),metadata.getTableName(i),metadata.getColumnName(i)));}
            catch(SQLException | AbstractMethodError unavailable){origins.add(new Origin("","","",""));}
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) row.add(displayValue(readValue(resultSet, metadata, i)));
            rows.add(row);
        }
        return new QueryResult(List.copyOf(columns), List.copyOf(rows), -1, 0, truncated,null,origins);
    }

    public static QueryResult update(int count, long elapsed) {
        return new QueryResult(List.of(), List.of(), count, elapsed, false);
    }

    public QueryResult withElapsed(long elapsed) {
        return new QueryResult(columns, rows, updateCount, elapsed, truncated, editing,origins);
    }

    public QueryResult withEditing(QueryEditSupport.Eligibility eligibility) {
        return new QueryResult(columns, rows, updateCount, elapsedMillis, truncated, eligibility,origins);
    }

    public boolean hasRows() { return !columns.isEmpty(); }

    private static Object displayValue(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof java.sql.Clob clob) {
            long length = Math.min(clob.length(), 10_000);
            return clob.getSubString(1, (int) length);
        }
        if (value instanceof java.sql.Blob blob) return "<BLOB: " + blob.length() + " bytes>";
        if (value instanceof byte[] bytes) return "<BINARIO: " + bytes.length + " bytes>";
        return value;
    }

    private static Object readValue(ResultSet resultSet, ResultSetMetaData metadata, int column) throws SQLException {
        int type = metadata.getColumnType(column);
        return switch (type) {
            case java.sql.Types.DATE, java.sql.Types.TIME, java.sql.Types.TIME_WITH_TIMEZONE,
                    java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> resultSet.getString(column);
            default -> resultSet.getObject(column);
        };
    }
}

