package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.model.*;
import com.formdev.flatlaf.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.imageio.ImageIO;

public final class ForeignKeyLookupWindowTest {
    public static void main(String[] args)throws Exception{
        if(GraphicsEnvironment.isHeadless())return;com.nexodb.util.I18n.initialize("es");FlatDarkLaf.setup();
        var profile=new ConnectionProfile("lookup-window","Lookup window test",DatabaseType.H2,"",0,"mem:lookup_window","sa","jdbc:h2:mem:lookup_window","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession s=JdbcSession.open(profile,new char[0])){
            s.execute("CREATE TABLE customers(id INT PRIMARY KEY,name VARCHAR(100))",1);s.execute("INSERT INTO customers VALUES(1,'Alice'),(2,'Bob')",1);s.execute("CREATE TABLE sales(id INT PRIMARY KEY,client INT,FOREIGN KEY(client) REFERENCES customers(id))",1);s.execute("INSERT INTO sales VALUES(1,1),(2,2)",1);
            QueryResult result=s.execute("SELECT * FROM sales ORDER BY id",100);AtomicReference<QueryResultPanel> panel=new AtomicReference<>();AtomicReference<JFrame> frame=new AtomicReference<>();
            SwingUtilities.invokeAndWait(()->{ResultTableModel model=new ResultTableModel();model.setResult(result);JTable table=new JTable(model);table.setAutoCreateRowSorter(true);table.setCellSelectionEnabled(true);var p=new QueryResultPanel(s,result,model,table,"SELECT * FROM sales ORDER BY id",100,b->{},t->{},t->{});panel.set(p);set(p,"confirm",(BiPredicate<String,String>)(a,b)->true);JFrame f=new JFrame();frame.set(f);f.setContentPane(p);f.setSize(1000,400);f.setVisible(true);});
            try{
                QueryResultPanel p=panel.get();await(()->!field(p,"references",List.class).isEmpty());
                SwingUtilities.invokeAndWait(()->{JTable table=field(p,"table",JTable.class);table.changeSelection(0,1,false,false);Component heading=table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(table,"CLIENT",false,false,-1,1);if(!(heading instanceof JLabel l)||l.getIcon()==null)throw new AssertionError("Missing FK header icon");});
                openAndChoose(p,false,false);equal(1,s.execute("SELECT client FROM sales WHERE id=1",10).rows().get(0).get(0));equal(0,field(p,"model",ResultTableModel.class).changeCount());
                SwingUtilities.invokeAndWait(()->field(p,"edit",JToggleButton.class).doClick());openAndChoose(p,true,false);
                equal(2,field(p,"model",ResultTableModel.class).getValueAt(0,1));equal(1,s.execute("SELECT client FROM sales WHERE id=1",10).rows().get(0).get(0));
                SwingUtilities.invokeAndWait(()->field(p,"save",JButton.class).doClick());await(()->!field(p,"saving",Boolean.class));equal(2,s.execute("SELECT client FROM sales WHERE id=1",10).rows().get(0).get(0));
                SwingUtilities.invokeAndWait(()->{JTable table=field(p,"table",JTable.class);table.getColumnModel().moveColumn(1,0);table.setRowSorter(new javax.swing.table.TableRowSorter<>(table.getModel()));table.getRowSorter().toggleSortOrder(0);table.getRowSorter().toggleSortOrder(0);table.changeSelection(table.convertRowIndexToView(0),0,false,false);});
                openAndChoose(p,true,true);equal(2,field(p,"model",ResultTableModel.class).getValueAt(0,1));
            }finally{SwingUtilities.invokeAndWait(()->frame.get().dispose());}
        }
        System.out.println("ForeignKeyLookupWindowTest: OK (FK icon, read-only browsing, modal selection, staged/save lifecycle, cancel, sorted rows and reordered columns)");
    }
    private static void openAndChoose(QueryResultPanel panel,boolean writable,boolean cancel)throws Exception{
        AtomicReference<Throwable> error=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            javax.swing.Timer timer=new javax.swing.Timer(60,null);long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
            timer.addActionListener(e->{for(Window window:Window.getWindows())if(window instanceof ForeignKeyLookupDialog dialog&&dialog.isVisible()){
                try{if(field(dialog,"worker",SwingWorker.class)!=null){if(System.nanoTime()>end)throw new AssertionError("Lookup timed out");return;}JButton choose=field(dialog,"select",JButton.class);equal(writable,choose.isVisible());if(writable&&!cancel){JTextField text=field(dialog,"search",JTextField.class);if(!text.getText().equals("Bob")){text.setText("Bob");field(dialog,"find",JButton.class).doClick();return;}}
                    snapshot((JComponent)dialog.getContentPane(),writable?"reference-select.png":"reference-readonly.png");timer.stop();if(writable&&!cancel)choose.doClick();else dialog.dispose();
                }catch(Throwable failure){error.set(failure);timer.stop();dialog.dispose();}
            }});timer.start();field(panel,"referenceButton",JButton.class).doClick();timer.stop();
        });if(error.get()!=null)throw new AssertionError(error.get());
    }
    private static void snapshot(JComponent c,String file)throws Exception{BufferedImage image=new BufferedImage(c.getWidth(),c.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();c.printAll(g);g.dispose();Path folder=Path.of("build/previews");Files.createDirectories(folder);ImageIO.write(image,"png",folder.resolve(file).toFile());}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){AtomicBoolean ready=new AtomicBoolean();SwingUtilities.invokeAndWait(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(30);}throw new AssertionError("UI timeout");}
    private static <T>T field(Object o,String name,Class<T> type){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(o));}catch(Exception error){throw new RuntimeException(error);}}
    private static void set(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(Exception error){throw new RuntimeException(error);}}
    private static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
}
