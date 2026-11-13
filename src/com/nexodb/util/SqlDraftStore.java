package com.nexodb.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Local editor buffer only. Restoring a draft never executes SQL or reconnects a database. */
public final class SqlDraftStore {
    public record Draft(String text,int caret) {}
    public record Workspace(java.util.List<Draft> tabs,int activeTab) {
        public Workspace {tabs=java.util.List.copyOf(tabs);if(tabs.isEmpty())tabs=java.util.List.of(new Draft("",0));activeTab=Math.max(0,Math.min(activeTab,tabs.size()-1));}
    }
    private final Path file;
    public SqlDraftStore(Path file){this.file=file.toAbsolutePath();}
    public static SqlDraftStore configured(){
        if(Boolean.getBoolean("nexodb.disableDraftPersistence"))return null;
        String override=System.getProperty("nexodb.draftsFile","");
        return new SqlDraftStore(override.isBlank()?AppSettings.file().resolveSibling("sql-editor-draft.properties"):Path.of(override));
    }
    public synchronized Optional<Draft> load()throws IOException {
        return loadWorkspace().map(workspace->workspace.tabs().get(workspace.activeTab()));
    }
    public synchronized Optional<Workspace> loadWorkspace()throws IOException {
        if(!Files.exists(file))return Optional.empty();
        Properties values=new Properties();
        try(var reader=Files.newBufferedReader(file)){values.load(reader);}catch(IllegalArgumentException error){throw new IOException("Invalid SQL draft file",error);}
        int count=number(values,"tabs",0);
        if(count>1000||count<0)throw new IOException("Invalid query tab count");
        if(count==0){if(!values.containsKey("text"))return Optional.empty();return Optional.of(new Workspace(java.util.List.of(read(values,"")),0));}
        java.util.List<Draft> tabs=new ArrayList<>();for(int i=0;i<count;i++)tabs.add(read(values,"tab."+i+"."));
        return Optional.of(new Workspace(tabs,number(values,"activeTab",0)));
    }
    private static int number(Properties values,String key,int fallback){try{return Integer.parseInt(values.getProperty(key,Integer.toString(fallback)));}catch(NumberFormatException ignored){return fallback;}}
    private static Draft read(Properties values,String prefix){String text=values.getProperty(prefix+"text","");return new Draft(text,Math.max(0,Math.min(number(values,prefix+"caret",0),text.length())));}
    public synchronized void save(Draft draft)throws IOException {
        saveWorkspace(new Workspace(java.util.List.of(draft),0));
    }
    public synchronized void saveWorkspace(Workspace workspace)throws IOException {
        Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"sql-draft-",".tmp");
        try {
            try{Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            Properties values=new Properties();values.setProperty("tabs",Integer.toString(workspace.tabs().size()));values.setProperty("activeTab",Integer.toString(workspace.activeTab()));
            for(int i=0;i<workspace.tabs().size();i++){Draft draft=workspace.tabs().get(i);values.setProperty("tab."+i+".text",draft.text());values.setProperty("tab."+i+".caret",Integer.toString(Math.max(0,Math.min(draft.caret(),draft.text().length()))));}
            try(var writer=Files.newBufferedWriter(temporary)){values.store(writer,"NexoDB Studio SQL editor draft");}
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
