package com.nexodb.util;

import com.nexodb.model.DatabaseType;
import com.nexodb.ui.SqlEditorPane;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

public final class SqlFormatterTest {
    public static void main(String[] args) throws Exception {
        String view="create algorithm=undefined definer=`factural`@`186.3.217.69` sql security definer view `db`.`view_compras1` as (select c.id as id,c.nombre as nombre,sum(d.total) as total from compras c left join detalle d on d.compra_id=c.id where c.nota='select from' and c.activo=1 group by c.id,c.nombre order by c.nombre);";
        String formatted=SqlFormatter.format(view,DatabaseType.MYSQL);
        contains(formatted,"CREATE ALGORITHM = UNDEFINED DEFINER = `factural`@`186.3.217.69` SQL SECURITY DEFINER VIEW");
        contains(formatted,"SELECT\n        c.id AS id,\n        c.nombre AS nombre,\n        SUM(d.total) AS total");
        contains(formatted,"\n    FROM compras c\n    LEFT JOIN detalle d\n        ON d.compra_id = c.id");
        contains(formatted,"'select from'");
        equal(formatted,SqlFormatter.format(formatted,DatabaseType.MYSQL));

        String routine="DELIMITER $$\ncreate procedure p() begin if x=1 then select 'select from' as sample,x as value; -- SELECT inside comment\nelse select 2; end if; end$$\nDELIMITER ;";
        String procedure=SqlFormatter.format(routine,DatabaseType.MYSQL);
        contains(procedure,"DELIMITER $$");
        contains(procedure,"CREATE PROCEDURE p()");
        contains(procedure,"IF x = 1 THEN");
        contains(procedure,"ELSE");
        contains(procedure,"END IF;");
        contains(procedure,"-- SELECT inside comment");
        contains(procedure,"'select from'");
        equal(procedure,SqlFormatter.format(procedure,DatabaseType.MYSQL));

        String function="create function f(x int) returns int begin if x>0 then return x; else return 0; end if; end";
        String formattedFunction=SqlFormatter.format(function,DatabaseType.MYSQL);
        contains(formattedFunction,"CREATE FUNCTION f(x INT) RETURNS INT");
        contains(formattedFunction,"IF x > 0 THEN");
        contains(formattedFunction,"END IF;");
        equal("select 'unterminated",SqlFormatter.format("select 'unterminated",DatabaseType.MYSQL));

        SwingUtilities.invokeAndWait(()->{
            SqlEditorPane editor=new SqlEditorPane();editor.setDialect(DatabaseType.MYSQL);
            String original="select a,b from t where a=1;\nselect c,d from u;";
            editor.restoreText(original);editor.select(0,31);
            Object action=editor.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_F,KeyEvent.CTRL_DOWN_MASK|KeyEvent.SHIFT_DOWN_MASK));
            equal("format-sql",action);editor.getActionMap().get(action).actionPerformed(new ActionEvent(editor,0,"format-sql"));
            contains(editor.getText(),"SELECT\n    a,\n    b\nFROM t");
            contains(editor.getText(),"select c,d from u;");
            editor.undoEdit();equal(original,editor.getText());
            editor.select(0,0);editor.formatSql();String once=editor.getText();editor.formatSql();equal(once,editor.getText());
            editor.undoEdit();equal(original,editor.getText());
        });
        System.out.println("SqlFormatterTest: OK (view, query, procedure, function, literals/comments, selection, undo and idempotence)");
    }

    private static void contains(String value,String expected){if(!value.contains(expected))throw new AssertionError("Missing ["+expected+"] in:\n"+value);}
    private static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
