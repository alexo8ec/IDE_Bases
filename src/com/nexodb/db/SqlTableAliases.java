package com.nexodb.db;

import java.text.Normalizer;
import java.util.*;

/** Aliases are proposed only when accepting a SELECT table completion. Existing SQL is not rewritten. */
public final class SqlTableAliases {
    public record Edit(int start, int end, String insertion) {}
    private static final Set<String> CLAUSES = Set.of("SELECT","FROM","JOIN","LEFT","RIGHT","INNER","OUTER","FULL","CROSS","ON","USING","WHERE","GROUP","ORDER","HAVING","LIMIT","OFFSET","FETCH","FOR","UNION","EXCEPT","INTERSECT","WINDOW","QUALIFY","SET","VALUES","RETURNING","AS");
    private SqlTableAliases() {}

    public static String next(String table, Collection<String> existing) {
        String name = Normalizer.normalize(table, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String[] words = name.split("_+");
        if (words.length > 1 && words[0].length() <= 3) name = words[1];
        String base = name.codePoints().filter(Character::isLetter).mapToObj(cp -> new String(Character.toChars(cp)).toLowerCase(Locale.ROOT)).findFirst().orElse("t");
        Set<String> used = new HashSet<>(); existing.forEach(value -> used.add(value.toLowerCase(Locale.ROOT)));
        String alias = base;
        for (int number = 2; used.contains(alias); number++) alias = base + number;
        return alias;
    }

    /** Conservatively reserve names across the current statement, including nested SELECTs. */
    public static Set<String> used(String sql) {
        var tokens = QueryEditSupport.tokenize(sql);
        Set<String> used = new HashSet<>();
        boolean fromList = false;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).value().equals(";")) { used.clear(); fromList=false; continue; }
            if (tokens.get(i).keyword("ON") || tokens.get(i).keyword("USING") || tokens.get(i).keyword("WHERE") || tokens.get(i).keyword("GROUP") || tokens.get(i).keyword("ORDER") || tokens.get(i).keyword("HAVING")) fromList=false;
            if (!tokens.get(i).keyword("FROM") && !tokens.get(i).keyword("JOIN") && !(fromList && tokens.get(i).value().equals(","))) continue;
            fromList=true;
            int j = i + 1;
            if (j >= tokens.size() || !tokens.get(j).name()) continue;
            String table = tokens.get(j++).value();
            while (j + 1 < tokens.size() && tokens.get(j).value().equals(".") && tokens.get(j+1).name()) { table = tokens.get(j+1).value(); j += 2; }
            if (j < tokens.size() && tokens.get(j).keyword("AS")) j++;
            used.add(j < tokens.size() && alias(tokens.get(j)) ? tokens.get(j).value() : table);
        }
        return used;
    }

    public static Edit complete(String sql, int caret, String candidate, String quote) {
        if (caret < 0 || caret > sql.length()) return null;
        String prefix = sql.substring(0, caret);
        if (!SqlColumnCompletion.inCode(prefix)) return null;
        var tokens = QueryEditSupport.tokenize(prefix);
        int first = 0;
        for (int i = 0; i < tokens.size(); i++) if (tokens.get(i).value().equals(";")) first = i+1;
        if (first >= tokens.size() || !(tokens.get(first).keyword("SELECT") || tokens.get(first).keyword("WITH"))) return null;
        int source = -1;
        for (int i = tokens.size()-1; i >= first; i--) if (tokens.get(i).keyword("FROM") || tokens.get(i).keyword("JOIN")) { source = i; break; }
        if (source < 0 || tokens.get(source).end() == caret) return null;
        List<String> typed = new ArrayList<>();
        for (int j = source+1; j < tokens.size(); j++) {
            if ((j-source)%2 == 1) { if (!tokens.get(j).name()) return null; typed.add(tokens.get(j).value()); }
            else if (!tokens.get(j).value().equals(".")) return null;
        }
        var names = QueryEditSupport.tokenize(candidate);
        List<String> parts = new ArrayList<>();
        for (int j = 0; j < names.size(); j++) {
            if (j%2 == 0) { if (!names.get(j).name()) return null; parts.add(names.get(j).value()); }
            else if (!names.get(j).value().equals(".")) return null;
        }
        if (parts.isEmpty() || names.size()%2 == 0) return null;
        int namespaceParts = typed.size() - (tokens.get(tokens.size()-1).value().equals(".") ? 0 : 1);
        if (parts.size() == 1 && namespaceParts > 0) parts.addAll(0, typed.subList(0, namespaceParts));
        int start = source+1 < tokens.size() ? tokens.get(source+1).start() : caret;
        int end = caret;
        for (var token : QueryEditSupport.tokenize(sql)) if (token.start() < caret && token.end() > caret && token.name()) { end = token.end(); break; }
        String table = parts.get(parts.size()-1);
        String expression = String.join(".", parts.stream().map(value -> VisualQuery.quote(value, quote)).toList());
        var suffix = QueryEditSupport.tokenize(sql.substring(end));
        if (!suffix.isEmpty() && (suffix.get(0).keyword("AS") || alias(suffix.get(0)))) return new Edit(start,end,expression);
        Set<String> occupied = used(prefix.substring(0,tokens.get(source).start()) + " " + sql.substring(end));
        String identifier = next(table, occupied);
        // A previously typed table.column remains valid; do not silently invalidate it by renaming its qualifier.
        for (int i = first; i+1 < source; i++) if (tokens.get(i).name() && tokens.get(i).value().equalsIgnoreCase(table) && tokens.get(i+1).value().equals(".")) { identifier = table; break; }
        String alias = identifier.matches("[a-zA-Z_][a-zA-Z_0-9]*") ? identifier : VisualQuery.quote(identifier,quote);
        return new Edit(start,end,expression + " AS " + alias);
    }
    private static boolean alias(QueryEditSupport.Token token) { return token.name() && (token.identifier() || !CLAUSES.contains(token.value().toUpperCase(Locale.ROOT))); }
}
