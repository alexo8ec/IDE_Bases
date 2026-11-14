package com.nexodb.issuer;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/** Encrypted administrative signing authority, never bundled in the client. */
public final class IssuerVault {
    private static final int MAGIC=0x4E585631,ITERATIONS=210000;
    private IssuerVault(){}
    public static void save(Path file,KeyPair pair,char[] password)throws Exception{
        if(password.length<12)throw new IllegalArgumentException("Usa una contraseña de al menos 12 caracteres.");SecureRandom random=new SecureRandom();byte[] salt=new byte[16],nonce=new byte[12];random.nextBytes(salt);random.nextBytes(nonce);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream data=new DataOutputStream(bytes)){byte[] pub=pair.getPublic().getEncoded(),privateBytes=pair.getPrivate().getEncoded();data.writeInt(pub.length);data.write(pub);data.writeInt(privateBytes.length);data.write(privateBytes);Arrays.fill(privateBytes,(byte)0);}
        PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);byte[] derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();byte[] plain=bytes.toByteArray();
        Path target=file.toAbsolutePath(),temp=Files.createTempFile(target.getParent(),".issuer-",".tmp");try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(derived,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD("NexoDB-Issuer-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));byte[] encrypted=cipher.doFinal(plain);try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(temp))){out.writeInt(MAGIC);out.write(salt);out.write(nonce);out.writeInt(encrypted.length);out.write(encrypted);}try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}}finally{spec.clearPassword();Arrays.fill(derived,(byte)0);Arrays.fill(plain,(byte)0);Files.deleteIfExists(temp);}
    }
    public static KeyPair load(Path file,char[] password)throws Exception{
        if(Files.size(file)>65536)throw new GeneralSecurityException("Archivo de autoridad no válido.");byte[] salt=new byte[16],nonce=new byte[12],encrypted;
        try(DataInputStream input=new DataInputStream(Files.newInputStream(file))){if(input.readInt()!=MAGIC)throw new GeneralSecurityException("Archivo de autoridad no válido.");input.readFully(salt);input.readFully(nonce);int count=input.readInt();if(count<16||count>60000)throw new GeneralSecurityException("Archivo de autoridad no válido.");encrypted=input.readNBytes(count);if(encrypted.length!=count||input.read()!=-1)throw new GeneralSecurityException("Archivo de autoridad incompleto.");}
        PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);byte[] derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),plain=null;
        try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(derived,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD("NexoDB-Issuer-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));plain=cipher.doFinal(encrypted);try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(plain))){int pubSize=input.readInt();if(pubSize<1||pubSize>1000)throw new GeneralSecurityException();byte[] pub=input.readNBytes(pubSize);int size=input.readInt();if(size<1||size>1000)throw new GeneralSecurityException();byte[] privateBytes=input.readNBytes(size);try{KeyFactory factory=KeyFactory.getInstance("Ed25519");KeyPair pair=new KeyPair(factory.generatePublic(new X509EncodedKeySpec(pub)),factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes)));Signature sig=Signature.getInstance("Ed25519");sig.initSign(pair.getPrivate());sig.update(pub);byte[] proof=sig.sign();sig.initVerify(pair.getPublic());sig.update(pub);if(!sig.verify(proof))throw new GeneralSecurityException("Las claves no coinciden.");return pair;}finally{Arrays.fill(privateBytes,(byte)0);}}}
        catch(AEADBadTagException error){throw new GeneralSecurityException("Contraseña incorrecta o archivo de autoridad alterado.",error);}finally{spec.clearPassword();Arrays.fill(derived,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
}
