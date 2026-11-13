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
    public record Target(String qualifiedTable, List<Column> columns, List<Integer> keys, SchemaItem table) {
        public Target { columns=List.copyOf(columns); keys=List.copyOf(keys); }
    }
    public record Eligibility(Target target, String reason) {}
    static record Token(String value, boolean identifier, int start, int end) {
        boolean keyword(String word) { return !identifier && value.equalsIgnoreCase(word); }
        boolean name() { return identifier || value.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*"); }
    }
    private QueryEditSupport() {}
    private static Eligibility denied(String reason) { return new Eligibility(null,reason); }

    public static Eligibility describe(Connection connection, DatabaseType type, String sql, ResultSetMetaData result) {
        try {
            if (!simpleSelect(sql)) return denied("La edición requiere un SELECT de una sola tabla, sin columnas calculadas, agrupaciones ni uniones.");
            int count=result.getColumnCount();
            String table="",catalog="",schema="";
            for(int i=1;i<=count;i++) {
                String name=clean(result.getTableName(i));
                if(name.isEmpty()) return denied("El controlador no identifica la tabla de origen de estas columnas.");
                String cat=clean(result.getCatalogName(i)), sch=clean(result.getSchemaName(i));
                if(i==1) { table=name;catalog=cat;schema=sch; }
                else if(!table.equals(name)||!catalog.equals(cat)||!schema.equals(sch))
                    return denied("La edición requiere columnas de una sola tabla.");
            }
            if(table.isEmpty()) return denied("El resultado no contiene una tabla editable.");
            // Capture the actual namespace now, so changing the active database cannot redirect an edit.
            if(catalog.isEmpty()) catalog=clean(connection.getCatalog());
            if(schema.isEmpty() && type!=DatabaseType.MYSQL && type!=DatabaseType.MARIADB && type!=DatabaseType.SQLITE)
                schema=clean(connection.getSchema());
            DatabaseMetaData metadata=connection.getMetaData();
            String escape=clean(metadata.getSearchStringEscape());
            boolean baseTable=false;
            try(ResultSet tables=metadata.getTables(empty(catalog),empty(pattern(schema,escape)),pattern(table,escape),new String[]{"TABLE"})) {
                while(tables.next()) if(table.equals(tables.getString("TABLE_NAME"))) baseTable=true;
            }
            if(!baseTable) return denied("La edición requiere una tabla física con clave primaria.");
            Set<String> actual=new HashSet<>(), generated=new HashSet<>();
            try(ResultSet columns=metadata.getColumns(empty(catalog),empty(pattern(schema,escape)),pattern(table,escape),"%")) {
                while(columns.next()) {
                    String name=columns.getString("COLUMN_NAME");actual.add(name);
                    try { if("YES".equalsIgnoreCase(columns.getString("IS_GENERATEDCOLUMN"))) generated.add(name); }catch(SQLException ignored) {}
                }
            }
            List<Column> columns=new ArrayList<>();Map<String,Integer> indexes=new HashMap<>();
            for(int i=1;i<=count;i++) {
                String name=clean(result.getColumnName(i));int jdbcType=result.getColumnType(i);
                if(!actual.contains(name)) return denied("El controlador no identifica las columnas originales de esta consulta.");
                if(indexes.put(name,i-1)!=null) return denied("La consulta contiene columnas repetidas. Selecciona cada columna una sola vez.");
                columns.add(new Column(name,jdbcType,!generated.contains(name)&&supported(jdbcType)));
            }
            SortedMap<Integer,String> primary=new TreeMap<>();
            try(ResultSet keys=metadata.getPrimaryKeys(empty(catalog),empty(schema),table)) {
                while(keys.next()) primary.put(keys.getInt("KEY_SEQ"),keys.getString("COLUMN_NAME"));
            }
            if(primary.isEmpty()) return denied("La tabla no tiene clave primaria; el resultado permanece en solo lectura.");
            List<Integer> keys=new ArrayList<>();
            for(String name:primary.values()) {
                Integer index=indexes.get(name);
                if(index==null || !supported(columns.get(index).type())) return denied("Incluye todas las columnas de la clave primaria en el SELECT para editar sus resultados.");
                keys.add(index);
            }
            String quote=clean(metadata.getIdentifierQuoteString());
            List<String> parts=new ArrayList<>();
            if(type==DatabaseType.MYSQL || type==DatabaseType.MARIADB) { if(!catalog.isEmpty()) parts.add(quoted(catalog,quote)); }
            else if(type==DatabaseType.SQLSERVER) { if(!catalog.isEmpty())parts.add(quoted(catalog,quote));if(!schema.isEmpty())parts.add(quoted(schema,quote)); }
            else if(!schema.isEmpty()) parts.add(quoted(schema,quote));
            parts.add(quoted(table,quote));
            // Store names already quoted; user data is always bound as parameters.
            List<Column> quotedColumns=columns.stream().map(c->new Column(quoted(c.name(),quote),c.type(),c.writable(),c.name())).toList();
            return new Eligibility(new Target(String.join(".",parts),quotedColumns,keys,new SchemaItem(SchemaItem.Kind.TABLE,empty(catalog),empty(schema),table,"")),"");
        }catch(SQLException|RuntimeException error) {
            return denied("No se pudo identificar una clave primaria editable para este resultado.");
        }
    }

    public record Saved(Object value, boolean pendingCommit) {}
    public record RowChange(int row, List<Object> original, Map<Integer,Object> values) {
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
            normalized.add(new RowChange(row.row(),row.original(),values));
        }
        synchronized(connection) {
            if(connection.isClosed() || connection.isReadOnly()) throw new SQLException(tr("La conexión está cerrada o en modo de solo lectura."));
            boolean auto=connection.getAutoCommit();
            Savepoint savepoint=null;boolean completed=false;
            try {
                if(auto) connection.setAutoCommit(false); else savepoint=connection.setSavepoint();
                for(RowChange row:normalized)updateRow(connection,target,row);
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
        for(var entry:changed.entrySet()) {
            if(!parameters.isEmpty())sql.append(", ");
            sql.append(target.columns().get(entry.getKey()).name()).append("=?");parameters.add(entry.getValue());
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
