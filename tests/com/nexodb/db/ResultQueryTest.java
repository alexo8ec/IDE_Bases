package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.file.Path;
import java.sql.Types;
import java.util.*;

public final class ResultQueryTest {
    public static void main(String[] args)throws Exception{
        var profile=new ConnectionProfile("result-query","Test",DatabaseType.H2,"",0,"mem:result_query","sa","jdbc:h2:mem:result_query","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE entries(id INT PRIMARY KEY,name VARCHAR(40))",1);
            session.execute("INSERT INTO entries SELECT X, 'row-' || X FROM SYSTEM_RANGE(1,2505)",1);
            List<String> columns=List.of("ID","NAME");List<Integer> types=List.of(Types.INTEGER,Types.VARCHAR);
            equal(true,ResultQuery.supported("SELECT * FROM entries ORDER BY id",DatabaseType.H2,columns));
            equal("SELECT * FROM entries ORDER BY id",ResultQuery.prepare("SELECT * FROM entries ORDER BY id",DatabaseType.H2,columns,types,"",List.of(),List.of()).sql());
            var global=ResultQuery.prepare("SELECT * FROM entries ORDER BY id",DatabaseType.H2,columns,types,"row-2401",List.of(),List.of());
            equal(false,global.sql().contains("row-2401"));equal(2,global.parameters().size());
            var found=session.executePage(global.sql(),global.parameters(),100,0);equal(1,found.rows().size());equal(2401,found.rows().get(0).get(0));
            var range=new ResultQuery.Filter(0,new ResultQuery.Condition(ResultQuery.Operator.GREATER,"2000"),ResultQuery.Junction.AND,new ResultQuery.Condition(ResultQuery.Operator.LESS_OR_EQUAL,"2010"),false);
            var filtered=ResultQuery.prepare("SELECT * FROM entries ORDER BY id",DatabaseType.H2,columns,types,"",List.of(range),List.of(new ResultQuery.Sort(0,true)));
            var page=session.executePage(filtered.sql(),filtered.parameters(),100,0);equal(10,page.rows().size());equal(2010,page.rows().get(0).get(0));equal(2001,page.rows().get(9).get(0));
            var hostile=ResultQuery.prepare("SELECT * FROM entries",DatabaseType.H2,columns,types,"%' OR 1=1 --",List.of(),List.of());
            equal(false,hostile.sql().contains("OR 1=1"));equal(0,session.executePage(hostile.sql(),hostile.parameters(),100,0).rows().size());
            equal(false,ResultQuery.supported("SELECT id AS x, id AS x FROM entries",DatabaseType.H2,List.of("X","X")));
            equal(false,ResultQuery.supported("WITH x AS (SELECT 1 id) SELECT * FROM x",DatabaseType.SQLSERVER,List.of("id")));
            var sqlServer=ResultQuery.prepare("SELECT * FROM entries ORDER BY id",DatabaseType.SQLSERVER,columns,types,"",List.of(),List.of(new ResultQuery.Sort(0,true)));
            equal(true,sqlServer.sql().contains("ORDER BY id OFFSET 0 ROWS"));equal(true,sqlServer.sql().endsWith("[__adisdb_result].[ID] DESC"));
        }
        System.out.println("ResultQueryTest: OK (global search, numeric conditions, global order, prepared parameters, escaping and dialect safety)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
