package com.nexodb.db;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.SshConfig;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local TCP forwarding owned by one JDBC session. */
public final class SshTunnel implements AutoCloseable {
    private static final Pattern AUTHORITY=Pattern.compile("(?<=//)(?:\\[[^]]+]|[^/:;]+)(?::\\d+)?");
    private static final Pattern ORACLE_SID=Pattern.compile("(?<=@)(?:\\[[^]]+]|[^/:;]+):\\d+");
    private final Session session;
    private final int localPort;

    private SshTunnel(Session session,int localPort){this.session=session;this.localPort=localPort;}

    public static SshTunnel open(ConnectionProfile profile,char[] secret)throws Exception{
        SshConfig config=profile.ssh();
        if(!config.enabled())throw new IllegalArgumentException("El túnel SSH no está activado.");
        if(config.host().isBlank()||config.username().isBlank())throw new IllegalArgumentException("Completa el host y el usuario SSH.");
        if(profile.host().isBlank()||profile.port()<1)throw new IllegalArgumentException("El túnel SSH requiere el host y puerto remotos de la base de datos.");
        JSch jsch=new JSch();
        if(config.strictHostKeyChecking()){
            if(config.knownHosts().isBlank()||!new File(config.knownHosts()).isFile())throw new IllegalArgumentException("Selecciona un archivo known_hosts válido para verificar el servidor SSH.");
            jsch.setKnownHosts(config.knownHosts());
        }
        byte[] encoded=encode(secret);
        try{
            if(config.authentication()==SshConfig.Authentication.PRIVATE_KEY){
                if(config.privateKey().isBlank()||!new File(config.privateKey()).isFile())throw new IllegalArgumentException("Selecciona una clave privada SSH válida.");
                jsch.addIdentity(config.privateKey(),encoded.length==0?null:encoded);
            }
            Session session=jsch.getSession(config.username(),config.host(),config.port());
            if(config.authentication()==SshConfig.Authentication.PASSWORD)session.setPassword(encoded);
            session.setConfig("PreferredAuthentications",config.authentication()==SshConfig.Authentication.PRIVATE_KEY?"publickey":"password,keyboard-interactive");
            session.setConfig("StrictHostKeyChecking",config.strictHostKeyChecking()?"yes":"no");
            if(config.keepAliveSeconds()>0){session.setServerAliveInterval(config.keepAliveSeconds()*1000);session.setServerAliveCountMax(3);}
            try{
                session.connect(config.connectTimeoutSeconds()*1000);
                int assigned=session.setPortForwardingL("127.0.0.1",config.localPort(),profile.host(),profile.port());
                return new SshTunnel(session,assigned);
            }catch(Exception error){session.disconnect();throw error;}
        }catch(JSchException error){throw friendly(error,config);}
        finally{Arrays.fill(encoded,(byte)0);}
    }

    public ConnectionProfile forward(ConnectionProfile profile){
        return forwardedProfile(profile,localPort);
    }

    static ConnectionProfile forwardedProfile(ConnectionProfile profile,int localPort){
        String endpoint="127.0.0.1:"+localPort;
        String url=replaceFirst(profile.jdbcUrl(),AUTHORITY,endpoint);
        if(url.equals(profile.jdbcUrl()))url=replaceFirst(url,ORACLE_SID,endpoint);
        if(url.equals(profile.jdbcUrl())&&!profile.host().isBlank()){
            String original=profile.host()+(profile.port()>0?":"+profile.port():"");
            int at=url.indexOf(original);if(at>=0)url=url.substring(0,at)+endpoint+url.substring(at+original.length());
        }
        if(url.equals(profile.jdbcUrl()))throw new IllegalArgumentException("No se pudo adaptar la URL JDBC al túnel SSH. Revisa la URL avanzada.");
        return new ConnectionProfile(profile.id(),profile.name(),profile.type(),"127.0.0.1",localPort,profile.database(),profile.username(),url,
                profile.driverClass(),profile.driverJar(),profile.authenticationMode(),profile.ssh(),profile.readOnly());
    }

    private static String replaceFirst(String value,Pattern pattern,String replacement){
        Matcher matcher=pattern.matcher(value);return matcher.find()?matcher.replaceFirst(Matcher.quoteReplacement(replacement)):value;
    }
    private static byte[] encode(char[] secret){if(secret==null||secret.length==0)return new byte[0];ByteBuffer bytes=StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));byte[] value=new byte[bytes.remaining()];bytes.get(value);return value;}
    private static IllegalStateException friendly(JSchException error,SshConfig config){
        String message=error.getMessage()==null?"":error.getMessage();String lower=message.toLowerCase(java.util.Locale.ROOT);
        if(lower.contains("auth fail")||lower.contains("authentication"))message="El servidor SSH rechazó el usuario, la contraseña o la clave privada.";
        else if(lower.contains("unknownhost")||lower.contains("timeout")||lower.contains("refused"))message="No se pudo contactar el servidor SSH "+config.host()+":"+config.port()+".";
        else if(lower.contains("hostkey")||lower.contains("reject hostkey"))message="La identidad del servidor SSH no coincide con el archivo known_hosts.";
        else message="No se pudo abrir el túnel SSH. "+message;
        return new IllegalStateException(message,error);
    }
    public int localPort(){return localPort;}
    public boolean isConnected(){return session.isConnected();}
    @Override public void close(){session.disconnect();}
}
