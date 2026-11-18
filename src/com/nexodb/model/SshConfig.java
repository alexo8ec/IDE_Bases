package com.nexodb.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Non-secret SSH tunnel settings. Passwords and key passphrases live in SecretStore. */
public record SshConfig(boolean enabled,String host,int port,String username,Authentication authentication,
                        String privateKey,String knownHosts,boolean strictHostKeyChecking,int localPort,
                        int connectTimeoutSeconds,int keepAliveSeconds) {
    public enum Authentication {
        PASSWORD("Contraseña"),PRIVATE_KEY("Clave privada");
        private final String label;
        Authentication(String label){this.label=label;}
        @Override public String toString(){return label;}
    }
    public SshConfig {
        host=value(host);username=value(username);privateKey=value(privateKey);knownHosts=value(knownHosts);
        authentication=Objects.requireNonNullElse(authentication,Authentication.PASSWORD);
        if(port<1||port>65535)port=22;
        if(localPort<0||localPort>65535)localPort=0;
        if(connectTimeoutSeconds<1||connectTimeoutSeconds>120)connectTimeoutSeconds=10;
        if(keepAliveSeconds<0||keepAliveSeconds>3600)keepAliveSeconds=30;
    }
    public static SshConfig disabled(){return new SshConfig(false,"",22,"",Authentication.PASSWORD,"","",false,0,10,30);}
    public static String secretId(String profileId){return (profileId==null?"":profileId)+":ssh";}
    public static String defaultKnownHosts(){
        Path file=Path.of(System.getProperty("user.home","."),".ssh","known_hosts");
        return Files.isRegularFile(file)?file.toString():"";
    }
    private static String value(String value){return value==null?"":value.trim();}
}
