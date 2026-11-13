package com.nexodb.util;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** HTTPS with certificate validation in every transport, including native TLS. */
public final class SecureDownload {
    public static final class HttpStatusException extends IOException {
        private final int status;
        public HttpStatusException(int status) { super("El servidor respondió HTTP " + status + "."); this.status = status; }
        public int status() { return status; }
    }
    private SecureDownload() {}

    public static void fetch(URI uri, Path destination, long maxBytes, Duration timeout,
                             IntConsumer progress) throws Exception {
        validate(uri);
        Exception failure;
        try {
            javaDownload(uri, destination, maxBytes, timeout, progress, false);
            return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(destination);
            throw interrupted;
        } catch (Exception first) {
            failure = first;
        }
        if (isTlsFailure(failure)) {
            try {
                javaDownload(uri, destination, maxBytes, timeout, progress, true);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Files.deleteIfExists(destination);
                throw interrupted;
            } catch (Exception retry) {
                retry.addSuppressed(failure);
                failure = retry;
            }
        }
        // Native curl uses the OS certificate store, useful on networks with HTTPS inspection.
        if (isTlsFailure(failure)) {
            try {
                nativeDownload(uri, destination, maxBytes, timeout);
                report(progress, 100);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Files.deleteIfExists(destination);
                throw interrupted;
            } catch (Exception nativeFailure) {
                if (nativeFailure instanceof HttpStatusException) {
                    nativeFailure.addSuppressed(failure);
                    failure = nativeFailure;
                } else failure.addSuppressed(nativeFailure);
            }
        }
        Files.deleteIfExists(destination);
        throw failure;
    }

    private static void javaDownload(URI uri, Path destination, long maxBytes, Duration timeout,
                                     IntConsumer progress, boolean tls12) throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (tls12) {
            SSLParameters parameters = new SSLParameters();
            parameters.setProtocols(new String[]{"TLSv1.2"});
            builder.sslParameters(parameters);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "NexoDB-Studio/" + com.nexodb.update.UpdateService.CURRENT_VERSION)
                .header("Accept", "*/*").GET().build();
        HttpResponse<InputStream> response = builder.build().send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HttpStatusException(response.statusCode());
            }
            long size = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (size > maxBytes) throw new IOException("La descarga supera el tamaño permitido.");
            long deadline = System.nanoTime() + timeout.toNanos();
            // Closing the stream also interrupts a stalled body after the request headers arrived.
            var watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "nexodb-download-timeout"); thread.setDaemon(true); return thread;
            });
            watchdog.schedule(() -> { try { input.close(); } catch (IOException ignored) {} }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            try (var output = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[65536];
                long copied = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (System.nanoTime() > deadline) throw new IOException("Se agotó el tiempo de descarga.");
                    copied += count;
                    if (copied > maxBytes) throw new IOException("La descarga supera el tamaño permitido.");
                    output.write(buffer, 0, count);
                    if (size > 0) report(progress, (int) Math.min(99, copied * 100 / size));
                }
                if (System.nanoTime() > deadline) throw new IOException("Se agotó el tiempo de descarga.");
                if (size >= 0 && copied != size) throw new IOException("La descarga está incompleta.");
            } finally { watchdog.shutdownNow(); }
        } catch (Exception error) {
            Files.deleteIfExists(destination);
            throw error;
        }
        report(progress, 100);
    }

    private static void nativeDownload(URI uri, Path destination, long maxBytes, Duration timeout) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String curl = os.startsWith("windows")
                ? Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "curl.exe").toString()
                : os.contains("mac") ? "/usr/bin/curl" : "curl";
        Path errors = Files.createTempFile("nexodb-https-", ".log");
        Path status = Files.createTempFile("nexodb-https-status-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(curl, "--fail", "--location", "--silent", "--show-error",
                    "--proto", "=https", "--proto-redir", "=https", "--connect-timeout", "12",
                    "--max-time", Long.toString(Math.max(1, timeout.toSeconds())),
                    "--max-filesize", Long.toString(maxBytes), "--write-out", "%{http_code}",
                    "--output", destination.toAbsolutePath().toString(), uri.toString())
                    .redirectOutput(status.toFile()).redirectError(errors.toFile()).start();
            if (!process.waitFor(timeout.toSeconds() + 5, TimeUnit.SECONDS)) {
                throw new IOException("Se agotó el tiempo de descarga HTTPS del sistema.");
            }
            String httpStatus = Files.readString(status).trim();
            if (httpStatus.matches("[45][0-9]{2}")) throw new HttpStatusException(Integer.parseInt(httpStatus));
            if (process.exitValue() != 0 || !Files.isRegularFile(destination)) {
                throw new IOException("La conexión HTTPS del sistema no pudo completar la descarga (código " + process.exitValue() + ").");
            }
            if (Files.size(destination) > maxBytes) throw new IOException("La descarga supera el tamaño permitido.");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(errors);
            Files.deleteIfExists(status);
        }
    }

    public static void validate(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("La dirección de descarga debe usar HTTPS y contener un servidor válido.");
        }
    }

    public static boolean isTlsFailure(Throwable error) {
        if (error == null) return false;
        if (error instanceof SSLHandshakeException) return true;
        String message = error.getMessage();
        if (message != null && (message.toLowerCase(Locale.ROOT).contains("handshake")
                || message.contains("PKIX") || message.toLowerCase(Locale.ROOT).contains("certificate"))) return true;
        return isTlsFailure(error.getCause());
    }

    private static void report(IntConsumer progress, int percent) { if (progress != null) progress.accept(percent); }
}
