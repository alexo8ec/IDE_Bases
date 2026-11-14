package com.nexodb.util;

public final class SqlTextTest {
    public static void main(String[] args) throws Exception {
        assertEquals("SELECT 2", SqlText.currentStatement("SELECT 1;\nSELECT 2;\nSELECT 3", 17).trim());
        assertEquals("SELECT ';' AS value", SqlText.currentStatement("SELECT ';' AS value; SELECT 2", 8).trim());
        assertEquals("SELECT 1 -- ; ignored", SqlText.currentStatement("SELECT 1 -- ; ignored\n; SELECT 2", 5).trim());
        assertEquals("SELECT 1 /* ; */", SqlText.currentStatement("SELECT 1 /* ; */; SELECT 2", 5).trim());
        assertEquals("SELECT 2",SqlText.currentStatement("SELECT 1; SELECT 2;",18).trim());
        assertEquals("",SqlText.currentStatement("DELETE FROM x;\n",15).trim());
        SqlText.Batch batch = SqlText.splitCompleteStatements("SELECT ';' AS value;\nSELECT * FROM `ct;bancos`;\nSELECT 3");
        if (batch.statements().size() != 2) throw new AssertionError("Expected two complete statements: " + batch.statements());
        assertEquals("SELECT ';' AS value", batch.statements().get(0));
        assertEquals("SELECT * FROM `ct;bancos`", batch.statements().get(1));
        assertEquals("SELECT 3", batch.incompleteTail());
        var editor=new javax.swing.JTextArea("SELECT 1; SELECT 2");
        editor.setCaretPosition(0);
        assertEquals(editor.getText(),SqlText.selectedOrAll(editor));
        editor.select(10,18);
        assertEquals("SELECT 2",SqlText.selectedOrAll(editor));
        if (!SqlText.executionStatements(editor.getText()).equals(java.util.List.of("SELECT 1","SELECT 2"))) throw new AssertionError("Missing unterminated final statement");
        if (SqlText.executionStatements("SELECT ';'; -- comment only\n").size()!=1) throw new AssertionError("Comments must not execute");
        if (SqlText.executionStatements("SELECT [a;b]; SELECT $$a;b$$").size()!=2) throw new AssertionError("Quoted semicolons must not split statements");
        System.out.println("SqlTextTest: OK");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
    }
}

