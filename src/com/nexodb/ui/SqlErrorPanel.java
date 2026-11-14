package com.nexodb.ui;

import com.nexodb.db.SqlDiagnostic;
import javax.swing.*;
import java.awt.*;

/** The explanation is selectable/copyable and the original engine details remain visible. */
final class SqlErrorPanel extends JPanel {
    SqlErrorPanel(SqlDiagnostic diagnostic,Runnable locate){
        super(new BorderLayout(5,5));setName("sql-error-detail");JTextArea text=new JTextArea(diagnostic.display());text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setRows(7);text.setMargin(new Insets(8,10,8,10));add(new JScrollPane(text));
        JButton go=UiLanguage.button("Ir a la línea del error");go.setName("sql-error-locate");go.addActionListener(e->locate.run());JPanel bar=new JPanel(new FlowLayout(FlowLayout.LEFT));bar.add(go);add(bar,BorderLayout.NORTH);
    }
}
