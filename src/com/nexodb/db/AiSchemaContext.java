package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.sql.*;
import java.util.*;

/** Reads only metadata for SQL sources. Never executes the supplied SQL or reads table rows. */
public final class AiSchemaContext {
    public record Snapshot(String text,int tables,int columns,int indexes,List<String> warnings) {
        public Snapshot { warnings=List.copyOf(warnings); }
        public String summary(){return tables+(tables==1?" tabla":" tablas")+" · "+columns+(columns==1?" columna":" columnas")+" · "+indexes+(indexes==1?" índice":" índices")+" · "+warnings.size()+(warnings.size()==1?" aviso":" avisos");}
    }
    private record Source(List<QueryEditSupport.Token> parts) {}
    private record Site(String catalog,String schema,String table) {}
    private static final int MAX_TABLES=20,MAX_COLUMNS=300,MAX_INDEX_ROWS=500,MAX_TEXT=100_000;
    private AiSchemaContext() {}

    public static String header(JdbcSession session,String database) {
        return "Motor: "+(session==null?"no especificado":session.profile().type().displayName())+"\nBase seleccionada: "+Objects.toString(database,"")+"\n";
    }
    public static Snapshot read(JdbcSession session,String database,String sql)throws SQLException,InterruptedException {
        StringBuilder text=new StringBuilder(header(session,database));List<String> warnings=new ArrayList<>();
        int tables=0,columns=0,indexes=0;
        if(session==null||!session.isOpen())return new Snapshot(text+"Sin conexión activa. Incorpora manualmente el esquema antes de optimizar.",0,0,0,List.of("Sin conexión activa."));
        var sources=sources(sql);
        if(sources.isEmpty())warnings.add("No se identificaron tablas físicas en el SQL. Para procedimientos o SQL dinámico, incorpora su esquema manualmente.");
        synchronized(session.connection()) {
            var connection=session.connection();var meta=connection.getMetaData();
            for(int i=0;i<sources.size();i++) {
                check();if(i>=MAX_TABLES||text.length()>=MAX_TEXT){warnings.add("Contexto limitado a 20 tablas y 100.000 caracteres. Selecciona una consulta más pequeña.");break;}
                Site site=resolve(sources.get(i),session.profile().type(),database,connection,meta);
                String name=qualified(site);text.append("\nTabla / vista: ").append(name).append('\n');
                int found=0;String escape=meta.getSearchStringEscape();String resolvedNamespace=null;boolean ambiguous=false;
                try(ResultSet rows=meta.getColumns(site.catalog,pattern(site.schema,escape),pattern(site.table,escape),"%")) {
                    SortedMap<Integer,String> definitions=new TreeMap<>();
                    while(rows.next()) {
                        String namespace=Objects.toString(optional(rows,"TABLE_CAT"),"")+"."+Objects.toString(optional(rows,"TABLE_SCHEM"),"");
                        if(resolvedNamespace==null)resolvedNamespace=namespace;
                        else if(!resolvedNamespace.equals(namespace)){ambiguous=true;definitions.clear();found=0;break;}
                        check();if(++found>MAX_COLUMNS){warnings.add(name+": columnas limitadas a "+MAX_COLUMNS+".");break;}
                        int position=rows.getInt("ORDINAL_POSITION");
                        String type=rows.getString("TYPE_NAME");
                        int size=rows.getInt("COLUMN_SIZE"),scale=rows.getInt("DECIMAL_DIGITS");
                        if(type!=null&&(type.toUpperCase(Locale.ROOT).contains("CHAR")||type.equalsIgnoreCase("DECIMAL")||type.equalsIgnoreCase("NUMERIC"))&&size>0)type+="("+size+(scale>0?","+scale:"")+")";
                        definitions.put(position>0?position:found,"  "+rows.getString("COLUMN_NAME")+" "+type+(rows.getInt("NULLABLE")==DatabaseMetaData.columnNoNulls?" NOT NULL":" NULL"));
                    }
                    for(String definition:definitions.values())text.append(definition).append('\n');columns+=definitions.size();
                }catch(SQLException e){found=0;warnings.add(name+": no se pudieron leer columnas (SQLState "+state(e)+").");}
                if(ambiguous){warnings.add(name+": nombre ambiguo en varios esquemas; califica la tabla para obtener metadatos correctos.");continue;}
                if(found==0){warnings.add(name+": sin columnas visibles; verifica nombre, esquema y permisos. Puede ser una CTE o una función.");continue;}
                tables++;
                try(ResultSet rows=meta.getPrimaryKeys(site.catalog,site.schema,site.table)) {
                    SortedMap<Integer,String> keys=new TreeMap<>();while(rows.next()){check();keys.put(rows.getInt("KEY_SEQ"),rows.getString("COLUMN_NAME"));}
                    text.append("  PRIMARY KEY: ").append(keys.isEmpty()?"no reportada":String.join(", ",keys.values())).append('\n');
                }catch(SQLException e){warnings.add(name+": clave primaria no disponible (SQLState "+state(e)+").");}
                try(ResultSet rows=meta.getIndexInfo(site.catalog,site.schema,site.table,false,true)) {
                    Map<String,SortedMap<Integer,String>> keys=new LinkedHashMap<>();Map<String,Boolean> unique=new HashMap<>();int count=0;
                    while(rows.next()){check();if(++count>MAX_INDEX_ROWS){warnings.add(name+": índices limitados a "+MAX_INDEX_ROWS+" columnas de índice.");break;}
                        if(rows.getShort("TYPE")==DatabaseMetaData.tableIndexStatistic)continue;
                        String index=rows.getString("INDEX_NAME"),column=rows.getString("COLUMN_NAME");if(index==null)continue;
                        String direction=rows.getString("ASC_OR_DESC");keys.computeIfAbsent(index,k->new TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"),Objects.toString(column,"(expresión no reportada)")+("D".equals(direction)?" DESC":"A".equals(direction)?" ASC":""));unique.put(index,!rows.getBoolean("NON_UNIQUE"));
                    }
                    text.append("  Índices reportados por JDBC:\n");
                    if(keys.isEmpty())text.append("    Ninguno reportado; esto no demuestra que no existan.\n");
                    for(var entry:keys.entrySet())text.append("    ").append(entry.getKey()).append(unique.get(entry.getKey())?" UNIQUE":"").append(" (").append(String.join(", ",entry.getValue().values())).append(")\n");indexes+=keys.size();
                }catch(SQLException e){warnings.add(name+": índices no disponibles (SQLState "+state(e)+").");}
                try(ResultSet rows=meta.getImportedKeys(site.catalog,site.schema,site.table)) {
                    int count=0;while(rows.next()){check();if(++count>MAX_INDEX_ROWS){warnings.add(name+": relaciones limitadas.");break;}
                        text.append("  FOREIGN KEY ").append(Objects.toString(rows.getString("FK_NAME"),""))
                            .append(": ").append(rows.getString("FKCOLUMN_NAME")).append(" -> ")
                            .append(Objects.toString(rows.getString("PKTABLE_SCHEM"),"")).append('.').append(rows.getString("PKTABLE_NAME"))
                            .append('(').append(rows.getString("PKCOLUMN_NAME")).append(")\n");
                    }
                }catch(SQLException e){warnings.add(name+": relaciones no disponibles (SQLState "+state(e)+").");}
            }
        }
        if(text.length()>MAX_TEXT){text.setLength(MAX_TEXT);warnings.add("El texto del esquema fue truncado a 100.000 caracteres.");}
        if(!warnings.isEmpty())text.append("\nAvisos de captura de metadatos:\n- ").append(String.join("\n- ",warnings)).append('\n');
        return new Snapshot(text.toString(),tables,columns,indexes,warnings);
    }
    private static Site resolve(Source source,DatabaseType type,String database,Connection connection,DatabaseMetaData meta)throws SQLException {
        List<String> parts=new ArrayList<>();for(var token:source.parts){String name=token.value();if(!token.identifier())name=meta.storesUpperCaseIdentifiers()?name.toUpperCase(Locale.ROOT):meta.storesLowerCaseIdentifiers()?name.toLowerCase(Locale.ROOT):name;parts.add(name);}
        String table=parts.get(parts.size()-1),catalog=connection.getCatalog(),schema=null;
        if(type==DatabaseType.MYSQL||type==DatabaseType.MARIADB)catalog=parts.size()>1?parts.get(parts.size()-2):database.isBlank()?catalog:database;
        else if(type!=DatabaseType.SQLITE) {
            try{schema=connection.getSchema();}catch(SQLFeatureNotSupportedException|AbstractMethodError ignored){}
            if(parts.size()>1)schema=parts.get(parts.size()-2);
            else if(type!=DatabaseType.POSTGRESQL&&type!=DatabaseType.SQLSERVER&&!database.isBlank())schema=database;
            if(parts.size()>2)catalog=parts.get(parts.size()-3);
            else if(type==DatabaseType.SQLSERVER&&!database.isBlank())catalog=database;
        }
        return new Site(catalog,schema,table);
    }
    private static List<Source> sources(String sql) {
        var tokens=QueryEditSupport.tokenize(sql);Map<String,Source> sources=new LinkedHashMap<>();Set<Integer> fromDepth=new HashSet<>();Set<String> ctes=new HashSet<>();int depth=0;
        for(int i=0;i<tokens.size();i++) {
            var token=tokens.get(i);if(token.keyword("WITH"))ctes.addAll(ctes(tokens,i));if(token.value().equals("(")){depth++;continue;}if(token.value().equals(")")){fromDepth.remove(depth--);continue;}if(token.value().equals(";")){fromDepth.clear();ctes.clear();depth=0;continue;}
            if(Set.of("WHERE","ON","USING","GROUP","ORDER","HAVING","LIMIT","UNION","SET","VALUES").stream().anyMatch(token::keyword))fromDepth.remove(depth);
            boolean from=token.keyword("FROM")||token.keyword("JOIN");
            if(!from&&!token.keyword("UPDATE")&&!token.keyword("INTO")&&!(fromDepth.contains(depth)&&token.value().equals(",")))continue;
            if(from)fromDepth.add(depth);
            int next=i+1;if(next>=tokens.size()||!tokens.get(next).name()||tokens.get(next).keyword("SELECT"))continue;
            var parts=new ArrayList<QueryEditSupport.Token>();parts.add(tokens.get(next++));
            while(next+1<tokens.size()&&tokens.get(next).value().equals(".")&&tokens.get(next+1).name()){parts.add(tokens.get(next+1));next+=2;}
            if(next<tokens.size()&&tokens.get(next).value().equals("("))continue;
            String key=parts.stream().map(AiSchemaContext::key).reduce((a,b)->a+"."+b).orElse("");if(parts.size()==1&&ctes.contains(key))continue;sources.putIfAbsent(key,new Source(List.copyOf(parts)));
        }
        return List.copyOf(sources.values());
    }
    private static Set<String> ctes(List<QueryEditSupport.Token> tokens,int start){
        Set<String> names=new HashSet<>();int next=start+1;if(next<tokens.size()&&tokens.get(next).keyword("RECURSIVE"))next++;
        while(next<tokens.size()&&tokens.get(next).name()){
            var name=tokens.get(next++);if(next<tokens.size()&&tokens.get(next).value().equals("("))next=afterGroup(tokens,next);
            if(next+1>=tokens.size()||!tokens.get(next).keyword("AS")||!tokens.get(next+1).value().equals("("))break;
            names.add(key(name));next=afterGroup(tokens,next+1);if(next>=tokens.size()||!tokens.get(next).value().equals(","))break;next++;
        }return names;
    }
    private static int afterGroup(List<QueryEditSupport.Token> tokens,int start){int depth=0;for(int i=start;i<tokens.size();i++){if(tokens.get(i).value().equals("("))depth++;else if(tokens.get(i).value().equals(")")&&--depth==0)return i+1;}return tokens.size();}
    private static String key(QueryEditSupport.Token token){return token.identifier()?"\""+token.value()+"\"":token.value().toLowerCase(Locale.ROOT);}
    private static String optional(ResultSet rows,String column){try{return rows.getString(column);}catch(SQLException unsupported){return null;}}
    private static String pattern(String text,String escape){if(text==null||escape==null||escape.isEmpty())return text;return text.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");}
    private static String qualified(Site s){List<String> parts=new ArrayList<>();if(s.catalog!=null&&!s.catalog.isBlank())parts.add(s.catalog);if(s.schema!=null&&!s.schema.isBlank())parts.add(s.schema);parts.add(s.table);return String.join(".",parts);}
    private static String state(SQLException e){return Objects.toString(e.getSQLState(),"no reportado")+", código "+e.getErrorCode();}
    private static void check()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Lectura de contexto cancelada.");}
}
