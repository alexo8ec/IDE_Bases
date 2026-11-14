package com.nexodb.db;

import java.io.*;
import java.util.*;

public final class SqlScriptReaderTest {
    public static void main(String[] args)throws Exception{
        List<String> statements=read("-- comment\nUSE `test`;\ndelimiter $$\nCREATE PROCEDURE p() BEGIN DECLARE x INT; SET x=YEAR('2026-01-01'); SELECT 'a;$$b'; END$$\ndElImItEr ;\nSELECT 1--2;\nSELECT 'can\\'t;end';\n/*comment*/ /*!40101 SET @x=1 */;");
        equal(5,statements.size());equal(true,statements.get(1).contains("DECLARE x INT;"));equal("SELECT 1--2",statements.get(2));
        equal(List.of("SELECT $$a;b$$","SELECT $x$c;d$x$"),read("SELECT $$a;b$$; SELECT $x$c;d$x$;"));
        equal(2,read("SET SQL_MODE='NO_BACKSLASH_ESCAPES'; SELECT 'abc\\';").size());
        equal(1,read("\uFEFFSELECT 1;").size());
        equal(3,read("SET SQL_MODE='NO_BACKSLASH_ESCAPES'; INSERT INTO t VALUES('SQL_MODE=''ANSI_QUOTES'''); SELECT 'abc\\';").size());
        equal(4,read("SET SQL_MODE='NO_BACKSLASH_ESCAPES'; SET @old=@@SQL_MODE, SQL_MODE=''; SET SQL_MODE=@old; SELECT 'abc\\';").size());
        try{read("SELECT 'open");throw new AssertionError("Unclosed string accepted");}catch(IOException expected){}
        equal("new",SqlScriptTools.databaseDirective("/*!50001 CREATE DATABASE IF NOT EXISTS `new` */"));
        equal(Set.of("source"),SqlScriptTools.referencedDatabases("CREATE VIEW `v` AS SELECT a.id FROM `source`.`t` a"));
        equal("CREATE VIEW v AS SELECT 'source.t', `target`.`t`.id FROM `target`.`t` -- source.t",SqlScriptTools.remap("CREATE VIEW v AS SELECT 'source.t', `source`.`t`.id FROM `source`.`t` -- source.t",Set.of("source"),"target"));
        equal("SELECT \"source\" FROM `target`.\"table\"",SqlScriptTools.remap("SELECT \"source\" FROM \"source\".\"table\"",Set.of("source"),"target"));
        equal("CREATE VIEW v AS SELECT 'DEFINER=`u`@`h`'",SqlScriptTools.withoutDefiner("CREATE DEFINER=`u`@`h` VIEW v AS SELECT 'DEFINER=`u`@`h`'"));
        equal("/*!50003 CREATE*/ /*!50017 */ /*!50003 TRIGGER x BEFORE INSERT ON t FOR EACH ROW SET @x=1 */",SqlScriptTools.withoutDefiner("/*!50003 CREATE*/ /*!50017 DEFINER=`root`@`localhost` */ /*!50003 TRIGGER x BEFORE INSERT ON t FOR EACH ROW SET @x=1 */"));
        var item=new SchemaItem(SchemaItem.Kind.PROCEDURE,"db",null,"p","");String ddl="CREATE PROCEDURE `db`.`p`()\nBEGIN\n SELECT 1;\nEND";String script=StoredProgramScript.editor(item,ddl,true);var body=StoredProgramScript.body(item,script);equal(ddl,body.sql());equal(script.indexOf("CREATE"),body.offset());equal(ddl,StoredProgramScript.body(item,ddl).sql());
        equal(ddl,StoredProgramScript.body(item,ddl+"$$").sql());equal(ddl,StoredProgramScript.body(item,ddl+"$$\nDELIMITER ;").sql());equal(ddl,StoredProgramScript.body(item,ddl+"\nDELIMITER ;").sql());equal(ddl+";",StoredProgramScript.body(item,ddl+";\n$$").sql());String dollars=ddl.replace("SELECT 1","SELECT '$$' /* $$ dentro de comentario */");equal(dollars,StoredProgramScript.body(item,dollars+"$$").sql());equal(0,StoredProgramScript.body(item,ddl+"$$").offset());
        for(String invalid:List.of(script.replace("USE `db`","USE `other`"),script.replace("DROP PROCEDURE IF EXISTS `db`.`p`","DROP PROCEDURE IF EXISTS `db`.`other`"),script+"SELECT 2;"))try{StoredProgramScript.body(item,invalid);throw new AssertionError("Unexpected script commands accepted");}catch(IOException expected){}
        String noEscape="CREATE PROCEDURE `db`.`p`() BEGIN SELECT 'abc\\'; END";equal(noEscape,StoredProgramScript.body(item,StoredProgramScript.editor(item,noEscape,false),"NO_BACKSLASH_ESCAPES").sql());
        var error=new java.sql.SQLException("You have an error in your SQL syntax near '' at line 4","42000",1064);var detail=SqlDiagnostic.analyze(error,body.sql(),script,body.offset());equal(10,detail.line());equal(true,detail.precise());if(!detail.suggestion().contains("END IF")||!detail.display().contains("1064"))throw new AssertionError("Missing actionable syntax explanation");
        var selection=SqlDiagnostic.analyze(new java.sql.SQLException("at line 2","42000",1064),"SELECT\n wrong","SELECT 1;\n\nSELECT\n wrong",11);equal(4,selection.line());
        var fallback=SqlDiagnostic.analyze(new java.sql.SQLException("Unknown column 'x'","42S22",1054),"SELECT x","\nSELECT x",1);equal(false,fallback.precise());equal(2,fallback.line());if(!fallback.explanation().contains("columna"))throw new AssertionError("Column explanation missing");
        String unicode="SELECT '😀';\nBAD";int codePointPosition=unicode.codePointCount(0,unicode.indexOf("BAD"))+1;equal(2,SqlDiagnostic.analyze(new java.sql.SQLException("Position: "+codePointPosition,"42601"),unicode,unicode,0).line());
        System.out.println("SqlScriptReaderTest: OK (SQLyog editor scripts, directives, SQL modes, exact source mapping and diagnostic explanations)");
    }
    private static List<String> read(String text)throws Exception{List<String> result=new ArrayList<>();try(SqlScriptReader reader=new SqlScriptReader(new StringReader(text))){SqlScriptReader.Statement s;while((s=reader.next())!=null)result.add(s.sql());}return result;}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
