package com.nexodb.ui;

import com.nexodb.db.JdbcSession;
import com.nexodb.model.*;
import com.nexodb.util.*;
import javax.swing.*;
import javax.swing.text.StyleConstants;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

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
                    try{Field mode=QueryPanel.class.getDeclaredField("readOnly");mode.setAccessible(true);((JCheckBox)mode.get(panel)).setSelected(false);Field confirm=QueryPanel.class.getDeclaredField("confirm");confirm.setAccessible(true);confirm.set(panel,(java.util.function.BiPredicate<String,String>)(title,text)->true);}catch(Exception error){throw new RuntimeException(error);}
                    equal(1,tabs(panel).getSelectedIndex());equal(false,busy.get());editor(panel).selectAll();panel.executeCurrent();
                });
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(busy.get()&&System.nanoTime()<deadline)Thread.sleep(20);SwingUtilities.invokeAndWait(()->{});
                equal(false,busy.get());equal(1,session.execute("SELECT * FROM keep_me",10).rows().size());equal(1,session.execute("SELECT * FROM generated",10).rows().size());
                verifyRetainedResults(session,file.resolveSibling("consulta.sql"));
            }
        }
        verifySearchAndSelection();
        System.out.println("QueryTabsTest: OK (isolated queries and retained results, all tabs restored, legacy header migration, search and highlighting)");
    }

    private static void verifyRetainedResults(JdbcSession session, Path savedSql) throws Exception {
        String firstSql = "SELECT id FROM keep_me";
        String secondSql = "SELECT id FROM generated";
        Files.writeString(savedSql, firstSql);
        String diskHash = SqlFiles.diskHash(savedSql);
        AtomicReference<QueryPanel> reference = new AtomicReference<>();
        AtomicBoolean busy = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            QueryPanel panel = new QueryPanel(text -> {}, busy::set, null);
            try {
                Field field = QueryPanel.class.getDeclaredField("session");
                field.setAccessible(true);
                field.set(panel, session);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            editor(panel).restoreText(firstSql);
            panel.markSaved(editor(panel), savedSql, firstSql, diskHash);
            reference.set(panel);
        });
        QueryPanel panel = reference.get();
        executeAndWait(panel, busy);
        AtomicReference<String> firstMessages = new AtomicReference<>();
        AtomicReference<QueryPanel.ReportSource> firstReport = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            equal(List.of(List.of(1)), selectedResult(panel).result().rows());
            firstMessages.set(messages(panel).getText());
            firstReport.set(panel.currentReportSource());
            outputTabs(panel).setSelectedIndex(1);
            equal(true, panel.openNewQuery(secondSql));
            equal(1, outputTabs(panel).getTabCount());
            equal("", messages(panel).getText());
        });
        executeAndWait(panel, busy);
        // Switching to the first tab must display its snapshot even after the table changes.
        session.setReadOnly(false);
        session.execute("UPDATE keep_me SET id = 3", 10);
        AtomicReference<String> secondMessages = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            QueryResultPanel second = selectedResult(panel);
            equal(List.of(List.of(2)), second.result().rows());
            equal(List.of(List.of(BigDecimal.ONE)), firstReport.get().snapshots().get().get(0).rows());
            field(second, "table", JTable.class).setRowSelectionInterval(0, 0);
            secondMessages.set(messages(panel).getText());
            tabs(panel).setSelectedIndex(0);
            equal(2, outputTabs(panel).getTabCount());
            equal(1, outputTabs(panel).getSelectedIndex());
            equal(firstMessages.get(), messages(panel).getText());
            outputTabs(panel).setSelectedIndex(0);
            equal(List.of(List.of(1)), selectedResult(panel).result().rows());
        });
        executeAndWait(panel, busy);
        SwingUtilities.invokeAndWait(() -> {
            equal(List.of(List.of(3)), selectedResult(panel).result().rows());
            tabs(panel).setSelectedIndex(1);
            equal(List.of(List.of(2)), selectedResult(panel).result().rows());
            equal(0, field(selectedResult(panel), "table", JTable.class).getSelectedRow());
            equal(secondMessages.get(), messages(panel).getText());
            JPanel firstTabHeader = (JPanel) tabs(panel).getTabComponentAt(0);
            ((JButton) firstTabHeader.getComponent(1)).doClick(0);
            equal(1, tabs(panel).getTabCount());
            equal(List.of(), firstReport.get().snapshots().get());
            equal(secondSql, editor(panel).getText());
            equal(List.of(List.of(2)), selectedResult(panel).result().rows());
            equal(secondMessages.get(), messages(panel).getText());
        });
    }

    private static void executeAndWait(QueryPanel panel, AtomicBoolean busy) throws Exception {
        SwingUtilities.invokeAndWait(panel::executeCurrent);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (busy.get() && System.nanoTime() < deadline) Thread.sleep(20);
        SwingUtilities.invokeAndWait(() -> equal(false, busy.get()));
    }

    private static QueryResultPanel selectedResult(QueryPanel panel) {
        if (outputTabs(panel).getSelectedComponent() instanceof QueryResultPanel result) return result;
        throw new AssertionError("Expected the selected SQL tab to show a result grid");
    }

    private static JTextArea messages(QueryPanel panel) { return field(panel, "messages", JTextArea.class); }
    private static JTabbedPane outputTabs(QueryPanel panel) { return field(panel, "outputTabs", JTabbedPane.class); }

    private static void verifySearchAndSelection() throws Exception {
        String sql = "SELECT 'saldo total 007', 'SALDO TOTAL 007', 'saldo total 9', saldo_total100, 1007;";
        AtomicReference<QueryPanel> owner = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> owner.set(new QueryPanel(text -> {}, busy -> {}, null)));
        QueryPanel panel = owner.get();
        SqlEditorPane pane = editor(panel);
        SqlEditorSearchPanel search = (SqlEditorSearchPanel) tabs(panel).getSelectedComponent();
        JLabel selectionCounter = field(search, "selectionCounter", JLabel.class);
        JLabel counter = field(search, "counter", JLabel.class);
        JTextField term = field(search, "term", JTextField.class);
        SwingUtilities.invokeAndWait(() -> pane.restoreText(sql));

        for (String selected : List.of("saldo", "007", "saldo total")) {
            SwingUtilities.invokeAndWait(() -> pane.select(sql.indexOf(selected), sql.indexOf(selected) + selected.length()));
            awaitSearch(() -> selectionCounter.isVisible() && selectionCounter.getText().equals("2 coincidencias"));
            SwingUtilities.invokeAndWait(() -> {
                equal(2, pane.getHighlighter().getHighlights().length);
                equal(selected, pane.getSelectedText());
                equal(sql, pane.getText());
            });
        }

        SwingUtilities.invokeAndWait(() -> invokeKey(pane, KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK));
        awaitSearch(() -> counter.getText().equals("1 de 3"));
        SwingUtilities.invokeAndWait(() -> {
            equal("saldo total", term.getText());
            field(search, "previous", JButton.class).doClick(0);
        });
        awaitSearch(() -> counter.getText().equals("3 de 3"));
        SwingUtilities.invokeAndWait(term::postActionEvent);
        awaitSearch(() -> counter.getText().equals("1 de 3"));
        SwingUtilities.invokeAndWait(() -> {
            equal("saldo total", pane.getSelectedText());
            equal(sql, pane.getText());
            equal(false, pane.canUndoEdit());
            term.setText("saldo.*");
        });
        awaitSearch(() -> counter.getText().equals("Sin coincidencias"));

        SwingUtilities.invokeAndWait(() -> {
            invokeKey(pane, KeyEvent.VK_ESCAPE, 0);
            pane.setCaretPosition(pane.getSelectionEnd());
            equal(false, field(search, "findBar", JPanel.class).isVisible());
            equal(false, selectionCounter.isVisible());
            equal(0, pane.getHighlighter().getHighlights().length);
            pane.select(sql.indexOf("007"), sql.indexOf("007") + 3);
        });
        awaitSearch(selectionCounter::isVisible);
        SwingUtilities.invokeAndWait(() -> {
            panel.insertSql("SELECT 007;");
            equal(false, selectionCounter.isVisible());
            equal(0, pane.getHighlighter().getHighlights().length);
            equal(0, editor(panel).getHighlighter().getHighlights().length);
        });
    }

    private static void invokeKey(JComponent component, int key, int modifiers) {
        Object binding = component.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, modifiers));
        component.getActionMap().get(binding).actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, binding.toString()));
    }

    private static void awaitSearch(BooleanSupplier ready) throws Exception {
        AtomicBoolean completed = new AtomicBoolean(false);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            SwingUtilities.invokeAndWait(() -> completed.set(ready.getAsBoolean()));
            if (completed.get()) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("SQL search did not produce the expected result");
    }

    private static SqlEditorPane editor(QueryPanel p){return field(p,"editor",SqlEditorPane.class);}
    private static JTabbedPane tabs(QueryPanel p){return field(p,"queryTabs",JTabbedPane.class);}
    private static <T>T field(Object obj,String name,Class<T> type){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}catch(Exception error){throw new RuntimeException(error);}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
