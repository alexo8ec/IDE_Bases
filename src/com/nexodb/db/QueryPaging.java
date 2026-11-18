package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import com.nexodb.util.SqlText;
import java.util.List;

/** Paging replays one read query only. CALL/EXEC and multi-statement batches are never replayed. */
public final class QueryPaging {
    public record PageSql(String sql,boolean nativeOffset) {}
    private QueryPaging() {}
    public static boolean supported(String sql) {
        try {
            var statements=SqlText.executionStatements(sql);
            if(statements.size()!=1||!SqlExecutionPolicy.readOnly(sql))return false;
            var tokens=SqlScriptTools.tokens(sql);
            return !tokens.isEmpty()&&(tokens.get(0).is("SELECT")||tokens.get(0).is("WITH"));
        }catch(java.io.IOException error){return false;}
    }
    public static void validate(String sql,int size,long offset) {
        if(!supported(sql))throw new IllegalArgumentException("La paginación requiere una única consulta SELECT o WITH de lectura.");
        if(size<1||size>10_000||offset<0)throw new IllegalArgumentException("El tamaño de página debe estar entre 1 y 10.000 filas.");
    }

    /** Adds server-side paging only when it is safe to preserve the original query semantics. */
    public static PageSql pageSql(String sql,DatabaseType type,int size,long offset){
        validate(sql,size,offset);
        try{
            String statement=SqlText.executionStatements(sql).get(0).strip();
            List<SqlScriptTools.Token> tokens=SqlScriptTools.tokens(statement);
            List<String> top=topLevelWords(tokens);
            if(top.stream().anyMatch(java.util.Set.of("LIMIT","OFFSET","FETCH","TOP")::contains))return new PageSql(statement,false);
            long requested=(long)size+1L;
            return switch(type){
                case MYSQL,MARIADB,POSTGRESQL,SQLITE,H2 -> new PageSql(statement+" LIMIT "+requested+" OFFSET "+offset,true);
                case SQLSERVER,ORACLE -> containsSequence(top,"ORDER","BY")
                        ?new PageSql(statement+" OFFSET "+offset+" ROWS FETCH NEXT "+requested+" ROWS ONLY",true)
                        :new PageSql(statement,false);
                default -> new PageSql(statement,false);
            };
        }catch(Exception unavailable){return new PageSql(sql,false);}
    }

    private static List<String> topLevelWords(List<SqlScriptTools.Token> tokens){
        List<String> words=new java.util.ArrayList<>();int depth=0;
        for(SqlScriptTools.Token token:tokens){
            if(token.is("(")){depth++;continue;}if(token.is(")")){depth=Math.max(0,depth-1);continue;}
            if(depth==0&&token.name())words.add(token.text().toUpperCase(java.util.Locale.ROOT));
        }
        return words;
    }
    private static boolean containsSequence(List<String> words,String first,String second){
        for(int i=0;i+1<words.size();i++)if(first.equals(words.get(i))&&second.equals(words.get(i+1)))return true;
        return false;
    }
}
