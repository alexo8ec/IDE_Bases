package com.nexodb.ui;

import com.nexodb.db.SqlDiagnostic;
import javax.swing.*;
import java.awt.*;
import static com.nexodb.util.I18n.tr;

/** The explanation is selectable/copyable and the original engine details remain visible. */
final class SqlErrorPanel extends JPanel {
    SqlErrorPanel(SqlDiagnostic diagnostic,Runnable locate){
        super(new BorderLayout(5,5));setName("sql-error-detail");JTextArea summary=new JTextArea(diagnostic.friendly());summary.setEditable(false);summary.setLineWrap(true);summary.setWrapStyleWord(true);summary.setRows(4);summary.setMargin(new Insets(8,10,8,10));add(summary);
        JTextArea technical=new JTextArea(diagnostic.technical());technical.setEditable(false);technical.setLineWrap(true);technical.setWrapStyleWord(true);technical.setRows(5);technical.setMargin(new Insets(8,10,8,10));JScrollPane detail=new JScrollPane(technical);detail.setVisible(false);add(detail,BorderLayout.SOUTH);
        JButton go=UiLanguage.button("Ir a la línea del error");go.setName("sql-error-locate");go.setVisible(diagnostic.precise());go.addActionListener(e->locate.run());JButton toggle=UiLanguage.button("Ver detalle técnico");toggle.addActionListener(e->{boolean show=!detail.isVisible();detail.setVisible(show);toggle.setText(tr(show?"Ocultar detalle técnico":"Ver detalle técnico"));revalidate();repaint();});JPanel bar=new JPanel(new FlowLayout(FlowLayout.LEFT));bar.add(go);bar.add(toggle);add(bar,BorderLayout.NORTH);
    }
}
