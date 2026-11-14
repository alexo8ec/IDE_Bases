package com.nexodb.android;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;

/** Only verified HTTPS APKs with the installed app's signature reach Android's installer. */
final class MobileUpdate {
    private static final String MANIFEST="https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist/android-update-manifest.json";
    private final MobileBackend app;private JSONObject checked;private volatile boolean downloadCancelled;
    MobileUpdate(MobileBackend app){this.app=app;}
    void cancel(){downloadCancelled=true;}
    JSONObject check()throws Exception{try(InputStream input=open(new URL(MANIFEST))){ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;while((n=input.read(bytes))!=-1){if(buffer.size()+n>65536)throw new IOException("Manifiesto demasiado grande.");buffer.write(bytes,0,n);}checked=new JSONObject(buffer.toString("UTF-8"));if(!checked.getString("sha256").matches("[a-fA-F0-9]{64}"))throw new IOException("SHA-256 no válido.");checked.put("available",checked.getInt("versionCode")>BuildConfig.VERSION_CODE);return checked;}}
    Object download()throws Exception{
        downloadCancelled=false;
        if(checked==null||!checked.optBoolean("available"))throw new IllegalStateException("Comprueba primero si existe una actualización.");
        for(var session:app.sessions.values())if(!session.getAutoCommit())throw new IllegalStateException("Confirma o revierte las transacciones y activa Auto-commit antes de actualizar.");
        if(!app.activity.ask("Descargar e instalar","NexoDB Studio "+checked.getString("version")+"\nSe verificará la descarga y su firma. Android pedirá confirmar la instalación y reiniciará la aplicación. Guarda tus cambios y finaliza las transacciones antes de continuar. ¿Continuar?"))throw MobileBackend.cancelled();
        Path directory=app.activity.getCacheDir().toPath().resolve("updates");Files.createDirectories(directory);Path file=directory.resolve("update.apk");MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=open(new URL(checked.getString("downloadUrl")));OutputStream output=Files.newOutputStream(file)){
            byte[] block=new byte[65536];int count;long total=0;while((count=input.read(block))!=-1){if(downloadCancelled)throw MobileBackend.cancelled();total+=count;if(total>256L*1024*1024)throw new IOException("La APK supera 256 MB.");digest.update(block,0,count);output.write(block,0,count);app.activity.progress((total/1024/1024)+" MB");}
        }catch(Exception error){Files.deleteIfExists(file);throw error;}
        if(downloadCancelled){Files.deleteIfExists(file);throw MobileBackend.cancelled();}
        if(!HexCompat.of().formatHex(digest.digest()).equalsIgnoreCase(checked.getString("sha256"))){Files.deleteIfExists(file);throw new IOException("La descarga no coincide con el SHA-256 publicado.");}
        return new UpdateInstaller(app.activity).request(file.toFile(),checked.getLong("versionCode"));
    }
    private static InputStream open(URL url)throws Exception{
        for(int i=0;i<6;i++){if(!url.getProtocol().equals("https")||url.getUserInfo()!=null)throw new IOException("La actualización requiere HTTPS sin credenciales.");HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","NexoDB-Android/"+BuildConfig.VERSION_NAME);int code=connection.getResponseCode();if(code>=300&&code<400){String location=connection.getHeaderField("Location");if(location==null)throw new IOException("Redirección sin destino.");url=new URL(url,location);connection.disconnect();continue;}if(code!=200){connection.disconnect();throw new IOException(code==404?"La actualización Android todavía no se ha publicado.":"Servidor de actualizaciones: HTTP "+code);}return new FilterInputStream(connection.getInputStream()){@Override public void close()throws IOException{try{super.close();}finally{connection.disconnect();}}};}throw new IOException("Demasiadas redirecciones.");
    }
}
