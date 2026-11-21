package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.List;

public final class MigrationServiceTest {
    public static void main(String[] args)throws Exception{
        var folder=Files.createTempDirectory("adisdb-migrations-");
        try{
            Files.writeString(folder.resolve("V1__crear_clientes.sql"),"CREATE TABLE clientes(id INT PRIMARY KEY, nombre VARCHAR(100));\n");
            Files.writeString(folder.resolve("V2__dato_inicial.sql"),"INSERT INTO clientes VALUES (1, 'Ana');\n");
            try(var connection=DriverManager.getConnection("jdbc:h2:mem:migrations;DB_CLOSE_DELAY=-1")){
                var initial=MigrationService.plan(connection,DatabaseType.H2,folder);
                equal(List.of(MigrationService.Status.PENDING,MigrationService.Status.PENDING),initial.stream().map(MigrationService.Plan::status).toList());
                var result=MigrationService.migrate(connection,DatabaseType.H2,folder,()->false);
                equal(2,result.applied());equal(List.of("1","2"),result.versions());
                try(var rows=connection.createStatement().executeQuery("SELECT nombre FROM clientes WHERE id=1")){equal(true,rows.next());equal("Ana",rows.getString(1));}
                equal(List.of(MigrationService.Status.APPLIED,MigrationService.Status.APPLIED),MigrationService.plan(connection,DatabaseType.H2,folder).stream().map(MigrationService.Plan::status).toList());
                equal(0,MigrationService.migrate(connection,DatabaseType.H2,folder,()->false).applied());
                Files.writeString(folder.resolve("V2__dato_inicial.sql"),"INSERT INTO clientes VALUES (2, 'Cambio');\n");
                equal(MigrationService.Status.CHANGED,MigrationService.plan(connection,DatabaseType.H2,folder).get(1).status());
                try{MigrationService.migrate(connection,DatabaseType.H2,folder,()->false);throw new AssertionError("Changed migration accepted");}catch(java.sql.SQLException expected){}
            }
            Files.writeString(folder.resolve("V1__duplicada.sql"),"SELECT 1;");
            try{MigrationService.discover(folder);throw new AssertionError("Duplicate version accepted");}catch(java.io.IOException expected){}
        }finally{try(var files=Files.walk(folder)){for(var path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("MigrationServiceTest: OK (version order, checksum drift, history and transactional execution)");
    }
    private static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+" got "+actual);}
}
