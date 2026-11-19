package com.nexodb.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.nexodb.tools.SqlProjectService;
import javax.swing.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class SqlProjectPanelTest {
    public static void main(String[] args)throws Exception{
        FlatDarkLaf.setup();Path root=Files.createTempDirectory("adisdb-project-panel-").resolve("project");SqlProjectService service=SqlProjectService.create(root,"Proyecto oscuro");Path script=service.createSql(Path.of("consulta.sql"));AtomicReference<Path> opened=new AtomicReference<>(),panelRoot=new AtomicReference<>();AtomicReference<SqlProjectPanel> holder=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{SqlProjectPanel panel=new SqlProjectPanel(service,opened::set);holder.set(panel);panelRoot.set(panel.root());});SqlProjectPanel panel=holder.get();equal(root.toAbsolutePath(),panelRoot.get());JTree tree=field(panel,"tree",JTree.class);await(()->tree.getRowCount()>=2);SwingUtilities.invokeAndWait(()->tree.setSelectionPath(tree.getPathForRow(1)));await(()->script.equals(field(panel,"current",Path.class)));SqlEditorPane editor=field(panel,"editor",SqlEditorPane.class);SwingUtilities.invokeAndWait(()->editor.restoreText("SELECT 'proyecto';\n"));await(()->field(panel,"dirty",Boolean.class));SwingUtilities.invokeAndWait(()->{equal(true,invoke(panel,"save"));invoke(panel,"openInQuery");});equal("SELECT 'proyecto';\n",service.read(script).text());equal(root.resolve(script),opened.get());equal(true,panel.canClose());
        System.out.println("SqlProjectPanelTest: OK (dark UI, project tree, edit/save and safe handoff to query editor)");
    }
    private static void await(BooleanSupplier condition)throws Exception{long end=System.currentTimeMillis()+10_000;while(System.currentTimeMillis()<end){AtomicBoolean result=new AtomicBoolean();SwingUtilities.invokeAndWait(()->result.set(condition.getAsBoolean()));if(result.get())return;Thread.sleep(25);}throw new AssertionError("Timed out");}
    private static Object invoke(Object target,String name,Object...args){try{Method method=Arrays.stream(target.getClass().getDeclaredMethods()).filter(item->item.getName().equals(name)&&item.getParameterCount()==args.length).findFirst().orElseThrow();method.setAccessible(true);return method.invoke(target,args);}catch(Exception error){throw new RuntimeException(error);}}
    private static <T>T field(Object target,String name,Class<T> type){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(target));}catch(Exception error){throw new RuntimeException(error);}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
