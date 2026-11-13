package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.sql.*;
import java.util.*;
import com.nexodb.db.QueryEditSupport.Token;

/** Bounded, short-lived column metadata cache; intended for background use only. */
public final class SqlColumnCompletion {
    private static final Set<String> CLAUSES=Set.of("WHERE","JOIN","LEFT","RIGHT","INNER","OUTER","CROSS","FULL","ON","SET","VALUES","ORDER","GROUP","HAVING","LIMIT","OFFSET","UNION","RETURNING","FETCH","FOR");
    private record Source(List<Token> parts,String alias) { String table(){return parts.get(parts.size()-1).value();} }
    private record Cached(long time,List<String> columns) {}
    private final JdbcSession session;
    private final Map<String,Cached> cache=new LinkedHashMap<>(16,0.75f,true);
    private volatile String quote="\"";
    public SqlColumnCompletion(JdbcSession session){this.session=session;}
    public synchronized void clear(){cache.clear();}
    public String identifier(String name){return quote+name.replace(quote,quote+quote)+quote;}

    public List<String> columns(String sql,String qualifier,String database,Collection<String> knownTables)throws SQLException {
        if(session==null||!session.isOpen())return List.of();
        List<Source> sources=sources(sql);
        if(!qualifier.isBlank()) {
            String last=qualifier.substring(qualifier.lastIndexOf('.')+1).replace("`","").replace("\"","").replace("[","").replace("]","");
            sources=sources.stream().filter(source->source.alias().equalsIgnoreCase(last)||source.table().equalsIgnoreCase(last)).toList();
            if(sources.isEmpty()&&knownTables.stream().anyMatch(name->name.equalsIgnoreCase(qualifier)||name.equalsIgnoreCase(last)||name.toLowerCase(Locale.ROOT).endsWith("."+last.toLowerCase(Locale.ROOT)))) {
                List<Token> tokens=QueryEditSupport.tokenize(qualifier);List<Token> parts=new ArrayList<>();
                for(Token token:tokens)if(token.name())parts.add(token);
                if(!parts.isEmpty())sources=List.of(new Source(parts,""));
            }
        }
        Set<String> result=new LinkedHashSet<>();
        for(Source source:sources)result.addAll(read(source,database));
        return List.copyOf(result);
    }
    private List<String> read(Source source,String database)throws SQLException {
        synchronized(session.connection()) {
            DatabaseMetaData metadata=session.connection().getMetaData();DatabaseType type=session.profile().type();
            String quoteString=metadata.getIdentifierQuoteString();if(quoteString!=null&&!quoteString.isBlank())quote=quoteString.trim();
            List<String> parts=new ArrayList<>();for(Token token:source.parts()) {
                String value=token.value();
                if(!token.identifier()){if(metadata.storesUpperCaseIdentifiers())value=value.toUpperCase(Locale.ROOT);else if(metadata.storesLowerCaseIdentifiers())value=value.toLowerCase(Locale.ROOT);}
                parts.add(value);
            }
            String table=parts.get(parts.size()-1),catalog=session.connection().getCatalog(),schema=null;
            if(type==DatabaseType.MYSQL||type==DatabaseType.MARIADB)catalog=parts.size()>1?parts.get(parts.size()-2):database.isBlank()?catalog:database;
            else if(type!=DatabaseType.SQLITE) {
                schema=parts.size()>1?parts.get(parts.size()-2):session.connection().getSchema();
                if(parts.size()>2)catalog=parts.get(parts.size()-3);
            }
            String key=Objects.toString(catalog,"")+"\0"+Objects.toString(schema,"")+"\0"+table;
            long now=System.nanoTime();
            synchronized(this){Cached cached=cache.get(key);if(cached!=null&&now-cached.time()<30_000_000_000L)return cached.columns();}
            String escape=metadata.getSearchStringEscape();
            SortedMap<Integer,String> names=new TreeMap<>();
            try(ResultSet columns=metadata.getColumns(catalog,pattern(schema,escape),pattern(table,escape),"%")) {
                int fallback=0;while(columns.next()){int position=columns.getInt("ORDINAL_POSITION");names.put(position>0?position:++fallback,columns.getString("COLUMN_NAME"));}
            }
            List<String> columns=List.copyOf(names.values());
            synchronized(this){cache.put(key,new Cached(now,columns));while(cache.size()>128)cache.remove(cache.keySet().iterator().next());}
            return columns;
        }
    }
    private static String pattern(String value,String escape){if(value==null||escape==null||escape.isEmpty())return value;return value.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");}
    private static List<Source> sources(String sql){
        List<Token> tokens=QueryEditSupport.tokenize(sql);List<Source> sources=new ArrayList<>();
        for(int i=0;i<tokens.size();i++)if(tokens.get(i).keyword("FROM")||tokens.get(i).keyword("JOIN")||tokens.get(i).keyword("UPDATE")||tokens.get(i).keyword("INTO")) {
            int next=i+1;if(next>=tokens.size()||!tokens.get(next).name())continue;
            List<Token> parts=new ArrayList<>();parts.add(tokens.get(next++));
            while(next+1<tokens.size()&&tokens.get(next).value().equals(".")&&tokens.get(next+1).name()){parts.add(tokens.get(next+1));next+=2;}
            if(next<tokens.size()&&tokens.get(next).keyword("AS"))next++;
            String alias="";
            if(next<tokens.size()&&tokens.get(next).name()&&(tokens.get(next).identifier()||!CLAUSES.contains(tokens.get(next).value().toUpperCase(Locale.ROOT))))alias=tokens.get(next).value();
            sources.add(new Source(List.copyOf(parts),alias));
        }
        return sources;
    }
    public static boolean inCode(String prefix){
        List<Token> tokens=QueryEditSupport.tokenize(prefix);if(tokens.isEmpty())return prefix.isBlank();
        String tail=prefix.substring(tokens.get(tokens.size()-1).end()).stripLeading();return !tail.startsWith("--")&&!tail.startsWith("#");
    }
}
