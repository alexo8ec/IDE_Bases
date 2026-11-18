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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

public final class JdbcSession implements AutoCloseable {
    public record DriverCheck(boolean available, File file, String detail) {}

    private final ConnectionProfile profile;
    private final Connection connection;
    private final URLClassLoader driverLoader;
    private final SshTunnel sshTunnel;
    private final QueryEditSupport.MetadataCache editMetadata = new QueryEditSupport.MetadataCache();
    private volatile Statement activeStatement;

    private JdbcSession(ConnectionProfile profile, Connection connection, URLClassLoader driverLoader) {
        this(profile,connection,driverLoader,null);
    }
    private JdbcSession(ConnectionProfile profile, Connection connection, URLClassLoader driverLoader,SshTunnel sshTunnel) {
        this.profile = profile;
        this.connection = connection;
        this.driverLoader = driverLoader;
        this.sshTunnel=sshTunnel;
    }

    public static JdbcSession open(ConnectionProfile profile, char[] password) throws Exception {
        return open(profile, password, (IntConsumer)null);
    }

    public static JdbcSession open(ConnectionProfile profile, char[] password, IntConsumer driverProgress) throws Exception {
        char[] sshSecret=loadStoredSshSecret(profile);
        try{return open(profile,password,sshSecret,driverProgress);}
        finally{Arrays.fill(sshSecret,'\0');}
    }

    public static JdbcSession open(ConnectionProfile profile,char[] password,char[] sshSecret,IntConsumer driverProgress)throws Exception{
        profile = SQLiteSupport.prepare(profile);
        if (profile.authenticationMode() == AuthenticationMode.WINDOWS && !com.nexodb.util.SecretStore.isSupported()) {
            throw new IllegalArgumentException("La autenticación integrada de Windows requiere Windows. Edita el perfil y elige usuario y contraseña.");
        }
        profile = PostgresSupport.initialProfile(profile);
        ensureKnownDriver(profile, driverProgress);
        SshTunnel tunnel=null;
        try{
            if(profile.ssh().enabled()){
                tunnel=SshTunnel.open(profile,sshSecret);
                profile=tunnel.forward(profile);
            }
            JdbcSession opened=openDatabase(profile,password);
            return tunnel==null?opened:new JdbcSession(opened.profile,opened.connection,opened.driverLoader,tunnel);
        }catch(Exception error){if(tunnel!=null)tunnel.close();throw error;}
    }

    private static JdbcSession openDatabase(ConnectionProfile profile,char[] password)throws Exception{
        try {
            return openResilient(profile, password);
        } catch (Exception firstError) {
            if (PostgresSupport.needsDatabaseSelection(profile) && firstError instanceof SQLException sql
                    && ("3D000".equals(sql.getSQLState()) || "42501".equals(sql.getSQLState()))) {
                try {
                    return openResilient(PostgresSupport.alternateInitialProfile(profile), password);
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
                    DatabaseType.MARIADB.driverClass(), "", profile.authenticationMode(), profile.ssh(), profile.readOnly());
            try {
                return openResilient(mariaDbProfile, password);
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

    private static char[] loadStoredSshSecret(ConnectionProfile profile)throws Exception{
        if(!profile.ssh().enabled()||profile.id().isBlank())return new char[0];
        com.nexodb.util.SecretStore store=new com.nexodb.util.SecretStore();String key=com.nexodb.model.SshConfig.secretId(profile.id());
        char[] value=store.has(key)?store.load(key):null;return value==null?new char[0]:value;
    }

    /** One quick retry handles short VPN/Wi-Fi route changes without exceeding the old total wait. */
    private static JdbcSession openResilient(ConnectionProfile profile,char[] password)throws Exception{
        try{return openDirect(profile,password);}
        catch(Exception first){
            if(!isConnectTimeout(first)||profile.host().isBlank())throw first;
            try{Thread.sleep(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw first;}
            try{return openDirect(profile,password);}
            catch(Exception second){
                if(!isConnectTimeout(second))throw second;
                String endpoint=profile.host()+(profile.port()>0?":"+profile.port():"");
                SQLTransientConnectionException friendly=new SQLTransientConnectionException(
                        "No se pudo contactar " + endpoint + " después de dos intentos. No se llegó a validar el usuario ni la contraseña.\n\n"
                                + "Comprueba que el servidor esté encendido, que el puerto sea correcto y esté abierto, y que la VPN o Tailscale esté conectada si esa dirección la requiere.","08001");
                friendly.addSuppressed(first);friendly.addSuppressed(second);throw friendly;
            }
        }
    }

    static boolean isConnectTimeout(Throwable error){
        for(Throwable current=error;current!=null;current=current.getCause()){
            if(current instanceof java.net.SocketTimeoutException)return true;
            String message=current.getMessage();if(message!=null){String value=message.toLowerCase(java.util.Locale.ROOT);if(value.contains("connect timed out")||value.contains("connection timed out")||value.contains("connect timeout"))return true;}
        }
        return false;
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
                        + "AdisDB envió el usuario indicado y la contraseña exactamente como aparecen "
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

            if (profile.type() == DatabaseType.SQLITE) connection = SQLiteSupport.wrap(connection, profile.readOnly());
            connection.setReadOnly(profile.readOnly());
            connection.setAutoCommit(true);
            char[] retained = password == null ? new char[0] : password.clone();
            Driver reconnectDriver = loadedDriver;
            try {
                Connection managed = ManagedConnection.wrap(connection, () -> {
                    Properties options = connectionProperties(profile, retained);
                    Connection reopened = reconnectDriver == null ? DriverManager.getConnection(profile.jdbcUrl(), options) : reconnectDriver.connect(profile.jdbcUrl(), options);
                    if (reopened == null) throw new SQLException("El controlador no reconoce la URL JDBC.");
                    if (profile.type() == DatabaseType.SQLITE) reopened = SQLiteSupport.wrap(reopened, profile.readOnly());
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
        if (profile.type() == DatabaseType.SQLITE) {
            props.setProperty("open_mode", profile.readOnly() ? "1" : "2");
            props.setProperty("busy_timeout", "5000");
            return props;
        }
        props.setProperty("user", profile.username());
        if (profile.type() == DatabaseType.MYSQL || profile.type() == DatabaseType.MARIADB || profile.type() == DatabaseType.POSTGRESQL) props.setProperty("tcpKeepAlive", "true");
        if (profile.type() == DatabaseType.POSTGRESQL) props.setProperty("connectTimeout", "7");
        if (profile.type() == DatabaseType.MYSQL || profile.type() == DatabaseType.MARIADB) {
            props.setProperty("connectTimeout", "7000");
            props.setProperty("defaultFetchSize", "500");
        }
        if (profile.type() == DatabaseType.MYSQL) {
            props.setProperty("useInformationSchema", "true");
            props.setProperty("useCursorFetch", "true");
            props.setProperty("cacheResultSetMetadata", "true");
            props.setProperty("cachePrepStmts", "true");
            props.setProperty("prepStmtCacheSize", "250");
            props.setProperty("prepStmtCacheSqlLimit", "2048");
            props.setProperty("useLocalSessionState", "true");
            props.setProperty("elideSetAutoCommits", "true");
            props.setProperty("maintainTimeStats", "false");
        }
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
        return executeResults(sql, maxRows, false,false).get(0);
    }

    /** User-facing SELECT execution may append hidden primary-key columns for safe editing. */
    public QueryResult executeEditable(String sql,int maxRows)throws SQLException{return executeResults(sql,maxRows,false,true).get(0);}

    /** All result sets and update counts produced by a statement, including CALL/EXEC. */
    public List<QueryResult> executeAll(String sql, int maxRows) throws SQLException {
        return executeResults(sql, maxRows, true,false);
    }
    public List<QueryResult> executeAllEditable(String sql,int maxRows)throws SQLException{return executeResults(sql,maxRows,true,true);}

    /** Forward-only JDBC fallback keeps SQL dialect, explicit LIMIT/TOP and scope intact. */
    public QueryResult executePage(String sql,int pageSize,long offset)throws SQLException {
      synchronized(connection) {
        QueryPaging.PageSql page=QueryPaging.pageSql(sql,profile.type(),pageSize,offset);long started=System.nanoTime();
        try(Statement statement=connection.createStatement()) {
            activeStatement=statement;long needed=page.nativeOffset()?pageSize+1L:offset+pageSize+1L;statement.setMaxRows(needed>0&&needed<=5_000_000?(int)needed:0);statement.setFetchSize(Math.min(pageSize,500));
            long serverStarted=System.nanoTime();boolean returned=statement.execute(page.sql());long serverMillis=elapsedMillis(serverStarted);
            if(!returned)throw new SQLException("La consulta no devolvió una tabla paginable.");
            QueryEditSupport.Enrichment enrichment;
            try(ResultSet rows=statement.getResultSet()) {
                long metadataStarted=System.nanoTime();
                enrichment=QueryEditSupport.enrichMissingPrimaryKeys(connection,profile.type(),page.sql(),rows.getMetaData(),editMetadata);
                long metadataMillis=elapsedMillis(metadataStarted);
                if(enrichment==null){
                    long fetchStarted=System.nanoTime();
                    long skipped=0;while(!page.nativeOffset()&&skipped<offset&&rows.next()){skipped++;if(Thread.currentThread().isInterrupted())throw new SQLException("Paginación cancelada.");}
                    QueryResult result=QueryResult.from(rows,pageSize);
                    long fetchMillis=elapsedMillis(fetchStarted);metadataStarted=System.nanoTime();
                    result=result.withEditing(QueryEditSupport.describe(connection,profile.type(),page.sql(),rows.getMetaData(),editMetadata));
                    metadataMillis+=elapsedMillis(metadataStarted);
                    return result.withTiming(timing(started,serverMillis,fetchMillis,metadataMillis));
                }
                return executeEnrichedPage(statement,enrichment,pageSize,page.nativeOffset()?0:offset,started,serverMillis,metadataMillis);
            }
        }finally{activeStatement=null;}
      }
    }

    private QueryResult executeEnrichedPage(Statement owner,QueryEditSupport.Enrichment enrichment,int pageSize,long offset,long started,long baseServerMillis,long baseMetadataMillis)throws SQLException{
        try(Statement statement=connection.createStatement()){
            activeStatement=statement;long needed=offset+pageSize+1L;statement.setMaxRows(needed>0&&needed<=5_000_000?(int)needed:0);statement.setFetchSize(Math.min(pageSize,500));
            long serverStarted=System.nanoTime();boolean returned=statement.execute(enrichment.sql());long serverMillis=baseServerMillis+elapsedMillis(serverStarted);
            if(!returned)throw new SQLException("La consulta no devolvió una tabla paginable.");
            try(ResultSet rows=statement.getResultSet()){
                long fetchStarted=System.nanoTime();
                long skipped=0;while(skipped<offset&&rows.next()){skipped++;if(Thread.currentThread().isInterrupted())throw new SQLException("Paginación cancelada.");}
                QueryResult result=QueryResult.from(rows,pageSize).withHiddenColumns(enrichment.hiddenColumns());
                long fetchMillis=elapsedMillis(fetchStarted);long metadataStarted=System.nanoTime();
                result=result.withEditing(QueryEditSupport.describe(connection,profile.type(),enrichment.sql(),rows.getMetaData(),editMetadata));
                return result.withTiming(timing(started,serverMillis,fetchMillis,baseMetadataMillis+elapsedMillis(metadataStarted)));
            }
        }finally{activeStatement=owner;}
    }

    public static final class ExecutionException extends SQLException {
        private final List<QueryResult> results;
        ExecutionException(SQLException cause, List<QueryResult> results) {
            super(cause.getMessage(), cause.getSQLState(), cause.getErrorCode(), cause);
            this.results = List.copyOf(results);
        }
        public List<QueryResult> results() { return results; }
    }

    private List<QueryResult> executeResults(String sql, int maxRows, boolean all,boolean enrichKeys) throws SQLException {
      synchronized(connection) {
        long started = System.nanoTime();
        List<QueryResult> results = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            activeStatement = statement;
            configureRowLimit(statement,maxRows);
            if(invalidatesMetadata(sql))editMetadata.clear();
            long serverStarted=System.nanoTime();boolean hasResult = statement.execute(sql);long serverMillis=elapsedMillis(serverStarted);
            do {
                if (hasResult) {
                    QueryEditSupport.Enrichment enrichment;
                    QueryResult result;QueryEditSupport.Eligibility eligibility;
                    try (ResultSet resultSet = statement.getResultSet()) {
                        long fetchStarted=System.nanoTime();
                        result = QueryResult.from(resultSet, maxRows);
                        long fetchMillis=elapsedMillis(fetchStarted);long metadataStarted=System.nanoTime();
                        eligibility = QueryEditSupport.describe(connection, profile.type(), sql, resultSet.getMetaData(),editMetadata);
                        enrichment=enrichKeys&&eligibility.target()==null?QueryEditSupport.enrichMissingPrimaryKeys(connection,profile.type(),sql,resultSet.getMetaData(),editMetadata):null;
                        long metadataMillis=elapsedMillis(metadataStarted);
                        if(enrichment!=null)results.add(executeEnriched(statement,enrichment,maxRows,started,serverMillis,fetchMillis,metadataMillis));
                        else results.add(result.withEditing(eligibility).withTiming(timing(started,serverMillis,fetchMillis,metadataMillis)));
                    }
                } else {
                    int count = statement.getUpdateCount();
                    if (count == -1) break;
                    long total=elapsedMillis(started);results.add(QueryResult.update(count,total).withTiming(new QueryResult.Timing(total,serverMillis,0,0)));
                }
                if (!all) break;
                serverStarted=System.nanoTime();hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);serverMillis=elapsedMillis(serverStarted);
            } while (true);
            if (results.isEmpty()) results.add(QueryResult.update(-1, elapsedMillis(started)));
            return List.copyOf(results);
        } catch (SQLException error) {
            if (!results.isEmpty() && all) throw new ExecutionException(error, results);
            throw error;
        } finally {
            activeStatement = null;
        }
      }
    }

    private QueryResult executeEnriched(Statement owner,QueryEditSupport.Enrichment enrichment,int maxRows,long started,long baseServerMillis,long baseFetchMillis,long baseMetadataMillis)throws SQLException{
        try(Statement statement=connection.createStatement()){
            activeStatement=statement;configureRowLimit(statement,maxRows);
            long serverStarted=System.nanoTime();boolean returned=statement.execute(enrichment.sql());long serverMillis=baseServerMillis+elapsedMillis(serverStarted);
            if(!returned)throw new SQLException("La consulta no devolvió un resultado editable.");
            try(ResultSet rows=statement.getResultSet()){
                long fetchStarted=System.nanoTime();
                QueryResult result=QueryResult.from(rows,maxRows).withHiddenColumns(enrichment.hiddenColumns());
                long fetchMillis=baseFetchMillis+elapsedMillis(fetchStarted);long metadataStarted=System.nanoTime();
                result=result.withEditing(QueryEditSupport.describe(connection,profile.type(),enrichment.sql(),rows.getMetaData(),editMetadata));
                return result.withTiming(timing(started,serverMillis,fetchMillis,baseMetadataMillis+elapsedMillis(metadataStarted)));
            }
        }finally{activeStatement=owner;}
    }

    public void cancel() throws SQLException {
        Statement statement = activeStatement;
        if (statement != null) statement.cancel();
    }

    static void configureRowLimit(Statement statement,int maxRows)throws SQLException {
        // JDBC uses zero for unlimited. Integer.MAX_VALUE exceeds limits in MariaDB/MySQL drivers.
        statement.setMaxRows(maxRows==Integer.MAX_VALUE?0:Math.addExact(maxRows,1));
        try{statement.setFetchDirection(ResultSet.FETCH_FORWARD);}catch(SQLFeatureNotSupportedException|AbstractMethodError ignored){}
        statement.setFetchSize(Math.min(maxRows,500));
    }

    private static boolean invalidatesMetadata(String sql){return sql!=null&&sql.stripLeading().matches("(?is)^(?:CREATE|ALTER|DROP|RENAME|TRUNCATE)\\b.*");}

    public void setAutoCommit(boolean autoCommit) throws SQLException { connection.setAutoCommit(autoCommit); }
    public boolean getAutoCommit() throws SQLException { return connection.getAutoCommit(); }
    public void setReadOnly(boolean readOnly) throws SQLException { connection.setReadOnly(readOnly); }
    public boolean getReadOnly() throws SQLException { return connection.isReadOnly(); }
    public void commit() throws SQLException { connection.commit(); }
    public void rollback() throws SQLException { connection.rollback(); }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static QueryResult.Timing timing(long started,long serverMillis,long fetchMillis,long metadataMillis){
        return new QueryResult.Timing(elapsedMillis(started),serverMillis,fetchMillis,metadataMillis);
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
            try { connection.close(); } finally {
                try { if (driverLoader != null) driverLoader.close(); }
                finally { if(sshTunnel!=null)sshTunnel.close(); }
            }
        }
    }
}

