package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.model.*;
import javax.swing.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class QueryBatchTest {
    public static void main(String[] args) throws Exception {
        var profile=new ConnectionProfile("query-batch","Test",DatabaseType.H2,"",0,"mem:query_batch","sa","jdbc:h2:mem:query_batch","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try (JdbcSession session=JdbcSession.open(profile,new char[0])) {
            var busy=new AtomicBoolean();var reference=new AtomicReference<QueryPanel>();
            SwingUtilities.invokeAndWait(()->{var panel=new QueryPanel(s->{},busy::set,null);set(panel,"session",session);reference.set(panel);});
            QueryPanel panel=reference.get();
            for (int caret:List.of(0,9,18)) {
                run(panel,busy,"SELECT 1; SELECT 2",caret,-1);
                equal(List.of(1,2),values(panel));
            }
            run(panel,busy,"SELECT 1; SELECT 2; SELECT 3",10,18);equal(List.of(2),values(panel));
            run(panel,busy,"SELECT ';' AS marker; -- ignored ;\n SELECT 4; -- trailing comment",0,-1);equal(List.of(";",4),values(panel));
            run(panel,busy,"SELECT 5; SELECT * FROM missing_table; SELECT 6",0,-1);equal(List.of(5,6),values(panel));
            if (!field(panel,"messages",JTextArea.class).getText().contains("missing_table")) throw new AssertionError("Statement error missing");
            run(panel,busy,"SELECT 5;\n\nSELECT * FROM missing_table;\nSELECT 6",0,-1);SwingUtilities.invokeAndWait(()->{var editor=field(panel,"editor",SqlEditorPane.class);equal(Set.of(3),editor.errorLines().keySet());equal(3,editor.getDocument().getDefaultRootElement().getElementIndex(editor.getCaretPosition())+1);});
            String selected="SELECT 7;\n\n  SELECT * FROM missing_table;\nSELECT 8";run(panel,busy,selected,selected.indexOf("  SELECT"),selected.indexOf(";\nSELECT 8"));SwingUtilities.invokeAndWait(()->{var editor=field(panel,"editor",SqlEditorPane.class);equal(Set.of(3),editor.errorLines().keySet());editor.restoreText("SELECT 1");equal(true,editor.errorLines().isEmpty());});
            session.execute("CREATE TABLE writes(id INT PRIMARY KEY)",10);
            var confirmations=new AtomicInteger();
            SwingUtilities.invokeAndWait(()->{field(panel,"readOnly",JCheckBox.class).setSelected(false);set(panel,"confirm",(java.util.function.BiPredicate<String,String>)(title,text)->{confirmations.incrementAndGet();return true;});});
            run(panel,busy,"INSERT INTO writes VALUES(1); INSERT INTO writes VALUES(1); INSERT INTO writes VALUES(2)",0,-1);
            equal(1,confirmations.get());equal(List.of(List.of(1)),session.execute("SELECT id FROM writes",10).rows());
        }
        System.out.println("QueryBatchTest: OK (all statements at every caret, selection only, final statement without semicolon, separate results, read error continues, write error stops)");
    }

    private static void run(QueryPanel panel,AtomicBoolean busy,String sql,int start,int end) throws Exception {
        SwingUtilities.invokeAndWait(()->{var editor=field(panel,"editor",SqlEditorPane.class);editor.restoreText(sql);if(end<0)editor.setCaretPosition(Math.min(start,sql.length()));else editor.select(start,end);panel.executeCurrent();});
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (busy.get() && System.nanoTime()<deadline) Thread.sleep(20);
        SwingUtilities.invokeAndWait(()->{});
        if (busy.get()) throw new AssertionError("Query timed out");
    }

    private static List<Object> values(QueryPanel panel) throws Exception {
        var results=new AtomicReference<List<Object>>();
        SwingUtilities.invokeAndWait(()->{var values=new ArrayList<Object>();var tabs=field(panel,"outputTabs",JTabbedPane.class);for(int i=0;i<tabs.getTabCount();i++)if(tabs.getComponentAt(i) instanceof QueryResultPanel grid)values.add(grid.result().rows().get(0).get(0));results.set(values);});
        return results.get();
    }
    private static <T>T field(Object obj,String name,Class<T> type) {try {var field=obj.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(obj));}catch(Exception error){throw new RuntimeException(error);}}
    private static void set(Object obj,String name,Object value) {try {var field=obj.getClass().getDeclaredField(name);field.setAccessible(true);field.set(obj,value);}catch(Exception error){throw new RuntimeException(error);}}
    private static void equal(Object expected,Object actual) {if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
