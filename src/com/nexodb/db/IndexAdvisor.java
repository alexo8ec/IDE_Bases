package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.util.*;

/** Builds reviewable index candidates only when the SQL and the plan identify the same table and columns. */
final class IndexAdvisor {
    private static final Set<String> CLAUSE_END=Set.of("SELECT","FROM","JOIN","LEFT","RIGHT","FULL","INNER","CROSS","UNION","EXCEPT","INTERSECT","LIMIT","OFFSET","FETCH","FOR");
    private static final Set<String> NON_COLUMNS=Set.of("AND","OR","NOT","NULL","TRUE","FALSE","ASC","DESC","NULLS","FIRST","LAST","CASE","WHEN","THEN","ELSE","END","DISTINCT","ALL","LIKE","ILIKE","IN","IS","BETWEEN","EXISTS","AS","COLLATE");

    private IndexAdvisor() {}

    static List<String> suggest(DatabaseType type,String sql,QueryPlan.Node root) {
        if(type==DatabaseType.SQLSERVER)return List.of(); // SQL Server supplies its own MissingIndex XML.
        if(!Set.of(DatabaseType.MYSQL,DatabaseType.MARIADB,DatabaseType.POSTGRESQL,DatabaseType.SQLITE,DatabaseType.H2).contains(type))return List.of();
        var tables=tables(sql);if(tables.isEmpty())return List.of();
        var columns=columns(sql,tables);if(columns.isEmpty())return List.of();
        Set<String> scans=new LinkedHashSet<>();collectScans(root,scans);
        if(scans.isEmpty())return List.of();
        List<String> out=new ArrayList<>();Set<String> emitted=new HashSet<>();
        for(var entry:tables.entrySet()) {
            String alias=entry.getKey(),table=entry.getValue();
            if(!scans.stream().anyMatch(scan->sameTable(scan,table)))continue;
            ColumnUse use=columns.get(alias);if(use==null)use=columns.get(normalize(table));if(use==null)continue;
            List<String> selected=new ArrayList<>();selected.addAll(use.equal);selected.addAll(use.range);selected.addAll(use.order);
            selected=selected.stream().filter(IndexAdvisor::identifier).distinct().limit(4).toList();
            if(selected.isEmpty())continue;
            String key=normalize(table)+"|"+String.join(",",selected).toLowerCase(Locale.ROOT);if(!emitted.add(key))continue;
            String index="idx_"+normalize(table).replaceAll("[^A-Za-z0-9_]","_")+"_"+String.join("_",selected).replaceAll("[^A-Za-z0-9_]","_");
            if(index.length()>60)index=index.substring(0,60);
            String reason=use.equal.isEmpty()?"ordenamiento/agrupación":"filtros o relaciones";
            out.add("-- Candidato para "+type.displayName()+" basado en un escaneo detectado y en "+reason+" del SQL.\n"
                +"-- Revísalo contra los índices existentes y compara el plan antes y después; no se aplica automáticamente.\n"
                +"CREATE INDEX "+quote(type,index)+" ON "+qualified(type,table)+" ("+selected.stream().map(c->quote(type,c)).collect(java.util.stream.Collectors.joining(", "))+");");
        }
        return List.copyOf(out);
    }

    static String emptyMessage(DatabaseType type,String sql) {
        List<SqlScriptTools.Token> tokens=SqlScriptTools.tokens(sql);
        boolean predicate=tokens.stream().anyMatch(t->t.is("WHERE")||t.is("JOIN")||t.is("HAVING")||t.is("ORDER")||t.is("GROUP"));
        if(!predicate)return "No se recomienda crear un índice para esta consulta: no contiene filtros, relaciones, agrupación ni ordenamiento que permitan deducir columnas útiles.";
        if(type==DatabaseType.SQLSERVER)return "SQL Server no incluyó una recomendación de índice faltante en este plan. Esto no significa que todos los índices existentes sean óptimos.";
        return "El plan de "+type.displayName()+" no aportó evidencia suficiente para proponer un índice seguro. Revisa el diagnóstico, las filas leídas y los índices existentes.";
    }

    private static Map<String,String> tables(String sql) {
        List<SqlScriptTools.Token> t=SqlScriptTools.tokens(sql);Map<String,String> result=new LinkedHashMap<>();
        for(int i=0;i<t.size();i++)if(t.get(i).is("FROM")||t.get(i).is("JOIN")) {
            int n=i+1;if(n>=t.size()||t.get(n).is("("))continue;
            List<String> path=new ArrayList<>();if(t.get(n).name())path.add(t.get(n++).text());else continue;
            while(n+1<t.size()&&t.get(n).is(".")&&t.get(n+1).name()){path.add(t.get(n+1).text());n+=2;}
            String table=String.join(".",path),alias=path.get(path.size()-1);
            if(n<t.size()&&t.get(n).is("AS"))n++;
            if(n<t.size()&&t.get(n).name()&&!reserved(t.get(n).text()))alias=t.get(n).text();
            result.putIfAbsent(normalize(alias),table);result.putIfAbsent(normalize(path.get(path.size()-1)),table);
        }
        return result;
    }

    private static Map<String,ColumnUse> columns(String sql,Map<String,String> tables) {
        List<SqlScriptTools.Token> t=SqlScriptTools.tokens(sql);Map<String,ColumnUse> out=new LinkedHashMap<>();String clause="";
        Set<String> uniqueTables=new LinkedHashSet<>(tables.values());String onlyAlias=uniqueTables.size()==1?tables.entrySet().stream().filter(e->e.getKey().equals(normalize(last(e.getValue())))).map(Map.Entry::getKey).findFirst().orElse(tables.keySet().iterator().next()):null;
        for(int i=0;i<t.size();i++) {
            var token=t.get(i);
            if(token.is("WHERE")||token.is("ON")||token.is("HAVING")){clause="filter";continue;}
            if((token.is("GROUP")||token.is("ORDER"))&&i+1<t.size()&&t.get(i+1).is("BY")){clause="order";i++;continue;}
            if(CLAUSE_END.contains(token.text().toUpperCase(Locale.ROOT))){clause="";continue;}
            if(clause.isEmpty()||!token.name()||!identifier(token.text())||NON_COLUMNS.contains(token.text().toUpperCase(Locale.ROOT)))continue;
            String alias,column;
            if(i+2<t.size()&&t.get(i+1).is(".")&&t.get(i+2).name()) {alias=normalize(token.text());column=t.get(i+2).text();i+=2;}
            else {
                if(onlyAlias==null||i+1<t.size()&&t.get(i+1).is("("))continue;
                boolean comparison=i+1<t.size()&&(Set.of("=","<",">","!").contains(t.get(i+1).text())||t.get(i+1).is("IN")||t.get(i+1).is("LIKE")||t.get(i+1).is("BETWEEN")||t.get(i+1).is("IS"));
                if(clause.equals("filter")&&!comparison)continue;alias=onlyAlias;column=token.text();
            }
            if(!tables.containsKey(alias))continue;ColumnUse use=out.computeIfAbsent(alias,k->new ColumnUse());
            if(clause.equals("order"))use.order.add(column);else if(equalityNear(t,i))use.equal.add(column);else use.range.add(column);
        }
        return out;
    }

    private static boolean equalityNear(List<SqlScriptTools.Token> tokens,int index) {
        for(int i=index+1;i<Math.min(tokens.size(),index+4);i++){String value=tokens.get(i).text();if(value.equals("=" )||tokens.get(i).is("IN")||tokens.get(i).is("IS"))return true;if(Set.of("<",">","!",",", "AND", "OR").contains(value.toUpperCase(Locale.ROOT)))break;}
        return false;
    }

    private static void collectScans(QueryPlan.Node node,Set<String> out) {
        String access=node.attributes().getOrDefault(PlanDiagnostics.ACCESS,"").toLowerCase(Locale.ROOT);
        boolean scan=access.equals("all")||access.equals("seq scan")||access.equals("scan")||access.equals("table scan");
        String table=node.attributes().get(PlanDiagnostics.TABLE);if(scan&&table!=null&&!table.isBlank())out.add(table);
        for(var child:node.children())collectScans(child,out);
    }

    private static boolean sameTable(String a,String b){return normalize(last(a)).equals(normalize(last(b)));}
    private static String last(String value){String[] parts=value.split("\\.");return parts[parts.length-1];}
    private static String normalize(String value){return value.replace("[","").replace("]","").replace("`","").replace("\"","").trim().toLowerCase(Locale.ROOT);}
    private static boolean identifier(String value){return value!=null&&value.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*");}
    private static boolean reserved(String value){String upper=value.toUpperCase(Locale.ROOT);return CLAUSE_END.contains(upper)||NON_COLUMNS.contains(upper)||Set.of("WHERE","ON","HAVING","GROUP","ORDER","BY").contains(upper);}
    private static String quote(DatabaseType type,String value){return switch(type){case MYSQL,MARIADB->"`"+value.replace("`","``")+"`";case SQLSERVER->"["+value.replace("]","]]" )+"]";default->"\""+value.replace("\"","\"\"")+"\"";};}
    private static String qualified(DatabaseType type,String value){return Arrays.stream(value.split("\\.")).map(IndexAdvisor::clean).map(v->quote(type,v)).collect(java.util.stream.Collectors.joining("."));}
    private static String clean(String value){return value.replaceAll("^[`\"\\[]|[`\"\\]]$","");}
    private static final class ColumnUse {final LinkedHashSet<String> equal=new LinkedHashSet<>(),range=new LinkedHashSet<>(),order=new LinkedHashSet<>();}
}
