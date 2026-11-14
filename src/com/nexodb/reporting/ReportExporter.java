package com.nexodb.reporting;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** File export contract, with host-provided tabular exporters to keep database/UI dependencies out. */
public final class ReportExporter {
    public enum Format {HTML("Web / HTML","html"),CSV("CSV","csv"),XLSX("Excel","xlsx"),PDF("PDF","pdf");
        private final String label,extension;Format(String label,String extension){this.label=label;this.extension=extension;}
        public String extension(){return extension;}@Override public String toString(){return label;}
    }
    @FunctionalInterface public interface TabularWriter {void write(ReportData data,Path path,Format format)throws IOException;}
    private ReportExporter() {}
    /** Atomic replacement: failures never destroy an existing report. */
    public static void write(ReportPlan plan,Path target,Format format,TabularWriter tabular) throws IOException {
        target=target.toAbsolutePath();Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),".nxreport-",".tmp");
        try {
            switch(format) {
                case CSV,XLSX -> tabular.write(plan.tabular(),temp,format);
                case HTML -> html(plan,temp);
                case PDF -> ReportPdfWriter.write(ReportLayout.build(plan),temp);
            }
            try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    private static void html(ReportPlan plan,Path path)throws IOException {
        var d=plan.design();
        try(Writer out=Files.newBufferedWriter(path,StandardCharsets.UTF_8)) {
            out.write("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+escape(d.title())+"</title><style>"
                    +"*{box-sizing:border-box}body{margin:0;background:#eef2f4;color:#233a43;font:12px Arial,sans-serif}main{max-width:"+d.pageWidth()+"pt;margin:24px auto;background:white;padding:0 32pt 24pt;border-top:6px solid #"+color(d.accent())+"}header{position:relative;height:"+(d.headerHeight()-8)+"pt;margin-left:-32pt;margin-right:-32pt}.block{position:absolute;white-space:pre-wrap;overflow:hidden}.metric{padding:6pt 8pt;background:#f0f6f5;border-radius:6pt}.metric small{display:block;font-size:9pt;font-weight:normal}.metric strong{display:block;margin-top:4pt}table{width:100%;border-collapse:collapse;table-layout:fixed}thead{display:table-header-group}th{background:#"+color(d.accent())+";color:white;text-align:left}th,td{padding:6pt 5pt;vertical-align:top;overflow-wrap:anywhere;white-space:pre-wrap;border-bottom:1px solid #dce4e8}tbody tr:nth-child(even){background:#f4f7f9}.group{background:#e4efed!important;font-weight:bold}.summary{background:#edf2f5!important;font-weight:bold}.summary-label{padding-bottom:0;border:0}footer{margin-top:20pt;border-top:1px solid #ccd8dc;padding-top:10pt;color:#607080;font-size:9pt}img{width:100%;height:100%}@media print{body{background:white}main{margin:0;max-width:none;padding-top:0}tr{break-inside:avoid}thead{display:table-header-group}}@page{size:A4 "+(d.landscape()?"landscape":"portrait")+";margin:14mm 10mm}</style></head><body><main><header>");
            for(var b:d.blocks()) {
                out.write("<div class=\"block "+(b.kind()==ReportDesign.Kind.METRIC?"metric":"")+"\" style=\"left:"+b.x()+"pt;top:"+(b.y()-6)+"pt;width:"+b.width()+"pt;height:"+b.height()+"pt;font-size:"+b.fontSize()+"pt;color:#"+color(b.color())+";font-weight:"+(b.bold()?"bold":"normal")+"\">");
                if(b.kind()==ReportDesign.Kind.TEXT)out.write(escape(b.text()));
                else if(b.kind()==ReportDesign.Kind.METRIC){Object value=plan.metric(b);out.write("<small>"+escape(b.text())+"</small><strong>"+escape(value==null?"—":ReportPlan.display(value))+"</strong>");}
                else {
                    var bitmap=new java.awt.image.BufferedImage(b.width()*2,b.height()*2,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=bitmap.createGraphics();
                    try{g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,bitmap.getWidth(),bitmap.getHeight());g.scale(2,2);g.translate(-b.x(),-b.y());ReportRenderer.paintBlock(g,plan,b);}finally{g.dispose();}
                    var bytes=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",bytes);out.write("<img alt=\""+escape(b.text())+"\" src=\"data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray())+"\">");bitmap.flush();
                }
                out.write("</div>");
            }
            out.write("</header><table><colgroup>");int sum=d.visibleColumns().stream().mapToInt(ReportDesign.Column::width).sum();
            for(var column:d.visibleColumns())out.write("<col style=\"width:"+(column.width()*100.0/Math.max(1,sum))+"%\">");
            out.write("</colgroup><thead><tr>");for(var column:d.visibleColumns())out.write("<th>"+escape(column.title())+"</th>");out.write("</tr></thead><tbody>");
            for(var line:plan.lines()) {
                if(line.kind()==ReportPlan.Kind.GROUP)out.write("<tr class=\"group\"><td colspan=\""+d.visibleColumns().size()+"\" style=\"padding-left:"+(5+line.level()*10)+"pt\">"+escape(line.label())+"</td></tr>");
                else {
                    boolean summary=line.kind()!=ReportPlan.Kind.DETAIL;
                    if(summary)out.write("<tr class=\"summary\"><td class=\"summary-label\" colspan=\""+d.visibleColumns().size()+"\">"+escape(line.label())+"</td></tr>");
                    out.write("<tr"+(summary?" class=\"summary\"":"")+">");for(Object value:line.values())out.write("<td>"+(value==null?"":escape(ReportPlan.display(value)))+"</td>");out.write("</tr>");
                }
            }
            out.write("</tbody></table><footer>"+escape(plan.sourceNote())+"<br>"+escape(d.footer())+"</footer></main></body></html>");
        }
    }
    private static String color(int rgb){return String.format(Locale.ROOT,"%06x",rgb&0xffffff);}
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
