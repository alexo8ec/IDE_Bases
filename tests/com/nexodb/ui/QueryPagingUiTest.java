package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.model.*;
import javax.swing.*;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.util.concurrent.atomic.*;

public final class QueryPagingUiTest {
    public static void main(String[] args)throws Exception {
        var profile=new ConnectionProfile("paging-ui","Test",DatabaseType.H2,"",0,"mem:paging_ui","sa","jdbc:h2:mem:paging_ui","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])) {
            session.execute("CREATE TABLE entries(id INT PRIMARY KEY)",1);session.execute("INSERT INTO entries SELECT X FROM SYSTEM_RANGE(1,1205)",1);
            var result=session.execute("SELECT * FROM entries ORDER BY id",1000);var busy=new AtomicBoolean();var loads=new AtomicInteger();var ref=new AtomicReference<QueryResultPanel>();
            SwingUtilities.invokeAndWait(()->{var model=new ResultTableModel();model.setResult(result);var table=new JTable(model);table.setAutoCreateRowSorter(true);var grid=new QueryResultPanel(session,result,model,table,"SELECT * FROM entries ORDER BY id",1000,value->{busy.set(value);if(value)loads.incrementAndGet();},s->{},t->{});ref.set(grid);equal(true,field(grid,"serverView",Boolean.class));if(!field(grid,"elapsed",JLabel.class).getText().contains("ms"))throw new AssertionError("Missing query time footer");if(!"◀".equals(field(grid,"previous",JButton.class).getText())||!"▶".equals(field(grid,"next",JButton.class).getText()))throw new AssertionError("Paging must use compact arrows");var scroll=field(grid,"scroll",JScrollPane.class);scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);scroll.setSize(600,200);scroll.doLayout();var bar=scroll.getVerticalScrollBar();bar.setValue(bar.getMaximum()-bar.getVisibleAmount());invoke(grid,"loadNextIfNeeded");});
            await(busy);equal(1,loads.get());SwingUtilities.invokeAndWait(()->{var grid=ref.get();if(grid.result().rows().size()!=205||!grid.result().rows().get(0).get(0).equals(1001))throw new AssertionError("Automatic next page missing");equal(true,field(grid,"keysetActive",Boolean.class));if(!field(grid,"pageLabel",JLabel.class).getText().contains("· clave"))throw new AssertionError("Keyset paging indicator missing");if(field(grid,"next",JButton.class).isEnabled())throw new AssertionError("Next page must disable at EOF");if(!field(grid,"pageLabel",JLabel.class).getText().contains("1001–1205"))throw new AssertionError("Page range missing");field(grid,"previous",JButton.class).doClick();});
            await(busy);equal(1,loads.get());SwingUtilities.invokeAndWait(()->{var grid=ref.get();if(!grid.result().rows().get(0).get(0).equals(1))throw new AssertionError("Previous page incorrect");field(grid,"pageSizeChoice",JComboBox.class).setSelectedItem("100");});await(busy);
            SwingUtilities.invokeAndWait(()->{var grid=ref.get();if(grid.result().rows().size()!=100)throw new AssertionError("Page size combo did not reload");if(!field(grid,"totalLabel",JLabel.class).getText().contains("1205"))throw new AssertionError("Exact asynchronous total missing");field(grid,"lastPage",JButton.class).doClick();});await(busy);
            SwingUtilities.invokeAndWait(()->{var grid=ref.get();if(grid.result().rows().size()!=5||!grid.result().rows().get(0).get(0).equals(1201)||!field(grid,"keysetActive",Boolean.class))throw new AssertionError("Reverse keyset last page missing");JTextField number=field(grid,"pageNumber",JTextField.class);number.setText("3");number.postActionEvent();});await(busy);
            SwingUtilities.invokeAndWait(()->{var grid=ref.get();if(!grid.result().rows().get(0).get(0).equals(201))throw new AssertionError("Direct page navigation failed");field(grid,"firstPage",JButton.class).doClick();});await(busy);
            SwingUtilities.invokeAndWait(()->{var grid=ref.get();if(!grid.result().rows().get(0).get(0).equals(1))throw new AssertionError("First page navigation failed");field(grid,"filter",JTextField.class).setText("1205");});Thread.sleep(500);await(busy);
            SwingUtilities.invokeAndWait(()->{var grid=ref.get();if(grid.result().rows().size()!=1||!grid.result().rows().get(0).get(0).equals(1205))throw new AssertionError("Global filter only searched the loaded page");field(grid,"filter",JTextField.class).setText("");field(grid,"table",JTable.class).getRowSorter().setSortKeys(java.util.List.of(new RowSorter.SortKey(0,SortOrder.DESCENDING)));});Thread.sleep(500);await(busy);
            SwingUtilities.invokeAndWait(()->{var grid=ref.get();if(grid.result().rows().size()!=100||!grid.result().rows().get(0).get(0).equals(1205))throw new AssertionError("Global descending order was not executed by the database");field(grid,"countTimer",Timer.class).stop();if(grid.canCancel())grid.cancelOperation();});

            AtomicBoolean countBusy=new AtomicBoolean();AtomicReference<QueryResultPanel> countPanel=new AtomicReference<>();QueryResult partial=session.execute("SELECT * FROM entries ORDER BY id",100);
            SwingUtilities.invokeAndWait(()->{ResultTableModel model=new ResultTableModel();model.setResult(partial);JTable table=new JTable(model);table.setAutoCreateRowSorter(true);QueryResultPanel panel=new QueryResultPanel(session,partial,model,table,"SELECT * FROM entries ORDER BY id",100,countBusy::set,s->{},t->{});countPanel.set(panel);JButton count=field(panel,"countRows",JButton.class);count.doClick();if(!count.isEnabled()||!count.getText().contains("Cancelar"))throw new AssertionError("Running count must remain cancelable");});await(countBusy);
            SwingUtilities.invokeAndWait(()->{QueryResultPanel panel=countPanel.get();equal(1205L,field(panel,"totalRows",Long.class));if(!field(panel,"totalLabel",JLabel.class).getText().contains("1205"))throw new AssertionError("Asynchronous count label missing");field(panel,"countTimer",Timer.class).stop();});
        }
        System.out.println("QueryPagingUiTest: OK (keyset pages, exact total, first/last/direct navigation, cached previous page, filters and ordering)");
    }
    private static void await(AtomicBoolean busy)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(busy.get()&&System.nanoTime()<deadline)Thread.sleep(25);SwingUtilities.invokeAndWait(()->{});if(busy.get())throw new AssertionError("Paging timed out");}
    private static <T>T field(Object obj,String name,Class<T> type){try{var f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}catch(Exception error){throw new RuntimeException(error);}}
    private static void invoke(Object obj,String name){try{var method=obj.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(obj);}catch(Exception error){throw new RuntimeException(error);}}
    private static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
