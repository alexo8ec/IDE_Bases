package com.nexodb.util;

import java.util.*;

/** Small strict JSON reader for update manifests; no code execution or external dependencies. */
public final class Json {
    private final String source;
    private int cursor;
    private Json(String source) { this.source = Objects.requireNonNull(source); }

    public static Map<String, Object> object(String source) {
        Json parser = new Json(source.startsWith("\uFEFF") ? source.substring(1) : source);
        Object value = parser.value(0);
        parser.space();
        if (parser.cursor != parser.source.length() || !(value instanceof Map)) throw parser.invalid();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    public static String stringify(Object value) {
        if(value==null)return "null";
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number){String text=value.toString();if(!text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("Número JSON no válido.");return text;}
        if(value instanceof Map<?,?> map){var pairs=new ArrayList<String>();map.forEach((k,v)->pairs.add(stringify(k.toString())+":"+stringify(v)));return "{"+String.join(",",pairs)+"}";}
        if(value instanceof Iterable<?> list){var values=new ArrayList<String>();for(var item:list)values.add(stringify(item));return "["+String.join(",",values)+"]";}
        StringBuilder out=new StringBuilder("\"");String text=value.toString();
        for(int i=0;i<text.length();i++){char c=text.charAt(i);switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}}
        return out.append('"').toString();
    }

    private Object value(int depth) {
        if (depth > 32) throw invalid();
        space();
        if (cursor >= source.length()) throw invalid();
        char start = source.charAt(cursor);
        if (start == '"') return string();
        if (start == '{') {
            cursor++;
            Map<String, Object> values = new LinkedHashMap<>();
            if (take('}')) return values;
            do {
                space();
                String key = string();
                if (!take(':') || values.containsKey(key)) throw invalid();
                values.put(key, value(depth + 1));
            } while (take(','));
            if (!take('}')) throw invalid();
            return values;
        }
        if (start == '[') {
            cursor++;
            List<Object> values = new ArrayList<>();
            if (take(']')) return values;
            do { values.add(value(depth + 1)); } while (take(','));
            if (!take(']')) throw invalid();
            return values;
        }
        for (String literal : List.of("true", "false", "null")) {
            if (source.startsWith(literal, cursor)) {
                cursor += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int begin = cursor;
        while (cursor < source.length() && "-+0123456789.eE".indexOf(source.charAt(cursor)) >= 0) cursor++;
        String number = source.substring(begin, cursor);
        if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
        return new java.math.BigDecimal(number);
    }

    private String string() {
        if (cursor >= source.length() || source.charAt(cursor++) != '"') throw invalid();
        StringBuilder result = new StringBuilder();
        while (cursor < source.length()) {
            char c = source.charAt(cursor++);
            if (c == '"') return result.toString();
            if (c < 32) throw invalid();
            if (c == '\\') {
                if (cursor >= source.length()) throw invalid();
                c = source.charAt(cursor++);
                switch (c) {
                    case '"', '\\', '/' -> result.append(c);
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'u' -> {
                        if (cursor + 4 > source.length()) throw invalid();
                        try { result.append((char) Integer.parseInt(source.substring(cursor, cursor + 4), 16)); }
                        catch (NumberFormatException invalid) { throw invalid(); }
                        cursor += 4;
                    }
                    default -> throw invalid();
                }
            } else result.append(c);
        }
        throw invalid();
    }

    private boolean take(char expected) {
        space();
        if (cursor < source.length() && source.charAt(cursor) == expected) { cursor++; return true; }
        return false;
    }
    private void space() { while (cursor < source.length() && " \r\n\t".indexOf(source.charAt(cursor)) >= 0) cursor++; }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("El manifiesto JSON no es válido (posición " + cursor + ")."); }
}
