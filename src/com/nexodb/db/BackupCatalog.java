package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import com.nexodb.util.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/** Integrity manifests and bounded retention for scheduled backup artifacts. */
public final class BackupCatalog {
    public record Verification(boolean valid,String detail,long size,String sha256,String database,Instant created){}
    private BackupCatalog(){}

    public static String artifactPrefix(String taskId,String taskName){
        String safe=Objects.toString(taskName,"").replaceAll("[^\\p{L}\\p{N}_.-]","_").replaceAll("_+","_");
        if(safe.isBlank())safe="respaldo";String id=SqlFiles.hash(Objects.toString(taskId,"")).substring(0,10);
        return "task-"+id+"-"+safe;
    }
    public static Path manifest(Path backup){return backup.resolveSibling(backup.getFileName()+".adisdb.properties");}
    public static Path record(Path backup,String taskId,String taskName,DatabaseType engine,String database,long rows)throws IOException{
        Path target=backup.toAbsolutePath().normalize();if(!Files.isRegularFile(target))throw new FileNotFoundException(target.toString());
        Properties values=new Properties();values.setProperty("format","1");values.setProperty("product","AdisDB");values.setProperty("version",AppVersion.current());values.setProperty("taskId",Objects.toString(taskId,""));values.setProperty("taskName",Objects.toString(taskName,""));values.setProperty("engine",Objects.requireNonNullElse(engine,DatabaseType.GENERIC).name());values.setProperty("database",Objects.toString(database,""));values.setProperty("created",Instant.now().toString());values.setProperty("file",target.getFileName().toString());values.setProperty("size",Long.toString(Files.size(target)));values.setProperty("rows",Long.toString(Math.max(0,rows)));values.setProperty("sha256",sha256(target));
        Path manifest=manifest(target);AtomicProperties.write(manifest,values);return manifest;
    }
    public static Verification verify(Path backup)throws IOException{
        Path target=backup.toAbsolutePath().normalize(),metadata=manifest(target);if(!Files.isRegularFile(target))return new Verification(false,"No existe el archivo de respaldo.",0,"","",null);if(!Files.isRegularFile(metadata))return new Verification(false,"El respaldo no tiene manifiesto de integridad AdisDB.",Files.size(target),sha256(target),"",null);
        Properties values=AtomicProperties.read(metadata);long size=Files.size(target);String actual=sha256(target),expected=values.getProperty("sha256","");boolean valid=target.getFileName().toString().equals(values.getProperty("file"))&&Long.toString(size).equals(values.getProperty("size"))&&MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII));Instant created=null;try{created=Instant.parse(values.getProperty("created",""));}catch(RuntimeException ignored){}
        return new Verification(valid,valid?"Respaldo íntegro: tamaño y SHA-256 coinciden.":"El respaldo cambió o está incompleto; no coincide con su manifiesto.",size,actual,values.getProperty("database",""),created);
    }
    public static List<Path> prune(Path folder,String prefix,int keep,Path preserve)throws IOException{
        if(keep<=0||!Files.isDirectory(folder))return List.of();Path root=folder.toAbsolutePath().normalize(),protectedFile=preserve==null?null:preserve.toAbsolutePath().normalize();List<Path> backups;
        try(Stream<Path> stream=Files.list(root)){backups=stream.filter(Files::isRegularFile).filter(path->{String name=path.getFileName().toString();return name.startsWith(prefix+"-")&&name.endsWith(".sql");}).sorted(Comparator.comparingLong(BackupCatalog::modified).reversed().thenComparing(path->path.getFileName().toString(),Comparator.reverseOrder())).toList();}
        List<Path> removed=new ArrayList<>();for(int i=keep;i<backups.size();i++){Path candidate=backups.get(i).toAbsolutePath().normalize();if(candidate.equals(protectedFile)||!candidate.getParent().equals(root))continue;Files.deleteIfExists(candidate);Files.deleteIfExists(manifest(candidate));removed.add(candidate);}return List.copyOf(removed);
    }
    public static String sha256(Path file)throws IOException{try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(file)){byte[] buffer=new byte[128*1024];int read;while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException error){throw new IOException(error);}}
    private static long modified(Path path){try{return Files.getLastModifiedTime(path).toMillis();}catch(IOException error){return Long.MIN_VALUE;}}
}
