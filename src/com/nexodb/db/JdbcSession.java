package com.nexodb.db;

import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import com.nexodb.model.AuthenticationMode;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Comparator;
import java.util.Properties;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

public final class JdbcSession implements AutoCloseable {
    public record DriverCheck(boolean available, File file, String detail) {}

    private final ConnectionProfile profile;
    private final Connection connection;
    private final URLClassLoader driverLoader;
    private volatile Statement activeStatement;

    private JdbcSession(ConnectionProfile profile, Connection connection, URLClassLoader driverLoader) {
        this.profile = profile;
        this.connection = connection;
        this.driverLoader = driverLoader;
    }

    public static JdbcSession open(ConnectionProfile profile, char[] password) throws Exception {
        return open(profile, password, null);
    }

    public static JdbcSession open(ConnectionProfile profile, char[] password, IntConsumer driverProgress) throws Exception {
        if (profile.authenticationMode() == AuthenticationMode.WINDOWS && !com.nexodb.util.SecretStore.isSupported()) {
            throw new IllegalArgumentException("La autenticación integrada de Windows requiere Windows. Edita el perfil y elige usuario y contraseña.");
        }
        profile = PostgresSupport.initialProfile(profile);
        ensureKnownDriver(profile, driverProgress);
        try {
            return openDirect(profile, password);
        } catch (Exception firstError) {
            if (PostgresSupport.needsDatabaseSelection(profile) && firstError instanceof SQLException sql
                    && ("3D000".equals(sql.getSQLState()) || "42501".equals(sql.getSQLState()))) {
                try {
                    return openDirect(PostgresSupport.alternateInitialProfile(profile), password);
                } catch (SQLException fallbackError) {
                    if (!"3D000".equals(fallbackError.getSQLState()) && !"42501".equals(fallbackError.getSQLState())) {
                        fallbackError.addSuppressed(firstError);
                        throw fallbackError;
                    }
                    SQLException friendly = new SQLException("No se pudo consultar la lista de bases: el usuario no puede "
                            + "conectarse a postgres ni a template1. Indica una base existente en Base / servicio / archivo.",
                            fallbackError.getSQLState());
                    friendly.addSuppressed(firstError);
                    friendly.addSuppressed(fallbackError);
                    throw friendly;
                }
            }
            if (profile.type() == DatabaseType.MARIADB && isAuthenticationError(firstError)) {
                IllegalStateException friendly = manualAuthenticationError(profile, password);
                friendly.addSuppressed(firstError);
                throw friendly;
            }
            if (profile.type() != DatabaseType.MYSQL || !requiresMariaDbDriver(firstError)) throw firstError;
            ConnectionProfile mariaDbProfile = new ConnectionProfile(
                    profile.id(), profile.name(), DatabaseType.MARIADB, profile.host(), profile.port(), profile.database(),
                    profile.username(), mariaDbFallbackUrl(profile),
                    DatabaseType.MARIADB.driverClass(), "", profile.authenticationMode(), profile.readOnly());
            try {
                return openDirect(mariaDbProfile, password);
            } catch (Exception fallbackError) {
                if (isAuthenticationError(fallbackError)) {
                    IllegalStateException friendly = manualAuthenticationError(profile, password);
                    friendly.addSuppressed(firstError);
                    friendly.addSuppressed(fallbackError);
                    throw friendly;
                }
                fallbackError.addSuppressed(firstError);
                throw fallbackError;
            }
        }
    }

    private static void ensureKnownDriver(ConnectionProfile profile, IntConsumer progress) throws Exception {
        if (!profile.driverJar().isBlank() || resolveDriver(profile) != null) return;
        if (DriverDownloadService.supports(profile.type())) {
            DriverDownloadService.ensureDownloaded(profile.type(), progress);
        }
    }

    private static IllegalStateException manualAuthenticationError(ConnectionProfile profile, char[] password) {
        if (profile.authenticationMode() == AuthenticationMode.WINDOWS) {
            return new IllegalStateException(
                    "El servidor no aceptó la sesión actual de Windows para el usuario MariaDB “"
                            + profile.username() + "”. Verifica que esa cuenta esté configurada con GSSAPI y vinculada "
                            + "al usuario o grupo de Windows correspondiente.");
        }
        String shownUser = profile.username().isEmpty() ? "(vacío)" : "“" + profile.username() + "”";
        return new IllegalStateException(
                "El servidor rechazó la conexión para el usuario " + shownUser + ". "
                        + "NexoDB envió el usuario indicado y la contraseña exactamente como aparecen "
                        + "en el formulario; no utilizó la cuenta de Windows. Verifica que el servidor permita esa cuenta.");
    }

    private static JdbcSession openDirect(ConnectionProfile profile, char[] password) throws Exception {
        Properties props = connectionProperties(profile, password);

        Connection connection = null;
        URLClassLoader loader = null;
        Driver loadedDriver = null;
        try {
            File resolvedDriver = resolveDriver(profile);
            if (resolvedDriver != null) {
                File jar = resolvedDriver;
                if (!jar.isFile()) throw new IllegalArgumentException("No existe el controlador: " + jar);
                if (profile.driverClass().isBlank()) throw new IllegalArgumentException("Indica la clase del controlador JDBC.");
                loader = new DriverClassLoader(new URL[]{jar.toURI().toURL()}, JdbcSession.class.getClassLoader(), profile.driverClass());
                Class<?> driverType;
                try {
                    driverType = Class.forName(profile.driverClass(), true, loader);
                } catch (ClassNotFoundException error) {
                    throw new IllegalArgumentException("El archivo seleccionado no contiene el controlador " + profile.driverClass() + ".", error);
                }
                Driver driver = (Driver) driverType.getDeclaredConstructor().newInstance();
                loadedDriver = driver;
                connection = driver.connect(profile.jdbcUrl(), props);
                if (connection == null) throw new SQLException("El controlador no reconoce la URL JDBC indicada.");
            } else {
                if (!profile.driverClass().isBlank()) {
                    try {
                        Class.forName(profile.driverClass());
                    } catch (ClassNotFoundException error) {
                        throw new IllegalArgumentException("Falta el controlador JDBC para " + profile.type().displayName()
                                + ". Selecciona su archivo .jar en “Archivo del controlador”.", error);
                    }
                }
                connection = DriverManager.getConnection(profile.jdbcUrl(), props);
            }

            connection.setReadOnly(profile.readOnly());
            connection.setAutoCommit(true);
            char[] retained = password == null ? new char[0] : password.clone();
            Driver reconnectDriver = loadedDriver;
            try {
                Connection managed = ManagedConnection.wrap(connection, () -> {
                    Properties options = connectionProperties(profile, retained);
                    Connection reopened = reconnectDriver == null ? DriverManager.getConnection(profile.jdbcUrl(), options) : reconnectDriver.connect(profile.jdbcUrl(), options);
                    if (reopened == null) throw new SQLException("El controlador no reconoce la URL JDBC.");
                    return reopened;
                }, () -> java.util.Arrays.fill(retained, '\0'));
                return new JdbcSession(profile, managed, loader);
            } catch (Exception error) { java.util.Arrays.fill(retained, '\0'); throw error; }
        } catch (Exception error) {
            if (connection != null) try { connection.close(); } catch (Exception ignored) {}
            if (loader != null) try { loader.close(); } catch (Exception ignored) {}
            throw error;
        }
    }

    static Properties connectionProperties(ConnectionProfile profile, char[] password) {
        Properties props = new Properties();
        props.setProperty("user", profile.username());
        if (profile.type() == DatabaseType.MYSQL || profile.type() == DatabaseType.MARIADB || profile.type() == DatabaseType.POSTGRESQL) props.setProperty("tcpKeepAlive", "true");
        if (profile.type() == DatabaseType.POSTGRESQL) props.setProperty("connectTimeout", "15");
        if (profile.type() == DatabaseType.MYSQL || profile.type() == DatabaseType.MARIADB) props.setProperty("connectTimeout", "15000");
        if (profile.type() == DatabaseType.MYSQL) props.setProperty("useInformationSchema", "true");
        if (profile.authenticationMode() == AuthenticationMode.DATABASE) {
            props.setProperty("password", password == null ? "" : new String(password));
        }
        if (profile.type() == DatabaseType.MARIADB
                && profile.authenticationMode() == AuthenticationMode.DATABASE) {
            props.setProperty("restrictedAuth",
                    "mysql_native_password,caching_sha2_password,sha256_password,client_ed25519,parsec");
        }
        if (profile.type() == DatabaseType.MONGODB && !profile.database().isBlank()) {
            props.setProperty("database", profile.database());
        }
        return props;
    }

    private static File resolveDriver(ConnectionProfile profile) {
        if (!profile.driverJar().isBlank()) return new File(profile.driverJar());
        String prefix = switch (profile.type()) {
            case MYSQL -> "mysql-connector-j-";
            case MARIADB -> profile.authenticationMode() == AuthenticationMode.WINDOWS
                    ? "mariadb-java-client-3.5.11"
                    : "mariadb-java-client-4.0.0";
            case POSTGRESQL -> "postgresql-";
            case SQLSERVER -> "mssql-jdbc-";
            case ORACLE -> "ojdbc";
            case SQLITE -> "sqlite-jdbc-";
            case H2 -> "h2-";
            case MONGODB -> "mongodb-jdbc-";
            default -> null;
        };
        if (prefix == null) return null;

        File downloaded = DriverDownloadService.findDownloadedDriver(profile.type());
        if (downloaded != null) return downloaded;

        try {
            Path codeLocation = Paths.get(JdbcSession.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path appDirectory = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            if (appDirectory == null) return null;
            Path drivers = appDirectory.resolve("drivers");
            if (!Files.isDirectory(drivers) && appDirectory.getParent() != null) {
                drivers = appDirectory.getParent().resolve("drivers");
            }
            if (!Files.isDirectory(drivers)) return null;
            try (Stream<Path> files = Files.list(drivers)) {
                return files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith(prefix))
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Checks the selected/bundled/downloaded driver without opening a database connection. */
    public static DriverCheck checkDriver(ConnectionProfile profile) throws Exception {
        if (profile.driverClass().isBlank()) {
            return new DriverCheck(false, null, "No se indicó la clase del controlador JDBC.");
        }
        File jar = resolveDriver(profile);
        if (jar != null) {
            if (!jar.isFile()) return new DriverCheck(false, jar, "No existe el archivo " + jar.getAbsolutePath() + ".");
            try (URLClassLoader loader = new DriverClassLoader(
                    new URL[]{jar.toURI().toURL()}, JdbcSession.class.getClassLoader(), profile.driverClass())) {
                Class<?> type = Class.forName(profile.driverClass(), true, loader);
                if (!Driver.class.isAssignableFrom(type)) {
                    return new DriverCheck(false, jar, "La clase indicada no implementa java.sql.Driver.");
                }
                return new DriverCheck(true, jar, "Controlador disponible y cargado correctamente.");
            } catch (ClassNotFoundException error) {
                return new DriverCheck(false, jar, "El archivo no contiene la clase " + profile.driverClass() + ".");
            }
        }
        try {
            Class<?> type = Class.forName(profile.driverClass());
            return new DriverCheck(Driver.class.isAssignableFrom(type), null,
                    Driver.class.isAssignableFrom(type) ? "Controlador incluido en la aplicación." : "La clase no implementa java.sql.Driver.");
        } catch (ClassNotFoundException error) {
            return new DriverCheck(false, null, "El controlador todavía no está instalado.");
        }
    }

    private static boolean requiresMariaDbDriver(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("auth_gssapi_client")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static boolean isAuthenticationError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlError) {
                String state = sqlError.getSQLState();
                if (state != null && state.startsWith("28")) return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("no hay credenciales disponibles")
                        || normalized.contains("no credentials available")
                        || normalized.contains("unable to obtain principal")
                        || normalized.contains("access denied")
                        || normalized.contains("authentication")
                        || normalized.contains("auth_gssapi_client")
                        || normalized.contains("restrictedauth")) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String mariaDbFallbackUrl(ConnectionProfile profile) {
        String url = profile.jdbcUrl().replaceFirst("^jdbc:mysql:", "jdbc:mariadb:");
        String host = profile.host().trim().toLowerCase();
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        if (local && !url.toLowerCase().contains("sslmode=")) {
            url += url.contains("?") ? "&sslMode=disable" : "?sslMode=disable";
        }
        return url;
    }

    public ConnectionProfile profile() { return profile; }
    public Connection connection() { return connection; }
    public boolean isOpen() {
        try { return !connection.isClosed(); }
        catch (SQLException e) { return false; }
    }

    public QueryResult execute(String sql, int maxRows) throws SQLException {
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            activeStatement = statement;
            configureRowLimit(statement,maxRows);
            boolean hasResult = statement.execute(sql);
            if (hasResult) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    QueryResult result = QueryResult.from(resultSet, maxRows);
                    var eligibility = QueryEditSupport.describe(connection, profile.type(), sql, resultSet.getMetaData());
                    return result.withEditing(eligibility).withElapsed(elapsedMillis(started));
                }
            }
            return QueryResult.update(statement.getUpdateCount(), elapsedMillis(started));
        } finally {
            activeStatement = null;
        }
    }

    public void cancel() throws SQLException {
        Statement statement = activeStatement;
        if (statement != null) statement.cancel();
    }

    static void configureRowLimit(Statement statement,int maxRows)throws SQLException {
        // JDBC uses zero for unlimited. Integer.MAX_VALUE exceeds limits in MariaDB/MySQL drivers.
        statement.setMaxRows(maxRows==Integer.MAX_VALUE?0:Math.addExact(maxRows,1));
        statement.setFetchSize(Math.min(maxRows,500));
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException { connection.setAutoCommit(autoCommit); }
    public boolean getAutoCommit() throws SQLException { return connection.getAutoCommit(); }
    public void setReadOnly(boolean readOnly) throws SQLException { connection.setReadOnly(readOnly); }
    public boolean getReadOnly() throws SQLException { return connection.isReadOnly(); }
    public void commit() throws SQLException { connection.commit(); }
    public void rollback() throws SQLException { connection.rollback(); }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static final class DriverClassLoader extends URLClassLoader {
        private final String childFirstPackage;

        DriverClassLoader(URL[] urls, ClassLoader parent, String driverClass) {
            super(urls, parent);
            int separator = driverClass.lastIndexOf('.');
            this.childFirstPackage = separator < 0 ? driverClass : driverClass.substring(0, separator + 1);
        }

        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(childFirstPackage)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try { loaded = findClass(name); }
                    catch (ClassNotFoundException ignored) { }
                }
                if (loaded != null) {
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            if (!connection.isClosed() && !connection.getAutoCommit()) connection.rollback();
        } finally {
            try { connection.close(); } finally { if (driverLoader != null) driverLoader.close(); }
        }
    }
}

