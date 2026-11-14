package com.nexodb.issuer;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** One-time build provisioning. Refuses to overwrite an existing signing authority. */
public final class IssuerBootstrap {
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Private authority folder and public key path required");Path folder=Path.of(args[0]).toAbsolutePath(),pub=Path.of(args[1]).toAbsolutePath();
        if(Files.exists(pub)||Files.exists(folder.resolve("issuer.nxvault")))throw new IllegalStateException("La autoridad ya existe. No se reemplazó ninguna clave.");
        Files.createDirectories(folder);Files.createDirectories(pub.getParent());try{Files.setPosixFilePermissions(folder,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
        KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] random=new byte[30];new SecureRandom().nextBytes(random);char[] password=Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray();Arrays.fill(random,(byte)0);
        try{IssuerVault.save(folder.resolve("issuer.nxvault"),pair,password);Files.write(pub,pair.getPublic().getEncoded(),StandardOpenOption.CREATE_NEW);Files.writeString(folder.resolve("contraseña-inicial.txt"),new String(password)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Files.writeString(folder.resolve("licencia-administrador.nexolicense"),IssuerAuthority.issue(pair,"Administrador de NexoDB",null)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("LEEME.txt"),"AUTORIDAD PRIVADA DE LICENCIAS NEXODB\n\nNo publiques ni compartas esta carpeta. No está incluida en Git ni en los paquetes del cliente.\n\nissuer.nxvault: clave privada cifrada del generador. Haz una copia de seguridad privada.\ncontraseña-inicial.txt: contraseña inicial. Abre el Generador, desbloquea esta autoridad y cambia la contraseña.\nlicencia-administrador.nexolicense: licencia ilimitada y transferible para tu propia instalación. No la distribuyas a clientes.\n\nLos clientes solo necesitan el archivo .nexolicense que generes para ellos.\nPerder la autoridad impide emitir nuevas licencias compatibles con la clave pública actual.\nCambiar la autoridad requiere distribuir una nueva clave pública en el cliente.\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }finally{Arrays.fill(password,'\0');}System.out.println("Autoridad creada. Los secretos quedaron únicamente en la carpeta privada; no se mostraron en la salida.");
    }
}
