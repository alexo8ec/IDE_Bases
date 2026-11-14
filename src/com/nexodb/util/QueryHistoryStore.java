package com.nexodb.util;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** A local history records execution, never replays it. */
public final class QueryHistoryStore {
    public record Entry(Instant time,String connection,String database,String sql,long millis,long rows,String error){}
    private final Path file;
    private final List<Entry> entries=new ArrayList<>();
    public static QueryHistoryStore configured(){return new QueryHistoryStore(Boolean.getBoolean("nexodb.disableDraftPersistence")?null:AppSettings.file().resolveSibling("query-history.properties"));}
    public QueryHistoryStore(Path file){this.file=file;try{Properties p=AtomicProperties.read(file);int count=Math.min(500,Integer.parseInt(p.getProperty("count","0")));for(int i=0;i<count;i++){String k=i+".";entries.add(new Entry(Instant.parse(p.getProperty(k+"time")),p.getProperty(k+"connection",""),p.getProperty(k+"database",""),p.getProperty(k+"sql",""),Long.parseLong(p.getProperty(k+"millis","0")),Long.parseLong(p.getProperty(k+"rows","0")),p.getProperty(k+"error","")));}}catch(Exception ignored){}}
    public synchronized List<Entry> entries(){return List.copyOf(entries);}
    public synchronized void add(Entry entry)throws IOException{
        if(entry.sql().length()>65536)return;entries.add(0,entry);while(entries.size()>500)entries.remove(entries.size()-1);save();
    }
    public synchronized void clear()throws IOException{entries.clear();save();}
    private void save()throws IOException{Properties p=new Properties();p.setProperty("count",""+entries.size());for(int i=0;i<entries.size();i++){Entry e=entries.get(i);String k=i+".";p.setProperty(k+"time",e.time().toString());p.setProperty(k+"connection",e.connection());p.setProperty(k+"database",e.database());p.setProperty(k+"sql",e.sql());p.setProperty(k+"millis",""+e.millis());p.setProperty(k+"rows",""+e.rows());p.setProperty(k+"error",e.error());}AtomicProperties.write(file,p);}
}
