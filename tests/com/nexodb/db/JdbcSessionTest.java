package com.nexodb.db;

import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import com.nexodb.model.AuthenticationMode;

import java.util.Properties;

public final class JdbcSessionTest {
    public static void main(String[] args) {
        ConnectionProfile blank = profile(DatabaseType.MARIADB, "root");
        Properties blankProperties = JdbcSession.connectionProperties(blank, new char[0]);
        assertProperty(blankProperties, "user", "root");
        assertProperty(blankProperties, "password", "");
        if (!blankProperties.containsKey("restrictedAuth")) {
            throw new AssertionError("MariaDB password authentication restriction is missing");
        }

        ConnectionProfile exact = profile(DatabaseType.MYSQL, "  usuario con espacios  ");
        Properties exactProperties = JdbcSession.connectionProperties(exact, " clave ".toCharArray());
        assertProperty(exactProperties, "user", "  usuario con espacios  ");
        assertProperty(exactProperties, "password", " clave ");

        ConnectionProfile windows = new ConnectionProfile("", "Windows", DatabaseType.MARIADB,
                "localhost", 3306, "", "root", DatabaseType.MARIADB.buildUrl("localhost", 3306, ""),
                DatabaseType.MARIADB.driverClass(), "", AuthenticationMode.WINDOWS, true);
        Properties windowsProperties = JdbcSession.connectionProperties(windows, "no debe enviarse".toCharArray());
        assertProperty(windowsProperties, "user", "root");
        if (windowsProperties.containsKey("password")) throw new AssertionError("Windows mode must not send a password");
        if (windowsProperties.containsKey("restrictedAuth")) throw new AssertionError("Windows mode must allow GSSAPI");
        System.out.println("JdbcSessionTest: OK");
    }

    private static ConnectionProfile profile(DatabaseType type, String username) {
        return new ConnectionProfile("", "Prueba", type, "localhost", type.defaultPort(), "", username,
                type.buildUrl("localhost", type.defaultPort(), ""), type.driverClass(), "", true);
    }

    private static void assertProperty(Properties properties, String key, String expected) {
        if (!properties.containsKey(key)) throw new AssertionError("Missing property: " + key);
        String actual = properties.getProperty(key);
        if (!expected.equals(actual)) throw new AssertionError(key + " expected [" + expected + "] but got [" + actual + "]");
    }
}
