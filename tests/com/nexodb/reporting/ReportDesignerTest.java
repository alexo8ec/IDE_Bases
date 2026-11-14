package com.nexodb.reporting;

import com.nexodb.reporting.ui.ReportDesignerPanel;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;

public final class ReportDesignerTest {
    public static void main(String[] args)throws Exception {
        ReportData data=new ReportData("Preview",List.of("Region","Amount"),List.of(List.of("Costa",15),List.of("Sierra",20)),false);
        final ReportDesignerPanel[] ref=new ReportDesignerPanel[1];
        SwingUtilities.invokeAndWait(()->ref[0]=new ReportDesignerPanel(()->List.of(data),data,ReportingTest.TABULAR,s->{}));ReportDesignerPanel panel=ref[0];
        await(panel);
        SwingUtilities.invokeAndWait(()->{
            JComponent canvas=field(panel,"canvas",JComponent.class);canvas.setSize(canvas.getPreferredSize());
            ReportDesign before=field(panel,"design",ReportDesign.class);ReportDesign.Block metric=before.blocks().get(2);
            mouse(canvas,MouseEvent.MOUSE_PRESSED,60,150);mouse(canvas,MouseEvent.MOUSE_DRAGGED,120,150);mouse(canvas,MouseEvent.MOUSE_RELEASED,120,150);
            ReportDesign after=field(panel,"design",ReportDesign.class);if(after.blocks().get(2).x()<=metric.x())throw new AssertionError("Mouse drag did not move metric");
            invoke(panel,"undo");if(!before.equals(field(panel,"design",ReportDesign.class)))throw new AssertionError("Drag must undo as one operation");invoke(panel,"redo");
            @SuppressWarnings("unchecked") JList<ReportDesign.Block> list=field(panel,"blocks",JList.class);list.setSelectedIndex(0);field(panel,"blockText",JTextField.class).setText("Título gerencial editado");invoke(panel,"applyBlock");
            if(!field(panel,"design",ReportDesign.class).blocks().get(0).text().equals("Título gerencial editado"))throw new AssertionError("Title edit missing");
            JTable columns=field(panel,"columns",JTable.class);columns.getModel().setValueAt(ReportDesign.Aggregate.SUM,1,3);
            if(field(panel,"design",ReportDesign.class).columns().get(1).aggregate()!=ReportDesign.Aggregate.SUM)throw new AssertionError("Column aggregate edit missing");
        });
        await(panel);
        SwingUtilities.invokeAndWait(()->{try{JComponent canvas=field(panel,"canvas",JComponent.class);var image=new java.awt.image.BufferedImage(canvas.getWidth(),canvas.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();try{canvas.paint(g);}finally{g.dispose();}javax.imageio.ImageIO.write(image,"png",java.nio.file.Path.of("build/report-qa/designer.png").toFile());panel.dispose();}catch(Exception error){throw new RuntimeException(error);}});
        System.out.println("ReportDesignerTest: OK (real canvas mouse drag, atomic undo/redo, title edit, total configuration, async preview and rendering)");
    }
    private static void mouse(JComponent canvas,int id,int x,int y){canvas.dispatchEvent(new MouseEvent(canvas,id,System.currentTimeMillis(),0,16+(int)(x*.8),16+(int)(y*.8),1,false,MouseEvent.BUTTON1));}
    private static void await(ReportDesignerPanel panel)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){boolean[] ready={false};SwingUtilities.invokeAndWait(()->{var layout=field(panel,"layout",ReportLayout.class);ready[0]=layout!=null&&layout.plan().design()==field(panel,"design",ReportDesign.class);});if(ready[0])return;Thread.sleep(30);}throw new AssertionError("Preview timed out");}
    private static <T>T field(Object obj,String name,Class<T> type){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}catch(Exception error){throw new RuntimeException(error);}}
    private static void invoke(Object obj,String name){try{Method m=obj.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(obj);}catch(Exception error){throw new RuntimeException(error);}}
}
