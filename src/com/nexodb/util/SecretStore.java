package com.nexodb.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

/** Stores connection passwords encrypted with Windows DPAPI for the current user. */
public final class SecretStore {
    public static boolean isSupported() { return System.getProperty("os.name", "").startsWith("Windows"); }
    private static final String ENTROPY = Base64.getEncoder().encodeToString(
            "NexoDB Studio credentials v1".getBytes(StandardCharsets.UTF_8));
    private static final String PROTECT =
            "Add-Type -AssemblyName System.Security;$in=[Console]::OpenStandardInput();$m=New-Object IO.MemoryStream;$in.CopyTo($m);"
            + "$e=[Convert]::FromBase64String('" + ENTROPY + "');"
            + "$p=[Security.Cryptography.ProtectedData]::Protect($m.ToArray(),$e,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "$o=[Console]::OpenStandardOutput();$o.Write($p,0,$p.Length)";
    private static final String UNPROTECT =
            "Add-Type -AssemblyName System.Security;$in=[Console]::OpenStandardInput();$m=New-Object IO.MemoryStream;$in.CopyTo($m);"
            + "$e=[Convert]::FromBase64String('" + ENTROPY + "');"
            + "$p=[Security.Cryptography.ProtectedData]::Unprotect($m.ToArray(),$e,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "$o=[Console]::OpenStandardOutput();$o.Write($p,0,$p.Length)";

    private final Path file;

    public SecretStore() {
        this(defaultFile());
    }

    public SecretStore(Path file) {
        this.file = file;
    }

    private static Path defaultFile() {
        String appData = System.getenv("APPDATA");
        Path base = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".nexodb-studio")
                : Path.of(appData, "NexoDB Studio");
        return base.resolve("secrets.dat");
    }

    public synchronized boolean has(String profileId) {
        if (!isSupported()) return false;
        if (profileId == null || profileId.isBlank()) return false;
        try { return read().containsKey(profileId); }
        catch (IOException ignored) { return false; }
    }

    public synchronized void save(String profileId, char[] password) throws IOException, InterruptedException {
        if (!isSupported()) throw new IOException("El guardado seguro de contraseñas requiere Windows DPAPI. Puedes conectarte escribiendo la contraseña.");
        if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("El perfil no tiene identificador.");
        if (password == null) password = new char[0];
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] plain = new byte[encoded.remaining()];
        encoded.get(plain);
        try {
            byte[] protectedBytes = runDpapi(PROTECT, plain);
            try {
                Properties values = read();
                values.setProperty(profileId, Base64.getEncoder().encodeToString(protectedBytes));
                write(values);
            } finally {
                Arrays.fill(protectedBytes, (byte) 0);
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public synchronized char[] load(String profileId) throws IOException, InterruptedException {
        if (!isSupported()) return null;
        String stored = profileId == null ? null : read().getProperty(profileId);
        if (stored == null) return null;
        byte[] protectedBytes = Base64.getDecoder().decode(stored);
        byte[] plain = null;
        try {
            plain = runDpapi(UNPROTECT, protectedBytes);
            CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
            char[] secret = new char[decoded.remaining()];
            decoded.get(secret);
            return secret;
        } finally {
            Arrays.fill(protectedBytes, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    public synchronized void delete(String profileId) {
        if (profileId == null || profileId.isBlank()) return;
        try {
            Properties values = read();
            if (values.remove(profileId) != null) write(values);
        } catch (IOException | SecurityException ignored) {
        }
    }

    private Properties read() throws IOException {
        Properties values = new Properties();
        if (!Files.isRegularFile(file)) return values;
        try (var input = Files.newInputStream(file)) { values.load(input); }
        return values;
    }

    private void write(Properties values) throws IOException {
        Files.createDirectories(file.getParent());
        try (var output = Files.newOutputStream(file)) {
            values.store(output, "NexoDB Studio - DPAPI encrypted credentials");
        }
    }

    private static byte[] runDpapi(String script, byte[] input) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-Command", script).start();
        try (var output = process.getOutputStream()) {
            output.write(input);
        }
        byte[] result;
        try (var stdout = process.getInputStream(); var buffer = new ByteArrayOutputStream()) {
            stdout.transferTo(buffer);
            result = buffer.toByteArray();
        }
        String error;
        try (var stderr = process.getErrorStream()) {
            error = new String(stderr.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int exit = process.waitFor();
        if (exit != 0) {
            Arrays.fill(result, (byte) 0);
            throw new IOException(error.isBlank() ? "Windows no pudo proteger la contraseña." : error);
        }
        return result;
    }
}
