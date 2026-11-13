package com.nexodb.db;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/** Reviews and executes SQL files without invoking a shell or mysql client commands. */
public final class SqlImportService {
    public record Analysis(Path file,String sha256,long bytes,long statements,long destructive,Set<String> databases,String preview) {}
    public record Options(String target,boolean createDatabase,boolean preserveDatabaseNames,boolean omitDefiner) {}
    public record Progress(long completed,long total,int line) {}
    public record Report(long executed,long skipped) {}
    public static final class ImportFailure extends Exception {
        private final long completed;private final int line;
        ImportFailure(long completed,int line,Throwable cause){super(cause.getMessage(),cause);this.completed=completed;this.line=line;}
        public long completed(){return completed;}public int line(){return line;}
    }
    private SqlImportService() {}
    public static Analysis analyze(Path file,SqlTransferControl control)throws Exception {
        file=file.toAbsolutePath();long statements=0,destructive=0;Set<String> databases=new LinkedHashSet<>();
        StringBuilder preview=new StringBuilder();MessageDigest digest=MessageDigest.getInstance("SHA-256");
        // Generated backups include an unambiguous source identifier even without USE/CREATE DATABASE.
        try(Reader input=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
            char[] header=new char[64*1024];int count=0,read;while(count<header.length&&(read=input.read(header,count,header.length-count))>0)count+=read;
            for(String line:new String(header,0,count).split("\\R",21))if(line.startsWith("-- NEXODB-DATABASE: "))try{databases.add(new String(Base64.getDecoder().decode(line.substring(20).trim()),StandardCharsets.UTF_8));}catch(IllegalArgumentException error){throw new IOException("Identificador de base no válido en el respaldo.",error);}
        }
        try(InputStream input=new DigestInputStream(Files.newInputStream(file),digest);SqlScriptReader reader=new SqlScriptReader(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder()))){
            SqlScriptReader.Statement statement;while((statement=reader.next())!=null){control.check();statements++;if(SqlScriptTools.destructive(statement.sql()))destructive++;
                String database=SqlScriptTools.databaseDirective(statement.sql());if(database!=null)databases.add(database);
                databases.addAll(SqlScriptTools.referencedDatabases(statement.sql()));
                if(preview.length()<12000){preview.append("-- Línea ").append(statement.line()).append('\n');String text=statement.sql();preview.append(text,0,Math.min(text.length(),12000-preview.length())).append(";\n\n");}
            }
        }
        return new Analysis(file,HexFormat.of().formatHex(digest.digest()),Files.size(file),statements,destructive,Collections.unmodifiableSet(databases),preview.toString());
    }
    public static Report restore(Connection connection,Analysis analysis,Options options,SqlTransferControl control,Consumer<Progress> progress)throws Exception {
        if(!connection.getAutoCommit())throw new SQLException("La importación requiere una conexión independiente sin transacciones pendientes.");
        if(!options.preserveDatabaseNames()&&analysis.databases().size()>1)throw new IllegalArgumentException("El archivo contiene varias bases. Impórtalas respetando sus nombres originales o usa un archivo de una sola base.");
        if(!options.preserveDatabaseNames())SqlBackupService.quote(options.target());
        long completed=0,skipped=0;int line=1;
        // Hold one open handle for verification and execution; on Windows this also denies file replacement.
        try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(analysis.file(),StandardOpenOption.READ)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(64*1024);
            while(channel.read(buffer)!=-1){control.check();buffer.flip();digest.update(buffer);buffer.clear();}
            if(channel.size()!=analysis.bytes()||!HexFormat.of().formatHex(digest.digest()).equals(analysis.sha256()))throw new IOException("El archivo SQL cambió después de revisarlo. Vuelve a abrirlo antes de importar.");
            channel.position(0);control.check();
            if(options.createDatabase())execute(connection,"CREATE DATABASE IF NOT EXISTS "+SqlBackupService.quote(options.target())+" CHARACTER SET utf8mb4",control);
            if(!options.target().isBlank())connection.setCatalog(options.target());
            connection.setAutoCommit(false);
            try(SqlScriptReader reader=new SqlScriptReader(java.nio.channels.Channels.newReader(channel,StandardCharsets.UTF_8))){
                SqlScriptReader.Statement statement;while((statement=reader.next())!=null){
                    control.check();line=statement.line();String sql=statement.sql();
                    if(!options.preserveDatabaseNames()&&SqlScriptTools.databaseDirective(sql)!=null){skipped++;continue;}
                    if(!options.preserveDatabaseNames()){
                        // A DROP DATABASE cannot be redirected safely to the target database.
                        var tokens=SqlScriptTools.tokens(sql);
                        if(tokens.size()>1&&tokens.get(0).is("DROP")&&(tokens.get(1).is("DATABASE")||tokens.get(1).is("SCHEMA")))throw new SQLException("DROP DATABASE no se permite al restaurar dentro de una base seleccionada.");
                        sql=SqlScriptTools.remap(sql,analysis.databases(),options.target());
                    }
                    if(options.omitDefiner())sql=SqlScriptTools.withoutDefiner(sql);
                    execute(connection,sql,control);completed++;progress.accept(new Progress(completed+skipped,analysis.statements(),line));
                }
                control.check();connection.commit();return new Report(completed,skipped);
            }catch(Exception error){try{connection.rollback();}catch(SQLException rollback){error.addSuppressed(rollback);}if(error instanceof java.util.concurrent.CancellationException)throw error;throw new ImportFailure(completed,line,error);}
        }
    }
    private static void execute(Connection connection,String sql,SqlTransferControl control)throws SQLException {
        control.check();try(Statement statement=connection.createStatement()){
            control.active(statement);boolean rows=statement.execute(sql);
            // Consume all results so stored routine calls do not leave the connection out of sync.
            while(true){if(rows)try(ResultSet result=statement.getResultSet()){while(result.next())control.check();}else if(statement.getUpdateCount()==-1)break;rows=statement.getMoreResults();}
        }finally{control.active(null);}
    }
}
