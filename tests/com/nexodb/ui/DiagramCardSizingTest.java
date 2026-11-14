package com.nexodb.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.nexodb.db.*;
import com.nexodb.model.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class DiagramCardSizingTest {
    public static void main(String[] args)throws Exception{
        FlatLightLaf.setup();var profile=new ConnectionProfile("sizing","Diagram test",DatabaseType.H2,"",0,"mem:sizing","sa","jdbc:h2:mem:sizing","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE parent(id INT PRIMARY KEY)",10);session.execute("CREATE TABLE child(id INT PRIMARY KEY,parent_id INT,FOREIGN KEY(parent_id) REFERENCES parent(id))",10);session.execute("INSERT INTO parent VALUES(1)",10);session.execute("INSERT INTO child VALUES(2,1)",10);
            AtomicReference<VisualDatabasePanel> ref=new AtomicReference<>();JFrame frame=edt(()->{JFrame f=new JFrame("Diagram sizing test");var panel=new VisualDatabasePanel(session,"PUBLIC",true,s->{},i->{});ref.set(panel);f.add(panel);f.setSize(1250,850);f.setVisible(true);return f;});
            try{
                VisualDatabasePanel panel=ref.get();await(()->field(panel,"names",DefaultListModel.class).size()==2);
                SwingUtilities.invokeAndWait(()->{invoke(panel,"addTable","CHILD");invoke(panel,"addTable","PARENT");});
                JComponent card=edt(()->(JComponent)field(panel,"cards",Map.class).get("CHILD"));
                SwingUtilities.invokeAndWait(()->{
                    int width=card.getWidth(),height=card.getHeight();resize(card,new Point(width-2,height-2),new Point(width+198,height+178));equal(width+200,card.getWidth());equal(height+180,card.getHeight());
                    resize(card,new Point(card.getWidth()-2,card.getHeight()-2),new Point(-10000,-10000));equal(190,card.getWidth());equal(85,card.getHeight());
                    resize(card,new Point(card.getWidth()-2,card.getHeight()-2),new Point(450,500));frame.validate();
                    invoke(card,"setTint",Color.MAGENTA);Properties state=(Properties)invoke(panel,"layoutState");Rectangle bounds=card.getBounds();equal(""+bounds.width,state.getProperty("0.width"));equal(""+bounds.height,state.getProperty("0.height"));equal("ff00ff",state.getProperty("0.color"));invoke(panel,"restoreLayout",state);equal(bounds,((JComponent)field(panel,"cards",Map.class).get("CHILD")).getBounds());equal(Color.MAGENTA,field(field(panel,"cards",Map.class).get("CHILD"),"tint",Color.class));
                });
                JComponent replacement=edt(()->(JComponent)field(panel,"cards",Map.class).get("CHILD"));Rectangle before=edt(replacement::getBounds);SwingUtilities.invokeAndWait(()->invoke(panel,"load"));await(()->field(panel,"cards",Map.class).get("CHILD")!=replacement);equal(before,edt(()->((JComponent)field(panel,"cards",Map.class).get("CHILD")).getBounds()));equal(Color.MAGENTA,edt(()->field(field(panel,"cards",Map.class).get("CHILD"),"tint",Color.class)));
                SwingUtilities.invokeAndWait(()->{JComponent parent=(JComponent)field(panel,"cards",Map.class).get("PARENT");JButton close=close(parent);if(close==null)throw new AssertionError("Missing corner close button");close.doClick();equal(false,field(panel,"cards",Map.class).containsKey("PARENT"));});
                equal(1,session.execute("SELECT * FROM parent",10).rows().size());equal(1,session.execute("SELECT * FROM child",10).rows().size());
            }finally{SwingUtilities.invokeAndWait(frame::dispose);}
        }
        System.out.println("DiagramCardSizingTest: OK (real edge drag, minimum size, persisted bounds, refresh preserves dimensions, close removes only the diagram card)");System.exit(0);
    }
    private static void resize(JComponent card,Point start,Point end){card.dispatchEvent(new MouseEvent(card,MouseEvent.MOUSE_PRESSED,1,MouseEvent.BUTTON1_DOWN_MASK,start.x,start.y,1,false,MouseEvent.BUTTON1));card.dispatchEvent(new MouseEvent(card,MouseEvent.MOUSE_DRAGGED,2,MouseEvent.BUTTON1_DOWN_MASK,end.x,end.y,0,false,MouseEvent.NOBUTTON));card.dispatchEvent(new MouseEvent(card,MouseEvent.MOUSE_RELEASED,3,0,end.x,end.y,1,false,MouseEvent.BUTTON1));}
    private static JButton close(Container c){for(Component item:c.getComponents()){if(item instanceof JButton b&&"close-diagram-table".equals(b.getName()))return b;if(item instanceof Container nested){JButton found=close(nested);if(found!=null)return found;}}return null;}
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(30);}throw new AssertionError("Timed out");}
    private static <T>T edt(java.util.concurrent.Callable<T> work)throws Exception{AtomicReference<T> result=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{try{result.set(work.call());}catch(Exception e){throw new RuntimeException(e);}});return result.get();}
    private static Object invoke(Object target,String name,Object...args){try{Method m=Arrays.stream(target.getClass().getDeclaredMethods()).filter(x->x.getName().equals(name)&&x.getParameterCount()==args.length).findFirst().orElseThrow();m.setAccessible(true);return m.invoke(target,args);}catch(Exception e){throw new RuntimeException(e);}}
    private static <T>T field(Object target,String name,Class<T> type){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(target));}catch(Exception e){throw new RuntimeException(e);}}
    private static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
}
