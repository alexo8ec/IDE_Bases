package com.nexodb.util;

import com.nexodb.db.ScheduledTaskSecrets;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SecretStoreTest {
    public static void main(String[] args) throws Exception {
        testUnsupportedNeverPersists();
        testNativeBackend(SecretStore.Backend.MACOS_KEYCHAIN);
        testNativeBackend(SecretStore.Backend.LINUX_SECRET_SERVICE);
        if (!Boolean.getBoolean("adisdb.test.skipNativeSecretStore")
                && SecretStore.detectBackend() == SecretStore.Backend.WINDOWS_DPAPI) testWindowsCompatibility();
        System.out.println("SecretStoreTest: OK");
    }

    private static void testUnsupportedNeverPersists() throws Exception {
        var folder = Files.createTempDirectory("adisdb-no-secret-");
        var file = folder.resolve("secrets.dat");
        SecretStore unsupported = new SecretStore(file, SecretStore.Backend.NONE,
                (command, input) -> { throw new AssertionError("No command expected"); });
        if (unsupported.supported() || unsupported.has("profile") || unsupported.load("profile") != null) {
            throw new AssertionError("Unsupported storage must remain disabled");
        }
        try {
            unsupported.save("profile", "not-on-disk".toCharArray());
            throw new AssertionError("Unsupported storage must not save passwords");
        } catch (java.io.IOException expected) {
            if (Files.exists(file)) throw new AssertionError("Plaintext credentials must not be written");
        }
        Files.delete(folder);
    }

    private static void testNativeBackend(SecretStore.Backend backend) throws Exception {
        var folder = Files.createTempDirectory("adisdb-native-secret-");
        var file = folder.resolve("secrets.dat");
        FakeSecureStore commands = new FakeSecureStore();
        SecretStore store = new SecretStore(file, backend, commands);
        String id = "profile-" + backend.name().toLowerCase();
        char[] expected = "clave segura ñ-42".toCharArray();
        store.save(id, expected);
        if (!store.supported() || !store.has(id)) throw new AssertionError(backend + " secret was not stored");
        char[] actual = store.load(id);
        try {
            if (!Arrays.equals(expected, actual)) throw new AssertionError(backend + " roundtrip failed");
        } finally {
            if (actual != null) Arrays.fill(actual, '\0');
        }
        if (Files.exists(file)) throw new AssertionError(backend + " must not create a local password file");
        if (commands.secretAppearedInArguments) throw new AssertionError("Secret was exposed in process arguments for " + backend);
        ScheduledTaskSecrets taskSecrets = new ScheduledTaskSecrets(store);
        if (!taskSecrets.supported()) throw new AssertionError("Scheduled tasks did not detect " + backend);
        taskSecrets.save("backup-1", expected);
        if (!taskSecrets.has("backup-1")) throw new AssertionError("Task credential was not stored in " + backend);
        taskSecrets.delete("backup-1");
        if (taskSecrets.has("backup-1")) throw new AssertionError("Task credential was not removed from " + backend);
        store.delete(id);
        if (store.has(id)) throw new AssertionError(backend + " secret was not removed");
        Arrays.fill(expected, '\0');
        Files.delete(folder);
    }

    private static void testWindowsCompatibility() throws Exception {
        var folder = Files.createTempDirectory("adisdb-dpapi-secret-");
        var file = folder.resolve("secrets.dat");
        SecretStore store = new SecretStore(file);
        String id = "test-" + UUID.randomUUID();
        char[] expected = "clave temporal ñ-42".toCharArray();
        try {
            store.save(id, expected);
            if (!store.has(id)) throw new AssertionError("Encrypted DPAPI secret was not stored");
            String disk = Files.readString(file);
            if (disk.contains("clave temporal")) throw new AssertionError("Secret was written as plain text");
            char[] actual = store.load(id);
            try {
                if (!Arrays.equals(expected, actual)) throw new AssertionError("DPAPI secret roundtrip failed");
            } finally {
                if (actual != null) Arrays.fill(actual, '\0');
            }
        } finally {
            store.delete(id);
            Arrays.fill(expected, '\0');
        }
        if (store.has(id)) throw new AssertionError("Encrypted secret was not removed");
        Files.deleteIfExists(file);
        Files.deleteIfExists(folder);
    }

    private static final class FakeSecureStore implements SecretStore.CommandRunner {
        private final Map<String, byte[]> values = new HashMap<>();
        private boolean secretAppearedInArguments;

        @Override
        public SecretStore.CommandResult run(List<String> command, byte[] input) {
            String joined = String.join(" ", command);
            if (joined.contains("clave segura")) secretAppearedInArguments = true;
            String id = argumentAfter(command, "-a");
            if (id == null) id = argumentAfter(command, "profile");
            if (command.contains("add-generic-password") || command.contains("store")) {
                int end = input.length;
                while (end > 0 && (input[end - 1] == '\n' || input[end - 1] == '\r')) end--;
                values.put(id, Arrays.copyOf(input, end));
                return ok(new byte[0]);
            }
            if (command.contains("find-generic-password") || command.contains("lookup")) {
                byte[] value = values.get(id);
                if (value == null) return new SecretStore.CommandResult(1, new byte[0], "not found");
                byte[] output = Arrays.copyOf(value, value.length + 1);
                output[output.length - 1] = '\n';
                return ok(output);
            }
            if (command.contains("delete-generic-password") || command.contains("clear")) {
                values.remove(id);
                return ok(new byte[0]);
            }
            return new SecretStore.CommandResult(2, new byte[0], "unexpected command");
        }

        private static String argumentAfter(List<String> command, String name) {
            int index = command.indexOf(name);
            return index >= 0 && index + 1 < command.size() ? command.get(index + 1) : null;
        }

        private static SecretStore.CommandResult ok(byte[] output) {
            return new SecretStore.CommandResult(0, output, "");
        }
    }
}
