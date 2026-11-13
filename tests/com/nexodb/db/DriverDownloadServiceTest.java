package com.nexodb.db;

import com.nexodb.model.DatabaseType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;

public final class DriverDownloadServiceTest {
    public static void main(String[] args) throws Exception {
        for (DatabaseType type : new DatabaseType[]{DatabaseType.POSTGRESQL, DatabaseType.SQLSERVER,
                DatabaseType.ORACLE, DatabaseType.SQLITE, DatabaseType.H2, DatabaseType.MONGODB}) {
            DriverDownloadService.DriverPackage item = DriverDownloadService.packageFor(type);
            require(item != null, "Falta el adaptador " + type);
            require("https".equals(item.uri().getScheme()), "La descarga debe usar HTTPS");
            require(item.sha256().matches("[0-9a-f]{64}"), "Firma SHA-256 inválida");
        }
        require(!DriverDownloadService.supports(DatabaseType.GENERIC), "JDBC genérico requiere selección manual");

        Path sample = Files.createTempFile("nexodb-driver-hash", ".bin");
        try {
            Files.writeString(sample, "NexoDB");
            require(DriverDownloadService.verify(sample,
                    "5e106d2db51227593971af4f7eb8002c5fc8aff768e97b55f1fdf4e0c1643271"), "No validó una firma correcta");
            require(!DriverDownloadService.verify(sample,
                    "0000000000000000000000000000000000000000000000000000000000000000"), "Aceptó una firma incorrecta");
        } finally {
            Files.deleteIfExists(sample);
        }

        if (args.length > 0 && "--download-postgresql".equals(args[0])) {
            var file = DriverDownloadService.ensureDownloaded(DatabaseType.POSTGRESQL, value -> {});
            require(file.isFile(), "No descargó PostgreSQL");
            require(DriverDownloadService.findDownloadedDriver(DatabaseType.POSTGRESQL) != null,
                    "No encontró el adaptador descargado");
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{file.toURI().toURL()})) {
                Class.forName("org.postgresql.Driver", true, loader);
            }
        }
        if (args.length > 0 && "--download-mongodb".equals(args[0])) {
            var file = DriverDownloadService.ensureDownloaded(DatabaseType.MONGODB, value -> {});
            require(file.isFile(), "No descargó MongoDB");
            require(DriverDownloadService.findDownloadedDriver(DatabaseType.MONGODB) != null,
                    "No encontró el adaptador MongoDB descargado");
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{file.toURI().toURL()})) {
                Class.forName("com.mongodb.jdbc.MongoDriver", true, loader);
            }
        }
        System.out.println("DriverDownloadServiceTest: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
