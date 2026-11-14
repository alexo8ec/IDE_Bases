package com.nexodb.util;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.util.ArrayList;
import java.util.List;

public final class SqlText {
    public record Batch(List<String> statements, String incompleteTail) {}
    private SqlText() {}

    /** Run exactly the selection, or the entire buffer when no text is selected. */
    public static String selectedOrAll(JTextComponent editor) {
        String selected = editor.getSelectedText();
        return (selected == null || selected.isEmpty() ? editor.getText() : selected).trim();
    }

    public static List<String> executionStatements(String sql) throws java.io.IOException {
        List<String> statements = new ArrayList<>();
        try (var reader = new com.nexodb.db.SqlScriptReader(new java.io.StringReader(sql))) {
            for (var statement = reader.next(); statement != null; statement = reader.next()) statements.add(statement.sql());
        }
        return List.copyOf(statements);
    }

    public static String selectedOrCurrent(JTextComponent editor) {
        String selected = editor.getSelectedText();
        if (selected != null && !selected.isBlank()) return selected.trim();
        return currentStatement(editor.getText(), editor.getCaretPosition()).trim();
    }

    public static String currentStatement(String sql, int caret) {
        if (sql == null || sql.isEmpty()) return "";
        int safeCaret = Math.max(0, Math.min(caret, sql.length()));
        if(safeCaret==sql.length()&&safeCaret>0&&sql.charAt(safeCaret-1)==';')safeCaret--;
        int start = 0;
        int end = sql.length();
        boolean single = false;
        boolean doub = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char n = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (c == '*' && n == '/') { blockComment = false; i++; }
                continue;
            }
            if (!single && !doub && c == '-' && n == '-') { lineComment = true; i++; continue; }
            if (!single && !doub && c == '/' && n == '*') { blockComment = true; i++; continue; }
            if (!doub && c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) { single = !single; continue; }
            if (!single && c == '"' && (i == 0 || sql.charAt(i - 1) != '\\')) { doub = !doub; continue; }
            if (!single && !doub && c == ';') {
                if (i < safeCaret) start = i + 1;
                else { end = i; break; }
            }
        }
        return sql.substring(start, end);
    }

    public static Batch splitCompleteStatements(String sql) {
        if (sql == null || sql.isBlank()) return new Batch(List.of(), "");
        List<String> statements = new ArrayList<>();
        boolean single = false, doub = false, backtick = false, lineComment = false, blockComment = false;
        int start = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n' || c == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i++; }
                continue;
            }
            if (single) {
                if (c == '\\') { i++; continue; }
                if (c == '\'' && next == '\'') { i++; continue; }
                if (c == '\'') single = false;
                continue;
            }
            if (doub) {
                if (c == '\\') { i++; continue; }
                if (c == '"' && next == '"') { i++; continue; }
                if (c == '"') doub = false;
                continue;
            }
            if (backtick) {
                if (c == '`' && next == '`') { i++; continue; }
                if (c == '`') backtick = false;
                continue;
            }
            if (c == '-' && next == '-') { lineComment = true; i++; continue; }
            if (c == '#') { lineComment = true; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '\'') { single = true; continue; }
            if (c == '"') { doub = true; continue; }
            if (c == '`') { backtick = true; continue; }
            if (c == ';') {
                String statement = sql.substring(start, i).trim();
                if (!statement.isBlank()) statements.add(statement);
                start = i + 1;
            }
        }
        return new Batch(List.copyOf(statements), sql.substring(start).trim());
    }
}

