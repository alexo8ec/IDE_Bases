package com.nexodb.android;

import java.security.*;

/** Explicit software provider for portable X.509 license keys; Android Keystore stays the AES provider. */
public final class Ed25519 {
    private static final Provider PROVIDER=new org.bouncycastle.jce.provider.BouncyCastleProvider();
    private Ed25519(){}
    public static Provider provider(){return PROVIDER;}
    public static KeyFactory keyFactory()throws NoSuchAlgorithmException{return KeyFactory.getInstance("Ed25519",PROVIDER);}
    public static Signature signature()throws NoSuchAlgorithmException{return Signature.getInstance("Ed25519",PROVIDER);}
}
