package com.nexodb.db;

import java.util.*;

public final class MysqlTableDesignTest {
    public static void main(String[] args){
        String ddl="CREATE TABLE `t` (`id` int NOT NULL, `a``b` enum('a,b','DEFAULT') DEFAULT 'x; COMMENT' COMMENT 'DEFAULT 2', `g` int GENERATED ALWAYS AS (coalesce(`id`,2)) STORED, PRIMARY KEY (`id`)) ENGINE=InnoDB";
        var definitions=MysqlTableDesign.columnDefinitions(ddl,false);equal(3,definitions.size());equal("'x; COMMENT'",MysqlTableDesign.defaultExpression(definitions.get("a`b"),false));equal("",MysqlTableDesign.defaultExpression(definitions.get("g"),false));
        equal("(json_object('a', 2))",MysqlTableDesign.defaultExpression("json DEFAULT (json_object('a', 2)) COMMENT 'a'",false));
        var id=column("id","INT","","","");var exotic=column("special","INT","","","special engine attribute");
        var options=new MysqlTableDesign.Options("InnoDB","utf8mb4","utf8mb4_unicode_ci","","","DYNAMIC","","","","","");
        var base=new MysqlTableDesign.Snapshot("db","t",ddl,List.of(id,exotic),List.of(),List.of(),options,List.of("InnoDB"),Map.of(),false);
        var added=column("added","VARCHAR","80","'a; b'","");
        String insert=MysqlTableDesign.plan(base,List.of(new MysqlTableDesign.ColumnChange("id",id),new MysqlTableDesign.ColumnChange(null,added),new MysqlTableDesign.ColumnChange("special",exotic)),List.of(),List.of(),options);
        equal(true,insert.contains("ADD COLUMN `added`"));equal(false,insert.contains("CHANGE COLUMN `special`"));
        var renamed=column("new_id","INT","","","");String rename=MysqlTableDesign.plan(base,List.of(new MysqlTableDesign.ColumnChange("id",renamed),new MysqlTableDesign.ColumnChange("special",exotic)),List.of(),List.of(),options);equal(false,rename.contains("CHANGE COLUMN `special`"));
        for(String bad:List.of("0; DROP TABLE x","0, DROP COLUMN id","1 /* comment */","(1","'unterminated")){
            boolean denied=false;try{MysqlTableDesign.columnSql(column("v","VARCHAR","80",bad,""),false);}catch(IllegalArgumentException expected){denied=true;}equal(true,denied);
        }
        equal(true,MysqlTableDesign.columnSql(column("v","DECIMAL","12,2","1.25",""),false).contains("DECIMAL(12,2)"));
        equal(true,MysqlTableDesign.columnSql(added,false).contains("DEFAULT 'a; b'"));
        equal("'a''b\\\\c'",MysqlTableDesign.literal("a'b\\c",false));equal("'a''b\\c'",MysqlTableDesign.literal("a'b\\c",true));
        System.out.println("MysqlTableDesignTest: OK (quoted commas/keywords/defaults, generated expressions, safe insertion/rename around exotic columns, expression boundaries and SQL-mode quoting)");
    }
    private static MysqlTableDesign.Column column(String name,String type,String length,String def,String unsupported){return new MysqlTableDesign.Column(name,type,length,false,false,false,false,def,"","","","","","",false,unsupported);}
    private static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
}
