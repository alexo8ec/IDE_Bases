package com.nexodb.ui;

import com.nexodb.util.AuditLog;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import static com.nexodb.util.I18n.tr;

/** Safe local audit view. */
final class AuditPanel extends JPanel {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final AuditLog audit=AuditLog.configured();
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{tr("Fecha"),tr("Acción"),tr("Conexión"),tr("Ámbito"),tr("Resultado"),tr("Huella")},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);private final Timer timer=new Timer(1500,e->refresh());
    AuditPanel(){super(new BorderLayout(8,8));setName("audit-panel");setBorder(new EmptyBorder(10,10,10,10));JToolBar tools=new JToolBar();tools.setFloatable(false);JButton refresh=UiLanguage.button("Actualizar"),clear=UiLanguage.button("Limpiar historial");refresh.setIcon(IconFactory.of(IconFactory.Kind.REFRESH,16));clear.setIcon(IconFactory.of(IconFactory.Kind.DELETE,16));tools.add(refresh);tools.add(clear);add(tools,BorderLayout.NORTH);table.setName("audit-table");table.setAutoCreateRowSorter(true);table.setRowHeight(26);table.getColumnModel().getColumn(0).setPreferredWidth(150);add(new JScrollPane(table));JLabel note=new JLabel(tr("Registro local: solo acciones administrativas, estado y huellas irreversibles; nunca SQL, datos, parámetros ni contraseñas."));note.setIcon(IconFactory.of(IconFactory.Kind.INFO,15));add(note,BorderLayout.SOUTH);refresh.addActionListener(e->refresh());clear.addActionListener(e->{if(JOptionPane.showConfirmDialog(this,tr("¿Limpiar el historial local de auditoría?"),tr("Auditoría local"),JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION){audit.clear();refresh();}});timer.start();refresh();}
    void disposePanel(){timer.stop();}
    private void refresh(){List<AuditLog.Entry> rows=audit.entries();model.setRowCount(0);for(int i=rows.size()-1;i>=0;i--){AuditLog.Entry e=rows.get(i);model.addRow(new Object[]{TIME.format(e.time()),e.action(),e.connection(),e.scope(),e.outcome(),e.fingerprint()});}}
}
