package com.nexodb.ui;

import com.nexodb.util.WorkspaceRecoveryStore;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import static com.nexodb.util.I18n.tr;

final class WorkspaceRecoveryPanel extends JPanel {
    WorkspaceRecoveryPanel(WorkspaceRecoveryStore.Recovery recovery){super(new BorderLayout(8,8));setName("workspace-recovery");setBorder(new EmptyBorder(14,14,14,14));JLabel title=new JLabel("<html><h2>"+tr("Recuperación del espacio de trabajo")+"</h2>"+tr("AdisDB detectó un cierre inesperado. Las consultas guardadas se restauraron como borradores; los resultados no se almacenan por seguridad y deben recargarse.")+"</html>");title.setIcon(IconFactory.of(IconFactory.Kind.INFO,28));add(title,BorderLayout.NORTH);DefaultTableModel model=new DefaultTableModel(new Object[]{tr("Hora"),tr("Tipo"),tr("Tarea"),tr("Conexión")},0){public boolean isCellEditable(int r,int c){return false;}};DateTimeFormatter formatter=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());for(WorkspaceRecoveryStore.InterruptedTask task:recovery.tasks())model.addRow(new Object[]{formatter.format(task.time()),task.kind(),task.label(),task.connection()});JTable table=new JTable(model);table.setRowHeight(26);add(new JScrollPane(table));JLabel note=new JLabel(tr("No se restauran datos, contraseñas ni tareas administrativas automáticamente. Revisa cada borrador antes de ejecutarlo."));add(note,BorderLayout.SOUTH);}
}
