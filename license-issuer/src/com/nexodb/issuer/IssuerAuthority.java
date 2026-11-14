package com.nexodb.issuer;

import com.nexodb.licensing.LicenseCodec;
import java.security.*;
import java.time.*;

public final class IssuerAuthority {
    private IssuerAuthority(){}
    public static String id(){String alphabet="0123456789ABCDEFGHJKMNPQRSTVWXYZ";SecureRandom random=new SecureRandom();StringBuilder result=new StringBuilder("NDB1-");for(int group=0;group<9;group++){if(group>0)result.append('-');for(int c=0;c<4;c++)result.append(alphabet.charAt(random.nextInt(32)));}return result.toString();}
    public static String issue(KeyPair key,String customer,Instant expires)throws Exception{Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);if(expires!=null&&!expires.isAfter(now))throw new IllegalArgumentException("La fecha de vencimiento debe estar en el futuro.");LicenseCodec.License license=new LicenseCodec.License(id(),customer.trim(),now,now.minusSeconds(60),expires);byte[] payload=LicenseCodec.payload(license);Signature signature=Signature.getInstance("Ed25519");signature.initSign(key.getPrivate());signature.update(payload);String token=LicenseCodec.token(payload,signature.sign());LicenseCodec.verify(token,key.getPublic(),now);return token;}
}
