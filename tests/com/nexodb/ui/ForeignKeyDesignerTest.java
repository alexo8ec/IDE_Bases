package com.nexodb.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.util.I18n;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class ForeignKeyDesignerTest {
    public static void main(String[] args)throws Exception{
        FlatLightLaf.setup();I18n.initialize("es");System.setProperty("nexodb.disableDraftPersistence","true");
        ConnectionProfile profile=new ConnectionProfile("fk-ui","Isolated H2",DatabaseType.H2,"",0,"mem:fk_ui","sa","jdbc:h2:mem:fk_ui","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE parent(id INT PRIMARY KEY)",1);session.execute("CREATE TABLE child(id INT PRIMARY KEY,parent_id INT)",1);session.execute("CREATE TABLE other(id INT PRIMARY KEY)",1);
            AtomicReference<VisualDatabasePanel> ref=new AtomicReference<>();List<String> generated=new ArrayList<>();JFrame frame=edt(()->{JFrame f=new JFrame("FK interaction test");var panel=new VisualDatabasePanel(session,"PUBLIC",true,generated::add,i->{});ref.set(panel);f.add(panel);f.setSize(1250,850);f.setVisible(true);return f;});
            try{
                VisualDatabasePanel panel=ref.get();await(()->field(panel,"names",DefaultListModel.class).size()==3);
                SwingUtilities.invokeAndWait(()->{invoke(panel,"addTable","CHILD");invoke(panel,"addTable","PARENT");});
                Object child=edt(()->field(panel,"cards",Map.class).get("CHILD")),parent=edt(()->field(panel,"cards",Map.class).get("PARENT"));
                JList<?> from=field(child,"columns",JList.class),to=field(parent,"columns",JList.class);
                SwingUtilities.invokeAndWait(()->{
                    Point p=center(from.getCellBounds(1,1));Point target=SwingUtilities.convertPoint(to,center(to.getCellBounds(0,0)),from);
                    from.dispatchEvent(mouse(from,MouseEvent.MOUSE_PRESSED,p,MouseEvent.BUTTON1_DOWN_MASK,1,false,MouseEvent.BUTTON1));
                    from.dispatchEvent(mouse(from,MouseEvent.MOUSE_DRAGGED,target,MouseEvent.BUTTON1_DOWN_MASK,0,false,MouseEvent.NOBUTTON));
                    equal("PARENT_ID",field(panel,"dragColumn",String.class));equal(true,field(panel,"dropTarget",Object.class)!=null);
                    from.dispatchEvent(mouse(from,MouseEvent.MOUSE_RELEASED,target,0,1,false,MouseEvent.BUTTON1));
                });
                await(()->dialog("Nueva clave foránea")!=null);JDialog create=edt(()->dialog("Nueva clave foránea"));ForeignKeyEditor editor=edt(()->find(create,ForeignKeyEditor.class));
                SwingUtilities.invokeAndWait(()->{equal("PARENT",editor.parent.getSelectedItem());equal("PARENT_ID",editor.mapping.getValueAt(0,0));equal("ID",editor.mapping.getValueAt(0,1));equal(true,editor.sql().contains("FOREIGN KEY (\"PARENT_ID\")"));editor.onDelete.setSelectedItem("CASCADE");image(create,"foreign-key-create.png");});
                equal(0,ForeignKeyDefinition.read(session.connection(),DatabaseType.H2,"PUBLIC","CHILD").size());
                SwingUtilities.invokeLater(()->button(create,"Aplicar relación…").doClick());await(()->confirmation()!=null);SwingUtilities.invokeAndWait(()->{equal("No",confirmation().getInitialValue());confirmation().setValue("No");});
                equal(0,ForeignKeyDefinition.read(session.connection(),DatabaseType.H2,"PUBLIC","CHILD").size());equal(true,edt(create::isVisible));
                SwingUtilities.invokeLater(()->button(create,"Aplicar relación…").doClick());await(()->confirmation()!=null);SwingUtilities.invokeAndWait(()->confirmation().setValue("Sí"));await(()->!create.isVisible());await(()->field(panel,"foreignKeys",Map.class).size()==1);
                var key=ForeignKeyDefinition.read(session.connection(),DatabaseType.H2,"PUBLIC","CHILD").get(0);equal("CASCADE",key.onDelete());
                SwingUtilities.invokeAndWait(()->{Point p=center(from.getCellBounds(1,1));from.dispatchEvent(mouse(from,MouseEvent.MOUSE_PRESSED,p,MouseEvent.BUTTON3_DOWN_MASK,1,true,MouseEvent.BUTTON3));});
                await(()->MenuSelectionManager.defaultManager().getSelectedPath().length>0);
                SwingUtilities.invokeAndWait(()->{MenuElement[] path=MenuSelectionManager.defaultManager().getSelectedPath();JPopupMenu menu=(JPopupMenu)path[0];equal(true,item(menu,"Crear clave foránea…")!=null);equal(true,item(menu,"Relaciones de esta tabla")!=null);MenuSelectionManager.defaultManager().clearSelectedPath();});
                SwingUtilities.invokeLater(()->invoke(panel,"deleteRelationship",key));await(()->dialog("Eliminar relación")!=null);JDialog drop=edt(()->dialog("Eliminar relación"));
                SwingUtilities.invokeAndWait(()->button(drop,"Abrir SQL en consulta nueva").doClick());equal(1,generated.size());equal(true,generated.get(0).contains("DROP CONSTRAINT"));equal(1,ForeignKeyDefinition.read(session.connection(),DatabaseType.H2,"PUBLIC","CHILD").size());
                SwingUtilities.invokeAndWait(()->{
                    Point old=((JComponent)child).getLocation();invoke(panel,"load");panel.putClientProperty("expected-position",old);
                });await(()->field(panel,"cards",Map.class).get("CHILD")!=child);await(()->field(panel,"foreignKeys",Map.class).size()==1);
                SwingUtilities.invokeAndWait(()->{equal(panel.getClientProperty("expected-position"),((JComponent)field(panel,"cards",Map.class).get("CHILD")).getLocation());image(panel,"foreign-key-diagram.png");});
                // Search result double-click opens the clicked, sorted row, with unlimited JDBC reading.
                session.execute("INSERT INTO parent SELECT X FROM SYSTEM_RANGE(1,1205)",1);
                AtomicReference<String> opened=new AtomicReference<>();DataSearchPanel search=edt(()->{var s=new DataSearchPanel(session,"PUBLIC",opened::set);frame.setContentPane(s);frame.validate();return s;});await(()->field(search,"run",JButton.class).isEnabled());
                SwingUtilities.invokeAndWait(()->{field(search,"search",JTextField.class).setText("1");field(search,"run",JButton.class).doClick();});await(()->field(search,"worker",Object.class)==null&&field(search,"matches",javax.swing.table.DefaultTableModel.class).getRowCount()>0);
                SwingUtilities.invokeAndWait(()->{JTable found=field(search,"found",JTable.class);found.getRowSorter().toggleSortOrder(0);Point p=center(found.getCellRect(0,0,true));found.dispatchEvent(mouse(found,MouseEvent.MOUSE_CLICKED,p,0,2,false,MouseEvent.BUTTON1));});equal(true,opened.get()!=null);equal(true,session.execute(opened.get(),Integer.MAX_VALUE).rows().size()>0);
            }finally{SwingUtilities.invokeAndWait(()->{for(Window w:Window.getWindows())if(w!=frame)w.dispose();frame.dispose();});}
        }
        System.out.println("ForeignKeyDesignerTest: OK (real mouse drop, selected endpoints, default No/cancel, confirmed DDL, context menu, preview-only DROP, layout preserved, search double-click and unlimited results)");System.exit(0);
    }
    private static MouseEvent mouse(Component c,int id,Point p,int modifiers,int clicks,boolean popup,int button){return new MouseEvent(c,id,System.currentTimeMillis(),modifiers,p.x,p.y,clicks,popup,button);}
    private static Point center(Rectangle r){return new Point(r.x+Math.min(35,r.width/2),r.y+r.height/2);}
    private static JOptionPane confirmation(){for(Window w:Window.getWindows())if(w.isVisible()){JOptionPane p=find(w,JOptionPane.class);if(p!=null&&p.getOptions()!=null&&Arrays.asList(p.getOptions()).contains("Sí"))return p;}return null;}
    private static JDialog dialog(String title){for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isVisible()&&d.getTitle().equals(title))return d;return null;}
    private static <T extends Component>T find(Container c,Class<T> type){for(Component item:c.getComponents()){if(type.isInstance(item))return type.cast(item);if(item instanceof Container nested){T found=find(nested,type);if(found!=null)return found;}}return null;}
    private static JButton button(Container c,String text){for(Component item:c.getComponents()){if(item instanceof JButton b&&b.getText().equals(text))return b;if(item instanceof Container nested){JButton found=button(nested,text);if(found!=null)return found;}}return null;}
    private static JMenuItem item(JPopupMenu menu,String text){for(Component c:menu.getComponents())if(c instanceof JMenuItem i&&i.getText().equals(text))return i;return null;}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(30);}throw new AssertionError("UI timed out");}
    private static <T>T edt(java.util.concurrent.Callable<T> work)throws Exception{AtomicReference<T> value=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{try{value.set(work.call());}catch(Exception e){throw new RuntimeException(e);}});return value.get();}
    private static Object invoke(Object target,String name,Object...args){try{Method m=Arrays.stream(target.getClass().getDeclaredMethods()).filter(x->x.getName().equals(name)&&x.getParameterCount()==args.length).findFirst().orElseThrow();m.setAccessible(true);return m.invoke(target,args);}catch(Exception e){throw new RuntimeException(e);}}
    private static <T>T field(Object target,String name,Class<T> type){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(target));}catch(Exception e){throw new RuntimeException(e);}}
    private static void image(Component c,String name){try{BufferedImage img=new BufferedImage(c.getWidth(),c.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();c.paint(g);g.dispose();Path out=Path.of("build/previews",name);Files.createDirectories(out.getParent());javax.imageio.ImageIO.write(img,"png",out.toFile());}catch(Exception e){throw new RuntimeException(e);}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
