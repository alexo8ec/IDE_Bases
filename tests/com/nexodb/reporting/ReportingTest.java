package com.nexodb.reporting;

import com.nexodb.db.QueryResult;
import com.nexodb.util.DataExporter;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static com.nexodb.reporting.ReportDesign.*;

public final class ReportingTest {
    public static final ReportExporter.TabularWriter TABULAR=(data,path,format)->DataExporter.write(new QueryResult(data.columns(),data.rows(),-1,0,false),path,format==ReportExporter.Format.XLSX?DataExporter.Format.XLSX:DataExporter.Format.CSV,"reporte");
    public static void main(String[] args)throws Exception {
        Path folder=Path.of("build/report-qa");Files.createDirectories(folder);
        var data=new ReportData("Ventas de prueba",List.of("Región","Sucursal","Importe","Observación"),List.of(
                Arrays.asList("Costa","Guayaquil",new BigDecimal("15.75"),"Entrega <script>alert('x')</script> & confirmación"),
                Arrays.asList("Sierra","Quito",new BigDecimal("20.25"),"Sin novedades"),
                Arrays.asList("Costa","Guayaquil",new BigDecimal("-5.00"),null),
                Arrays.asList(null,"Sin asignar",null,"Texto con Unicode: mañana 日本語 😀")),true);
        ReportDesign base=ReportDesign.create(data);var cols=new ArrayList<>(base.columns());cols.set(2,new Column(2,"Ventas netas",130,true,Aggregate.SUM));
        var design=new ReportDesign("Ventas por región",false,260,"Preparado para gerencia",0x176B65,data.columns(),cols,List.of(0,1),List.of(
                new Block("title",Kind.TEXT,32,30,530,40,"Ventas por región",24,true,0x173A40,-1,Aggregate.NONE),
                new Block("subtitle",Kind.TEXT,32,77,530,28,"Resumen comercial · datos de prueba",12,false,0x607080,-1,Aggregate.NONE),
                new Block("count",Kind.METRIC,32,115,160,48,"Registros",22,true,0x176B65,-1,Aggregate.COUNT),
                new Block("sum",Kind.METRIC,220,115,180,48,"Ventas netas",22,true,0x176B65,2,Aggregate.SUM),
                new Block("note",Kind.TEXT,32,188,530,42,"Agrupación por región y sucursal, con subtotales y total general.",11,false,0x607080,-1,Aggregate.NONE)));
        ReportPlan plan=ReportPlan.build(design,data);
        equal(4L,plan.lines().stream().filter(l->l.kind()==ReportPlan.Kind.DETAIL).count());
        equal(new BigDecimal("31.00"),plan.lines().get(plan.lines().size()-1).values().get(2));
        equal(4L,plan.metric(design.blocks().get(2)));equal(new BigDecimal("31.00"),plan.metric(design.blocks().get(3)));
        equal(new BigDecimal("10.75"),plan.lines().stream().filter(l->l.kind()==ReportPlan.Kind.SUBTOTAL&&l.level()==0&&l.label().contains("Costa")).findFirst().orElseThrow().values().get(2));
        equal(new BigDecimal("10.33333333333333333333333333333333"),ReportPlan.aggregate(data.rows(),2,Aggregate.AVG));
        equal(new BigDecimal("-5.00"),ReportPlan.aggregate(data.rows(),2,Aggregate.MIN));equal(new BigDecimal("20.25"),ReportPlan.aggregate(data.rows(),2,Aggregate.MAX));
        equal(3L,ReportPlan.aggregate(data.rows(),2,Aggregate.COUNT));
        Path template=folder.resolve("ventas.nxreport");ReportStore.save(template,design);equal(design,ReportStore.load(template));
        String saved=Files.readString(template);if(saved.contains("alert('x')")||saved.contains("15.75"))throw new AssertionError("Templates must not contain result rows");
        try{design.requireCompatible(new ReportData("Different",List.of("x"),List.of(),false));throw new AssertionError("Schema mismatch accepted");}catch(IllegalArgumentException expected){}
        for(var format:ReportExporter.Format.values())ReportExporter.write(plan,folder.resolve("ventas."+format.extension()),format,TABULAR);
        String html=Files.readString(folder.resolve("ventas.html"));if(html.contains("<script>")||!html.contains("&lt;script&gt;")||!html.contains("Subtotal")||!html.contains("resultado limitado"))throw new AssertionError("HTML escaping/grouping/limit note missing");
        String csv=Files.readString(folder.resolve("ventas.csv"));if(!csv.contains("Total general")||!csv.contains("31.00"))throw new AssertionError("CSV totals missing");
        try(var zip=new java.util.zip.ZipFile(folder.resolve("ventas.xlsx").toFile())){String xml=new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(!xml.contains("Subtotal")||!xml.contains("31.00"))throw new AssertionError("Excel report totals missing");}
        var layout=ReportLayout.build(plan);ImageIO.write(ReportRenderer.image(layout,0,1.3),"png",folder.resolve("ventas.png").toFile());
        String longText="Detalle largo con espacios y números 1234567890. ".repeat(250);
        var longData=new ReportData("Texto largo",List.of("Descripción"),List.of(List.of(longText)),false);var longPlan=ReportPlan.build(ReportDesign.create(longData),longData);var longLayout=ReportLayout.build(longPlan);
        if(longLayout.pages().size()<2)throw new AssertionError("Long cells must paginate");
        int previous=0;StringBuilder reconstructed=new StringBuilder();
        for(var page:longLayout.pages())for(var row:page.rows()) {
            if(row.y()+row.height()>longPlan.design().pageHeight()-65)throw new AssertionError("Row crosses footer");
            if(row.line().kind()==ReportPlan.Kind.DETAIL){equal(previous,row.offset());for(int i=row.offset();i<row.offset()+row.count();i++)if(i<row.cells().get(0).size())reconstructed.append(row.cells().get(0).get(i));previous+=row.count();}
        }
        equal(longText,reconstructed.toString());
        ReportExporter.write(longPlan,folder.resolve("long-text.pdf"),ReportExporter.Format.PDF,TABULAR);
        ImageIO.write(ReportRenderer.image(longLayout,longLayout.pages().size()-1,1.0),"png",folder.resolve("last-page.png").toFile());
        var numericGroups=new ReportData("Numeric keys",List.of("Key"),List.of(List.of(new BigDecimal("1.0")),List.of(new BigDecimal("1.00"))),false);var numericPlan=ReportPlan.build(ReportDesign.create(numericGroups).withGroups(List.of(0)),numericGroups);equal(1L,numericPlan.lines().stream().filter(l->l.kind()==ReportPlan.Kind.GROUP).count());
        Path preserved=folder.resolve("preserved.csv");Files.writeString(preserved,"KEEP");try{ReportExporter.write(plan,preserved,ReportExporter.Format.CSV,(d,p,f)->{Files.writeString(p,"PARTIAL");throw new java.io.IOException("Test export failure");});throw new AssertionError("Expected failed export");}catch(java.io.IOException expected){}equal("KEEP",Files.readString(preserved));
        try(var files=Files.list(folder)){if(files.anyMatch(p->p.getFileName().toString().endsWith(".tmp")))throw new AssertionError("Export temporary files leaked");}
        System.out.println("ReportingTest: OK (nested grouping, exact decimal totals, NULL, template round trip/no rows, all four exports, HTML escaping, Unicode, lossless pagination, atomic export failure)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
