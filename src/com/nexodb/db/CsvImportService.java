package com.nexodb.db;

import com.nexodb.util.SqlFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.LongConsumer;

public final class CsvImportService {
    public record Column(String name,int type){}
    public record Preview(Path file,String hash,List<String> headers,List<List<String>> rows,char delimiter,boolean header){}
    public record Mapping(int source,Column target){}
    public record Options(String qualifiedTable,List<Mapping> mapping,String nullToken,boolean emptyNull){}
    private CsvImportService(){}
    public static Preview preview(Path path,char delimiter,boolean header)throws Exception{
        path=path.toRealPath();List<String> names;List<List<String>> rows=new ArrayList<>();
        try(CsvReader reader=new CsvReader(Files.newBufferedReader(path,StandardCharsets.UTF_8),delimiter)){
            List<String> first=reader.next();if(first==null)throw new IllegalArgumentException("El archivo está vacío.");
            if(header)names=first;else{names=new ArrayList<>();for(int i=0;i<first.size();i++)names.add("Columna "+(i+1));rows.add(first);}
            List<String> row;while(rows.size()<20&&(row=reader.next())!=null){if(row.size()!=names.size())throw new IllegalArgumentException("Cantidad de columnas diferente en fila "+reader.record());rows.add(row);}
        }return new Preview(path,SqlFiles.diskHash(path),List.copyOf(names),List.copyOf(rows),delimiter,header);
    }
    public static long importRows(Connection connection,Preview reviewed,Options options,SqlTransferControl control,LongConsumer progress)throws Exception{
        if(!connection.getAutoCommit())throw new SQLException("La importación requiere una conexión independiente.");if(options.mapping().isEmpty())throw new IllegalArgumentException("Selecciona al menos una columna de destino.");
        if(options.mapping().stream().map(m->m.target().name()).distinct().count()!=options.mapping().size())throw new IllegalArgumentException("Hay columnas de destino duplicadas.");
        if(!SqlFiles.diskHash(reviewed.file()).equals(reviewed.hash()))throw new java.io.IOException("El archivo cambió después de la vista previa. Revísalo otra vez.");
        String quote=connection.getMetaData().getIdentifierQuoteString();String sql="INSERT INTO "+options.qualifiedTable()+" ("+String.join(",",options.mapping().stream().map(m->VisualQuery.quote(m.target().name(),quote)).toList())+") VALUES ("+String.join(",",Collections.nCopies(options.mapping().size(),"?"))+")";
        long count=0;connection.setAutoCommit(false);
        try(CsvReader reader=new CsvReader(Files.newBufferedReader(reviewed.file(),StandardCharsets.UTF_8),reviewed.delimiter());PreparedStatement statement=connection.prepareStatement(sql)){
            control.active(statement);if(reviewed.header())reader.next();List<String> row;int batch=0;
            while((row=reader.next())!=null){control.check();if(row.size()!=reviewed.headers().size())throw new java.io.IOException("Cantidad de columnas diferente en fila "+reader.record());
                for(int i=0;i<options.mapping().size();i++){Mapping map=options.mapping().get(i);String value=row.get(map.source());if(value.equals(options.nullToken())||options.emptyNull()&&value.isEmpty())statement.setNull(i+1,map.target().type());else statement.setObject(i+1,convert(value,map.target().type()));}
                statement.addBatch();count++;if(++batch==200){statement.executeBatch();batch=0;progress.accept(count);}
            }
            if(batch>0)statement.executeBatch();control.check();connection.commit();progress.accept(count);return count;
        }catch(Exception error){try{connection.rollback();}catch(SQLException rollback){error.addSuppressed(rollback);}control.check();throw new SQLException("Importación detenida cerca del registro "+(count+1)+". El lote fue revertido. "+error.getMessage(),error);}
        finally{control.active(null);connection.setAutoCommit(true);}
    }
    private static Object convert(String value,int type){return switch(type){case Types.INTEGER,Types.BIGINT,Types.SMALLINT,Types.TINYINT->new java.math.BigDecimal(value.trim()).toBigIntegerExact();case Types.DECIMAL,Types.NUMERIC->new java.math.BigDecimal(value.trim());case Types.FLOAT,Types.REAL,Types.DOUBLE->new java.math.BigDecimal(value.trim());case Types.BOOLEAN,Types.BIT->{if(value.equalsIgnoreCase("true")||value.equals("1"))yield true;if(value.equalsIgnoreCase("false")||value.equals("0"))yield false;throw new IllegalArgumentException("Valor booleano inválido");}default->value;};}
}
