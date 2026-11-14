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
                if (format == DataExporter.Format.XLSX) {
                    verifyWorkbook(file);
                    Files.delete(file);
                    continue;
                }
                String text = Files.readString(file);
                if (text.isBlank()) throw new AssertionError(format + " export is empty");
                if (format == DataExporter.Format.JSON && !text.contains("\"nombre\": \"O'Reilly\"")) throw new AssertionError("Invalid JSON export");
                if (format == DataExporter.Format.SQL && !text.contains("O''Reilly")) throw new AssertionError("SQL value was not escaped");
                if (format == DataExporter.Format.TXT && !text.contains("id\tnombre\tnota"))throw new AssertionError("TXT must be readable tab-separated text with headers");
                Files.delete(file);
            }
        } finally {
            Files.deleteIfExists(folder);
        }
        System.out.println("DataExporterTest: OK");
    }

    private static void verifyWorkbook(java.nio.file.Path file) throws Exception {
        try (var zip = new java.util.zip.ZipFile(file.toFile())) {
            var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var parser=factory.newDocumentBuilder();
            for (var entries=zip.entries();entries.hasMoreElements();) {
                try (var input=zip.getInputStream(entries.nextElement())) { parser.parse(input); }
            }
            try (var input=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))) {
                var doc=parser.parse(input);
                if (doc.getElementsByTagName("row").getLength()!=3) throw new AssertionError("Excel rows missing");
                if (!doc.getDocumentElement().getTextContent().contains("O'Reilly")) throw new AssertionError("Excel text missing");
            }
        }
        QueryResult edge = new QueryResult(List.of("text","number","bool","null","long id","unicode","control"),
                List.of(java.util.Arrays.asList("=1+1",12.5,true,null,1234567890123456789L," ñ <&> 😀 ","_x0041_\u0001\r\n")), -1,0,false);
        DataExporter.write(edge,file,DataExporter.Format.XLSX,"test");
        try (var zip = new java.util.zip.ZipFile(file.toFile())) {
            String sheet=new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if (sheet.contains("<f>") || !sheet.contains(">=1+1</t>") || !sheet.contains("<c r=\"B2\"><v>12.5</v>")
                    || !sheet.contains("<c r=\"C2\" t=\"b\"><v>1</v>") || sheet.contains("r=\"D2\"")
                    || !sheet.contains("<c r=\"E2\" t=\"inlineStr\"") || !sheet.contains("1234567890123456789")
                    || !sheet.contains(" ñ &lt;&amp;&gt; 😀 ") || !sheet.contains("_x005F_x0041__x0001_&#13;\n"))
                throw new AssertionError("Excel types, precision or escaping: "+sheet);
        }
        try {
            DataExporter.write(new QueryResult(List.of("text"),List.of(List.of("x".repeat(32768))),-1,0,false),
                    file,DataExporter.Format.XLSX,"test");
            throw new AssertionError("Excel must reject oversized text without truncating it");
        } catch (java.io.IOException expected) { }
    }
}
