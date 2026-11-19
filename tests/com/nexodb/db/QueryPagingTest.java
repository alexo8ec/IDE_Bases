package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.file.Path;
import java.util.*;

public final class QueryPagingTest {
    public static void main(String[] args)throws Exception {
        var profile=new ConnectionProfile("paging","Test",DatabaseType.H2,"",0,"mem:paging","sa","jdbc:h2:mem:paging","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        var mysqlPage=QueryPaging.pageSql("SELECT * FROM entries ORDER BY id;",DatabaseType.MYSQL,100,500);
        equal(true,mysqlPage.nativeOffset());equal(true,mysqlPage.sql().endsWith("LIMIT 101 OFFSET 500"));
        equal(false,QueryPaging.pageSql("SELECT * FROM entries ORDER BY id LIMIT 7",DatabaseType.MYSQL,3,6).nativeOffset());
        equal(true,QueryPaging.pageSql("WITH x AS (SELECT * FROM t LIMIT 2) SELECT * FROM x ORDER BY id",DatabaseType.POSTGRESQL,10,20).nativeOffset());
        equal(false,QueryPaging.pageSql("SELECT * FROM entries",DatabaseType.SQLSERVER,100,500).nativeOffset());
        equal(true,QueryPaging.pageSql("SELECT * FROM entries ORDER BY id",DatabaseType.SQLSERVER,100,500).nativeOffset());
        equal(List.of(new QueryPaging.Order(0,false)),QueryPaging.order("SELECT id,name FROM entries ORDER BY id",List.of("id","name")).orders());
        equal(List.of(new QueryPaging.Order(1,true),new QueryPaging.Order(0,false)),QueryPaging.order("SELECT id,name FROM entries e ORDER BY e.name DESC, 1",List.of("id","name")).orders());
        equal(false,QueryPaging.order("SELECT id,name FROM entries ORDER BY LOWER(name)",List.of("id","name")).supported());
        var seekSql=QueryPaging.seekSql("SELECT id,name FROM entries ORDER BY id",DatabaseType.MYSQL,100,List.of("id","name"),List.of(new QueryPaging.Order(0,false)),List.of(),List.of(500));
        equal(false,seekSql.sql().contains("OFFSET 500"));equal(true,seekSql.sql().contains("`id` > ?"));equal(List.of(500),seekSql.parameters());
        equal("SELECT COUNT(*) FROM (SELECT id,name FROM entries) `__adisdb_count`",QueryPaging.countSql("SELECT id,name FROM entries ORDER BY id",DatabaseType.MYSQL));
        equal(true,QueryPaging.cheapCount("SELECT * FROM entries WHERE id>? ORDER BY id"));equal(false,QueryPaging.cheapCount("SELECT name,COUNT(*) FROM entries GROUP BY name"));
        equal(List.of(new QueryPaging.Order(0,true)),QueryPaging.reverse(List.of(new QueryPaging.Order(0,false))));
        try(JdbcSession session=JdbcSession.open(profile,new char[0])) {
            session.execute("CREATE TABLE entries(id INT PRIMARY KEY,name VARCHAR(20))",10);session.execute("INSERT INTO entries SELECT X,'row' FROM SYSTEM_RANGE(1,2505)",10);
            QueryResult page=session.execute("SELECT * FROM entries ORDER BY id",1000);equal(1000,page.rows().size());equal(true,page.truncated());
            Set<Object> seen=new HashSet<>();long offset=0;
            do {for(var row:page.rows())if(!seen.add(row.get(0)))throw new AssertionError("Duplicate paging row");offset+=page.rows().size();if(!page.truncated())break;page=session.executePage("SELECT * FROM entries ORDER BY id",1000,offset);}while(true);
            equal(2505,seen.size());equal(505,page.rows().size());equal(false,page.truncated());
            for(int size:List.of(100,500,1000,10000)){var result=session.executePage("SELECT * FROM entries ORDER BY id",size,1000);equal(Math.min(size,1505),result.rows().size());equal(1001,result.rows().get(0).get(0));if(result.editing()==null||result.editing().target()==null)throw new AssertionError("Page lost editing identity");}
            var limited=session.executePage("SELECT id FROM entries ORDER BY id LIMIT 7",3,6);equal(List.of(List.of(7)),limited.rows());equal(false,limited.truncated());
            equal(0,session.executePage("SELECT id FROM entries ORDER BY id",100,9000).rows().size());
            List<QueryPaging.Order> byId=List.of(new QueryPaging.Order(0,false));
            QueryResult seekFirst=session.executeSeekPage("SELECT * FROM entries ORDER BY id",List.of(),1000,List.of("ID","NAME"),byId,List.of());equal(1,seekFirst.rows().get(0).get(0));
            session.execute("INSERT INTO entries VALUES(0,'late')",1);
            QueryResult seekSecond=session.executeSeekPage("SELECT * FROM entries ORDER BY id",List.of(),1000,List.of("ID","NAME"),byId,List.of(1000));equal(1001,seekSecond.rows().get(0).get(0));equal(2000,seekSecond.rows().get(999).get(0));
            session.execute("DELETE FROM entries WHERE id=0",1);
            QueryResult seekDeep=session.executeSeekPage("SELECT * FROM entries WHERE id > ? ORDER BY id",List.of(2000),100,List.of("ID","NAME"),byId,List.of());equal(100,seekDeep.rows().size());equal(2001,seekDeep.rows().get(0).get(0));
            equal(2505L,session.countRows("SELECT * FROM entries ORDER BY id",List.of(),5));equal(505L,session.countRows("SELECT * FROM entries WHERE id > ? ORDER BY id",List.of(2000),5));
            JdbcSession firstReader=null,secondReader=null;try(JdbcSession.ReadLease first=session.borrowReadOnly();JdbcSession.ReadLease second=session.borrowReadOnly()){
                firstReader=first.session();secondReader=second.session();equal(true,firstReader.profile().readOnly());equal(true,secondReader.profile().readOnly());equal(false,firstReader==secondReader);equal(2505L,firstReader.countRows("SELECT * FROM entries",List.of(),5));equal(505L,secondReader.countRows("SELECT * FROM entries WHERE id>?",List.of(2000),5));
            }
            try(JdbcSession.ReadLease reused=session.borrowReadOnly()){equal(true,reused.session()==firstReader||reused.session()==secondReader);}
            QueryResult descending=session.executeSeekPage("SELECT * FROM entries ORDER BY id DESC",List.of(),3,List.of("ID","NAME"),List.of(new QueryPaging.Order(0,true)),List.of(2503));equal(List.of(2502,2501,2500),descending.rows().stream().map(row->row.get(0)).toList());
            try{QueryPaging.seekSql("SELECT * FROM entries ORDER BY id LIMIT 10",DatabaseType.H2,5,List.of("ID","NAME"),byId,List.of(),List.of());throw new AssertionError("Explicit limit accepted for seek paging");}catch(IllegalArgumentException expected){}
            try{QueryPaging.seekSql("SELECT * FROM entries ORDER BY id",DatabaseType.H2,5,List.of("ID","NAME"),byId,List.of(),Arrays.asList((Object)null));throw new AssertionError("NULL cursor accepted");}catch(IllegalArgumentException expected){}
            for(String sql:List.of("CALL proc()","EXEC dbo.proc","DELETE FROM entries","SELECT 1; DELETE FROM entries","SELECT * FROM entries FOR UPDATE")){equal(false,QueryPaging.supported(sql));try{session.executePage(sql,100,0);throw new AssertionError("Unsafe replay accepted");}catch(IllegalArgumentException expected){}}
            for(int size:List.of(0,10001))try{session.executePage("SELECT 1",size,0);throw new AssertionError("Invalid size accepted");}catch(IllegalArgumentException expected){}
            equal(2505,session.execute("SELECT id FROM entries",3000).rows().size());
        }
        System.out.println("QueryPagingTest: OK (offset/keyset paging, concurrent inserts, independent read pool, counts, filters, metadata and safe replay)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
