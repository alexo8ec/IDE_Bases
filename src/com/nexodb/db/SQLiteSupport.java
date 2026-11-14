package com.nexodb.db;

import static com.nexodb.util.I18n.tr;
import com.nexodb.model.*;
import java.net.URI;
import java.nio.file.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.sql.*;
import java.lang.reflect.*;

/** Opens existing local SQLite files in place. A misspelled path never creates a database. */
public final class SQLiteSupport {
    private SQLiteSupport() {}
    /** Xerial cannot switch JDBC read-only after a SELECT. Enforce it with SQLite's
     * connection-local query_only pragma, while preserving the file opening mode. */
    static Connection wrap(Connection nativeConnection, boolean fileReadOnly) throws SQLException {
        final boolean[] readOnly = {nativeConnection.isReadOnly()};
        return (Connection) Proxy.newProxyInstance(SQLiteSupport.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("isReadOnly")) return readOnly[0];
            if (name.equals("setReadOnly")) {
                boolean requested = (Boolean) args[0];
                if (fileReadOnly && !requested) throw new SQLException(tr("Esta conexión abre el archivo SQLite en solo lectura. Edita el perfil para permitir escritura."));
                if (requested == readOnly[0]) return null;
                if (!nativeConnection.getAutoCommit()) throw new SQLException(tr("Finaliza la transacción antes de cambiar el modo de lectura."));
                try (Statement s = nativeConnection.createStatement()) { s.execute(requested ? "PRAGMA query_only=ON" : "PRAGMA query_only=OFF"); }
                readOnly[0] = requested; return null;
            }
            try { return method.invoke(nativeConnection, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    public static ConnectionProfile prepare(ConnectionProfile profile) throws Exception {
        if (profile.type() != DatabaseType.SQLITE) return profile;
        String url = profile.jdbcUrl();
        String location = url.startsWith("jdbc:sqlite:") ? url.substring("jdbc:sqlite:".length()) : profile.database();
        if (location.isBlank()) throw new IllegalArgumentException(tr("Selecciona un archivo SQLite existente."));
        if (location.equals(":memory:")) return profile;
        Path file;
        try {
            // Ignore URL options here; the application controls the opening mode below.
            if (location.startsWith("file:")) {
                int query = location.indexOf('?');
                file = Path.of(URI.create(query < 0 ? location : location.substring(0,query)));
            } else file = Path.of(location);
        } catch (IllegalArgumentException error) { throw new IllegalArgumentException(tr("La ruta del archivo SQLite no es válida.")); }
        file = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw new IllegalArgumentException(tr("No se encontró un archivo SQLite legible en la ruta indicada.") + "\n" + file);
        if (Files.size(file) > 0) try (InputStream input = Files.newInputStream(file)) {
            if (!Arrays.equals(input.readNBytes(16), "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException(tr("El archivo no tiene formato SQLite o está cifrado. Selecciona el archivo principal, no un archivo WAL o SHM."));
        }
        String canonical = "jdbc:sqlite:" + file.toUri().toASCIIString() + (profile.readOnly() ? "?mode=ro" : "?mode=rw");
        return new ConnectionProfile(profile.id(), profile.name(), DatabaseType.SQLITE, "", 0, file.toString(), "", canonical,
                profile.driverClass(), profile.driverJar(), AuthenticationMode.DATABASE, profile.readOnly());
    }
}
