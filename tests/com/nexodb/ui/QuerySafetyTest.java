package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.model.*;
import javax.swing.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class QuerySafetyTest {
    public static void main(String[] args)throws Exception{
        for(String sql:List.of("SELECT 1","SELECT 'DELETE FROM x'","SELECT `UPDATE` FROM t","WITH a AS(SELECT 1) SELECT * FROM a","SHOW CREATE TABLE t"))equal(true,SqlExecutionPolicy.readOnly(sql));
        for(String sql:List.of("DELETE FROM x","UPDATE x SET a=1","CREATE TABLE t(a INT)","SELECT * INTO OUTFILE '/tmp/x' FROM t","WITH a AS(DELETE FROM t RETURNING *) SELECT * FROM a","CALL p()","EXPLAIN ANALYZE DELETE FROM t"))equal(false,SqlExecutionPolicy.readOnly(sql));
        ConnectionProfile profile=new ConnectionProfile("safety-test","Test",DatabaseType.H2,"",0,"mem:safety","sa","jdbc:h2:mem:safety","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE people(id INT PRIMARY KEY,name VARCHAR(100))",10);session.execute("INSERT INTO people SELECT X, 'name' FROM SYSTEM_RANGE(1,1205)",10);
            AtomicReference<QueryPanel> ref=new AtomicReference<>();AtomicBoolean busy=new AtomicBoolean();AtomicInteger confirms=new AtomicInteger();
            SwingUtilities.invokeAndWait(()->{try{QueryPanel panel=new QueryPanel(s->{},busy::set,null);ref.set(panel);set(panel,"session",session);set(panel,"confirm",(java.util.function.BiPredicate<String,String>)(title,text)->{confirms.incrementAndGet();return false;});equal(true,field(panel,"readOnly",JCheckBox.class).isSelected());equal(true,field(panel,"limitRows",JCheckBox.class).isSelected());field(panel,"editor",SqlEditorPane.class).restoreText("SELECT * FROM people;");panel.executeCurrent();}catch(Exception e){throw new RuntimeException(e);}});await(busy);equal(0,confirms.get());if(field(ref.get(),"currentResult",QueryResult.class)==null)throw new AssertionError(field(ref.get(),"messages",JTextArea.class).getText());equal(1000,field(ref.get(),"currentResult",QueryResult.class).rows().size());
            SwingUtilities.invokeAndWait(()->{field(ref.get(),"limitRows",JCheckBox.class).setSelected(false);ref.get().executeCurrent();});await(busy);equal(1205,field(ref.get(),"currentResult",QueryResult.class).rows().size());equal(0,confirms.get());
            SwingUtilities.invokeAndWait(()->{QueryPanel p=ref.get();field(p,"readOnly",JCheckBox.class).setSelected(false);field(p,"editor",SqlEditorPane.class).restoreText("DELETE FROM people;");p.executeCurrent();});equal(1,confirms.get());equal(1205,session.execute("SELECT * FROM people",2000).rows().size());
            SwingUtilities.invokeAndWait(()->{try{set(ref.get(),"confirm",(java.util.function.BiPredicate<String,String>)(title,text)->true);ref.get().executeCurrent();}catch(Exception e){throw new RuntimeException(e);}});await(busy);equal(0,session.execute("SELECT * FROM people",10).rows().size());
            SwingUtilities.invokeAndWait(()->{QueryResult result;try{session.execute("INSERT INTO people VALUES(7,'kept')",10);result=session.execute("SELECT * FROM people",10);}catch(Exception e){throw new RuntimeException(e);}ResultTableModel model=new ResultTableModel();model.setResult(result);JTable table=new JTable(model);QueryResultPanel grid=new QueryResultPanel(session,result,model,table,"SELECT * FROM people",10,x->{},s->{},t->{});equal(false,table.isCellEditable(0,1));try{set(grid,"confirm",(java.util.function.BiPredicate<String,String>)(title,text)->false);}catch(Exception e){throw new RuntimeException(e);}field(grid,"edit",JToggleButton.class).doClick();equal(false,field(grid,"edit",JToggleButton.class).isSelected());equal(false,table.isCellEditable(0,1));});
        }
        System.out.println("QuerySafetyTest: OK (default read-only, SELECT never prompts, default 1000/unlimited 1205 rows, No prevents DELETE, Yes executes only selected SQL, denied edit never unlocks grid)");
    }
    private static void await(AtomicBoolean busy)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(busy.get()&&System.nanoTime()<end)Thread.sleep(20);SwingUtilities.invokeAndWait(()->{});if(busy.get())throw new AssertionError("Operation timed out");}
    private static <T>T field(Object obj,String name,Class<T> type){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}catch(Exception e){throw new RuntimeException(e);}}
    private static void set(Object obj,String name,Object value)throws Exception{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);f.set(obj,value);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
