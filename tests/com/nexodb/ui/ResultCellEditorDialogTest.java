package com.nexodb.ui;

import java.sql.Types;

public final class ResultCellEditorDialogTest {
    public static void main(String[] args) {
        equal(ResultCellEditorDialog.Kind.TEXT,ResultCellEditorDialog.kind(Types.LONGVARCHAR,"xml"));
        equal(ResultCellEditorDialog.Kind.TEXT,ResultCellEditorDialog.kind(Types.VARCHAR,"x".repeat(300)));
        equal(ResultCellEditorDialog.Kind.PLAIN,ResultCellEditorDialog.kind(Types.VARCHAR,"corto"));
        equal(ResultCellEditorDialog.Kind.DATE,ResultCellEditorDialog.kind(Types.DATE,"2026-11-15"));
        equal(ResultCellEditorDialog.Kind.TIME,ResultCellEditorDialog.kind(Types.TIME,"09:40:03"));
        equal(ResultCellEditorDialog.Kind.TIMESTAMP,ResultCellEditorDialog.kind(Types.TIMESTAMP,"2026-11-15 09:40:03"));
        equal("2026-11-15",ResultCellEditorDialog.normalizeTemporal(ResultCellEditorDialog.Kind.DATE,"2026-11-15"));
        equal("09:40:03",ResultCellEditorDialog.normalizeTemporal(ResultCellEditorDialog.Kind.TIME,"09:40:03"));
        equal("2026-11-15 09:40:03",ResultCellEditorDialog.normalizeTemporal(ResultCellEditorDialog.Kind.TIMESTAMP,"2026-11-15 09:40:03.000"));
        System.out.println("ResultCellEditorDialogTest: OK");
    }
    private static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
