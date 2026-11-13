package com.nexodb.util;

import com.nexodb.db.QueryResult;

import java.nio.file.Files;
import java.util.List;

public final class DataExporterTest {
    public static void main(String[] args) throws Exception {
        QueryResult result = new QueryResult(List.of("id", "nombre", "nota"),
                List.of(List.of(1, "Ana", "A, B"), java.util.Arrays.asList(2, "O'Reilly", null)), -1, 1, false);
        var folder = Files.createTempDirectory("nexodb-export-test-");
        try {
            for (DataExporter.Format format : DataExporter.Format.values()) {
                var file = folder.resolve("datos." + format.extension());
                DataExporter.write(result, file, format, "personas");
                String text = Files.readString(file);
                if (text.isBlank()) throw new AssertionError(format + " export is empty");
                if (format == DataExporter.Format.JSON && !text.contains("\"nombre\": \"O'Reilly\"")) throw new AssertionError("Invalid JSON export");
                if (format == DataExporter.Format.SQL && !text.contains("O''Reilly")) throw new AssertionError("SQL value was not escaped");
                Files.delete(file);
            }
        } finally {
            Files.deleteIfExists(folder);
        }
        System.out.println("DataExporterTest: OK");
    }
}
