package com.nexodb.licensing;

import com.nexodb.util.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

public final class LicenseManager {
    public static final int TRIAL_DAYS=15;
    public record Trial(Instant started,Instant expires,int daysRemaining,boolean active){}
    private final Path folder;private final PublicKey key;
    public LicenseManager(Path folder,PublicKey key){this.folder=folder.toAbsolutePath();this.key=key;}
    public static LicenseManager configured()throws Exception{try(InputStream input=LicenseManager.class.getResourceAsStream("/com/nexodb/licensing/public-key.der")){if(input==null)throw new GeneralSecurityException("No está configurada la autoridad de licencias del distribuidor.");return new LicenseManager(AppSettings.file().getParent(),LicenseCodec.publicKey(input.readAllBytes()));}}
    public LicenseCodec.License verify(String token,Instant now)throws Exception{
        var license=LicenseCodec.verify(token,key,now);Properties state=AtomicProperties.read(folder.resolve("license-clock.properties"));if(!license.unlimited()&&license.id().equals(state.getProperty("id"))){long seen=Long.parseLong(state.getProperty("lastSeen","0"));if(now.isBefore(Instant.ofEpochSecond(seen).minusSeconds(300)))throw new GeneralSecurityException("La fecha del equipo retrocedió. Corrige el reloj para validar la licencia.");}return license;
    }
    public LicenseCodec.License installed(Instant now)throws Exception{Path file=folder.resolve("license.nexolicense");if(!Files.isRegularFile(file))throw new GeneralSecurityException("Activa una licencia de AdisDB para continuar.");if(Files.size(file)>32768)throw new GeneralSecurityException("Archivo de licencia no válido.");return verify(Files.readString(file),now);}
    public Trial trial(Instant now)throws Exception{
        Path file=folder.resolve("trial.properties");Properties state=AtomicProperties.read(file);long started,lastSeen;
        if(state.isEmpty()){started=now.getEpochSecond();lastSeen=started;state.setProperty("version","1");state.setProperty("started",Long.toString(started));}
        else try{started=Long.parseLong(state.getProperty("started"));lastSeen=Long.parseLong(state.getProperty("lastSeen",Long.toString(started)));}catch(RuntimeException error){throw new GeneralSecurityException("No se pudo validar el período de prueba de esta instalación.",error);}
        if(now.isBefore(Instant.ofEpochSecond(lastSeen).minusSeconds(300)))throw new GeneralSecurityException("La fecha del equipo retrocedió. Corrige el reloj para validar el período de prueba.");
        Instant begins=Instant.ofEpochSecond(started),expires=begins.plus(Duration.ofDays(TRIAL_DAYS));long seconds=Math.max(0,Duration.between(now,expires).getSeconds());int days=(int)Math.min(TRIAL_DAYS,(seconds+86399)/86400);boolean active=now.isBefore(expires);
        state.setProperty("lastSeen",Long.toString(Math.max(lastSeen,now.getEpochSecond())));Files.createDirectories(folder);AtomicProperties.write(file,state);return new Trial(begins,expires,days,active);
    }
    public void activate(String token,Instant now)throws Exception{var license=verify(token,now);Files.createDirectories(folder);SqlFiles.write(folder.resolve("license.nexolicense"),token.replaceAll("\\s+","")+"\n");record(license,now);}
    public void record(LicenseCodec.License license,Instant now)throws IOException{Properties state=new Properties();state.setProperty("id",license.id());state.setProperty("lastSeen",""+now.getEpochSecond());AtomicProperties.write(folder.resolve("license-clock.properties"),state);}
}
