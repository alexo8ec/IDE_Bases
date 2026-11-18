package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.sql.*;
import java.util.*;

/** Dialect-aware CREATE TABLE drafts used by the visual schema designer. */
public final class CreateTableDefinition {
    public record Column(String name,String type,boolean primaryKey,boolean nullable,boolean autoIncrement,
                         boolean unsigned,String defaultSql,String collation,boolean zerofill,boolean binary,
                         boolean updateTimestamp,String charset,String comment,String generated,String expression,
                         boolean invisible,List<String> values) { public Column { values=values==null?List.of():List.copyOf(values); } }
    public record Features(List<String> types,boolean autoIncrement,boolean unsigned,boolean collation) {
        public Features { types=List.copyOf(types); }
    }
    private CreateTableDefinition() {}

    public static Features features(DatabaseType type){
        return switch(type){
            case MYSQL,MARIADB -> new Features(List.of("INT","BIGINT","SMALLINT","TINYINT","DECIMAL(18,2)","FLOAT","DOUBLE","BOOLEAN","CHAR(1)","VARCHAR(255)","TEXT","MEDIUMTEXT","LONGTEXT","BINARY(16)","VARBINARY(255)","BLOB","MEDIUMBLOB","LONGBLOB","DATE","TIME","DATETIME","TIMESTAMP","YEAR","JSON","ENUM","SET"),true,true,true);
            case POSTGRESQL -> new Features(List.of("INTEGER","BIGINT","SMALLINT","NUMERIC(18,2)","REAL","DOUBLE PRECISION","BOOLEAN","CHAR(1)","VARCHAR(255)","TEXT","BYTEA","DATE","TIME","TIMESTAMP","TIMESTAMP WITH TIME ZONE","UUID","JSON","JSONB","INET"),true,false,true);
            case SQLSERVER -> new Features(List.of("INT","BIGINT","SMALLINT","TINYINT","DECIMAL(18,2)","FLOAT","REAL","BIT","CHAR(1)","VARCHAR(255)","NVARCHAR(255)","VARCHAR(MAX)","NVARCHAR(MAX)","BINARY(16)","VARBINARY(MAX)","DATE","TIME","DATETIME","DATETIME2","DATETIMEOFFSET","UNIQUEIDENTIFIER","XML"),true,false,true);
            case ORACLE -> new Features(List.of("NUMBER(10)","NUMBER(18)","NUMBER(18,2)","BINARY_FLOAT","BINARY_DOUBLE","CHAR(1)","VARCHAR2(255)","NVARCHAR2(255)","CLOB","NCLOB","BLOB","RAW(16)","DATE","TIMESTAMP","TIMESTAMP WITH TIME ZONE","JSON"),true,false,true);
            case SQLITE -> new Features(List.of("INTEGER","REAL","NUMERIC","TEXT","BLOB","DATE","DATETIME","BOOLEAN","VARCHAR(255)","DECIMAL(18,2)"),true,false,true);
            case H2 -> new Features(List.of("INTEGER","BIGINT","SMALLINT","TINYINT","DECIMAL(18,2)","REAL","DOUBLE PRECISION","BOOLEAN","CHAR(1)","VARCHAR(255)","CLOB","BINARY(16)","VARBINARY(255)","BLOB","DATE","TIME","TIMESTAMP","TIMESTAMP WITH TIME ZONE","UUID","JSON"),true,false,true);
            default -> new Features(List.of("INTEGER","BIGINT","DECIMAL(18,2)","BOOLEAN","VARCHAR(255)","TEXT","BLOB","DATE","TIME","TIMESTAMP"),false,false,false);
        };
    }

    public static String createSql(DatabaseType engine,String quote,String namespace,String table,List<Column> columns){
        table=Objects.toString(table,"").trim();
        if(table.isBlank())throw new IllegalArgumentException("Indica el nombre de la tabla.");
        if(columns==null||columns.isEmpty())throw new IllegalArgumentException("Agrega al menos una columna.");
        VisualQuery.quote(table,quote);
        Set<String> names=new HashSet<>();int identities=0;List<String> definitions=new ArrayList<>(),keys=new ArrayList<>();
        for(Column input:columns){
            String name=Objects.toString(input.name(),"").trim(),type=columnType(engine,input.type(),input.values());
            if(name.isBlank())throw new IllegalArgumentException("Todas las columnas deben tener nombre.");
            if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("La columna “"+name+"” está repetida.");
            if(input.autoIncrement()&&++identities>1)throw new IllegalArgumentException("Solo una columna puede ser autoincremental.");
            if(input.autoIncrement()&&!input.primaryKey())throw new IllegalArgumentException("La columna autoincremental debe formar parte de la clave primaria.");
            String defaultSql=safeDefault(input.defaultSql());String collation=safeIdentifier(input.collation(),"collation"),charset=safeIdentifier(input.charset(),"charset");
            String generated=Objects.toString(input.generated(),"").trim().toUpperCase(Locale.ROOT),expression=safeExpression(input.expression());
            boolean mysql=engine==DatabaseType.MYSQL||engine==DatabaseType.MARIADB;
            if((input.zerofill()||input.binary()||input.updateTimestamp()||!charset.isBlank()||!generated.isBlank()||input.invisible())&&!mysql)throw new IllegalArgumentException("La columna “"+name+"” usa propiedades exclusivas de MySQL/MariaDB.");
            if(!generated.isBlank()&&!Set.of("VIRTUAL","STORED").contains(generated))throw new IllegalArgumentException("El tipo de columna generada debe ser VIRTUAL o STORED.");
            if(!generated.isBlank()&&expression.isBlank())throw new IllegalArgumentException("Indica la expresión de la columna generada “"+name+"”.");
            if(!generated.isBlank()&&(input.autoIncrement()||!defaultSql.isBlank()))throw new IllegalArgumentException("Una columna generada no puede ser autoincremental ni tener valor predeterminado.");
            if(input.autoIncrement()&&!defaultSql.isBlank()&&engine!=DatabaseType.MYSQL&&engine!=DatabaseType.MARIADB)throw new IllegalArgumentException("Una columna de identidad no puede tener valor predeterminado en este motor.");
            boolean sqliteIdentity=engine==DatabaseType.SQLITE&&input.autoIncrement();
            if(sqliteIdentity&&!type.equalsIgnoreCase("INTEGER"))throw new IllegalArgumentException("SQLite requiere INTEGER para usar AUTOINCREMENT.");
            StringBuilder sql=new StringBuilder("    ").append(VisualQuery.quote(name,quote)).append(' ').append(type);
            if(input.unsigned()){
                if(!mysql)throw new IllegalArgumentException("UNSIGNED solo está disponible en MySQL y MariaDB.");
                sql.append(" UNSIGNED");
            }
            if(input.zerofill())sql.append(" ZEROFILL");
            if(!charset.isBlank())sql.append(" CHARACTER SET ").append(VisualQuery.quote(charset,quote));
            if(!collation.isBlank())sql.append(" COLLATE ").append(VisualQuery.quote(collation,quote));
            if(input.binary())sql.append(" BINARY");
            if(sqliteIdentity)sql.append(" PRIMARY KEY AUTOINCREMENT");
            else{
                if(!generated.isBlank())sql.append(" GENERATED ALWAYS AS (").append(expression).append(") ").append(generated);
                else if(input.autoIncrement())sql.append(identity(engine));
                sql.append(input.primaryKey()||!input.nullable()?" NOT NULL":" NULL");
                if(!defaultSql.isBlank())sql.append(" DEFAULT ").append(defaultSql);
                if(input.autoIncrement()&&(engine==DatabaseType.MYSQL||engine==DatabaseType.MARIADB))sql.append(" AUTO_INCREMENT");
                if(input.updateTimestamp())sql.append(" ON UPDATE CURRENT_TIMESTAMP");
                if(!Objects.toString(input.comment(),"").isBlank()){
                    if(!mysql)throw new IllegalArgumentException("Los comentarios de columna desde este formulario están disponibles para MySQL y MariaDB.");
                    sql.append(" COMMENT '").append(Objects.toString(input.comment(),"").replace("'","''")).append('\'');
                }
                if(input.invisible())sql.append(" INVISIBLE");
                if(input.primaryKey())keys.add(VisualQuery.quote(name,quote));
            }
            definitions.add(sql.toString());
        }
        if(!keys.isEmpty())definitions.add("    PRIMARY KEY ("+String.join(", ",keys)+")");
        return "CREATE TABLE "+VisualQuery.qualified(namespace,table,quote)+" (\n"+String.join(",\n",definitions)+"\n);";
    }

    private static String identity(DatabaseType type){
        return switch(type){
            case POSTGRESQL,ORACLE,H2 -> " GENERATED BY DEFAULT AS IDENTITY";
            case SQLSERVER -> " IDENTITY(1,1)";
            case MYSQL,MARIADB -> "";
            default -> throw new IllegalArgumentException("El motor seleccionado no admite autoincremento desde este diseñador.");
        };
    }
    private static String normalizeType(String value){
        String type=Objects.toString(value,"").trim().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
        if(type.isBlank())throw new IllegalArgumentException("Indica el tipo de todas las columnas.");
        if(!type.matches("[A-Z][A-Z0-9_]*(?:\\s+(?:WITH|WITHOUT)\\s+TIME\\s+ZONE|\\s+PRECISION)?(?:\\((?:[0-9]+(?:\\s*,\\s*[0-9]+)?|MAX)\\))?"))throw new IllegalArgumentException("Tipo de dato no válido: "+value);
        return type;
    }
    private static String columnType(DatabaseType engine,String requested,List<String> values){
        String raw=Objects.toString(requested,"").trim(),base=raw.toUpperCase(Locale.ROOT);
        if(!base.equals("ENUM")&&!base.equals("SET"))return normalizeType(raw);
        if(engine!=DatabaseType.MYSQL&&engine!=DatabaseType.MARIADB)throw new IllegalArgumentException(base+" solo está disponible en MySQL y MariaDB.");
        if(values==null||values.isEmpty())throw new IllegalArgumentException("Agrega al menos un valor para "+base+".");
        List<String> literals=new ArrayList<>();Set<String> unique=new HashSet<>();for(String value:values){String item=Objects.toString(value,"");if(item.chars().anyMatch(c->c=='\n'||c=='\r'||c==0))throw new IllegalArgumentException("Los valores de "+base+" no pueden contener saltos de línea.");if(!unique.add(item))throw new IllegalArgumentException("El valor “"+item+"” está repetido en "+base+".");literals.add("'"+item.replace("\\","\\\\").replace("'","''")+"'");}
        return base+"("+String.join(",",literals)+")";
    }
    private static String safeDefault(String value){
        String sql=Objects.toString(value,"").trim();
        if(sql.contains(";")||sql.contains("--")||sql.contains("/*")||sql.contains("*/")||sql.chars().anyMatch(c->c=='\n'||c=='\r'||c==0))throw new IllegalArgumentException("El valor predeterminado debe ser una sola expresión SQL.");
        return sql;
    }
    private static String safeIdentifier(String value,String label){String text=Objects.toString(value,"").trim();if(!text.isBlank()&&!text.matches("[\\p{L}_][\\p{L}\\p{N}_$]*"))throw new IllegalArgumentException("El "+label+" no es válido: "+text);return text;}
    private static String safeExpression(String value){String sql=Objects.toString(value,"").trim();if(sql.contains(";")||sql.contains("--")||sql.contains("/*")||sql.contains("*/")||sql.chars().anyMatch(c->c=='\n'||c=='\r'||c==0))throw new IllegalArgumentException("La expresión generada debe ser una sola expresión SQL.");return sql;}

    public static List<String> collations(JdbcSession session){
        String sql=switch(session.profile().type()){
            case MYSQL,MARIADB -> "SELECT COLLATION_NAME FROM information_schema.COLLATIONS ORDER BY COLLATION_NAME";
            case POSTGRESQL -> "SELECT collname FROM pg_collation ORDER BY collname";
            case SQLSERVER -> "SELECT name FROM sys.fn_helpcollations() ORDER BY name";
            case H2 -> "SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLLATIONS ORDER BY COLLATION_NAME";
            default -> "";
        };
        if(sql.isBlank())return List.of();List<String> values=new ArrayList<>();
        try{ synchronized(session.connection()){try(Statement statement=session.connection().createStatement();ResultSet rows=statement.executeQuery(sql)){while(rows.next()){String value=rows.getString(1);if(value!=null&&!value.isBlank())values.add(value);}}}}
        catch(SQLException ignored){return List.of();}
        return List.copyOf(values);
    }
}
