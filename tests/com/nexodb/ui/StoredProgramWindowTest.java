package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.debug.*;
import com.nexodb.util.I18n;
import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class StoredProgramWindowTest {
    public static void main(String[] args)throws Exception{
        if(!Boolean.getBoolean("nexodb.test.storedPrograms"))throw new IllegalStateException("Dedicated local fixture required");
        var profile=StoredProgramIntegrationTest.profile("nexodb_stored_test");Path folder=Path.of("build/stored-program-qa");Files.createDirectories(folder);System.setProperty("nexodb.settingsFile",folder.resolve("settings.properties").toAbsolutePath().toString());FlatLightLaf.setup();I18n.initialize("es");
        AtomicReference<MainFrame> window=new AtomicReference<>();AtomicReference<StoredObjectEditorPanel> editorRef=new AtomicReference<>();AtomicReference<StoredProcedureDebugPanel> debugRef=new AtomicReference<>();
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            equal(Path.of("build/stored-program-test-data").toRealPath(),Path.of(StoredObjectService.scalar(session.connection(),"SELECT @@datadir")).toRealPath());
            var item=new SchemaItem(SchemaItem.Kind.PROCEDURE,"nexodb_stored_test",null,"debug_calc","");
            SwingUtilities.invokeAndWait(()->{try{
                MainFrame frame=new MainFrame();window.set(frame);frame.setSize(1400,900);frame.setVisible(true);
                for(var group:java.util.List.of(SchemaItem.Kind.VIEWS,SchemaItem.Kind.PROCEDURES,SchemaItem.Kind.FUNCTIONS,SchemaItem.Kind.TRIGGERS,SchemaItem.Kind.EVENTS)){
                    SchemaItem parent=new SchemaItem(group,"nexodb_stored_test",null,group.name(),"");JPopupMenu menu=menu(frame,parent,session);equal(true,entry(menu,"object-create").isEnabled());equal(false,entry(menu,"object-edit").isEnabled());equal(false,entry(menu,"object-drop").isEnabled());
                    var entity=new SchemaItem(StoredObjectService.objectKind(group),"nexodb_stored_test",null,"sample","");JPopupMenu selected=menu(frame,entity,session);equal(true,entry(selected,"object-edit").isEnabled());equal(true,entry(selected,"object-drop").isEnabled());if(group==SchemaItem.Kind.TRIGGERS)equal(false,entry(selected,"object-rename").isEnabled());captureMenu(selected,folder.resolve(group.name()+".png"));
                }
                JTree tree=field(frame,"objectTree",JTree.class);for(String key:java.util.List.of("F4","F6","DELETE","F2"))if(tree.getInputMap().get(KeyStroke.getKeyStroke(key))==null)throw new AssertionError("Shortcut missing: "+key);
                entry(menu(frame,item,session),"object-edit").doClick();
            }catch(Exception error){throw new RuntimeException(error);}});
            await(()->field(window.get(),"workspaceTabs",JTabbedPane.class).getSelectedComponent() instanceof StoredObjectEditorPanel);
            SwingUtilities.invokeAndWait(()->{var editor=(StoredObjectEditorPanel)field(window.get(),"workspaceTabs",JTabbedPane.class).getSelectedComponent();editorRef.set(editor);set(editor,"confirm",(java.util.function.BiPredicate<String,String>)(a,b)->true);SqlEditorPane code=field(editor,"editor",SqlEditorPane.class);code.restoreText(code.getText().replace("AS resultado","AS resultado_ui"));field(editor,"apply",JButton.class).doClick();});
            await(()->!field(editorRef.get(),"busy",Boolean.class));if(!StoredObjectService.read(session,item).sql().contains("resultado_ui"))throw new AssertionError("Object editor did not apply full procedure body");
            var definition=StoredObjectService.read(session,item);
            SwingUtilities.invokeAndWait(()->{var editor=editorRef.get();var code=field(editor,"editor",SqlEditorPane.class);if(!code.getText().contains("DELIMITER")||!code.getText().contains("DROP PROCEDURE IF EXISTS"))throw new AssertionError("SQLyog script not shown");code.restoreText(code.getText().replace("SET p_result = 0","THIS IS INVALID SQL"));field(editor,"apply",JButton.class).doClick();});
            await(()->!field(editorRef.get(),"busy",Boolean.class));equal(definition.hash(),StoredObjectService.read(session,item).hash());SwingUtilities.invokeAndWait(()->{var code=field(editorRef.get(),"editor",SqlEditorPane.class);if(code.errorLines().isEmpty())throw new AssertionError("DDL error line was not marked");var status=field(editorRef.get(),"status",JLabel.class);if(!status.getText().contains("sintaxis"))throw new AssertionError("DDL explanation missing");code.restoreText(StoredProgramScript.editor(item,definition.sql(),true));});
            SwingUtilities.invokeAndWait(()->{try{var panel=new StoredProcedureDebugPanel(profile,new char[0],item,definition,s->{});debugRef.set(panel);set(panel,"confirm",(java.util.function.BiPredicate<String,String>)(a,b)->true);DefaultTableModel inputs=field(panel,"inputs",DefaultTableModel.class);inputs.setValueAt("2",0,3);inputs.setValueAt(false,0,4);int line=1+(int)definition.sql().substring(0,definition.sql().indexOf("INSERT INTO debug_target")).chars().filter(c->c=='\n').count();invoke(panel,"toggle",new Class<?>[]{int.class},line);invoke(window.get(),"addToolTab",new Class<?>[]{String.class,JComponent.class},"Depuración real · debug_calc",panel);field(panel,"start",JButton.class).doClick();}catch(Exception error){throw new RuntimeException(error);}});
            await(()->field(debugRef.get(),"debugger",MysqlDebugSession.class)!=null&&field(debugRef.get(),"debugger",MysqlDebugSession.class).state()==MysqlDebugSession.State.PAUSED);
            SwingUtilities.invokeAndWait(()->{var panel=debugRef.get();if(field(panel,"variables",DefaultTableModel.class).getRowCount()<4)throw new AssertionError("No variable context displayed");field(panel,"resume",JButton.class).doClick();});
            await(()->{var engine=field(debugRef.get(),"debugger",MysqlDebugSession.class);return engine.state()==MysqlDebugSession.State.PAUSED&&engine.last().variables().stream().anyMatch(v->v.name().equals("p_result")&&"102".equals(v.value()));});
            SwingUtilities.invokeAndWait(()->{try{window.get().validate();var panel=debugRef.get();SqlEditorPane source=field(panel,"source",SqlEditorPane.class);int at=source.getText().indexOf("INSERT INTO debug_target VALUES(p_result)")+"INSERT INTO debug_target VALUES(".length();var value=(MysqlDebugSession.Value)invoke(panel,"hoverValue",new Class<?>[]{int.class},at);equal("102",value.value());var rectangle=source.modelToView2D(at);source.dispatchEvent(new java.awt.event.MouseEvent(source,java.awt.event.MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,(int)rectangle.getX()+2,(int)rectangle.getY()+4,0,false));if(source.getToolTipText()==null||!source.getToolTipText().contains("102"))throw new AssertionError("Hover did not show the live variable value");if(field(panel,"trace",DefaultTableModel.class).getRowCount()<2)throw new AssertionError("Step history missing");var image=new BufferedImage(window.get().getWidth(),window.get().getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();try{window.get().paint(g);}finally{g.dispose();}javax.imageio.ImageIO.write(image,"png",folder.resolve("debugger-window.png").toFile());if(panel.canClose())throw new AssertionError("Active debugger must block close");field(panel,"stop",JButton.class).doClick();}catch(Exception error){throw new RuntimeException(error);}});
            await(()->field(debugRef.get(),"debugger",MysqlDebugSession.class).finished());SwingUtilities.invokeAndWait(()->{equal(true,debugRef.get().canClose());debugRef.get().dispose();window.get().dispose();});
        }finally{if(window.get()!=null)SwingUtilities.invokeAndWait(()->window.get().dispose());}
        System.out.println("StoredProgramWindowTest: OK (five real context menus, group/selection enablement, F4/F6/Delete/F2, full-body save, real debugger GUI, variables, breakpoint stop and safe close)");
    }
    private static JPopupMenu menu(MainFrame frame,SchemaItem item,JdbcSession session)throws Exception{Class<?> node=Class.forName("com.nexodb.ui.MainFrame$LazyNode");var ctor=node.getDeclaredConstructor(SchemaItem.class,boolean.class,JdbcSession.class);ctor.setAccessible(true);Object value=ctor.newInstance(item,true,session);return (JPopupMenu)invoke(frame,"objectMenu",new Class<?>[]{node},value);}
    private static JMenuItem entry(JPopupMenu menu,String name){for(Component component:menu.getComponents())if(component instanceof JMenuItem item&&name.equals(item.getName()))return item;throw new AssertionError(name);}
    private static void captureMenu(JPopupMenu menu,Path path)throws Exception{Dimension size=menu.getPreferredSize();menu.setSize(size);menu.doLayout();var image=new BufferedImage(size.width,size.height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();try{menu.paint(g);}finally{g.dispose();}javax.imageio.ImageIO.write(image,"png",path.toFile());}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){boolean[] ready={false};SwingUtilities.invokeAndWait(()->ready[0]=condition.getAsBoolean());if(ready[0])return;Thread.sleep(30);}throw new AssertionError("Window operation timed out");}
    private static <T>T field(Object obj,String name,Class<T> type){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}catch(Exception error){throw new RuntimeException(error);}}
    private static void set(Object obj,String name,Object value){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);f.set(obj,value);}catch(Exception error){throw new RuntimeException(error);}}
    private static Object invoke(Object obj,String name,Class<?>[] types,Object...args)throws Exception{var method=obj.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(obj,args);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
