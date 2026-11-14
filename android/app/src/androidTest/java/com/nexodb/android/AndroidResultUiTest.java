package com.nexodb.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.webkit.WebView;
import com.nexodb.db.QueryResult;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the actual JS execution/rendering path without accessing a license or user database. */
final class AndroidResultUiTest {
    static void run(Instrumentation test) throws Exception {
        MobileStore originalStore=new MobileStore(test.getTargetContext());String originalDraft=originalStore.get("draft","{}");
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            var field = MainActivity.class.getDeclaredField("web");field.setAccessible(true);
            WebView web = (WebView)field.get(activity);
            await(test,web,"typeof execute === 'function' && typeof S.version === 'string' && document.readyState === 'complete'",true);
            QueryResult single = new QueryResult(List.of("id","nombre","nota"), List.of(Arrays.asList("1","Móvil",null),Arrays.asList("2","Segundo","")), -1, 4, false);
            QueryResult empty = new QueryResult(List.of("id","nombre"),List.of(),-1,2,false);
            JSONObject fixtures = new JSONObject().put("single",MobileJson.object(single).put("resultId","ui-single")).put("empty",MobileJson.object(empty).put("resultId","ui-empty"));
            fixtures.getJSONObject("single").put("references",MobileJson.value(List.of(new com.nexodb.db.ForeignKeyLookup.Reference("fk_fixture","","","ui_fixture","","","customers",List.of(new com.nexodb.db.ForeignKeyLookup.Part("id",0,"id")))))).put("editing",new JSONObject("{\"target\":{\"columns\":[{\"writable\":true},{\"writable\":true},{\"writable\":true}]}}"));
            String script;
            try(var input=test.getContext().getAssets().open("query-results-test.js")){script=new String(StreamCompat.readNBytes(input,32768),StandardCharsets.UTF_8);}
            evaluate(test,web,"window.NexoResultFixtures="+fixtures+";window.NexoResultUiOutcome=null;"+script);
            Object outcome=await(test,web,"window.NexoResultUiOutcome",false);
            if(!"OK".equals(outcome))throw new AssertionError(outcome);
            java.io.File directory=new java.io.File(test.getTargetContext().getCacheDir(),"updates");directory.mkdirs();
            java.io.File apk=new java.io.File(directory,"installer-test.apk");
            try{
                java.nio.file.Files.copy(java.nio.file.Path.of(test.getTargetContext().getApplicationInfo().sourceDir),apk.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                UpdateInstaller.verify(test.getTargetContext(),apk,BuildConfig.VERSION_CODE,false);
                try{UpdateInstaller.verify(test.getTargetContext(),apk,BuildConfig.VERSION_CODE,true);throw new AssertionError("A same-version APK must not be offered as an upgrade");}catch(SecurityException expected){}
                Intent installer=UpdateInstaller.installIntent(test.getTargetContext(),apk);
                if(!"content".equals(installer.getData().getScheme())||!"application/vnd.android.package-archive".equals(installer.getType())||(installer.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)throw new AssertionError("Invalid Android installer intent");
                if(installer.resolveActivity(test.getTargetContext().getPackageManager())==null)throw new AssertionError("Android package installer is unavailable");
                JSONObject manifest=new JSONObject().put("version",BuildConfig.VERSION_NAME).put("versionCode",BuildConfig.VERSION_CODE).put("downloadUrl","https://example.test/update.apk").put("sha256","a".repeat(64));
                if(MobileUpdate.validate("\uFEFF"+manifest).getBoolean("available"))throw new AssertionError("Current version offered as update");
                for(String bad:new String[]{new JSONObject(manifest.toString()).put("downloadUrl","http://example.test/update.apk").toString(),new JSONObject(manifest.toString()).put("sha256","invalid").toString(),new JSONObject(manifest.toString()).put("versionCode",1).toString(),new JSONObject(manifest.toString()).put("size",999999999L).toString()}){try{MobileUpdate.validate(bad);throw new AssertionError("Invalid update manifest accepted");}catch(java.io.IOException expected){}}
            }finally{java.nio.file.Files.deleteIfExists(apk.toPath());}
        } finally { test.runOnMainSync(activity::finish);originalStore.put("draft",originalDraft); }
    }
    private static Object await(Instrumentation test,WebView web,String expression,boolean ready)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){Object result=evaluate(test,web,expression);if(ready?Boolean.TRUE.equals(result):result instanceof String)return result;Thread.sleep(100);}
        throw new AssertionError("Timed out waiting for the Android result UI: "+expression);
    }
    private static Object evaluate(Instrumentation test,WebView web,String script)throws Exception {
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
        test.runOnMainSync(()->web.evaluateJavascript(script,value->{result.set(value);latch.countDown();}));
        if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("WebView JavaScript callback timed out");
        return new JSONTokener(result.get()).nextValue();
    }
}
