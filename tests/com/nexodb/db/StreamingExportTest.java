package com.nexodb.db;

import com.nexodb.model.*;
import com.nexodb.util.DataExporter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class StreamingExportTest {
    public static void main(String[] args)throws Exception{
        var profile=new ConnectionProfile("stream-export","Test",DatabaseType.H2,"",0,"mem:stream_export","sa","jdbc:h2:mem:stream_export","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        Path folder=Files.createTempDirectory("adisdb-stream-export-");
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            session.execute("CREATE TABLE entries(id INT PRIMARY KEY,name VARCHAR(50))",1);
            session.execute("INSERT INTO entries SELECT X, 'row-' || X FROM SYSTEM_RANGE(1,25005)",1);
            QueryResult first=session.executePage("SELECT * FROM entries ORDER BY id",1000,0);equal(1000,first.rows().size());
            Path csv=folder.resolve("all.csv");AtomicLong progress=new AtomicLong();
            long exported=DataExporter.writeStreaming(csv,DataExporter.Format.CSV,"entries",writer->session.streamResult("SELECT * FROM entries ORDER BY id",List.of(),Long.MAX_VALUE,bridge(writer),progress::set));
            equal(25005L,exported);equal(25005L,progress.get());
            try(var lines=Files.lines(csv)){equal(25006L,lines.count());}
            String tail=Files.readString(csv);contains(tail,"25005,row-25005");

            Path json=folder.resolve("filtered.json");
            long filtered=DataExporter.writeStreaming(json,DataExporter.Format.JSON,"entries",writer->session.streamResult("SELECT * FROM entries WHERE id > ? ORDER BY id",List.of(24990),Long.MAX_VALUE,bridge(writer),null));
            equal(15L,filtered);String jsonText=Files.readString(json);contains(jsonText,"row-25005");if(jsonText.contains("row-24990"))throw new AssertionError("Prepared filter was not applied");

            Path existing=folder.resolve("preserved.csv");Files.writeString(existing,"KEEP");
            try{DataExporter.writeStreaming(existing,DataExporter.Format.CSV,"entries",writer->{writer.start(List.of("id"));writer.row(List.of(1));throw new java.io.IOException("failure");});throw new AssertionError("Expected export failure");}catch(java.io.IOException expected){}
            equal("KEEP",Files.readString(existing));
            try(var files=Files.list(folder)){if(files.anyMatch(path->path.getFileName().toString().endsWith(".tmp")))throw new AssertionError("Temporary export leaked");}
        }finally{try(var files=Files.list(folder)){for(Path file:files.toList())Files.deleteIfExists(file);}Files.deleteIfExists(folder);}
        System.out.println("StreamingExportTest: OK (25,005 rows beyond first page, prepared parameters, progress and atomic failure recovery)");
    }
    private static JdbcSession.ResultStream bridge(DataExporter.RowWriter writer){return new JdbcSession.ResultStream(){public void start(List<String> columns,List<Integer> types)throws Exception{writer.start(columns);}public void row(List<Object> values)throws Exception{writer.row(values);}};}
    private static void contains(String value,String expected){if(!value.contains(expected))throw new AssertionError("Missing ["+expected+"]");}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
