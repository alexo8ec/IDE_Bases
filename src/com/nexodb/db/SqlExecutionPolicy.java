package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative first line of protection; JDBC read-only is also applied when executing reads. */
public final class SqlExecutionPolicy {
    private static final Set<String> WRITES=Set.of("INSERT","UPDATE","DELETE","REPLACE","MERGE","CREATE","ALTER","DROP","TRUNCATE","GRANT","REVOKE","CALL","EXEC","EXECUTE","OUTFILE","DUMPFILE","LOCK","UNLOCK");
    private static final String TRIVIA = "(?:[ \\t\\r\\n\\f]++|#[^\\r\\n]*+|--(?=[ \\t\\r\\n\\f]|\\z)[^\\r\\n]*+|(?s:/\\*(?![!+]|[mM]!).*?\\*/))*+";
    private static final String USER_VARIABLE = "@[a-z_][a-z0-9_$]*+";
    // Values with no SQL expressions or escape rules that depend on the server's sql_mode.
    private static final String VALUE = "(?:'(?:''|[^'\\\\])*+'|[+-]?(?:[0-9]++(?:\\.[0-9]*+)?|\\.[0-9]++)(?:e[+-]?[0-9]++)?|NULL|TRUE|FALSE|"+USER_VARIABLE+")";
    private static final Pattern SESSION_SET = Pattern.compile(TRIVIA+"SET"+TRIVIA, Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGNMENT = Pattern.compile(TRIVIA+USER_VARIABLE+TRIVIA+"(?::=|=)"+TRIVIA+VALUE+TRIVIA, Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_TRIVIA = Pattern.compile(TRIVIA);
    private SqlExecutionPolicy(){}
    public static boolean readOnly(String sql){
        var tokens=SqlScriptTools.tokens(sql);if(tokens.isEmpty())return true;String first=tokens.get(0).text().toUpperCase(Locale.ROOT);
        if(Set.of("SHOW","DESCRIBE","DESC","USE").contains(first))return true;
        if(!Set.of("SELECT","WITH","EXPLAIN").contains(first))return false;
        for(var token:tokens){char c=sql.charAt(token.start());if(c=='`'||c=='"'||c=='\'')continue;String word=token.text().toUpperCase(Locale.ROOT);if(WRITES.contains(word)||word.equals("INTO")||first.equals("EXPLAIN")&&word.equals("ANALYZE"))return false;}
        return true;
    }

    public static boolean readOnly(String sql, DatabaseType type) {
        return readOnly(sql) || sessionVariableAssignment(sql, type);
    }

    public static boolean sessionVariableAssignment(String sql, DatabaseType type) {
        if (type != DatabaseType.MYSQL && type != DatabaseType.MARIADB) return false;
        var prefix = SESSION_SET.matcher(sql);
        if (!prefix.lookingAt()) return false;

        var assignment = ASSIGNMENT.matcher(sql);
        int start = prefix.end(), end = sql.length();
        while (assignment.region(start, end).lookingAt()) {
            int next = assignment.end();
            if (next == end) return true;
            char separator = sql.charAt(next);
            if (separator == ';') return TRAILING_TRIVIA.matcher(sql).region(next + 1, end).matches();
            if (separator != ',') return false;
            start = next + 1;
        }
        return false;
    }

    /** Detects connection-local references outside literals and ordinary comments. */
    public static boolean referencesSessionVariables(String sql) {
        if (sql.indexOf('@') < 0) return false;
        var tokens = SqlScriptTools.tokens(sql);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            var token = tokens.get(i);
            var next = tokens.get(i + 1);
            if (sql.charAt(token.start()) != '@' || token.end() != next.start()) continue;
            char first = sql.charAt(next.start());
            if (next.name() || first == '\'' || first == '@') return true;
        }
        return false;
    }
}
