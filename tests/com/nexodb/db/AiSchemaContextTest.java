package com.nexodb.db;

import com.nexodb.MetadataFixture;
import com.nexodb.model.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static com.nexodb.MetadataFixture.equal;

public final class AiSchemaContextTest {
    public static void main(String[] args)throws Exception {
        List<String> patterns=new ArrayList<>();int[] closed={0};
        try(var session=MetadataFixture.session("ai",DatabaseType.MYSQL,(proxy,method,a)->switch(method.getName()) {
            case "storesUpperCaseIdentifiers","storesLowerCaseIdentifiers" -> false;
            case "getColumns" -> {
                equal("tenant_db",a[0]);equal(null,a[1]);String pattern=(String)a[2];patterns.add(pattern);
                String table=pattern.replace("\\_","_");
                String column=table.equals("ct_ventas")?"id_cliente":"identificacion";
                yield MetadataFixture.track(MetadataFixture.rows(new String[]{"COLUMN_NAME","TYPE_NAME","COLUMN_SIZE","DECIMAL_DIGITS","NULLABLE","ORDINAL_POSITION"},new Object[]{column,"BIGINT",19,0,0,1}),()->closed[0]++);
            }
            case "getPrimaryKeys" -> MetadataFixture.rows(new String[]{"KEY_SEQ","COLUMN_NAME"},new Object[]{1,a[2].equals("ct_clientes")?"identificacion":"id"});
            case "getIndexInfo" -> {equal(true,a[4]);yield MetadataFixture.rows(new String[]{"TYPE","INDEX_NAME","COLUMN_NAME","ASC_OR_DESC","ORDINAL_POSITION","NON_UNIQUE"},
                new Object[]{3,"ix_join","id_cliente","A",2,1},new Object[]{3,"ix_join","region","A",1,1});}
            case "getImportedKeys" -> MetadataFixture.rows(new String[]{"FK_NAME","FKCOLUMN_NAME","PKTABLE_SCHEM","PKTABLE_NAME","PKCOLUMN_NAME"},new Object[]{"fk_cliente","id_cliente",null,"ct_clientes","identificacion"});
            default -> throw new AssertionError("Unexpected operation "+method.getName());
        })) {
            var snapshot=AiSchemaContext.read(session,"tenant_db","SELECT * FROM `ct_ventas` AS v JOIN `ct_clientes` AS c ON v.id_cliente=c.identificacion WHERE 'JOIN secrets'='ignore'");
            equal(2,snapshot.tables());equal(2,snapshot.columns());equal(2,snapshot.indexes());equal(0,snapshot.warnings().size());equal(2,closed[0]);
            equal(List.of("ct\\_ventas","ct\\_clientes"),patterns);
            contains(snapshot.text(),"Motor: MySQL");contains(snapshot.text(),"identificacion BIGINT NOT NULL");contains(snapshot.text(),"PRIMARY KEY: identificacion");contains(snapshot.text(),"ix_join (region ASC, id_cliente ASC)");contains(snapshot.text(),"fk_cliente");
            patterns.clear();AiSchemaContext.read(session,"tenant_db","SELECT * FROM ct_ventas a, ct_clientes b WHERE a.id_cliente=b.identificacion");equal(2,patterns.size());
            patterns.clear();AiSchemaContext.read(session,"tenant_db","SELECT 'FROM ct_secret' /* JOIN ct_secret */");equal(List.of(),patterns);
            patterns.clear();AiSchemaContext.read(session,"tenant_db","WITH ct_clientes AS (SELECT * FROM ct_ventas) SELECT * FROM ct_clientes");equal(List.of("ct\\_ventas"),patterns);
        }
        try(var denied=MetadataFixture.session("denied",DatabaseType.MYSQL,(p,m,a)->switch(m.getName()){
            case "storesUpperCaseIdentifiers","storesLowerCaseIdentifiers" -> false;
            case "getColumns" -> throw new SQLException("private-password must not appear","42501",1142);
            default -> throw new AssertionError(m.getName());
        })) {
            var partial=AiSchemaContext.read(denied,"tenant_db","SELECT * FROM ct_ventas");equal(0,partial.tables());contains(partial.text(),"42501");contains(partial.text(),"permisos");
            if(partial.text().contains("private-password"))throw new AssertionError("Driver error leaked secrets");
        }
        var profile=new ConnectionProfile("ai-context","Test",DatabaseType.H2,"",0,"mem:ai_context","sa","jdbc:h2:mem:ai_context","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(var session=JdbcSession.open(profile,new char[0])) {
            session.execute("CREATE TABLE ct_clientes(identificacion BIGINT PRIMARY KEY, nombre VARCHAR(80) NOT NULL)",1);
            session.execute("CREATE TABLE ct_ventas(id BIGINT PRIMARY KEY, id_cliente BIGINT, FOREIGN KEY(id_cliente) REFERENCES ct_clientes(identificacion))",1);
            session.execute("CREATE INDEX ix_cliente ON ct_ventas(id_cliente)",1);
            var actual=AiSchemaContext.read(session,"","SELECT * FROM ct_ventas v JOIN ct_clientes c ON v.id_cliente=c.identificacion");
            equal(2,actual.tables());equal(4,actual.columns());equal(0,actual.warnings().size());contains(actual.text(),"IX_CLIENTE");contains(actual.text(),"FOREIGN KEY");contains(actual.text(),"PRIMARY KEY: IDENTIFICACION");
            var missing=AiSchemaContext.read(session,"","SELECT * FROM missing_table");equal(0,missing.tables());contains(missing.text(),"sin columnas visibles");
            Thread.currentThread().interrupt();try{AiSchemaContext.read(session,"","SELECT * FROM ct_clientes");throw new AssertionError("Cancellation ignored");}catch(InterruptedException expected){}finally{Thread.interrupted();}
        }
        System.out.println("AiSchemaContextTest: OK (real H2 metadata, MySQL quoting/namespaces, ordered indexes/PK/FK, comments/literals, comma joins, failure visibility, no data queries and cancellation)");
    }
    private static void contains(String value,String text){if(!value.contains(text))throw new AssertionError("Missing "+text+" in "+value);}
}
