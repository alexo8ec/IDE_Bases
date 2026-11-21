package com.nexodb.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Stores credentials in the operating system's secure store. */
public final class SecretStore {
    enum Backend { WINDOWS_DPAPI, MACOS_KEYCHAIN, LINUX_SECRET_SERVICE, NONE }

    interface CommandRunner {
        CommandResult run(List<String> command, byte[] input) throws IOException, InterruptedException;
    }

    static final class CommandResult {
        final int exitCode;
        final byte[] output;
        final String error;

        CommandResult(int exitCode, byte[] output, String error) {
            this.exitCode = exitCode;
            this.output = output == null ? new byte[0] : output;
            this.error = error == null ? "" : error;
        }
    }

    private static final String SERVICE = "com.nexodb.studio.credentials";
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
    private final Backend backend;
    private final CommandRunner runner;

    public SecretStore() {
        this(defaultFile(), detectBackend(), SecretStore::runCommand);
    }

    public SecretStore(Path file) {
        this(file, detectBackend(), SecretStore::runCommand);
    }

    SecretStore(Path file, Backend backend, CommandRunner runner) {
        this.file = file;
        this.backend = backend == null ? Backend.NONE : backend;
        this.runner = runner == null ? SecretStore::runCommand : runner;
    }

    public static boolean isSupported() { return detectBackend() != Backend.NONE; }
    public static String backendName() { return backendName(detectBackend()); }
    public boolean supported() { return backend != Backend.NONE; }

    private static String backendName(Backend value) {
        switch (value) {
            case WINDOWS_DPAPI: return "Windows DPAPI";
            case MACOS_KEYCHAIN: return "Llavero de macOS";
            case LINUX_SECRET_SERVICE: return "Secret Service de Linux";
            default: return "no disponible";
        }
    }

    static Backend detectBackend() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) return Backend.WINDOWS_DPAPI;
        if (os.startsWith("mac") && Files.isExecutable(Path.of("/usr/bin/security"))) return Backend.MACOS_KEYCHAIN;
        if (os.contains("linux") && executableOnPath("secret-tool")) return Backend.LINUX_SECRET_SERVICE;
        return Backend.NONE;
    }

    private static boolean executableOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return false;
        for (String folder : path.split(java.io.File.pathSeparator)) {
            if (folder.isBlank()) continue;
            try {
                if (Files.isExecutable(Path.of(folder, command))) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    private static Path defaultFile() {
        String appData = System.getenv("APPDATA");
        Path base = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".nexodb-studio")
                : Path.of(appData, "NexoDB Studio");
        return base.resolve("secrets.dat");
    }

    public synchronized boolean has(String profileId) {
        if (!supported() || profileId == null || profileId.isBlank()) return false;
        try {
            if (backend == Backend.WINDOWS_DPAPI) return read().containsKey(profileId);
            char[] secret = load(profileId);
            if (secret == null) return false;
            Arrays.fill(secret, '\0');
            return true;
        } catch (IOException | InterruptedException ignored) {
            if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
            return false;
        }
    }

    public synchronized void save(String profileId, char[] password) throws IOException, InterruptedException {
        validateProfile(profileId);
        if (!supported()) {
            throw new IOException("No hay un almacén seguro disponible. Instala libsecret/secret-tool en Linux o usa el llavero del sistema.");
        }
        char[] value = password == null ? new char[0] : password;
        byte[] plain = encode(value);
        try {
            switch (backend) {
                case WINDOWS_DPAPI: saveWindows(profileId, plain); break;
                case MACOS_KEYCHAIN: saveMac(profileId, plain); break;
                case LINUX_SECRET_SERVICE: saveLinux(profileId, plain); break;
                default: throw new IOException("El almacén seguro no está disponible.");
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public synchronized char[] load(String profileId) throws IOException, InterruptedException {
        if (!supported() || profileId == null || profileId.isBlank()) return null;
        switch (backend) {
            case WINDOWS_DPAPI: return loadWindows(profileId);
            case MACOS_KEYCHAIN:
                return loadExternal(List.of("/usr/bin/security", "find-generic-password", "-a", profileId, "-s", SERVICE, "-w"));
            case LINUX_SECRET_SERVICE:
                return loadExternal(List.of("secret-tool", "lookup", "application", SERVICE, "profile", profileId));
            default: return null;
        }
    }

    public synchronized void delete(String profileId) {
        if (profileId == null || profileId.isBlank() || !supported()) return;
        try {
            switch (backend) {
                case WINDOWS_DPAPI:
                    Properties values = read();
                    if (values.remove(profileId) != null) write(values);
                    break;
                case MACOS_KEYCHAIN:
                    runner.run(List.of("/usr/bin/security", "delete-generic-password", "-a", profileId, "-s", SERVICE), new byte[0]);
                    break;
                case LINUX_SECRET_SERVICE:
                    runner.run(List.of("secret-tool", "clear", "application", SERVICE, "profile", profileId), new byte[0]);
                    break;
                default: break;
            }
        } catch (IOException | InterruptedException | SecurityException ignored) {
            if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
        }
    }

    private void saveWindows(String profileId, byte[] plain) throws IOException, InterruptedException {
        byte[] protectedBytes = runDpapi(PROTECT, plain);
        try {
            Properties values = read();
            values.setProperty(profileId, Base64.getEncoder().encodeToString(protectedBytes));
            write(values);
        } finally {
            Arrays.fill(protectedBytes, (byte) 0);
        }
    }

    private char[] loadWindows(String profileId) throws IOException, InterruptedException {
        String stored = read().getProperty(profileId);
        if (stored == null) return null;
        byte[] protectedBytes = Base64.getDecoder().decode(stored);
        byte[] plain = null;
        try {
            plain = runDpapi(UNPROTECT, protectedBytes);
            return decode(plain);
        } finally {
            Arrays.fill(protectedBytes, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private void saveMac(String profileId, byte[] plain) throws IOException, InterruptedException {
        if (plain.length > 128) throw new IOException("El llavero de macOS admite hasta 128 bytes mediante el ingreso seguro utilizado por AdisDB.");
        for (byte value : plain) {
            if (value == '\n' || value == '\r') throw new IOException("El llavero de macOS no admite saltos de línea en esta contraseña.");
        }
        byte[] input = Arrays.copyOf(plain, plain.length + 1);
        input[input.length - 1] = '\n';
        try {
            CommandResult result = runner.run(List.of("/usr/bin/security", "add-generic-password", "-U", "-a", profileId, "-s", SERVICE, "-w"), input);
            requireSuccess(result, "macOS no pudo guardar la contraseña en el llavero.");
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    private void saveLinux(String profileId, byte[] plain) throws IOException, InterruptedException {
        CommandResult result = runner.run(List.of("secret-tool", "store", "--label=AdisDB", "application", SERVICE, "profile", profileId), plain);
        requireSuccess(result, "Linux no pudo guardar la contraseña en Secret Service.");
    }

    private char[] loadExternal(List<String> command) throws IOException, InterruptedException {
        CommandResult result = runner.run(command, new byte[0]);
        if (result.exitCode != 0) {
            Arrays.fill(result.output, (byte) 0);
            return null;
        }
        byte[] clean = stripTrailingLineBreak(result.output);
        try {
            return decode(clean);
        } finally {
            Arrays.fill(clean, (byte) 0);
            if (clean != result.output) Arrays.fill(result.output, (byte) 0);
        }
    }

    private static byte[] stripTrailingLineBreak(byte[] source) {
        int end = source.length;
        if (end > 0 && source[end - 1] == '\n') end--;
        if (end > 0 && source[end - 1] == '\r') end--;
        return end == source.length ? source : Arrays.copyOf(source, end);
    }

    private static byte[] encode(char[] value) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        return result;
    }

    private static char[] decode(byte[] value) {
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(value));
        char[] result = new char[decoded.remaining()];
        decoded.get(result);
        if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
        return result;
    }

    private static void validateProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("El perfil no tiene identificador.");
        if (profileId.indexOf('\0') >= 0 || profileId.indexOf('\n') >= 0 || profileId.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("El identificador del perfil contiene caracteres no permitidos.");
        }
    }

    private Properties read() throws IOException {
        Properties values = new Properties();
        if (!Files.isRegularFile(file)) return values;
        try (var input = Files.newInputStream(file)) { values.load(input); }
        return values;
    }

    private void write(Properties values) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("La ruta del almacén seguro no tiene una carpeta válida.");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "secrets-", ".tmp");
        boolean moved = false;
        try {
            try (var output = Files.newOutputStream(temp)) {
                values.store(output, "NexoDB Studio - DPAPI encrypted credentials");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    private static byte[] runDpapi(String script, byte[] input) throws IOException, InterruptedException {
        CommandResult result = runCommand(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script), input);
        if (result.exitCode != 0) {
            Arrays.fill(result.output, (byte) 0);
            throw new IOException(result.error.isBlank() ? "Windows no pudo proteger la contraseña." : result.error);
        }
        return result.output;
    }

    private static CommandResult runCommand(List<String> command, byte[] input) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(new ArrayList<>(command)).start();
        try (var output = process.getOutputStream()) { output.write(input); }
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        Thread stdout = new Thread(() -> transfer(process.getInputStream(), stdoutBuffer), "adisdb-secret-stdout");
        Thread stderr = new Thread(() -> transfer(process.getErrorStream(), stderrBuffer), "adisdb-secret-stderr");
        stdout.setDaemon(true);
        stderr.setDaemon(true);
        stdout.start();
        stderr.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("El almacén seguro no respondió dentro del tiempo esperado.");
        }
        stdout.join(5_000);
        stderr.join(5_000);
        return new CommandResult(process.exitValue(), stdoutBuffer.toByteArray(), stderrBuffer.toString(StandardCharsets.UTF_8).trim());
    }

    private static void transfer(java.io.InputStream input, ByteArrayOutputStream output) {
        try (input; output) { input.transferTo(output); }
        catch (IOException ignored) { }
    }

    private static void requireSuccess(CommandResult result, String fallback) throws IOException {
        try {
            if (result.exitCode != 0) throw new IOException(result.error.isBlank() ? fallback : result.error);
        } finally {
            Arrays.fill(result.output, (byte) 0);
        }
    }
}
