package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.util.*;
import static com.nexodb.db.PlanDiagnostics.*;

public final class PlanDiagnosticsTest {
    public static void main(String[] args)throws Exception {
        String xml="""
            <ShowPlanXML xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan"><QueryPlan>
            <RelOp PhysicalOp="Index Seek" EstimateRows="5" EstimatedTotalSubtreeCost="0.3"><IndexScan>
            <Object Table="[sales]" Index="[IX_region]"/>
            <SeekPredicates><ScalarOperator ScalarString="[region]=@region"/></SeekPredicates>
            </IndexScan><RunTimeInformation>
            <RunTimeCountersPerThread Thread="1" ActualRows="4" ActualRowsRead="200000" ActualExecutions="1" ActualElapsedms="30" ActualCPUms="20"/>
            <RunTimeCountersPerThread Thread="2" ActualRows="6" ActualRowsRead="300000" ActualExecutions="1" ActualElapsedms="40" ActualCPUms="25"/>
            </RunTimeInformation></RelOp></QueryPlan></ShowPlanXML>
            """;
        var sql=QueryPlan.parse(DatabaseType.SQLSERVER,xml,true,9000);
        var seek=find(sql.root(),ACCESS,"Index Seek");
        eq("[IX_region]",seek.attributes().get(INDEX));eq(40d,value(seek,TIME));eq(45d,value(seek,CPU));
        eq(10d,value(seek,ROWS));eq(500000d,value(seek,READ));eq("Alta",seek.attributes().get(LEVEL));
        contains(seek.attributes().get("SeekPredicates"),"@region");
        var estimate=QueryPlan.parse(DatabaseType.SQLSERVER,xml,false,9000);
        if(value(find(estimate.root(),ACCESS,"Index Seek"),TIME)!=null)throw new AssertionError("Estimated plan exposed actual time");
        var missing=QueryPlan.parse(DatabaseType.SQLSERVER,QueryPlanTest.XML,true,25);
        eq(22d,value(missing.root().children().get(0),TIME));
        if(missing.root().children().get(0).children().get(0).attributes().containsKey(TIME))throw new AssertionError("Invented scan time");

        String pg="""
            [{"Plan":{"Node Type":"Seq Scan","Relation Name":"sales","Plan Rows":2,
            "Actual Total Time":15,"Actual Rows":2,"Actual Loops":4,"Rows Removed by Filter":249998,
            "Shared Read Blocks":50},"Execution Time":63}]
            """;
        var seq=find(QueryPlan.parse(DatabaseType.POSTGRESQL,pg,true,100).root(),ACCESS,"Seq Scan");
        eq(List.of("63"),queryTimes(QueryPlan.parse(DatabaseType.POSTGRESQL,pg,true,100).root()));
        eq(8d,value(seq,ROWS));eq(1_000_000d,value(seq,READ));eq(15d,value(seq,TIME));eq("Alta",seq.attributes().get(LEVEL));
        contains(seq.attributes().get(SCOPE),"Promedio por loop");
        var pgIndex=find(QueryPlan.parse(DatabaseType.POSTGRESQL,"""
            [{"Plan":{"Node Type":"Index Scan","Relation Name":"sales","Index Name":"sales_pkey",
            "Index Cond":"id = 1","Plan Rows":1,"Actual Rows":1,"Actual Loops":1,"Actual Total Time":0.2}}]
            """,true,1).root(),INDEX,"sales_pkey");
        if(pgIndex.attributes().getOrDefault(WARNING,"").contains("completo"))throw new AssertionError("Selective PostgreSQL index scan treated as full scan");

        var mysql=QueryPlan.parse(DatabaseType.MYSQL,"""
            {"query_block":{"cost_info":{"query_cost":"456.7"},"table":{"table_name":"sales","access_type":"ALL",
            "possible_keys":["IX_region"],"rows_examined_per_scan":900000,"filtered":0.1}}}
            """,false,8);
        var all=find(mysql.root(),ACCESS,"ALL");eq("Alta",all.attributes().get(LEVEL));eq(900000d,value(all,ESTIMATED_READ));
        eq(null,value(all,TIME));eq(null,value(all,ROWS));eq(null,value(all,READ));contains(all.attributes().get(WARNING),"no confirma un índice faltante");
        eq(456.7d,value(find(mysql.root(),COST,"456.7"),COST));
        if(!mysql.suggestions().isEmpty())throw new AssertionError("Heuristic generated optimizer DDL");
        var ref=find(QueryPlan.parse(DatabaseType.MYSQL,"""
            {"query_block":{"table":{"table_name":"sales","access_type":"ref","key":"IX_region",
            "used_key_parts":["region"],"ref":["const"],"rows_examined_per_scan":5}}}
            """,false,1).root(),INDEX,"IX_region");
        eq("[region]",ref.attributes().get("used_key_parts"));

        var tree=QueryPlan.parse(DatabaseType.MYSQL,"""
            -> Filter: (sales.region = 'Costa')  (cost=200 rows=2) (actual time=0.2..50 rows=2 loops=3)
                -> Table scan on sales  (cost=200 rows=200000) (actual time=0.1..45 rows=200000 loops=3)
            """,true,100);
        var table=find(tree.root(),ACCESS,"Table scan");eq(600000d,value(table,READ));eq(45d,value(table,TIME));
        eq("sales",table.attributes().get(TABLE));eq(200000d,value(table,ESTIMATED));eq("Alta",tree.root().children().get(0).attributes().get(LEVEL));
        var treeIndex=find(QueryPlan.parse(DatabaseType.MYSQL,"""
            -> Index lookup on sales using IX_region (region='Costa')  (cost=1 rows=3) (actual time=0.01..0.02 rows=3 loops=2)
            """,true,1).root(),ACCESS,"Index lookup");
        eq("IX_region",treeIndex.attributes().get(INDEX));eq(6d,value(treeIndex,ROWS));
        var range=find(QueryPlan.parse(DatabaseType.MYSQL,"""
            -> Index range scan on sales using PRIMARY over (100 < id)  (cost=10 rows=200) (actual time=0.01..0.5 rows=200 loops=1)
            """,true,1).root(),ACCESS,"Index range scan");eq("PRIMARY",range.attributes().get(INDEX));
        var noSort=find(QueryPlan.parse(DatabaseType.MYSQL,"""
            -> Index lookup on sort_data using IX_region (region='Costa')  (cost=100 rows=200000) (actual time=0.01..5 rows=200000 loops=1)
            """,true,10).root(),ACCESS,"Index lookup");
        if(noSort.attributes().getOrDefault(WARNING,"").contains("Ordenamiento"))throw new AssertionError("Table name mistaken for sort operator");

        var maria=find(QueryPlan.parse(DatabaseType.MARIADB,"""
            {"query_block":{"r_total_time_ms":90,"nested_loop":[{"table":{"table_name":"sales","access_type":"ALL",
            "rows":100000,"r_rows":250000,"r_loops":2,"r_filtered":0.01,"r_table_time_ms":70,
            "r_engine_stats":{"pages_accessed":800}}}]}}
            """,true,100).root(),ACCESS,"ALL");
        eq(500000d,value(maria,READ));eq(50d,value(maria,ROWS));eq(70d,value(maria,TIME));eq("800",maria.attributes().get("pages_accessed"));
        contains(maria.attributes().get(SCOPE),"Total reportado");

        var sqlite=QueryPlan.parse(DatabaseType.SQLITE,"2\t0\t0\tSCAN sales\n3\t0\t0\tSEARCH people USING COVERING INDEX IX_name (name=?)",false,4);
        eq("sales",find(sqlite.root(),ACCESS,"SCAN").attributes().get(TABLE));eq("IX_name",find(sqlite.root(),ACCESS,"SEARCH").attributes().get(INDEX));
        contains(find(sqlite.root(),ACCESS,"SCAN").attributes().get(WARNING),"no reporta el volumen");
        if(findings(sqlite.root()).stream().anyMatch(n->"Alta".equals(n.attributes().get(LEVEL))))throw new AssertionError("Unknown SQLite volume labeled high");

        var small=QueryPlan.parse(DatabaseType.POSTGRESQL,"""
            [{"Plan":{"Node Type":"Seq Scan","Relation Name":"tiny","Plan Rows":20,"Actual Rows":20,"Actual Loops":1,"Actual Total Time":0.1}}]
            """,true,2);
        if(findings(small.root()).stream().anyMatch(n->"Alta".equals(n.attributes().get(LEVEL))))throw new AssertionError("Tiny scan labeled high");
        var disk=find(QueryPlan.parse(DatabaseType.POSTGRESQL,"""
            [{"Plan":{"Node Type":"Sort","Actual Rows":300000,"Actual Loops":1,"Actual Total Time":400,"Sort Space Type":"Disk","Sort Space Used":1000}}]
            """,true,450).root(),ACCESS,"Sort");
        contains(disk.attributes().get(WARNING),"Derrame a disco");
        var skipped=find(QueryPlan.parse(DatabaseType.POSTGRESQL,"""
            [{"Plan":{"Node Type":"Seq Scan","Actual Rows":0,"Actual Loops":0,"Actual Total Time":0,"Plan Rows":1000000}}]
            """,true,1).root(),ACCESS,"Seq Scan");eq(0d,value(skipped,READ));
        if("Alta".equals(skipped.attributes().get(LEVEL)))throw new AssertionError("Unexecuted node marked high");
        var mixed=QueryPlan.parse(DatabaseType.SQLSERVER,xml.replace("ActualRowsRead=\"200000\"","").replace("ActualRowsRead=\"300000\"","").replace("EstimateRows=\"5\"","EstimateRows=\"5\" EstimatedRowsRead=\"900000\""),true,1);
        if(find(mixed.root(),ACCESS,"Index Seek").attributes().getOrDefault(WARNING,"").contains("Lectura excesiva"))throw new AssertionError("Compared estimated reads to actual output");
        String timed=xml.replace("<QueryPlan>","<QueryPlan><QueryTimeStats ElapsedTime=\"50\" CpuTime=\"45\"/>");
        eq(List.of("50","50"),queryTimes(QueryPlan.parse(DatabaseType.SQLSERVER,timed+timed,true,2000).root()));
        System.out.println("PlanDiagnosticsTest: OK (parallel counters, missing metrics, estimates, loops, index access, large scans, filters, disk spills, six engine formats)");
    }
    private static QueryPlan.Node find(QueryPlan.Node root,String key,String value){if(value.equals(root.attributes().get(key)))return root;for(var child:root.children()){var found=maybe(child,key,value);if(found!=null)return found;}throw new AssertionError("Missing "+key+"="+value+" in "+root);}
    private static QueryPlan.Node maybe(QueryPlan.Node node,String key,String value){if(value.equals(node.attributes().get(key)))return node;for(var child:node.children()){var result=maybe(child,key,value);if(result!=null)return result;}return null;}
    private static void contains(String text,String part){if(text==null||!text.contains(part))throw new AssertionError("Missing "+part+" in "+text);}
    private static void eq(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
