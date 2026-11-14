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
    @Override public void onStart(){Bundle output=new Bundle();try{probe();output.putString("stream","NEXODB_ANDROID_PROBE_OK: direct JDBC, metadata, unlimited reads, row updates, SQL backup/restore, Keystore and signatures\n");finish(Activity.RESULT_OK,output);}catch(Throwable error){output.putString("stream","NEXODB_ANDROID_PROBE_FAILED: "+error.toString()+"\n"+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,output);}}
    private void probe()throws Exception{
        var profile=new ConnectionProfile("android-probe","Android probe",DatabaseType.MARIADB,"10.0.2.2",43183,"","root","jdbc:mariadb://10.0.2.2:43183/?sslMode=disable","org.mariadb.jdbc.Driver","",false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            try(Statement s=session.connection().createStatement();ResultSet r=s.executeQuery("SELECT @@datadir")){r.next();String data=r.getString(1).replace('\\','/').replaceAll("/+$","").toLowerCase(Locale.ROOT);String hash=HexCompat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));check(hash.equals(args.getString("datadirSha")),"Refusing a database outside the isolated fixture");}
            session.execute("CREATE DATABASE IF NOT EXISTS nx_android",1);session.execute("CREATE DATABASE IF NOT EXISTS nx_android_restored",1);session.execute("DROP TABLE IF EXISTS nx_android_restored.child",1);session.execute("DROP TABLE IF EXISTS nx_android_restored.parent",1);session.connection().setCatalog("nx_android");session.execute("DROP TABLE IF EXISTS child",1);session.execute("DROP TABLE IF EXISTS parent",1);session.execute("CREATE TABLE parent(id INT PRIMARY KEY,name VARCHAR(50))",1);session.execute("CREATE TABLE child(id INT PRIMARY KEY,parent_id INT,FOREIGN KEY(parent_id) REFERENCES parent(id))",1);
            try(PreparedStatement insert=session.connection().prepareStatement("INSERT INTO parent VALUES(?,?)")){for(int i=1;i<=1205;i++){insert.setInt(1,i);insert.setString(2,"Móvil "+i);insert.addBatch();}insert.executeBatch();}session.execute("INSERT INTO child VALUES(1,1)",1);
            check(session.execute("SELECT * FROM parent",1000).rows().size()==1000,"Default row limit");var rows=session.execute("SELECT * FROM parent ORDER BY id",Integer.MAX_VALUE);check(rows.rows().size()==1205,"Unlimited JDBC rows");check(rows.editing()!=null&&rows.editing().target()!=null,"Native editable metadata");
            QueryEditSupport.save(session.connection(),rows.editing().target(),rows.rows().get(0),1,"Cambio Android");check(session.execute("SELECT name FROM parent WHERE id=1",10).rows().get(0).get(0).equals("Cambio Android"),"Confirmed row update core");
            var schema=SchemaMetadataReader.read(session,"nx_android");check(schema.objects().size()==2,"Schema snapshot");check(ForeignKeyDefinition.read(session.connection(),DatabaseType.MARIADB,"nx_android","child").size()==1,"Foreign key metadata");
            var snapshot=MysqlTableDesign.read(session,"nx_android","parent");check(snapshot.columns().size()==2,"Structure metadata");
            Path backup=getTargetContext().getCacheDir().toPath().resolve("probe.sql");var objects=SqlBackupService.objects(session.connection(),"nx_android");SqlBackupService.write(session.connection(),DatabaseType.MARIADB,"nx_android",objects,new SqlBackupService.Options(SqlBackupService.Mode.STRUCTURE_AND_DATA,true,false,true,false,true),backup,new SqlTransferControl(),p->{});
            var analysis=SqlImportService.analyze(backup,new SqlTransferControl());check(analysis.statements()>10,"Complete backup with inserts");SqlImportService.restore(session.connection(),analysis,new SqlImportService.Options("nx_android_restored",true,false,true),new SqlTransferControl(),p->{});session.setAutoCommit(true);check(session.execute("SELECT * FROM nx_android_restored.parent",Integer.MAX_VALUE).rows().size()==1205,"Restored rows");Files.delete(backup);
        }
        MobileStore store=new MobileStore(getTargetContext());store.saveProfile(new org.json.JSONObject().put("id","probe-store").put("name","Test only").put("remember",true).put("password","fixture-secret"));check(new String(store.password(store.profile("probe-store"),"")).equals("fixture-secret"),"Keystore encryption");check(!store.publicProfiles().toString().contains("fixture-secret"),"No password exposed to renderer");store.removeProfile("probe-store");new MobileLicense(getTargetContext(),store);
        KeyPairGenerator generator=KeyPairGenerator.getInstance("Ed25519",Ed25519.provider());KeyPair keys=generator.generateKeyPair();Instant now=Instant.now();var license=new LicenseCodec.License("NDB1-1234-1234-1234-1234-1234-1234-1234-1234-1234","Test only",now,now,null);byte[] payload=LicenseCodec.payload(license);Signature signature=Ed25519.signature();signature.initSign(keys.getPrivate());signature.update(payload);check(LicenseCodec.verify(LicenseCodec.token(payload,signature.sign()),keys.getPublic(),now).unlimited(),"Transferable license codec");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
