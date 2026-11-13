package com.nexodb.db;

public final class QueryEditSqlTest {
    public static void main(String[] args){
        for(String sql:new String[]{
                "SELECT * FROM ct_ventas WHERE doc_electronico !=5 AND year(fecha)=2026;",
                "SELECT id, name AS title FROM db.people p WHERE name='JOIN FROM SELECT' ORDER BY id LIMIT 20",
                "-- comment\nSELECT p.* FROM `sales_db`.`people` AS p WHERE p.name LIKE '%x%'",
                "SELECT \"id\", \"from\" AS \"SELECT\" FROM \"table name\" WHERE \"from\"='x'",
                "SELECT [a].[id], [a].[value] v FROM [database].[dbo].[table] a WHERE id IN (1,2)",
                "SELECT t.id, t.name FROM t WHERE id > 1 /* JOIN ( SELECT */"})check(true,sql);
        for(String sql:new String[]{
                "SELECT a.id FROM a JOIN b ON a.id=b.id", "SELECT * FROM a, b WHERE a.id=b.id",
                "SELECT * FROM (SELECT * FROM a) t", "SELECT * FROM a WHERE id IN (SELECT id FROM b)",
                "SELECT DISTINCT id FROM a", "SELECT COUNT(*),id FROM a", "SELECT max(id) id FROM a",
                "SELECT name || 'x' AS name,id FROM a", "SELECT a.* FROM a UNION SELECT * FROM a",
                "WITH t AS (SELECT * FROM a) SELECT * FROM t", "SELECT id FROM a GROUP BY id",
                "SELECT id FROM a; UPDATE a SET id=2", "CALL get_rows()", "SHOW TABLES", "SELECT 1",
                "SELECT id, FROM a", "SELECT id INTO b FROM a", "SELECT * FROM a LEFT JOIN a b ON 1=1",
                "SELECT id FROM a INTERSECT SELECT id FROM a", "SELECT id FROM a WHERE name='unfinished"})check(false,sql);
        System.out.println("QueryEditSqlTest: OK (filtered SELECTs and aliases accepted; ambiguous shapes rejected)");
    }
    private static void check(boolean expected,String sql){if(QueryEditSupport.simpleSelect(sql)!=expected)throw new AssertionError("Unexpected eligibility: "+sql);}
}
