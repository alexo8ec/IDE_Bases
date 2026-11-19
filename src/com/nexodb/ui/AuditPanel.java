package com.nexodb.ui;

import com.nexodb.util.AuditLog;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import static com.nexodb.util.I18n.tr;

/** Safe local audit view. */
final class AuditPanel extends JPanel {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final AuditLog audit=AuditLog.configured();
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{tr("Fecha"),tr("Acción"),tr("Conexión"),tr("Ámbito"),tr("Resultado"),tr("Huella")},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);private final Timer timer=new Timer(1500,e->refresh());private final JTextField search=new JTextField(18);private final JComboBox<String> outcome=new JComboBox<>(new String[]{"Todos","OK","FAILED"});private List<AuditLog.Entry> entries=List.of();
    AuditPanel(){super(new BorderLayout(8,8));setName("audit-panel");setBorder(new EmptyBorder(10,10,10,10));JToolBar tools=new JToolBar();tools.setFloatable(false);JButton refresh=UiLanguage.button("Actualizar"),export=UiLanguage.button("Exportar registro seguro…"),clear=UiLanguage.button("Limpiar historial");refresh.setIcon(IconFactory.of(IconFactory.Kind.REFRESH,16));export.setIcon(IconFactory.of(IconFactory.Kind.SAVE,16));clear.setIcon(IconFactory.of(IconFactory.Kind.DELETE,16));tools.add(refresh);tools.add(export);tools.add(clear);tools.addSeparator();tools.add(new JLabel(tr("Buscar: ")));UiLanguage.placeholder(search,"Acción, conexión, ámbito o huella");search.setMaximumSize(new Dimension(230,28));tools.add(search);tools.addSeparator();tools.add(new JLabel(tr("Resultado: ")));tools.add(outcome);add(tools,BorderLayout.NORTH);table.setName("audit-table");table.setAutoCreateRowSorter(true);table.setRowHeight(26);table.getColumnModel().getColumn(0).setPreferredWidth(150);add(new JScrollPane(table));JLabel note=new JLabel(tr("Registro local: solo acciones administrativas, estado y huellas irreversibles; nunca SQL, datos, parámetros ni contraseñas."));note.setIcon(IconFactory.of(IconFactory.Kind.INFO,15));add(note,BorderLayout.SOUTH);refresh.addActionListener(e->refresh());export.addActionListener(e->export());clear.addActionListener(e->{if(JOptionPane.showConfirmDialog(this,tr("¿Limpiar el historial local de auditoría?"),tr("Auditoría local"),JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION){audit.clear();refresh();}});search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){private void changed(){render();}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}});outcome.addActionListener(e->render());timer.start();refresh();}
    void disposePanel(){timer.stop();}
    private void refresh(){entries=audit.entries();render();}
    private void render(){String needle=search.getText().trim().toLowerCase(Locale.ROOT),selected=(String)outcome.getSelectedItem();model.setRowCount(0);for(int i=entries.size()-1;i>=0;i--){AuditLog.Entry e=entries.get(i);String haystack=(e.action()+" "+e.connection()+" "+e.scope()+" "+e.outcome()+" "+e.fingerprint()).toLowerCase(Locale.ROOT);if(!needle.isBlank()&&!haystack.contains(needle)||!"Todos".equals(selected)&&!e.outcome().equalsIgnoreCase(selected))continue;model.addRow(new Object[]{TIME.format(e.time()),e.action(),e.connection(),e.scope(),e.outcome(),e.fingerprint()});}}
    private void export(){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("adisdb-auditoria-segura.csv"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;StringBuilder csv=new StringBuilder("fecha,accion,conexion,ambito,resultado,huella\r\n");for(int row=0;row<model.getRowCount();row++){for(int col=0;col<model.getColumnCount();col++){if(col>0)csv.append(',');csv.append(csv(model.getValueAt(row,col)));}csv.append("\r\n");}try{Files.writeString(chooser.getSelectedFile().toPath(),csv,StandardCharsets.UTF_8);JOptionPane.showMessageDialog(this,tr("Registro seguro exportado correctamente."));}catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage(),tr("No se pudo exportar"),JOptionPane.ERROR_MESSAGE);}}
    private static String csv(Object value){return '"'+String.valueOf(value).replace("\"","\"\"")+'"';}
}
