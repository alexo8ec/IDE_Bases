package com.nexodb.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

public final class AtomicProperties {
    private AtomicProperties(){}
    public static Properties read(Path file)throws IOException{Properties values=new Properties();if(file!=null&&Files.exists(file))try(var reader=Files.newBufferedReader(file)){values.load(reader);}return values;}
    public static void write(Path file,Properties values)throws IOException{
        if(file==null)return;file=file.toAbsolutePath();Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"nexodb-",".tmp");
        try{try{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            try(var writer=Files.newBufferedWriter(temp)){values.store(writer,"NexoDB Studio");}
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
}
