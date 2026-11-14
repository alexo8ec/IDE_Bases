package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.file.Path;
import java.util.*;

public final class QueryPagingTest {
    public static void main(String[] args)throws Exception {
        var profile=new ConnectionProfile("paging","Test",DatabaseType.H2,"",0,"mem:paging","sa","jdbc:h2:mem:paging","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])) {
            session.execute("CREATE TABLE entries(id INT PRIMARY KEY,name VARCHAR(20))",10);session.execute("INSERT INTO entries SELECT X,'row' FROM SYSTEM_RANGE(1,2505)",10);
            QueryResult page=session.execute("SELECT * FROM entries ORDER BY id",1000);equal(1000,page.rows().size());equal(true,page.truncated());
            Set<Object> seen=new HashSet<>();long offset=0;
            do {for(var row:page.rows())if(!seen.add(row.get(0)))throw new AssertionError("Duplicate paging row");offset+=page.rows().size();if(!page.truncated())break;page=session.executePage("SELECT * FROM entries ORDER BY id",1000,offset);}while(true);
            equal(2505,seen.size());equal(505,page.rows().size());equal(false,page.truncated());
            for(int size:List.of(100,500,1000,10000)){var result=session.executePage("SELECT * FROM entries ORDER BY id",size,1000);equal(Math.min(size,1505),result.rows().size());equal(1001,result.rows().get(0).get(0));if(result.editing()==null||result.editing().target()==null)throw new AssertionError("Page lost editing identity");}
            var limited=session.executePage("SELECT id FROM entries ORDER BY id LIMIT 7",3,6);equal(List.of(List.of(7)),limited.rows());equal(false,limited.truncated());
            equal(0,session.executePage("SELECT id FROM entries ORDER BY id",100,9000).rows().size());
            for(String sql:List.of("CALL proc()","EXEC dbo.proc","DELETE FROM entries","SELECT 1; DELETE FROM entries","SELECT * FROM entries FOR UPDATE")){equal(false,QueryPaging.supported(sql));try{session.executePage(sql,100,0);throw new AssertionError("Unsafe replay accepted");}catch(IllegalArgumentException expected){}}
            for(int size:List.of(0,10001))try{session.executePage("SELECT 1",size,0);throw new AssertionError("Invalid size accepted");}catch(IllegalArgumentException expected){}
            equal(2505,session.execute("SELECT id FROM entries",3000).rows().size());
        }
        System.out.println("QueryPagingTest: OK (2505 rows across pages without gaps/duplicates, sizes, final page, explicit SQL LIMIT, empty page, metadata and no CALL/EXEC/write replay)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
