package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import com.nexodb.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Engine-supplied plans. Index suggestions are extracted from SQL Server XML, never auto-applied. */
public final class QueryPlan {
    public record Node(String title,Map<String,String> attributes,List<Node> children){public Node{attributes=Collections.unmodifiableMap(new LinkedHashMap<>(attributes));children=List.copyOf(children);}@Override public String toString(){return title;}}
    public record Result(Node root,String raw,String extension,List<String> suggestions,boolean actual,long elapsedMillis) {public Result{suggestions=List.copyOf(suggestions);}}
    public static final class Control {
        private volatile Statement statement;private volatile boolean cancelled;
        public void cancel(){cancelled=true;Statement active=statement;if(active!=null)try{active.cancel();}catch(SQLException ignored){}}
        private void check()throws SQLException{if(cancelled)throw new SQLException("Análisis de plan cancelado.");}
    }
    private QueryPlan() {}
    public static void validate(DatabaseType type,String sql,boolean actual)throws IOException {
        boolean read=QueryPaging.supported(sql);
        var statements=SqlText.executionStatements(sql);var tokens=SqlScriptTools.tokens(sql);
        boolean procedure=type==DatabaseType.SQLSERVER&&!actual&&statements.size()==1&&!tokens.isEmpty()&&(tokens.get(0).is("EXEC")||tokens.get(0).is("EXECUTE"));
        if(!read&&!procedure)throw new IllegalArgumentException("Selecciona una sola consulta SELECT o WITH de lectura. SQL Server también admite EXEC para el plan estimado.");
        if(tokens.stream().anyMatch(t->t.is("SET")||t.text().toUpperCase(Locale.ROOT).startsWith("SHOWPLAN_")))throw new IllegalArgumentException("No incluyas cambios SET/SHOWPLAN en el SQL a analizar.");
        if(!Set.of(DatabaseType.SQLSERVER,DatabaseType.MYSQL,DatabaseType.MARIADB,DatabaseType.POSTGRESQL,DatabaseType.SQLITE,DatabaseType.H2).contains(type))throw new IllegalArgumentException("El plan visual está disponible para SQL Server, MySQL, MariaDB, PostgreSQL, SQLite y H2.");
        if(actual&&type==DatabaseType.SQLITE)throw new IllegalArgumentException("SQLite ofrece EXPLAIN QUERY PLAN estimado; no ofrece tiempos reales por operador con esta interfaz.");
    }
    public static Result capture(JdbcSession session,String sql,boolean actual,Control control)throws Exception {
        DatabaseType type=session.profile().type();validate(type,sql,actual);long start=System.nanoTime();String raw;
        synchronized(session.connection()) {
            control.check();
            if(type==DatabaseType.SQLSERVER) {
                String option=actual?"STATISTICS XML":"SHOWPLAN_XML";boolean enabled=false;
                try {
                    try(Statement command=session.connection().createStatement()){command.execute("SET "+option+" ON");enabled=true;}
                    control.check();raw=collect(session.connection(),sql,control,true);
                }finally {
                    control.statement=null;
                    if(enabled)try(Statement command=session.connection().createStatement()){command.execute("SET "+option+" OFF");}
                    catch(SQLException cleanup){try{session.connection().close();}catch(SQLException close){cleanup.addSuppressed(close);}throw new SQLException("No se pudo restaurar "+option+". Se cerró la conexión para evitar reutilizar una sesión alterada.",cleanup);}
                }
            } else {
                String prefix=switch(type){case MYSQL->actual?"EXPLAIN ANALYZE FORMAT=TREE ":"EXPLAIN FORMAT=JSON ";case MARIADB->actual?"ANALYZE FORMAT=JSON ":"EXPLAIN FORMAT=JSON ";case POSTGRESQL->actual?"EXPLAIN (ANALYZE TRUE, BUFFERS TRUE, FORMAT JSON) ":"EXPLAIN (FORMAT JSON) ";case SQLITE->"EXPLAIN QUERY PLAN ";case H2->actual?"EXPLAIN ANALYZE ":"EXPLAIN ";default->throw new IllegalArgumentException();};
                raw=collect(session.connection(),prefix+sql,control,false);
            }
        }
        control.check();if(raw.isBlank())throw new SQLException("El servidor no devolvió un plan. Revisa permisos SHOWPLAN, versión del motor y SQL seleccionado.");
        return parse(type,raw,actual,(System.nanoTime()-start)/1_000_000);
    }
    private static String collect(Connection connection,String sql,Control control,boolean xml)throws SQLException {
        StringBuilder text=new StringBuilder();
        try(Statement command=connection.createStatement()) {
            command.setQueryTimeout(120);control.statement=command;boolean result=command.execute(sql);
            do {
                control.check();
                if(result)try(ResultSet rows=command.getResultSet()) {
                    ResultSetMetaData meta=rows.getMetaData();boolean showplan=!xml||meta.getColumnCount()==1&&meta.getColumnLabel(1).toLowerCase(Locale.ROOT).contains("showplan");
                    while(rows.next()) {
                        control.check();if(!showplan)continue;
                        if(meta.getColumnCount()==1){if(!text.isEmpty())text.append('\n');text.append(rows.getString(1));}
                        else {for(int i=1;i<=meta.getColumnCount();i++){if(i>1)text.append('\t');text.append(rows.getString(i));}text.append('\n');}
                        if(text.length()>4_000_000)throw new SQLException("El plan supera 4 MB. Analiza una consulta más pequeña.");
                    }
                }else if(command.getUpdateCount()==-1)break;
                result=command.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            }while(true);
        }finally{control.statement=null;}
        return text.toString();
    }
    public static Result parse(DatabaseType type,String raw,boolean actual,long elapsed)throws Exception {
        if(raw.length()>4_000_000)throw new IllegalArgumentException("El plan supera 4 MB.");
        List<String> suggestions=new ArrayList<>();Node root;String extension="txt";
        if(type==DatabaseType.SQLSERVER) {
            extension="sqlplan";List<Node> roots=new ArrayList<>();Map<String,String> summary=new LinkedHashMap<>();
            // A batch may emit several independent ShowPlanXML documents.
            var matcher=java.util.regex.Pattern.compile("(?s)(?:<\\?xml.*?\\?>\\s*)?<(?:(?:\\w+):)?ShowPlanXML\\b.*?</(?:(?:\\w+):)?ShowPlanXML>").matcher(raw);
            boolean found=false;int query=0;while(matcher.find()){found=true;Document doc=xml(matcher.group());var operators=doc.getElementsByTagNameNS("*","RelOp");for(int i=0;i<operators.getLength();i++){Element operator=(Element)operators.item(i);org.w3c.dom.Node parent=operator.getParentNode();boolean nested=false;while(parent!=null){if(parent instanceof Element e&&"RelOp".equals(e.getLocalName())){nested=true;break;}parent=parent.getParentNode();}if(!nested)roots.add(xmlOperator(operator,0));}missingIndexes(doc,suggestions);
                if(actual){var timings=doc.getElementsByTagNameNS("*","QueryTimeStats");for(int i=0;i<timings.getLength();i++){Element time=(Element)timings.item(i);String prefix="Consulta "+(++query)+" · ";if(time.hasAttribute("ElapsedTime"))summary.put(prefix+PlanDiagnostics.QUERY_TIME,time.getAttribute("ElapsedTime"));if(time.hasAttribute("CpuTime"))summary.put(prefix+PlanDiagnostics.CPU,time.getAttribute("CpuTime"));}}
            }
            if(!found)throw new IllegalArgumentException("No se encontró ShowPlanXML en el archivo.");
            summary.put("Métricas",actual?"Tiempos reportados por operador; no sumar tiempos de subárboles ni hilos.":"Costos estimados: no son milisegundos.");
            root=new Node("SQL Server · "+(actual?"plan real":"plan estimado"),summary,roots);
        }else if(type==DatabaseType.MYSQL||type==DatabaseType.MARIADB||type==DatabaseType.POSTGRESQL) {
            String input=raw.stripLeading();if(input.startsWith("{")||input.startsWith("[")){extension="json";Object json=Json.object("{\"plan\":"+raw+"}").get("plan");root=jsonNode(type.toString(),json,0);}
            else root=textTree(raw,type.toString());
        }else root=textTree(raw,type.toString());
        return new Result(PlanDiagnostics.enrich(type,root,actual),raw,extension,suggestions,actual,elapsed);
    }
    private static Document xml(String text)throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void error(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});return builder.parse(new InputSource(new StringReader(text)));
    }
    private static Node xmlOperator(Element element,int depth) {
        if(depth>80)throw new IllegalArgumentException("El plan supera 80 niveles.");
        Map<String,String> attrs=attributes(element);List<Node> children=new ArrayList<>();List<Element> details=new ArrayList<>();operatorChildren(element,children,details,depth);
        for(Element detail:details) {
            String name=detail.getLocalName();if(Set.of("Object","RunTimeCountersPerThread","Warnings","SpillToTempDb","MemoryGrantWarning","SortSpillDetails","HashSpillDetails").contains(name)) {
                String prefix=name.equals("RunTimeCountersPerThread")?"Runtime hilo "+detail.getAttribute("Thread")+" · ":name+" · ";attributes(detail).forEach((k,v)->attrs.put(prefix+k,v));
            }
            if(Set.of("Predicate","SeekPredicates","OrderBy").contains(name)) {
                var expressions=detail.getElementsByTagNameNS("*","ScalarOperator");
                for(int i=0;i<expressions.getLength();i++){String expression=((Element)expressions.item(i)).getAttribute("ScalarString");if(!expression.isBlank())attrs.merge(name,expression,(a,b)->a+"; "+b);}
            }
        }
        String object=details.stream().filter(e->"Object".equals(e.getLocalName())).map(e->e.getAttribute("Table")).findFirst().orElse("");
        return new Node(element.getAttribute("PhysicalOp")+(object.isBlank()?"":" · "+object),attrs,children);
    }
    private static void operatorChildren(Element parent,List<Node> children,List<Element> details,int depth) {
        if(depth>100)throw new IllegalArgumentException("Plan demasiado profundo.");
        for(org.w3c.dom.Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e){if("RelOp".equals(e.getLocalName()))children.add(xmlOperator(e,depth+1));else{details.add(e);operatorChildren(e,children,details,depth+1);}}
    }
    private static Map<String,String> attributes(Element e){Map<String,String> values=new LinkedHashMap<>();NamedNodeMap attrs=e.getAttributes();for(int i=0;i<attrs.getLength();i++)values.put(attrs.item(i).getNodeName(),attrs.item(i).getNodeValue());return values;}
    private static void missingIndexes(Document doc,List<String> out) {
        NodeList groups=doc.getElementsByTagNameNS("*","MissingIndexGroup");
        for(int i=0;i<groups.getLength();i++) {
            Element group=(Element)groups.item(i);NodeList indexes=group.getElementsByTagNameNS("*","MissingIndex");
            for(int j=0;j<indexes.getLength();j++) {
                Element index=(Element)indexes.item(j);List<String> keys=new ArrayList<>(),include=new ArrayList<>();
                // Equality keys precede inequality keys, independently of XML order.
                for(String usage:List.of("EQUALITY","INEQUALITY","INCLUDE")){NodeList columnGroups=index.getElementsByTagNameNS("*","ColumnGroup");for(int k=0;k<columnGroups.getLength();k++){Element cg=(Element)columnGroups.item(k);if(!usage.equals(cg.getAttribute("Usage")))continue;NodeList columns=cg.getElementsByTagNameNS("*","Column");for(int n=0;n<columns.getLength();n++)(usage.equals("INCLUDE")?include:keys).add(quote(((Element)columns.item(n)).getAttribute("Name")));}}
                if(keys.isEmpty())continue;List<String> path=new ArrayList<>();for(String part:List.of("Database","Schema","Table"))if(!index.getAttribute(part).isBlank())path.add(quote(index.getAttribute(part)));
                out.add("-- Sugerencia del optimizador. Impacto estimado: "+group.getAttribute("Impact")+"%. Revisa índices existentes, tamaño y costo de escrituras.\nCREATE INDEX "+quote("IX_Nexo_Plan_"+(out.size()+1))+" ON "+String.join(".",path)+" ("+String.join(", ",keys)+")"+(include.isEmpty()?"":" INCLUDE ("+String.join(", ",include)+")")+";");
            }
        }
    }
    private static String quote(String name){if(name.startsWith("[")&&name.endsWith("]"))name=name.substring(1,name.length()-1).replace("]]","]");return "["+name.replace("]","]]")+"]";}
    private static Node jsonNode(String name,Object value,int depth) {
        if(depth>60)throw new IllegalArgumentException("Plan JSON demasiado profundo.");Map<String,String> attrs=new LinkedHashMap<>();List<Node> children=new ArrayList<>();
        if(value instanceof Map<?,?> map) {
            if(map.get("Node Type")!=null)name=map.get("Node Type").toString();
            else if(map.get("access_type")!=null)name=map.get("access_type")+" · "+map.get("table_name");
            for(var entry:map.entrySet()){
                String key=entry.getKey().toString();Object item=entry.getValue();
                if(Set.of("cost_info","r_engine_stats").contains(key)&&item instanceof Map<?,?> info)info.forEach((k,v)->attrs.put(k.toString(),String.valueOf(v)));
                else if(item instanceof List<?> list&&list.stream().noneMatch(v->v instanceof Map<?,?>||v instanceof List<?>))attrs.put(key,list.toString());
                else if(item instanceof Map<?,?>||item instanceof List<?>)children.add(jsonNode(key,item,depth+1));
                else if(item!=null)attrs.put(key,item.toString());
            }
        }else if(value instanceof List<?> list){for(int i=0;i<list.size();i++)children.add(jsonNode("Paso "+(i+1),list.get(i),depth+1));}
        else attrs.put("Valor",String.valueOf(value));
        return new Node(name,attrs,children);
    }
    private static Node textTree(String raw,String name) {
        class Step {String title;int indent;Map<String,String> attrs=new LinkedHashMap<>();List<Step> children=new ArrayList<>();Node node(){return new Node(title,attrs,children.stream().map(Step::node).toList());}}
        Step root=new Step();root.title=name+" · plan del motor";root.indent=-1;Deque<Step> stack=new ArrayDeque<>();stack.push(root);
        for(String line:raw.split("\\R"))if(!line.isBlank()) {
            Step step=new Step();step.title=line.strip().replaceFirst("^->\\s*","");step.indent=line.length()-line.stripLeading().length();step.attrs.put("Detalle del motor",line);
            var time=java.util.regex.Pattern.compile("actual time=([0-9.eE+\\-]+)\\.\\.([0-9.eE+\\-]+) rows=([0-9.eE+\\-]+) loops=([0-9.eE+\\-]+)").matcher(line);
            if(time.find()){step.attrs.put("Tiempo primera fila (ms)",time.group(1));step.attrs.put("Tiempo del iterador (ms, promedio por loop)",time.group(2));step.attrs.put("Filas reales",time.group(3));step.attrs.put("Loops",time.group(4));}
            while(stack.size()>1&&stack.peek().indent>=step.indent)stack.pop();stack.peek().children.add(step);stack.push(step);
        }
        return root.node();
    }
}
