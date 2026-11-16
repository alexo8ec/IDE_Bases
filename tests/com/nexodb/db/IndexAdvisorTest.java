package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.util.*;

public final class IndexAdvisorTest {
    public static void main(String[] args)throws Exception {
        equal("EXPLAIN ANALYZE FORMAT=TREE SELECT 1",QueryPlan.explainSql(DatabaseType.MYSQL,"SELECT 1",true));
        equal("ANALYZE FORMAT=JSON SELECT 1",QueryPlan.explainSql(DatabaseType.MARIADB,"SELECT 1",true));
        equal("EXPLAIN (ANALYZE TRUE, BUFFERS TRUE, FORMAT JSON) SELECT 1",QueryPlan.explainSql(DatabaseType.POSTGRESQL,"SELECT 1",true));
        var mysql=QueryPlan.parse(DatabaseType.MYSQL,"""
            {"query_block":{"table":{"table_name":"sales","access_type":"ALL","rows_examined_per_scan":900000}}}
            """,false,1);
        var mysqlAdvice=IndexAdvisor.suggest(DatabaseType.MYSQL,"SELECT * FROM sales s WHERE s.region = 'Costa' ORDER BY s.sale_date",mysql.root());
        equal(1,mysqlAdvice.size());contains(mysqlAdvice.get(0),"CREATE INDEX `idx_sales_region_sale_date` ON `sales` (`region`, `sale_date`)");

        var postgres=QueryPlan.parse(DatabaseType.POSTGRESQL,"""
            [{"Plan":{"Node Type":"Seq Scan","Relation Name":"orders","Plan Rows":50000,"Filter":"customer_id = 7"}}]
            """,false,1);
        var pgAdvice=IndexAdvisor.suggest(DatabaseType.POSTGRESQL,"SELECT * FROM public.orders o WHERE o.customer_id = 7",postgres.root());
        equal(1,pgAdvice.size());contains(pgAdvice.get(0),"CREATE INDEX \"idx_public_orders_customer_id\" ON \"public\".\"orders\" (\"customer_id\")");

        var sqlite=QueryPlan.parse(DatabaseType.SQLITE,"2\t0\t0\tSCAN people",false,1);
        var sqliteAdvice=IndexAdvisor.suggest(DatabaseType.SQLITE,"SELECT * FROM people WHERE email = ?",sqlite.root());
        equal(1,sqliteAdvice.size());contains(sqliteAdvice.get(0),"CREATE INDEX \"idx_people_email\" ON \"people\" (\"email\")");

        equal(0,IndexAdvisor.suggest(DatabaseType.MYSQL,"SELECT * FROM sales LIMIT 500",mysql.root()).size());
        contains(IndexAdvisor.emptyMessage(DatabaseType.MARIADB,"SELECT * FROM ct_clientes LIMIT 500"),"No se recomienda crear un índice");
        System.out.println("IndexAdvisorTest: OK (engine quoting, scan evidence, column order and no-index explanation)");
    }
    private static void contains(String text,String part){if(text==null||!text.contains(part))throw new AssertionError("Missing "+part+" in "+text);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
