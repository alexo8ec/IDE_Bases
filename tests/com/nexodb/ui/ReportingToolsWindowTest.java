package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.reporting.*;
import com.nexodb.reporting.ui.ReportDesignerPanel;
import com.nexodb.util.*;
import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;

public final class ReportingToolsWindowTest {
    public static void main(String[] args)throws Exception {
        Path test=Files.createTempDirectory("nexodb-report-ui-");System.setProperty("nexodb.settingsFile",test.resolve("settings.properties").toString());FlatLightLaf.setup();I18n.initialize("es");
        var profile=new ConnectionProfile("report-tools","Test",DatabaseType.H2,"",0,"mem:report_tools","sa","jdbc:h2:mem:report_tools","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])) {
            session.execute("CREATE TABLE sales(id INT PRIMARY KEY,region VARCHAR(30),amount DECIMAL(12,2))",1);session.execute("INSERT INTO sales VALUES(1,'Costa',15.75),(2,'Sierra',20.25)",1);
            AtomicReference<MainFrame> ref=new AtomicReference<>();AtomicReference<ReportDesignerPanel> reportRef=new AtomicReference<>();AtomicReference<ExecutionPlanPanel> planRef=new AtomicReference<>();AtomicReference<AiAssistantPanel> aiRef=new AtomicReference<>();
            SwingUtilities.invokeAndWait(()->{try{MainFrame frame=new MainFrame();ref.set(frame);frame.setSize(1400,900);frame.setVisible(true);invoke(frame,"openReports");ReportDesignerPanel report=(ReportDesignerPanel)field(frame,"workspaceTabs",JTabbedPane.class).getSelectedComponent();reportRef.set(report);if(!field(report,"data",ReportData.class).rows().isEmpty())throw new AssertionError("Standalone report must start empty");
                set(frame,"session",session);field(frame,"sessions",Map.class).put(profile.id(),session);QueryPanel query=field(frame,"queryPanel",QueryPanel.class);set(query,"session",session);query.openNewQuery("SELECT * FROM sales ORDER BY id");query.executeCurrent();
            }catch(Exception error){throw new RuntimeException(error);}});
            await(()->!field(field(ref.get(),"queryPanel",QueryPanel.class),"queryRunning",Boolean.class));
            SwingUtilities.invokeAndWait(()->{try{
                MainFrame frame=ref.get();QueryPanel query=field(frame,"queryPanel",QueryPanel.class);if(query.reportSources().size()!=1||query.reportSources().get(0).rows().size()!=2)throw new AssertionError("Report bridge lost data");
                invoke(frame,"openReports");ReportDesignerPanel report=(ReportDesignerPanel)field(frame,"workspaceTabs",JTabbedPane.class).getSelectedComponent();reportRef.set(report);
                invoke(frame,"openPlan");ExecutionPlanPanel plan=(ExecutionPlanPanel)field(frame,"workspaceTabs",JTabbedPane.class).getSelectedComponent();planRef.set(plan);Method result=ExecutionPlanPanel.class.getDeclaredMethod("setResult",QueryPlan.Result.class);result.setAccessible(true);result.invoke(plan,QueryPlan.parse(DatabaseType.SQLSERVER,QueryPlanTest.XML,true,25));
                var ops=field(plan,"operators",javax.swing.table.DefaultTableModel.class);boolean timeFound=false;for(int i=0;i<ops.getRowCount();i++)if(Double.valueOf(22).equals(ops.getValueAt(i,3)))timeFound=true;if(!timeFound)throw new AssertionError("Measured operator time missing");
                field(plan,"askAi",JButton.class).doClick();AiAssistantPanel ai=(AiAssistantPanel)field(frame,"workspaceTabs",JTabbedPane.class).getSelectedComponent();aiRef.set(ai);if(!field(ai,"plan",JTextArea.class).getText().contains("ShowPlanXML"))throw new AssertionError("Plan not passed for AI review");
                field(ai,"proposal",SqlEditorPane.class).restoreText("DELETE FROM sales;");field(ai,"open",JButton.class).setEnabled(true);field(ai,"open",JButton.class).doClick();if(!field(query,"readOnly",JCheckBox.class).isSelected())throw new AssertionError("AI SQL must open in read-only mode");
                field(frame,"workspaceTabs",JTabbedPane.class).setSelectedComponent(plan);
            }catch(Exception error){throw new RuntimeException(error);}});
            if(session.execute("SELECT * FROM sales",10).rows().size()!=2)throw new AssertionError("AI proposal was executed");
            SwingUtilities.invokeAndWait(()->{try{MainFrame frame=ref.get();frame.validate();capture(frame,"build/report-qa/plan-window.png");field(frame,"workspaceTabs",JTabbedPane.class).setSelectedComponent(reportRef.get());frame.validate();capture(frame,"build/report-qa/report-window.png");}catch(Exception error){throw new RuntimeException(error);}});
            SwingUtilities.invokeAndWait(()->{for(Component child:field(ref.get(),"workspaceTabs",JTabbedPane.class).getComponents()){if(child instanceof ReportDesignerPanel report)report.dispose();if(child instanceof AiAssistantPanel ai)ai.dispose();}ref.get().dispose();});
        }
        System.out.println("ReportingToolsWindowTest: OK (standalone menu without connection, live result bridge, planner graph/runtime table, plan-to-AI review, SQL opens read-only without execution, actual window rendering)");
    }
    private static void capture(JFrame frame,String path)throws Exception{var image=new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();try{frame.paint(g);}finally{g.dispose();}javax.imageio.ImageIO.write(image,"png",Path.of(path).toFile());}
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){boolean[] done={false};SwingUtilities.invokeAndWait(()->done[0]=condition.getAsBoolean());if(done[0])return;Thread.sleep(30);}throw new AssertionError("UI operation timed out");}
    private static <T>T field(Object obj,String name,Class<T> type){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}catch(Exception error){throw new RuntimeException(error);}}
    private static void set(Object obj,String name,Object value)throws Exception{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);f.set(obj,value);}
    private static void invoke(Object obj,String name)throws Exception{Method m=obj.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(obj);}
}
