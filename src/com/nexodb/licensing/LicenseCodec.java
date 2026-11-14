package com.nexodb.licensing;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;

/** Offline Ed25519 licenses. The client contains only a public verification key. */
public final class LicenseCodec {
    public record License(String id,String customer,Instant issued,Instant starts,Instant expires){public boolean unlimited(){return expires==null;}}
    private static final Base64.Encoder B64=Base64.getUrlEncoder().withoutPadding();private static final Base64.Decoder DECODER=Base64.getUrlDecoder();
    private LicenseCodec(){}
    public static PublicKey publicKey(byte[] bytes)throws GeneralSecurityException{return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));}
    public static byte[] payload(License license){
        if(!license.id().matches("NDB1-(?:[0-9A-HJKMNP-TV-Z]{4}-){8}[0-9A-HJKMNP-TV-Z]{4}"))throw new IllegalArgumentException("Identificador de licencia no válido.");
        if(license.customer().isBlank()||license.customer().length()>200)throw new IllegalArgumentException("Cliente no válido.");
        return String.join("\n","NEXODB-LICENSE-1",license.id(),B64.encodeToString(license.customer().getBytes(StandardCharsets.UTF_8)),""+license.issued().getEpochSecond(),""+license.starts().getEpochSecond(),license.expires()==null?"0":""+license.expires().getEpochSecond(),"TRANSFERABLE","NexoDB-Studio").getBytes(StandardCharsets.UTF_8);
    }
    public static String token(byte[] payload,byte[] signature){return "NXL1."+B64.encodeToString(payload)+"."+B64.encodeToString(signature);}
    public static License verify(String token,PublicKey key,Instant now)throws GeneralSecurityException{
        try{
            if(token.length()>32768)throw new IllegalArgumentException();String[] pieces=token.replaceAll("\\s+","").split("\\.",-1);if(pieces.length!=3||!pieces[0].equals("NXL1"))throw new IllegalArgumentException();byte[] payload=DECODER.decode(pieces[1]),signed=DECODER.decode(pieces[2]);if(payload.length>4096||signed.length!=64)throw new IllegalArgumentException();
            Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(payload);if(!verifier.verify(signed))throw new SignatureException("La firma de la licencia no es válida.");
            String[] values=new String(payload,StandardCharsets.UTF_8).split("\n",-1);if(values.length!=8||!values[0].equals("NEXODB-LICENSE-1")||!values[6].equals("TRANSFERABLE")||!values[7].equals("NexoDB-Studio"))throw new IllegalArgumentException();
            License license=new License(values[1],new String(DECODER.decode(values[2]),StandardCharsets.UTF_8),Instant.ofEpochSecond(Long.parseLong(values[3])),Instant.ofEpochSecond(Long.parseLong(values[4])),values[5].equals("0")?null:Instant.ofEpochSecond(Long.parseLong(values[5])));payload(license);
            if(license.starts().isAfter(now)||license.issued().isAfter(now.plusSeconds(300)))throw new GeneralSecurityException("La licencia todavía no está vigente. Revisa la fecha del equipo.");
            if(license.expires()!=null&&(!license.expires().isAfter(license.issued())||!license.expires().isAfter(now)))throw new GeneralSecurityException("La licencia venció. Activa una licencia vigente para continuar.");return license;
        }catch(IllegalArgumentException error){throw new GeneralSecurityException("Formato de licencia no válido. Carga el archivo .nexolicense o pega la clave completa.",error);}
    }
}
