package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;

public final class BackupCatalogTest {
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("adisdb-backup-catalog-");String prefix=BackupCatalog.artifactPrefix("task-1","Respaldo ventas");List<Path> files=new ArrayList<>();
        for(int i=0;i<4;i++){Path file=folder.resolve(prefix+"-20261119-0"+i+"0000.sql");Files.writeString(file,"-- backup "+i+"\nSELECT "+i+";\n");Files.setLastModifiedTime(file,FileTime.from(Instant.parse("2026-11-19T0"+i+":00:00Z")));BackupCatalog.record(file,"task-1","Respaldo ventas",DatabaseType.MYSQL,"ventas",i);files.add(file);}
        var valid=BackupCatalog.verify(files.get(3));require(valid.valid(),valid.detail());equal("ventas",valid.database());require(valid.sha256().matches("[0-9a-f]{64}"),"Missing SHA-256");
        Files.writeString(files.get(2),"alterado",StandardOpenOption.APPEND);require(!BackupCatalog.verify(files.get(2)).valid(),"Tampered backup accepted");
        Path unrelated=folder.resolve("otro-respaldo.sql");Files.writeString(unrelated,"keep");List<Path> removed=BackupCatalog.prune(folder,prefix,2,files.get(3));equal(2,removed.size());require(Files.exists(files.get(3))&&Files.exists(files.get(2)),"Newest backups were removed");require(Files.exists(unrelated),"Unrelated file was removed");require(!Files.exists(files.get(0))&&!Files.exists(BackupCatalog.manifest(files.get(0))),"Old backup/manifest retained");
        equal(prefix,BackupCatalog.artifactPrefix("task-1","Respaldo ventas"));System.out.println("BackupCatalogTest: OK (SHA-256 manifests, tamper detection, stable prefixes and bounded safe retention)");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
