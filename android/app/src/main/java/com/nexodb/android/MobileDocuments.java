package com.nexodb.android;

import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.nexodb.util.SqlFiles;

final class MobileDocuments {
    private final MainActivity activity;private final Map<String,Path> files=new HashMap<>();
    MobileDocuments(MainActivity activity){this.activity=activity;}
    JSONObject open()throws Exception{
        Uri uri=activity.chooseDocument(false,"","*/*");String name="archivo";try(var cursor=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}
        Path path=Files.createTempFile(activity.getCacheDir().toPath(),"import-",".data");long bytes=0;
        try(InputStream input=activity.getContentResolver().openInputStream(uri);OutputStream output=Files.newOutputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){bytes+=n;if(bytes>512L*1024*1024)throw new IOException("El archivo supera 512 MB. Divídelo en varios archivos.");output.write(buffer,0,n);}}
        String id=UUID.randomUUID().toString();files.put(id,path);JSONObject result=new JSONObject().put("handle",id).put("name",name).put("bytes",bytes);if(bytes<=2_000_000)result.put("text",SqlFiles.read(path).text());return result;
    }
    Path file(String id){Path path=files.get(id);if(path==null)throw new IllegalArgumentException("Vuelve a seleccionar el archivo.");return path;}
    void close(){for(Path path:files.values())try{Files.deleteIfExists(path);}catch(IOException ignored){}files.clear();}
    void export(String text,String name)throws Exception{Uri uri=activity.chooseDocument(true,name,"text/plain");try(OutputStream output=activity.getContentResolver().openOutputStream(uri,"wt")){output.write(text.getBytes(StandardCharsets.UTF_8));}}
    void export(Path path,String name)throws Exception{Uri uri=activity.chooseDocument(true,name,"application/octet-stream");try(InputStream input=Files.newInputStream(path);OutputStream output=activity.getContentResolver().openOutputStream(uri,"wt")){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}finally{Files.deleteIfExists(path);}}
}
