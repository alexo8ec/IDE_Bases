package com.nexodb.update;

import com.nexodb.util.Json;
import com.nexodb.util.SecureDownload;
import java.nio.file.*;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;

public final class UpdateServiceTest {
    public static void main(String[] args) throws Exception {
        equal(Files.readString(Path.of("VERSION")).trim(), UpdateService.CURRENT_VERSION);
        if (args.length > 0 && args[0].equals("--live-check")) {
            UpdateService.UpdateInfo info = new UpdateService().check();
            System.out.println("Live update channel: " + info.version() + " / " + UpdateService.platformKey());
            return;
        }
        equal("windows-x64", UpdateService.platformKey("Windows 11", "amd64"));
        equal("macos-arm64", UpdateService.platformKey("Mac OS X", "aarch64"));
        equal("linux-x64", UpdateService.platformKey("Linux", "x86_64"));
        equal("linux-arm64", UpdateService.platformKey("Linux", "arm64"));
        String checksum = "a".repeat(64);
        String manifest = """
            {"version":"1.2.3","notes":"English\\nEspa\\u00f1ol","platforms":{
              "windows-x64":{"downloadUrl":"https://example.com/win.zip","sha256":"%s"},
              "linux-x64":{"downloadUrl":"https://example.com/linux.deb","sha256":"%s"},
              "macos-arm64":{"downloadUrl":"https://example.com/mac.dmg","sha256":"%s"}}}
            """.formatted(checksum, "b".repeat(64), "c".repeat(64));
        equal("https://example.com/linux.deb", UpdateService.parseManifest(manifest, "linux-x64").downloadUrl());
        equal("c".repeat(64), UpdateService.parseManifest(manifest, "macos-arm64").sha256());
        equal("English\nEspañol", UpdateService.parseManifest(manifest, "windows-x64").notes());
        rejects(() -> UpdateService.parseManifest(manifest, "macos-x64"));
        String legacy = "{\"version\":\"1.0.0\",\"downloadUrl\":\"https://example.com/win.zip\",\"sha256\":\"" + checksum + "\"}";
        equal(checksum, UpdateService.parseManifest(legacy, "windows-x64").sha256());
        rejects(() -> UpdateService.parseManifest(legacy, "linux-x64"));
        rejects(() -> UpdateService.parseManifest(legacy.replace(checksum, "bad"), "windows-x64"));
        rejects(() -> UpdateService.parseManifest(legacy.replace("1.0.0", "../bad"), "windows-x64"));
        rejects(() -> UpdateService.parseManifest(legacy.replace("https:", "http:"), "windows-x64"));
        rejects(() -> Json.object("{\"version\":\"1\",\"version\":\"2\"}"));
        rejects(() -> Json.object("{\"version\":\"1\",}"));
        rejects(() -> Json.object("{} trailing"));
        rejects(() -> SecureDownload.validate(URI.create("http://example.com/file")));
        rejects(() -> SecureDownload.validate(URI.create("https://user:password@example.com/file")));
        String message = UpdateService.explainError(new javax.net.ssl.SSLHandshakeException("handshake_failure"));
        if (message.contains("Received fatal alert") || !message.contains("HTTPS")) throw new AssertionError(message);
        if (!UpdateService.explainError(new SecureDownload.HttpStatusException(404)).contains("404")) throw new AssertionError("Missing channel must have a useful explanation");

        Path source = Files.createTempFile("nexodb-test-update-", ".zip");
        try {
            Files.writeString(source, "verified update payload");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            UpdateService service = new UpdateService();
            Path downloaded = service.download(new UpdateService.UpdateInfo("1.2.3", source.toUri().toString(), hash, ""), null);
            try { equal(Files.readString(source), Files.readString(downloaded)); }
            finally { Files.delete(downloaded); }
            Set<Path> before = tempUpdates();
            rejects(() -> service.download(new UpdateService.UpdateInfo("1.2.3", source.toUri().toString(), "0".repeat(64), ""), null));
            equal(before, tempUpdates());
        } finally { Files.deleteIfExists(source); }
        System.out.println("UpdateServiceTest: OK");
    }

    private static Set<Path> tempUpdates() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return new HashSet<>(paths.filter(p -> p.getFileName().toString().startsWith("nexodb-update-")).toList());
        }
    }
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Invalid update was accepted");
    }
}
