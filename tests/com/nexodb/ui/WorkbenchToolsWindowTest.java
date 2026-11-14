package com.nexodb.ui;

import com.formdev.flatlaf.*;
import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.util.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class WorkbenchToolsWindowTest {
    public static void main(String[] args)throws Exception{
        FlatLightLaf.setup();I18n.initialize("es");Path root=Files.createTempDirectory("nexodb-workbench-ui-");System.setProperty("nexodb.settingsFile",root.resolve("settings.properties").toString());
        ConnectionProfile profile=new ConnectionProfile("tools-window","Isolated H2 test",DatabaseType.H2,"",0,"mem:tools","sa","jdbc:h2:mem:tools","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE parent(id INT PRIMARY KEY,name VARCHAR(100))",10);session.execute("CREATE TABLE child(id INT PRIMARY KEY,parent_id INT,FOREIGN KEY(parent_id) REFERENCES parent(id))",10);session.execute("INSERT INTO parent VALUES(1,'snow leopard')",10);session.execute("INSERT INTO child VALUES(2,1)",10);
            AtomicReference<MainFrame> ref=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{try{MainFrame frame=new MainFrame();ref.set(frame);frame.setSize(1500,900);frame.setVisible(true);set(frame,"session",session);QueryPanel q=field(frame,"queryPanel",QueryPanel.class);q.setSession(session);field(q,"editor",SqlEditorPane.class).restoreText("DELETE FROM parent;");}catch(Exception error){throw new RuntimeException(error);}});
            MainFrame frame=ref.get();QueryPanel query=field(frame,"queryPanel",QueryPanel.class);JTabbedPane workspace=field(frame,"workspaceTabs",JTabbedPane.class);
            try{
                await(()->field(query,"database",JComboBox.class).getItemCount()>1);SwingUtilities.invokeAndWait(()->query.selectDatabase("PUBLIC"));
                SwingUtilities.invokeAndWait(()->{JPopupMenu menu=query.newQueryMenu();menu.setSize(menu.getPreferredSize());layout(menu);image(menu,"new-query-menu.png");item(menu,"Constructor visual de consultas").doClick();});
                VisualDatabasePanel builder=onEdt(()->(VisualDatabasePanel)workspace.getSelectedComponent());await(()->field(builder,"names",DefaultListModel.class).size()==2);
                SwingUtilities.invokeAndWait(()->{invoke(builder,"addTable","PARENT");invoke(builder,"addTable","CHILD");var fields=field(builder,"fields",DefaultTableModel.class);fields.addRow(new Object[]{true,"p","NAME","","customer","ASC",""});fields.addRow(new Object[]{true,"c","ID","","","",""});image(builder,"query-builder-light.png");button(builder,"Abrir SQL en consulta nueva").doClick();equal(0,workspace.getSelectedIndex());equal(false,query.currentSql().contains("DELETE"));equal(true,query.currentSql().contains("SELECT"));});
                equal(1,session.execute("SELECT * FROM parent",10).rows().size());
                SwingUtilities.invokeAndWait(()->item(query.newQueryMenu(),"Diseñador de esquemas").doClick());VisualDatabasePanel designer=onEdt(()->(VisualDatabasePanel)workspace.getSelectedComponent());await(()->field(designer,"names",DefaultListModel.class).size()==2);
                SwingUtilities.invokeAndWait(()->{invoke(designer,"addTable","PARENT");invoke(designer,"addTable","CHILD");});await(()->!field(designer,"joins",java.util.List.class).isEmpty());SwingUtilities.invokeAndWait(()->{image(designer,"schema-designer-light.png");FlatDarkLaf.setup();SwingUtilities.updateComponentTreeUI(frame);frame.validate();image(designer,"schema-designer-dark.png");});
                SwingUtilities.invokeAndWait(()->item(query.newQueryMenu(),"Buscar datos").doClick());DataSearchPanel search=onEdt(()->(DataSearchPanel)workspace.getSelectedComponent());await(()->field(search,"run",JButton.class).isEnabled());SwingUtilities.invokeAndWait(()->{field(search,"search",JTextField.class).setText("snow leopard");field(search,"run",JButton.class).doClick();});await(()->field(search,"worker",SwingWorker.class)==null&&field(search,"matches",DefaultTableModel.class).getRowCount()>0);
                SwingUtilities.invokeAndWait(()->{image(search,"data-search-dark.png");field(search,"found",JTable.class).setRowSelectionInterval(0,0);field(search,"results",JButton.class).doClick();});await(()->!field(query,"queryRunning",Boolean.class));
                equal(1,session.execute("SELECT * FROM parent",10).rows().size());
                SwingUtilities.invokeAndWait(()->{UiLanguage.change("en",frame);JPopupMenu menu=query.newQueryMenu();equal(true,item(menu,"Visual query builder")!=null);image(query,"query-documents-dark.png");JMenu power=findMenu(frame,"Power tools");equal(8L,java.util.Arrays.stream(power.getMenuComponents()).filter(c->c instanceof JMenuItem).count());});
            }finally{SwingUtilities.invokeAndWait(()->{field(frame,"scheduledTasks",ScheduledTasks.class).close();frame.dispose();});}
        }finally{System.clearProperty("nexodb.settingsFile");}
        System.out.println("WorkbenchToolsWindowTest: OK (compact menu, real builder/designer/search workflows, generated SQL isolated from old DELETE, relation diagram, both themes and translated menus)");
        System.exit(0);
    }
    private static JMenu findMenu(MainFrame frame,String text){for(int i=0;i<frame.getJMenuBar().getMenuCount();i++)if(frame.getJMenuBar().getMenu(i).getText().equals(text))return frame.getJMenuBar().getMenu(i);throw new AssertionError("Missing menu "+text);}
    private static JMenuItem item(JPopupMenu menu,String text){for(Component c:menu.getComponents())if(c instanceof JMenuItem item&&item.getText().equals(text))return item;throw new AssertionError("Missing item "+text);}
    private static JButton button(Container root,String text){for(Component c:root.getComponents()){if(c instanceof JButton b&&b.getText().equals(text))return b;if(c instanceof Container child){JButton b=button(child,text);if(b!=null)return b;}}return null;}
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);AtomicBoolean done=new AtomicBoolean();while(System.nanoTime()<deadline){SwingUtilities.invokeAndWait(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(25);}throw new AssertionError("UI operation timed out");}
    private static <T>T onEdt(java.util.concurrent.Callable<T> value)throws Exception{AtomicReference<T> ref=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{try{ref.set(value.call());}catch(Exception error){throw new RuntimeException(error);}});return ref.get();}
    private static void invoke(Object target,String name,Object... args){try{Method m=java.util.Arrays.stream(target.getClass().getDeclaredMethods()).filter(x->x.getName().equals(name)&&x.getParameterCount()==args.length).findFirst().orElseThrow();m.setAccessible(true);m.invoke(target,args);}catch(Exception error){throw new RuntimeException(error);}}
    private static <T>T field(Object obj,String name,Class<T> type){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}catch(Exception error){throw new RuntimeException(error);}}
    private static void set(Object obj,String name,Object value)throws Exception{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);f.set(obj,value);}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    private static void image(Component component,String name){try{layout((Container)component);BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();component.paint(g);g.dispose();Path output=Path.of("build/previews");Files.createDirectories(output);javax.imageio.ImageIO.write(image,"png",output.resolve(name).toFile());}catch(Exception error){throw new RuntimeException(error);}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
