package com.nexodb.reporting;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.util.List;

/** One painter for the designer, paginated preview, printing and PDF. */
public final class ReportRenderer {
    private ReportRenderer() {}
    public static BufferedImage image(ReportLayout layout,int page,double scale) {
        ReportDesign d=layout.plan().design();BufferedImage image=new BufferedImage((int)Math.ceil(d.pageWidth()*scale),(int)Math.ceil(d.pageHeight()*scale),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();try{g.scale(scale,scale);paint(g,layout,page);}finally{g.dispose();}return image;
    }
    public static void paint(Graphics2D g,ReportLayout layout,int pageIndex) {
        var plan=layout.plan();var d=plan.design();var page=layout.pages().get(pageIndex);int width=d.pageWidth(),height=d.pageHeight(),tableWidth=width-64;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);g.fillRect(0,0,width,height);
        g.setColor(new Color(d.accent()));g.fillRect(0,0,width,6);
        if(pageIndex==0)for(var block:d.blocks())paintBlock(g,plan,block);
        else {g.setFont(ReportLayout.BOLD);g.setColor(new Color(0x29414A));g.drawString(fit(g,d.title(),tableWidth),32,30);}
        int[] widths=layout.widths();int x=32;
        g.setFont(ReportLayout.BOLD);
        for(int i=0;i<widths.length;i++) {
            g.setColor(new Color(d.accent()));g.fillRect(x,page.tableY(),widths[i],layout.tableHeaderHeight());g.setColor(Color.WHITE);
            int y=page.tableY()+16;for(String line:layout.headers().get(i)){g.drawString(line,x+5,y);y+=ReportLayout.LINE;}
            x+=widths[i];
        }
        int stripe=0;
        for(var row:page.rows()) {
            var line=row.line();boolean detail=line.kind()==ReportPlan.Kind.DETAIL;
            Color background=detail?(stripe++%2==0?Color.WHITE:new Color(0xF4F7F9)):line.kind()==ReportPlan.Kind.GROUP?new Color(0xE4EFED):new Color(0xEDF2F5);
            g.setColor(background);g.fillRect(32,row.y(),tableWidth,row.height());
            g.setColor(new Color(0x233A43));g.setFont(detail?ReportLayout.BODY:ReportLayout.BOLD);
            int labelHeight=0;
            if(!row.label().isEmpty()) {
                int y=row.y()+14;for(String label:row.label()){g.drawString(label,40+line.level()*10,y);y+=ReportLayout.LINE;}
                labelHeight=row.label().size()*ReportLayout.LINE+6;
            }
            x=32;
            for(int i=0;i<widths.length;i++) {
                int y=row.y()+labelHeight+14;
                for(int n=row.offset();n<row.offset()+row.count();n++) {
                    if(n<row.cells().get(i).size())g.drawString(row.cells().get(i).get(n),x+5,y);
                    y+=ReportLayout.LINE;
                }
                x+=widths[i];
            }
            g.setColor(new Color(0xDCE4E8));g.drawLine(32,row.y()+row.height(),width-32,row.y()+row.height());
        }
        if(plan.data().columns().isEmpty()){g.setFont(ReportLayout.BODY);g.setColor(new Color(0x607080));g.drawString("Selecciona un resultado de consulta para incorporar datos.",32,page.tableY()+30);}
        else if(plan.data().rows().isEmpty()){g.setFont(ReportLayout.BODY);g.setColor(new Color(0x607080));g.drawString("El resultado no contiene registros.",32,page.tableY()+layout.tableHeaderHeight()+18);}
        g.setColor(new Color(0xCCD8DC));g.drawLine(32,height-51,width-32,height-51);
        g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,8));g.setColor(new Color(0x607080));
        g.drawString(fit(g,plan.sourceNote(),tableWidth),32,height-36);
        g.drawString(fit(g,d.footer(),tableWidth-90),32,height-21);
        String pages=(pageIndex+1)+" / "+layout.pages().size();g.drawString(pages,width-32-g.getFontMetrics().stringWidth(pages),height-21);
    }
    public static void paintBlock(Graphics2D original,ReportPlan plan,ReportDesign.Block b) {
        Graphics2D g=(Graphics2D)original.create();
        try {
            g.clipRect(b.x(),b.y(),b.width(),b.height());g.setColor(new Color(b.color()));
            if(b.kind()==ReportDesign.Kind.TEXT) {
                g.setFont(new Font(Font.SANS_SERIF,b.bold()?Font.BOLD:Font.PLAIN,b.fontSize()));
                int y=b.y()+b.fontSize();int step=g.getFontMetrics().getHeight();
                for(String line:ReportLayout.wrap(b.text(),g.getFontMetrics(),b.width())){g.drawString(line,b.x(),y);y+=step;}
            } else if(b.kind()==ReportDesign.Kind.METRIC) {
                g.setColor(new Color(0xF0F6F5));g.fillRoundRect(b.x(),b.y(),b.width(),b.height(),8,8);g.setColor(new Color(b.color()));
                g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,9));g.drawString(fit(g,b.text(),b.width()-16),b.x()+8,b.y()+13);
                g.setFont(new Font(Font.SANS_SERIF,b.bold()?Font.BOLD:Font.PLAIN,b.fontSize()));
                Object value=plan.metric(b);g.drawString(fit(g,value==null?"—":ReportPlan.display(value),b.width()-16),b.x()+8,b.y()+b.height()-8);
            } else {
                g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,10));g.drawString(fit(g,b.text(),b.width()),b.x(),b.y()+12);
                List<ReportPlan.Bar> bars=plan.bars(b);int step=Math.max(12,(b.height()-34)/Math.max(1,bars.size()));
                double min=0,max=0;for(var bar:bars){double value=finite(bar.value());min=Math.min(min,value);max=Math.max(max,value);}
                double range=max-min;if(range==0)range=1;
                int labelWidth=Math.min(100,b.width()/3),graphWidth=Math.max(1,b.width()-labelWidth-65),base=b.x()+labelWidth;
                g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,8));
                for(int i=0;i<bars.size();i++) {
                    var bar=bars.get(i);int y=b.y()+25+i*step;
                    g.setColor(new Color(0x4C626A));g.drawString(fit(g,bar.label(),labelWidth-6),b.x(),y+4);
                    int zero=base+(int)(-min/range*graphWidth),end=base+(int)((finite(bar.value())-min)/range*graphWidth);
                    g.setColor(new Color(b.color()));g.fillRect(Math.min(zero,end),y-5,Math.max(1,Math.abs(end-zero)),Math.max(4,step-5));
                    g.setColor(new Color(0x29414A));g.drawString(fit(g,ReportPlan.display(bar.value()),60),base+graphWidth+5,y+4);
                }
                g.setColor(new Color(0x607080));g.drawString("Hasta 8 categorías principales",b.x(),b.y()+b.height()-2);
            }
        }finally{g.dispose();}
    }
    private static double finite(BigDecimal number) {double n=number.doubleValue();return Double.isFinite(n)?n:Math.copySign(Double.MAX_VALUE/4,n);}
    public static String fit(Graphics2D g,String value,int width) {
        if(g.getFontMetrics().stringWidth(value)<=width)return value;
        int end=value.length();while(end>0&&g.getFontMetrics().stringWidth(value.substring(0,end)+"…")>width)end=value.offsetByCodePoints(end,-1);
        return value.substring(0,end)+"…";
    }
}
