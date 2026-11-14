package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** MySQL/MariaDB programmable-object DDL, preserving namespace, stored SQL mode and source freshness. */
public final class StoredObjectService {
    public record Definition(String sql,String sqlMode,String charset,String collation) {
        public String hash(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((sql+"\0"+sqlMode+"\0"+charset+"\0"+collation).getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    }
    public record Header(int start,int end,String name) {}
    private StoredObjectService(){}
    public static boolean supported(DatabaseType type){return type==DatabaseType.MYSQL||type==DatabaseType.MARIADB;}
    public static SchemaItem.Kind objectKind(SchemaItem.Kind kind){return switch(kind){case VIEWS,VIEW->SchemaItem.Kind.VIEW;case PROCEDURES,PROCEDURE->SchemaItem.Kind.PROCEDURE;case FUNCTIONS,FUNCTION->SchemaItem.Kind.FUNCTION;case TRIGGERS,TRIGGER->SchemaItem.Kind.TRIGGER;case EVENTS,EVENT->SchemaItem.Kind.EVENT;default->null;};}
    public static String quote(String name){if(name==null||name.isBlank()||name.length()>64||name.indexOf('\0')>=0)throw new IllegalArgumentException("Nombre de objeto no válido (1 a 64 caracteres).");return "`"+name.replace("`","``")+"`";}
    public static String namespace(SchemaItem item){if(item.catalog()!=null&&!item.catalog().isBlank())return item.catalog();if(item.schema()!=null&&!item.schema().isBlank())return item.schema();if(item.kind()==SchemaItem.Kind.SCHEMA)return item.name();throw new IllegalArgumentException("Selecciona una base de datos.");}
    public static String qualified(SchemaItem item){return quote(namespace(item))+"."+quote(item.name());}
    public static Definition read(JdbcSession session,SchemaItem item)throws SQLException {
        requireEngine(session);String ddl="",mode="",charset="",collation="";
        synchronized(session.connection()){
            try(Statement statement=session.connection().createStatement();ResultSet rows=statement.executeQuery("SHOW CREATE "+item.kind().name()+" "+qualified(item))){
                if(!rows.next())throw new SQLException("El objeto no existe o no tienes acceso a su definición.");var meta=rows.getMetaData();
                for(int i=1;i<=meta.getColumnCount();i++){String name=meta.getColumnLabel(i).toLowerCase(Locale.ROOT),value=Objects.toString(rows.getString(i),"");if(name.startsWith("create ")||name.equals("sql original statement"))ddl=value;else if(name.equals("sql_mode"))mode=value;else if(name.equals("character_set_client"))charset=value;else if(name.equals("collation_connection"))collation=value;}
            }
            if(ddl.isBlank())throw new SQLException("El servidor no devolvió la definición completa. Revisa los permisos SHOW CREATE.");
            return new Definition(ddl,mode,charset,collation);
        }
    }
    public static Header header(String sql,SchemaItem.Kind kind){
        var tokens=SqlScriptTools.tokens(sql);
        if(tokens.isEmpty()||!tokens.get(0).is("CREATE"))throw new IllegalArgumentException("El editor requiere una única definición CREATE "+kind+".");
        for(int i=1;i+1<tokens.size();i++)if(tokens.get(i).is(kind.name())&&Character.isLetter(sql.charAt(tokens.get(i).start()))){
            var token=tokens.get(i+1);int end=token.end();String name=token.text();
            if(i+3<tokens.size()&&tokens.get(i+2).is(".")){end=tokens.get(i+3).end();name=tokens.get(i+3).text();}
            return new Header(token.start(),end,name);
        }
        throw new IllegalArgumentException("No se encontró el nombre CREATE "+kind+".");
    }
    public static String withName(String sql,SchemaItem.Kind kind,String qualified){Header h=header(sql,kind);return sql.substring(0,h.start())+qualified+sql.substring(h.end());}
    public static String template(SchemaItem item){String name=qualified(item);return switch(item.kind()){
        case VIEW->"CREATE VIEW "+name+" AS\nSELECT 1 AS id;\n";
        case PROCEDURE->"CREATE PROCEDURE "+name+"(IN p_id INT)\nBEGIN\n    DECLARE v_resultado INT DEFAULT 0;\n    SET v_resultado = p_id;\n    SELECT v_resultado AS resultado;\nEND";
        case FUNCTION->"CREATE FUNCTION "+name+"(p_valor INT)\nRETURNS INT\nDETERMINISTIC\nNO SQL\nBEGIN\n    RETURN p_valor;\nEND";
        case TRIGGER->"CREATE TRIGGER "+name+"\nBEFORE INSERT ON "+quote(namespace(item))+".`tabla_destino`\nFOR EACH ROW\nBEGIN\n    -- Sustituye tabla_destino y escribe la acción del trigger.\n    SET @nexodb_ejemplo_trigger = 1;\nEND";
        case EVENT->"CREATE EVENT "+name+"\nON SCHEDULE EVERY 1 DAY\nDISABLE\nDO\nBEGIN\n    -- El evento se crea deshabilitado. Completa la acción antes de habilitarlo.\n    DO 1;\nEND";
        default->throw new IllegalArgumentException("Tipo de objeto no soportado.");};}
    public static String exportSql(Definition definition){String delimiter="__NEXO_"+UUID.randomUUID().toString().replace("-","")+"__";return "-- SQL_MODE original: "+definition.sqlMode().replace('\n',' ')+"\nDELIMITER "+delimiter+"\n"+definition.sql().stripTrailing()+"\n"+delimiter+"\nDELIMITER ;\n";}
    public static String replacementNote(SchemaItem.Kind kind){return kind==SchemaItem.Kind.VIEW||kind==SchemaItem.Kind.EVENT?"Se aplicará ALTER conservando el nombre del objeto.":"MySQL requiere recrear este objeto para cambiar su cuerpo: DROP y CREATE no son atómicos. Revisa y exporta los permisos de rutina antes de continuar; pueden requerir reaplicación.";}
    public static void save(JdbcSession session,SchemaItem item,String edited,Definition original)throws SQLException {
        requireEngine(session);Header header=header(edited,item.kind());if(!header.name().equals(item.name()))throw new IllegalArgumentException("Usa Renombrar para cambiar el nombre del objeto.");
        String proposed=withName(edited,item.kind(),qualified(item));
        synchronized(session.connection()){
            requireWritable(session);String catalog=session.connection().getCatalog(),mode=scalar(session.connection(),"SELECT @@SESSION.sql_mode");boolean readOnly=session.getReadOnly();
            try{
                session.setReadOnly(false);session.connection().setCatalog(namespace(item));
                if(original!=null&&!read(session,item).hash().equals(original.hash()))throw new SQLException("El objeto cambió en el servidor. Vuelve a abrirlo para evitar sobrescribir cambios ajenos.");
                if(original!=null&&item.kind()!=SchemaItem.Kind.VIEW)setMode(session.connection(),original.sqlMode());
                if(original==null)exec(session.connection(),proposed);
                else if(item.kind()==SchemaItem.Kind.VIEW||item.kind()==SchemaItem.Kind.EVENT)exec(session.connection(),proposed.replaceFirst("(?i)^\\s*CREATE\\b","ALTER"));
                else{
                    preflight(session.connection(),item,proposed);
                    if(!read(session,item).hash().equals(original.hash()))throw new SQLException("El objeto cambió durante la validación. Vuelve a abrirlo.");
                    if(item.kind()==SchemaItem.Kind.TRIGGER)proposed=preserveTriggerOrder(session.connection(),item,proposed);
                    exec(session.connection(),"DROP "+item.kind()+" "+qualified(item));
                    try{exec(session.connection(),proposed);}
                    catch(SQLException error){try{exec(session.connection(),original.sql());}catch(SQLException restore){error.addSuppressed(restore);throw new SQLException("Falló CREATE y no se pudo restaurar la definición original. Usa la copia del editor para recuperarla. "+error.getMessage(),error);}throw new SQLException("Falló CREATE; se restauró la definición original. Revisa también los permisos de rutina. "+error.getMessage(),error);}
                }
            }finally{try{setMode(session.connection(),mode);}finally{try{session.connection().setCatalog(catalog);}finally{session.setReadOnly(readOnly);}}}
        }
    }
    private static void preflight(Connection connection,SchemaItem item,String proposed)throws SQLException {
        String stem="__nxddl_"+UUID.randomUUID().toString().replace("-","");String ns=quote(namespace(item))+".",probe=ns+quote(stem+"_r");boolean created=false,tableCreated=false;String table=ns+quote(stem+"_t");
        try{
            String test=withName(proposed,item.kind(),probe);
            if(item.kind()==SchemaItem.Kind.TRIGGER){var tokens=SqlScriptTools.tokens(test);Header h=header(test,item.kind());int at=-1;for(int i=0;i+1<tokens.size();i++)if(tokens.get(i).start()>=h.end()&&tokens.get(i).is("ON")){at=i+1;break;}if(at<0)throw new SQLException("Falta la tabla del trigger.");int end=tokens.get(at).end();if(at+2<tokens.size()&&tokens.get(at+1).is("."))end=tokens.get(at+2).end();String source=test.substring(tokens.get(at).start(),end);exec(connection,"CREATE TABLE "+table+" LIKE "+source);tableCreated=true;test=test.substring(0,tokens.get(at).start())+table+test.substring(end);
                tokens=SqlScriptTools.tokens(test);for(int i=0;i+4<tokens.size();i++)if(tokens.get(i).is("FOR")&&tokens.get(i+1).is("EACH")&&tokens.get(i+2).is("ROW")&&(tokens.get(i+3).is("FOLLOWS")||tokens.get(i+3).is("PRECEDES"))){test=test.substring(0,tokens.get(i+3).start())+test.substring(tokens.get(i+4).end());break;}
            }
            exec(connection,test);created=true;
        }finally{try{if(created)exec(connection,"DROP "+item.kind()+" "+probe);}finally{if(tableCreated)exec(connection,"DROP TABLE "+table);}}
    }
    private static String preserveTriggerOrder(Connection connection,SchemaItem item,String sql)throws SQLException {
        var tokens=SqlScriptTools.tokens(sql);int insertion=-1;for(int i=0;i+2<tokens.size();i++)if(tokens.get(i).is("FOR")&&tokens.get(i+1).is("EACH")&&tokens.get(i+2).is("ROW")){insertion=tokens.get(i+2).end();if(i+3<tokens.size()&&(tokens.get(i+3).is("FOLLOWS")||tokens.get(i+3).is("PRECEDES")))return sql;break;}if(insertion<0)return sql;
        String table,timing,event;int order;try(PreparedStatement s=connection.prepareStatement("SELECT EVENT_OBJECT_TABLE,ACTION_TIMING,EVENT_MANIPULATION,ACTION_ORDER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND TRIGGER_NAME=?")){s.setString(1,namespace(item));s.setString(2,item.name());try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("El trigger ya no existe.");table=r.getString(1);timing=r.getString(2);event=r.getString(3);order=r.getInt(4);}}
        Header header=header(sql,item.kind());String targetTable="",targetTiming="",targetEvent="";for(int i=0;i<tokens.size();i++)if(tokens.get(i).start()>=header.end()&&tokens.get(i).end()<insertion){if(tokens.get(i).is("BEFORE")||tokens.get(i).is("AFTER")){targetTiming=tokens.get(i).text();if(i+1<tokens.size())targetEvent=tokens.get(i+1).text();}if(tokens.get(i).is("ON")&&i+1<tokens.size()){targetTable=tokens.get(i+1).text();if(i+3<tokens.size()&&tokens.get(i+2).is("."))targetTable=tokens.get(i+3).text();}}
        if(!table.equals(targetTable)||!timing.equalsIgnoreCase(targetTiming)||!event.equalsIgnoreCase(targetEvent))return sql;
        String before=null,after=null;try(PreparedStatement s=connection.prepareStatement("SELECT TRIGGER_NAME,ACTION_ORDER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND EVENT_OBJECT_TABLE=? AND ACTION_TIMING=? AND EVENT_MANIPULATION=? AND TRIGGER_NAME<>? ORDER BY ACTION_ORDER")){s.setString(1,namespace(item));s.setString(2,table);s.setString(3,timing);s.setString(4,event);s.setString(5,item.name());try(ResultSet r=s.executeQuery()){while(r.next()){if(r.getInt(2)<order)before=r.getString(1);else if(after==null)after=r.getString(1);}}}
        String clause=before!=null?" FOLLOWS "+quote(before):after!=null?" PRECEDES "+quote(after):"";return sql.substring(0,insertion)+clause+sql.substring(insertion);
    }
    public static String dropSql(SchemaItem item){return "DROP "+item.kind()+" "+qualified(item);}
    public static String renameSql(SchemaItem item,String name){String target=quote(namespace(item))+"."+quote(name);return switch(item.kind()){case VIEW->"RENAME TABLE "+qualified(item)+" TO "+target;case EVENT->"ALTER EVENT "+qualified(item)+" RENAME TO "+target;default->throw new IllegalArgumentException("El motor no permite renombrar este tipo de objeto directamente.");};}
    public static void apply(JdbcSession session,String sql)throws SQLException {synchronized(session.connection()){requireEngine(session);requireWritable(session);boolean readOnly=session.getReadOnly();try{session.setReadOnly(false);exec(session.connection(),sql);}finally{session.setReadOnly(readOnly);}}}
    public static void requireWritable(JdbcSession session)throws SQLException {if(session.profile().readOnly())throw new SQLException("La conexión está protegida como Solo lectura.");if(!session.getAutoCommit())throw new SQLException("Finaliza la transacción y activa Auto-commit antes de modificar objetos.");}
    private static void requireEngine(JdbcSession session){if(!supported(session.profile().type()))throw new IllegalArgumentException("Estas acciones están disponibles para MySQL y MariaDB.");}
    public static String scalar(Connection connection,String sql)throws SQLException {try(Statement s=connection.createStatement();ResultSet r=s.executeQuery(sql)){return r.next()?r.getString(1):"";}}
    public static void exec(Connection connection,String sql)throws SQLException {try(Statement s=connection.createStatement()){s.execute(sql);}}
    public static void setMode(Connection connection,String mode)throws SQLException {try(PreparedStatement s=connection.prepareStatement("SET SESSION sql_mode=?")){s.setString(1,mode);s.execute();}}
}
