package com.nexodb.util;

import com.nexodb.db.QueryResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Incremental, atomic tabular export without retaining the complete result in memory. */
public final class DataExporter {
    public enum Format {
        CSV("CSV", "csv"), XLSX("Excel", "xlsx"), TXT("Texto (tabulado)", "txt"), TSV("TSV", "tsv"), JSON("JSON", "json"),
        XML("XML", "xml"), HTML("HTML", "html"), SQL("SQL INSERT", "sql");
        private final String label;private final String extension;
        Format(String label,String extension){this.label=label;this.extension=extension;}
        public String extension(){return extension;}@Override public String toString(){return label;}
    }

    public interface RowWriter {void start(List<String> columns)throws IOException;void row(List<?> values)throws IOException;}
    @FunctionalInterface public interface StreamSource {long transfer(RowWriter writer)throws Exception;}
    interface Target extends RowWriter,AutoCloseable {void finish()throws IOException;long rowCount();@Override void close()throws IOException;}
    private DataExporter(){}

    public static void write(QueryResult result,Path path,Format format,String tableName)throws IOException{
        try{writeStreaming(path,format,tableName,writer->{writer.start(result.columns());for(List<Object> row:result.rows())writer.row(row);return result.rows().size();});}
        catch(IOException error){throw error;}catch(Exception error){throw new IOException(error.getMessage(),error);}
    }

    /** Writes beside the destination and replaces it only after a complete export. */
    public static long writeStreaming(Path path,Format format,String tableName,StreamSource source)throws Exception{
        Path destination=path.toAbsolutePath(),parent=destination.getParent();if(parent==null)throw new IOException("La ruta de exportación no tiene una carpeta válida.");Files.createDirectories(parent);
        Path temporary=Files.createTempFile(parent,"."+destination.getFileName()+"-",".tmp");boolean completed=false;long rows;
        try{
            try(Target target=format==Format.XLSX?XlsxExporter.open(temporary):new TextTarget(temporary,format,safeTableName(tableName))){long reported=source.transfer(target);target.finish();rows=target.rowCount();if(reported>=0&&reported!=rows)throw new IOException("La fuente reportó "+reported+" filas, pero se escribieron "+rows+".");}
            try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unavailable){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}completed=true;return rows;
        }finally{if(!completed)Files.deleteIfExists(temporary);}
    }

    private static final class TextTarget implements Target {
        private final BufferedWriter writer;private final Format format;private final String table;private List<String> columns=List.of();private boolean started,finished,first=true;private long rows;
        TextTarget(Path path,Format format,String table)throws IOException{writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8);this.format=format;this.table=table;}
        public void start(List<String> value)throws IOException{
            if(started)throw new IOException("La exportación ya fue iniciada.");if(value==null||value.isEmpty())throw new IOException("El resultado no contiene columnas.");columns=List.copyOf(value);started=true;
            switch(format){
                case CSV->{writer.write('\ufeff');writeDelimitedRow(writer,columns,',');}case TSV,TXT->{writer.write('\ufeff');writeDelimitedRow(writer,columns,'\t');}case JSON->writer.write("[\n");case XML->writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rows>\n");
                case HTML->{writer.write("<!doctype html><html><head><meta charset=\"utf-8\"><title>Exportación AdisDB</title><style>body{font-family:Segoe UI,Arial,sans-serif;margin:24px}table{border-collapse:collapse}th,td{border:1px solid #cbd5e1;padding:6px 10px;text-align:left}th{background:#eaf2ff}</style></head><body><table><thead><tr>");for(String column:columns){writer.write("<th>");writer.write(xml(column));writer.write("</th>");}writer.write("</tr></thead><tbody>\n");}
                case SQL->{}case XLSX->throw new IllegalStateException("Excel usa un escritor OOXML incremental.");
            }
        }
        public void row(List<?> values)throws IOException{
            ensureStarted();if(values.size()!=columns.size())throw new IOException("La fila no coincide con las columnas del resultado.");
            switch(format){
                case CSV->writeDelimitedRow(writer,values,',');case TSV,TXT->writeDelimitedRow(writer,values,'\t');
                case JSON->{if(!first)writer.write(",\n");writer.write("  {");for(int col=0;col<columns.size();col++){if(col>0)writer.write(',');writer.write("\n    \"");writer.write(json(columns.get(col)));writer.write("\": ");writeJsonValue(writer,values.get(col));}writer.write("\n  }");}
                case XML->{writer.write("  <row>\n");for(int col=0;col<columns.size();col++){writer.write("    <column name=\"");writer.write(xml(columns.get(col)));writer.write("\"");Object value=values.get(col);if(value==null)writer.write(" null=\"true\" />\n");else{writer.write(">");writer.write(xml(value.toString()));writer.write("</column>\n");}}writer.write("  </row>\n");}
                case HTML->{writer.write("<tr>");for(Object value:values){writer.write("<td>");if(value!=null)writer.write(xml(value.toString()));writer.write("</td>");}writer.write("</tr>\n");}
                case SQL->writeSqlRow(writer,columns,values,table);case XLSX->throw new IllegalStateException("Excel usa un escritor OOXML incremental.");
            }
            first=false;rows++;
        }
        public void finish()throws IOException{ensureStarted();if(finished)return;switch(format){case JSON->writer.write("\n]\n");case XML->writer.write("</rows>\n");case HTML->writer.write("</tbody></table></body></html>\n");default->{}}writer.flush();finished=true;}
        public long rowCount(){return rows;}public void close()throws IOException{writer.close();}private void ensureStarted()throws IOException{if(!started)throw new IOException("La fuente no inició la exportación con sus columnas.");}
    }

    private static void writeDelimitedRow(BufferedWriter writer,List<?> values,char delimiter)throws IOException{for(int i=0;i<values.size();i++){if(i>0)writer.write(delimiter);String value=values.get(i)==null?"":values.get(i).toString();boolean quote=value.indexOf(delimiter)>=0||value.contains("\"")||value.contains("\n")||value.contains("\r");if(quote)writer.write('"');writer.write(value.replace("\"","\"\""));if(quote)writer.write('"');}writer.newLine();}
    private static void writeJsonValue(BufferedWriter writer,Object value)throws IOException{if(value==null)writer.write("null");else if(value instanceof Number||value instanceof Boolean)writer.write(value.toString());else{writer.write('"');writer.write(json(value.toString()));writer.write('"');}}
    private static String json(String value){StringBuilder out=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<0x20)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}}return out.toString();}
    private static void writeSqlRow(BufferedWriter writer,List<String> columns,List<?> row,String tableName)throws IOException{writer.write("INSERT INTO ");writer.write(tableName);writer.write(" (");for(int col=0;col<columns.size();col++){if(col>0)writer.write(", ");writer.write(quoteIdentifier(columns.get(col)));}writer.write(") VALUES (");for(int col=0;col<row.size();col++){if(col>0)writer.write(", ");writer.write(sqlLiteral(row.get(col)));}writer.write(");\n");}
    private static String safeTableName(String value){if(value==null||value.isBlank())return "`resultado`";String[] parts=value.replace("`","").replace("\"","").split("\\.");StringBuilder out=new StringBuilder();for(String part:parts){if(out.length()>0)out.append('.');out.append(quoteIdentifier(part));}return out.toString();}
    private static String quoteIdentifier(String value){return "`"+value.replace("`","``")+"`";}private static String sqlLiteral(Object value){if(value==null)return "NULL";if(value instanceof Number)return value.toString();if(value instanceof Boolean flag)return flag?"1":"0";return "'"+value.toString().replace("'","''")+"'";}
    private static String xml(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
