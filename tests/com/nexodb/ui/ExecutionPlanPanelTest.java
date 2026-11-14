package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.util.I18n;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Headless regression for the visible metrics, warnings, selection and result replacement. */
public final class ExecutionPlanPanelTest {
    public static void main(String[] args)throws Exception {
        I18n.initialize("es");
        var profile=new ConnectionProfile("plan-ui","Test",DatabaseType.H2,"",0,"mem:plan_ui","sa","jdbc:h2:mem:plan_ui","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])) {
            String xml=QueryPlanTest.XML.replace("<QueryPlan>","<QueryPlan><QueryTimeStats ElapsedTime=\"125\" CpuTime=\"80\"/>")
                .replace("<TableScan>","<RunTimeInformation><RunTimeCountersPerThread Thread=\"0\" ActualRows=\"10\" ActualRowsRead=\"1000000\" ActualExecutions=\"1\" ActualElapsedms=\"90\" ActualCPUms=\"70\"/></RunTimeInformation><TableScan>");
            SwingUtilities.invokeAndWait(()->{try {
                var panel=new ExecutionPlanPanel(session,"SELECT * FROM sales ORDER BY id",b->{},s->{});
                setResult(panel,QueryPlan.parse(DatabaseType.SQLSERVER,xml,true,9000));
                var model=field(panel,"operators",DefaultTableModel.class);int scan=row(model,"Table Scan · [sales]");
                eq(90d,model.getValueAt(scan,3));eq(1000000d,model.getValueAt(scan,8));eq(10d,model.getValueAt(scan,7));
                String summary=field(panel,"summary",JLabel.class).getText();contains(summary,"125 ms");contains(summary,"1 alertas de carga alta");
                if(summary.contains("9000"))throw new AssertionError("Capture duration presented as execution time");
                String report=field(panel,"diagnostics",JTextArea.class).getText();contains(report,"1000000");contains(report,"no confirma un índice faltante");
                var properties=field(panel,"properties",DefaultTableModel.class);eq("1000000",property(properties,PlanDiagnostics.READ));
                JTable table=(JTable)component(panel,"execution-plan-operators");table.getRowSorter().toggleSortOrder(3);
                table.setRowSelectionInterval(table.convertRowIndexToView(scan),table.convertRowIndexToView(scan));eq("[sales]",property(properties,PlanDiagnostics.TABLE));
                int sort=row(model,"Sort");table.setRowSelectionInterval(table.convertRowIndexToView(sort),table.convertRowIndexToView(sort));eq("22",property(properties,PlanDiagnostics.TIME));
                panel.setSize(1200,720);layout(panel);var image=new BufferedImage(1200,720,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();try{panel.paint(graphics);}finally{graphics.dispose();}
                Files.createDirectories(Path.of("build/report-qa"));javax.imageio.ImageIO.write(image,"png",Path.of("build/report-qa/planner-metrics.png").toFile());
                setResult(panel,QueryPlan.parse(DatabaseType.MYSQL,"""
                    {"query_block":{"table":{"table_name":"sales","access_type":"ref","key":"IX_region","rows_examined_per_scan":5,"used_key_parts":["region"]}}}
                    """,false,1));
                int index=row(model,"ref · sales");eq("IX_region",model.getValueAt(index,5));eq("ref",model.getValueAt(index,6));eq(null,model.getValueAt(index,3));
                contains(field(panel,"summary",JLabel.class).getText(),"Plan estimado");
                if(field(panel,"diagnostics",JTextArea.class).getText().contains("1000000"))throw new AssertionError("Stale diagnostics");
                setResult(panel,QueryPlan.parse(DatabaseType.H2,"SELECT 1",true,8));contains(field(panel,"summary",JLabel.class).getText(),"no reportó tiempos");
                if(model.getRowCount()!=2)throw new AssertionError("Stale operator rows");
            }catch(Exception e){throw new RuntimeException(e);}});
        }
        System.out.println("ExecutionPlanPanelTest: OK (timings, scanned rows, index access, diagnostics, sorted selection, stale results and headless rendering)");
    }
    private static void setResult(ExecutionPlanPanel panel,QueryPlan.Result result)throws Exception {var method=ExecutionPlanPanel.class.getDeclaredMethod("setResult",QueryPlan.Result.class);method.setAccessible(true);method.invoke(panel,result);}
    private static int row(DefaultTableModel m,String name){for(int r=0;r<m.getRowCount();r++)if(name.equals(m.getValueAt(r,0)))return r;throw new AssertionError("Missing "+name);}
    private static Object property(DefaultTableModel m,String name){for(int r=0;r<m.getRowCount();r++)if(name.equals(m.getValueAt(r,0)))return m.getValueAt(r,1);return null;}
    private static Component component(Container root,String name){for(Component c:root.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container nested){Component found=component(nested,name);if(found!=null)return found;}}return null;}
    private static void layout(Container root){root.doLayout();for(Component c:root.getComponents())if(c instanceof Container nested)layout(nested);}
    private static <T>T field(Object obj,String name,Class<T> type)throws Exception {var f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}
    private static void contains(String text,String part){if(!text.contains(part))throw new AssertionError("Missing "+part+" in "+text);}
    private static void eq(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
