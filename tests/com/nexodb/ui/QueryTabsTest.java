package com.nexodb.ui;

import com.nexodb.db.JdbcSession;
import com.nexodb.model.*;
import com.nexodb.util.*;
import javax.swing.*;
import javax.swing.text.StyleConstants;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class QueryTabsTest {
    public static void main(String[] args)throws Exception {
        I18n.initialize("es");Path file=Files.createTempDirectory("nexodb-tabs-").resolve("draft.properties");
        SqlDraftStore store=new SqlDraftStore(file);AtomicReference<QueryPanel> ref=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            QueryPanel panel=new QueryPanel(text->{},busy->{},store);ref.set(panel);equal("",editor(panel).getText());
            editor(panel).restoreText("DELETE FROM keep_me;\n");editor(panel).setCaretPosition(8);
            panel.insertSql("CREATE TABLE generated(id INT PRIMARY KEY);\nINSERT INTO generated VALUES(2);\n");
            equal(2,tabs(panel).getTabCount());equal(false,editor(panel).getText().contains("DELETE"));
            tabs(panel).setSelectedIndex(0);equal("DELETE FROM keep_me;\n",editor(panel).getText());equal(8,editor(panel).getCaretPosition());
            tabs(panel).setSelectedIndex(1);equal(true,panel.saveDraftBeforeClose());
            QueryPanel reopened=new QueryPanel(text->{},busy->{},store);equal(2,tabs(reopened).getTabCount());equal(1,tabs(reopened).getSelectedIndex());
            equal(editor(panel).getText(),editor(reopened).getText());tabs(reopened).setSelectedIndex(0);equal("DELETE FROM keep_me;\n",editor(reopened).getText());
            SqlEditorPane pane=editor(panel);pane.setText("");pane.append("year(");equal("YEAR(",pane.getText());pane.setText("");pane.append("declare ");equal("DECLARE ",pane.getText());
            pane.restoreText("SELECT 'year declare', `year`, \"declare\"; -- year declare\n");String original=pane.getText();pane.append(" ");equal(original+" ",pane.getText());
            pane.restoreText("year declare rank json_extract");try{Method highlight=SqlEditorPane.class.getDeclaredMethod("highlight");highlight.setAccessible(true);highlight.invoke(pane);}catch(Exception error){throw new RuntimeException(error);}
            equal(true,StyleConstants.isBold(pane.getStyledDocument().getCharacterElement(0).getAttributes()));equal(true,StyleConstants.isBold(pane.getStyledDocument().getCharacterElement(5).getAttributes()));
            @SuppressWarnings("unchecked") Set<String> completion=field(panel,"completionWords",Set.class);equal(true,completion.containsAll(List.of("YEAR","DECLARE","WHILE","JSON_EXTRACT")));
        });
        // Migrate only the exact old startup header, retaining all user SQL and custom comments.
        store.save(new SqlDraftStore.Draft("-- NexoDB Studio\n-- Select a query or place the cursor inside it and press Ctrl+Enter.\n\nSELECT 7;",100));
        SwingUtilities.invokeAndWait(()->equal("SELECT 7;",editor(new QueryPanel(text->{},busy->{},store)).getText()));
        store.save(new SqlDraftStore.Draft("-- NexoDB Studio\n-- comentario del usuario\nDELETE FROM x;",0));
        SwingUtilities.invokeAndWait(()->equal(true,editor(new QueryPanel(text->{},busy->{},store)).getText().contains("comentario del usuario")));
        Path driver=Path.of("build/h2-test.jar").toAbsolutePath();
        if(Files.exists(driver)){
            ConnectionProfile profile=new ConnectionProfile("tabs-test","Test",DatabaseType.H2,"",0,"mem:tabs","sa","jdbc:h2:mem:tabs","org.h2.Driver",driver.toString(),false);
            try(JdbcSession session=JdbcSession.open(profile,new char[0])){
                session.execute("CREATE TABLE keep_me(id INT PRIMARY KEY)",10);session.execute("INSERT INTO keep_me VALUES(1)",10);AtomicBoolean busy=new AtomicBoolean();AtomicReference<QueryPanel> active=new AtomicReference<>();
                SwingUtilities.invokeAndWait(()->{
                    QueryPanel panel=new QueryPanel(text->{},busy::set,null);active.set(panel);try{Field f=QueryPanel.class.getDeclaredField("session");f.setAccessible(true);f.set(panel,session);}catch(Exception error){throw new RuntimeException(error);}
                    editor(panel).restoreText("DELETE FROM keep_me;");panel.insertSql("CREATE TABLE generated(id INT);\nINSERT INTO generated VALUES(2);");
                    equal(1,tabs(panel).getSelectedIndex());equal(false,busy.get());editor(panel).selectAll();panel.executeCurrent();
                });
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(busy.get()&&System.nanoTime()<deadline)Thread.sleep(20);SwingUtilities.invokeAndWait(()->{});
                equal(false,busy.get());equal(1,session.execute("SELECT * FROM keep_me",10).rows().size());equal(1,session.execute("SELECT * FROM generated",10).rows().size());
            }
        }
        System.out.println("QueryTabsTest: OK (isolated generated scripts, original DELETE never executed, all tabs restored, legacy header migration, keyword highlighting/casing/completion)");
    }
    private static SqlEditorPane editor(QueryPanel p){return field(p,"editor",SqlEditorPane.class);}
    private static JTabbedPane tabs(QueryPanel p){return field(p,"queryTabs",JTabbedPane.class);}
    private static <T>T field(Object obj,String name,Class<T> type){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}catch(Exception error){throw new RuntimeException(error);}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
