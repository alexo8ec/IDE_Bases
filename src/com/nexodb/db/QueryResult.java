package com.nexodb.db;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record QueryResult(List<String> columns, List<List<Object>> rows, int updateCount, long elapsedMillis, boolean truncated,
                          QueryEditSupport.Eligibility editing, List<Origin> origins, List<Integer> jdbcTypes) {
    public record Origin(String catalog,String schema,String table,String column) {}
    public QueryResult {
        origins=origins==null?List.of():List.copyOf(origins);
        jdbcTypes=jdbcTypes==null||jdbcTypes.size()!=columns.size()
                ?Collections.nCopies(columns.size(),Types.OTHER):List.copyOf(jdbcTypes);
    }
    public QueryResult(List<String> columns,List<List<Object>> rows,int updateCount,long elapsedMillis,boolean truncated,
                       QueryEditSupport.Eligibility editing,List<Origin> origins){this(columns,rows,updateCount,elapsedMillis,truncated,editing,origins,List.of());}
    public QueryResult(List<String> columns,List<List<Object>> rows,int updateCount,long elapsedMillis,boolean truncated,QueryEditSupport.Eligibility editing){this(columns,rows,updateCount,elapsedMillis,truncated,editing,List.of(),List.of());}
    public QueryResult(List<String> columns, List<List<Object>> rows, int updateCount, long elapsedMillis, boolean truncated) {
        this(columns, rows, updateCount, elapsedMillis, truncated, null);
    }
    public static QueryResult from(ResultSet resultSet, int maxRows) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        List<Origin> origins = new ArrayList<>(columnCount);
        List<Integer> jdbcTypes = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = metadata.getColumnLabel(i);
            columns.add(label == null || label.isBlank() ? metadata.getColumnName(i) : label);
            jdbcTypes.add(metadata.getColumnType(i));
            try {origins.add(new Origin(metadata.getCatalogName(i),base(metadata,"getBaseSchemaName",i,metadata.getSchemaName(i)),base(metadata,"getBaseTableName",i,metadata.getTableName(i)),base(metadata,"getBaseColumnName",i,metadata.getColumnName(i))));}
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
        return new QueryResult(List.copyOf(columns), List.copyOf(rows), -1, 0, truncated,null,origins,jdbcTypes);
    }

    public static QueryResult update(int count, long elapsed) {
        return new QueryResult(List.of(), List.of(), count, elapsed, false);
    }

    public QueryResult withElapsed(long elapsed) {
        return new QueryResult(columns, rows, updateCount, elapsed, truncated, editing,origins,jdbcTypes);
    }

    public QueryResult withEditing(QueryEditSupport.Eligibility eligibility) {
        return new QueryResult(columns, rows, updateCount, elapsedMillis, truncated, eligibility,origins,jdbcTypes);
    }

    public boolean hasRows() { return !columns.isEmpty(); }
    private static String base(ResultSetMetaData metadata,String method,int column,String fallback){
        // PostgreSQL exposes physical origins separately from aliases through its metadata extension.
        try{return (String)metadata.getClass().getMethod(method,int.class).invoke(metadata,column);}catch(ReflectiveOperationException|SecurityException unavailable){return fallback;}
    }

    private static Object displayValue(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof java.sql.Clob clob) return clob.getSubString(1, Math.toIntExact(clob.length()));
        if (value instanceof java.sql.SQLXML xml) return xml.getString();
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

