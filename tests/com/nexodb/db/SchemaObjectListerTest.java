package com.nexodb.db;

import com.nexodb.MetadataFixture;
import com.nexodb.model.DatabaseType;
import java.lang.reflect.*;
import java.sql.ResultSet;

public final class SchemaObjectListerTest {
    public static void main(String[] args)throws Exception{
        if(!SchemaObjectLister.wanted("BASE TABLE",false)||!SchemaObjectLister.wanted("SYSTEM TABLE",false)||SchemaObjectLister.wanted("SYSTEM VIEW",false)||!SchemaObjectLister.wanted("SYSTEM VIEW",true))throw new AssertionError("JDBC table type compatibility is incomplete");
        InvocationHandler metadata=(proxy,method,values)->{if(method.getName().equals("getTables")){String[] types=(String[])values[3];if(types!=null)return MetadataFixture.rows(new String[]{"TABLE_CAT","TABLE_SCHEM","TABLE_NAME","TABLE_TYPE","REMARKS"});return MetadataFixture.rows(new String[]{"TABLE_CAT","TABLE_SCHEM","TABLE_NAME","TABLE_TYPE","REMARKS"},new Object[]{"db","","normal","BASE TABLE",""},new Object[]{"db","","system_table","SYSTEM TABLE",""},new Object[]{"db","","system_view","SYSTEM VIEW",""});}throw new AssertionError(method.getName());};
        JdbcSession session=MetadataFixture.session("metadata",DatabaseType.POSTGRESQL,metadata);var tables=SchemaObjectLister.list(session,"db",null,false);var views=SchemaObjectLister.list(session,"db",null,true);if(tables.size()!=2||views.size()!=1||!views.get(0).name().equals("system_view"))throw new AssertionError("Unfiltered metadata fallback did not restore hidden tables/views");
        System.out.println("SchemaObjectListerTest: OK (base, system and regular table/view types)");
    }
}
