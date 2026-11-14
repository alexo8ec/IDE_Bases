package com.nexodb.db;

import java.util.*;

/** Conservative first line of protection; JDBC read-only is also applied when executing reads. */
public final class SqlExecutionPolicy {
    private static final Set<String> WRITES=Set.of("INSERT","UPDATE","DELETE","REPLACE","MERGE","CREATE","ALTER","DROP","TRUNCATE","GRANT","REVOKE","CALL","EXEC","EXECUTE","OUTFILE","DUMPFILE","LOCK","UNLOCK");
    private SqlExecutionPolicy(){}
    public static boolean readOnly(String sql){
        var tokens=SqlScriptTools.tokens(sql);if(tokens.isEmpty())return true;String first=tokens.get(0).text().toUpperCase(Locale.ROOT);
        if(Set.of("SHOW","DESCRIBE","DESC","USE").contains(first))return true;
        if(!Set.of("SELECT","WITH","EXPLAIN").contains(first))return false;
        for(var token:tokens){char c=sql.charAt(token.start());if(c=='`'||c=='"'||c=='\'')continue;String word=token.text().toUpperCase(Locale.ROOT);if(WRITES.contains(word)||word.equals("INTO")||first.equals("EXPLAIN")&&word.equals("ANALYZE"))return false;}
        return true;
    }
}
