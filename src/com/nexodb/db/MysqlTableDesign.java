package com.nexodb.db;

import java.sql.*;
import java.util.*;
import java.util.regex.*;
import static com.nexodb.util.I18n.tr;

/** Live MySQL/MariaDB structure, edited as a draft and applied as one reviewed ALTER TABLE. */
public final class MysqlTableDesign {
    public record Column(String name,String type,String length,boolean notNull,boolean unsigned,boolean autoIncrement,
                         boolean zerofill,String defaultSql,String onUpdate,String comment,String generated,String expression,
                         String charset,String collation,boolean invisible,String unsupported) {}
    public record IndexColumn(String name,String prefix,String order) {}
    public record Index(String name,String kind,List<IndexColumn> columns,String method,String comment,boolean invisible,String unsupported) {
        public Index {columns=List.copyOf(columns);}
    }
    public record Options(String engine,String charset,String collation,String comment,String autoIncrement,String rowFormat,
                          String averageRowLength,String maxRows,String minRows,String checksum,String delayKeyWrite) {}
    public record Snapshot(String database,String table,String ddl,List<Column> columns,List<Index> indexes,
                           List<ForeignKeyDefinition> keys,Options options,List<String> engines,Map<String,List<String>> collations,boolean noBackslashEscapes) {
        public Snapshot {columns=List.copyOf(columns);indexes=List.copyOf(indexes);keys=List.copyOf(keys);engines=List.copyOf(engines);collations=Map.copyOf(collations);}
    }
    public record ColumnChange(String original,Column value) {}
    public record IndexChange(String original,Index value) {}
    public record KeyChange(String original,ForeignKeyDefinition value) {}
    private MysqlTableDesign(){}
    public static String quote(String value){return VisualQuery.quote(value,"`");}
    private static String qualified(String db,String table){return VisualQuery.qualified(db,table,"`");}
    private static String optional(ResultSet row,String name){try{return Objects.toString(row.getString(name),"");}catch(SQLException ignored){return "";}}
    public static String ddl(Connection connection,String database,String table)throws SQLException{
        try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SHOW CREATE TABLE "+qualified(database,table))){if(!r.next())throw new SQLException(tr("No se encontró la tabla."));return r.getString(2);}
    }
    public static Snapshot read(JdbcSession session,String database,String table)throws SQLException{
        synchronized(session.connection()){
            Connection c=session.connection();String sqlMode="";
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT @@SESSION.sql_mode")){r.next();sqlMode=r.getString(1);}
            boolean noBackslash=Arrays.asList(sqlMode.split(",")).contains("NO_BACKSLASH_ESCAPES");
            String ddl=ddl(c,database,table);Map<String,String> definitions=columnDefinitions(ddl,noBackslash);
            List<Column> columns=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("SELECT * FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")){
                s.setString(1,database);s.setString(2,table);try(ResultSet r=s.executeQuery()){while(r.next()){
                    String name=r.getString("COLUMN_NAME"),full=r.getString("COLUMN_TYPE"),extra=Objects.toString(r.getString("EXTRA"),"");
                    Matcher type=Pattern.compile("(?is)^([a-z]+)(?:\\((.*)\\))?(?: unsigned)?(?: zerofill)?$").matcher(full);
                    if(!type.matches())throw new SQLException(tr("Tipo de columna no reconocido: %s",full));
                    String expression=optional(r,"GENERATION_EXPRESSION");String generated=expression.isBlank()?"":extra.toUpperCase(Locale.ROOT).contains("VIRTUAL")?"VIRTUAL":"STORED";
                    Matcher update=Pattern.compile("(?i)on update (current_timestamp(?:\\(\\d*\\))?)").matcher(extra);String onUpdate=update.find()?update.group(1):"";
                    String unsupported=extra.replaceAll("(?i)(auto_increment|default_generated|on update current_timestamp(?:\\(\\d*\\))?|virtual generated|stored generated|persistent|invisible)","").trim();
                    if(!optional(r,"SRS_ID").isBlank())unsupported+=" SRID";
                    String raw=definitions.get(name);if(raw==null)throw new SQLException(tr("No se pudo leer la definición completa de la columna %s.",name));
                    columns.add(new Column(name,type.group(1).toUpperCase(Locale.ROOT),Objects.toString(type.group(2),""),"NO".equals(r.getString("IS_NULLABLE")),full.toLowerCase(Locale.ROOT).contains("unsigned"),extra.toLowerCase(Locale.ROOT).contains("auto_increment"),full.toLowerCase(Locale.ROOT).contains("zerofill"),defaultExpression(raw,noBackslash),onUpdate,Objects.toString(r.getString("COLUMN_COMMENT"),""),generated,expression,Objects.toString(r.getString("CHARACTER_SET_NAME"),""),Objects.toString(r.getString("COLLATION_NAME"),""),extra.toUpperCase(Locale.ROOT).contains("INVISIBLE"),unsupported));
                }}
            }
            record IndexHead(String name,String kind,String method,String comment,boolean invisible,String unsupported){}
            Map<IndexHead,SortedMap<Integer,IndexColumn>> indexes=new LinkedHashMap<>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SHOW INDEX FROM "+qualified(database,table))){while(r.next()){
                String name=r.getString("Key_name"),method=r.getString("Index_type"),column=r.getString("Column_name");
                String kind=name.equals("PRIMARY")?"PRIMARY":Set.of("FULLTEXT","SPATIAL","RTREE").contains(method.toUpperCase(Locale.ROOT))?(method.equalsIgnoreCase("FULLTEXT")?"FULLTEXT":"SPATIAL"):r.getBoolean("Non_unique")?"INDEX":"UNIQUE";
                String unsupported=column==null?"expression":"";
                // Preserve exotic index clauses by refusing an incomplete rewrite of them.
                for(String part:bodyDefinitions(ddl,noBackslash))if(part.contains(quote(name))&&Pattern.compile("(?i)\\b(WITH PARSER|KEY_BLOCK_SIZE|IGNORED|ENGINE_ATTRIBUTE)\\b").matcher(part).find())unsupported="extended options";
                IndexHead head=new IndexHead(name,kind,method,optional(r,"Index_comment"),"NO".equalsIgnoreCase(optional(r,"Visible")),unsupported);
                indexes.computeIfAbsent(head,k->new TreeMap<>()).put(r.getInt("Seq_in_index"),new IndexColumn(Objects.toString(column,optional(r,"Expression")),optional(r,"Sub_part"),"D".equals(optional(r,"Collation"))?"DESC":"ASC"));
            }}
            List<Index> indexList=indexes.entrySet().stream().map(e->new Index(e.getKey().name,e.getKey().kind,new ArrayList<>(e.getValue().values()),e.getKey().method,e.getKey().comment,e.getKey().invisible,e.getKey().unsupported)).toList();
            Options options;
            try(PreparedStatement s=c.prepareStatement("SELECT ENGINE,TABLE_COLLATION,TABLE_COMMENT,AUTO_INCREMENT,ROW_FORMAT,CREATE_OPTIONS FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){
                s.setString(1,database);s.setString(2,table);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException(tr("No se encontró la tabla."));String collation=r.getString(2),settings=Objects.toString(r.getString(6),"");options=new Options(r.getString(1),collation.substring(0,collation.indexOf('_')),collation,Objects.toString(r.getString(3),""),Objects.toString(r.getString(4),""),r.getString(5).toUpperCase(Locale.ROOT),option(settings,"avg_row_length"),option(settings,"max_rows"),option(settings,"min_rows"),option(settings,"checksum"),option(settings,"delay_key_write"));}
            }
            List<String> engines=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SHOW ENGINES")){while(r.next())if(Set.of("YES","DEFAULT").contains(r.getString("Support")))engines.add(r.getString("Engine"));}
            Map<String,List<String>> collations=new LinkedHashMap<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SHOW COLLATION")){while(r.next()){String charset=r.getString("Charset");if(charset!=null&&!charset.isBlank())collations.computeIfAbsent(charset,k->new ArrayList<>()).add(r.getString("Collation"));}}
            return new Snapshot(database,table,ddl,columns,indexList,ForeignKeyDefinition.read(c,session.profile().type(),database,table),options,engines,collations,noBackslash);
        }
    }
    private static String option(String options,String key){Matcher m=Pattern.compile("(?i)(?:^|\\s)"+key+"=(\\d+)").matcher(options);return m.find()?m.group(1):"";}
    public static Map<String,String> columnDefinitions(String ddl,boolean noBackslash){
        Map<String,String> result=new LinkedHashMap<>();for(String part:bodyDefinitions(ddl,noBackslash))if(part.startsWith("`")){
            StringBuilder name=new StringBuilder();int i=1;for(;i<part.length();i++){char ch=part.charAt(i);if(ch=='`'){if(i+1<part.length()&&part.charAt(i+1)=='`'){name.append('`');i++;}else break;}else name.append(ch);}result.put(name.toString(),part.substring(i+1).trim());
        }return result;
    }
    private static List<String> bodyDefinitions(String sql,boolean noBackslash){
        List<String> parts=new ArrayList<>();int depth=0,start=-1;char quoted=0;
        for(int i=0;i<sql.length();i++){char ch=sql.charAt(i);if(quoted!=0){if(ch=='\\'&&!noBackslash&&quoted!='`'){i++;continue;}if(ch==quoted){if(i+1<sql.length()&&sql.charAt(i+1)==quoted)i++;else quoted=0;}continue;}
            if(ch=='\''||ch=='"'||ch=='`'){quoted=ch;continue;}if(ch=='('){if(depth++==0)start=i+1;}else if(ch==')'){if(--depth==0){parts.add(sql.substring(start,i).trim());return parts;}}else if(ch==','&&depth==1){parts.add(sql.substring(start,i).trim());start=i+1;}}
        throw new IllegalArgumentException(tr("No se pudo interpretar la estructura de la tabla."));
    }
    private record Word(String value,int start,int end){}
    private static List<Word> words(String sql,boolean noBackslash){
        List<Word> result=new ArrayList<>();int depth=0;char quoted=0;
        for(int i=0;i<sql.length();i++){char ch=sql.charAt(i);if(quoted!=0){if(ch=='\\'&&!noBackslash&&quoted!='`'){i++;continue;}if(ch==quoted){if(i+1<sql.length()&&sql.charAt(i+1)==quoted)i++;else quoted=0;}continue;}if(ch=='\''||ch=='"'||ch=='`'){quoted=ch;continue;}if(ch=='('){depth++;continue;}if(ch==')'){depth--;continue;}if(depth==0&&Character.isLetter(ch)){int start=i;while(i+1<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i+1))||sql.charAt(i+1)=='_'))i++;result.add(new Word(sql.substring(start,i+1).toUpperCase(Locale.ROOT),start,i+1));}}
        return result;
    }
    static String defaultExpression(String definition,boolean noBackslash){
        List<Word> words=words(definition,noBackslash);for(int i=0;i<words.size();i++)if(words.get(i).value.equals("DEFAULT")){
            int start=words.get(i).end,end=definition.length();for(int j=i+1;j<words.size();j++)if(Set.of("COMMENT","AUTO_INCREMENT","ON","COLLATE","COLUMN_FORMAT","STORAGE","INVISIBLE","VISIBLE").contains(words.get(j).value)){end=words.get(j).start;break;}return definition.substring(start,end).trim();
        }return "";
    }
    public static String literal(String value,boolean noBackslash){return "'"+(noBackslash?value:value.replace("\\","\\\\")).replace("'","''")+"'";}
    private static void fragment(String value,boolean noBackslash,boolean allowComma){
        if(value.isBlank())return;char quoted=0;int depth=0;for(int i=0;i<value.length();i++){char ch=value.charAt(i);if(quoted!=0){if(ch=='\\'&&!noBackslash&&quoted!='`'){i++;continue;}if(ch==quoted){if(i+1<value.length()&&value.charAt(i+1)==quoted)i++;else quoted=0;}continue;}if(ch=='\''||ch=='"'||ch=='`'){quoted=ch;continue;}if(ch=='(')depth++;if(ch==')'&&--depth<0)throw new IllegalArgumentException(tr("Expresión SQL no válida."));if(ch==';'||ch==','&&depth==0&&!allowComma||ch=='#'||ch=='/'&&i+1<value.length()&&value.charAt(i+1)=='*'||ch=='-'&&i+1<value.length()&&value.charAt(i+1)=='-')throw new IllegalArgumentException(tr("La expresión no puede contener otra sentencia ni comentarios."));}if(quoted!=0||depth!=0)throw new IllegalArgumentException(tr("Expresión SQL no válida."));
    }
    public static String columnSql(Column c,boolean noBackslash){
        identifier(c.name);if(!c.unsupported.isBlank())throw new IllegalArgumentException(tr("Esta columna usa atributos especiales. Modifícala desde SQL para conservarlos: %s",c.name));
        if(!Set.of("TINYINT","SMALLINT","MEDIUMINT","INT","INTEGER","BIGINT","DECIMAL","NUMERIC","FLOAT","DOUBLE","REAL","BIT","BOOL","BOOLEAN","CHAR","VARCHAR","BINARY","VARBINARY","TINYTEXT","TEXT","MEDIUMTEXT","LONGTEXT","TINYBLOB","BLOB","MEDIUMBLOB","LONGBLOB","DATE","TIME","DATETIME","TIMESTAMP","YEAR","JSON","ENUM","SET","GEOMETRY","POINT","LINESTRING","POLYGON","MULTIPOINT","MULTILINESTRING","MULTIPOLYGON","GEOMETRYCOLLECTION","UUID","INET4","INET6","VECTOR").contains(c.type.toUpperCase(Locale.ROOT)))throw new IllegalArgumentException(tr("Tipo SQL no válido."));
        fragment(c.length,noBackslash,true);fragment(c.defaultSql,noBackslash,false);fragment(c.expression,noBackslash,false);
        StringBuilder sql=new StringBuilder(quote(c.name)).append(' ').append(c.type.toUpperCase(Locale.ROOT));if(!c.length.isBlank())sql.append('(').append(c.length).append(')');if(c.unsigned)sql.append(" UNSIGNED");if(c.zerofill)sql.append(" ZEROFILL");
        if(!c.charset.isBlank())sql.append(" CHARACTER SET ").append(token(c.charset));if(!c.collation.isBlank())sql.append(" COLLATE ").append(token(c.collation));
        if(!c.generated.isBlank()){if(!Set.of("VIRTUAL","STORED").contains(c.generated)||c.expression.isBlank())throw new IllegalArgumentException(tr("Revisa la expresión de la columna generada."));sql.append(" GENERATED ALWAYS AS (").append(c.expression).append(") ").append(c.generated);}
        sql.append(c.notNull?" NOT NULL":" NULL");if(!c.defaultSql.isBlank())sql.append(" DEFAULT ").append(c.defaultSql);
        if(c.autoIncrement)sql.append(" AUTO_INCREMENT");if(!c.onUpdate.isBlank()){if(!c.onUpdate.matches("(?i)CURRENT_TIMESTAMP(?:\\(\\d*\\))?"))throw new IllegalArgumentException(tr("ON UPDATE debe usar CURRENT_TIMESTAMP."));sql.append(" ON UPDATE ").append(c.onUpdate);}
        if(!c.comment.isBlank())sql.append(" COMMENT ").append(literal(c.comment,noBackslash));if(c.invisible)sql.append(" INVISIBLE");return sql.toString();
    }
    private static String token(String value){if(!value.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException(tr("Opción de estructura no válida."));return value;}
    private static void identifier(String value){if(value.isBlank()||value.codePointCount(0,value.length())>64)throw new IllegalArgumentException(tr("Los nombres deben tener entre 1 y 64 caracteres."));}
    public static String indexSql(Index index,boolean noBackslash){
        identifier(index.name);if(!index.unsupported.isBlank())throw new IllegalArgumentException(tr("Este índice usa opciones especiales. Modifícalo desde SQL para conservarlas: %s",index.name));
        if(!Set.of("PRIMARY","INDEX","UNIQUE","FULLTEXT","SPATIAL").contains(index.kind)||index.columns.isEmpty())throw new IllegalArgumentException(tr("Selecciona el tipo y las columnas del índice."));
        List<String> fields=new ArrayList<>();Set<String> seen=new HashSet<>();for(IndexColumn part:index.columns){identifier(part.name);if(!seen.add(part.name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException(tr("No repitas columnas en el índice."));if(!part.prefix.matches("[0-9]*")||!part.prefix.isBlank()&&Long.parseLong(part.prefix)==0||!Set.of("ASC","DESC").contains(part.order))throw new IllegalArgumentException(tr("Revisa la longitud y el orden del índice."));fields.add(quote(part.name)+(part.prefix.isBlank()?"":"("+part.prefix+")")+" "+part.order);}
        String sql=(index.kind.equals("PRIMARY")?"PRIMARY KEY":(index.kind.equals("INDEX")?"":index.kind+" ")+"INDEX "+quote(index.name))+" ("+String.join(", ",fields)+")";
        if(Set.of("BTREE","HASH").contains(index.method)&&!Set.of("FULLTEXT","SPATIAL").contains(index.kind))sql+=" USING "+index.method;
        if(!index.comment.isBlank())sql+=" COMMENT "+literal(index.comment,noBackslash);if(index.invisible)sql+=" INVISIBLE";return sql;
    }
    public static String plan(Snapshot base,List<ColumnChange> columns,List<IndexChange> indexes,List<KeyChange> keys,Options options){
        if(columns.isEmpty())throw new IllegalArgumentException(tr("La tabla debe conservar al menos una columna."));
        List<String> changes=new ArrayList<>();Set<String> names=new HashSet<>();for(var column:columns)if(!names.add(column.value.name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException(tr("Hay nombres de columna repetidos."));
        Set<String> oldKeys=new HashSet<>();for(var row:keys)if(row.original!=null)oldKeys.add(row.original);
        for(var key:base.keys)if(!oldKeys.contains(key.name())||keys.stream().anyMatch(k->Objects.equals(k.original,key.name())&&!k.value.equals(key)))changes.add("DROP FOREIGN KEY "+quote(key.name()));
        Set<String> oldIndexes=new HashSet<>();for(var row:indexes)if(row.original!=null)oldIndexes.add(row.original);
        for(var index:base.indexes)if(!oldIndexes.contains(index.name)||indexes.stream().anyMatch(k->Objects.equals(k.original,index.name)&&!k.value.equals(index)))changes.add(index.name.equals("PRIMARY")?"DROP PRIMARY KEY":"DROP INDEX "+quote(index.name));
        Set<String> originals=new HashSet<>();for(var row:columns)if(row.original!=null)originals.add(row.original);
        for(var column:base.columns)if(!originals.contains(column.name))changes.add("DROP COLUMN "+quote(column.name));
        for(int i=0;i<columns.size();i++){
            var row=columns.get(i);Column before=base.columns.stream().filter(c->c.name.equals(row.original)).findFirst().orElse(null);
            String previous=i==0?null:columns.get(i-1).value.name;
            List<String> surviving=base.columns.stream().map(Column::name).filter(originals::contains).toList();int oldPosition=before==null?-1:surviving.indexOf(before.name);String oldPrevious=oldPosition<=0?null:surviving.get(oldPosition-1);
            String previousOriginal=null;for(int j=i-1;j>=0;j--)if(columns.get(j).original!=null){previousOriginal=columns.get(j).original;break;}
            boolean moved=!Objects.equals(previousOriginal,oldPrevious);
            if(before==null||!before.equals(row.value)||moved)changes.add((before==null?"ADD COLUMN ":"CHANGE COLUMN "+quote(row.original)+" ")+columnSql(row.value,base.noBackslashEscapes)+(i==0?" FIRST":" AFTER "+quote(previous)));
        }
        names.clear();int primary=0;for(var row:indexes){if(!names.add(row.value.name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException(tr("Hay nombres de índice repetidos."));if(row.value.kind.equals("PRIMARY")&&++primary>1)throw new IllegalArgumentException(tr("Solo puede existir una clave primaria."));Index original=base.indexes.stream().filter(v->v.name.equals(row.original)).findFirst().orElse(null);if(!row.value.equals(original))changes.add("ADD "+indexSql(row.value,base.noBackslashEscapes));}
        names.clear();for(var row:keys){if(!names.add(row.value.name().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException(tr("Hay nombres de relación repetidos."));var original=base.keys.stream().filter(v->v.name().equals(row.original)).findFirst().orElse(null);if(!row.value.equals(original)){String add=row.value.createSql("`");changes.add(add.substring(add.indexOf("\n")+1).stripTrailing().replaceFirst(";$",""));}}
        optionChanges(base.options,options,base.noBackslashEscapes,changes);
        return changes.isEmpty()?"":"ALTER TABLE "+qualified(base.database,base.table)+"\n    "+String.join(",\n    ",changes)+";\n";
    }
    private static void optionChanges(Options old,Options desired,boolean noBackslash,List<String> changes){
        if(!old.engine.equals(desired.engine))changes.add("ENGINE="+token(desired.engine));
        if(!old.charset.equals(desired.charset))changes.add("DEFAULT CHARACTER SET="+token(desired.charset));
        if(!old.collation.equals(desired.collation))changes.add("COLLATE="+token(desired.collation));
        if(!old.comment.equals(desired.comment))changes.add("COMMENT="+literal(desired.comment,noBackslash));
        if(!old.rowFormat.equals(desired.rowFormat))changes.add("ROW_FORMAT="+token(desired.rowFormat));
        String[] names={"AUTO_INCREMENT","AVG_ROW_LENGTH","MAX_ROWS","MIN_ROWS","CHECKSUM","DELAY_KEY_WRITE"};String[] a={old.autoIncrement,old.averageRowLength,old.maxRows,old.minRows,old.checksum,old.delayKeyWrite},b={desired.autoIncrement,desired.averageRowLength,desired.maxRows,desired.minRows,desired.checksum,desired.delayKeyWrite};
        for(int i=0;i<a.length;i++)if(!a[i].equals(b[i])){if(!b[i].matches("[0-9]{1,20}"))throw new IllegalArgumentException(tr("Las opciones numéricas deben ser enteros sin signo."));changes.add(names[i]+"="+b[i]);}
    }
    public static void apply(JdbcSession session,Snapshot base,String sql)throws Exception{
        synchronized(session.connection()){
            try(Statement s=session.connection().createStatement();ResultSet r=s.executeQuery("SELECT @@SESSION.sql_mode")){r.next();boolean noBackslash=Arrays.asList(r.getString(1).split(",")).contains("NO_BACKSLASH_ESCAPES");if(noBackslash!=base.noBackslashEscapes)throw new SQLException(tr("Cambió el modo SQL de la conexión. Actualiza la estructura antes de guardar."));}
            String current=ddl(session.connection(),base.database,base.table);
            if(!fingerprint(current,base.noBackslashEscapes).equals(fingerprint(base.ddl,base.noBackslashEscapes)))throw new SQLException(tr("La estructura cambió en el servidor. Actualiza antes de guardar para no sobrescribir cambios ajenos."));
            ForeignKeyDefinition.apply(session,sql);
        }
    }
    private static String fingerprint(String ddl,boolean noBackslash){
        for(Word word:words(ddl,noBackslash))if(word.value.equals("AUTO_INCREMENT")){
            Matcher counter=Pattern.compile("\\s*=\\s*[0-9]+").matcher(ddl.substring(word.end));if(counter.lookingAt())return ddl.substring(0,word.start)+ddl.substring(word.end+counter.end());
        }return ddl;
    }
}
