package com.nexodb.ui;

import com.nexodb.util.SqlDraftStore;
import javax.swing.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class SqlDraftPersistenceTest {
    public static void main(String[] args)throws Exception {
        Path folder=Files.createTempDirectory("nexodb-draft-test-");Path file=folder.resolve("draft.properties");
        SqlDraftStore store=new SqlDraftStore(file);
        equal(Optional.empty(),store.load());String sql="-- mañana: áéíóú\nSELECT * FROM ct_ventas;\n\nUPDATE ejemplo SET texto='línea\\n=valor';\nselect ";
        store.save(new SqlDraftStore.Draft(sql,19));equal(new SqlDraftStore.Draft(sql,19),new SqlDraftStore(file).load().orElseThrow());
        AtomicReference<QueryPanel> restored=new AtomicReference<>();AtomicInteger operations=new AtomicInteger();
        SwingUtilities.invokeAndWait(()->{QueryPanel panel=new QueryPanel(text->{},busy->operations.incrementAndGet(),store);restored.set(panel);equal(sql,editor(panel).getText());equal(19,editor(panel).getCaretPosition());editor(panel).append("\nSELECT 'sin ejecutar';");});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);String expected=sql+"\nSELECT 'sin ejecutar';";
        while(System.nanoTime()<deadline&&!store.load().orElseThrow().text().equals(expected))Thread.sleep(20);
        equal(expected,store.load().orElseThrow().text());equal(0,operations.get());
        SwingUtilities.invokeAndWait(()->{equal(true,restored.get().saveDraftBeforeClose());QueryPanel reopened=new QueryPanel(text->{},busy->operations.incrementAndGet(),new SqlDraftStore(file));equal(expected,editor(reopened).getText());editor(reopened).setText("");equal(true,reopened.saveDraftBeforeClose());});
        equal("",store.load().orElseThrow().text());
        store.save(new SqlDraftStore.Draft("SELECT 1",999));equal(8,store.load().orElseThrow().caret());
        try(var files=Files.list(folder)){equal(List.of("draft.properties"),files.map(p->p.getFileName().toString()).toList());}
        System.out.println("SqlDraftPersistenceTest: OK (Unicode/multiline SQL, caret, autosave, restart, empty buffers and no execution)");
    }
    private static SqlEditorPane editor(QueryPanel panel){try{Field f=QueryPanel.class.getDeclaredField("editor");f.setAccessible(true);return (SqlEditorPane)f.get(panel);}catch(Exception e){throw new RuntimeException(e);}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
