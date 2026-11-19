package com.nexodb.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.util.I18n;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class DataDictionaryPanelTest {
    public static void main(String[] args)throws Exception{
        FlatDarkLaf.setup();I18n.initialize("es");ConnectionProfile profile=new ConnectionProfile("dictionary-ui","Dictionary UI",DatabaseType.H2,"",0,"mem:dictionary_ui;DB_CLOSE_DELAY=-1","sa","jdbc:h2:mem:dictionary_ui;DB_CLOSE_DELAY=-1","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){session.execute("CREATE TABLE parent(id INT PRIMARY KEY,note VARCHAR(40))",1);session.execute("CREATE TABLE child(id INT PRIMARY KEY,parent_id INT,FOREIGN KEY(parent_id) REFERENCES parent(id))",1);DataDictionaryPanel[] ref=new DataDictionaryPanel[1];SwingUtilities.invokeAndWait(()->ref[0]=new DataDictionaryPanel(session,"PUBLIC",item->{},()->{}));DataDictionaryPanel panel=ref[0];await(()->field(panel,"worker",SwingWorker.class)==null);DefaultListModel<?> names=field(panel,"names",DefaultListModel.class);require(names.size()==2,"Dictionary table list missing");SwingUtilities.invokeAndWait(()->field(panel,"tables",JList.class).setSelectedIndex(0));await(()->field(panel,"worker",SwingWorker.class)==null);require(field(panel,"columnModel",DefaultTableModel.class).getRowCount()>0,"Columns not rendered");require(field(panel,"exportMarkdown",JButton.class).isEnabled(),"Export unavailable");SwingUtilities.invokeAndWait(panel::disposePanel);}
        System.out.println("DataDictionaryPanelTest: OK (dark UI, asynchronous summary and lazy structural details)");
    }
    private static void await(Check value)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){final boolean[] done={false};SwingUtilities.invokeAndWait(()->{try{done[0]=value.get();}catch(Exception error){throw new RuntimeException(error);}});if(done[0])return;Thread.sleep(25);}throw new AssertionError("Timed out");}
    @FunctionalInterface private interface Check{boolean get()throws Exception;}
    private static <T>T field(Object target,String name,Class<T> type){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(target));}catch(Exception error){throw new RuntimeException(error);}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
