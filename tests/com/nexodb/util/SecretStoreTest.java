package com.nexodb.util;

import java.util.Arrays;
import java.util.UUID;
import java.nio.file.Files;

public final class SecretStoreTest {
    public static void main(String[] args) throws Exception {
        if (!SecretStore.isSupported()) {
            var testFile = Files.createTempDirectory("nexodb-no-secret-").resolve("secrets.dat");
            SecretStore unsupported = new SecretStore(testFile);
            if (unsupported.has("profile") || unsupported.load("profile") != null) throw new AssertionError("Unsupported storage must remain disabled");
            try {
                unsupported.save("profile", "not-on-disk".toCharArray());
                throw new AssertionError("Unsupported storage must not save passwords");
            } catch (java.io.IOException expected) {
                if (Files.exists(testFile)) throw new AssertionError("Plaintext credentials must not be written");
            }
            Files.delete(testFile.getParent());
            System.out.println("SecretStoreTest: OK (password persistence disabled on this platform)");
            return;
        }
        var folder = Files.createTempDirectory("nexodb-secret-test-");
        SecretStore store = new SecretStore(folder.resolve("secrets.dat"));
        String id = "test-" + UUID.randomUUID();
        char[] expected = "clave temporal ñ-42".toCharArray();
        try {
            store.save(id, expected);
            if (!store.has(id)) throw new AssertionError("Encrypted secret was not stored");
            String disk = Files.readString(folder.resolve("secrets.dat"));
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
        Files.deleteIfExists(folder.resolve("secrets.dat"));
        Files.deleteIfExists(folder);
        System.out.println("SecretStoreTest: OK");
    }
}
