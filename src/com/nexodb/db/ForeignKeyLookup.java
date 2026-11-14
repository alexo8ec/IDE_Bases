package com.nexodb.db;

import static com.nexodb.util.I18n.tr;
import com.nexodb.model.DatabaseType;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Read-only navigation using actual JDBC origins and ordered FK mappings, never naming guesses. */
public final class ForeignKeyLookup {
    public record Part(String local,int column,String referenced) {}
    public record Reference(String name,String childCatalog,String childSchema,String childTable,String parentCatalog,String parentSchema,String parentTable,List<Part> parts) {
        public Reference {parts=List.copyOf(parts);}
        public boolean complete(){return parts.stream().allMatch(p->p.column()>=0);}
        public boolean contains(int column){return parts.stream().anyMatch(p->p.column()==column);}
        public String label(){return parentTable+" · "+Objects.toString(name,"");}
    }
    public record Page(QueryResult result,int offset,boolean exact) {}
    private record Table(String catalog,String schema,String name) {}
    private record Key(String name,Table parent) {}
    private record Mapping(int sequence,String local,String parent) {}
    private record Column(String name,int type) {}
    private record Parameter(Object value,int type) {}
    private ForeignKeyLookup() {}
    private static String clean(String s){return Objects.toString(s,"");}
    public static List<Reference> describe(Connection connection,QueryResult result)throws SQLException {
        synchronized(connection){
            List<QueryResult.Origin> origins=result.origins();
            if(origins.isEmpty()&&result.editing()!=null&&result.editing().target()!=null){var t=result.editing().target();origins=t.columns().stream().map(c->new QueryResult.Origin(t.table().catalog(),t.table().schema(),t.table().name(),c.sourceName())).toList();}
            Map<Table,List<Integer>> tables=new LinkedHashMap<>();
            for(int i=0;i<origins.size();i++){var o=origins.get(i);if(!clean(o.table()).isBlank())tables.computeIfAbsent(new Table(clean(o.catalog()),clean(o.schema()),o.table()),k->new ArrayList<>()).add(i);}
            List<Reference> refs=new ArrayList<>();
            for(var entry:tables.entrySet()){
                Table table=entry.getKey();Map<Key,List<Mapping>> groups=new LinkedHashMap<>();
                try(ResultSet r=connection.getMetaData().getImportedKeys(empty(table.catalog()),empty(table.schema()),table.name())){
                    while(r.next()){Key key=new Key(clean(r.getString("FK_NAME")),new Table(clean(r.getString("PKTABLE_CAT")),clean(r.getString("PKTABLE_SCHEM")),r.getString("PKTABLE_NAME")));groups.computeIfAbsent(key,k->new ArrayList<>()).add(new Mapping(r.getInt("KEY_SEQ"),r.getString("FKCOLUMN_NAME"),r.getString("PKCOLUMN_NAME")));}
                }
                for(var group:groups.entrySet()){
                    List<List<Mapping>> constraints=new ArrayList<>();List<Mapping> mappings=group.getValue();
                    long starts=mappings.stream().filter(p->p.sequence()==1).count();
                    if(group.getKey().name().isEmpty()&&starts>1){
                        // JDBC cannot distinguish unnamed composite constraints with interleaved rows.
                        // Never combine unrelated keys. Single-column unnamed FKs remain navigable.
                        if(mappings.stream().anyMatch(p->p.sequence()>1))continue;
                        for(Mapping m:mappings)constraints.add(List.of(m));
                    }else constraints.add(mappings);
                    for(List<Mapping> members:constraints){
                        List<Part> parts=new ArrayList<>();members=new ArrayList<>(members);members.sort(Comparator.comparingInt(Mapping::sequence));
                        for(Mapping m:members){int index=-1;for(int col:entry.getValue())if(clean(origins.get(col).column()).equals(m.local())){if(index>=0){index=-1;break;}index=col;}parts.add(new Part(m.local(),index,m.parent()));}
                        if(parts.stream().noneMatch(p->p.column()>=0))continue;
                        Table parent=group.getKey().parent();refs.add(new Reference(group.getKey().name(),table.catalog(),table.schema(),table.name(),parent.catalog(),parent.schema(),parent.name(),parts));
                    }
                }
            }
            return List.copyOf(refs);
        }
    }
    public static Page search(Connection connection,DatabaseType type,Reference reference,List<Object> row,int clicked,String text,String searchColumn,boolean matchOthers,int offset,int limit)throws SQLException {
        synchronized(connection){
            if(offset<0||limit<0||limit>10000)throw new IllegalArgumentException(tr("Límite o posición no válidos."));
            List<Column> columns=columns(connection,reference);String quote=connection.getMetaData().getIdentifierQuoteString().trim();
            if(!searchColumn.isEmpty()&&columns.stream().noneMatch(c->c.name().equals(searchColumn)))throw new IllegalArgumentException(tr("Columna de búsqueda no válida."));
            Part clickedPart=reference.parts().stream().filter(p->p.column()==clicked).findFirst().orElseThrow(()->new IllegalArgumentException(tr("Selecciona una columna relacionada.")));
            String input=Objects.toString(text,"");
            if(!input.isEmpty()){
                Column key=columns.stream().filter(c->c.name().equals(clickedPart.referenced())).findFirst().orElseThrow(()->new SQLException(tr("La columna referenciada ya no existe.")));
                Object value=exactValue(input,key.type());
                if(value!=null){List<Parameter> params=new ArrayList<>();List<String> terms=otherTerms(reference,row,clicked,matchOthers,columns,quote,params);terms.add(VisualQuery.quote(key.name(),quote)+" = ?");params.add(new Parameter(value,key.type()));QueryResult exact=read(connection,type,reference,terms,params,quote,offset,limit);if(!exact.rows().isEmpty())return new Page(exact,offset,true);}
            }
            List<Parameter> params=new ArrayList<>();List<String> terms=otherTerms(reference,row,clicked,matchOthers,columns,quote,params);
            if(!input.isEmpty()){
                List<String> likes=new ArrayList<>();String pattern="%"+input.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
                for(Column column:columns)if((searchColumn.isEmpty()||column.name().equals(searchColumn))&&searchable(column.type())){
                    String sqlType=switch(type){case MYSQL,MARIADB->"CHAR";case POSTGRESQL,SQLITE->"TEXT";case SQLSERVER->"NVARCHAR(MAX)";case ORACLE->"VARCHAR2(4000)";default->"VARCHAR(4000)";};
                    likes.add("LOWER(CAST("+VisualQuery.quote(column.name(),quote)+" AS "+sqlType+")) LIKE LOWER(?) ESCAPE '!'");params.add(new Parameter(pattern,Types.VARCHAR));
                }
                if(likes.isEmpty())terms.add("1=0");else terms.add("("+String.join(" OR ",likes)+")");
            }
            return new Page(read(connection,type,reference,terms,params,quote,offset,limit),offset,false);
        }
    }
    public static Map<Integer,Object> selection(Reference ref,QueryResult parent,int row){
        if(!ref.complete()||row<0||row>=parent.rows().size())throw new IllegalArgumentException(tr("La referencia completa no está disponible en este resultado."));
        Map<Integer,Object> values=new LinkedHashMap<>();for(Part part:ref.parts()){int column=parent.columns().indexOf(part.referenced());if(column<0)throw new IllegalArgumentException(tr("La columna referenciada ya no existe."));Object value=parent.rows().get(row).get(column);if(value==null||value instanceof String s&&(s.startsWith("<BINARIO:")||s.startsWith("<BLOB:")))throw new IllegalArgumentException(tr("Este tipo de referencia solo se puede consultar."));values.put(part.column(),value);}return values;
    }
    private static List<Column> columns(Connection c,Reference r)throws SQLException{
        List<Column> cols=new ArrayList<>();DatabaseMetaData m=c.getMetaData();String escape=m.getSearchStringEscape();String table=pattern(r.parentTable(),escape),schema=pattern(r.parentSchema(),escape);
        try(ResultSet rs=m.getColumns(empty(r.parentCatalog()),empty(schema),table,"%")){while(rs.next())cols.add(new Column(rs.getString("COLUMN_NAME"),rs.getInt("DATA_TYPE")));}
        if(cols.isEmpty())throw new SQLException(tr("No se pudo leer la tabla referenciada. Comprueba los permisos de lectura."));return cols;
    }
    private static String pattern(String s,String escape){s=clean(s);return escape==null||escape.isEmpty()?s:s.replace(escape,escape+escape).replace("%",escape+"%").replace("_",escape+"_");}
    private static List<String> otherTerms(Reference ref,List<Object> row,int clicked,boolean match,List<Column> columns,String quote,List<Parameter> params){
        List<String> terms=new ArrayList<>();if(!match)return terms;
        for(Part p:ref.parts())if(p.column()>=0&&p.column()!=clicked&&p.column()<row.size()){Object value=row.get(p.column());if(value==null||value==QueryEditSupport.DefaultValue.INSTANCE)continue;Column col=columns.stream().filter(c->c.name().equals(p.referenced())).findFirst().orElse(null);if(col==null)continue;terms.add(VisualQuery.quote(p.referenced(),quote)+" = ?");params.add(new Parameter(value,col.type()));}return terms;
    }
    private static QueryResult read(Connection c,DatabaseType type,Reference ref,List<String> terms,List<Parameter> params,String quote,int offset,int limit)throws SQLException{
        String namespace=clean(ref.parentSchema());if(namespace.isEmpty())namespace=clean(ref.parentCatalog());
        String table=VisualQuery.qualified(namespace,ref.parentTable(),quote);
        if(type==DatabaseType.SQLSERVER&&!clean(ref.parentCatalog()).isEmpty()&&!clean(ref.parentSchema()).isEmpty())table=VisualQuery.quote(ref.parentCatalog(),quote)+"."+table;
        String sql="SELECT * FROM "+table+(terms.isEmpty()?"":" WHERE "+String.join(" AND ",terms))+" ORDER BY "+String.join(", ",ref.parts().stream().map(p->VisualQuery.quote(p.referenced(),quote)).distinct().toList());
        if(limit>0){if(type==DatabaseType.SQLSERVER||type==DatabaseType.ORACLE){sql+=" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";params.add(new Parameter(offset,Types.INTEGER));params.add(new Parameter(limit+1,Types.INTEGER));}else{sql+=" LIMIT ? OFFSET ?";params.add(new Parameter(limit+1,Types.INTEGER));params.add(new Parameter(offset,Types.INTEGER));}}
        try(PreparedStatement s=c.prepareStatement(sql)){int i=1;for(Parameter p:params){if(p.type()==Types.OTHER)s.setObject(i++,p.value(),Types.OTHER);else s.setObject(i++,p.value(),p.type());}s.setMaxRows(limit>0?limit+1:0);try(ResultSet r=s.executeQuery()){return QueryResult.from(r,limit>0?limit:Integer.MAX_VALUE);}}
    }
    private static boolean searchable(int type){return switch(type){case Types.BLOB,Types.CLOB,Types.NCLOB,Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.ARRAY,Types.JAVA_OBJECT,Types.SQLXML,Types.OTHER->false;default->true;};}
    private static Object exactValue(String text,int type){try{return switch(type){case Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.BIGINT,Types.FLOAT,Types.REAL,Types.DOUBLE,Types.DECIMAL,Types.NUMERIC->new BigDecimal(text);case Types.DATE->java.sql.Date.valueOf(text);case Types.TIMESTAMP->java.sql.Timestamp.valueOf(text);case Types.BOOLEAN,Types.BIT->text.equals("1")||text.equalsIgnoreCase("true")?true:text.equals("0")||text.equalsIgnoreCase("false")?false:null;default->searchable(type)||type==Types.OTHER?text:null;};}catch(IllegalArgumentException invalid){return null;}}
    private static String empty(String s){return s==null||s.isEmpty()?null:s;}
}
