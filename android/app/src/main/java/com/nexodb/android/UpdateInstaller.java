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
    static final int INSTALL_REQUEST=91;
    private final MainActivity activity;
    UpdateInstaller(MainActivity activity){this.activity=activity;}
    static void verify(Context context,File apk,long expectedVersion,boolean newer)throws Exception{
        PackageManager manager=context.getPackageManager();
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo incoming=manager.getPackageArchiveInfo(apk.getAbsolutePath(),flags),current=manager.getPackageInfo(context.getPackageName(),flags);
        if(incoming==null||!context.getPackageName().equals(incoming.packageName))throw new SecurityException("La descarga no es una actualización de AdisDB.");
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
        activity.getPreferences(Context.MODE_PRIVATE).edit().putLong("pending-update-version",version).putString("pending-update-state","ready").commit();
        CompletableFuture<String> result=new CompletableFuture<>();
        activity.runOnUiThread(()->{try{
            if(!activity.getPackageManager().canRequestPackageInstalls()){
                activity.getPreferences(Context.MODE_PRIVATE).edit().putString("pending-update-state","permission").apply();
                activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())),PERMISSION_REQUEST);
                result.complete("permission");
            }else{launch(file,version);result.complete("installer");}
        }catch(Exception error){result.completeExceptionally(error);}});
        return result.get();
    }
    void permissionResult(){
        if(!activity.getPreferences(Context.MODE_PRIVATE).getString("pending-update-state","").equals("permission"))return;
        long version=activity.getPreferences(Context.MODE_PRIVATE).getLong("pending-update-version",0);
        if(version==0)return;
        if(!activity.getPackageManager().canRequestPackageInstalls()){Toast.makeText(activity,"No se autorizó instalar actualizaciones. Puedes volver a intentarlo desde Preferencias.",Toast.LENGTH_LONG).show();return;}
        try{launch(new File(activity.getCacheDir(),"updates/update.apk"),version);}catch(Exception error){Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void launch(File file,long version)throws Exception{
        verify(activity,file,version,true);
        Intent intent=installIntent(activity,file);
        activity.getPreferences(Context.MODE_PRIVATE).edit().putString("pending-update-state","installing").commit();
        try{activity.startActivityForResult(intent,INSTALL_REQUEST);}catch(Exception error){activity.getPreferences(Context.MODE_PRIVATE).edit().putString("pending-update-state","ready").apply();throw error;}
    }
    Object resume()throws Exception{long version=activity.getPreferences(Context.MODE_PRIVATE).getLong("pending-update-version",0);if(version<=BuildConfig.VERSION_CODE)throw new IllegalStateException("Busca una actualización antes de continuar.");return request(new File(activity.getCacheDir(),"updates/update.apk"),version);}
    org.json.JSONObject status()throws Exception{
        var preferences=activity.getPreferences(Context.MODE_PRIVATE);long version=preferences.getLong("pending-update-version",0);File file=new File(activity.getCacheDir(),"updates/update.apk");
        if(version>0&&(version<=BuildConfig.VERSION_CODE||!file.isFile())){preferences.edit().remove("pending-update-version").remove("pending-update-state").apply();version=0;}
        return new org.json.JSONObject().put("installedVersion",BuildConfig.VERSION_NAME).put("pending",version>BuildConfig.VERSION_CODE).put("state",version>0?preferences.getString("pending-update-state","ready"):"idle").put("permission",activity.getPackageManager().canRequestPackageInstalls());
    }
    void installationResult(){activity.getPreferences(Context.MODE_PRIVATE).edit().putString("pending-update-state","ready").apply();Toast.makeText(activity,"Puedes reintentar la instalación desde Actualizaciones sin descargar de nuevo.",Toast.LENGTH_LONG).show();}
    static Intent installIntent(Context context,File file){
        Uri uri=FileProvider.getUriForFile(context,context.getPackageName()+".updates",file);
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true);
    }
}
