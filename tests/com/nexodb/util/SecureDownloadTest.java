package com.nexodb.util;

import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real local TLS, including refusal of an untrusted certificate. No Internet needed. */
public final class SecureDownloadTest {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("nexodb-tls-test-");
        Path keystore = folder.resolve("local.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process certificate = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "test-only-123",
                "-keypass", "test-only-123", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!certificate.waitFor(20, TimeUnit.SECONDS)) { certificate.destroyForcibly(); throw new AssertionError("Local test certificate timed out"); }
        if (certificate.exitValue() != 0) throw new AssertionError("Cannot create local TLS test certificate");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keystore)) { keys.load(input, "test-only-123".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "test-only-123".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        SSLContext local = SSLContext.getInstance("TLS");
        local.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
        SSLContext original = SSLContext.getDefault();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(local));
        AtomicInteger missingRequests = new AtomicInteger();
        server.createContext("/ok", exchange -> {
            byte[] payload = "verified HTTPS payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) { output.write(payload); }
        });
        server.createContext("/missing", exchange -> {
            missingRequests.incrementAndGet(); exchange.sendResponseHeaders(404, -1); exchange.close();
        });
        server.start();
        Path download = folder.resolve("download.bin");
        URI base = URI.create("https://localhost:" + server.getAddress().getPort());
        try {
            // The native transport must not silently accept this self-signed certificate either.
            try {
                SecureDownload.fetch(base.resolve("/ok"), download, 4096, Duration.ofSeconds(3), null);
                throw new AssertionError("Untrusted TLS certificate was accepted");
            } catch (Exception expected) {
                if (Files.exists(download)) throw new AssertionError("Failed TLS download was left on disk");
            }
            SSLContext.setDefault(local);
            SecureDownload.fetch(base.resolve("/ok"), download, 4096, Duration.ofSeconds(5), null);
            if (!Files.readString(download).equals("verified HTTPS payload")) throw new AssertionError("TLS payload changed");
            Files.delete(download);
            try {
                SecureDownload.fetch(base.resolve("/missing"), download, 4096, Duration.ofSeconds(5), null);
                throw new AssertionError("HTTP 404 was accepted");
            } catch (SecureDownload.HttpStatusException expected) {
                if (expected.status() != 404 || missingRequests.get() != 1) throw new AssertionError("HTTP errors must not trigger TLS retries");
                if (Files.exists(download)) throw new AssertionError("Failed HTTP download was left on disk");
            }
            try {
                SecureDownload.fetch(base.resolve("/ok"), download, 8, Duration.ofSeconds(5), null);
                throw new AssertionError("Download size limit was ignored");
            } catch (java.io.IOException expected) {
                if (Files.exists(download)) throw new AssertionError("Oversized download was left on disk");
            }
        } finally {
            SSLContext.setDefault(original);
            server.stop(0);
            Files.deleteIfExists(download); Files.deleteIfExists(keystore); Files.delete(folder);
        }
        System.out.println("SecureDownloadTest: OK");
    }
}
