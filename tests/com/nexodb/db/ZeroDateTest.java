package com.nexodb.db;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicInteger;

public final class ZeroDateTest {
    public static void main(String[] args) throws Exception {
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
                ZeroDateTest.class.getClassLoader(), new Class[]{ResultSetMetaData.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getColumnCount" -> 1;
                    case "getColumnLabel", "getColumnName" -> "created_at";
                    case "getColumnType" -> Types.TIMESTAMP;
                    default -> defaultValue(method.getReturnType());
                });
        AtomicInteger cursor = new AtomicInteger();
        ResultSet rows = (ResultSet) Proxy.newProxyInstance(
                ZeroDateTest.class.getClassLoader(), new Class[]{ResultSet.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> cursor.getAndIncrement() == 0;
                    case "getString" -> "0000-00-00 00:00:00";
                    case "getObject" -> throw new SQLException("Zero date value prohibited");
                    default -> defaultValue(method.getReturnType());
                });
        QueryResult result = QueryResult.from(rows, 10);
        Object value = result.rows().get(0).get(0);
        if (!"0000-00-00 00:00:00".equals(value)) throw new AssertionError("Zero date was not preserved: " + value);
        System.out.println("ZeroDateTest: OK");
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }
}
