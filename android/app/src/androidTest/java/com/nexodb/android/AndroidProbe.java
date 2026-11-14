package com.nexodb.android;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.licensing.LicenseCodec;
import java.sql.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Runs on ART against a database directory whose hash must match the isolated host fixture. */
public final class AndroidProbe extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    @Override public void onStart(){Bundle output=new Bundle();try{if("true".equals(args.getString("upgradePrepare"))){prepareUpgrade();output.putString("stream","NEXODB_UPGRADE_PREPARED\n");}else if("true".equals(args.getString("upgradeVerify"))){verifyUpgrade();output.putString("stream","NEXODB_UPGRADE_VERIFIED: profile, encrypted password, SQL draft, settings and license file preserved\n");}else if("true".equals(args.getString("upgradeInstall"))){installUpgrade();}else if("true".equals(args.getString("uiOnly"))){AndroidResultUiTest.run(this);output.putString("stream","NEXODB_ANDROID_RESULT_UI_OK: SELECT results, FK lookup/staging, update manifest validation and installation retry\n");}else{probe();output.putString("stream","NEXODB_ANDROID_PROBE_OK: direct JDBC, metadata, FK lookup, unlimited reads, row updates, SQL backup/restore, Keystore and signatures\n");}finish(Activity.RESULT_OK,output);}catch(Throwable error){output.putString("stream","NEXODB_ANDROID_PROBE_FAILED: "+error.toString()+"\n"+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,output);}}
    private void prepareUpgrade()throws Exception{
        MobileStore store=new MobileStore(getTargetContext());check(store.get("upgrade-probe-old-draft","").isEmpty(),"An earlier upgrade fixture needs cleanup");
        store.put("upgrade-probe-old-draft",store.get("draft","{}"));store.put("upgrade-probe-settings",store.get("settings",""));store.put("upgrade-probe-profiles",digest(store.profiles().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path license=getTargetContext().getFilesDir().toPath().resolve("license.nexolicense");store.put("upgrade-probe-license",Files.exists(license)?digest(Files.readAllBytes(license)):"absent");
        store.saveProfile(new org.json.JSONObject().put("id","__nexodb-upgrade-proof__").put("name","Isolated upgrade proof").put("remember",true).put("password","upgrade-fixture-only"));store.put("draft","{\"tabs\":[{\"sql\":\"SELECT 'upgrade-proof';\"}]}");
    }
    private void verifyUpgrade()throws Exception{
        MobileStore store=new MobileStore(getTargetContext());check(new String(store.password(store.profile("__nexodb-upgrade-proof__"),"")).equals("upgrade-fixture-only"),"Encrypted password lost during upgrade");check(store.get("draft","").contains("upgrade-proof"),"SQL draft lost during upgrade");check(store.get("settings","").equals(store.get("upgrade-probe-settings","")),"Settings changed during upgrade");Path license=getTargetContext().getFilesDir().toPath().resolve("license.nexolicense");check((Files.exists(license)?digest(Files.readAllBytes(license)):"absent").equals(store.get("upgrade-probe-license","")),"License changed during upgrade");
        store.removeProfile("__nexodb-upgrade-proof__");check(digest(store.profiles().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(store.get("upgrade-probe-profiles","")),"Existing profiles changed during upgrade");store.put("draft",store.get("upgrade-probe-old-draft","{}"));for(String key:List.of("upgrade-probe-old-draft","upgrade-probe-settings","upgrade-probe-profiles","upgrade-probe-license"))store.put(key,"");
    }
    private void installUpgrade()throws Exception{
        Activity activity=startActivitySync(new android.content.Intent(getTargetContext(),MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        java.io.File file=new java.io.File(activity.getCacheDir(),"updates/update.apk");file.getParentFile().mkdirs();
        try(android.os.ParcelFileDescriptor pipe=getUiAutomation().executeShellCommand("cat /data/local/tmp/nexodb-upgrade.apk");java.io.InputStream input=new java.io.FileInputStream(pipe.getFileDescriptor())){Files.copy(input,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        Object result=new UpdateInstaller((MainActivity)activity).request(file,1404);Bundle progress=new Bundle();progress.putString("stream","NEXODB_UPGRADE_INSTALLER_OPENED: "+result+"\n");sendStatus(0,progress);
        boolean returned=false,confirmed=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<deadline){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null){String owner=String.valueOf(root.getPackageName());java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes=new java.util.ArrayDeque<>();nodes.add(root);
                while(!nodes.isEmpty()){var node=nodes.removeFirst();String text=String.valueOf(node.getText());
                    if(owner.equals("com.android.settings")&&node.isVisibleToUser()&&node.isCheckable()&&!node.isChecked()&&node.isEnabled()){var control=node.isClickable()?node:node.getParent();if(control!=null)control.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);}
                    if(!confirmed&&owner.contains("packageinstaller")&&node.isVisibleToUser()&&node.isEnabled()&&(text.equalsIgnoreCase("Update")||text.equalsIgnoreCase("Install")||text.equalsIgnoreCase("Actualizar")||text.equalsIgnoreCase("Instalar"))){if(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)){confirmed=true;Bundle accepted=new Bundle();accepted.putString("stream","NEXODB_UPGRADE_SYSTEM_CONFIRMATION_ACCEPTED\n");sendStatus(0,accepted);}}
                    for(int i=0;i<node.getChildCount();i++){var child=node.getChild(i);if(child!=null)nodes.add(child);}
                }
                if(owner.equals("com.android.settings")&&activity.getPackageManager().canRequestPackageInstalls()&&!returned){returned=true;sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);}
            }Thread.sleep(200);
        }throw new AssertionError("System installer did not complete the upgrade");
    }
    private static String digest(byte[] bytes)throws Exception{return HexCompat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private void probe()throws Exception{
        Path document=getTargetContext().getCacheDir().toPath().resolve("probe-document.sql");
        com.nexodb.util.SqlFiles.write(document,"SELECT 'Android';\n");
        check(com.nexodb.util.SqlFiles.read(document).text().equals("SELECT 'Android';\n"),"SQL document round trip on ART");Files.delete(document);
        var profile=new ConnectionProfile("android-probe","Android probe",DatabaseType.MARIADB,"10.0.2.2",43183,"","root","jdbc:mariadb://10.0.2.2:43183/?sslMode=disable","org.mariadb.jdbc.Driver","",false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            try(Statement s=session.connection().createStatement();ResultSet r=s.executeQuery("SELECT @@datadir")){r.next();String data=r.getString(1).replace('\\','/').replaceAll("/+$","").toLowerCase(Locale.ROOT);String hash=HexCompat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));check(hash.equals(args.getString("datadirSha")),"Refusing a database outside the isolated fixture");}
            session.execute("CREATE DATABASE IF NOT EXISTS nx_android",1);session.execute("CREATE DATABASE IF NOT EXISTS nx_android_restored",1);session.execute("DROP TABLE IF EXISTS nx_android_restored.child",1);session.execute("DROP TABLE IF EXISTS nx_android_restored.parent",1);session.connection().setCatalog("nx_android");session.execute("DROP TABLE IF EXISTS child",1);session.execute("DROP TABLE IF EXISTS parent",1);session.execute("CREATE TABLE parent(id INT PRIMARY KEY,name VARCHAR(50))",1);session.execute("CREATE TABLE child(id INT PRIMARY KEY,parent_id INT,FOREIGN KEY(parent_id) REFERENCES parent(id))",1);
            try(PreparedStatement insert=session.connection().prepareStatement("INSERT INTO parent VALUES(?,?)")){for(int i=1;i<=1205;i++){insert.setInt(1,i);insert.setString(2,"Móvil "+i);insert.addBatch();}insert.executeBatch();}session.execute("INSERT INTO child VALUES(1,1)",1);
            check(session.execute("SELECT * FROM parent",1000).rows().size()==1000,"Default row limit");var rows=session.execute("SELECT * FROM parent ORDER BY id",Integer.MAX_VALUE);check(rows.rows().size()==1205,"Unlimited JDBC rows");check(rows.editing()!=null&&rows.editing().target()!=null,"Native editable metadata");
            QueryEditSupport.save(session.connection(),rows.editing().target(),rows.rows().get(0),1,"Cambio Android");check(session.execute("SELECT name FROM parent WHERE id=1",10).rows().get(0).get(0).equals("Cambio Android"),"Confirmed row update core");
            var schema=SchemaMetadataReader.read(session,"nx_android");check(schema.objects().size()==2,"Schema snapshot");check(ForeignKeyDefinition.read(session.connection(),DatabaseType.MARIADB,"nx_android","child").size()==1,"Foreign key metadata");
            var childRows=session.execute("SELECT * FROM child",100);var references=ForeignKeyLookup.describe(session.connection(),childRows);check(references.size()==1,"Result foreign-key origins on ART");var related=ForeignKeyLookup.search(session.connection(),DatabaseType.MARIADB,references.get(0),childRows.rows().get(0),1,"1","",true,0,25);check(related.exact()&&related.result().rows().size()==1,"Exact parent lookup on ART");check(ForeignKeyLookup.search(session.connection(),DatabaseType.MARIADB,references.get(0),childRows.rows().get(0),1,"Móvil","",true,25,25).result().rows().size()==25,"Parent fuzzy search and pagination on ART");
            String prefix="SELECT * FROM par";var edit=SqlTableAliases.complete(prefix,prefix.length(),"parent","`");check(edit!=null&&edit.insertion().endsWith(" AS p"),"Readable table alias");check(session.execute(prefix.substring(0,edit.start())+edit.insertion(),Integer.MAX_VALUE).rows().size()==1205,"Aliased SELECT executes on ART");
            prefix="SELECT * FROM parent AS p JOIN ";var joins=new SqlJoinCompletion(session).suggestions(prefix,prefix.length(),"nx_android");check(!joins.isEmpty()&&joins.get(0).insertion().contains(" AS `c`"),"Related-table alias and ON expression");check(session.execute(prefix+joins.get(0).insertion(),100).rows().size()==1,"Aliased JOIN executes on ART");
            var snapshot=MysqlTableDesign.read(session,"nx_android","parent");check(snapshot.columns().size()==2,"Structure metadata");
            String before=String.valueOf(session.execute("SELECT CONNECTION_ID()",1).rows().get(0).get(0));
            session.setReadOnly(true);session.execute("SET SESSION wait_timeout=2",1);Thread.sleep(17000);
            check(session.execute("SELECT * FROM parent WHERE id=1",10).rows().size()==1,"Read after actual server idle timeout");
            String after=String.valueOf(session.execute("SELECT CONNECTION_ID()",1).rows().get(0).get(0));check(!before.equals(after),"An expired socket must be replaced");check(session.getReadOnly(),"Read-only state survives recovery");check(session.connection().getCatalog().equals("nx_android"),"Selected database survives recovery");session.setReadOnly(false);
            Path backup=getTargetContext().getCacheDir().toPath().resolve("probe.sql");var objects=SqlBackupService.objects(session.connection(),"nx_android");SqlBackupService.write(session.connection(),DatabaseType.MARIADB,"nx_android",objects,new SqlBackupService.Options(SqlBackupService.Mode.STRUCTURE_AND_DATA,true,false,true,false,true),backup,new SqlTransferControl(),p->{});
            var analysis=SqlImportService.analyze(backup,new SqlTransferControl());check(analysis.statements()>10,"Complete backup with inserts");SqlImportService.restore(session.connection(),analysis,new SqlImportService.Options("nx_android_restored",true,false,true),new SqlTransferControl(),p->{});session.setAutoCommit(true);check(session.execute("SELECT * FROM nx_android_restored.parent",Integer.MAX_VALUE).rows().size()==1205,"Restored rows");Files.delete(backup);
        }
        MobileStore store=new MobileStore(getTargetContext());store.saveProfile(new org.json.JSONObject().put("id","probe-store").put("name","Test only").put("remember",true).put("password","fixture-secret"));check(new String(store.password(store.profile("probe-store"),"")).equals("fixture-secret"),"Keystore encryption");check(!store.publicProfiles().toString().contains("fixture-secret"),"No password exposed to renderer");store.removeProfile("probe-store");new MobileLicense(getTargetContext(),store);
        KeyPairGenerator generator=KeyPairGenerator.getInstance("Ed25519",Ed25519.provider());KeyPair keys=generator.generateKeyPair();Instant now=Instant.now();var license=new LicenseCodec.License("NDB1-1234-1234-1234-1234-1234-1234-1234-1234-1234","Test only",now,now,null);byte[] payload=LicenseCodec.payload(license);Signature signature=Ed25519.signature();signature.initSign(keys.getPrivate());signature.update(payload);check(LicenseCodec.verify(LicenseCodec.token(payload,signature.sign()),keys.getPublic(),now).unlimited(),"Transferable license codec");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
