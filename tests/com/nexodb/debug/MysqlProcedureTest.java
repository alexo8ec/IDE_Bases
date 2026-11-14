package com.nexodb.debug;

import com.nexodb.db.*;
import java.util.*;

public final class MysqlProcedureTest {
    public static void main(String[] args){
        String sql="CREATE PROCEDURE `p`(IN `ñ` DECIMAL(12,2), INOUT b VARCHAR(100))\nBEGIN\n DECLARE x,y INT DEFAULT 0;\n SET b='a;b -- not comment';\n /* SET fake=1; */\n BEGIN\n DECLARE x INT DEFAULT 9;\n SET y=x;\n END;\n SET y=x;\nEND";
        var p=MysqlProcedure.compile(new StoredObjectService.Definition(sql,"NO_BACKSLASH_ESCAPES","utf8mb4","utf8mb4_general_ci"));equal(2,p.parameters().size());equal("DECIMAL(12,2)",p.parameters().get(0).type());equal("ñ",p.parameters().get(0).name());
        var inner=p.points().stream().filter(point->point.line()==8).findFirst().orElseThrow();var outer=p.points().stream().filter(point->point.line()==10).findFirst().orElseThrow();String a=inner.variables().stream().filter(v->v.name().equals("x")).findFirst().orElseThrow().scope(),b=outer.variables().stream().filter(v->v.name().equals("x")).findFirst().orElseThrow().scope();if(a.equals(b))throw new AssertionError("Shadowed variable scope lost");
        if(p.executableLines().contains(3)||p.executableLines().contains(5)||p.executableLines().contains(7))throw new AssertionError("Declarations/comments must not be executable points");
        String clone=p.instrument("`db`.`copy`","`db`.`pause`");if(!clone.contains("PROCEDURE `db`.`copy`")||!clone.contains("CALL `db`.`pause`")||!clone.contains("'a;b -- not comment'"))throw new AssertionError("Body changed by instrumentation");
        for(String forbidden:List.of("ROW_COUNT()","FOUND_ROWS()","PREPARE s FROM 'SELECT 1'"))try{MysqlProcedure.compile(new StoredObjectService.Definition("CREATE PROCEDURE p() BEGIN SELECT "+forbidden+"; END","","",""));throw new AssertionError("Unsafe diagnostics accepted");}catch(IllegalArgumentException expected){}
        for(var kind:List.of(SchemaItem.Kind.VIEW,SchemaItem.Kind.PROCEDURE,SchemaItem.Kind.FUNCTION,SchemaItem.Kind.TRIGGER,SchemaItem.Kind.EVENT)){var item=new SchemaItem(kind,"base extra",null,"nombre` especial","");String template=StoredObjectService.template(item);equal(item.name(),StoredObjectService.header(template,kind).name());if(!StoredObjectService.dropSql(item).contains("`nombre`` especial`"))throw new AssertionError("Identifier escaping lost");}
        var values=List.of(new MysqlDebugSession.Value("x","INT","bloque","7",false,false),new MysqlDebugSession.Value("@session","sesión","sesión","hola",false,false));String hover="SET `x` = x; SELECT 'x'; -- x\nSET @session = 2;";var tokens=MysqlProcedure.lex(hover,"");equal("7",DebugInspection.valueAt(tokens,hover.indexOf("`x`")+1,values).value());equal(null,DebugInspection.valueAt(tokens,hover.indexOf("'x'")+1,values));equal(null,DebugInspection.valueAt(tokens,hover.indexOf("-- x")+3,values));equal("hola",DebugInspection.valueAt(tokens,hover.indexOf("@session")+2,values).value());
        var json=new MysqlDebugSession.Value("rows","JSON","bloque","[{\"id\":1,\"text\":\"<html>\"},{\"id\":2,\"other\":null}]",false,false);var table=DebugInspection.table(json);equal(List.of("id","text","other"),table.columns());equal(null,table.rows().get(1).get(1));equal("<html>",table.rows().get(0).get(1));equal(null,DebugInspection.table(new MysqlDebugSession.Value("rows","JSON","bloque",json.value(),false,true)));
        var example=MysqlProcedure.compile(new StoredObjectService.Definition(DebugExample.sql("`db`.`test`"),"","",""));int fromLine=1+(int)example.source().substring(0,example.source().indexOf("FROM (SELECT")).chars().filter(c->c=='\n').count();equal(fromLine-1,example.breakpointLine(fromLine));equal(-1,example.breakpointLine(4));
        System.out.println("MysqlProcedureTest: OK (instrumentation, hover scope/token boundaries, JSON tables and example compilation)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
