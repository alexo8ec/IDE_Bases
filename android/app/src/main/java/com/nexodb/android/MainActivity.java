package com.nexodb.android;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.webkit.*;
import androidx.webkit.WebViewAssetLoader;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

/** Packaged local interface. External web pages never receive the native database bridge. */
public final class MainActivity extends Activity {
    private WebView web;private MobileBackend backend;private Throwable startupError;private volatile boolean destroyed;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer,CompletableFuture<Uri>> documents=new ConcurrentHashMap<>();private int documentRequest=100;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){WebResourceResponse result=loader.shouldInterceptRequest(request.getUrl());return result!=null?result:new WebResourceResponse("text/plain","utf-8",new ByteArrayInputStream(new byte[0]));}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !"appassets.androidplatform.net".equals(request.getUrl().getHost());}
        });
        web.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(web);worker.execute(()->{try{backend=new MobileBackend(this);}catch(Throwable e){startupError=e;}});web.addJavascriptInterface(new Bridge(),"Native");web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }
    public final class Bridge {
        @JavascriptInterface public void call(int id,String method,String payload){
            if(destroyed)return;
            if(method.equals("cancel")){if(backend!=null)backend.cancel();reply(id,true,new JSONObject());return;}
            worker.execute(()->{try{if(startupError!=null)throw new IllegalStateException("No se pudo iniciar NexoDB: "+startupError.getMessage());reply(id,true,MobileJson.value(backend.call(method,new JSONObject(payload))));}catch(Throwable e){reply(id,false,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}});
        }
    }
    private void reply(int id,boolean ok,Object value){try{String json=new JSONObject().put("id",id).put("ok",ok).put(ok?"value":"error",value==null?JSONObject.NULL:value).toString().replace("\u2028","\\u2028").replace("\u2029","\\u2029");runOnUiThread(()->{if(!destroyed)web.evaluateJavascript("window.NexoReply("+json+")",null);});}catch(JSONException ignored){}}
    void progress(String text){runOnUiThread(()->{if(!destroyed)web.evaluateJavascript("window.NexoProgress("+JSONObject.quote(text)+")",null);});}
    boolean ask(String title,String message)throws Exception{
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        runOnUiThread(()->{if(destroyed){result.complete(false);return;}AlertDialog dialog=new AlertDialog.Builder(this).setTitle(com.nexodb.util.I18n.tr(title)).setMessage(message).setPositiveButton(com.nexodb.util.I18n.tr("Sí"),(d,w)->result.complete(true)).setNegativeButton(com.nexodb.util.I18n.tr("No"),(d,w)->result.complete(false)).setOnCancelListener(d->result.complete(false)).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus());dialog.show();});
        return result.get();
    }
    Uri chooseDocument(boolean create,String name,String mime)throws Exception{
        CompletableFuture<Uri> result=new CompletableFuture<>();runOnUiThread(()->{int request=++documentRequest;documents.put(request,result);Intent intent=new Intent(create?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime);if(create)intent.putExtra(Intent.EXTRA_TITLE,name);try{startActivityForResult(intent,request);}catch(Exception error){documents.remove(request);result.completeExceptionally(error);}});
        Uri uri=result.get();if(uri==null)throw new CancellationException("Operación cancelada.");return uri;
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==UpdateInstaller.PERMISSION_REQUEST){new UpdateInstaller(this).permissionResult();return;}if(request==UpdateInstaller.INSTALL_REQUEST){new UpdateInstaller(this).installationResult();return;}CompletableFuture<Uri> future=documents.remove(request);if(future!=null)future.complete(result==RESULT_OK&&data!=null?data.getData():null);}
    void copy(String text){runOnUiThread(()->((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("NexoDB",text)));}
    @Override public void onBackPressed(){web.evaluateJavascript("window.NexoBack && window.NexoBack()",null);}
    @Override protected void onResume(){super.onResume();if(getPackageManager().canRequestPackageInstalls())new UpdateInstaller(this).permissionResult();}
    @Override protected void onPause(){if(web!=null)web.evaluateJavascript("window.NexoSave && window.NexoSave()",null);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;documents.values().forEach(f->f.complete(null));documents.clear();if(backend!=null){backend.cancel();worker.execute(backend::close);}worker.shutdown();if(web!=null){web.removeJavascriptInterface("Native");web.destroy();}super.onDestroy();}
}

