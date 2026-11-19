package com.nexodb.ui;

import com.nexodb.util.BackgroundOperations;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.util.Objects;

public final class BackgroundOperationsPanelTest {
    public static void main(String[] args)throws Exception {
        System.setProperty("nexodb.settingsFile",Files.createTempDirectory("adisdb-operation-ui").resolve("settings.properties").toString());
        BackgroundOperations.clearFinished();BackgroundOperations.configure(2,1,60);
        BackgroundOperations.Operation operation=BackgroundOperations.create(BackgroundOperations.Kind.EXPORT,"Exportar resultado","MySQL local",()->{},null);
        operation.awaitTurn();operation.progress(35,"350 filas");
        BackgroundOperationsPanel[] panel=new BackgroundOperationsPanel[1];
        SwingUtilities.invokeAndWait(()->panel[0]=new BackgroundOperationsPanel());
        SwingUtilities.invokeAndWait(()->{});
        JTable table=(JTable)find(panel[0],"operations-table");
        if(table==null||table.getRowCount()!=1)throw new AssertionError("The active operation is not visible");
        equal("Exportar resultado",table.getValueAt(0,0));equal("35%",table.getValueAt(0,7));
        operation.complete("350 filas");SwingUtilities.invokeAndWait(()->{});
        if(table.getRowCount()!=1)throw new AssertionError("The short history disappeared");
        SwingUtilities.invokeAndWait(panel[0]::disposePanel);BackgroundOperations.clearFinished();BackgroundOperations.configure(4,2,120);
        System.out.println("BackgroundOperationsPanelTest: OK (central active/history view, priority and progress)");
    }
    private static Component find(Container root,String name){for(Component component:root.getComponents()){if(Objects.equals(name,component.getName()))return component;if(component instanceof Container child){Component found=find(child,name);if(found!=null)return found;}}return null;}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
