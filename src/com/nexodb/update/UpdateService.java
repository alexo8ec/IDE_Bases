package com.nexodb.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateService {
    public static final String CURRENT_VERSION = "0.10.4";
    private static final Pattern JSON_STRING = Pattern.compile("\\\"%s\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static volatile String sessionManifestUrl = "";

    public record UpdateInfo(String version, String downloadUrl, String sha256, String notes) {}

    public String getManifestUrl() {
        String configured = sessionManifestUrl;
        try {
            configured = settings().get("manifestUrl", configured).trim();
        } catch (SecurityException ignored) {
            // Algunas políticas corporativas bloquean el registro de Java.
            // La aplicación debe abrir igualmente y conservar el valor en esta sesión.
        }
        if (!configured.isBlank()) return configured;
        String systemValue = System.getProperty("nexodb.updateManifest", "").trim();
        if (!systemValue.isBlank()) return systemValue;
        return System.getenv().getOrDefault("NEXODB_UPDATE_URL", "").trim();
    }

    public void setManifestUrl(String url) {
        String normalized = url == null ? "" : url.trim();
        if (!normalized.isBlank()) validateRemoteUri(URI.create(normalized), "El canal de actualizaciones");
        sessionManifestUrl = normalized;
        try {
            settings().put("manifestUrl", normalized);
        } catch (SecurityException ignored) {
            // Se mantiene para la sesión actual cuando Windows niega acceso al registro.
        }
    }

    public UpdateInfo check() throws Exception {
        String manifestUrl = getManifestUrl();
        if (manifestUrl.isBlank()) {
            throw new IllegalStateException("Todavía no se ha configurado el canal de actualizaciones.");
        }
        URI uri = URI.create(manifestUrl);
        String json;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            json = Files.readString(Path.of(uri), StandardCharsets.UTF_8);
        } else {
            validateRemoteUri(uri, "El canal de actualizaciones");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", "NexoDB-Studio/" + CURRENT_VERSION)
                    .GET()
                    .build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("El servidor de actualizaciones respondió HTTP " + response.statusCode() + ".");
            }
            json = response.body();
        }
        return parseManifest(json);
    }

    public Path download(UpdateInfo update, IntConsumer progress) throws Exception {
        Objects.requireNonNull(update, "update");
        if (update.downloadUrl().isBlank()) throw new IllegalArgumentException("La actualización no contiene una URL de descarga.");
        if (update.sha256().isBlank()) throw new IllegalArgumentException("La actualización no contiene una firma SHA-256.");
        URI uri = URI.create(update.downloadUrl());
        Path destination = Files.createTempFile("nexodb-update-" + update.version() + "-", ".zip");
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Files.copy(Path.of(uri), destination, StandardCopyOption.REPLACE_EXISTING);
                progress.accept(100);
            } else {
                validateRemoteUri(uri, "La descarga");
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "NexoDB-Studio/" + CURRENT_VERSION)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("La descarga respondió HTTP " + response.statusCode() + ".");
                }
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                try (InputStream input = response.body(); var output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[64 * 1024];
                    long copied = 0;
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                        copied += read;
                        if (total > 0) progress.accept((int) Math.min(99, copied * 100 / total));
                    }
                }
                progress.accept(100);
            }
            String actual = sha256(destination);
            if (!actual.equalsIgnoreCase(update.sha256())) {
                throw new SecurityException("La firma SHA-256 de la actualización no coincide. El archivo fue descartado.");
            }
            return destination;
        } catch (Exception error) {
            Files.deleteIfExists(destination);
            throw error;
        }
    }

    public void launchInstaller(Path zip) throws IOException {
        String executableProperty = System.getProperty("jpackage.app-path", "").trim();
        if (executableProperty.isBlank()) {
            throw new IllegalStateException("La actualización automática solo funciona desde la aplicación portable instalada.");
        }
        Path executable = Path.of(executableProperty).toAbsolutePath().normalize();
        Path installDirectory = executable.getParent();
        if (installDirectory == null || !Files.isRegularFile(executable)) {
            throw new IllegalStateException("No se pudo localizar la instalación de NexoDB Studio.");
        }

        Path script = Files.createTempFile("nexodb-updater-", ".ps1");
        Files.writeString(script, updaterScript(), StandardCharsets.UTF_8);
        new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.toString(),
                Long.toString(ProcessHandle.current().pid()),
                zip.toAbsolutePath().toString(),
                installDirectory.toString(),
                executable.toString()
        ).start();
    }

    public static UpdateInfo parseManifest(String json) {
        String version = jsonValue(json, "version", true);
        String downloadUrl = jsonValue(json, "downloadUrl", false);
        String sha256 = jsonValue(json, "sha256", false).toLowerCase(Locale.ROOT);
        String notes = jsonValue(json, "notes", false);
        if (!sha256.isBlank() && !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("El SHA-256 del manifiesto no es válido.");
        }
        return new UpdateInfo(version, downloadUrl, sha256, notes);
    }

    public static boolean isNewer(String candidate, String current) {
        int[] left = versionParts(candidate);
        int[] right = versionParts(current);
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            int a = index < left.length ? left[index] : 0;
            int b = index < right.length ? right[index] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] versionParts(String version) {
        String clean = version == null ? "" : version.trim().replaceFirst("^[vV]", "");
        String[] pieces = clean.split("[-+]", 2)[0].split("\\.");
        int[] values = new int[pieces.length];
        try {
            for (int index = 0; index < pieces.length; index++) values[index] = Integer.parseInt(pieces[index]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Versión no válida: " + version, error);
        }
        return values;
    }

    private static String jsonValue(String json, String key, boolean required) {
        Matcher matcher = Pattern.compile(String.format(JSON_STRING.pattern(), Pattern.quote(key))).matcher(json);
        if (!matcher.find()) {
            if (required) throw new IllegalArgumentException("El manifiesto no contiene “" + key + "”.");
            return "";
        }
        return matcher.group(1)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static void validateRemoteUri(URI uri, String label) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(label + " debe usar HTTPS.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(label + " no contiene un servidor válido.");
        }
    }

    private static Preferences settings() {
        return Preferences.userRoot().node("com/nexodb/studio/updater");
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String updaterScript() {
        return """
                param(
                    [Parameter(Mandatory=$true)][long]$ProcessIdToWait,
                    [Parameter(Mandatory=$true)][string]$ZipPath,
                    [Parameter(Mandatory=$true)][string]$InstallDirectory,
                    [Parameter(Mandatory=$true)][string]$ExecutablePath
                )
                $ErrorActionPreference = 'Stop'
                Wait-Process -Id $ProcessIdToWait -ErrorAction SilentlyContinue
                $stage = Join-Path ([System.IO.Path]::GetTempPath()) ('NexoDB-update-' + [guid]::NewGuid().ToString('N'))
                New-Item -ItemType Directory -Path $stage -Force | Out-Null
                try {
                    Expand-Archive -LiteralPath $ZipPath -DestinationPath $stage -Force
                    Copy-Item -Path (Join-Path $stage '*') -Destination $InstallDirectory -Recurse -Force
                    Start-Process -FilePath $ExecutablePath
                }
                finally {
                    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
                    Remove-Item -LiteralPath $ZipPath -Force -ErrorAction SilentlyContinue
                    Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue
                }
                """;
    }
}

