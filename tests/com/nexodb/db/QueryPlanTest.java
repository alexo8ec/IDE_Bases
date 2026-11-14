package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.*;

public final class QueryPlanTest {
    public static final String XML="""
            <ShowPlanXML xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan"><BatchSequence><Batch><Statements><StmtSimple StatementText="SELECT * FROM sales"><QueryPlan>
            <MissingIndexes><MissingIndexGroup Impact="83.2"><MissingIndex Database="[demo]" Schema="[dbo]" Table="[sales]">
            <ColumnGroup Usage="INCLUDE"><Column Name="[amount]"/></ColumnGroup><ColumnGroup Usage="INEQUALITY"><Column Name="[sale_date]"/></ColumnGroup><ColumnGroup Usage="EQUALITY"><Column Name="[region]"/></ColumnGroup>
            </MissingIndex></MissingIndexGroup></MissingIndexes>
            <RelOp NodeId="0" PhysicalOp="Sort" LogicalOp="Sort" EstimateRows="100" EstimatedTotalSubtreeCost="4.2"><RunTimeInformation><RunTimeCountersPerThread Thread="0" ActualRows="130" ActualElapsedms="22" ActualCPUms="11"/></RunTimeInformation><Sort><RelOp NodeId="1" PhysicalOp="Table Scan" LogicalOp="Table Scan" EstimateRows="100" EstimatedTotalSubtreeCost="3.1"><TableScan><Object Database="[demo]" Schema="[dbo]" Table="[sales]"/></TableScan></RelOp></Sort></RelOp>
            </QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>
            """;
    public static void main(String[] args)throws Exception {
        QueryPlan.Result parsed=QueryPlan.parse(DatabaseType.SQLSERVER,XML,true,23);equal(1,parsed.root().children().size());var sort=parsed.root().children().get(0);equal("Sort",sort.title());equal("4.2",sort.attributes().get("EstimatedTotalSubtreeCost"));equal("22",sort.attributes().get("Runtime hilo 0 · ActualElapsedms"));equal("Table Scan · [sales]",sort.children().get(0).title());equal(1,parsed.suggestions().size());
        String ddl=parsed.suggestions().get(0);if(!ddl.contains("[region], [sale_date]")||!ddl.contains("INCLUDE ([amount])"))throw new AssertionError("Invalid optimizer index order: "+ddl);
        var pg=QueryPlan.parse(DatabaseType.POSTGRESQL,"[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Relation Name\":\"sales\",\"Actual Total Time\":12.3,\"Plan Rows\":100}}]",true,10);if(!flatten(pg.root()).contains("Seq Scan"))throw new AssertionError("PostgreSQL plan lost structure");
        var mysql=QueryPlan.parse(DatabaseType.MYSQL,"{\"query_block\":{\"table\":{\"table_name\":\"sales\",\"access_type\":\"ALL\",\"rows_examined_per_scan\":100}}}",false,1);if(!flatten(mysql.root()).contains("ALL · sales"))throw new AssertionError("MySQL access method lost");
        for(String sql:List.of("DELETE FROM x","SELECT 1; DELETE FROM x","SELECT 1 SET SHOWPLAN_XML OFF"))try{QueryPlan.validate(DatabaseType.SQLSERVER,sql,false);throw new AssertionError("Unsafe plan accepted");}catch(IllegalArgumentException expected){}
        QueryPlan.validate(DatabaseType.SQLSERVER,"EXEC dbo.sales @region='Costa'",false);
        try{QueryPlan.validate(DatabaseType.SQLSERVER,"EXEC dbo.sales",true);throw new AssertionError("Actual EXEC must require a different execution workflow");}catch(IllegalArgumentException expected){}
        var commands=new ArrayList<String>();try(JdbcSession session=mock(commands,false,false)){QueryPlan.capture(session,"SELECT 1",false,new QueryPlan.Control());}equal(List.of("SET SHOWPLAN_XML ON","SELECT 1","SET SHOWPLAN_XML OFF"),commands);
        commands.clear();try(JdbcSession session=mock(commands,true,false)){try{QueryPlan.capture(session,"SELECT 1",false,new QueryPlan.Control());throw new AssertionError("Expected SQL error");}catch(SQLException expected){}}equal(List.of("SET SHOWPLAN_XML ON","SELECT 1","SET SHOWPLAN_XML OFF"),commands);
        commands.clear();try(JdbcSession session=mock(commands,false,true)){try{QueryPlan.capture(session,"SELECT 1",false,new QueryPlan.Control());throw new AssertionError("Expected restore failure");}catch(SQLException expected){if(!expected.getMessage().contains("cerró"))throw expected;}}if(!commands.contains("CLOSE"))throw new AssertionError("Failed restoration must invalidate the connection");
        try{QueryPlan.parse(DatabaseType.SQLSERVER,XML.replace("SELECT * FROM sales","&external;").replace("<ShowPlanXML","<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///nonexistent'>]><ShowPlanXML"),false,0);throw new AssertionError("External entity accepted");}catch(Exception expected){}
        var profile=new ConnectionProfile("plan","Test",DatabaseType.H2,"",0,"mem:plan","sa","jdbc:h2:mem:plan","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){session.execute("CREATE TABLE sales(id INT PRIMARY KEY)",1);session.execute("INSERT INTO sales VALUES(1),(2)",1);var result=QueryPlan.capture(session,"SELECT * FROM sales WHERE id=1",false,new QueryPlan.Control());if(!result.raw().replace("\"","").contains("PUBLIC.SALES"))throw new AssertionError(result.raw());equal(2,session.execute("SELECT * FROM sales",10).rows().size());}
        System.out.println("QueryPlanTest: OK (SQL Server hierarchy/runtime/missing indexes, PostgreSQL/MySQL JSON, real H2 EXPLAIN, no write/actual EXEC, SHOWPLAN restoration on failure, invalidation and XML isolation)");
    }
    private static String flatten(QueryPlan.Node node){return node.title()+node.children().stream().map(QueryPlanTest::flatten).toList();}
    private static JdbcSession mock(List<String> commands,boolean failQuery,boolean failRestore)throws Exception {
        boolean[] closed={false};
        Connection connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->switch(method.getName()){
            case "createStatement" -> Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->switch(m.getName()){
                case "execute" -> {String sql=(String)a[0];commands.add(sql);if(failQuery&&sql.startsWith("SELECT"))throw new SQLException("Query failure");if(failRestore&&sql.endsWith("OFF"))throw new SQLException("Restore failure");yield sql.startsWith("SELECT");}
                case "getResultSet" -> {var rows=RowSetProvider.newFactory().createCachedRowSet();var metadata=new RowSetMetaDataImpl();metadata.setColumnCount(1);metadata.setColumnName(1,"Microsoft SQL Server 2005 XML Showplan");metadata.setColumnLabel(1,"Microsoft SQL Server 2005 XML Showplan");metadata.setColumnType(1,Types.VARCHAR);rows.setMetaData(metadata);rows.moveToInsertRow();rows.updateString(1,XML);rows.insertRow();rows.moveToCurrentRow();rows.beforeFirst();yield rows;}
                case "getMoreResults" -> false;
                case "getUpdateCount" -> -1;
                default -> null;
            });
            case "isClosed" -> closed[0];case "getAutoCommit" -> true;case "close" -> {closed[0]=true;if(failRestore)commands.add("CLOSE");yield null;}default -> null;
        });
        var profile=new ConnectionProfile("plan-mock","Test",DatabaseType.SQLSERVER,"",0,"","sa","","","",false);var constructor=JdbcSession.class.getDeclaredConstructor(ConnectionProfile.class,Connection.class,java.net.URLClassLoader.class);constructor.setAccessible(true);return constructor.newInstance(profile,connection,null);
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
