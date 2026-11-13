package com.nexodb.util;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class AppSettings {
    private AppSettings() {}
    public static Path file() {
        String override = System.getProperty("nexodb.settingsFile", "");
        if (!override.isBlank()) return Path.of(override).toAbsolutePath();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.startsWith("windows")) return Path.of(System.getenv().getOrDefault("APPDATA", home.toString()), "NexoDB Studio", "settings.properties");
        if (os.contains("mac")) return home.resolve("Library/Application Support/NexoDB Studio/settings.properties");
        return Path.of(System.getenv().getOrDefault("XDG_CONFIG_HOME", home.resolve(".config").toString()), "nexodb-studio", "settings.properties");
    }
    private static Properties read() {
        Properties values = new Properties();
        try {
            if (Files.isRegularFile(file())) try (var reader = Files.newBufferedReader(file())) { values.load(reader); }
        } catch (IOException | SecurityException ignored) {}
        return values;
    }
    public static synchronized String get(String key, String fallback) { return read().getProperty(key, fallback); }
    public static synchronized void put(String key, String value) {
        Properties values = read();
        values.setProperty(key, value);
        Path target = file();
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "settings-", ".tmp");
            try (var writer = Files.newBufferedWriter(temporary)) { values.store(writer, "NexoDB Studio preferences"); }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException | SecurityException error) {
            throw new IllegalStateException(I18n.tr("No se pudieron guardar las preferencias."), error);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }
}
