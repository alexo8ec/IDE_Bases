package com.nexodb.ui;

import java.sql.Types;

public final class ResultCellEditorDialogTest {
    public static void main(String[] args) throws Exception {
        equal(ResultCellEditorDialog.Kind.TEXT,ResultCellEditorDialog.kind(Types.LONGVARCHAR,"xml"));
        equal(ResultCellEditorDialog.Kind.TEXT,ResultCellEditorDialog.kind(Types.VARCHAR,"x".repeat(300)));
        equal(ResultCellEditorDialog.Kind.PLAIN,ResultCellEditorDialog.kind(Types.VARCHAR,"corto"));
        equal(ResultCellEditorDialog.Kind.DATE,ResultCellEditorDialog.kind(Types.DATE,"2026-11-15"));
        equal(ResultCellEditorDialog.Kind.TIME,ResultCellEditorDialog.kind(Types.TIME,"09:40:03"));
        equal(ResultCellEditorDialog.Kind.TIMESTAMP,ResultCellEditorDialog.kind(Types.TIMESTAMP,"2026-11-15 09:40:03"));
        equal("2026-11-15",ResultCellEditorDialog.normalizeTemporal(ResultCellEditorDialog.Kind.DATE,"2026-11-15"));
        equal("09:40:03",ResultCellEditorDialog.normalizeTemporal(ResultCellEditorDialog.Kind.TIME,"09:40:03"));
        equal("2026-11-15 09:40:03",ResultCellEditorDialog.normalizeTemporal(ResultCellEditorDialog.Kind.TIMESTAMP,"2026-11-15 09:40:03.000"));
        String original="<root>\r\n  <value>1</value>\r\n</root>";
        equal(original,ResultCellEditorDialog.editedText(original,"<root>\n  <value>1</value>\n</root>",false));
        equal("<root>\r\n  <value>2</value>\r\n</root>",ResultCellEditorDialog.editedText(original,"<root>\n  <value>2</value>\n</root>",true));
        String formatted=ResultCellEditorDialog.formatXml("<?xml version=\"1.0\"?><root><value>1</value></root>");
        String portable=formatted.replace("\r\n","\n");if(!portable.contains("\n  <value>1</value>\n")||!portable.startsWith("<?xml"))throw new AssertionError(formatted);
        if(!ResultCellEditorDialog.looksLikeXml(formatted)||ResultCellEditorDialog.looksLikeXml("SELECT 1"))throw new AssertionError("XML detection");
        try{ResultCellEditorDialog.formatXml("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///x'>]><x>&e;</x>");throw new AssertionError("External XML accepted");}catch(Exception expected){}
        System.out.println("ResultCellEditorDialogTest: OK (types, temporal values, exact text preservation and explicit secure XML formatting)");
    }
    private static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
