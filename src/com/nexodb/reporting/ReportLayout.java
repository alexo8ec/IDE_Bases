package com.nexodb.reporting;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/** Wraps text and splits long detail rows across pages without losing cell contents. */
public final class ReportLayout {
    public static final int MARGIN=32, LINE=13;
    public static final Font BODY=new Font(Font.SANS_SERIF,Font.PLAIN,9), BOLD=BODY.deriveFont(Font.BOLD);
    public record Row(ReportPlan.Line line,List<List<String>> cells,List<String> label,int offset,int count,int y,int height) {}
    public record Page(int tableY,List<Row> rows) {public Page{rows=List.copyOf(rows);}}
    private final ReportPlan plan;
    private final List<Page> pages;
    private final int[] widths;
    private final List<List<String>> headers;
    private final int headerHeight;
    private ReportLayout(ReportPlan plan,List<Page> pages,int[] widths,List<List<String>> headers,int headerHeight){this.plan=plan;this.pages=List.copyOf(pages);this.widths=widths;this.headers=headers;this.headerHeight=headerHeight;}
    public ReportPlan plan(){return plan;}
    public List<Page> pages(){return pages;}
    public int[] widths(){return widths.clone();}
    public List<List<String>> headers(){return headers;}
    public int tableHeaderHeight(){return headerHeight;}
    public static ReportLayout build(ReportPlan plan) {
        var design=plan.design();var visible=design.visibleColumns();int available=design.pageWidth()-2*MARGIN;
        int[] widths=new int[visible.size()];int total=visible.stream().mapToInt(ReportDesign.Column::width).sum(),used=0;
        for(int i=0;i<widths.length;i++){widths[i]=i==widths.length-1?available-used:Math.max(1,available*visible.get(i).width()/Math.max(1,total));used+=widths[i];}
        BufferedImage scratch=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);Graphics2D g=scratch.createGraphics();
        try {
            FontMetrics fm=g.getFontMetrics(BODY),bold=g.getFontMetrics(BOLD);
            List<List<String>> headers=new ArrayList<>();int headerLines=1;
            for(int i=0;i<visible.size();i++){var wrapped=wrap(visible.get(i).title(),bold,widths[i]-10);headers.add(wrapped);headerLines=Math.max(headerLines,wrapped.size());}
            int headerHeight=headerLines*LINE+10;
            if(headerHeight>120)throw new IllegalArgumentException("Los títulos de columna no caben en la página. Amplía columnas, acorta sus títulos o usa orientación horizontal.");
            List<Page> pages=new ArrayList<>();List<Row> page=new ArrayList<>();int tableY=design.headerHeight(),y=tableY+headerHeight,bottom=design.pageHeight()-65;
            List<ReportPlan.Line> activeGroups=new ArrayList<>();
            for(var line:plan.lines()) {
                if(line.kind()==ReportPlan.Kind.GROUP){while(activeGroups.size()>line.level())activeGroups.remove(activeGroups.size()-1);activeGroups.add(line);}
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                List<List<String>> cells=new ArrayList<>();int count=1;
                for(int i=0;i<widths.length;i++){Object v=line.values().get(i);var text=wrap(v==null?"":ReportPlan.display(v),line.kind()==ReportPlan.Kind.DETAIL?fm:bold,widths[i]-10);cells.add(text);count=Math.max(count,text.size());}
                var label=wrap(line.label(),bold,available-16-line.level()*10);
                boolean labeled=line.kind()!=ReportPlan.Kind.DETAIL;
                if(line.kind()==ReportPlan.Kind.GROUP)count=0;
                int offset=0;
                do {
                    int labelHeight=labeled?label.size()*LINE+6:0;
                    int remaining=count-offset;
                    int minimum=labelHeight+(remaining>0?LINE:0)+8;
                    if(y+minimum>bottom || line.kind()==ReportPlan.Kind.GROUP && y+minimum+LINE+8>bottom) {
                        pages.add(new Page(tableY,page));page=new ArrayList<>();tableY=48;y=tableY+headerHeight;
                        for(var group:activeGroups) {
                            if(group==line)continue;
                            var continuation=wrap(group.label()+" (continuación)",bold,available-16-group.level()*10);int continuationHeight=continuation.size()*LINE+14;
                            page.add(new Row(group,List.of(),continuation,0,0,y,continuationHeight));y+=continuationHeight;
                        }
                    }
                    if(minimum>bottom-y)throw new IllegalArgumentException("Una etiqueta de grupo es demasiado larga para la página. Usa valores de agrupación más cortos.");
                    int take=Math.min(remaining,Math.max(0,(bottom-y-labelHeight-8)/LINE));
                    int height=labelHeight+take*LINE+8;
                    page.add(new Row(line,cells,labeled?label:List.of(),offset,take,y,height));y+=height;offset+=take;
                    // Continued fragments repeat their summary label, while detail cells continue at the exact line offset.
                }while(offset<count);
                if(line.kind()==ReportPlan.Kind.SUBTOTAL)while(activeGroups.size()>line.level())activeGroups.remove(activeGroups.size()-1);
            }
            pages.add(new Page(tableY,page));
            return new ReportLayout(plan,pages,widths,List.copyOf(headers),headerHeight);
        }finally{g.dispose();}
    }
    public static List<String> wrap(String text,FontMetrics fm,int width) {
        width=Math.max(8,width);List<String> lines=new ArrayList<>();
        for(String paragraph:text.replace("\r\n","\n").replace('\r','\n').split("\n",-1)) {
            if(paragraph.isEmpty()){lines.add("");continue;}
            int start=0;
            while(start<paragraph.length()) {
                int end=start,lastSpace=-1,measured=0;
                while(end<paragraph.length()) {
                    int next=end+Character.charCount(paragraph.codePointAt(end));
                    int advance=fm.stringWidth(paragraph.substring(end,next));
                    if(end>start&&measured+advance>width)break;
                    if(Character.isWhitespace(paragraph.codePointAt(end)))lastSpace=next;
                    measured+=advance;end=next;
                }
                if(end<paragraph.length()&&lastSpace>start)end=lastSpace;
                lines.add(paragraph.substring(start,end));start=end;
            }
        }
        return List.copyOf(lines);
    }
}
