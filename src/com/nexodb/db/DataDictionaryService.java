package com.nexodb.db;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.IntConsumer;

/** Read-only, dialect-neutral data dictionary built only from JDBC metadata. */
public final class DataDictionaryService {
    public record Column(String name,String type,int size,int scale,boolean nullable,String defaultValue,String autoIncrement,String remarks,boolean primaryKey){}
    public record Index(String name,boolean unique,List<String> columns){public Index{columns=List.copyOf(columns);}}
    public record Relation(String name,List<String> columns,String parent,List<String> parentColumns,String onUpdate,String onDelete){public Relation{columns=List.copyOf(columns);parentColumns=List.copyOf(parentColumns);}}
    public record Table(String catalog,String schema,String name,String type,String remarks,List<Column> columns,List<Index> indexes,List<Relation> relations,boolean detailed){
        public Table{columns=List.copyOf(columns);indexes=List.copyOf(indexes);relations=List.copyOf(relations);}
        public String qualified(){return schema==null||schema.isBlank()?name:schema+"."+name;}
    }
    public record Dictionary(String database,Instant generatedAt,List<Table> tables,List<String> warnings){public Dictionary{tables=List.copyOf(tables);warnings=List.copyOf(warnings);}}
    private record Positioned<T>(int position,T value){}
    private DataDictionaryService(){}

    public static Dictionary read(JdbcSession session,String database)throws SQLException,InterruptedException{
        synchronized(session.connection()){
            check();Connection connection=session.connection();DatabaseMetaData meta=connection.getMetaData();boolean catalogMode=SchemaMetadataReader.catalogNamespace(session.profile().type());String catalog=catalogMode?database:null,schema=catalogMode?null:pattern(database,meta.getSearchStringEscape());List<Table> tables=new ArrayList<>();List<String>warnings=new ArrayList<>();
            try(ResultSet rows=meta.getTables(catalog,schema,"%",new String[]{"TABLE","VIEW"})){
                while(rows.next()){check();String kind="VIEW".equalsIgnoreCase(rows.getString("TABLE_TYPE"))?"VIEW":"TABLE";tables.add(new Table(text(rows,"TABLE_CAT"),text(rows,"TABLE_SCHEM"),rows.getString("TABLE_NAME"),kind,text(rows,"REMARKS"),List.of(),List.of(),List.of(),false));}
            }
            Map<String,List<Positioned<Column>>> columns=new HashMap<>();for(Table table:tables)columns.put(key(table.catalog(),table.schema(),table.name()),new ArrayList<>());
            try(ResultSet rows=meta.getColumns(catalog,schema,"%","%")){
                while(rows.next()){check();List<Positioned<Column>> target=columns.get(key(text(rows,"TABLE_CAT"),text(rows,"TABLE_SCHEM"),rows.getString("TABLE_NAME")));if(target==null)continue;target.add(new Positioned<>(rows.getInt("ORDINAL_POSITION"),new Column(rows.getString("COLUMN_NAME"),rows.getString("TYPE_NAME"),rows.getInt("COLUMN_SIZE"),rows.getInt("DECIMAL_DIGITS"),rows.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,text(rows,"COLUMN_DEF"),text(rows,"IS_AUTOINCREMENT"),text(rows,"REMARKS"),false)));}
            }catch(SQLException error){warnings.add("Algunos comentarios o columnas no pudieron leerse: "+state(error));}
            List<Table> completed=new ArrayList<>();for(Table table:tables){List<Column> ordered=columns.getOrDefault(key(table.catalog(),table.schema(),table.name()),List.of()).stream().sorted(Comparator.comparingInt(Positioned::position)).map(Positioned::value).toList();completed.add(new Table(table.catalog(),table.schema(),table.name(),table.type(),table.remarks(),ordered,List.of(),List.of(),false));}
            completed.sort(Comparator.comparing(Table::qualified,String.CASE_INSENSITIVE_ORDER));return new Dictionary(database,Instant.now(),completed,warnings);
        }
    }

    public static Table details(JdbcSession session,Table table)throws SQLException,InterruptedException{
        if(table.detailed()||!"TABLE".equals(table.type()))return new Table(table.catalog(),table.schema(),table.name(),table.type(),table.remarks(),table.columns(),List.of(),List.of(),true);
        synchronized(session.connection()){
            check();DatabaseMetaData meta=session.connection().getMetaData();Set<String> primary=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);try(ResultSet rows=meta.getPrimaryKeys(blank(table.catalog()),blank(table.schema()),table.name())){while(rows.next()){check();primary.add(rows.getString("COLUMN_NAME"));}}
            List<Column> columns=table.columns().stream().map(c->new Column(c.name(),c.type(),c.size(),c.scale(),c.nullable(),c.defaultValue(),c.autoIncrement(),c.remarks(),primary.contains(c.name()))).toList();
            Map<String,List<Positioned<String>>> indexColumns=new LinkedHashMap<>();Map<String,Boolean> unique=new HashMap<>();try(ResultSet rows=meta.getIndexInfo(blank(table.catalog()),blank(table.schema()),table.name(),false,false)){while(rows.next()){check();String name=text(rows,"INDEX_NAME"),column=text(rows,"COLUMN_NAME");if(name.isBlank()||column.isBlank())continue;indexColumns.computeIfAbsent(name,k->new ArrayList<>()).add(new Positioned<>(rows.getInt("ORDINAL_POSITION"),column+("D".equalsIgnoreCase(text(rows,"ASC_OR_DESC"))?" DESC":"")));unique.put(name,!rows.getBoolean("NON_UNIQUE"));}}
            List<Index> indexes=indexColumns.entrySet().stream().map(entry->new Index(entry.getKey(),unique.getOrDefault(entry.getKey(),false),entry.getValue().stream().sorted(Comparator.comparingInt(Positioned::position)).map(Positioned::value).toList())).toList();
            record RelationPart(int sequence,String local,String parentColumn){}record RelationHead(String name,String parent,String update,String delete){}Map<RelationHead,List<RelationPart>> relationRows=new LinkedHashMap<>();try(ResultSet rows=meta.getImportedKeys(blank(table.catalog()),blank(table.schema()),table.name())){while(rows.next()){check();String parent=join(text(rows,"PKTABLE_CAT"),text(rows,"PKTABLE_SCHEM"),rows.getString("PKTABLE_NAME"));RelationHead head=new RelationHead(text(rows,"FK_NAME"),parent,rule(rows.getShort("UPDATE_RULE")),rule(rows.getShort("DELETE_RULE")));relationRows.computeIfAbsent(head,k->new ArrayList<>()).add(new RelationPart(rows.getInt("KEY_SEQ"),rows.getString("FKCOLUMN_NAME"),rows.getString("PKCOLUMN_NAME")));}}
            List<Relation> relations=new ArrayList<>();for(var entry:relationRows.entrySet()){List<RelationPart> parts=entry.getValue().stream().sorted(Comparator.comparingInt(RelationPart::sequence)).toList();relations.add(new Relation(entry.getKey().name(),parts.stream().map(RelationPart::local).toList(),entry.getKey().parent(),parts.stream().map(RelationPart::parentColumn).toList(),entry.getKey().update(),entry.getKey().delete()));}
            return new Table(table.catalog(),table.schema(),table.name(),table.type(),table.remarks(),columns,indexes,relations,true);
        }
    }

    public static Dictionary complete(JdbcSession session,String database,IntConsumer progress)throws SQLException,InterruptedException{
        Dictionary summary=read(session,database);List<Table> tables=new ArrayList<>();int total=Math.max(1,summary.tables().size());for(int i=0;i<summary.tables().size();i++){check();tables.add(details(session,summary.tables().get(i)));if(progress!=null)progress.accept((i+1)*100/total);}return new Dictionary(database,Instant.now(),tables,summary.warnings());
    }
    public static String markdown(Dictionary dictionary){StringBuilder out=new StringBuilder("# Diccionario de datos · ").append(dictionary.database()).append("\n\nGenerado: ").append(dictionary.generatedAt()).append("\n\n");for(Table table:dictionary.tables()){out.append("## ").append(table.qualified()).append(" · ").append(table.type()).append("\n\n");if(!table.remarks().isBlank())out.append(escape(table.remarks())).append("\n\n");out.append("| Columna | Tipo | NULL | Predeterminado | PK | Auto | Comentario |\n|---|---|---:|---|---:|---:|---|\n");for(Column column:table.columns())out.append('|').append(escape(column.name())).append('|').append(escape(type(column))).append('|').append(column.nullable()?"Sí":"No").append('|').append(escape(column.defaultValue())).append('|').append(column.primaryKey()?"Sí":"").append('|').append("YES".equalsIgnoreCase(column.autoIncrement())?"Sí":"").append('|').append(escape(column.remarks())).append("|\n");if(!table.indexes().isEmpty()){out.append("\n**Índices**\n\n");for(Index index:table.indexes())out.append("- ").append(escape(index.name())).append(index.unique()?" · UNIQUE":"").append(": ").append(escape(String.join(", ",index.columns()))).append('\n');}if(!table.relations().isEmpty()){out.append("\n**Relaciones**\n\n");for(Relation relation:table.relations())out.append("- ").append(escape(relation.name())).append(": ").append(escape(String.join(", ",relation.columns()))).append(" → ").append(escape(relation.parent())).append(" (").append(escape(String.join(", ",relation.parentColumns()))).append(") · UPDATE ").append(relation.onUpdate()).append(" · DELETE ").append(relation.onDelete()).append('\n');}out.append('\n');}return out.toString();}
    public static String csv(Dictionary dictionary){StringBuilder out=new StringBuilder("tabla,tipo_tabla,columna,tipo_dato,permite_null,predeterminado,clave_primaria,autoincremento,comentario_tabla,comentario_columna\r\n");for(Table table:dictionary.tables())for(Column column:table.columns())out.append(csv(table.qualified())).append(',').append(csv(table.type())).append(',').append(csv(column.name())).append(',').append(csv(type(column))).append(',').append(column.nullable()).append(',').append(csv(column.defaultValue())).append(',').append(column.primaryKey()).append(',').append("YES".equalsIgnoreCase(column.autoIncrement())).append(',').append(csv(table.remarks())).append(',').append(csv(column.remarks())).append("\r\n");return out.toString();}
    private static String type(Column column){String size=column.size()>0&&(column.type().toUpperCase(Locale.ROOT).contains("CHAR")||column.type().toUpperCase(Locale.ROOT).contains("BINARY")||Set.of("DECIMAL","NUMERIC").contains(column.type().toUpperCase(Locale.ROOT)))?"("+column.size()+(column.scale()>0?","+column.scale():"")+")":"";return column.type()+size;}
    private static String rule(short value){return switch(value){case DatabaseMetaData.importedKeyCascade->"CASCADE";case DatabaseMetaData.importedKeySetNull->"SET NULL";case DatabaseMetaData.importedKeySetDefault->"SET DEFAULT";case DatabaseMetaData.importedKeyRestrict->"RESTRICT";case DatabaseMetaData.importedKeyNoAction->"NO ACTION";default->"UNKNOWN";};}
    private static String key(String catalog,String schema,String table){return (Objects.toString(catalog,"")+"\u0000"+Objects.toString(schema,"")+"\u0000"+Objects.toString(table,"")).toLowerCase(Locale.ROOT);}
    private static String pattern(String value,String escape){if(value==null||escape==null||escape.isEmpty())return value;return value.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");}
    private static String text(ResultSet rows,String column){try{return Objects.toString(rows.getString(column),"");}catch(SQLException ignored){return "";}}
    private static String state(SQLException error){return Objects.toString(error.getSQLState(),"sin estado")+" / "+error.getErrorCode();}
    private static String blank(String value){return value==null||value.isBlank()?null:value;}
    private static String join(String...parts){return Arrays.stream(parts).filter(value->value!=null&&!value.isBlank()).reduce((a,b)->a+"."+b).orElse("");}
    private static String escape(String value){return Objects.toString(value,"").replace("|","\\|").replace("\r"," ").replace("\n","<br>");}
    private static String csv(String value){String text=Objects.toString(value,"");return '"'+text.replace("\"","\"\"")+'"';}
    private static void check()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Lectura del diccionario cancelada.");}
}
