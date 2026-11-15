package com.nexodb.util;

import java.util.Locale;

/**
 * Formateador SQL básico que normaliza espacios y saltos de línea,
 * respetando cadenas de texto y comentarios.
 */
public final class SqlFormatter {
    private SqlFormatter() {}

    public static String format(String sql) {
        if (sql == null || sql.isBlank()) return sql;
        
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = sql.length();
        boolean lastWasKeyword = false;
        
        while (i < len) {
            char c = sql.charAt(i);
            
            // Saltos de línea y espacios
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                // Comprimir espacios múltiples
                while (i < len && (sql.charAt(i) == ' ' || sql.charAt(i) == '\t' || sql.charAt(i) == '\n' || sql.charAt(i) == '\r')) {
                    i++;
                }
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ' && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append(' ');
                }
                continue;
            }
            
            // Comentarios de línea
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) end = len;
                sb.append(sql, i, end);
                i = end;
                continue;
            }
            
            // Comentarios de bloque
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) end = len;
                else end += 2;
                sb.append(sql, i, end);
                i = end;
                continue;
            }
            
            // Cadenas de texto
            if (c == '\'' || c == '"') {
                int end = i + 1;
                while (end < len) {
                    if (sql.charAt(end) == '\\') { end += 2; continue; }
                    if (sql.charAt(end) == c) {
                        if (end + 1 < len && sql.charAt(end + 1) == c) { end += 2; continue; }
                        end++;
                        break;
                    }
                    end++;
                }
                sb.append(sql, i, end);
                i = end;
                continue;
            }
            
            // Identificadores con backticks o corchetes
            if (c == '`' || c == '[') {
                char close = (c == '[') ? ']' : '`';
                int end = i + 1;
                while (end < len && sql.charAt(end) != close) end++;
                if (end < len) end++;
                sb.append(sql, i, end);
                i = end;
                continue;
            }
            
            // Palabras (keywords, identificadores, números)
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int end = i + 1;
                while (end < len && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_' || sql.charAt(end) == '$')) {
                    end++;
                }
                String word = sql.substring(i, end);
                
                // Si la palabra anterior era un keyword, añadir espacio
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ' && sb.charAt(sb.length() - 1) != '(' && sb.charAt(sb.length() - 1) != ',' && sb.charAt(sb.length() - 1) != '.') {
                    sb.append(' ');
                }
                
                sb.append(word);
                i = end;
                continue;
            }
            
            // Otros caracteres (operadores, paréntesis, etc.)
            sb.append(c);
            i++;
        }
        
        // Limpiar espacios finales
        String result = sb.toString().trim();
        
        // Normalizar espacios múltiples
        result = result.replaceAll("\\s+", " ");
        
        return result;
    }
}
