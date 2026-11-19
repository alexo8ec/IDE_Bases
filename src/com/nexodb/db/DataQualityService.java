package com.nexodb.db;

import com.nexodb.model.DatabaseType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Preview-first profiling, deterministic masking and test-data generation. */
public final class DataQualityService {
    public static final class Control implements BooleanSupplier {
        private final AtomicBoolean cancelled=new AtomicBoolean();private volatile Statement statement;
        public void cancel(){cancelled.set(true);Statement active=statement;if(active!=null)try{active.cancel();}catch(SQLException ignored){}}
        public boolean getAsBoolean(){return cancelled.get();}
        private void active(Statement value){statement=value;if(value!=null&&cancelled.get())try{value.cancel();}catch(SQLException ignored){}}
        private void clear(Statement value){if(statement==value)statement=null;}
    }
    public enum MaskRule { CONSERVAR, NULO, FIJO, HASH, EMAIL, TELEFONO, NOMBRE }
    public record Column(String name,int jdbcType,String typeName,int size,boolean nullable,boolean autoIncrement,boolean generated,boolean primaryKey,String defaultValue) {
        public boolean text(){return switch(jdbcType){case Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB->true;default->false;};}
        public boolean writable(){return !autoIncrement&&!generated;}
    }
    public record Table(String catalog,String schema,String name,List<Column> columns,List<String> primaryKeys,String quote,String qualifiedName) {
        public Table { columns=List.copyOf(columns);primaryKeys=List.copyOf(primaryKeys); }
        public Column column(String name){return columns.stream().filter(c->c.name().equalsIgnoreCase(name)).findFirst().orElseThrow();}
    }
    public record ColumnProfile(String name,String type,long nulls,long empty,long distinct,String minimum,String maximum,int maximumLength) {}
    public record Profile(long rows,List<ColumnProfile> columns) { public Profile {columns=List.copyOf(columns);} }
    public record MaskSelection(String column,MaskRule rule,String fixedValue) {}
    public record MaskPreview(Table table,List<String> columns,List<List<Object>> original,List<List<Object>> masked,List<Map<String,Object>> keys) {
        public MaskPreview {columns=List.copyOf(columns);original=copyRows(original);masked=copyRows(masked);keys=keys.stream().map(Map::copyOf).toList();}
    }
    public record GeneratedPreview(Table table,List<String> columns,List<List<Object>> rows) {
        public GeneratedPreview {columns=List.copyOf(columns);rows=copyRows(rows);}
    }

    private DataQualityService() {}

    public static List<String> tables(Connection connection,DatabaseType type,String database)throws SQLException{
        DatabaseMetaData metadata=connection.getMetaData();boolean catalog=SchemaMetadataReader.catalogNamespace(type);List<String> values=new ArrayList<>();
        try(ResultSet rows=metadata.getTables(catalog?database:null,catalog?null:database,"%",new String[]{"TABLE"})){while(rows.next())values.add(rows.getString("TABLE_NAME"));}
        values.sort(String.CASE_INSENSITIVE_ORDER);return List.copyOf(values);
    }

    public static Table describe(Connection connection,DatabaseType type,String database,String table)throws SQLException{
        DatabaseMetaData metadata=connection.getMetaData();boolean catalogMode=SchemaMetadataReader.catalogNamespace(type);String catalog=catalogMode?database:null,schema=catalogMode?null:database;
        LinkedHashSet<String> primary=new LinkedHashSet<>();try(ResultSet keys=metadata.getPrimaryKeys(catalog,schema,table)){TreeMap<Short,String> ordered=new TreeMap<>();while(keys.next())ordered.put(keys.getShort("KEY_SEQ"),keys.getString("COLUMN_NAME"));primary.addAll(ordered.values());}
        List<Column> columns=new ArrayList<>();String actualSchema=schema;try(ResultSet rows=metadata.getColumns(catalog,schema,table,"%")){while(rows.next()){if(actualSchema==null||actualSchema.isBlank())actualSchema=optional(rows,"TABLE_SCHEM");String name=rows.getString("COLUMN_NAME");columns.add(new Column(name,rows.getInt("DATA_TYPE"),rows.getString("TYPE_NAME"),rows.getInt("COLUMN_SIZE"),rows.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,"YES".equalsIgnoreCase(optional(rows,"IS_AUTOINCREMENT")),"YES".equalsIgnoreCase(optional(rows,"IS_GENERATEDCOLUMN")),primary.stream().anyMatch(name::equalsIgnoreCase),rows.getString("COLUMN_DEF")));}}
        if(columns.isEmpty())throw new SQLException("No se encontraron columnas para "+table+".");String quote=Objects.toString(metadata.getIdentifierQuoteString(),"").trim();return new Table(catalog,actualSchema,table,columns,List.copyOf(primary),quote,qualified(type,database,actualSchema,table,quote));
    }

    public static Profile profile(Connection connection,Table table,BooleanSupplier cancelled)throws SQLException{
        long total=scalarLong(connection,"SELECT COUNT(*) FROM "+table.qualifiedName(),cancelled);List<ColumnProfile> profiles=new ArrayList<>();
        for(Column column:table.columns()){check(cancelled);String q=quote(column.name(),table.quote()),source=table.qualifiedName();long nulls=safeLong(connection,"SELECT COUNT(*) FROM "+source+" WHERE "+q+" IS NULL",cancelled);long empty=column.text()?safeLong(connection,"SELECT COUNT(*) FROM "+source+" WHERE "+q+" = ''",cancelled):0;long distinct=safeLong(connection,"SELECT COUNT(DISTINCT "+q+") FROM "+source,cancelled);String minimum=safeText(connection,"SELECT MIN("+q+") FROM "+source,cancelled),maximum=safeText(connection,"SELECT MAX("+q+") FROM "+source,cancelled);long measured=column.text()?safeLong(connection,"SELECT COALESCE(MAX(LENGTH("+q+")),0) FROM "+source,cancelled):0;int length=measured<0?-1:(int)Math.min(Integer.MAX_VALUE,measured);profiles.add(new ColumnProfile(column.name(),column.typeName(),nulls,empty,distinct,minimum,maximum,length));}
        return new Profile(total,profiles);
    }

    public static MaskPreview previewMask(Connection connection,Table table,List<MaskSelection> selections,int limit,BooleanSupplier cancelled)throws SQLException{
        if(table.primaryKeys().isEmpty())throw new SQLException("La tabla no tiene clave primaria. El enmascaramiento permanece bloqueado para evitar actualizar filas ambiguas.");List<MaskSelection> active=selections.stream().filter(s->s.rule()!=MaskRule.CONSERVAR).toList();if(active.isEmpty())throw new SQLException("Selecciona al menos una regla de enmascaramiento.");
        for(MaskSelection selection:active){Column column=table.column(selection.column());if(column.primaryKey())throw new SQLException("No se permite enmascarar la clave primaria "+column.name()+".");if(!column.writable())throw new SQLException("La columna "+column.name()+" es automática o generada.");if(selection.rule()==MaskRule.NULO&&!column.nullable())throw new SQLException("La columna "+column.name()+" no permite NULL.");if(!column.text()&&!Set.of(MaskRule.NULO,MaskRule.FIJO).contains(selection.rule()))throw new SQLException("La regla "+selection.rule()+" solo se puede aplicar a columnas de texto.");if(selection.rule()==MaskRule.FIJO&&column.text()&&column.size()>0&&Objects.toString(selection.fixedValue(),"").length()>column.size())throw new SQLException("El valor fijo supera la longitud de "+column.name()+".");}
        LinkedHashSet<String> read=new LinkedHashSet<>(table.primaryKeys());active.forEach(s->read.add(s.column()));List<String> readColumns=List.copyOf(read);String sql="SELECT "+readColumns.stream().map(c->quote(c,table.quote())).reduce((a,b)->a+", "+b).orElseThrow()+" FROM "+table.qualifiedName();List<List<Object>> original=new ArrayList<>(),masked=new ArrayList<>();List<Map<String,Object>> keys=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement(sql)){active(cancelled,statement);statement.setMaxRows(Math.max(1,Math.min(500,limit)));try{statement.setQueryTimeout(30);}catch(SQLException ignored){}try(ResultSet rows=statement.executeQuery()){while(rows.next()){check(cancelled);Map<String,Object> key=new LinkedHashMap<>();for(String pk:table.primaryKeys())key.put(pk,rows.getObject(index(readColumns,pk)+1));List<Object> before=new ArrayList<>(),after=new ArrayList<>();for(MaskSelection selection:active){Column column=table.column(selection.column());Object value=rows.getObject(index(readColumns,selection.column())+1);before.add(value);after.add(masked(column,value,selection,key));}original.add(before);masked.add(after);keys.add(key);}}finally{clear(cancelled,statement);}}
        return new MaskPreview(table,active.stream().map(MaskSelection::column).toList(),original,masked,keys);
    }

    public static int applyMask(Connection connection,MaskPreview preview,BooleanSupplier cancelled)throws SQLException{
        if(preview.masked().isEmpty())return 0;boolean auto=connection.getAutoCommit();if(!auto)throw new SQLException("Finaliza la transacción actual con Commit o Rollback antes de aplicar el enmascaramiento.");connection.setAutoCommit(false);int updated=0;String set=preview.columns().stream().map(c->quote(c,preview.table().quote())+"=?").reduce((a,b)->a+", "+b).orElseThrow();String where=preview.table().primaryKeys().stream().map(c->quote(c,preview.table().quote())+"=?").reduce((a,b)->a+" AND "+b).orElseThrow();String sql="UPDATE "+preview.table().qualifiedName()+" SET "+set+" WHERE "+where;
        try(PreparedStatement statement=connection.prepareStatement(sql)){active(cancelled,statement);for(int row=0;row<preview.masked().size();row++){check(cancelled);int parameter=1;for(Object value:preview.masked().get(row))statement.setObject(parameter++,value);for(String key:preview.table().primaryKeys())statement.setObject(parameter++,preview.keys().get(row).get(key));int changed=statement.executeUpdate();if(changed!=1)throw new SQLException("La fila cambió desde la vista previa; se esperaba actualizar 1 fila y se actualizaron "+changed+".");updated+=changed;}connection.commit();return updated;}catch(SQLException|RuntimeException error){connection.rollback();throw error;}finally{clear(cancelled,null);connection.setAutoCommit(auto);}
    }

    public static GeneratedPreview generate(Table table,int count){List<Column> writable=table.columns().stream().filter(Column::writable).filter(c->c.defaultValue()==null||c.primaryKey()).filter(c->!c.autoIncrement()).toList();List<List<Object>> rows=new ArrayList<>();for(int row=1;row<=Math.max(1,Math.min(500,count));row++){List<Object> values=new ArrayList<>();for(Column column:writable)values.add(generated(column,row));rows.add(values);}return new GeneratedPreview(table,writable.stream().map(Column::name).toList(),rows);}

    public static int insertGenerated(Connection connection,GeneratedPreview preview,BooleanSupplier cancelled)throws SQLException{
        if(preview.columns().isEmpty())throw new SQLException("No hay columnas editables para insertar.");String columns=preview.columns().stream().map(c->quote(c,preview.table().quote())).reduce((a,b)->a+", "+b).orElseThrow();String marks=String.join(", ",Collections.nCopies(preview.columns().size(),"?"));String sql="INSERT INTO "+preview.table().qualifiedName()+" ("+columns+") VALUES ("+marks+")";boolean auto=connection.getAutoCommit();if(!auto)throw new SQLException("Finaliza la transacción actual con Commit o Rollback antes de insertar datos de prueba.");connection.setAutoCommit(false);int inserted=0;
        try(PreparedStatement statement=connection.prepareStatement(sql)){active(cancelled,statement);for(List<Object> row:preview.rows()){check(cancelled);for(int i=0;i<row.size();i++)statement.setObject(i+1,row.get(i));inserted+=statement.executeUpdate();}connection.commit();return inserted;}catch(SQLException|RuntimeException error){connection.rollback();throw error;}finally{clear(cancelled,null);connection.setAutoCommit(auto);}
    }

    public static Object mask(Object value,MaskRule rule,String fixed,String seed){if(rule==MaskRule.CONSERVAR)return value;if(rule==MaskRule.NULO)return null;if(rule==MaskRule.FIJO)return fixed;if(value==null)return null;String hash=hash(seed+"|"+value),text=String.valueOf(value);return switch(rule){case HASH->hash;case EMAIL->"usuario_"+hash.substring(0,10)+"@ejemplo.invalid";case TELEFONO->"09"+digits(hash,8);case NOMBRE->"Persona "+hash.substring(0,8).toUpperCase(Locale.ROOT);default->text;};}
    private static Object generated(Column column,int row){return switch(column.jdbcType()){case Types.BOOLEAN,Types.BIT->row%2==0;case Types.TINYINT,Types.SMALLINT,Types.INTEGER->row;case Types.BIGINT->(long)row;case Types.REAL,Types.FLOAT,Types.DOUBLE,Types.NUMERIC,Types.DECIMAL->BigDecimal.valueOf(row).movePointLeft(2);case Types.DATE->java.sql.Date.valueOf(LocalDate.now().plusDays(row));case Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE->Timestamp.valueOf(LocalDateTime.now().plusMinutes(row).withNano(0));case Types.TIME,Types.TIME_WITH_TIMEZONE->Time.valueOf(LocalDateTime.now().plusMinutes(row).toLocalTime().withNano(0));default->fit("PRUEBA_"+String.format(Locale.ROOT,"%04d",row),column.size());};}
    private static String fit(String value,int size){return size>0&&value.length()>size?value.substring(0,size):value;}
    private static int index(List<String> values,String name){for(int i=0;i<values.size();i++)if(values.get(i).equalsIgnoreCase(name))return i;return -1;}
    private static long scalarLong(Connection connection,String sql,BooleanSupplier cancelled)throws SQLException{try(Statement statement=connection.createStatement()){active(cancelled,statement);try{statement.setQueryTimeout(30);}catch(SQLException ignored){}try(ResultSet row=statement.executeQuery(sql)){check(cancelled);return row.next()?row.getLong(1):0;}finally{clear(cancelled,statement);}}}
    private static String scalarText(Connection connection,String sql,BooleanSupplier cancelled)throws SQLException{try(Statement statement=connection.createStatement()){active(cancelled,statement);try{statement.setQueryTimeout(30);}catch(SQLException ignored){}try(ResultSet row=statement.executeQuery(sql)){check(cancelled);Object value=row.next()?row.getObject(1):null;return value==null?"":String.valueOf(value);}finally{clear(cancelled,statement);}}}
    private static long safeLong(Connection connection,String sql,BooleanSupplier cancelled){try{return scalarLong(connection,sql,cancelled);}catch(SQLException unsupported){check(cancelled);return -1;}}
    private static String safeText(Connection connection,String sql,BooleanSupplier cancelled){try{return scalarText(connection,sql,cancelled);}catch(SQLException unsupported){check(cancelled);return "No compatible";}}
    private static void check(BooleanSupplier cancelled){if(Thread.currentThread().isInterrupted()||cancelled!=null&&cancelled.getAsBoolean())throw new CancellationException("Operación cancelada");}
    private static void active(BooleanSupplier cancelled,Statement statement){if(cancelled instanceof Control control)control.active(statement);}
    private static void clear(BooleanSupplier cancelled,Statement statement){if(cancelled instanceof Control control){if(statement==null)control.active(null);else control.clear(statement);}}
    private static String optional(ResultSet rows,String column){try{return rows.getString(column);}catch(SQLException ignored){return "";}}
    private static Object masked(Column column,Object value,MaskSelection selection,Map<String,Object> key){Object result=mask(value,selection.rule(),selection.fixedValue(),key+"|"+selection.column());if(result instanceof String text&&column.size()>0)return fit(text,column.size());return result;}
    private static String qualified(DatabaseType type,String database,String schema,String table,String quote){
        if(type==DatabaseType.SQLSERVER){String prefix=database==null||database.isBlank()?"":quote(database,quote)+".";return prefix+(schema==null||schema.isBlank()?"":quote(schema,quote)+".")+quote(table,quote);}
        return (database==null||database.isBlank()?"":quote(database,quote)+".")+quote(table,quote);
    }
    private static String quote(String name,String quote){String q=Objects.toString(quote,"").trim();if(q.isEmpty()){if(!name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*"))throw new IllegalArgumentException("Identificador no seguro: "+name);return name;}return q+name.replace(q,q+q)+q;}
    private static String hash(String value){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte item:digest)out.append(String.format("%02x",item));return out.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String digits(String value,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(Character.digit(value.charAt(i),16)%10);return out.toString();}
    private static List<List<Object>> copyRows(List<List<Object>> rows){return rows.stream().map(row->Collections.unmodifiableList(new ArrayList<>(row))).toList();}
}
