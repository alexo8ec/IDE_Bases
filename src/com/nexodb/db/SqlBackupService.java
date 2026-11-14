package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/** MySQL/MariaDB native DDL and streamed, unlimited data. Always use a dedicated connection. */
public final class SqlBackupService {
    public enum Kind { TABLE, VIEW, PROCEDURE, FUNCTION, TRIGGER, EVENT }
    public enum Mode { STRUCTURE_AND_DATA, STRUCTURE, DATA }
    public record ObjectInfo(Kind kind,String name,String parent,String engine) {}
    public record Options(Mode mode,boolean createDatabase,boolean drop,boolean disableForeignKeys,boolean consistent,boolean omitDefiner) {}
    public record Progress(String object,long rows,int completed,int total) {}
    public record Report(int objects,long rows,Path file) {}
    private record Definition(ObjectInfo object,String sql,String mode) {}
    private record Column(String name,boolean geometry) {}
    private SqlBackupService() {}
    public static boolean supported(DatabaseType type){return type==DatabaseType.MYSQL||type==DatabaseType.MARIADB;}
    public static String quote(String name){if(name==null||name.isBlank()||name.indexOf('\0')>=0)throw new IllegalArgumentException("Nombre de base u objeto vacío o no válido.");return "`"+name.replace("`","``")+"`";}
    public static List<ObjectInfo> objects(Connection connection,String database)throws SQLException {
        List<ObjectInfo> result=new ArrayList<>();
        try(PreparedStatement s=connection.prepareStatement("SELECT TABLE_NAME,TABLE_TYPE,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME")){
            s.setString(1,database);try(ResultSet r=s.executeQuery()){while(r.next())result.add(new ObjectInfo("VIEW".equals(r.getString(2))?Kind.VIEW:Kind.TABLE,r.getString(1),"",Objects.toString(r.getString(3),"")));}
        }
        try(PreparedStatement s=connection.prepareStatement("SELECT ROUTINE_NAME,ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=? ORDER BY ROUTINE_NAME")){
            s.setString(1,database);try(ResultSet r=s.executeQuery()){while(r.next())result.add(new ObjectInfo(Kind.valueOf(r.getString(2)),r.getString(1),"",""));}
        }
        try(PreparedStatement s=connection.prepareStatement("SELECT TRIGGER_NAME,EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? ORDER BY TRIGGER_NAME")){
            s.setString(1,database);try(ResultSet r=s.executeQuery()){while(r.next())result.add(new ObjectInfo(Kind.TRIGGER,r.getString(1),r.getString(2),""));}
        }
        try(PreparedStatement s=connection.prepareStatement("SELECT EVENT_NAME FROM information_schema.EVENTS WHERE EVENT_SCHEMA=? ORDER BY EVENT_NAME")){
            s.setString(1,database);try(ResultSet r=s.executeQuery()){while(r.next())result.add(new ObjectInfo(Kind.EVENT,r.getString(1),"",""));}
        }
        return List.copyOf(result);
    }
    public static List<String> nonTransactional(List<ObjectInfo> objects){return objects.stream().filter(o->o.kind()==Kind.TABLE&&!o.engine().equalsIgnoreCase("InnoDB")).map(ObjectInfo::name).toList();}
    private static void execute(Connection connection,String sql,SqlTransferControl control)throws SQLException {
        control.check();try(Statement s=connection.createStatement()){control.active(s);s.execute(sql);}finally{control.active(null);}
    }
    public static Report write(Connection connection,DatabaseType type,String database,List<ObjectInfo> objects,Options options,Path file,SqlTransferControl control,Consumer<Progress> progress)throws Exception {
        if(!supported(type))throw new IllegalArgumentException("El respaldo SQL completo está disponible para MySQL y MariaDB.");
        if(options.mode()==Mode.DATA)objects=objects.stream().filter(o->o.kind()==Kind.TABLE).toList();
        if(objects.isEmpty())throw new IllegalArgumentException("Selecciona al menos un objeto.");
        if(!connection.getAutoCommit())throw new SQLException("El respaldo requiere una conexión independiente sin transacciones pendientes.");
        quote(database);connection.setCatalog(database);
        String sourceMode="";try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT @@SESSION.sql_mode")){if(r.next())sourceMode=r.getString(1);}
        List<Definition> definitions=new ArrayList<>();boolean structure=options.mode()!=Mode.DATA,data=options.mode()!=Mode.STRUCTURE;
        for(ObjectInfo object:objects){control.check();if(structure)definitions.add(definition(connection,database,object,options.omitDefiner(),control));}
        String createDatabase="";
        if(options.createDatabase()&&structure)try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SHOW CREATE DATABASE "+quote(database))){if(r.next())createDatabase=r.getString(2);}
        Path target=file.toAbsolutePath();Files.createDirectories(target.getParent());Path temporary=Files.createTempFile(target.getParent(),"nexodb-backup-",".part");
        boolean snapshot=false;long rows=0;int completed=0;
        try {
            if(options.consistent()&&data){execute(connection,"SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ",control);connection.setAutoCommit(false);snapshot=true;execute(connection,"START TRANSACTION WITH CONSISTENT SNAPSHOT",control);}
            try(Writer out=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){
                out.write("-- AdisDB SQL backup · "+java.time.Instant.now()+"\n-- NEXODB-DATABASE: "+Base64.getEncoder().encodeToString(database.getBytes(StandardCharsets.UTF_8))+"\n");
                out.write("-- Server: "+connection.getMetaData().getDatabaseProductVersion().replace('\n',' ').replace('\r',' ')+"\n");
                out.write("SET @NEXODB_OLD_SQL_MODE=@@SESSION.sql_mode;\nSET @NEXODB_OLD_TIME_ZONE=@@SESSION.time_zone;\nSET time_zone='+00:00';\nSET NAMES utf8mb4;\nSET SQL_MODE="+literal(sourceMode)+";\n");
                // TIMESTAMP text must be read and restored in the same zone; DATETIME stays unchanged.
                execute(connection,"SET time_zone='+00:00'",control);
                if(options.disableForeignKeys())out.write("SET @NEXODB_OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS;\nSET FOREIGN_KEY_CHECKS=0;\n");
                if(options.createDatabase()){
                    if(!createDatabase.isBlank())out.write(createDatabase.replaceFirst("(?i)^CREATE DATABASE ","CREATE DATABASE IF NOT EXISTS ")+";\n");
                    out.write("USE "+quote(database)+";\n");
                }
                if(structure&&options.drop()){
                    List<ObjectInfo> reverse=new ArrayList<>(objects);reverse.sort(Comparator.comparingInt(o->dropOrder(o.kind())));
                    for(ObjectInfo object:reverse)out.write("DROP "+object.kind()+" IF EXISTS "+quote(object.name())+";\n");
                }
                // Functions precede routines and views that can depend on them. Views use temporary
                // table placeholders, as mysqldump does, to handle dependencies between selected views.
                if(structure)for(Definition def:definitions)if(def.object().kind()==Kind.TABLE){writeDefinition(out,def,sourceMode);if(!data)progress.accept(new Progress(def.object().name(),rows,++completed,objects.size()));}
                if(data)out.write("SET SQL_MODE="+literal(dataMode(sourceMode))+";\n");
                if(data)for(ObjectInfo object:objects)if(object.kind()==Kind.TABLE){
                    control.check();rows+=writeData(connection,database,object,out,control,rows,completed,objects.size(),progress);completed++;
                    progress.accept(new Progress(object.name(),rows,completed,objects.size()));
                }
                if(data)out.write("SET SQL_MODE="+literal(sourceMode)+";\n");
                if(structure){
                    for(Definition def:definitions)if(def.object().kind()==Kind.VIEW)writeViewPlaceholder(connection,database,def.object(),out);
                    for(Kind kind:List.of(Kind.FUNCTION,Kind.PROCEDURE,Kind.VIEW,Kind.TRIGGER,Kind.EVENT))for(Definition def:definitions)if(def.object().kind()==kind){
                        control.check();if(kind==Kind.VIEW)out.write("DROP TABLE IF EXISTS "+quote(def.object().name())+";\n");
                        writeDefinition(out,def,sourceMode);progress.accept(new Progress(def.object().name(),rows,++completed,objects.size()));
                    }
                }
                if(options.disableForeignKeys())out.write("SET FOREIGN_KEY_CHECKS=@NEXODB_OLD_FOREIGN_KEY_CHECKS;\n");
                out.write("SET SQL_MODE=@NEXODB_OLD_SQL_MODE;\nSET time_zone=@NEXODB_OLD_TIME_ZONE;\n-- Backup complete\n");
            }
            control.check();if(snapshot){connection.rollback();snapshot=false;}
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            return new Report(objects.size(),rows,target);
        }finally{try{if(snapshot)connection.rollback();}finally{Files.deleteIfExists(temporary);}}
    }
    private static int dropOrder(Kind kind){return switch(kind){case EVENT,TRIGGER->0;case VIEW->1;case PROCEDURE,FUNCTION->2;case TABLE->3;};}
    private static String dataMode(String source){
        Set<String> modes=new LinkedHashSet<>(Arrays.asList(source.split(",")));
        modes.removeIf(m->m.isBlank()||Set.of("STRICT_TRANS_TABLES","STRICT_ALL_TABLES","NO_ZERO_DATE","NO_ZERO_IN_DATE").contains(m));modes.add("NO_AUTO_VALUE_ON_ZERO");return String.join(",",modes);
    }
    private static Definition definition(Connection connection,String database,ObjectInfo object,boolean omitDefiner,SqlTransferControl control)throws SQLException {
        try(Statement s=connection.createStatement()){
            control.active(s);try(ResultSet r=s.executeQuery("SHOW CREATE "+object.kind()+" "+quote(database)+"."+quote(object.name()))){
                if(!r.next())throw new SQLException("No se encontró la definición de "+object.name());
                String ddl=null,mode="";for(int i=1;i<=r.getMetaData().getColumnCount();i++){
                    String label=r.getMetaData().getColumnLabel(i);if(label.toLowerCase(Locale.ROOT).startsWith("create ")||label.equalsIgnoreCase("SQL Original Statement"))ddl=r.getString(i);
                    if(label.equalsIgnoreCase("sql_mode"))mode=Objects.toString(r.getString(i),"");
                }
                if(ddl==null||ddl.isBlank())throw new SQLException("Sin permiso para leer la definición de "+object.name());
                if(omitDefiner)ddl=SqlScriptTools.withoutDefiner(ddl);
                return new Definition(object,ddl,mode);
            }
        }finally{control.active(null);}
    }
    private static void writeDefinition(Writer out,Definition def,String sourceMode)throws IOException {
        out.write("\n-- "+def.object().kind()+" "+def.object().name().replace('\n',' ').replace('\r',' ')+"\n");
        if(!def.mode().isBlank())out.write("SET SQL_MODE="+literal(def.mode())+";\n");
        String delimiter=";;";while(def.sql().contains(delimiter))delimiter+=";";
        out.write("DELIMITER "+delimiter+"\n"+def.sql()+delimiter+"\nDELIMITER ;\n");
        if(!def.mode().isBlank())out.write("SET SQL_MODE="+literal(sourceMode)+";\n");
    }
    private static void writeViewPlaceholder(Connection connection,String database,ObjectInfo object,Writer out)throws SQLException,IOException {
        try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT * FROM "+quote(database)+"."+quote(object.name())+" LIMIT 0")){
            out.write("CREATE TABLE "+quote(object.name())+" (");ResultSetMetaData m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++){if(i>1)out.write(",");out.write(quote(m.getColumnLabel(i))+" INT");}out.write(");\n");
        }
    }
    private static List<Column> columns(Connection connection,String database,String table)throws SQLException {
        List<Column> columns=new ArrayList<>();try(PreparedStatement s=connection.prepareStatement("SELECT COLUMN_NAME,DATA_TYPE,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")){
            s.setString(1,database);s.setString(2,table);try(ResultSet r=s.executeQuery()){while(r.next()){
                String extra=Objects.toString(r.getString(3),"").toUpperCase(Locale.ROOT);if(extra.contains("VIRTUAL GENERATED")||extra.contains("STORED GENERATED")||extra.equals("PERSISTENT"))continue;
                String type=r.getString(2).toUpperCase(Locale.ROOT);columns.add(new Column(r.getString(1),Set.of("GEOMETRY","POINT","LINESTRING","POLYGON","MULTIPOINT","MULTILINESTRING","MULTIPOLYGON","GEOMETRYCOLLECTION").contains(type)));
            }}
        }return columns;
    }
    private static long writeData(Connection connection,String database,ObjectInfo object,Writer writer,SqlTransferControl control,long before,int completed,int total,Consumer<Progress> progress)throws Exception {
        List<Column> columns=columns(connection,database,object.name());String prefix="INSERT INTO "+quote(object.name())+" ("+String.join(",",columns.stream().map(c->quote(c.name())).toList())+") VALUES\n";
        String projection=String.join(",",columns.stream().map(c->c.geometry()?"ST_AsWKB("+quote(c.name())+"),ST_SRID("+quote(c.name())+")":quote(c.name())).toList());
        if(columns.isEmpty())projection="1";
        long rows=0;CountingWriter out=new CountingWriter(writer);long batchStart=0;int batch=0;
        try(Statement s=connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
            control.active(s);s.setFetchSize(connection.getMetaData().getDriverName().toLowerCase(Locale.ROOT).contains("mysql")?Integer.MIN_VALUE:128);
            try(ResultSet r=s.executeQuery("SELECT "+projection+" FROM "+quote(database)+"."+quote(object.name()))){
                while(r.next()){
                    control.check();if(batch==0){out.write(prefix);batchStart=out.count;}else out.write(",\n");out.write("(");int index=1;
                    for(int i=0;i<columns.size();i++){
                        if(i>0)out.write(",");if(columns.get(i).geometry()){
                            InputStream value=r.getBinaryStream(index++);int srid=r.getInt(index++);if(value==null)out.write("NULL");else {out.write("ST_GeomFromWKB(");hex(value,out,control);out.write(","+srid+")");}
                        }else writeValue(r,index++,out,control);
                    }
                    out.write(")");if(out.count-batchStart>31L*1024*1024)throw new IOException("Una fila supera el tamaño máximo de sentencia SQL (31 MB). Usa la herramienta nativa del servidor para este objeto.");
                    rows++;batch++;if(batch>=100||out.count-batchStart>=512*1024){out.write(";\n");batch=0;}
                    if(rows%500==0)progress.accept(new Progress(object.name(),before+rows,completed,total));
                }
                if(batch>0)out.write(";\n");
            }
        }finally{control.active(null);}return rows;
    }
    private static void writeValue(ResultSet result,int index,Writer out,SqlTransferControl control)throws Exception {
        int type=result.getMetaData().getColumnType(index);
        switch(type){
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB -> {InputStream value=result.getBinaryStream(index);if(value==null)out.write("NULL");else hex(value,out,control);}
            case Types.BIT -> {byte[] value=result.getBytes(index);out.write(value==null?"NULL":"X'"+HexFormat.of().formatHex(value)+"'");}
            case Types.DATE,Types.TIME,Types.TIMESTAMP,Types.TIME_WITH_TIMEZONE,Types.TIMESTAMP_WITH_TIMEZONE -> {String value=result.getString(index);out.write(value==null?"NULL":literal(value));}
            case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL,Types.REAL,Types.FLOAT,Types.DOUBLE -> {String value=result.getString(index);if(value==null)out.write("NULL");else {if(!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))throw new SQLException("Valor numérico no exportable");out.write(value);}}
            case Types.BOOLEAN -> {boolean value=result.getBoolean(index);out.write(result.wasNull()?"NULL":value?"1":"0");}
            default -> {
                Reader value=result.getCharacterStream(index);if(value==null){out.write("NULL");break;}
                out.write("CONVERT(");out.write("X'");
                try(value){Writer utf8=new OutputStreamWriter(new HexOutput(out),StandardCharsets.UTF_8);char[] buffer=new char[4096];int n;while((n=value.read(buffer))!=-1){control.check();utf8.write(buffer,0,n);}utf8.flush();}
                out.write("' USING utf8mb4)");
            }
        }
    }
    private static void hex(InputStream input,Writer out,SqlTransferControl control)throws Exception{out.write("X'");try(input){HexOutput hex=new HexOutput(out);byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){control.check();hex.write(buffer,0,count);}}out.write("'");}
    private static String literal(String value){return "'"+value.replace("'","''")+"'";}
    private static final class HexOutput extends OutputStream {
        private static final char[] HEX="0123456789ABCDEF".toCharArray();private final Writer out;
        HexOutput(Writer out){this.out=out;}public void write(int b)throws IOException{out.write(HEX[(b>>4)&15]);out.write(HEX[b&15]);}
        public void write(byte[] bytes,int offset,int count)throws IOException{char[] chars=new char[count*2];for(int i=0;i<count;i++){int b=bytes[offset+i]&255;chars[2*i]=HEX[b>>4];chars[2*i+1]=HEX[b&15];}out.write(chars);}
    }
    private static final class CountingWriter extends Writer {
        private final Writer out;long count;CountingWriter(Writer out){this.out=out;}public void write(char[] chars,int offset,int length)throws IOException{out.write(chars,offset,length);count+=length;}public void flush()throws IOException{out.flush();}public void close(){}
    }
}
