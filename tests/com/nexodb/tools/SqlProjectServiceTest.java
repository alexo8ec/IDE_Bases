package com.nexodb.tools;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class SqlProjectServiceTest {
    public static void main(String[] args)throws Exception{
        Path parent=Files.createTempDirectory("adisdb-sql-project-");SqlProjectService project=SqlProjectService.create(parent.resolve("ventas"),"Ventas");equal("Ventas",project.name());Path folder=project.createFolder(Path.of("reportes"));equal(Path.of("reportes"),folder);Path file=project.createSql(Path.of("reportes","mensual"));equal(Path.of("reportes","mensual.sql"),file);project.write(file,"SELECT 1;\n");equal("SELECT 1;\n",project.read(file).text());reject(()->project.write(Path.of("..","escape.sql"),"SELECT 1"));
        require(project.gitAvailable(),"Git is required for SQL project integration");ok(project.initGit());ok(project.configureIdentity("AdisDB Test","adisdb@example.invalid"));ok(project.stageAll());ok(project.commit("proyecto inicial"));equal(Map.of(),project.gitStatuses());
        project.write(file,"SELECT 2;\n");equal("M",project.gitStatuses().get("reportes/mensual.sql"));require(project.diff(file).contains("SELECT 2"),"Working-tree diff missing");require(project.history(file).contains("proyecto inicial"),"File history missing");ok(project.stage(file));require(project.gitSummary().output().contains("mensual.sql"),"Staged status missing");
        Path renamed=project.rename(file,"ventas_mes");equal(Path.of("reportes","ventas_mes.sql"),renamed);require(project.entries().stream().anyMatch(entry->entry.relative().equals(renamed)),"Renamed SQL missing");project.delete(renamed);equal(false,Files.exists(project.root().resolve(renamed)));project.delete(Path.of("reportes"));
        System.out.println("SqlProjectServiceTest: OK (safe project paths, atomic SQL files, Git init/status/diff/history/stage/commit and lifecycle)");
    }
    private static void ok(SqlProjectService.GitResult result){if(!result.ok())throw new AssertionError(result.output());}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void reject(Throwing work)throws Exception{try{work.run();throw new AssertionError("Unsafe path accepted");}catch(IOException expected){}}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
    @FunctionalInterface private interface Throwing{void run()throws Exception;}
}
