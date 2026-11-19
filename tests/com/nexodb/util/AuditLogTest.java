package com.nexodb.util;

import java.nio.file.*;

public final class AuditLogTest {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("adisdb-audit-");System.setProperty("nexodb.settingsFile",root.resolve("settings.properties").toString());System.clearProperty("nexodb.disableDraftPersistence");
        AuditLog audit=AuditLog.configured();audit.clear();String secret="UPDATE users SET password='VerySecret' WHERE token='abc'";audit.record(AuditLog.sqlAction(secret),"Producción","sistema","OK",secret);
        if(audit.entries().size()!=1)throw new AssertionError("Audit entry not stored");String raw=Files.readString(root.resolve("audit-log.properties"));if(raw.contains("VerySecret")||raw.contains("UPDATE users")||raw.contains("token='abc'"))throw new AssertionError("Sensitive data leaked into audit");if(audit.entries().get(0).fingerprint().length()!=16)throw new AssertionError("Missing irreversible fingerprint");
        System.out.println("AuditLogTest: OK (safe local administrative trail without SQL or values)");
    }
}
