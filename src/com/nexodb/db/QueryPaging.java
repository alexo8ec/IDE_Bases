package com.nexodb.db;

import com.nexodb.util.SqlText;

/** Paging replays one read query only. CALL/EXEC and multi-statement batches are never replayed. */
public final class QueryPaging {
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
}
