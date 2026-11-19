package com.nexodb.util;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Small data-free history of server health aggregates. Never stores SQL, users or session identifiers. */
public final class ServerMonitorHistory {
    public record Entry(Instant time,String profile,int sessions,int active,int idle,int blocked,int transactions,long longestSeconds,int deadlocks,int replicas,long maximumLagSeconds,String replicationState){}
    private static final int MAX=1_000;
    private static final Duration RETENTION=Duration.ofDays(7);
    private final Path file;
    private final List<Entry> entries=new ArrayList<>();

    public static ServerMonitorHistory configured(){return new ServerMonitorHistory(Boolean.getBoolean("nexodb.disableDraftPersistence")?null:AppSettings.file().resolveSibling("server-monitor-history.properties"));}
    public ServerMonitorHistory(Path file){this.file=file;load();}

    public synchronized void record(Entry entry){
        if(file==null||entry==null)return;
        Entry clean=sanitize(entry);if(!entries.isEmpty()){Entry last=entries.get(entries.size()-1);if(last.profile().equals(clean.profile())&&Duration.between(last.time(),clean.time()).abs().compareTo(Duration.ofSeconds(30))<0)entries.set(entries.size()-1,clean);else entries.add(clean);}else entries.add(clean);prune(Instant.now());saveQuietly();
    }
    public synchronized List<Entry> entries(String profile){
        prune(Instant.now());String wanted=Objects.toString(profile,"");return entries.stream().filter(e->e.profile().equals(wanted)).toList();
    }
    public synchronized void clear(String profile){
        String wanted=Objects.toString(profile,"");entries.removeIf(e->e.profile().equals(wanted));saveQuietly();
    }
    private void load(){
        if(file==null)return;try{Properties values=AtomicProperties.read(file);int count=Math.min(MAX,Integer.parseInt(values.getProperty("count","0")));for(int i=0;i<count;i++){String k=i+".";entries.add(new Entry(Instant.parse(values.getProperty(k+"time")),safe(values.getProperty(k+"profile")),integer(values,k+"sessions"),integer(values,k+"active"),integer(values,k+"idle"),integer(values,k+"blocked"),integer(values,k+"transactions"),number(values,k+"longest"),integer(values,k+"deadlocks"),integer(values,k+"replicas"),number(values,k+"lag"),safe(values.getProperty(k+"replication"))));}prune(Instant.now());}catch(Exception ignored){entries.clear();}
    }
    private void prune(Instant now){Instant minimum=now.minus(RETENTION);entries.removeIf(e->e.time().isBefore(minimum));if(entries.size()>MAX)entries.subList(0,entries.size()-MAX).clear();}
    private void saveQuietly(){
        if(file==null)return;Properties values=new Properties();values.setProperty("version","1");values.setProperty("count",Integer.toString(entries.size()));for(int i=0;i<entries.size();i++){Entry e=entries.get(i);String k=i+".";values.setProperty(k+"time",e.time().toString());values.setProperty(k+"profile",e.profile());values.setProperty(k+"sessions",Integer.toString(e.sessions()));values.setProperty(k+"active",Integer.toString(e.active()));values.setProperty(k+"idle",Integer.toString(e.idle()));values.setProperty(k+"blocked",Integer.toString(e.blocked()));values.setProperty(k+"transactions",Integer.toString(e.transactions()));values.setProperty(k+"longest",Long.toString(e.longestSeconds()));values.setProperty(k+"deadlocks",Integer.toString(e.deadlocks()));values.setProperty(k+"replicas",Integer.toString(e.replicas()));values.setProperty(k+"lag",Long.toString(e.maximumLagSeconds()));values.setProperty(k+"replication",e.replicationState());}try{AtomicProperties.write(file,values);}catch(IOException|SecurityException ignored){}
    }
    private static Entry sanitize(Entry e){return new Entry(e.time()==null?Instant.now():e.time(),safe(e.profile()),positive(e.sessions()),positive(e.active()),positive(e.idle()),positive(e.blocked()),positive(e.transactions()),Math.max(0,e.longestSeconds()),positive(e.deadlocks()),positive(e.replicas()),Math.max(0,e.maximumLagSeconds()),safe(e.replicationState()));}
    private static int integer(Properties p,String key){try{return positive(Integer.parseInt(p.getProperty(key,"0")));}catch(RuntimeException ignored){return 0;}}
    private static long number(Properties p,String key){try{return Math.max(0,Long.parseLong(p.getProperty(key,"0")));}catch(RuntimeException ignored){return 0;}}
    private static int positive(int value){return Math.max(0,value);}
    private static String safe(String value){String text=Objects.toString(value,"").replaceAll("[\\r\\n=]"," ").trim();return text.length()>120?text.substring(0,120):text;}
}
