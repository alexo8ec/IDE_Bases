package com.nexodb.util;

import com.nexodb.db.QueryResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CsvExporter {
    private CsvExporter() {}

    public static void write(QueryResult result, Path path) throws IOException {
        DataExporter.write(result, path, DataExporter.Format.CSV, "resultado");
    }
}

