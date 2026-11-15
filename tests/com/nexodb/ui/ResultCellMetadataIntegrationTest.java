package com.nexodb.ui;

import com.nexodb.db.JdbcSession;
import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import java.nio.file.Path;
import java.sql.Types;

public final class ResultCellMetadataIntegrationTest {
    public static void main(String[] args)throws Exception {
        var profile=new ConnectionProfile("cell-editor-test","Cell editor",DatabaseType.H2,"",0,"mem:cell_editor","sa",
                "jdbc:h2:mem:cell_editor","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])) {
            session.execute("CREATE TABLE documents(id INT PRIMARY KEY, body CLOB, created TIMESTAMP)",1);
            session.execute("INSERT INTO documents VALUES(1, REPEAT('x',12050), TIMESTAMP '2026-11-15 09:40:03')",1);
            var result=session.execute("SELECT * FROM documents",10);
            equal(12050,String.valueOf(result.rows().get(0).get(1)).length());
            equal(Types.CLOB,result.jdbcTypes().get(1));equal(Types.TIMESTAMP,result.jdbcTypes().get(2));
            var model=new ResultTableModel();model.setResult(result);
            equal(ResultCellEditorDialog.Kind.TEXT,ResultCellEditorDialog.kind(model.jdbcType(1),model.getValueAt(0,1)));
            equal(ResultCellEditorDialog.Kind.TIMESTAMP,ResultCellEditorDialog.kind(model.jdbcType(2),model.getValueAt(0,2)));
        }
        System.out.println("ResultCellMetadataIntegrationTest: OK (full CLOB and JDBC text/date metadata)");
    }
    private static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
