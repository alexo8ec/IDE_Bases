package com.nexodb.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Small, portable and atomic user snippet library. */
public final class SqlSnippetStore {
    public record Snippet(String name,String sql,boolean builtIn) {public Snippet{if(name==null||name.isBlank())throw new IllegalArgumentException("El fragmento necesita un nombre.");sql=Objects.toString(sql,"");}}
    private final Path file;
    public SqlSnippetStore(Path file){this.file=file;}
    public static SqlSnippetStore configured(){return new SqlSnippetStore(AppSettings.file().resolveSibling("sql-snippets.properties"));}
    public List<Snippet> list(){
        List<Snippet> values=new ArrayList<>(builtIns());
        try{Properties p=AtomicProperties.read(file);int count=Math.min(500,Integer.parseInt(p.getProperty("count","0")));for(int i=0;i<count;i++){String name=p.getProperty(i+".name","").trim();if(!name.isBlank())values.add(new Snippet(name,p.getProperty(i+".sql",""),false));}}catch(Exception ignored){}
        return List.copyOf(values);
    }
    public void save(List<Snippet> snippets)throws IOException{
        List<Snippet> users=snippets.stream().filter(s->!s.builtIn()).toList();Properties p=new Properties();p.setProperty("count",Integer.toString(users.size()));for(int i=0;i<users.size();i++){p.setProperty(i+".name",users.get(i).name());p.setProperty(i+".sql",users.get(i).sql());}AtomicProperties.write(file,p);
    }
    private static List<Snippet> builtIns(){return List.of(
        new Snippet("SELECT paginado","SELECT ${columnas}\nFROM ${tabla}\nWHERE ${condicion}\nORDER BY ${orden};",true),
        new Snippet("INSERT seguro","INSERT INTO ${tabla} (${columnas})\nVALUES (${valores});",true),
        new Snippet("UPDATE con condición","UPDATE ${tabla}\nSET ${columna} = ${valor}\nWHERE ${condicion};",true),
        new Snippet("CTE","WITH ${nombre} AS (\n    SELECT ${columnas}\n    FROM ${tabla}\n)\nSELECT *\nFROM ${nombre};",true),
        new Snippet("Transacción","BEGIN;\n\n${sentencias}\n\nCOMMIT;",true));}
}
