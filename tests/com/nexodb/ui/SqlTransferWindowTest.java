package com.nexodb.ui;

import com.formdev.flatlaf.*;
import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.util.I18n;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** UI smoke tests use only the isolated transfer test database. Run after SqlTransferIntegrationTest. */
public final class SqlTransferWindowTest {
    public static void main(String[] args)throws Exception{
        if(!Boolean.getBoolean("nexodb.test.transfer"))throw new IllegalStateException("Isolated local database test only");
        FlatLightLaf.setup();I18n.initialize("es");
        ConnectionProfile profile=new ConnectionProfile("transfer-ui-test","Local isolated test",DatabaseType.MARIADB,"127.0.0.1",43179,"nexodb_transfer_source","root","jdbc:mariadb://127.0.0.1:43179/nexodb_transfer_source?sslMode=disable","org.mariadb.jdbc.Driver",Path.of("build/drivers/mariadb-java-client-4.0.0.jar").toAbsolutePath().toString(),false);
        try(JdbcSession probe=JdbcSession.open(profile,new char[0]);var s=probe.connection().createStatement();var r=s.executeQuery("SELECT @@datadir")){r.next();equal(Path.of("build/sql-transfer-test-data").toRealPath(),Path.of(r.getString(1)).toRealPath());}
        AtomicReference<SqlTransferDialog> backup=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{SqlTransferDialog dialog=new SqlTransferDialog(null,profile,new char[0],false,"nexodb_transfer_source","item");dialog.setModal(false);dialog.setVisible(true);backup.set(dialog);});
        await(()->field(backup.get(),"worker",SwingWorker.class)==null&&field(backup.get(),"table",JTable.class).getRowCount()>0);
        SwingUtilities.invokeAndWait(()->{
            SqlTransferDialog dialog=backup.get();JTable table=field(dialog,"table",JTable.class);equal(10,table.getRowCount());int selected=0;for(int i=0;i<table.getRowCount();i++)if(Boolean.TRUE.equals(table.getValueAt(i,0)))selected++;
            equal(2,selected);equal(true,field(dialog,"start",JButton.class).isEnabled());
            field(dialog,"file",JTextField.class).setText(Path.of("build/ui-backup-preview.sql").toAbsolutePath().toString());snapshot(dialog,"sql-backup-light.png");
            UiLanguage.change("en",dialog);equal("SQL backup",dialog.getTitle());equal("Create backup",field(dialog,"start",JButton.class).getText());
            FlatDarkLaf.setup();SwingUtilities.updateComponentTreeUI(dialog);snapshot(dialog,"sql-backup-dark.png");invoke(dialog,"requestClose");
        });
        I18n.initialize("es");AtomicReference<SqlTransferDialog> restore=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{SqlTransferDialog dialog=new SqlTransferDialog(null,profile,new char[0],true,"nexodb_transfer_target","");dialog.setModal(false);dialog.setVisible(true);restore.set(dialog);});
        await(()->field(restore.get(),"worker",SwingWorker.class)==null&&field(restore.get(),"dedicated",JdbcSession.class)!=null);
        SwingUtilities.invokeAndWait(()->{field(restore.get(),"file",JTextField.class).setText(Path.of("build/sql-transfer-full.sql").toAbsolutePath().toString());equal(false,field(restore.get(),"start",JButton.class).isEnabled());field(restore.get(),"review",JButton.class).doClick();});
        await(()->field(restore.get(),"worker",SwingWorker.class)==null&&field(restore.get(),"analysis",SqlImportService.Analysis.class)!=null);
        SwingUtilities.invokeAndWait(()->{
            SqlTransferDialog dialog=restore.get();equal(true,field(dialog,"start",JButton.class).isEnabled());snapshot(dialog,"sql-import-dark.png");
            field(dialog,"file",JTextField.class).setText(Path.of("build/sql-transfer-native.sql").toAbsolutePath().toString());equal(false,field(dialog,"start",JButton.class).isEnabled());invoke(dialog,"requestClose");
        });
        SwingUtilities.invokeAndWait(()->{
            FlatLightLaf.setup();QueryPanel panel=new QueryPanel(text->{},busy->{},null);panel.insertSql("SELECT * FROM ct_ventas WHERE YEAR(fecha)=2026;\n");panel.insertSql("ALTER TABLE ejemplo ADD COLUMN nueva INT;\n");panel.setSize(1100,650);layout(panel);image(panel,"query-tabs-light.png");
        });
        System.out.println("SqlTransferWindowTest: OK (table/trigger preselection, checkbox states, independent connection, preview gate, file changes invalidate review, live captions and both themes)");
    }
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);AtomicBoolean done=new AtomicBoolean();while(System.nanoTime()<deadline){SwingUtilities.invokeAndWait(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(30);}throw new AssertionError("UI timed out");}
    private static void snapshot(SqlTransferDialog dialog,String name){Container root=dialog.getContentPane();root.setSize(900,650);layout(root);image(root,name);}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    private static void image(Component component,String name){try{BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();component.paint(g);g.dispose();Path output=Path.of("build/previews");Files.createDirectories(output);javax.imageio.ImageIO.write(image,"png",output.resolve(name).toFile());}catch(Exception error){throw new RuntimeException(error);}}
    private static void invoke(Object target,String name){try{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}catch(Exception error){throw new RuntimeException(error);}}
    private static <T>T field(Object target,String name,Class<T> type){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(target));}catch(Exception error){throw new RuntimeException(error);}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
