package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.sql.*;
import java.util.*;

/** Reliable browser listing, including system schemas and drivers using BASE/SYSTEM TABLE names. */
public final class SchemaObjectLister {
    public record Item(String catalog,String schema,String name,String remarks) {}
    private SchemaObjectLister(){}
    public static List<Item> list(JdbcSession session,String catalog,String schema,boolean views)throws SQLException{
        LinkedHashMap<String,Item> found=new LinkedHashMap<>();DatabaseMetaData metadata=session.connection().getMetaData();
        String[] preferred=views?new String[]{"VIEW","SYSTEM VIEW"}:new String[]{"TABLE","BASE TABLE","SYSTEM TABLE","GLOBAL TEMPORARY","LOCAL TEMPORARY"};
        read(metadata,catalog,schema,preferred,views,found);
        // Several MySQL/MariaDB and generic JDBC drivers ignore system tables when a type filter is supplied.
        if(found.isEmpty())read(metadata,catalog,schema,null,views,found);
        if(found.isEmpty()&&(session.profile().type()==DatabaseType.MYSQL||session.profile().type()==DatabaseType.MARIADB))readMysqlInformationSchema(session,catalog!=null&&!catalog.isBlank()?catalog:schema,views,found);
        List<Item> result=new ArrayList<>(found.values());result.sort(Comparator.comparing(Item::name,String.CASE_INSENSITIVE_ORDER));return List.copyOf(result);
    }
    private static void read(DatabaseMetaData metadata,String catalog,String schema,String[] types,boolean views,Map<String,Item> found)throws SQLException{
        try(ResultSet rows=metadata.getTables(catalog,schema,"%",types)){while(rows.next()){String type=optional(rows,"TABLE_TYPE"),name=optional(rows,"TABLE_NAME");if(name.isBlank()||!wanted(type,views))continue;String cat=optional(rows,"TABLE_CAT"),sch=optional(rows,"TABLE_SCHEM");found.putIfAbsent((cat+"\0"+sch+"\0"+name).toLowerCase(Locale.ROOT),new Item(cat,sch,name,optional(rows,"REMARKS")));}}
    }
    private static void readMysqlInformationSchema(JdbcSession session,String database,boolean views,Map<String,Item> found)throws SQLException{
        if(database==null||database.isBlank())return;String sql="SELECT TABLE_CATALOG,TABLE_SCHEMA,TABLE_NAME,TABLE_TYPE,TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME";
        synchronized(session.connection()){try(PreparedStatement statement=session.connection().prepareStatement(sql)){statement.setString(1,database);try(ResultSet rows=statement.executeQuery()){while(rows.next()){String type=rows.getString("TABLE_TYPE");if(!wanted(type,views))continue;String cat=Objects.toString(rows.getString("TABLE_CATALOG"),""),sch=Objects.toString(rows.getString("TABLE_SCHEMA"),""),name=rows.getString("TABLE_NAME");found.putIfAbsent((cat+"\0"+sch+"\0"+name).toLowerCase(Locale.ROOT),new Item(cat,sch,name,Objects.toString(rows.getString("TABLE_COMMENT"),"")));}}}}
    }
    static boolean wanted(String type,boolean views){String value=Objects.toString(type,"").toUpperCase(Locale.ROOT);boolean view=value.contains("VIEW");return views?view:!view&&(value.contains("TABLE")||value.contains("TEMPORARY"));}
    private static String optional(ResultSet rows,String name){try{return Objects.toString(rows.getString(name),"");}catch(SQLException error){return "";}}
}
