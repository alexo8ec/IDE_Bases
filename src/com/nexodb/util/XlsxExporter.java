package com.nexodb.util;

import com.nexodb.db.QueryResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

/** Streaming Office Open XML workbook; text cells never become formulas. */
final class XlsxExporter {
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private XlsxExporter() {}

    static void write(QueryResult result, Path path) throws IOException {
        if (result.columns().isEmpty() || result.columns().size()>16_384 || result.rows().size()>1_048_575)
            throw new IOException("Excel admite hasta 16.384 columnas y 1.048.575 filas de datos más el encabezado. Exporta a CSV o reduce el resultado.");
        for (String column : result.columns()) validate(column);
        for (var row : result.rows()) for (Object value : row) if (value!=null) validate(value.toString());
        try (var zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path)),StandardCharsets.UTF_8)) {
            entry(zip,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    +"<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    +"<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    +"<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    +"<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    +"<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
            entry(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\""+REL+"/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip,"xl/workbook.xml","<workbook xmlns=\""+MAIN+"\" xmlns:r=\""+REL+"\"><sheets><sheet name=\"Resultado\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    +"<Relationship Id=\"rId1\" Type=\""+REL+"/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    +"<Relationship Id=\"rId2\" Type=\""+REL+"/styles\" Target=\"styles.xml\"/></Relationships>");
            entry(zip,"xl/styles.xml","<styleSheet xmlns=\""+MAIN+"\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    +"<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    +"<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    +"<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    +"<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                    +"<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            var writer = new BufferedWriter(new OutputStreamWriter(zip,StandardCharsets.UTF_8));
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\""+MAIN+"\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>");
            row(writer,1,result.columns(),true);
            for (int i=0;i<result.rows().size();i++) row(writer,i+2,result.rows().get(i),false);
            writer.write("</sheetData><autoFilter ref=\"A1:"+column(result.columns().size()-1)+(result.rows().size()+1)+"\"/></worksheet>");
            writer.flush();
            zip.closeEntry();
        }
    }

    private static void validate(String value) throws IOException {
        if (value.length()>32_767) throw new IOException("Una celda supera el límite de Excel de 32.767 caracteres. Exporta a CSV para conservar el texto completo.");
    }

    private static void entry(ZipOutputStream zip,String name,String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"+xml).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void row(Writer writer,int index,java.util.List<?> values,boolean header) throws IOException {
        writer.write("<row r=\""+index+"\">");
        for (int i=0;i<values.size();i++) {
            Object value=values.get(i);
            if (value==null) continue;
            String ref=column(i)+index, text=value.toString();
            writer.write("<c r=\""+ref+"\""+(header?" s=\"1\"":""));
            if (!header && value instanceof Boolean bool) writer.write(" t=\"b\"><v>"+(bool?"1":"0")+"</v></c>");
            else if (!header && numeric(value)) writer.write("><v>"+text+"</v></c>");
            else writer.write(" t=\"inlineStr\"><is><t xml:space=\"preserve\">"+text(text)+"</t></is></c>");
        }
        writer.write("</row>");
    }

    private static boolean numeric(Object value) {
        if (!(value instanceof Number)) return false;
        try {
            var number=new java.math.BigDecimal(value.toString());
            double approximate=number.doubleValue();
            return number.precision()<=15 && Double.isFinite(approximate) && (approximate!=0 || number.signum()==0);
        }
        catch (NumberFormatException invalid) { return false; }
    }

    private static String column(int index) {
        StringBuilder result=new StringBuilder();
        for (int n=index+1;n>0;n=(n-1)/26) result.append((char)('A'+(n-1)%26));
        return result.reverse().toString();
    }

    private static String text(String value) {
        // Escape literal Excel escape sequences, then preserve XML-invalid controls as OOXML escapes.
        value=value.replaceAll("_(?=[xX][0-9a-fA-F]{4}_)","_x005F_");
        StringBuilder out=new StringBuilder();
        for (int i=0;i<value.length();) {
            int c=value.codePointAt(i); i+=Character.charCount(c);
            switch (c) {
                case '&' -> out.append("&amp;"); case '<' -> out.append("&lt;"); case '>' -> out.append("&gt;");
                case '\r' -> out.append("&#13;");
                default -> {
                    if ((c<32 && c!='\n' && c!='\t') || c==0xFFFE || c==0xFFFF || c>=0xD800 && c<=0xDFFF)
                        out.append(String.format(java.util.Locale.ROOT,"_x%04X_",c));
                    else out.appendCodePoint(c);
                }
            }
        }
        return out.toString();
    }
}
