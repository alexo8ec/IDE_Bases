package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static com.nexodb.util.I18n.tr;

/** Resolves actual JDBC column origins and updates one identified row with optimistic locking. */
public final class QueryEditSupport {
    public record Column(String name, int type, boolean writable, String sourceName) {
        public Column(String name,int type,boolean writable){this(name,type,writable,name);}
    }
    public record Target(String qualifiedTable, List<Column> columns, List<Integer> keys, SchemaItem table, DatabaseType type) {
        public Target { columns=List.copyOf(columns); keys=List.copyOf(keys); }
    }
    public record Eligibility(Target target, String reason, List<Target> targets) {
        public Eligibility {
            targets=targets==null?(target==null?List.of():List.of(target)):List.copyOf(targets);
        }
        public Eligibility(Target target,String reason){this(target,reason,target==null?List.of():List.of(target));}
    }
    static record Token(String value, boolean identifier, int start, int end) {
        boolean keyword(String word) { return !identifier && value.equalsIgnoreCase(word); }
        boolean name() { return identifier || value.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*"); }
    }
    private QueryEditSupport() {}
    private static Eligibility denied(String reason) { return new Eligibility(null,reason); }

    /** Per-session bounded cache; cleared by JdbcSession after every schema-changing statement. */
    public static final class MetadataCache {
        private final LinkedHashMap<TableOrigin,TableShape> tables=new LinkedHashMap<>(32,.75f,true){protected boolean removeEldestEntry(Map.Entry<TableOrigin,TableShape> eldest){return size()>128;}};
        synchronized TableShape get(TableOrigin origin){return tables.get(origin);}synchronized void put(TableOrigin origin,TableShape shape){tables.put(origin,shape);}public synchronized void clear(){tables.clear();}
    }
    private record TableShape(boolean baseTable,Map<String,String> columns,Set<String> generated,SortedMap<Integer,String> primary){}

    public static Eligibility describe(Connection connection, DatabaseType type, String sql, ResultSetMetaData result) {
        return describe(connection,type,sql,result,new MetadataCache());
    }

    static Eligibility describe(Connection connection, DatabaseType type, String sql, ResultSetMetaData result,MetadataCache cache) {
        try {
            if (!editableSelect(sql)) return denied("La edición requiere un SELECT sin columnas calculadas, agrupaciones, subconsultas ni uniones de resultados.");
            int count=result.getColumnCount();
            Map<TableOrigin,List<Integer>> origins=new LinkedHashMap<>();
            for(int i=1;i<=count;i++) {
                String table=clean(result.getTableName(i));
                if(table.isEmpty()) return denied("El controlador no identifica la tabla de origen de una o más columnas calculadas.");
                String catalog=clean(result.getCatalogName(i)),schema=clean(result.getSchemaName(i));
                if(catalog.isEmpty())catalog=clean(connection.getCatalog());
                if(schema.isEmpty()&&type!=DatabaseType.MYSQL&&type!=DatabaseType.MARIADB&&type!=DatabaseType.SQLITE)schema=clean(connection.getSchema());
                origins.computeIfAbsent(new TableOrigin(catalog,schema,table),key->new ArrayList<>()).add(i-1);
            }
            if(origins.isEmpty())return denied("El resultado no contiene una tabla editable.");
            DatabaseMetaData metadata=connection.getMetaData();
            String escape=clean(metadata.getSearchStringEscape());
            String quote=clean(metadata.getIdentifierQuoteString());
            List<Target> targets=new ArrayList<>();
            for(TableOrigin origin:origins.keySet()) {
                Target target=target(metadata,result,type,origin,count,escape,quote,cache);
                if(target!=null)targets.add(target);
            }
            if(targets.isEmpty())return denied(origins.size()>1
                    ?"Para editar un JOIN, incluye en el SELECT la clave primaria completa de la tabla que deseas modificar."
                    :"La tabla no tiene una clave primaria completa en el resultado; permanece en solo lectura.");
            return new Eligibility(targets.get(0),"",targets);
        }catch(SQLException|RuntimeException error) {
            return denied("No se pudo identificar una clave primaria editable para este resultado.");
        }
    }

    private record TableOrigin(String catalog,String schema,String table) {}
    record Enrichment(String sql,int hiddenColumns) {}
    static Enrichment enrichMissingPrimaryKeys(Connection connection,DatabaseType type,String sql,ResultSetMetaData result,MetadataCache cache)throws SQLException{
        if(!simpleSelect(sql)||result.getColumnCount()==0)return null;
        String table=clean(result.getTableName(1));if(table.isEmpty())return null;
        String catalog=clean(result.getCatalogName(1)),schema=clean(result.getSchemaName(1));
        if(catalog.isEmpty())catalog=clean(connection.getCatalog());
        if(schema.isEmpty()&&type!=DatabaseType.MYSQL&&type!=DatabaseType.MARIADB&&type!=DatabaseType.SQLITE)schema=clean(connection.getSchema());
        TableOrigin origin=new TableOrigin(catalog,schema,table);
        Set<String> selected=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(int column=1;column<=result.getColumnCount();column++){
            if(!table.equalsIgnoreCase(clean(result.getTableName(column))))return null;
            selected.add(clean(result.getColumnName(column)));
        }
        DatabaseMetaData metadata=connection.getMetaData();String escape=clean(metadata.getSearchStringEscape()),quote=clean(metadata.getIdentifierQuoteString());
        TableShape shape=cache==null?null:cache.get(origin);if(shape==null)shape=readShape(metadata,origin,escape);if(cache!=null)cache.put(origin,shape);
        if(!shape.baseTable()||shape.primary().isEmpty())return null;
        List<String> missing=shape.primary().values().stream().filter(name->!selected.contains(name)).toList();if(missing.isEmpty())return null;
        List<Token> tokens=tokenize(sql);int from=-1;for(int i=0;i<tokens.size();i++)if(tokens.get(i).keyword("FROM")){from=i;break;}if(from<0)return null;
        int source=from+1,index=source;if(index>=tokens.size()||!tokens.get(index).name())return null;int last=index++;
        while(index+1<tokens.size()&&tokens.get(index).value().equals(".")&&tokens.get(index+1).name()){last=index+1;index+=2;}
        String qualifier=sql.substring(tokens.get(source).start(),tokens.get(last).end());
        if(index<tokens.size()&&tokens.get(index).keyword("AS")){if(++index>=tokens.size()||!tokens.get(index).name())return null;qualifier=sql.substring(tokens.get(index).start(),tokens.get(index).end());}
        else if(index<tokens.size()&&tokens.get(index).name()&&!clause(tokens.get(index)))qualifier=sql.substring(tokens.get(index).start(),tokens.get(index).end());
        String prefix=qualifier+".";String additions=String.join(", ",missing.stream().map(name->prefix+quoted(name,quote)).toList());
        String rewritten=sql.substring(0,tokens.get(from).start()).stripTrailing()+", "+additions+" "+sql.substring(tokens.get(from).start());
        return new Enrichment(rewritten,missing.size());
    }
    private static Target target(DatabaseMetaData metadata,ResultSetMetaData result,DatabaseType type,TableOrigin origin,int count,String escape,String quote,MetadataCache cache)throws SQLException {
        TableShape shape=cache==null?null:cache.get(origin);if(shape==null)shape=readShape(metadata,origin,escape);if(cache!=null)cache.put(origin,shape);
        if(!shape.baseTable())return null;Map<String,String> actual=shape.columns();Set<String> generated=shape.generated();
        List<Column> columns=new ArrayList<>();Map<String,Integer> indexes=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(int i=1;i<=count;i++) {
            String source=clean(result.getColumnName(i));int jdbcType=result.getColumnType(i);
            boolean belongs=origin.table().equalsIgnoreCase(clean(result.getTableName(i)))
                    && namespaceMatches(origin.catalog(),clean(result.getCatalogName(i)))
                    && namespaceMatches(origin.schema(),clean(result.getSchemaName(i)));
            String real=actual.get(source);boolean writable=belongs&&real!=null&&!generated.contains(real)&&supported(jdbcType);
            if(belongs&&real!=null&&indexes.put(real,i-1)!=null)return null;
            columns.add(new Column(quoted(real==null?source:real,quote),jdbcType,writable,real==null?source:real));
        }
        if(shape.primary().isEmpty())return null;
        List<Integer> keys=new ArrayList<>();
        for(String name:shape.primary().values()) {Integer index=indexes.get(name);if(index==null||!supported(columns.get(index).type()))return null;keys.add(index);}
        List<String> parts=new ArrayList<>();
        if(type==DatabaseType.MYSQL||type==DatabaseType.MARIADB){if(!origin.catalog().isEmpty())parts.add(quoted(origin.catalog(),quote));}
        else if(type==DatabaseType.SQLSERVER){if(!origin.catalog().isEmpty())parts.add(quoted(origin.catalog(),quote));if(!origin.schema().isEmpty())parts.add(quoted(origin.schema(),quote));}
        else if(!origin.schema().isEmpty())parts.add(quoted(origin.schema(),quote));
        parts.add(quoted(origin.table(),quote));
        return new Target(String.join(".",parts),columns,keys,new SchemaItem(SchemaItem.Kind.TABLE,empty(origin.catalog()),empty(origin.schema()),origin.table(),""),type);
    }
    private static TableShape readShape(DatabaseMetaData metadata,TableOrigin origin,String escape)throws SQLException{
        boolean baseTable=false;
        try(ResultSet tables=metadata.getTables(empty(origin.catalog()),empty(pattern(origin.schema(),escape)),pattern(origin.table(),escape),new String[]{"TABLE"})) {
            while(tables.next())if(origin.table().equalsIgnoreCase(clean(tables.getString("TABLE_NAME"))))baseTable=true;
        }
        Map<String,String> actual=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);Set<String> generated=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try(ResultSet columns=metadata.getColumns(empty(origin.catalog()),empty(pattern(origin.schema(),escape)),pattern(origin.table(),escape),"%")) {
            while(columns.next()) {
                String name=columns.getString("COLUMN_NAME");actual.put(name,name);
                try{if("YES".equalsIgnoreCase(columns.getString("IS_GENERATEDCOLUMN")))generated.add(name);}catch(SQLException ignored){}
            }
        }
        SortedMap<Integer,String> primary=new TreeMap<>();
        try(ResultSet keys=metadata.getPrimaryKeys(empty(origin.catalog()),empty(origin.schema()),origin.table())) {
            while(keys.next())primary.put(keys.getInt("KEY_SEQ"),keys.getString("COLUMN_NAME"));
        }
        return new TableShape(baseTable,Collections.unmodifiableMap(actual),Collections.unmodifiableSet(generated),Collections.unmodifiableSortedMap(primary));
    }
    private static boolean namespaceMatches(String resolved,String reported){return reported.isEmpty()||resolved.equalsIgnoreCase(reported);}

    public record Saved(Object value, boolean pendingCommit) {}
    public enum DefaultValue { INSTANCE; @Override public String toString(){return "(DEFAULT)";} }
    public enum ChangeKind { UPDATE, INSERT, DELETE }
    public record RowChange(int row, List<Object> original, Map<Integer,Object> values, ChangeKind kind) {
        public RowChange(int row,List<Object> original,Map<Integer,Object> values){this(row,original,values,ChangeKind.UPDATE);}
        public RowChange { original=Collections.unmodifiableList(new ArrayList<>(original));values=Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    }
    public record BatchSaved(List<RowChange> rows, boolean pendingCommit) {}
    public static Saved save(Connection connection, Target target, List<Object> row, int column, Object input) throws SQLException {
        Map<Integer,Object> values=new LinkedHashMap<>();values.put(column,input);
        BatchSaved saved=saveChanges(connection,target,List.of(new RowChange(0,row,values)));
        return new Saved(saved.rows().get(0).values().get(column),saved.pendingCommit());
    }
    public static BatchSaved saveChanges(Connection connection, Target target, List<RowChange> changes) throws SQLException {
        List<RowChange> normalized=new ArrayList<>();
        if(target==null||target.keys().isEmpty())throw new SQLException(tr("Este resultado es de solo lectura."));
        for(RowChange row:changes) {
            if(row.original().size()!=target.columns().size())throw new SQLException(tr("Este resultado es de solo lectura."));
            Map<Integer,Object> values=new LinkedHashMap<>();
            for(var entry:row.values().entrySet()) {
                int column=entry.getKey();
                if(column<0||column>=target.columns().size()||!target.columns().get(column).writable())throw new SQLException(tr("Esta columna es de solo lectura."));
                values.put(column,convert(entry.getValue(),target.columns().get(column).type()));
            }
            normalized.add(new RowChange(row.row(),row.original(),values,row.kind()));
        }
        synchronized(connection) {
            if(connection.isClosed() || connection.isReadOnly()) throw new SQLException(tr("La conexión está cerrada o en modo de solo lectura."));
            boolean auto=connection.getAutoCommit();
            Savepoint savepoint=null;boolean completed=false;
            try {
                if(auto) connection.setAutoCommit(false); else savepoint=connection.setSavepoint();
                for(RowChange row:normalized)switch(row.kind()) {
                    case UPDATE -> updateRow(connection,target,row);
                    case INSERT -> insertRow(connection,target,row);
                    case DELETE -> deleteRow(connection,target,row);
                }
                if(auto)connection.commit();
                completed=true;
                return new BatchSaved(List.copyOf(normalized),!auto);
            }finally {
                try {
                    if(!completed) { if(auto)connection.rollback();else if(savepoint!=null)connection.rollback(savepoint); }
                    if(savepoint!=null)try{connection.releaseSavepoint(savepoint);}catch(SQLException ignored){}
                } finally { if(auto)connection.setAutoCommit(true); }
            }
        }
    }
    private static void updateRow(Connection connection,Target target,RowChange row)throws SQLException {
        Map<Integer,Object> changed=new LinkedHashMap<>();
        for(var entry:row.values().entrySet())if(!equivalent(row.original().get(entry.getKey()),entry.getValue()))changed.put(entry.getKey(),entry.getValue());
        if(changed.isEmpty())return;
        StringBuilder sql=new StringBuilder("UPDATE ").append(target.qualifiedTable()).append(" SET ");
        List<Object> parameters=new ArrayList<>();
        int assignments=0;
        for(var entry:changed.entrySet()) {
            if(assignments++>0)sql.append(", ");
            sql.append(target.columns().get(entry.getKey()).name()).append('=');
            if(entry.getValue()==DefaultValue.INSTANCE)sql.append("DEFAULT");
            else{sql.append('?');parameters.add(entry.getValue());}
        }
        sql.append(" WHERE ");
        for(int i=0;i<target.keys().size();i++) {
            int key=target.keys().get(i);Object value=row.original().get(key);
            if(value==null)throw new SQLException(tr("La clave primaria de esta fila no está disponible."));
            if(i>0)sql.append(" AND ");
            sql.append(target.columns().get(key).name()).append("=?");parameters.add(value);
        }
        for(int column:changed.keySet()) {
            sql.append(" AND ").append(target.columns().get(column).name());Object old=row.original().get(column);
            if(old==null)sql.append(" IS NULL");else{sql.append("=?");parameters.add(old);}
        }
        try(PreparedStatement statement=connection.prepareStatement(sql.toString())) {
            for(int i=0;i<parameters.size();i++)statement.setObject(i+1,parameters.get(i));
            if(statement.executeUpdate()!=1)throw new SQLException(tr("La fila cambió o fue eliminada. Ejecuta de nuevo la consulta antes de editarla."));
        }
    }
    private static void insertRow(Connection connection,Target target,RowChange row)throws SQLException {
        List<Integer> columns=row.values().keySet().stream().filter(index->row.values().get(index)!=DefaultValue.INSTANCE).toList();
        StringBuilder sql=new StringBuilder("INSERT INTO ").append(target.qualifiedTable());
        if(columns.isEmpty()) {
            if(target.type()==DatabaseType.MYSQL||target.type()==DatabaseType.MARIADB)sql.append(" () VALUES ()");
            else if(target.type()==DatabaseType.ORACLE)sql.append(" (").append(target.columns().get(0).name()).append(") VALUES (DEFAULT)");
            else sql.append(" DEFAULT VALUES");
        }else {
            sql.append(" (");for(int i=0;i<columns.size();i++){if(i>0)sql.append(',');sql.append(target.columns().get(columns.get(i)).name());}
            sql.append(") VALUES (").append(String.join(",",Collections.nCopies(columns.size(),"?"))).append(')');
        }
        try(PreparedStatement statement=connection.prepareStatement(sql.toString())) {
            for(int i=0;i<columns.size();i++)statement.setObject(i+1,row.values().get(columns.get(i)));
            if(statement.executeUpdate()!=1)throw new SQLException(tr("No se pudo insertar la fila."));
        }
    }
    private static void deleteRow(Connection connection,Target target,RowChange row)throws SQLException {
        Set<Integer> columns=new LinkedHashSet<>(target.keys());
        for(int index=0;index<target.columns().size();index++)if(target.columns().get(index).writable())columns.add(index);
        StringBuilder sql=new StringBuilder("DELETE FROM ").append(target.qualifiedTable()).append(" WHERE ");
        List<Object> parameters=new ArrayList<>();int conditions=0;
        for(int index:columns) {
            Object value=row.original().get(index);
            if(target.keys().contains(index)&&value==null)throw new SQLException(tr("La clave primaria de esta fila no está disponible."));
            if(conditions++>0)sql.append(" AND ");
            sql.append(target.columns().get(index).name());
            if(value==null)sql.append(" IS NULL");else{sql.append("=?");parameters.add(value);}
        }
        try(PreparedStatement statement=connection.prepareStatement(sql.toString())) {
            for(int i=0;i<parameters.size();i++)statement.setObject(i+1,parameters.get(i));
            if(statement.executeUpdate()!=1)throw new SQLException(tr("La fila cambió o fue eliminada. Ejecuta de nuevo la consulta antes de editarla."));
        }
    }
    private static boolean equivalent(Object a,Object b){
        if(a instanceof Number&&b instanceof Number)try{return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;}catch(NumberFormatException ignored){}
        return Objects.equals(a,b);
    }
    static Object convert(Object value,int type)throws SQLException {
        if(value==null)return null;
        if(!(value instanceof String text))return value;
        try {
            return switch(type) {
                case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT -> new BigDecimal(text.trim()).toBigIntegerExact();
                case Types.NUMERIC,Types.DECIMAL -> new BigDecimal(text.trim());
                case Types.REAL,Types.FLOAT,Types.DOUBLE -> {
                    double number=Double.parseDouble(text.trim());if(!Double.isFinite(number))throw new NumberFormatException();yield number;
                }
                case Types.BIT,Types.BOOLEAN -> {
                    if(text.equalsIgnoreCase("true")||text.equals("1"))yield true;
                    if(text.equalsIgnoreCase("false")||text.equals("0"))yield false;
                    throw new NumberFormatException();
                }
                default -> text;
            };
        }catch(IllegalArgumentException|ArithmeticException error){throw new SQLException(tr("El valor no es válido para el tipo de esta columna."),error);}
    }
    private static boolean supported(int type) { return switch(type) {
        case Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,
             Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL,Types.REAL,Types.FLOAT,Types.DOUBLE,
             Types.BOOLEAN,Types.BIT,Types.DATE,Types.TIME,Types.TIMESTAMP,Types.TIME_WITH_TIMEZONE,Types.TIMESTAMP_WITH_TIMEZONE -> true;
        default -> false;
    }; }
    private static String clean(String value){return value==null?"":value;}
    private static String empty(String value){return value.isEmpty()?null:value;}
    private static String pattern(String value,String escape){return escape.isEmpty()?value:value.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");}
    private static String quoted(String name,String quote){quote=quote.trim();return quote.isEmpty()?name:quote+name.replace(quote,quote+quote)+quote;}

    /** Allows ordinary JOINs while rejecting result shapes whose rows cannot be mapped safely. */
    static boolean editableSelect(String sql) {
        List<Token> tokens=tokenize(sql);if(tokens.isEmpty()||!tokens.get(0).keyword("SELECT"))return false;
        int from=-1;
        for(int i=1;i<tokens.size();i++) {
            Token token=tokens.get(i);
            if(token.keyword("SELECT")||token.keyword("UNION")||token.keyword("INTERSECT")||token.keyword("EXCEPT")
                    ||token.keyword("GROUP")||token.keyword("HAVING")||token.keyword("DISTINCT")||token.keyword("INTO")||token.keyword("OVER"))return false;
            if(token.keyword("FROM")){if(from!=-1)return false;from=i;}
            if(token.value().equals(";")&&i!=tokens.size()-1)return false;
        }
        if(from<2||from+1>=tokens.size())return false;
        int index=1;
        while(index<from) {
            if(!tokens.get(index).name()&&!tokens.get(index).value().equals("*"))return false;
            boolean star=tokens.get(index++).value().equals("*");
            while(index<from&&tokens.get(index).value().equals(".")) {
                if(star||++index>=from||(!tokens.get(index).name()&&!tokens.get(index).value().equals("*")))return false;
                star=tokens.get(index++).value().equals("*");
            }
            if(index<from&&tokens.get(index).keyword("AS")){if(star||++index>=from||!tokens.get(index++).name())return false;}
            else if(index<from&&tokens.get(index).name()){if(star)return false;index++;}
            if(index==from)break;
            if(!tokens.get(index++).value().equals(",")||index==from)return false;
        }
        return true;
    }

    /** Conservative SELECT shape check; WHERE/ORDER/LIMIT expressions are permitted. */
    static boolean simpleSelect(String sql) {
        List<Token> tokens=tokenize(sql);if(tokens.isEmpty()||!tokens.get(0).keyword("SELECT"))return false;
        int from=-1;
        for(int i=1;i<tokens.size();i++) {
            Token token=tokens.get(i);
            if(token.keyword("SELECT")||token.keyword("JOIN")||token.keyword("UNION")||token.keyword("INTERSECT")||token.keyword("EXCEPT")
                    ||token.keyword("GROUP")||token.keyword("HAVING")||token.keyword("DISTINCT")||token.keyword("INTO")||token.keyword("OVER"))return false;
            if(token.keyword("FROM")){if(from!=-1)return false;from=i;}
            if(token.value().equals(";")&&i!=tokens.size()-1)return false;
        }
        if(from<2)return false;
        // Projections: [qualifier.]column [AS alias], or a wildcard. No expressions.
        int index=1;
        while(index<from) {
            if(!tokens.get(index).name()&&!tokens.get(index).value().equals("*"))return false;
            boolean star=tokens.get(index++).value().equals("*");
            while(index<from&&tokens.get(index).value().equals(".")) {
                if(star||++index>=from||(!tokens.get(index).name()&&!tokens.get(index).value().equals("*")))return false;
                star=tokens.get(index++).value().equals("*");
            }
            if(index<from&&tokens.get(index).keyword("AS")){if(star||++index>=from||!tokens.get(index++).name())return false;}
            else if(index<from&&tokens.get(index).name()){if(star)return false;index++;}
            if(index==from)break;
            if(!tokens.get(index++).value().equals(",")||index==from)return false;
        }
        index=from+1;if(index>=tokens.size()||!tokens.get(index++).name())return false;
        while(index<tokens.size()&&tokens.get(index).value().equals(".")){if(++index>=tokens.size()||!tokens.get(index++).name())return false;}
        if(index<tokens.size()&&tokens.get(index).keyword("AS")){if(++index>=tokens.size()||!tokens.get(index++).name())return false;}
        else if(index<tokens.size()&&tokens.get(index).name()&&!clause(tokens.get(index)))index++;
        return index==tokens.size()||clause(tokens.get(index))||tokens.get(index).value().equals(";")&&index==tokens.size()-1;
    }
    private static boolean clause(Token token){return token.keyword("WHERE")||token.keyword("ORDER")||token.keyword("LIMIT")||token.keyword("OFFSET")||token.keyword("FETCH")||token.keyword("FOR");}
    public static String qualifiedSelect(String sql,Target target) {
        // JOIN queries already carry their complete FROM clause. Replacing its first table with
        // the selected edit target would change the meaning of the query.
        if(!simpleSelect(sql))return sql;
        List<Token> tokens=tokenize(sql);
        for(int i=0;i<tokens.size();i++)if(tokens.get(i).keyword("FROM")) {
            int first=i+1,last=first;
            while(last+2<tokens.size()&&tokens.get(last+1).value().equals("."))last+=2;
            return sql.substring(0,tokens.get(first).start())+target.qualifiedTable()+sql.substring(tokens.get(last).end());
        }
        throw new IllegalArgumentException("Not a single-table SELECT");
    }
    static List<Token> tokenize(String sql) {
        List<Token> tokens=new ArrayList<>();
        for(int i=0;i<sql.length();) {
            char c=sql.charAt(i);if(Character.isWhitespace(c)){i++;continue;}
            if(c=='#'||c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-'){while(i<sql.length()&&sql.charAt(i)!='\n')i++;continue;}
            if(c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'){int end=sql.indexOf("*/",i+2);if(end<0)return List.of();i=end+2;continue;}
            if(c=='\''||c=='"'||c=='`'||c=='[') {
                int start=i;char end=c=='['?']':c;boolean identifier=c!='\'';StringBuilder value=new StringBuilder();boolean closed=false;i++;
                while(i<sql.length()) {
                    char next=sql.charAt(i++);
                    if(next==end){if(i<sql.length()&&sql.charAt(i)==end){value.append(end);i++;}else{closed=true;break;}}
                    else if(next=='\\'&&!identifier&&i<sql.length()){value.append(sql.charAt(i++));}else value.append(next);
                }
                if(!closed)return List.of();tokens.add(new Token(identifier?value.toString():"<string>",identifier,start,i));continue;
            }
            if(Character.isLetterOrDigit(c)||c=='_'||c=='$') {int start=i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'||sql.charAt(i)=='$'))i++;tokens.add(new Token(sql.substring(start,i),false,start,i));}
            else {tokens.add(new Token(String.valueOf(c),false,i,i+1));i++;}
        }
        return tokens;
    }
}
