package com.nexodb.util;

import com.nexodb.db.QueryResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exports a tabular result without requiring external libraries. */
public final class DataExporter {
    public enum Format {
        CSV("CSV", "csv"), XLSX("Excel", "xlsx"), TXT("Texto (tabulado)", "txt"), TSV("TSV", "tsv"), JSON("JSON", "json"),
        XML("XML", "xml"), HTML("HTML", "html"), SQL("SQL INSERT", "sql");

        private final String label;
        private final String extension;
        Format(String label, String extension) { this.label = label; this.extension = extension; }
        public String extension() { return extension; }
        @Override public String toString() { return label; }
    }

    private DataExporter() {}

    public static void write(QueryResult result, Path path, Format format, String tableName) throws IOException {
        if (format == Format.XLSX) {
            XlsxExporter.write(result,path);
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            switch (format) {
                case CSV -> writeDelimited(writer, result, ',');
                case TSV, TXT -> writeDelimited(writer, result, '\t');
                case JSON -> writeJson(writer, result);
                case XML -> writeXml(writer, result);
                case HTML -> writeHtml(writer, result);
                case SQL -> writeSql(writer, result, safeTableName(tableName));
                case XLSX -> throw new IllegalStateException("Excel uses a ZIP workbook");
            }
        }
    }

    private static void writeDelimited(BufferedWriter writer, QueryResult result, char delimiter) throws IOException {
        writer.write('\ufeff');
        writeDelimitedRow(writer, result.columns(), delimiter);
        for (List<Object> row : result.rows()) writeDelimitedRow(writer, row, delimiter);
    }

    private static void writeDelimitedRow(BufferedWriter writer, List<?> values, char delimiter) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) writer.write(delimiter);
            String value = values.get(i) == null ? "" : values.get(i).toString();
            boolean quote = value.indexOf(delimiter) >= 0 || value.contains("\"") || value.contains("\n") || value.contains("\r");
            if (quote) writer.write('"');
            writer.write(value.replace("\"", "\"\""));
            if (quote) writer.write('"');
        }
        writer.newLine();
    }

    private static void writeJson(BufferedWriter writer, QueryResult result) throws IOException {
        writer.write("[\n");
        for (int row = 0; row < result.rows().size(); row++) {
            if (row > 0) writer.write(",\n");
            writer.write("  {");
            for (int col = 0; col < result.columns().size(); col++) {
                if (col > 0) writer.write(',');
                writer.write("\n    \""); writer.write(json(result.columns().get(col))); writer.write("\": ");
                writeJsonValue(writer, result.rows().get(row).get(col));
            }
            writer.write("\n  }");
        }
        writer.write("\n]\n");
    }

    private static void writeJsonValue(BufferedWriter writer, Object value) throws IOException {
        if (value == null) writer.write("null");
        else if (value instanceof Number || value instanceof Boolean) writer.write(value.toString());
        else { writer.write('"'); writer.write(json(value.toString())); writer.write('"'); }
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.toString();
    }

    private static void writeXml(BufferedWriter writer, QueryResult result) throws IOException {
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rows>\n");
        for (List<Object> row : result.rows()) {
            writer.write("  <row>\n");
            for (int col = 0; col < result.columns().size(); col++) {
                writer.write("    <column name=\""); writer.write(xml(result.columns().get(col))); writer.write("\"");
                Object value = row.get(col);
                if (value == null) writer.write(" null=\"true\" />\n");
                else { writer.write(">"); writer.write(xml(value.toString())); writer.write("</column>\n"); }
            }
            writer.write("  </row>\n");
        }
        writer.write("</rows>\n");
    }

    private static void writeHtml(BufferedWriter writer, QueryResult result) throws IOException {
        writer.write("<!doctype html><html><head><meta charset=\"utf-8\"><title>Exportación AdisDB</title>"
                + "<style>body{font-family:Segoe UI,Arial,sans-serif;margin:24px}table{border-collapse:collapse}th,td{border:1px solid #cbd5e1;padding:6px 10px;text-align:left}th{background:#eaf2ff}</style>"
                + "</head><body><table><thead><tr>");
        for (String column : result.columns()) { writer.write("<th>"); writer.write(xml(column)); writer.write("</th>"); }
        writer.write("</tr></thead><tbody>\n");
        for (List<Object> row : result.rows()) {
            writer.write("<tr>");
            for (Object value : row) { writer.write("<td>"); if (value != null) writer.write(xml(value.toString())); writer.write("</td>"); }
            writer.write("</tr>\n");
        }
        writer.write("</tbody></table></body></html>\n");
    }

    private static void writeSql(BufferedWriter writer, QueryResult result, String tableName) throws IOException {
        for (List<Object> row : result.rows()) {
            writer.write("INSERT INTO "); writer.write(tableName); writer.write(" (");
            for (int col = 0; col < result.columns().size(); col++) {
                if (col > 0) writer.write(", ");
                writer.write(quoteIdentifier(result.columns().get(col)));
            }
            writer.write(") VALUES (");
            for (int col = 0; col < row.size(); col++) {
                if (col > 0) writer.write(", ");
                writer.write(sqlLiteral(row.get(col)));
            }
            writer.write(");\n");
        }
    }

    private static String safeTableName(String value) {
        if (value == null || value.isBlank()) return "`resultado`";
        String[] parts = value.replace("`", "").replace("\"", "").split("\\.");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append('.');
            out.append(quoteIdentifier(part));
        }
        return out.toString();
    }

    private static String quoteIdentifier(String value) { return "`" + value.replace("`", "``") + "`"; }
    private static String sqlLiteral(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean flag) return flag ? "1" : "0";
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
