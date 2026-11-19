package com.nexodb.util;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Crash marker and safe task descriptors. SQL, result rows and secrets are never stored here. */
public final class WorkspaceRecoveryStore {
    public record InterruptedTask(Instant time,String kind,String label,String connection) {}
    public record Recovery(boolean unexpectedShutdown,String connection,String database,List<InterruptedTask> tasks) {}
    private final Path file;private boolean started;
    public WorkspaceRecoveryStore(Path file){this.file=file;}
    public static WorkspaceRecoveryStore configured(){return new WorkspaceRecoveryStore(Boolean.getBoolean("nexodb.disableDraftPersistence")?null:AppSettings.file().resolveSibling("workspace-recovery.properties"));}
    public synchronized Recovery start(){
        Properties previous=read();boolean unclean="false".equals(previous.getProperty("clean","true"));Recovery recovery=new Recovery(unclean,previous.getProperty("connection",""),previous.getProperty("database",""),tasks(previous));started=true;write(false,"","",List.of());return recovery;
    }
    public synchronized void checkpoint(String connection,String database,List<BackgroundOperations.Snapshot> operations){
        if(!started||file==null)return;List<InterruptedTask> tasks=operations.stream().filter(BackgroundOperations.Snapshot::active).limit(30).map(item->new InterruptedTask(Instant.now(),item.kind().name(),safe(item.label()),safe(item.connection()))).toList();write(false,safe(connection),safe(database),tasks);
    }
    public synchronized void cleanShutdown(){if(started)write(true,"","",List.of());started=false;}
    private Properties read(){try{return AtomicProperties.read(file);}catch(IOException|SecurityException error){return new Properties();}}
    private void write(boolean clean,String connection,String database,List<InterruptedTask> tasks){if(file==null)return;Properties out=new Properties();out.setProperty("version","1");out.setProperty("clean",Boolean.toString(clean));out.setProperty("connection",safe(connection));out.setProperty("database",safe(database));out.setProperty("count",Integer.toString(tasks.size()));for(int i=0;i<tasks.size();i++){InterruptedTask task=tasks.get(i);String p="task."+i+".";out.setProperty(p+"time",task.time().toString());out.setProperty(p+"kind",safe(task.kind()));out.setProperty(p+"label",safe(task.label()));out.setProperty(p+"connection",safe(task.connection()));}try{AtomicProperties.write(file,out);}catch(IOException|SecurityException ignored){}}
    private static List<InterruptedTask> tasks(Properties values){int count;try{count=Math.min(30,Integer.parseInt(values.getProperty("count","0")));}catch(RuntimeException error){count=0;}List<InterruptedTask> out=new ArrayList<>();for(int i=0;i<count;i++)try{String p="task."+i+".";out.add(new InterruptedTask(Instant.parse(values.getProperty(p+"time")),values.getProperty(p+"kind",""),values.getProperty(p+"label",""),values.getProperty(p+"connection","")));}catch(RuntimeException ignored){}return List.copyOf(out);}
    private static String safe(String text){String value=Objects.toString(text,"").replaceAll("[\\r\\n\\t]+"," ").trim();return value.length()>120?value.substring(0,120)+"…":value;}
}
