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
        if(System.getProperty("os.name","").startsWith("Windows"))verifyRebrandUpdater();
        System.out.println("UpdateServiceTest: OK (legacy/new manifests, hashes and NexoDB-to-AdisDB updater compatibility)");
    }
    private static void verifyRebrandUpdater()throws Exception{
        Path root=Files.createTempDirectory("adisdb-updater-migration-").toAbsolutePath();Path install=Files.createDirectory(root.resolve("installed"));Path temporary=Files.createDirectory(root.resolve("temp"));Path old=install.resolve("NexoDB Studio.exe");Files.writeString(old,"legacy-launcher");
        var method=UpdateService.class.getDeclaredMethod("updaterScript");method.setAccessible(true);String script=(String)method.invoke(null);String stub="function Start-Process { param([string]$FilePath) [IO.File]::WriteAllText($env:ADISDB_TEST_LAUNCH_LOG,$FilePath) }\n";
        // Run real extraction/copy/cleanup, but record launch paths instead of starting any application.
        script=script.replace("$ErrorActionPreference = 'Stop'",stub+"$ErrorActionPreference = 'Stop'");
        for(boolean branded:List.of(true,false)){
            Files.deleteIfExists(install.resolve("AdisDB.exe"));Path archive=root.resolve("update.zip"),log=root.resolve("launch.txt"),file=root.resolve("apply.ps1");
            try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))){zip.putNextEntry(new java.util.zip.ZipEntry(branded?"AdisDB.exe":"NexoDB Studio.exe"));zip.write("new-launcher".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();zip.putNextEntry(new java.util.zip.ZipEntry("app/nexodb-studio.jar"));zip.write("new-app".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
            Files.writeString(file,script);ProcessBuilder builder=new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",file.toString(),"2147483647",archive.toString(),install.toString(),old.toString()).redirectErrorStream(true);builder.environment().put("TEMP",temporary.toString());builder.environment().put("TMP",temporary.toString());builder.environment().put("ADISDB_TEST_LAUNCH_LOG",log.toString());Process process=builder.start();if(!process.waitFor(40,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Updater migration timed out");}String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(process.exitValue()!=0)throw new AssertionError(output);
            equal((branded?install.resolve("AdisDB.exe"):old).toString(),Files.readString(log));equal("new-app",Files.readString(install.resolve("app/nexodb-studio.jar")));equal(false,Files.exists(archive));equal(false,Files.exists(file));try(var stages=Files.list(temporary)){if(stages.anyMatch(p->p.getFileName().toString().startsWith("AdisDB-update-")))throw new AssertionError("Updater stage left behind");}
        }
        // Only remove paths created under this verified, isolated temporary fixture.
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(!path.toAbsolutePath().startsWith(root))throw new AssertionError("Unsafe test cleanup");Files.delete(path);}}
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
