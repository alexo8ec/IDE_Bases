package com.nexodb.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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

public final class UpdateService {
    public static final String CURRENT_VERSION = "0.13.1";
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
        return System.getenv().getOrDefault("NEXODB_UPDATE_URL",
                "https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist/update-manifest.json").trim();
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
            Path manifest = Files.createTempFile("nexodb-manifest-", ".json");
            try {
                com.nexodb.util.SecureDownload.fetch(uri, manifest, 1024 * 1024, Duration.ofSeconds(25), null);
                json = Files.readString(manifest, StandardCharsets.UTF_8);
            } finally { Files.deleteIfExists(manifest); }
        }
        return parseManifest(json);
    }

    public Path download(UpdateInfo update, IntConsumer progress) throws Exception {
        Objects.requireNonNull(update, "update");
        if (update.downloadUrl().isBlank()) throw new IllegalArgumentException("La actualización no contiene una URL de descarga.");
        if (update.sha256().isBlank()) throw new IllegalArgumentException("La actualización no contiene una firma SHA-256.");
        URI uri = URI.create(update.downloadUrl());
        Path destination = Files.createTempFile("nexodb-update-", packageSuffix(uri));
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Files.copy(Path.of(uri), destination, StandardCopyOption.REPLACE_EXISTING);
                if (progress != null) progress.accept(100);
            } else {
                validateRemoteUri(uri, "La descarga");
                com.nexodb.util.SecureDownload.fetch(uri, destination, 2L * 1024 * 1024 * 1024,
                        Duration.ofMinutes(5), progress);
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
        if (!Files.isRegularFile(zip)) throw new IOException("No se encontró el paquete descargado.");
        if (!canInstallInPlace()) {
            if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                throw new IOException("Abre el paquete descargado con el instalador de tu sistema: " + zip);
            }
            java.awt.Desktop.getDesktop().open(zip.toFile());
            return;
        }
        if (!zip.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            java.awt.Desktop.getDesktop().open(zip.toFile());
            return;
        }
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

    public static String platformKey() {
        return platformKey(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static String platformKey(String os, String arch) {
        String name = os.toLowerCase(Locale.ROOT);
        String platform = name.startsWith("windows") ? "windows" : name.contains("mac") ? "macos"
                : name.contains("linux") ? "linux" : "unknown";
        String cpu = arch.toLowerCase(Locale.ROOT);
        String architecture = cpu.equals("aarch64") || cpu.equals("arm64") ? "arm64"
                : cpu.equals("amd64") || cpu.equals("x86_64") ? "x64" : cpu;
        return platform + "-" + architecture;
    }

    public static UpdateInfo parseManifest(String json) { return parseManifest(json, platformKey()); }

    public static UpdateInfo parseManifest(String json, String platform) {
        var root = com.nexodb.util.Json.object(json);
        String version = string(root, "version");
        if (!version.matches("[vV]?[0-9]+(?:\\.[0-9]+){1,3}")) throw new IllegalArgumentException("La versión del manifiesto no es válida.");
        java.util.Map<?, ?> asset = root;
        if (root.get("platforms") instanceof java.util.Map<?, ?> platforms) {
            if (!(platforms.get(platform) instanceof java.util.Map<?, ?> selected)) {
                throw new IllegalStateException("Esta versión todavía no tiene un paquete para " + platform + ".");
            }
            asset = selected;
        } else if (!platform.startsWith("windows-")) {
            throw new IllegalStateException("El canal solo contiene un paquete de Windows. Aún no hay una actualización para " + platform + ".");
        }
        String downloadUrl = string(asset, "downloadUrl");
        String sha256 = string(asset, "sha256").toLowerCase(Locale.ROOT);
        if (!sha256.isBlank() && !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("El SHA-256 del manifiesto no es válido.");
        if (!downloadUrl.isBlank()) {
            URI uri = URI.create(downloadUrl);
            if (!"file".equalsIgnoreCase(uri.getScheme())) com.nexodb.util.SecureDownload.validate(uri);
        }
        return new UpdateInfo(version, downloadUrl, sha256, string(root, "notes"));
    }

    private static String string(java.util.Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (value == null) return "";
        if (!(value instanceof String text)) throw new IllegalArgumentException("El campo " + key + " debe ser texto.");
        return text;
    }

    private static String packageSuffix(URI uri) {
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".zip", ".dmg", ".pkg", ".deb", ".rpm", ".tar.gz"}) {
            if (path.endsWith(suffix)) return suffix;
        }
        throw new IllegalArgumentException("El formato del paquete de actualización no está admitido.");
    }

    public static boolean canInstallInPlace() {
        return platformKey().startsWith("windows-") && !System.getProperty("jpackage.app-path", "").isBlank();
    }

    public static String explainError(Throwable error) {
        while ((error instanceof java.util.concurrent.ExecutionException || error instanceof java.util.concurrent.CompletionException)
                && error.getCause() != null) error = error.getCause();
        if (error instanceof com.nexodb.util.SecureDownload.HttpStatusException http) {
            if (http.status() == 404) return com.nexodb.util.I18n.tr("El canal de actualizaciones no está disponible (HTTP 404). El manifiesto debe estar publicado y ser accesible sin iniciar sesión. Revisa la dirección en Ayuda → Canal de actualizaciones.");
            if (http.status() == 403 || http.status() == 429) return com.nexodb.util.I18n.tr("El servidor de actualizaciones ha limitado el acceso. Inténtalo más tarde o revisa el canal configurado.");
            return com.nexodb.util.I18n.tr("El servidor de actualizaciones respondió HTTP %s. Inténtalo más tarde.", http.status());
        }
        if (com.nexodb.util.SecureDownload.isTlsFailure(error)) {
            return com.nexodb.util.I18n.tr("No se pudo establecer una conexión HTTPS segura para buscar actualizaciones.\nSe intentaron Java, TLS 1.2 y la conexión del sistema. Revisa el proxy o los certificados de tu red e inténtalo de nuevo.");
        }
        if (error instanceof java.net.ConnectException || error instanceof java.net.UnknownHostException
                || error instanceof java.net.http.HttpTimeoutException) {
            return com.nexodb.util.I18n.tr("No se pudo contactar con el servidor de actualizaciones. Revisa tu conexión a Internet e inténtalo de nuevo.");
        }
        return error.getMessage() == null ? error.toString() : com.nexodb.util.I18n.tr(error.getMessage());
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

