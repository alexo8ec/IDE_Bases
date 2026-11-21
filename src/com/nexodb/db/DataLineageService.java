package com.nexodb.db;

import java.util.*;

/** Builds a portable dependency graph from the completed JDBC data dictionary. */
public final class DataLineageService {
    public record Edge(String source,String target,String kind,String detail){}
    public record Graph(String database,List<String> nodes,List<Edge> edges){public Graph{nodes=List.copyOf(nodes);edges=List.copyOf(edges);}}
    private DataLineageService(){}
    public static Graph from(DataDictionaryService.Dictionary dictionary){
        Set<String> nodes=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);List<Edge> edges=new ArrayList<>();for(var table:dictionary.tables()){nodes.add(table.qualified());for(var relation:table.relations()){nodes.add(relation.parent());edges.add(new Edge(table.qualified(),relation.parent(),"FK",String.join(", ",relation.columns())+" → "+String.join(", ",relation.parentColumns())));}}
        edges.sort(Comparator.comparing(Edge::source,String.CASE_INSENSITIVE_ORDER).thenComparing(Edge::target,String.CASE_INSENSITIVE_ORDER));return new Graph(dictionary.database(),new ArrayList<>(nodes),edges);
    }
    public static String mermaid(Graph graph){StringBuilder out=new StringBuilder("flowchart LR\n");Map<String,String> ids=new LinkedHashMap<>();int i=0;for(String node:graph.nodes()){String id="T"+(++i);ids.put(node,id);out.append("  ").append(id).append("[\"").append(escape(node)).append("\"]\n");}for(Edge edge:graph.edges())out.append("  ").append(ids.get(edge.source())).append(" -->|\"").append(escape(edge.kind()+" "+edge.detail())).append("\"| ").append(ids.get(edge.target())).append('\n');return out.toString();}
    public static String markdown(Graph graph){StringBuilder out=new StringBuilder("# Linaje y dependencias · ").append(graph.database()).append("\n\n```mermaid\n").append(mermaid(graph)).append("```\n\n| Origen | Destino | Tipo | Detalle |\n|---|---|---|---|\n");for(Edge edge:graph.edges())out.append('|').append(pipe(edge.source())).append('|').append(pipe(edge.target())).append('|').append(edge.kind()).append('|').append(pipe(edge.detail())).append("|\n");return out.toString();}
    private static String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"");}private static String pipe(String value){return value.replace("|","\\|");}
}
