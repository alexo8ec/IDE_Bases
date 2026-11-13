package com.nexodb.db;

import com.nexodb.MetadataFixture;
import com.nexodb.model.DatabaseType;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.nexodb.MetadataFixture.equal;

public final class SqlColumnCompletionTest {
    public static void main(String[] args)throws Exception {
        AtomicInteger reads=new AtomicInteger();
        JdbcSession session=MetadataFixture.session("columns",DatabaseType.MYSQL,(proxy,method,values)->switch(method.getName()){
            case "storesUpperCaseIdentifiers","storesLowerCaseIdentifiers" -> false;
            case "getColumns" -> {
                reads.incrementAndGet();String table=values[2].toString();
                if(table.equals("odd\\_\\%"))yield MetadataFixture.rows(new String[]{"COLUMN_NAME","ORDINAL_POSITION"},new Object[]{"strange",1});
                if(table.equals("items")){equal("db2",values[0]);yield MetadataFixture.rows(new String[]{"COLUMN_NAME","ORDINAL_POSITION"},new Object[]{"item_id",1});}
                equal("people",table);
                yield MetadataFixture.rows(new String[]{"COLUMN_NAME","ORDINAL_POSITION"},new Object[]{"full name",2},new Object[]{"id",1});
            }
            default -> throw new AssertionError(method.getName());
        });
        SqlColumnCompletion provider=new SqlColumnCompletion(session);
        equal(List.of("id","full name"),provider.columns("SELECT p. FROM people p","p","db1",List.of()));equal(1,reads.get());
        equal(List.of("id","full name"),provider.columns("UPDATE people SET na","","db1",List.of()));equal(1,reads.get());
        equal(List.of("id","full name"),provider.columns("people.","people","db1",List.of("people")));equal(1,reads.get());
        equal(List.of(),provider.columns("SELECT p. FROM people p","other","db1",List.of()));equal(1,reads.get());
        equal(List.of("item_id"),provider.columns("SELECT b. FROM db1.people a JOIN db2.items b ON a.id=b.item_id","b","db1",List.of()));equal(2,reads.get());
        equal(List.of("strange"),provider.columns("SELECT t. FROM db1.`odd_%` t","t","db1",List.of()));equal(3,reads.get());
        equal("`full name`",provider.identifier("full name"));equal("`a``b`",provider.identifier("a`b"));
        provider.columns("SELECT * FROM people WHERE id=1","","db2",List.of());equal(4,reads.get());
        provider.clear();provider.columns("SELECT * FROM people","","db1",List.of());equal(5,reads.get());
        equal(false,SqlColumnCompletion.inCode("SELECT 'FROM people"));equal(false,SqlColumnCompletion.inCode("SELECT /* unfinished"));
        System.out.println("SqlColumnCompletionTest: OK (aliases, tables, schemas, escaped names, cache reuse, invalidation and database isolation)");
    }
}
