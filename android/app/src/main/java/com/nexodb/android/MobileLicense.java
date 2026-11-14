package com.nexodb.android;

import android.content.Context;
import com.nexodb.licensing.LicenseCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;

final class MobileLicense {
    private final Path file;private final MobileStore store;private final PublicKey key;
    MobileLicense(Context context,MobileStore store)throws Exception{
        this.store=store;file=context.getFilesDir().toPath().resolve("license.nexolicense");
        try(var input=MobileLicense.class.getResourceAsStream("/com/nexodb/licensing/public-key.der")){if(input==null)throw new GeneralSecurityException("No se encontró la clave pública de NexoDB.");key=LicenseCodec.publicKey(StreamCompat.readNBytes(input,8192));}
    }
    LicenseCodec.License current()throws Exception{
        if(!Files.isRegularFile(file))throw new GeneralSecurityException("Activa una licencia de NexoDB para continuar.");
        var license=verify(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));long now=System.currentTimeMillis(),last=Long.parseLong(store.get("license-clock","0"));
        if(!license.unlimited()&&now+300000<last)throw new GeneralSecurityException("La fecha del teléfono retrocedió. Corrige la fecha para validar la licencia.");
        if(now>last)store.put("license-clock",Long.toString(now));return license;
    }
    LicenseCodec.License verify(String token)throws Exception{return LicenseCodec.verify(token,key,Instant.now());}
    LicenseCodec.License activate(String token)throws Exception{
        var license=verify(token);Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"license-",".tmp");
        try{Files.write(temporary,token.trim().getBytes(StandardCharsets.UTF_8));Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
        store.put("license-clock",Long.toString(System.currentTimeMillis()));return license;
    }
}
