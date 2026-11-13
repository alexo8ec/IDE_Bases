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
        System.out.println("SqlScriptReaderTest: OK (DELIMITER routines, quotes, comments, SQL modes, source remapping and DEFINER)");
    }
    private static List<String> read(String text)throws Exception{List<String> result=new ArrayList<>();try(SqlScriptReader reader=new SqlScriptReader(new StringReader(text))){SqlScriptReader.Statement s;while((s=reader.next())!=null)result.add(s.sql());}return result;}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
