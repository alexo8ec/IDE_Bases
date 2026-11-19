package com.nexodb.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local administrative audit trail. It deliberately stores no SQL, values or credentials. */
public final class AuditLog {
    public record Entry(Instant time,String action,String connection,String scope,String outcome,String fingerprint) {}
    private static final int MAX=500;
    private static final Map<Path,AuditLog> INSTANCES=new ConcurrentHashMap<>();
    private final Path file;

    private AuditLog(Path file){this.file=file;}
    public static AuditLog configured(){
        if(Boolean.getBoolean("nexodb.disableDraftPersistence"))return new AuditLog(null);
        Path target=AppSettings.file().resolveSibling("audit-log.properties").toAbsolutePath();
        return INSTANCES.computeIfAbsent(target,AuditLog::new);
    }
    public synchronized void record(String action,String connection,String scope,String outcome,String subject){
        if(file==null)return;
        try{
            List<Entry> entries=new ArrayList<>(entries());
            entries.add(new Entry(Instant.now(),safe(action),safe(connection),safe(scope),safe(outcome),fingerprint(subject)));
            if(entries.size()>MAX)entries=entries.subList(entries.size()-MAX,entries.size());
            write(entries);
        }catch(IOException|RuntimeException ignored){}
    }
    public synchronized List<Entry> entries(){
        if(file==null)return List.of();
        try{
            Properties values=AtomicProperties.read(file);int count=parse(values.getProperty("count","0"));List<Entry> out=new ArrayList<>();
            for(int i=0;i<count;i++)try{String p="entry."+i+".";out.add(new Entry(Instant.parse(values.getProperty(p+"time")),values.getProperty(p+"action",""),values.getProperty(p+"connection",""),values.getProperty(p+"scope",""),values.getProperty(p+"outcome",""),values.getProperty(p+"fingerprint","")));}catch(RuntimeException ignored){}
            return List.copyOf(out);
        }catch(IOException|SecurityException error){return List.of();}
    }
    public synchronized void clear(){if(file==null)return;try{write(List.of());}catch(IOException|RuntimeException ignored){}}
    private void write(List<Entry> entries)throws IOException{
        Properties values=new Properties();values.setProperty("version","1");values.setProperty("count",Integer.toString(entries.size()));
        for(int i=0;i<entries.size();i++){Entry e=entries.get(i);String p="entry."+i+".";values.setProperty(p+"time",e.time().toString());values.setProperty(p+"action",safe(e.action()));values.setProperty(p+"connection",safe(e.connection()));values.setProperty(p+"scope",safe(e.scope()));values.setProperty(p+"outcome",safe(e.outcome()));values.setProperty(p+"fingerprint",safe(e.fingerprint()));}
        AtomicProperties.write(file,values);
    }
    public static String fingerprint(String subject){
        if(subject==null||subject.isBlank())return "";
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));StringBuilder value=new StringBuilder();for(int i=0;i<8;i++)value.append(String.format("%02x",digest[i]));return value.toString();}catch(Exception error){return "";}
    }
    public static String sqlAction(String sql){
        String value=Objects.toString(sql,"").stripLeading();int end=0;while(end<value.length()&&Character.isLetter(value.charAt(end)))end++;String word=end==0?"SQL":value.substring(0,end).toUpperCase(Locale.ROOT);return "SQL_"+safe(word);
    }
    private static int parse(String value){try{return Math.max(0,Math.min(MAX,Integer.parseInt(value)));}catch(RuntimeException error){return 0;}}
    private static String safe(String text){String value=Objects.toString(text,"").replaceAll("[\\r\\n\\t]+"," ").trim();return value.length()>120?value.substring(0,120)+"…":value;}
}
