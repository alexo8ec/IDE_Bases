package com.nexodb.util;

import com.nexodb.db.SchemaItem;
import com.nexodb.model.ConnectionProfile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Keeps the user's exact SQL layout locally because database metadata may normalize whitespace. */
public final class StoredObjectFormatStore {
    private final Path file;
    public StoredObjectFormatStore(Path file){this.file=file.toAbsolutePath();}
    public static StoredObjectFormatStore configured(){
        if(Boolean.getBoolean("nexodb.disableDraftPersistence"))return null;
        String override=System.getProperty("nexodb.storedObjectFormatsFile","");
        Path target=override.isBlank()?AppSettings.file().resolveSibling("stored-object-formats.properties"):Path.of(override);
        return new StoredObjectFormatStore(target);
    }
    public static String key(ConnectionProfile profile,SchemaItem item){
        String identity=String.join("\0",profile.id(),profile.type().name(),Objects.toString(item.catalog(),""),Objects.toString(item.schema(),""),item.kind().name(),item.name());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(identity.getBytes(StandardCharsets.UTF_8));
    }
    public synchronized Optional<String> load(String key,String serverHash)throws IOException{
        Properties values=read();String prefix="object."+key+".";
        if(!Objects.equals(serverHash,values.getProperty(prefix+"serverHash")))return Optional.empty();
        String text=values.getProperty(prefix+"text");return text==null||text.isBlank()?Optional.empty():Optional.of(text);
    }
    public synchronized void save(String key,String serverHash,String text)throws IOException{
        Properties values=read();String prefix="object."+key+".";
        values.setProperty(prefix+"serverHash",Objects.requireNonNull(serverHash));
        values.setProperty(prefix+"text",Objects.requireNonNull(text));
        Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"stored-object-formats-",".tmp");
        try{
            try(var writer=Files.newBufferedWriter(temporary)){values.store(writer,"AdisDB stored object editor formatting");}
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    private Properties read()throws IOException{
        Properties values=new Properties();if(!Files.isRegularFile(file))return values;
        try(var reader=Files.newBufferedReader(file)){values.load(reader);}catch(IllegalArgumentException error){throw new IOException("El archivo de formatos guardados no es válido.",error);}
        return values;
    }
}
