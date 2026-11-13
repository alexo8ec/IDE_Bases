package com.nexodb.db;

import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PostgreSQL needs an initial database even when browsing a server. */
public final class PostgresSupport {
    private static final Pattern URL = Pattern.compile("^(jdbc:postgresql://[^/?]+)(/[^?]*)?(\\?.*)?$");

    private PostgresSupport() {}

    public static boolean needsDatabaseSelection(ConnectionProfile profile) {
        if (profile.type() != DatabaseType.POSTGRESQL || !profile.database().isBlank()) return false;
        Matcher match = URL.matcher(profile.jdbcUrl());
        if (!match.matches()) return false;
        String path = match.group(2);
        return path == null || path.equals("/") || path.equals("/postgres") || path.equals("/template1");
    }

    public static ConnectionProfile withDatabase(ConnectionProfile profile, String database) {
        return replaceDatabase(profile, database, database);
    }

    static ConnectionProfile initialProfile(ConnectionProfile profile) {
        Matcher match = URL.matcher(profile.jdbcUrl());
        if (profile.type() == DatabaseType.POSTGRESQL && match.matches()
                && (match.group(2) == null || match.group(2).equals("/"))) {
            String database = profile.database().isBlank() ? "postgres" : profile.database();
            return replaceDatabase(profile, database, profile.database());
        }
        return profile;
    }

    static ConnectionProfile alternateInitialProfile(ConnectionProfile profile) {
        return replaceDatabase(profile, "template1", "");
    }

    private static ConnectionProfile replaceDatabase(ConnectionProfile profile, String database, String savedDatabase) {
        Matcher match = URL.matcher(profile.jdbcUrl());
        if (!match.matches()) throw new IllegalArgumentException("La URL PostgreSQL debe indicar el servidor y el puerto.");
        String encoded = URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
        String url = match.group(1) + "/" + encoded + (match.group(3) == null ? "" : match.group(3));
        return new ConnectionProfile(profile.id(), profile.name(), profile.type(), profile.host(), profile.port(),
                savedDatabase, profile.username(), url, profile.driverClass(), profile.driverJar(),
                profile.authenticationMode(), profile.readOnly());
    }

    public static List<String> databases(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT datname FROM pg_catalog.pg_database "
                     + "WHERE datallowconn AND NOT datistemplate "
                     + "AND pg_catalog.has_database_privilege(current_user, oid, 'CONNECT') ORDER BY datname")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        if (names.isEmpty()) throw new SQLException("El usuario no tiene bases de datos disponibles con permiso CONNECT.");
        return List.copyOf(names);
    }
}
