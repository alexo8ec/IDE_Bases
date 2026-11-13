package com.nexodb.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.nexodb.db.*;
import com.nexodb.model.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class QueryGridIntegrationTest {
    public static void main(String[] args)throws Exception {
        FlatLightLaf.setup();
        com.nexodb.util.I18n.initialize("es");
        ResultTableModel copied=new ResultTableModel();copied.setResult(new QueryResult(List.of("id","text","note"),List.of(Arrays.asList(1,"a\t\"b\n",null)),-1,0,false));
        equal("1\t\"a\t\"\"b\n\"\t(NULL)",ResultGridActions.text(copied,List.of(0),List.of(0,1,2)));
        Path driver=Path.of(System.getProperty("nexodb.test.h2Jar","build/h2-test.jar")).toAbsolutePath();
        ConnectionProfile profile=new ConnectionProfile("grid-test","Memory test",DatabaseType.H2,"",0,"mem:grid_test","sa",
                "jdbc:h2:mem:grid_test;DB_CLOSE_DELAY=-1","org.h2.Driver",driver.toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])) {
            session.execute("CREATE TABLE people(id INT PRIMARY KEY,name VARCHAR(100),note VARCHAR(100))",10);
            session.execute("INSERT INTO people VALUES(1,'Alice',NULL),(2,'Bob','one'),(3,'Carol','two')",10);
            QueryResult result=session.execute("SELECT * FROM people ORDER BY id",10);
            AtomicReference<QueryResultPanel> reference=new AtomicReference<>();AtomicBoolean busy=new AtomicBoolean();
            AtomicReference<JFrame> frame=new AtomicReference<>();
            SwingUtilities.invokeAndWait(()->{
                ResultTableModel model=new ResultTableModel();model.setResult(result);JTable table=new JTable(model);table.setRowHeight(26);table.setAutoCreateRowSorter(true);table.setCellSelectionEnabled(true);
                QueryResultPanel panel=new QueryResultPanel(session,result,model,table,"SELECT * FROM people ORDER BY id",10,busy::set,text->{},t->{});reference.set(panel);
                JFrame window=new JFrame();frame.set(window);window.setContentPane(panel);window.setSize(1100,420);window.setVisible(true);window.validate();
                button(panel,"query-edit").doClick();model.setValueAt("Alice changed",0,1);equal(1,model.changeCount());equal(1,model.changes().get(0).values().size());
                snapshot(panel,"query-grid-light.png");
            });
            QueryResultPanel panel=reference.get();
            equal("Alice",session.execute("SELECT name FROM people WHERE id=1",10).rows().get(0).get(0));
            SwingUtilities.invokeAndWait(()->button(panel,"query-discard").doClick());
            SwingUtilities.invokeAndWait(()->{
                ResultTableModel model=field(panel,"model",ResultTableModel.class);JTable table=field(panel,"table",JTable.class);
                model.setValueAt("Edited",1,1);button(panel,"query-save").doClick();
            });await(()->!busy.get());
            equal("Edited",session.execute("SELECT name FROM people WHERE id=2",10).rows().get(0).get(0));
            SwingUtilities.invokeAndWait(()->{
                JTable table=field(panel,"table",JTable.class);ResultTableModel model=field(panel,"model",ResultTableModel.class);
                JTextField filter=field(panel,"filter",JTextField.class);filter.setText("Carol");equal(1,table.getRowCount());
                JTable rows=find(panel,"query-row-selector",JTable.class);rows.setValueAt(true,0,0);
                ResultGridActions actions=field(panel,"grid",ResultGridActions.class);equal(List.of(2),actions.selectedRows());
                filter.setText("");table.getRowSorter().toggleSortOrder(0);table.getRowSorter().toggleSortOrder(0);
                equal(List.of(2),actions.selectedRows());equal("Carol\ttwo",ResultGridActions.text(model,actions.selectedRows(),List.of(1,2)));
                // Reordered display columns still map NULL to the correct database column.
                table.moveColumn(2,0);table.changeSelection(0,0,false,false);button(panel,"query-null").doClick();
                equal(null,model.getValueAt(2,2));equal(1,model.changeCount());equal("Alice",model.getValueAt(0,1));
                button(panel,"query-discard").doClick();equal("two",model.getValueAt(2,2));
                // Header click selects the entire column without changing sort order.
                List<? extends RowSorter.SortKey> order=table.getRowSorter().getSortKeys();
                table.getTableHeader().dispatchEvent(new MouseEvent(table.getTableHeader(),MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,10,10,1,false,MouseEvent.BUTTON1));
                equal(3,table.getSelectedRowCount());equal(1,table.getSelectedColumnCount());equal(order,table.getRowSorter().getSortKeys());
                table.changeSelection(0,0,false,false);
                JPopupMenu menu=invoke(actions,"menu",new Class[]{boolean.class},false);
                for(Component item:menu.getComponents())if(item instanceof JMenuItem entry&&entry.getText().equals("Filtrar por este valor"))entry.doClick();
                equal(1,table.getRowCount());equal("two",table.getValueAt(0,0));
                AtomicReference<String> structureColumn=new AtomicReference<>();panel.setColumnEditor((origin,target,column,delete)->{equal(session,origin);equal("PEOPLE",target.table().name());equal(true,delete);structureColumn.set(column);});
                JPopupMenu structure=invoke(actions,"menu",new Class[]{boolean.class},true);
                for(Component item:structure.getComponents())if(item instanceof JMenuItem entry&&entry.getText().equals("Eliminar columna de la tabla…"))entry.doClick();
                equal("NOTE",structureColumn.get());
                UiLanguage.change("en",panel);equal("Save",button(panel,"query-save").getText());equal("NAME",model.getColumnName(1));
                if(field(panel,"count",JLabel.class).getText().contains("%s"))throw new AssertionError("Unformatted result count");
                FlatDarkLaf.setup();SwingUtilities.updateComponentTreeUI(frame.get());frame.get().validate();snapshot(panel,"query-grid-dark.png");
            });
            SwingUtilities.invokeAndWait(frame.get()::dispose);
            com.nexodb.util.I18n.initialize("es");
            completion(session);
        }
        System.out.println("QueryGridIntegrationTest: OK (staging/save/discard, checkbox identity, reordered columns, NULL, filters, copy, both themes and live column completion)");
    }
    private static void completion(JdbcSession session)throws Exception {
        SqlColumnCompletion provider=new SqlColumnCompletion(session);
        equal(List.of("ID","NAME","NOTE"),provider.columns("SELECT p. FROM people p","p","",List.of("PEOPLE")));
        equal(List.of("ID","NAME","NOTE"),provider.columns("UPDATE people SET na","","",List.of()));
        equal(List.of("ID","NAME","NOTE"),provider.columns("SELECT * FROM people WHERE na","","",List.of()));
        equal(List.of(),provider.columns("SELECT p. FROM people p","other","",List.of()));
        equal(false,SqlColumnCompletion.inCode("SELECT 'unfinished"));equal(false,SqlColumnCompletion.inCode("SELECT -- comment"));
        equal(true,SqlColumnCompletion.inCode("SELECT 'finished' WHERE "));
        AtomicReference<QueryPanel> panel=new AtomicReference<>();AtomicReference<JFrame> window=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{QueryPanel p=new QueryPanel(text->{},value->{});p.setSession(session);panel.set(p);JFrame frame=new JFrame();window.set(frame);frame.setContentPane(p);frame.setSize(1100,700);frame.setVisible(true);});
        QueryPanel p=panel.get();
        try{
            SwingUtilities.invokeAndWait(()->{SqlEditorPane editor=field(p,"editor",SqlEditorPane.class);editor.setText("SELECT p. FROM people p");editor.setCaretPosition("SELECT p.".length());editor.requestFocusInWindow();});
            await(()->field(p,"editor",SqlEditorPane.class).isFocusOwner());
            SwingUtilities.invokeAndWait(()->invoke(p,"showCompletions",new Class[]{boolean.class},true));
            await(()->field(p,"completionModel",DefaultListModel.class).contains("NAME"));
            SwingUtilities.invokeAndWait(()->{JList<?> list=field(p,"completionList",JList.class);list.setSelectedValue("NAME",true);invoke(p,"acceptCompletion",new Class[]{});equal("SELECT p.NAME FROM people p",field(p,"editor",SqlEditorPane.class).getText());});
        }finally{SwingUtilities.invokeAndWait(()->{field(p,"completionTimer",javax.swing.Timer.class).stop();window.get().dispose();});}
    }
    private static AbstractButton button(Container root,String name){return find(root,name,AbstractButton.class);}
    private static <T extends Component>T find(Component root,String name,Class<T> type){if(name.equals(root.getName())&&type.isInstance(root))return type.cast(root);if(root instanceof Container c)for(Component child:c.getComponents()){T found=find(child,name,type);if(found!=null)return found;}return null;}
    private static <T>T field(Object target,String name,Class<T> type){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(target));}catch(Exception error){throw new RuntimeException(error);}}
    @SuppressWarnings("unchecked") private static <T>T invoke(Object target,String name,Class<?>[] types,Object... args){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return (T)method.invoke(target,args);}catch(Exception error){throw new RuntimeException(error);}}
    private static void await(BooleanSupplier condition)throws Exception {AtomicBoolean value=new AtomicBoolean();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){SwingUtilities.invokeAndWait(()->value.set(condition.getAsBoolean()));if(value.get())return;Thread.sleep(20);}throw new AssertionError("UI operation timed out");}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
    private static void snapshot(Component component,String name){try{BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();component.paint(g);g.dispose();Path path=Path.of("build/previews");Files.createDirectories(path);javax.imageio.ImageIO.write(image,"png",path.resolve(name).toFile());}catch(Exception error){throw new RuntimeException(error);}}
}
