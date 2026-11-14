package com.nexodb.db;

import java.sql.*;
import java.util.*;
import java.util.regex.*;
import com.nexodb.model.DatabaseType;

/** JOIN targets and ON expressions are derived from actual ordered FK metadata, never guessed names. */
public final class SqlJoinCompletion {
    public record Suggestion(String label,String insertion){}
    private record Site(String catalog,String schema,String table,String alias){}
    private record Pair(String child,String parent){}
    private record Edge(String name,Site child,Site parent,List<Pair> columns){}
    private record Cached(long time,List<Edge> edges){}
    private static final Pattern POSITION=Pattern.compile("(?is)\\bJOIN\\s+([\\p{L}\\p{N}_$`\".\\[\\]-]*)$");
    private static final Set<String> CLAUSES=Set.of("ON","WHERE","JOIN","LEFT","RIGHT","INNER","OUTER","FULL","CROSS","ORDER","GROUP","HAVING","LIMIT","OFFSET","UNION","SET","VALUES");
    private final JdbcSession session;private final Map<String,Cached> cache=new java.util.concurrent.ConcurrentHashMap<>();
    public SqlJoinCompletion(JdbcSession session){this.session=session;}
    public void clear(){cache.clear();}
    public static boolean atJoin(String prefix){return POSITION.matcher(prefix).find();}
    public List<Suggestion> suggestions(String sql,int caret,String database)throws SQLException{
        String prefix=sql.substring(0,Math.max(0,Math.min(caret,sql.length())));Matcher position=POSITION.matcher(prefix);if(!position.find()||!SqlColumnCompletion.inCode(prefix))return List.of();String typed=position.group(1).replace("`","").replace("\"","").replace("[","").replace("]","").toLowerCase(Locale.ROOT);
        synchronized(session.connection()){
            DatabaseMetaData metadata=session.connection().getMetaData();String quote=metadata.getIdentifierQuoteString();boolean catalogs=SchemaMetadataReader.catalogNamespace(session.profile().type());
            String before=prefix.substring(0,position.start());var tokens=QueryEditSupport.tokenize(before);int first=0;for(int i=0;i<tokens.size();i++)if(tokens.get(i).value().equals(";"))first=i+1;
            List<Site> sites=new ArrayList<>();boolean from=false;for(int i=first;i<tokens.size()&&sites.size()<8;i++){
                var token=tokens.get(i);if(token.keyword("WHERE")||token.keyword("GROUP")||token.keyword("ORDER"))from=false;
                if(token.keyword("FROM")||token.keyword("JOIN")||from&&token.value().equals(",")){
                    from=true;List<String> parts=new ArrayList<>();int j=i+1;if(j>=tokens.size()||!tokens.get(j).name())continue;parts.add(normalize(tokens.get(j++),metadata));while(j+1<tokens.size()&&tokens.get(j).value().equals(".")&&tokens.get(j+1).name()){parts.add(normalize(tokens.get(j+1),metadata));j+=2;}
                    String table=parts.get(parts.size()-1),catalog=catalogs?(parts.size()>1?parts.get(parts.size()-2):database):null,schema=catalogs?null:parts.size()>1?parts.get(parts.size()-2):session.connection().getSchema();
                    if(session.profile().type()==DatabaseType.SQLSERVER){catalog=parts.size()>2?parts.get(parts.size()-3):session.connection().getCatalog();schema=parts.size()>1?parts.get(parts.size()-2):session.connection().getSchema();}
                    if(j<tokens.size()&&tokens.get(j).keyword("AS"))j++;String alias=j<tokens.size()&&tokens.get(j).name()&&!CLAUSES.contains(tokens.get(j).value().toUpperCase(Locale.ROOT))?normalize(tokens.get(j),metadata):table;sites.add(new Site(catalog,schema,table,alias));i=j-1;
                }
            }
            Set<String> used=new HashSet<>();sites.forEach(s->used.add(s.alias.toLowerCase(Locale.ROOT)));String alias="j1";for(int i=2;used.contains(alias.toLowerCase(Locale.ROOT));i++)alias="j"+i;
            LinkedHashMap<String,Suggestion> result=new LinkedHashMap<>();for(Site site:sites)for(Edge edge:edges(site)){
                boolean child=same(site,edge.child);Site target=child?edge.parent:edge.child;if(!target.table.toLowerCase(Locale.ROOT).startsWith(typed)&&!qualified(target,quote).replace("`","").replace("\"","").toLowerCase(Locale.ROOT).startsWith(typed))continue;
                String targetAlias=alias;List<String> on=new ArrayList<>();for(Pair pair:edge.columns){String a=VisualQuery.quote(child?site.alias:targetAlias,quote)+"."+VisualQuery.quote(pair.child,quote),b=VisualQuery.quote(child?targetAlias:site.alias,quote)+"."+VisualQuery.quote(pair.parent,quote);on.add(a+" = "+b);}
                String insertion=qualified(target,quote)+" AS "+VisualQuery.quote(alias,quote)+" ON "+String.join(" AND ",on);result.putIfAbsent(insertion,new Suggestion(target.table+"  ON "+String.join(" AND ",on)+"  ["+Objects.toString(edge.name,"FK")+"]",insertion));if(result.size()>=25)return List.copyOf(result.values());
            }return List.copyOf(result.values());
        }
    }
    private boolean same(Site a,Site b){return a.table.equalsIgnoreCase(b.table)&&Objects.toString(a.catalog,"").equalsIgnoreCase(Objects.toString(b.catalog,""))&&Objects.toString(a.schema,"").equalsIgnoreCase(Objects.toString(b.schema,""));}
    private static String normalize(QueryEditSupport.Token token,DatabaseMetaData metadata)throws SQLException{if(token.identifier())return token.value();return metadata.storesUpperCaseIdentifiers()?token.value().toUpperCase(Locale.ROOT):metadata.storesLowerCaseIdentifiers()?token.value().toLowerCase(Locale.ROOT):token.value();}
    private String qualified(Site site,String quote){List<String> parts=new ArrayList<>();if(site.catalog!=null&&!site.catalog.isBlank()&&(SchemaMetadataReader.catalogNamespace(session.profile().type())))parts.add(VisualQuery.quote(site.catalog,quote));if(site.schema!=null&&!site.schema.isBlank())parts.add(VisualQuery.quote(site.schema,quote));parts.add(VisualQuery.quote(site.table,quote));return String.join(".",parts);}
    private List<Edge> edges(Site site)throws SQLException{
        String key=site.catalog+"\0"+site.schema+"\0"+site.table;Cached found=cache.get(key);if(found!=null&&System.currentTimeMillis()-found.time<60000)return found.edges;List<Edge> edges=new ArrayList<>();DatabaseMetaData metadata=session.connection().getMetaData();try(ResultSet rows=metadata.getImportedKeys(site.catalog,site.schema,site.table)){edges.addAll(read(rows));}try(ResultSet rows=metadata.getExportedKeys(site.catalog,site.schema,site.table)){edges.addAll(read(rows));}if(cache.size()>=64)cache.clear();cache.put(key,new Cached(System.currentTimeMillis(),List.copyOf(edges)));return edges;
    }
    private List<Edge> read(ResultSet rows)throws SQLException{
        record Header(String name,Site child,Site parent){}Map<Header,SortedMap<Integer,Pair>> grouped=new LinkedHashMap<>();while(rows.next()){
            boolean catalog=SchemaMetadataReader.catalogNamespace(session.profile().type());Site child=new Site(catalog?rows.getString("FKTABLE_CAT"):null,rows.getString("FKTABLE_SCHEM"),rows.getString("FKTABLE_NAME"),""),parent=new Site(catalog?rows.getString("PKTABLE_CAT"):null,rows.getString("PKTABLE_SCHEM"),rows.getString("PKTABLE_NAME"),"");Header h=new Header(rows.getString("FK_NAME"),child,parent);grouped.computeIfAbsent(h,k->new TreeMap<>()).put(rows.getInt("KEY_SEQ"),new Pair(rows.getString("FKCOLUMN_NAME"),rows.getString("PKCOLUMN_NAME")));
        }return grouped.entrySet().stream().map(e->new Edge(e.getKey().name,e.getKey().child,e.getKey().parent,new ArrayList<>(e.getValue().values()))).toList();
    }
}
