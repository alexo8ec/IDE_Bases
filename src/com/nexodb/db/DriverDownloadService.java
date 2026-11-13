package com.nexodb.db;

import com.nexodb.model.DatabaseType;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.IntConsumer;

/** Downloads known JDBC drivers to a per-user directory and verifies pinned SHA-256 hashes. */
public final class DriverDownloadService {
    public record DriverPackage(String version, String fileName, URI uri, String sha256) {}

    private static final Map<DatabaseType, DriverPackage> CATALOG = Map.of(
            DatabaseType.POSTGRESQL, pkg("42.7.14", "postgresql-42.7.14.jar",
                    "org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar",
                    "73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c"),
            DatabaseType.SQLSERVER, pkg("13.2.1.jre11", "mssql-jdbc-13.2.1.jre11.jar",
                    "com/microsoft/sqlserver/mssql-jdbc/13.2.1.jre11/mssql-jdbc-13.2.1.jre11.jar",
                    "2d137aa308c78932878952fe3624ea090ecfe644414b8b23e71a305f425698c5"),
            DatabaseType.ORACLE, pkg("23.26.0.0.0", "ojdbc11-23.26.0.0.0.jar",
                    "com/oracle/database/jdbc/ojdbc11/23.26.0.0.0/ojdbc11-23.26.0.0.0.jar",
                    "ef295bf01374dac7d791b3bc6526d10e8910ecac4645a95a3ca0c01cfc5c21d0"),
            DatabaseType.SQLITE, pkg("3.51.0.0", "sqlite-jdbc-3.51.0.0.jar",
                    "org/xerial/sqlite-jdbc/3.51.0.0/sqlite-jdbc-3.51.0.0.jar",
                    "b0ca8cf80d6eff80ee18aa6e8a6aa72f697af0bf9c822c4b2ad9e35c7a3146e1"),
            DatabaseType.H2, pkg("2.4.240", "h2-2.4.240.jar",
                    "com/h2database/h2/2.4.240/h2-2.4.240.jar",
                    "29b70e427cc1c40cdc376283adbb0cc62853073797bb5fe5761f81fe73d57ce0"),
            DatabaseType.MONGODB, pkg("4.0.0", "mongodb-jdbc-4.0.0-all.jar",
                    "org/mongodb/mongodb-jdbc/4.0.0/mongodb-jdbc-4.0.0-all.jar",
                    "6e4438cad39bb9090c7f584172d43ab5f5d2f8a3cc3abbe9a5c8d84cdc1dacdd")
    );

    private DriverDownloadService() {}

    private static DriverPackage pkg(String version, String fileName, String path, String sha256) {
        return new DriverPackage(version, fileName, URI.create("https://repo.maven.apache.org/maven2/" + path), sha256);
    }

    public static boolean supports(DatabaseType type) { return CATALOG.containsKey(type); }
    public static DriverPackage packageFor(DatabaseType type) { return CATALOG.get(type); }

    public static File findDownloadedDriver(DatabaseType type) {
        DriverPackage item = CATALOG.get(type);
        if (item == null) return null;
        Path target = driverDirectory().resolve(item.fileName());
        try {
            return Files.isRegularFile(target) && verify(target, item.sha256()) ? target.toFile() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static synchronized File ensureDownloaded(DatabaseType type, IntConsumer progress) throws Exception {
        DriverPackage item = CATALOG.get(type);
        if (item == null) throw new IllegalArgumentException("No hay una descarga automática registrada para " + type.displayName() + ".");
        File existing = findDownloadedDriver(type);
        if (existing != null) return existing;

        Path directory = driverDirectory();
        Files.createDirectories(directory);
        Path target = directory.resolve(item.fileName());
        Path partial = directory.resolve(item.fileName() + ".part");
        Files.deleteIfExists(partial);
        Files.deleteIfExists(target);
        notifyProgress(progress, 0);

        try {
            com.nexodb.util.SecureDownload.fetch(item.uri(), partial, 512L * 1024 * 1024,
                    Duration.ofMinutes(5), progress);
        } catch (Exception error) {
            Files.deleteIfExists(partial);
            throw error;
        }

        return finishDownload(type, item, partial, target, progress);
    }

    public static Path driverDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return Path.of(local, "NexoDB Studio", "drivers");
        return Path.of(System.getProperty("user.home"), ".nexodb", "drivers");
    }

    static boolean verify(Path file, String expectedSha256) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expectedSha256);
    }

    private static void notifyProgress(IntConsumer progress, int value) {
        if (progress != null) progress.accept(Math.max(0, Math.min(100, value)));
    }

    private static File finishDownload(DatabaseType type, DriverPackage item, Path partial, Path target,
                                       IntConsumer progress) throws Exception {
        if (!verify(partial, item.sha256())) {
            Files.deleteIfExists(partial);
            throw new SecurityException("La firma SHA-256 del adaptador de " + type.displayName()
                    + " no coincide. El archivo fue descartado.");
        }
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception unsupportedAtomicMove) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
        notifyProgress(progress, 100);
        return target.toFile();
    }
}
