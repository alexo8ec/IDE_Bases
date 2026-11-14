package com.nexodb.reporting;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.nexodb.reporting.ReportDesign.*;

/** Versioned templates contain layout and field bindings, never SQL, credentials or result rows. */
public final class ReportStore {
    private ReportStore() {}
    public static void save(Path path,ReportDesign design) throws IOException {
        Properties p=new Properties();p.setProperty("format","nexodb-report-1");
        p.setProperty("title",design.title());p.setProperty("landscape",Boolean.toString(design.landscape()));
        p.setProperty("header",Integer.toString(design.headerHeight()));p.setProperty("footer",design.footer());p.setProperty("accent",Integer.toString(design.accent()));
        p.setProperty("sources",Integer.toString(design.sourceColumns().size()));
        for(int i=0;i<design.sourceColumns().size();i++)p.setProperty("source."+i,design.sourceColumns().get(i));
        p.setProperty("columns",Integer.toString(design.columns().size()));
        for(int i=0;i<design.columns().size();i++) {
            Column c=design.columns().get(i);String k="column."+i+".";
            p.setProperty(k+"source",""+c.source());p.setProperty(k+"title",c.title());p.setProperty(k+"width",""+c.width());p.setProperty(k+"visible",""+c.visible());p.setProperty(k+"aggregate",c.aggregate().name());
        }
        p.setProperty("groups",String.join(",",design.groups().stream().map(Object::toString).toList()));
        p.setProperty("blocks",""+design.blocks().size());
        for(int i=0;i<design.blocks().size();i++) {
            Block b=design.blocks().get(i);String k="block."+i+".";
            p.setProperty(k+"id",b.id());p.setProperty(k+"kind",b.kind().name());p.setProperty(k+"x",""+b.x());p.setProperty(k+"y",""+b.y());p.setProperty(k+"width",""+b.width());p.setProperty(k+"height",""+b.height());
            p.setProperty(k+"text",b.text());p.setProperty(k+"font",""+b.fontSize());p.setProperty(k+"bold",""+b.bold());p.setProperty(k+"color",""+b.color());p.setProperty(k+"field",""+b.field());p.setProperty(k+"aggregate",b.aggregate().name());
        }
        Path target=path.toAbsolutePath();Files.createDirectories(target.getParent());Path temporary=Files.createTempFile(target.getParent(),".nxreport-",".tmp");
        try {
            try(Writer writer=Files.newBufferedWriter(temporary)){p.store(writer,"AdisDB report template - no result data");}
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
    public static ReportDesign load(Path path) throws IOException {
        if(Files.size(path)>2_000_000)throw new IOException("La plantilla supera 2 MB.");
        Properties p=new Properties();try(Reader reader=Files.newBufferedReader(path)){p.load(reader);}
        try {
            if(!"nexodb-report-1".equals(p.getProperty("format")))throw new IllegalArgumentException("Formato de plantilla no compatible.");
            int sources=integer(p,"sources",0,16384),columnCount=integer(p,"columns",0,16384),blockCount=integer(p,"blocks",0,100);
            List<String> fields=new ArrayList<>();for(int i=0;i<sources;i++)fields.add(required(p,"source."+i));
            List<Column> columns=new ArrayList<>();
            for(int i=0;i<columnCount;i++){String k="column."+i+".";columns.add(new Column(integer(p,k+"source",0,sources-1),required(p,k+"title"),integer(p,k+"width",30,1000),bool(p,k+"visible"),Aggregate.valueOf(required(p,k+"aggregate"))));}
            List<Integer> groups=new ArrayList<>();String grouping=required(p,"groups");if(!grouping.isBlank())for(String group:grouping.split(","))groups.add(Integer.valueOf(group));
            List<Block> blocks=new ArrayList<>();
            for(int i=0;i<blockCount;i++){String k="block."+i+".";blocks.add(new Block(required(p,k+"id"),Kind.valueOf(required(p,k+"kind")),integer(p,k+"x",24,842),integer(p,k+"y",24,842),integer(p,k+"width",40,842),integer(p,k+"height",24,842),required(p,k+"text"),integer(p,k+"font",8,48),bool(p,k+"bold"),Integer.parseInt(required(p,k+"color")),integer(p,k+"field",-1,sources-1),Aggregate.valueOf(required(p,k+"aggregate"))));}
            return new ReportDesign(required(p,"title"),bool(p,"landscape"),integer(p,"header",90,682),required(p,"footer"),Integer.parseInt(required(p,"accent")),fields,columns,groups,blocks);
        }catch(IllegalArgumentException error){throw new IOException("Plantilla de reporte no válida: "+error.getMessage(),error);}
    }
    private static String required(Properties p,String key){String value=p.getProperty(key);if(value==null)throw new IllegalArgumentException("Falta "+key);return value;}
    private static int integer(Properties p,String key,int min,int max){int n=Integer.parseInt(required(p,key));if(n<min||n>max)throw new IllegalArgumentException("Fuera de rango: "+key);return n;}
    private static boolean bool(Properties p,String key){String value=required(p,key);if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("Valor booleano no válido: "+key);return Boolean.parseBoolean(value);}
}
