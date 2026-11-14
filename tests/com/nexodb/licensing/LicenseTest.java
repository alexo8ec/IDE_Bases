package com.nexodb.licensing;

import com.nexodb.issuer.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

public final class LicenseTest {
    public static void main(String[] args)throws Exception{
        KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Instant now=Instant.now();String limited=IssuerAuthority.issue(pair,"Cliente mañana 😀",now.plusSeconds(86400));var license=LicenseCodec.verify(limited,pair.getPublic(),now);equal(49,license.id().length());equal("Cliente mañana 😀",license.customer());equal(false,license.unlimited());
        reject(()->LicenseCodec.verify(limited,KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic(),now));reject(()->LicenseCodec.verify(limited,pair.getPublic(),now.plusSeconds(86401)));reject(()->LicenseCodec.verify(license.id(),pair.getPublic(),now));
        char[] changed=limited.toCharArray();int at=limited.indexOf('.')+12;changed[at]=changed[at]=='A'?'B':'A';reject(()->LicenseCodec.verify(new String(changed),pair.getPublic(),now));
        String unlimited=IssuerAuthority.issue(pair,"Licencia ilimitada",null);equal(true,LicenseCodec.verify(unlimited,pair.getPublic(),now.plusSeconds(365L*86400*100)).unlimited());
        Path root=Files.createTempDirectory("nexodb-licenses-");LicenseManager one=new LicenseManager(root.resolve("device1"),pair.getPublic()),two=new LicenseManager(root.resolve("device2"),pair.getPublic());one.activate(unlimited,now);two.activate(unlimited,now);equal(one.installed(now).id(),two.installed(now).id());one.activate(limited,now);one.record(one.installed(now),now.plusSeconds(3600));reject(()->one.installed(now));
        char[] password="Testing-only-secret-123".toCharArray();Path vault=root.resolve("issuer.nxvault");IssuerVault.save(vault,pair,password);KeyPair recovered=IssuerVault.load(vault,password);equal(true,Arrays.equals(pair.getPublic().getEncoded(),recovered.getPublic().getEncoded()));String signed=IssuerAuthority.issue(recovered,"Restored authority",null);equal(true,LicenseCodec.verify(signed,pair.getPublic(),now).unlimited());reject(()->IssuerVault.load(vault,"incorrect".toCharArray()));byte[] bytes=Files.readAllBytes(vault);bytes[bytes.length-1]^=1;Files.write(vault,bytes);reject(()->IssuerVault.load(vault,password));Arrays.fill(password,'\0');
        System.out.println("LicenseTest: OK (49-character IDs, Ed25519 signature, wrong key/tampering, expiry/unlimited, two-device transfer, clock rollback, encrypted vault and incorrect password)");
    }
    @FunctionalInterface interface Throwing{void run()throws Exception;}
    private static void reject(Throwing action)throws Exception{try{action.run();throw new AssertionError("Invalid license/authority accepted");}catch(GeneralSecurityException expected){}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
