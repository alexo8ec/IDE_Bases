package com.nexodb.ui;

import com.nexodb.util.*;
import javax.swing.*;
import java.lang.reflect.*;
import java.awt.event.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;

public final class SqlDocumentTest {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("nexodb-documents-");Path sql=SqlFiles.withExtension(root.resolve("mañana"));equal("mañana.sql",sql.getFileName().toString());
        String text="SELECT 'Mañana 😀';\nDECLARE x INT;\n";String disk=SqlFiles.write(sql,text);equal(text,SqlFiles.read(sql).text());equal(disk,SqlFiles.diskHash(sql));
        Path utf16=root.resolve("utf16.sql");byte[] encoded=text.getBytes(StandardCharsets.UTF_16LE);byte[] bytes=new byte[encoded.length+2];bytes[0]=(byte)255;bytes[1]=(byte)254;System.arraycopy(encoded,0,bytes,2,encoded.length);Files.write(utf16,bytes);equal(text,SqlFiles.read(utf16).text());
        Path draftFile=root.resolve("draft.properties");SqlDraftStore drafts=new SqlDraftStore(draftFile);
        SwingUtilities.invokeAndWait(()->{try{
            QueryPanel panel=new QueryPanel(t->{},b->{},drafts);equal(true,panel.openSqlLoaded(SqlFiles.read(sql)));SqlEditorPane first=editor(panel);equal(false,panel.isDirty(first));
            first.append("-- comentario");equal(true,panel.isDirty(first));String changed=first.getText();invoke(first,"highlight");key(first,KeyEvent.VK_Z,KeyEvent.CTRL_DOWN_MASK);equal(text,first.getText());equal(false,panel.isDirty(first));key(first,KeyEvent.VK_Y,KeyEvent.CTRL_DOWN_MASK);equal(changed,first.getText());
            panel.openNewQuery("SELECT 2;\n");SqlEditorPane second=editor(panel);second.append("year(");equal(true,second.getText().endsWith("YEAR("));key(second,KeyEvent.VK_Z,KeyEvent.CTRL_DOWN_MASK);equal("SELECT 2;\n",second.getText());equal(changed,first.getText());
            second.append("declare ");equal(true,second.getText().endsWith("DECLARE "));key(second,KeyEvent.VK_Z,KeyEvent.CTRL_DOWN_MASK);equal("SELECT 2;\n",second.getText());
            second.append("-- branch");equal(false,second.canRedoEdit());
            second.editAtomically(()->{try{second.getDocument().remove(0,6);second.getDocument().insertString(0,"UPDATE",null);}catch(Exception error){throw new RuntimeException(error);}});key(second,KeyEvent.VK_Z,KeyEvent.CTRL_DOWN_MASK);equal("SELECT 2;\n-- branch",second.getText());
            equal("save-sql",second.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_S,KeyEvent.CTRL_DOWN_MASK)));equal("export-csv",second.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_E,KeyEvent.CTRL_DOWN_MASK|KeyEvent.SHIFT_DOWN_MASK)));
            equal(true,panel.saveDraftBeforeClose());QueryPanel reopened=new QueryPanel(t->{},b->{},drafts);JTabbedPane tabs=field(reopened,"queryTabs",JTabbedPane.class);tabs.setSelectedIndex(1);equal(changed,editor(reopened).getText());equal(true,reopened.isDirty(editor(reopened)));equal(true,tabs.getTitleAt(1).contains("mañana.sql"));
            editor(reopened).undoEdit();equal(changed,editor(reopened).getText());
        }catch(Exception error){throw new RuntimeException(error);}});
        try(var paths=Files.list(root)){equal(true,paths.noneMatch(p->p.toString().endsWith(".tmp")));}
        System.out.println("SqlDocumentTest: OK (UTF-8/UTF-16/BOM, SQL file round trip, per-tab undo/redo, syntax ignored, keyword normalization atomic, save shortcuts, modified markers and file association after restart)");
    }
    private static void key(SqlEditorPane editor,int key,int mask){Object name=editor.getInputMap().get(KeyStroke.getKeyStroke(key,mask));editor.getActionMap().get(name).actionPerformed(new ActionEvent(editor,0,name.toString()));}
    private static void invoke(Object target,String name)throws Exception{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}
    private static SqlEditorPane editor(QueryPanel p){return field(p,"editor",SqlEditorPane.class);}
    private static <T>T field(Object obj,String name,Class<T> type){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}catch(Exception error){throw new RuntimeException(error);}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
