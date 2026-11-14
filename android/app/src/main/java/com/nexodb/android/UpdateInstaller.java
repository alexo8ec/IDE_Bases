package com.nexodb.android;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** The platform installer always asks the user; only matching app identities can reach it. */
final class UpdateInstaller {
    static final int PERMISSION_REQUEST=90;
    private final MainActivity activity;
    UpdateInstaller(MainActivity activity){this.activity=activity;}
    static void verify(Context context,File apk,long expectedVersion,boolean newer)throws Exception{
        PackageManager manager=context.getPackageManager();
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo incoming=manager.getPackageArchiveInfo(apk.getAbsolutePath(),flags),current=manager.getPackageInfo(context.getPackageName(),flags);
        if(incoming==null||!context.getPackageName().equals(incoming.packageName))throw new SecurityException("La descarga no es una actualización de NexoDB Studio.");
        long candidate=version(incoming),installed=version(current);
        if(candidate!=expectedVersion||newer&&candidate<=installed)throw new SecurityException("La versión de la APK no coincide con la actualización anunciada.");
        if(!certificates(incoming).equals(certificates(current))||certificates(incoming).isEmpty())throw new SecurityException("La firma de la APK no coincide con la aplicación instalada.");
    }
    private static long version(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    private static Set<String> certificates(PackageInfo info)throws Exception{
        android.content.pm.Signature[] signatures=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        Set<String> result=new TreeSet<>();if(signatures!=null)for(var signature:signatures)result.add(HexCompat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));return result;
    }
    Object request(File file,long version)throws Exception{
        verify(activity,file,version,true);
        activity.getPreferences(Context.MODE_PRIVATE).edit().putLong("pending-update-version",version).commit();
        CompletableFuture<String> result=new CompletableFuture<>();
        activity.runOnUiThread(()->{try{
            if(!activity.getPackageManager().canRequestPackageInstalls()){
                activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())),PERMISSION_REQUEST);
                result.complete("permission");
            }else{launch(file,version);result.complete("installer");}
        }catch(Exception error){result.completeExceptionally(error);}});
        return result.get();
    }
    void permissionResult(){
        long version=activity.getPreferences(Context.MODE_PRIVATE).getLong("pending-update-version",0);
        if(version==0)return;
        if(!activity.getPackageManager().canRequestPackageInstalls()){Toast.makeText(activity,"No se autorizó instalar actualizaciones. Puedes volver a intentarlo desde Preferencias.",Toast.LENGTH_LONG).show();return;}
        try{launch(new File(activity.getCacheDir(),"updates/update.apk"),version);}catch(Exception error){Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void launch(File file,long version)throws Exception{
        verify(activity,file,version,true);
        Intent intent=installIntent(activity,file);
        activity.getPreferences(Context.MODE_PRIVATE).edit().remove("pending-update-version").apply();
        activity.startActivity(intent);
    }
    static Intent installIntent(Context context,File file){
        Uri uri=FileProvider.getUriForFile(context,context.getPackageName()+".updates",file);
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
}
