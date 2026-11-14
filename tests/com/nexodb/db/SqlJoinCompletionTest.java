package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.file.Path;
import java.util.*;

public final class SqlJoinCompletionTest {
    public static void main(String[] args)throws Exception{
        var profile=new ConnectionProfile("join-test","JOIN test",DatabaseType.H2,"",0,"mem:join_complete","sa","jdbc:h2:mem:join_complete","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE parent(tenant INT,id INT,PRIMARY KEY(tenant,id))",10);session.execute("CREATE TABLE child(id INT PRIMARY KEY,tenant INT,parent_id INT,CONSTRAINT fk_parent FOREIGN KEY(tenant,parent_id) REFERENCES parent(tenant,id))",10);session.execute("INSERT INTO parent VALUES(1,2)",10);session.execute("INSERT INTO child VALUES(3,1,2)",10);
            var completion=new SqlJoinCompletion(session);String prefix="SELECT * FROM child c JOIN ";var suggestions=completion.suggestions(prefix,prefix.length(),"PUBLIC");equal(1,suggestions.size());String insertion=suggestions.get(0).insertion();equal(true,insertion.contains(" AND "));equal(1,session.execute(prefix+insertion,100).rows().size());
            prefix="SELECT * FROM parent AS p LEFT JOIN ";suggestions=completion.suggestions(prefix,prefix.length(),"PUBLIC");equal(1,suggestions.size());equal(1,session.execute(prefix+suggestions.get(0).insertion(),100).rows().size());
            prefix="SELECT * FROM child c JOIN par";suggestions=completion.suggestions(prefix,prefix.length(),"PUBLIC");equal(1,suggestions.size());equal(true,suggestions.get(0).label().startsWith("PARENT"));
            prefix="DELETE FROM parent; SELECT * FROM child c JOIN ";suggestions=completion.suggestions(prefix,prefix.length(),"PUBLIC");equal(1,suggestions.size());equal(false,suggestions.get(0).insertion().contains("DELETE"));equal(1,session.execute("SELECT * FROM parent",10).rows().size());
            for(String text:List.of("SELECT 'JOIN ","-- SELECT * FROM child JOIN ","SELECT * FROM child WHERE id=1"))equal(0,completion.suggestions(text,text.length(),"PUBLIC").size());
            equal("v",SqlTableAliases.next("ct_ventas",List.of()));equal("c",SqlTableAliases.next("ct_clientes",List.of()));equal("c3",SqlTableAliases.next("ct_clientes",List.of("C","c2")));
            session.execute("CREATE TABLE ct_ventas(id INT PRIMARY KEY)",1);session.execute("INSERT INTO ct_ventas VALUES(7)",1);
            prefix="SELECT * FROM CT_V";var edit=SqlTableAliases.complete(prefix,prefix.length(),"CT_VENTAS","\"");equal("SELECT * FROM \"CT_VENTAS\" AS v",apply(prefix,edit));equal(1,session.execute(apply(prefix,edit),100).rows().size());
            prefix="SELECT * FROM PUBLIC.";edit=SqlTableAliases.complete(prefix,prefix.length(),"CT_VENTAS","\"");equal(1,session.execute(apply(prefix,edit),100).rows().size());
            prefix="SELECT CT_VENTAS.id FROM CT_V";edit=SqlTableAliases.complete(prefix,prefix.length(),"CT_VENTAS","\"");equal(1,session.execute(apply(prefix,edit),100).rows().size());
            prefix="SELECT * FROM CT_V AS invoices WHERE invoices.id=7";edit=SqlTableAliases.complete(prefix,prefix.indexOf(" AS"),"CT_VENTAS","\"");equal(1,session.execute(apply(prefix,edit),100).rows().size());equal(false,apply(prefix,edit).contains(" AS v"));
            prefix="SELECT * FROM CT_VENTAS c JOIN CT_V";edit=SqlTableAliases.complete(prefix,prefix.length(),"CT_VENTAS","\"");equal(true,apply(prefix,edit).endsWith(" AS v"));
            prefix="SELECT * FROM parent c JOIN ";suggestions=completion.suggestions(prefix,prefix.length(),"PUBLIC");equal(true,suggestions.get(0).insertion().contains(" AS \"c2\""));equal(1,session.execute(prefix+suggestions.get(0).insertion(),100).rows().size());
            prefix="SELECT * FROM parent p, child c JOIN ch";edit=SqlTableAliases.complete(prefix,prefix.length(),"CHILD","\"");equal(true,apply(prefix,edit).endsWith(" AS c2"));equal(1,session.execute(apply(prefix,edit)+" ON 1=1",100).rows().size());
            prefix="SELECT * FROM child c JOIN par AS custom";equal(0,completion.suggestions(prefix,prefix.indexOf(" AS custom"),"PUBLIC").size());
            for(String text:List.of("DELETE FROM CT_V","INSERT INTO CT_V","SELECT 'FROM CT_V","SELECT 1 -- FROM CT_V"))equal(null,SqlTableAliases.complete(text,text.length(),"CT_VENTAS","\""));
            prefix="DELETE FROM parent; SELECT * FROM CT_V";edit=SqlTableAliases.complete(prefix,prefix.length(),"CT_VENTAS","\"");equal(true,apply(prefix,edit).startsWith("DELETE FROM parent; SELECT"));equal(1,session.execute("SELECT * FROM parent",10).rows().size());
        }
        System.out.println("SqlJoinCompletionTest: OK (actual FK-based INNER/LEFT JOIN execution, composite ON order, aliases, prefixes, statement isolation and comment/string exclusion)");
    }
    private static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    private static String apply(String sql,SqlTableAliases.Edit edit){if(edit==null)throw new AssertionError("Missing alias completion: "+sql);return sql.substring(0,edit.start())+edit.insertion()+sql.substring(edit.end());}
}
