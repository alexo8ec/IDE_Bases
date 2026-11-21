package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Versioned, checksum-protected SQL migrations for relational engines. */
public final class MigrationService {
    public static final String HISTORY_TABLE = "adisdb_schema_history";
    private static final Pattern FILE = Pattern.compile("^V([0-9][0-9A-Za-z._-]*)__([A-Za-z0-9][A-Za-z0-9._-]*)\\.sql$", Pattern.CASE_INSENSITIVE);

    public enum Status { PENDING, APPLIED, CHANGED, FAILED }
    public record Migration(String version,String description,Path file,String script,String checksum) {}
    public record History(int rank,String version,String description,String script,String checksum,String installedBy,Instant installedOn,long executionMs,boolean success) {}
    public record Plan(Migration migration,Status status,History history,String detail) {}
    public record Result(int applied,long executionMs,List<String> versions) {}

    private MigrationService() {}

    public static boolean supported(DatabaseType type) {
        return type != null && type != DatabaseType.MONGODB && type != DatabaseType.GENERIC;
    }

    public static List<Migration> discover(Path folder) throws Exception {
        Path root = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("La carpeta de migraciones no existe: " + root);
        List<Migration> result = new ArrayList<>();
        Set<String> versions = new HashSet<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                Matcher match = FILE.matcher(file.getFileName().toString());
                if (!match.matches()) continue;
                String version = match.group(1), description = match.group(2).replace('_',' ').replace('-',' ').trim();
                if (!versions.add(version.toLowerCase(Locale.ROOT))) throw new IOException("Versión de migración duplicada: " + version);
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > 32 * 1024 * 1024) throw new IOException("La migración supera 32 MB: " + file.getFileName());
                String script = new String(bytes, StandardCharsets.UTF_8);
                result.add(new Migration(version,description,file,file.getFileName().toString(),hex(bytes)));
                Arrays.fill(bytes,(byte)0);
            }
        }
        result.sort(Comparator.comparing(Migration::version,MigrationService::compareVersions));
        return List.copyOf(result);
    }

    public static List<History> history(Connection connection,DatabaseType type) throws SQLException {
        ensureHistory(connection,type);
        List<History> result = new ArrayList<>();
        try (Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery(
                "SELECT installed_rank,version,description,script,checksum,installed_by,installed_on,execution_ms,success FROM "+quote(type,HISTORY_TABLE)+" ORDER BY installed_rank")) {
            while(rows.next()) {
                Timestamp installed=rows.getTimestamp(7);
                result.add(new History(rows.getInt(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),installed==null?null:installed.toInstant(),rows.getLong(8),rows.getBoolean(9)));
            }
        }
        return List.copyOf(result);
    }

    public static List<Plan> plan(Connection connection,DatabaseType type,Path folder) throws Exception {
        List<Migration> migrations=discover(folder);Map<String,History> applied=new LinkedHashMap<>();
        for(History row:history(connection,type))applied.put(row.version().toLowerCase(Locale.ROOT),row);
        List<Plan> result=new ArrayList<>();
        for(Migration migration:migrations){History row=applied.remove(migration.version().toLowerCase(Locale.ROOT));Status status;String detail;
            if(row==null){status=Status.PENDING;detail="Pendiente";}
            else if(!row.success()){status=Status.FAILED;detail="La ejecución anterior falló";}
            else if(!row.checksum().equalsIgnoreCase(migration.checksum())){status=Status.CHANGED;detail="El archivo cambió después de aplicarse";}
            else{status=Status.APPLIED;detail="Aplicada";}
            result.add(new Plan(migration,status,row,detail));
        }
        for(History row:applied.values())result.add(new Plan(null,row.success()?Status.APPLIED:Status.FAILED,row,"La migración aplicada ya no existe en la carpeta"));
        return List.copyOf(result);
    }

    public static Result migrate(Connection connection,DatabaseType type,Path folder,BooleanSupplier cancelled) throws Exception {
        if(!supported(type))throw new IllegalArgumentException("Las migraciones versionadas requieren un motor SQL relacional reconocido.");
        if(!connection.getAutoCommit())throw new SQLException("Finaliza la transacción actual antes de ejecutar migraciones.");
        List<Plan> plan=plan(connection,type,folder);
        for(Plan item:plan)if(item.status()==Status.CHANGED||item.status()==Status.FAILED)throw new SQLException(item.detail()+": "+(item.migration()==null?item.history().script():item.migration().script()));
        List<Migration> pending=plan.stream().filter(item->item.status()==Status.PENDING).map(Plan::migration).toList();
        int rank=history(connection,type).stream().mapToInt(History::rank).max().orElse(0);List<String> applied=new ArrayList<>();long totalStart=System.nanoTime();
        for(Migration migration:pending){if(cancelled.getAsBoolean())throw new CancellationException("Migración cancelada antes de "+migration.script());long start=System.nanoTime();boolean transactional=transactionalDdl(type);boolean original=connection.getAutoCommit();
            if(transactional)connection.setAutoCommit(false);
            try(Reader input=Files.newBufferedReader(migration.file(),StandardCharsets.UTF_8);SqlScriptReader reader=new SqlScriptReader(input)){
                SqlScriptReader.Statement item;while((item=reader.next())!=null){if(cancelled.getAsBoolean())throw new CancellationException("Migración cancelada en la línea "+item.line());try(Statement statement=connection.createStatement()){statement.execute(item.sql());consume(statement,cancelled);}}
                long elapsed=millis(start);insertHistory(connection,type,++rank,migration,elapsed,true);if(transactional)connection.commit();applied.add(migration.version());
            }catch(Exception error){if(transactional)try{connection.rollback();}catch(SQLException rollback){error.addSuppressed(rollback);}try{insertHistory(connection,type,++rank,migration,millis(start),false);}catch(SQLException record){error.addSuppressed(record);}throw error;
            }finally{if(transactional)connection.setAutoCommit(original);}
        }
        return new Result(applied.size(),millis(totalStart),List.copyOf(applied));
    }

    private static void ensureHistory(Connection connection,DatabaseType type)throws SQLException{
        if(tableExists(connection))return;
        String booleanType=type==DatabaseType.ORACLE?"NUMBER(1)":type==DatabaseType.SQLSERVER?"BIT":"BOOLEAN";
        String timestampDefault=type==DatabaseType.ORACLE?"DEFAULT CURRENT_TIMESTAMP":"DEFAULT CURRENT_TIMESTAMP";
        String sql="CREATE TABLE "+quote(type,HISTORY_TABLE)+" (installed_rank INTEGER NOT NULL PRIMARY KEY, version VARCHAR(100) NOT NULL, description VARCHAR(500) NOT NULL, script VARCHAR(1000) NOT NULL, checksum VARCHAR(64) NOT NULL, installed_by VARCHAR(200), installed_on TIMESTAMP "+timestampDefault+", execution_ms BIGINT NOT NULL, success "+booleanType+" NOT NULL)";
        if(type==DatabaseType.ORACLE)sql=sql.replace("execution_ms BIGINT","execution_ms NUMBER(19)");
        try(Statement statement=connection.createStatement()){statement.execute(sql);}catch(SQLException error){if(!tableExists(connection))throw error;}
    }

    private static boolean tableExists(Connection connection)throws SQLException{
        DatabaseMetaData meta=connection.getMetaData();for(String name:List.of(HISTORY_TABLE,HISTORY_TABLE.toUpperCase(Locale.ROOT))){try(ResultSet tables=meta.getTables(connection.getCatalog(),null,name,new String[]{"TABLE"})){if(tables.next())return true;}}return false;
    }

    private static void insertHistory(Connection connection,DatabaseType type,int rank,Migration migration,long elapsed,boolean success)throws SQLException{
        String sql="INSERT INTO "+quote(type,HISTORY_TABLE)+" (installed_rank,version,description,script,checksum,installed_by,execution_ms,success) VALUES (?,?,?,?,?,?,?,?)";
        try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setInt(1,rank);statement.setString(2,migration.version());statement.setString(3,migration.description());statement.setString(4,migration.script());statement.setString(5,migration.checksum());statement.setString(6,Objects.toString(connection.getMetaData().getUserName(),""));statement.setLong(7,elapsed);statement.setBoolean(8,success);statement.executeUpdate();}
    }

    private static void consume(Statement statement,BooleanSupplier cancelled)throws SQLException{
        boolean rows=statement.getResultSet()!=null;while(true){if(cancelled.getAsBoolean())throw new CancellationException();if(rows)try(ResultSet result=statement.getResultSet()){while(result.next())if(cancelled.getAsBoolean())throw new CancellationException();}else if(statement.getUpdateCount()==-1)break;rows=statement.getMoreResults();}
    }

    private static boolean transactionalDdl(DatabaseType type){return type==DatabaseType.POSTGRESQL||type==DatabaseType.SQLSERVER||type==DatabaseType.H2||type==DatabaseType.SQLITE;}
    private static String quote(DatabaseType type,String name){return switch(type){case MYSQL,MARIADB->"`"+name.replace("`","``")+"`";case SQLSERVER->"["+name.replace("]","]]")+"]";default->"\""+name.replace("\"","\"\"")+"\"";};}
    private static long millis(long start){return Math.max(0,(System.nanoTime()-start)/1_000_000L);}
    private static String hex(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static int compareVersions(String left,String right){String[] a=left.split("[._-]"),b=right.split("[._-]");for(int i=0;i<Math.max(a.length,b.length);i++){String x=i<a.length?a[i]:"0",y=i<b.length?b[i]:"0";int c;if(x.matches("\\d+")&&y.matches("\\d+"))c=new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));else c=x.compareToIgnoreCase(y);if(c!=0)return c;}return left.compareToIgnoreCase(right);}
}
