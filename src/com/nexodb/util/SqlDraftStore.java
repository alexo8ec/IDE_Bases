package com.nexodb.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Local editor buffer only. Restoring a draft never executes SQL or reconnects a database. */
public final class SqlDraftStore {
    public record Draft(String text,int caret) {}
    private final Path file;
    public SqlDraftStore(Path file){this.file=file.toAbsolutePath();}
    public static SqlDraftStore configured(){
        if(Boolean.getBoolean("nexodb.disableDraftPersistence"))return null;
        String override=System.getProperty("nexodb.draftsFile","");
        return new SqlDraftStore(override.isBlank()?AppSettings.file().resolveSibling("sql-editor-draft.properties"):Path.of(override));
    }
    public synchronized Optional<Draft> load()throws IOException {
        if(!Files.exists(file))return Optional.empty();
        Properties values=new Properties();
        try(var reader=Files.newBufferedReader(file)){values.load(reader);}catch(IllegalArgumentException error){throw new IOException("Invalid SQL draft file",error);}
        String text=values.getProperty("text");if(text==null)return Optional.empty();
        int caret=0;try{caret=Integer.parseInt(values.getProperty("caret","0"));}catch(NumberFormatException ignored){}
        return Optional.of(new Draft(text,Math.max(0,Math.min(caret,text.length()))));
    }
    public synchronized void save(Draft draft)throws IOException {
        Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"sql-draft-",".tmp");
        try {
            try{Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            Properties values=new Properties();values.setProperty("text",draft.text());values.setProperty("caret",Integer.toString(Math.max(0,Math.min(draft.caret(),draft.text().length()))));
            try(var writer=Files.newBufferedWriter(temporary)){values.store(writer,"NexoDB Studio SQL editor draft");}
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
