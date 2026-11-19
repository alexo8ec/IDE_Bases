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
        LicenseManager trialManager=new LicenseManager(root.resolve("trial"),pair.getPublic());var first=trialManager.trial(now);equal(true,first.active());equal(15,first.daysRemaining());equal(1,trialManager.trial(now.plus(Duration.ofDays(14))).daysRemaining());equal(false,trialManager.trial(now.plus(Duration.ofDays(15))).active());reject(()->trialManager.trial(now.minusSeconds(301)));
        char[] password="Testing-only-secret-123".toCharArray();Path vault=root.resolve("issuer.nxvault");IssuerVault.save(vault,pair,password);KeyPair recovered=IssuerVault.load(vault,password);equal(true,Arrays.equals(pair.getPublic().getEncoded(),recovered.getPublic().getEncoded()));String signed=IssuerAuthority.issue(recovered,"Restored authority",null);equal(true,LicenseCodec.verify(signed,pair.getPublic(),now).unlimited());reject(()->IssuerVault.load(vault,"incorrect".toCharArray()));byte[] bytes=Files.readAllBytes(vault);bytes[bytes.length-1]^=1;Files.write(vault,bytes);reject(()->IssuerVault.load(vault,password));Arrays.fill(password,'\0');
        System.out.println("LicenseTest: OK (signed licenses, 15-day installation trial, expiry, clock rollback and encrypted authority vault)");
    }
    @FunctionalInterface interface Throwing{void run()throws Exception;}
    private static void reject(Throwing action)throws Exception{try{action.run();throw new AssertionError("Invalid license/authority accepted");}catch(GeneralSecurityException expected){}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
