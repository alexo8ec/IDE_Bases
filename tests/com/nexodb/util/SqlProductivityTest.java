package com.nexodb.util;

import java.nio.file.Files;
import java.util.List;

public final class SqlProductivityTest {
    public static void main(String[] args)throws Exception{
        String input="SELECT c.id, 'c.id' AS texto /* c.id */ FROM clientes c -- c.id\nWHERE c.id=1";
        String output=SqlRefactor.renameIdentifier(input,"c","cli");
        equal("SELECT cli.id, 'c.id' AS texto /* c.id */ FROM clientes cli -- c.id\nWHERE cli.id=1",output);
        try{SqlRefactor.renameIdentifier(input,"c","mal nombre");throw new AssertionError("Invalid identifier accepted");}catch(IllegalArgumentException expected){}
        var path=Files.createTempFile("adisdb-snippets-",".properties");try{var store=new SqlSnippetStore(path);var values=new java.util.ArrayList<>(store.list());values.add(new SqlSnippetStore.Snippet("Mi consulta","SELECT 42;",false));store.save(values);var loaded=store.list();equal(true,loaded.stream().anyMatch(s->s.name().equals("Mi consulta")&&s.sql().equals("SELECT 42;")));equal(true,loaded.stream().anyMatch(SqlSnippetStore.Snippet::builtIn));}finally{Files.deleteIfExists(path);}
        System.out.println("SqlProductivityTest: OK (safe rename and portable snippets)");
    }
    private static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+" got "+actual);}
}
