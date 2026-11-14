package com.nexodb.android;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import com.nexodb.db.ManagedConnection;

/** Fresh manifests, atomic downloads and resumable installation with unchanged app identity. */
final class MobileUpdate {
    private static final String MANIFEST="https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist/android-update-manifest.json";
    private static final long MAX_APK=256L*1024*1024;
    private final MobileBackend app;private JSONObject checked;private volatile boolean downloadCancelled;
    MobileUpdate(MobileBackend app){this.app=app;}
    void cancel(){downloadCancelled=true;}
    static JSONObject validate(String json)throws Exception{
        JSONObject value=new JSONObject(json.startsWith("\uFEFF")?json.substring(1):json);String version=value.getString("version");String[] parts=version.split("\\.",-1);
        if(parts.length!=3)throw new IOException("Versión Android no válida.");int code=0;for(String part:parts){if(!part.matches("[0-9]{1,2}"))throw new IOException("Versión Android no válida.");code=code*100+Integer.parseInt(part);}
        if(code<=0||value.getLong("versionCode")!=code)throw new IOException("La versión publicada no coincide con su código Android.");
        if(!value.getString("sha256").matches("[a-fA-F0-9]{64}"))throw new IOException("SHA-256 no válido.");
        URL url=new URL(value.getString("downloadUrl"));validateUrl(url);long size=value.optLong("size",0);if(size<0||size>MAX_APK)throw new IOException("El tamaño publicado de la APK no es válido.");
        value.put("available",code>BuildConfig.VERSION_CODE);return value;
    }
    JSONObject check()throws Exception{
        try(InputStream input=open(new URL(MANIFEST+"?check="+System.currentTimeMillis()))){ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;while((n=input.read(bytes))!=-1){if(buffer.size()+n>65536)throw new IOException("Manifiesto demasiado grande.");buffer.write(bytes,0,n);}checked=validate(buffer.toString("UTF-8"));return new JSONObject(checked.toString()).put("installedVersion",BuildConfig.VERSION_NAME);}
    }
    Object download()throws Exception{
        downloadCancelled=false;if(checked==null||!checked.optBoolean("available"))throw new IllegalStateException("Comprueba primero si existe una actualización.");
        assertTransactions();JSONObject release=new JSONObject(checked.toString());
        if(!app.activity.ask("Descargar e instalar","NexoDB Studio "+release.getString("version")+"\nSe conservarán tus conexiones, consultas y licencia. Guarda los cambios pendientes. Android pedirá confirmar la instalación. ¿Continuar?"))throw MobileBackend.cancelled();
        Path directory=app.activity.getCacheDir().toPath().resolve("updates");Files.createDirectories(directory);Path file=directory.resolve("update.apk"),partial=directory.resolve("update.pending.apk");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0,expected=release.optLong("size",0);int shown=-1;
        try{
            try(InputStream input=open(new URL(release.getString("downloadUrl")));OutputStream output=Files.newOutputStream(partial)){
                byte[] block=new byte[65536];int count;while((count=input.read(block))!=-1){if(downloadCancelled)throw MobileBackend.cancelled();total+=count;if(total>MAX_APK||expected>0&&total>expected)throw new IOException("El tamaño de la APK no coincide con el publicado.");digest.update(block,0,count);output.write(block,0,count);int progress=expected>0?(int)(total*100/expected):(int)(total/1024/1024);if(progress!=shown){shown=progress;app.activity.progress("Descargando actualización: "+(expected>0?progress+"% · ":"")+(total/1024/1024)+" MB");}}
            }
            if(downloadCancelled)throw MobileBackend.cancelled();if(total==0||expected>0&&total!=expected)throw new IOException("La descarga está incompleta. Vuelve a intentarlo.");
            if(!HexCompat.of().formatHex(digest.digest()).equalsIgnoreCase(release.getString("sha256")))throw new IOException("La descarga no coincide con el SHA-256 publicado.");
            app.activity.progress("Verificando versión y firma de la actualización…");UpdateInstaller.verify(app.activity,partial.toFile(),release.getLong("versionCode"),true);
            if(downloadCancelled)throw MobileBackend.cancelled();Files.move(partial,file,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(partial);}
        return new UpdateInstaller(app.activity).request(file.toFile(),release.getLong("versionCode"));
    }
    Object resume()throws Exception{assertTransactions();return new UpdateInstaller(app.activity).resume();}
    JSONObject status()throws Exception{return new UpdateInstaller(app.activity).status();}
    private void assertTransactions()throws Exception{for(var session:app.sessions.values())if(ManagedConnection.hasActiveTransaction(session.connection()))throw new IllegalStateException("Confirma o revierte las transacciones y activa Auto-commit antes de actualizar.");}
    private static void validateUrl(URL url)throws IOException{if(!url.getProtocol().equals("https")||url.getHost().isEmpty()||url.getUserInfo()!=null||url.getRef()!=null)throw new IOException("La actualización requiere HTTPS sin credenciales.");}
    private static InputStream open(URL initial)throws Exception{
        URL url=initial;
        for(int i=0;i<6;i++){validateUrl(url);HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setRequestProperty("Cache-Control","no-cache");connection.setRequestProperty("User-Agent","NexoDB-Android/"+BuildConfig.VERSION_NAME);
            try{int code=connection.getResponseCode();if(code>=300&&code<400){String location=connection.getHeaderField("Location");if(location==null)throw new IOException("Redirección sin destino.");url=new URL(url,location);connection.disconnect();continue;}if(code!=200)throw new IOException(code==404?"La actualización Android todavía no se ha publicado.":"Servidor de actualizaciones: HTTP "+code);
                return new FilterInputStream(connection.getInputStream()){@Override public void close()throws IOException{try{super.close();}finally{connection.disconnect();}}};
            }catch(Exception error){connection.disconnect();throw error;}
        }throw new IOException("Demasiadas redirecciones.");
    }
}
