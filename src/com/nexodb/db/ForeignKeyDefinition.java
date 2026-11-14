package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.sql.*;
import java.util.*;
import static com.nexodb.util.I18n.tr;

/** Ordered mappings belong to one constraint, including composite and self-referencing keys. */
public record ForeignKeyDefinition(String database,String table,String name,String parentDatabase,String parentTable,
                                   List<ColumnPair> columns,String onDelete,String onUpdate) {
    public record ColumnPair(String local,String referenced) {}
    public ForeignKeyDefinition {columns=List.copyOf(columns);}
    public String key(){return database+"\0"+table+"\0"+name;}
    private String addClause(String quote){
        if(name==null||name.isBlank()||name.codePointCount(0,name.length())>64||columns.isEmpty())throw new IllegalArgumentException(tr("Completa el nombre y las columnas de la relación (máximo 64 caracteres)."));
        Set<String> local=new HashSet<>(),parent=new HashSet<>();
        for(ColumnPair pair:columns){
            if(pair.local()==null||pair.local().isBlank()||pair.referenced()==null||pair.referenced().isBlank()||!local.add(pair.local())||!parent.add(pair.referenced()))throw new IllegalArgumentException(tr("Selecciona columnas distintas y completas en cada fila de la relación."));
        }
        for(String action:List.of(onDelete,onUpdate))if(!Set.of("NO ACTION","RESTRICT","CASCADE","SET NULL").contains(action))throw new IllegalArgumentException(tr("Acción referencial no válida."));
        return "ADD CONSTRAINT "+VisualQuery.quote(name,quote)+" FOREIGN KEY ("+String.join(", ",columns.stream().map(p->VisualQuery.quote(p.local(),quote)).toList())+")\nREFERENCES "+VisualQuery.qualified(parentDatabase,parentTable,quote)+" ("+String.join(", ",columns.stream().map(p->VisualQuery.quote(p.referenced(),quote)).toList())+")\nON DELETE "+onDelete+" ON UPDATE "+onUpdate;
    }
    public String createSql(String quote){return "ALTER TABLE "+VisualQuery.qualified(database,table,quote)+"\n"+addClause(quote)+";\n";}
    public String dropSql(DatabaseType type,String quote){return "ALTER TABLE "+VisualQuery.qualified(database,table,quote)+"\n"+dropClause(type,quote)+";\n";}
    private String dropClause(DatabaseType type,String quote){
        if(name==null||name.isBlank())throw new IllegalArgumentException(tr("La relación no tiene un nombre disponible."));
        return (type==DatabaseType.MYSQL||type==DatabaseType.MARIADB?"DROP FOREIGN KEY ":"DROP CONSTRAINT ")+VisualQuery.quote(name,quote);
    }
    public String replaceSql(ForeignKeyDefinition previous,DatabaseType type,String quote){
        if(!Objects.equals(database,previous.database)||!table.equals(previous.table))throw new IllegalArgumentException(tr("No se puede cambiar la tabla hija al modificar una relación."));
        // One MySQL ALTER avoids a separate destructive DROP before a possibly invalid ADD.
        if(type==DatabaseType.MYSQL||type==DatabaseType.MARIADB)return "ALTER TABLE "+VisualQuery.qualified(database,table,quote)+"\n"+previous.dropClause(type,quote)+",\n"+addClause(quote)+";\n";
        throw new IllegalArgumentException(tr("La modificación visual de relaciones está disponible para MySQL y MariaDB."));
    }
    public static List<ForeignKeyDefinition> read(Connection connection,DatabaseType type,String database,String table)throws SQLException{
        boolean catalogs=SchemaMetadataReader.catalogNamespace(type);
        record Header(String name,String parentDatabase,String parent,String delete,String update){}
        Map<Header,SortedMap<Integer,ColumnPair>> keys=new LinkedHashMap<>();
        try(ResultSet rows=connection.getMetaData().getImportedKeys(catalogs?database:null,catalogs?null:database,table)){
            while(rows.next()){
                String parentDatabase=rows.getString(catalogs?"PKTABLE_CAT":"PKTABLE_SCHEM");
                Header header=new Header(rows.getString("FK_NAME"),parentDatabase,rows.getString("PKTABLE_NAME"),action(rows.getInt("DELETE_RULE")),action(rows.getInt("UPDATE_RULE")));
                keys.computeIfAbsent(header,k->new TreeMap<>()).put(rows.getInt("KEY_SEQ"),new ColumnPair(rows.getString("FKCOLUMN_NAME"),rows.getString("PKCOLUMN_NAME")));
            }
        }
        return keys.entrySet().stream().map(e->new ForeignKeyDefinition(database,table,e.getKey().name(),e.getKey().parentDatabase(),e.getKey().parent(),new ArrayList<>(e.getValue().values()),e.getKey().delete(),e.getKey().update())).toList();
    }
    private static String action(int rule){return switch(rule){case DatabaseMetaData.importedKeyCascade->"CASCADE";case DatabaseMetaData.importedKeySetNull->"SET NULL";case DatabaseMetaData.importedKeyRestrict->"RESTRICT";case DatabaseMetaData.importedKeyNoAction->"NO ACTION";default->"SET DEFAULT";};}
    /** Confirmed DDL must never implicitly commit an editor transaction or leave its read-only state changed. */
    public static void apply(JdbcSession session,String sql)throws Exception{
        if(session.profile().readOnly())throw new SQLException(tr("La conexión es de solo lectura."));
        synchronized(session.connection()){
            if(!session.getAutoCommit())throw new SQLException(tr("Finaliza la transacción y activa Auto-commit antes de modificar relaciones."));
            boolean readOnly=session.getReadOnly();
            try{if(readOnly)session.setReadOnly(false);session.execute(sql,1);}
            finally{if(readOnly)session.setReadOnly(true);}
        }
    }
}
