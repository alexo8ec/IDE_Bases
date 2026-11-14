package com.nexodb.util;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** SQL documents are independent UTF-8 files, never executed by the file operations. */
public final class SqlFiles {
    public static final int MAX_BYTES=16*1024*1024;
    public record Loaded(Path path,String text,String diskHash) {}
    private SqlFiles(){}
    public static String hash(String text){return hash(text.getBytes(StandardCharsets.UTF_8));}
    public static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(NoSuchAlgorithmException error){throw new AssertionError(error);}}
    public static Loaded read(Path source)throws IOException{
        Path path=source.toRealPath();if(Files.size(path)>MAX_BYTES)throw new IOException("El editor admite archivos SQL de hasta 16 MB. Para restaurar archivos grandes utiliza Importar SQL.");
        byte[] bytes;try(InputStream input=Files.newInputStream(path)){bytes=input.readNBytes(MAX_BYTES+1);}if(bytes.length>MAX_BYTES)throw new IOException("El archivo SQL supera 16 MB.");
        Charset charset=StandardCharsets.UTF_8;int offset=0;
        if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191)offset=3;
        else if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254){offset=2;charset=StandardCharsets.UTF_16LE;}
        else if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255){offset=2;charset=StandardCharsets.UTF_16BE;}
        String text=charset.newDecoder().decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();
        return new Loaded(path,text,hash(bytes));
    }
    public static Path withExtension(Path path){return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")?path:path.resolveSibling(path.getFileName()+".sql");}
    public static String diskHash(Path path)throws IOException{try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(path)){byte[] block=new byte[65536];int count;while((count=input.read(block))!=-1)digest.update(block,0,count);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException error){throw new AssertionError(error);}}
    public static String write(Path path,String text)throws IOException{
        path=path.toAbsolutePath();byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("El archivo SQL supera 16 MB.");
        Path temp=Files.createTempFile(path.getParent(),".nexodb-sql-",".tmp");
        try{Files.write(temp,bytes);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}return hash(bytes);}finally{Files.deleteIfExists(temp);}
    }
}
