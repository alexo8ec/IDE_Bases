package com.nexodb.util;

public final class SqlTextTest {
    public static void main(String[] args) {
        assertEquals("SELECT 2", SqlText.currentStatement("SELECT 1;\nSELECT 2;\nSELECT 3", 17).trim());
        assertEquals("SELECT ';' AS value", SqlText.currentStatement("SELECT ';' AS value; SELECT 2", 8).trim());
        assertEquals("SELECT 1 -- ; ignored", SqlText.currentStatement("SELECT 1 -- ; ignored\n; SELECT 2", 5).trim());
        assertEquals("SELECT 1 /* ; */", SqlText.currentStatement("SELECT 1 /* ; */; SELECT 2", 5).trim());
        SqlText.Batch batch = SqlText.splitCompleteStatements("SELECT ';' AS value;\nSELECT * FROM `ct;bancos`;\nSELECT 3");
        if (batch.statements().size() != 2) throw new AssertionError("Expected two complete statements: " + batch.statements());
        assertEquals("SELECT ';' AS value", batch.statements().get(0));
        assertEquals("SELECT * FROM `ct;bancos`", batch.statements().get(1));
        assertEquals("SELECT 3", batch.incompleteTail());
        System.out.println("SqlTextTest: OK");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
    }
}

