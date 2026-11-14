package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.util.*;
import java.util.regex.Pattern;

/** Normalizes engine evidence without inventing timings or claiming that a scan proves a missing index. */
public final class PlanDiagnostics {
    public static final String TABLE="Tabla", INDEX="Índice utilizado", ACCESS="Forma de acceso",
        ESTIMATED="Filas estimadas por ejecución", ROWS="Filas reales totales", READ="Filas leídas totales",
        ESTIMATED_READ="Filas por leer estimadas por ejecución", LOOPS="Ejecuciones / loops",
        TIME="Tiempo reportado (ms)", SCOPE="Alcance del tiempo", CPU="CPU total (ms)",
        COST="Costo estimado (unidades)", WARNING="Diagnóstico", LEVEL="Nivel de alerta",
        QUERY_TIME="Tiempo de ejecución de la consulta (ms)";
    private PlanDiagnostics() {}

    public static QueryPlan.Node enrich(DatabaseType type,QueryPlan.Node node,boolean actual) {
        var attrs=new LinkedHashMap<>(node.attributes());
        var children=node.children().stream().map(c->enrich(type,c,actual)).toList();
        switch(type) {
            case SQLSERVER -> sqlServer(attrs,actual);
            case POSTGRESQL -> postgres(attrs,actual);
            case MYSQL,MARIADB -> mysql(attrs,node.title(),actual);
            case SQLITE -> sqlite(attrs,node.title());
            default -> { /* Keep unsupported metrics absent. */ }
        }
        diagnose(attrs,node.title());
        if(actual&&type==DatabaseType.MYSQL&&node.title().startsWith("Filter:")) {
            Double output=value(attrs,ROWS);Double input=children.size()==1?value(children.get(0),ROWS):null;
            if(input!=null&&output!=null&&input>=10_000&&input>=100*Math.max(1,output)){
                attrs.put(READ,format(input));attrs.put(LEVEL,"Alta");attrs.put(WARNING,"Filtro poco selectivo en el acceso: "+format(input)+" filas recibidas / "+format(output)+" devueltas. Revisar un índice para el predicado; confirmar con los índices existentes y un nuevo plan.");
            }
        }
        return new QueryPlan.Node(node.title(),attrs,children);
    }

    private static void sqlServer(Map<String,String> a,boolean actual) {
        copy(a,TABLE,"Object · Table");copy(a,INDEX,"Object · Index");copy(a,ACCESS,"PhysicalOp");
        copy(a,ESTIMATED,"EstimateRows");copy(a,ESTIMATED_READ,"EstimatedRowsRead");copy(a,COST,"EstimatedTotalSubtreeCost");
        if(!actual)return;
        runtime(a,ROWS,"ActualRows",false);runtime(a,READ,"ActualRowsRead",false);
        runtime(a,LOOPS,"ActualExecutions",false);runtime(a,TIME,"ActualElapsedms",true);runtime(a,CPU,"ActualCPUms",false);
        if(a.containsKey(TIME))a.put(SCOPE,"Máximo entre hilos; puede incluir hijos. No sumar operadores.");
    }
    private static void runtime(Map<String,String> a,String target,String suffix,boolean maximum) {
        Double value=null;
        for(var entry:a.entrySet())if(entry.getKey().startsWith("Runtime hilo ")&&entry.getKey().endsWith(" · "+suffix)) {
            Double n=number(entry.getValue());if(n!=null)value=value==null?n:maximum?Math.max(value,n):value+n;
        }
        put(a,target,value);
    }
    private static void postgres(Map<String,String> a,boolean actual) {
        copy(a,TABLE,"Relation Name");copy(a,INDEX,"Index Name");copy(a,ACCESS,"Node Type");
        copy(a,ESTIMATED,"Plan Rows");copy(a,COST,"Total Cost");
        if(!actual)return;
        copy(a,QUERY_TIME,"Execution Time");
        copy(a,LOOPS,"Actual Loops");copy(a,TIME,"Actual Total Time");
        Double loops=value(a,"Actual Loops"),rows=value(a,"Actual Rows");
        put(a,ROWS,multiply(rows,loops));
        String access=a.getOrDefault(ACCESS,"");
        if(access.contains("Scan")&&rows!=null&&loops!=null)put(a,READ,(rows+orZero(value(a,"Rows Removed by Filter"))+orZero(value(a,"Rows Removed by Index Recheck")))*loops);
        if(a.containsKey(TIME))a.put(SCOPE,"Promedio por loop, incluye hijos; filas totales = promedio × loops.");
    }
    private static void mysql(Map<String,String> a,String title,boolean actual) {
        copy(a,TABLE,"table_name");copy(a,INDEX,"key");copy(a,ACCESS,"access_type");
        copy(a,ESTIMATED,"rows","rows_examined_per_scan");copy(a,ESTIMATED_READ,"rows_examined_per_scan","rows");
        copy(a,COST,"prefix_cost","query_cost");
        if(actual) {
            copy(a,LOOPS,"r_loops","Loops");
            copy(a,TIME,"r_total_time_ms","r_table_time_ms","Tiempo del iterador (ms, promedio por loop)");
            Double loops=value(a,LOOPS),scanned=value(a,"r_rows"),filtered=value(a,"r_filtered");
            if(scanned!=null){put(a,READ,multiply(scanned,loops));if(filtered!=null)put(a,ROWS,multiply(scanned*filtered/100,loops));}
            else put(a,ROWS,multiply(value(a,"Filas reales"),loops));
            if(a.containsKey(TIME))a.put(SCOPE,a.containsKey("Tiempo del iterador (ms, promedio por loop)")?"Promedio por loop, incluye hijos; no sumar operadores.":"Total reportado por el motor; puede incluir hijos.");
        }
        String detail=title.replaceFirst("\\s*\\(cost=.*$","").replaceFirst("\\s*\\(actual time=.*$","");
        var method=Pattern.compile("(?i)^(Table scan|Index scan|Covering index scan|Index range scan|Index lookup|Single-row index lookup|Covering index lookup) on (\\S+)(?: using (\\S+))?(?:\\s+.*)?$").matcher(detail);
        if(method.matches()){a.put(ACCESS,method.group(1));a.put(TABLE,method.group(2));if(method.group(3)!=null)a.put(INDEX,method.group(3));}
        var estimate=Pattern.compile("\\(cost=([0-9.eE+\\-]+)(?:\\.\\.[0-9.eE+\\-]+)? rows=([0-9.eE+\\-]+)\\)").matcher(title);
        if(estimate.find()){a.put(COST,estimate.group(1));a.put(ESTIMATED,estimate.group(2));}
        // A table scan returns every examined row. Filter output belongs to its parent iterator.
        if("Table scan".equalsIgnoreCase(a.get(ACCESS))&&a.containsKey(ROWS))a.put(READ,a.get(ROWS));
    }
    private static void sqlite(Map<String,String> a,String title) {
        var match=Pattern.compile("(?i)\\b(SCAN|SEARCH) (?:TABLE )?(\\S+)(?:.*?USING (?:COVERING )?INDEX (\\S+))?.*").matcher(title);
        if(match.find()){a.put(ACCESS,match.group(1));a.put(TABLE,match.group(2));if(match.group(3)!=null)a.put(INDEX,match.group(3));}
    }

    private static void diagnose(Map<String,String> a,String title) {
        String access=a.getOrDefault(ACCESS,"").toLowerCase(Locale.ROOT);
        boolean scan=Set.of("all","index","seq scan","parallel seq scan","table scan","clustered index scan","index scan","index only scan","parallel index scan","parallel index only scan","covering index scan","scan").contains(access);
        if(a.containsKey("Index Cond"))scan=false;
        boolean measured=a.containsKey(READ);
        Double read=value(a,measured?READ:ESTIMATED_READ);
        if(read==null&&scan){measured=a.containsKey(ROWS);read=value(a,measured?ROWS:ESTIMATED);}
        Double rows=value(a,ROWS);
        var notes=new ArrayList<String>();int level=0;
        if(scan) {
            notes.add(a.containsKey(INDEX)?"Recorre el índice completo; usar un índice no implica una búsqueda selectiva.":"Escaneo completo sin búsqueda selectiva por índice.");
            if(read!=null&&read>=100_000){level=2;notes.add("Carga alta: "+format(read)+(measured?" filas leídas.":" filas estimadas por ejecución."));}
            else if(read==null)notes.add("El motor no reporta el volumen leído en este plan.");
            notes.add("Revisar índices sobre filtros y JOIN, y el orden de columnas. Un escaneo puede ser óptimo si se devuelve gran parte de la tabla; no confirma un índice faltante.");
        }
        if(measured&&read!=null&&rows!=null&&read>=10_000&&read>=100*Math.max(1,rows)) {
            level=2;notes.add("Lectura excesiva frente al resultado: "+format(read)+" leídas / "+format(rows)+" devueltas. Revisar selectividad, predicados e índices.");
        }
        if(access.equals("ref")||access.equals("eq_ref")||access.equals("const")||access.equals("range")||access.contains("seek")||access.contains("lookup")||access.equals("search")||a.containsKey("Index Cond"))
            notes.add("Búsqueda por clave o rango; revisar columnas del índice y condiciones de búsqueda en las propiedades.");
        if(access.equals("all")&&a.containsKey("possible_keys")&&!a.get("possible_keys").equals("[]")&&!a.containsKey(INDEX))notes.add("Índices candidatos no elegidos: "+a.get("possible_keys")+". Revisar estadísticas, selectividad y orden de columnas.");
        boolean sort=access.equals("sort")||access.equals("incremental sort")||title.matches("(?i)^(Sort|Sort:|filesort|ordering_operation)(?:\\s.*)?$");
        Double volume=rows!=null?rows:value(a,ESTIMATED);
        if(sort&&volume!=null&&volume>=100_000){level=2;notes.add("Ordenamiento de gran volumen. Revisar si un índice compatible con filtros y ORDER BY puede evitarlo.");}
        boolean spill=a.entrySet().stream().anyMatch(e->(e.getKey().contains("Spill")&&Optional.ofNullable(number(e.getValue())).orElse(0d)>0)||e.getKey().equals("Sort Space Type")&&e.getValue().equalsIgnoreCase("Disk"));
        if(spill){level=2;notes.add("Derrame a disco reportado por el motor; revisar volumen, memoria y ordenamiento.");}
        if(!notes.isEmpty()){a.put(WARNING,String.join(" ",notes));a.put(LEVEL,level==2?"Alta":"Revisar");}
    }

    public static List<QueryPlan.Node> findings(QueryPlan.Node root) {
        var out=new ArrayList<QueryPlan.Node>();collect(root,out);return List.copyOf(out);
    }
    public static List<String> queryTimes(QueryPlan.Node node){var times=new ArrayList<String>();node.attributes().forEach((k,v)->{if(k.endsWith(QUERY_TIME)&&number(v)!=null)times.add(v);});for(var child:node.children())times.addAll(queryTimes(child));return List.copyOf(times);}
    private static void collect(QueryPlan.Node node,List<QueryPlan.Node> out){if(node.attributes().containsKey(WARNING))out.add(node);for(var child:node.children())collect(child,out);}
    public static Double value(QueryPlan.Node node,String key){return value(node.attributes(),key);}
    private static Double value(Map<String,String> a,String key){return number(a.get(key));}
    private static Double number(String text){try{double n=Double.parseDouble(text);return Double.isFinite(n)&&n>=0?n:null;}catch(Exception ignored){return null;}}
    private static double orZero(Double n){return n==null?0:n;}
    private static Double multiply(Double a,Double b){return a==null||b==null?null:a*b;}
    private static void put(Map<String,String> a,String key,Double n){if(n!=null&&Double.isFinite(n))a.put(key,format(n));}
    private static void copy(Map<String,String> a,String target,String... sources){for(String key:sources)if(a.containsKey(key)){a.put(target,a.get(key));return;}}
    public static String format(double n){return java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();}
}
