package com.nexodb.db;

import com.nexodb.model.*;

public final class SshTunnelTest {
    public static void main(String[] args)throws Exception{
        Class.forName("com.jcraft.jsch.JSch");
        SshConfig ssh=new SshConfig(true,"bastion.example",2222,"deploy",SshConfig.Authentication.PRIVATE_KEY,"C:/keys/id_ed25519","C:/keys/known_hosts",true,0,12,25);
        ConnectionProfile mysql=new ConnectionProfile("id","Producción",DatabaseType.MYSQL,"db.internal",3306,"ventas","app","jdbc:mysql://db.internal:3306/ventas?sslMode=REQUIRED",DatabaseType.MYSQL.driverClass(),"",AuthenticationMode.DATABASE,ssh,true);
        ConnectionProfile forwarded=SshTunnel.forwardedProfile(mysql,49152);
        equal("127.0.0.1",forwarded.host());equal(49152,forwarded.port());equal("jdbc:mysql://127.0.0.1:49152/ventas?sslMode=REQUIRED",forwarded.jdbcUrl());equal(ssh,forwarded.ssh());
        ConnectionProfile oracle=new ConnectionProfile("","Oracle",DatabaseType.ORACLE,"oracle.internal",1521,"XEPDB1","system","jdbc:oracle:thin:@//oracle.internal:1521/XEPDB1",DatabaseType.ORACLE.driverClass(),"",AuthenticationMode.DATABASE,ssh,false);
        equal("jdbc:oracle:thin:@//127.0.0.1:49153/XEPDB1",SshTunnel.forwardedProfile(oracle,49153).jdbcUrl());
        equal("id:ssh",SshConfig.secretId("id"));equal(22,SshConfig.disabled().port());
        try{ConnectionProfile generic=new ConnectionProfile("","Generic",DatabaseType.GENERIC,"server",1234,"","","jdbc:custom:value","Driver","",AuthenticationMode.DATABASE,ssh,false);SshTunnel.forwardedProfile(generic,49154);throw new AssertionError("Unrewritable JDBC URL accepted");}catch(IllegalArgumentException expected){}
        System.out.println("SshTunnelTest: OK (bundled JSch, endpoint forwarding, Oracle, secure settings and invalid URLs)");
    }
    private static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
