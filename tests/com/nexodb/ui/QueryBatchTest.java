package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.MetadataFixture;
import com.nexodb.db.*;
import com.nexodb.model.*;
import javax.swing.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
            SwingUtilities.invokeAndWait(() -> {
                var tabs = field(panel, "outputTabs", JTabbedPane.class);
                equal(3, tabs.getTabCount());
                equal(tabs.getComponentAt(0), tabs.getSelectedComponent());
                equal(true, tabs.getComponentAt(1) instanceof QueryResultPanel);
                equal(tr("Mensajes"), tabs.getTitleAt(2));
            });
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
            SwingUtilities.invokeAndWait(() -> {
                var tabs = field(panel, "outputTabs", JTabbedPane.class);
                equal(3, tabs.getTabCount());
                equal(tr("Sentencia ") + 1, tabs.getTitleAt(0));
                equal(true, tabs.getComponentAt(1) instanceof SqlErrorPanel);
                equal(tr("Mensajes"), tabs.getTitleAt(2));
            });
        }
        verifySessionVariableBatch(DatabaseType.MYSQL, "MySQL", false);
        verifySessionVariableBatch(DatabaseType.GENERIC, "MariaDB", false);
        verifySessionVariableBatch(DatabaseType.MARIADB, "MariaDB", true);
        System.out.println("QueryBatchTest: OK (selection, separate SELECT results, DML and errors, one grid for a session-variable CTE)");
    }

    private static void verifySessionVariableBatch(DatabaseType profileType, String databaseProduct, boolean failAssignment) throws Exception {
        List<String> expected = List.of(
                "SET @empresa = 'EMP-DEMO'",
                "SET @fecha_desde = '2025-01-01'",
                "SET @fecha_hasta = '2025-12-31'",
                "SET @codigo = NULL",
                "-- NULL incluye todos los códigos.\nWITH kardex AS (SELECT @empresa AS empresa) SELECT empresa FROM kardex");
        List<String> executed = new ArrayList<>();
        var busy = new AtomicBoolean();
        var readOnly = new AtomicBoolean();
        var panelRef = new AtomicReference<QueryPanel>();
        DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(QueryBatchTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDatabaseProductName" -> databaseProduct;
                    default -> null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(QueryBatchTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "isClosed" -> false;
                    case "getAutoCommit" -> true;
                    case "isReadOnly" -> readOnly.get();
                    case "setReadOnly" -> { readOnly.set((Boolean) arguments[0]); yield null; }
                    case "createStatement" -> variableStatement(expected, executed, readOnly, failAssignment);
                    default -> null;
                });
        var profile = new ConnectionProfile("variable-batch", "Test", profileType, "", 0, "", "", "", "", "", true);
        var constructor = JdbcSession.class.getDeclaredConstructor(ConnectionProfile.class, Connection.class, java.net.URLClassLoader.class);
        constructor.setAccessible(true);
        try (var session = constructor.newInstance(profile, connection, null)) {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new QueryPanel(status -> {}, busy::set, null);
                set(panel, "session", session);
                panelRef.set(panel);
            });
            var panel = panelRef.get();
            String prefix = "DELETE FROM inventario;\n";
            String selected = String.join(";\n", expected) + ";";
            run(panel, busy, prefix + selected + "\nDROP TABLE inventario;", prefix.length(), prefix.length() + selected.length());
            if (failAssignment) {
                equal(expected.subList(0, 3), executed);
                equal(List.of(), values(panel));
                SwingUtilities.invokeAndWait(() -> {
                    var tabs = field(panel, "outputTabs", JTabbedPane.class);
                    equal(2, tabs.getTabCount());
                    equal(true, tabs.getSelectedComponent() instanceof SqlErrorPanel);
                    equal(tr("Mensajes"), tabs.getTitleAt(1));
                    String messages = field(panel, "messages", JTextArea.class).getText();
                    equal(true, messages.contains("Fixture rejected variable assignment"));
                    equal(Set.of(4), field(panel, "editor", SqlEditorPane.class).errorLines().keySet());
                });
            } else {
                equal(expected, executed);
                equal(List.of("EMP-DEMO"), values(panel));
                SwingUtilities.invokeAndWait(() -> {
                    var tabs = field(panel, "outputTabs", JTabbedPane.class);
                    equal(2, tabs.getTabCount());
                    equal(tabs.getComponentAt(0), tabs.getSelectedComponent());
                    equal(tr("Mensajes"), tabs.getTitleAt(1));
                    var grid = (QueryResultPanel) tabs.getSelectedComponent();
                    equal(session, grid.origin());
                    equal(false, field(grid, "reload", JButton.class).isEnabled());
                    equal(false, field(grid, "pageable", Boolean.class));
                    String messages = field(panel, "messages", JTextArea.class).getText();
                    for (int statement = 1; statement < expected.size(); statement++) {
                        String heading = "] " + tr("Sentencia ") + statement + "\n";
                        if (!messages.contains(heading)) throw new AssertionError("Missing SET completion: " + statement);
                    }
                });
            }
        }
    }

    private static Statement variableStatement(List<String> expected, List<String> executed, AtomicBoolean readOnly, boolean failAssignment) {
        AtomicReference<ResultSet> rows = new AtomicReference<>();
        AtomicInteger updateCount = new AtomicInteger(-1);
        return (Statement) Proxy.newProxyInstance(QueryBatchTest.class.getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "execute" -> {
                        equal(true, readOnly.get());
                        String sql = (String) arguments[0];
                        equal(expected.get(executed.size()), sql);
                        executed.add(sql);
                        if (failAssignment && executed.size() == 3) throw new SQLException("Fixture rejected variable assignment", "42000");
                        if (executed.size() == expected.size()) {
                            rows.set(MetadataFixture.rows(new String[]{"empresa"}, new Object[]{"EMP-DEMO"}));
                            yield true;
                        }
                        updateCount.set(0);
                        yield false;
                    }
                    case "getResultSet" -> rows.get();
                    case "getUpdateCount" -> updateCount.get();
                    case "getMoreResults" -> { updateCount.set(-1); yield false; }
                    default -> null;
                });
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
