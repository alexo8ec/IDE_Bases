package com.nexodb.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.nexodb.MetadataFixture;
import com.nexodb.db.JdbcSession;
import com.nexodb.model.DatabaseType;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static com.nexodb.MetadataFixture.equal;

/** Exercises the actual modal checkbox picker and schema comparison window. */
public final class ComparisonWindowTest {
    public static void main(String[] args)throws Exception {
        FlatLightLaf.setup();
        JdbcSession a=endpoint("Source"),b=endpoint("Target");
        ComparisonChoices memory=new ComparisonChoices();
        AtomicReference<SchemaCompareDialog> window=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->window.set(new SchemaCompareDialog(null,a,List.of(a,b),(s,sql)->{},memory)));
        SchemaCompareDialog dialog=window.get();
        try {
            await(()->field(dialog,"compare",JButton.class).isEnabled());
            SwingUtilities.invokeAndWait(()->{
                javax.swing.Timer accept=new javax.swing.Timer(25,null);
                accept.addActionListener(event->{
                    for(Window popup:Window.getWindows()) if(popup.isShowing() && popup instanceof JDialog && popup!=dialog) {
                        JTable table=find(popup,JTable.class);JOptionPane pane=find(popup,JOptionPane.class);
                        if(table==null || pane==null || table.getColumnClass(0)!=Boolean.class)continue;
                        for(int row=0;row<table.getRowCount();row++) table.setValueAt(table.getValueAt(row,1).toString().endsWith("beta"),row,0);
                        try { snapshot(popup,"comparison-checkboxes.png"); }catch(Exception error){throw new RuntimeException(error);}
                        pane.setValue(JOptionPane.OK_OPTION);accept.stop();return;
                    }
                });
                accept.start();field(dialog,"chooseObjects",JButton.class).doClick();
            });
            await(()->memory.tablesConfigured());
            equal(Set.of("table:beta"),memory.tables());
            SwingUtilities.invokeAndWait(()->field(dialog,"compare",JButton.class).doClick());
            await(()->field(dialog,"lastComparison",Object.class)!=null);
            SwingUtilities.invokeAndWait(()->{
                DefaultTableModel model=field(dialog,"model",DefaultTableModel.class);
                equal(1,model.getRowCount());equal("beta",model.getValueAt(0,1));
                dialog.addNotify();dialog.validate();
                try {snapshot(dialog,"comparison-selected-only.png");}catch(Exception error){throw new RuntimeException(error);}
            });
        }finally{SwingUtilities.invokeAndWait(dialog::dispose);}
        System.out.println("ComparisonWindowTest: OK (real checkbox picker; only checked table compared)");
    }
    private static JdbcSession endpoint(String id)throws Exception {
        return MetadataFixture.session(id,DatabaseType.MYSQL,(proxy,method,args)->switch(method.getName()) {
            case "getCatalogs" -> MetadataFixture.rows(new String[]{"TABLE_CAT"},new Object[]{"db1"},new Object[]{"db2"});
            case "getTables" -> MetadataFixture.rows(new String[]{"TABLE_NAME","TABLE_TYPE"},new Object[]{"alpha","TABLE"},new Object[]{"beta","TABLE"});
            case "getColumns" -> MetadataFixture.rows(new String[]{"TABLE_NAME","COLUMN_NAME","TYPE_NAME","COLUMN_SIZE","DECIMAL_DIGITS","NULLABLE","COLUMN_DEF","IS_AUTOINCREMENT","ORDINAL_POSITION"},
                    new Object[]{"alpha","id","INT",10,0,0,null,"NO",1},new Object[]{"beta","id","INT",10,0,0,null,"NO",1});
            default -> throw new AssertionError(method.getName());
        });
    }
    private static <T>T field(Object object,String name,Class<T> type){try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(object));}catch(Exception e){throw new RuntimeException(e);}}
    private static <T extends Component>T find(Component root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof Container container)for(Component child:container.getComponents()){T found=find(child,type);if(found!=null)return found;}return null;}
    private static void snapshot(Component component,String name)throws Exception {
        BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();component.paint(g);g.dispose();
        Path directory=Path.of("build/previews");Files.createDirectories(directory);javax.imageio.ImageIO.write(image,"png",directory.resolve(name).toFile());
    }
    private static void await(BooleanSupplier condition)throws Exception {
        AtomicBoolean ready=new AtomicBoolean();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline){SwingUtilities.invokeAndWait(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(20);}
        throw new AssertionError("Timed out waiting for comparison window");
    }
}
