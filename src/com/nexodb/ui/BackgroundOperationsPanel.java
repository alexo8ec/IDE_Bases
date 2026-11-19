package com.nexodb.ui;

import com.nexodb.util.BackgroundOperations;
import com.nexodb.util.BackgroundOperations.Snapshot;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import static com.nexodb.util.I18n.tr;

/** Central, non-sensitive view of current and recent background work. */
final class BackgroundOperationsPanel extends JPanel {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{tr("Tarea"),tr("Tipo"),tr("Conexión"),tr("Estado"),tr("Inicio"),tr("Duración"),tr("Progreso"),tr("Detalle")},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);
    private final JButton cancel=UiLanguage.text(new JButton(tr("Cancelar"),IconFactory.of(IconFactory.Kind.STOP,16)),UiLanguage.key("Cancelar"));
    private final JButton retry=UiLanguage.text(new JButton(tr("Reintentar"),IconFactory.of(IconFactory.Kind.REFRESH,16)),UiLanguage.key("Reintentar"));
    private final JButton clear=UiLanguage.text(new JButton(tr("Limpiar finalizadas"),IconFactory.of(IconFactory.Kind.DELETE,16)),UiLanguage.key("Limpiar finalizadas"));
    private final JLabel summary=new JLabel();
    private final JSpinner concurrency=new JSpinner(new SpinnerNumberModel(BackgroundOperations.maxConcurrent(),1,8,1));
    private final JSpinner timeout=new JSpinner(new SpinnerNumberModel(BackgroundOperations.timeoutSeconds(),5,3600,5));
    private final Timer timer=new Timer(500,e->refresh());
    private final BackgroundOperations.Listener listener=this::refresh;
    private List<Snapshot> rows=List.of();

    BackgroundOperationsPanel(){
        super(new BorderLayout(8,8));setName("background-operations");setBorder(new EmptyBorder(10,10,10,10));
        JToolBar actions=new JToolBar();actions.setFloatable(false);actions.add(cancel);actions.add(retry);actions.add(clear);actions.addSeparator();actions.add(new JLabel(tr("Máx. simultáneas: ")));actions.add(concurrency);actions.addSeparator();actions.add(new JLabel(tr("Tiempo máximo (s): ")));actions.add(timeout);JButton apply=UiLanguage.text(new JButton(tr("Aplicar límites"),IconFactory.of(IconFactory.Kind.SETTINGS,16)),UiLanguage.key("Aplicar límites"));actions.add(apply);actions.add(Box.createHorizontalGlue());actions.add(summary);add(actions,BorderLayout.NORTH);
        table.setName("operations-table");table.setAutoCreateRowSorter(true);table.setRowHeight(27);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getSelectionModel().addListSelectionListener(e->updateActions());
        table.getColumnModel().getColumn(0).setPreferredWidth(180);table.getColumnModel().getColumn(2).setPreferredWidth(150);table.getColumnModel().getColumn(7).setPreferredWidth(260);add(new JScrollPane(table),BorderLayout.CENTER);
        JLabel privacy=new JLabel(tr("El historial permanece solo durante esta sesión y no almacena SQL, parámetros, resultados ni credenciales."));privacy.setIcon(IconFactory.of(IconFactory.Kind.INFO,15));add(privacy,BorderLayout.SOUTH);
        cancel.addActionListener(e->{Snapshot selected=selected();if(selected!=null)BackgroundOperations.cancel(selected.id());});
        retry.addActionListener(e->{Snapshot selected=selected();if(selected!=null)BackgroundOperations.retry(selected.id());});
        clear.addActionListener(e->BackgroundOperations.clearFinished());
        apply.addActionListener(e->{try{BackgroundOperations.configure((Integer)concurrency.getValue(),(Integer)timeout.getValue());refresh();}catch(IllegalStateException error){JOptionPane.showMessageDialog(this,error.getMessage(),tr("Preferencias"),JOptionPane.WARNING_MESSAGE);}});
        BackgroundOperations.addListener(listener);timer.start();refresh();
    }
    void disposePanel(){timer.stop();BackgroundOperations.removeListener(listener);}
    private void refresh(){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}long selectedId=selected()==null?-1:selected().id();rows=new ArrayList<>(BackgroundOperations.snapshots());model.setRowCount(0);int select=-1;
        for(int i=0;i<rows.size();i++){Snapshot item=rows.get(i);model.addRow(new Object[]{item.label(),kind(item),item.connection(),state(item),TIME.format(item.queuedAt()),duration(item.elapsedMillis()),item.progress()<0?"—":item.progress()+"%",item.detail()});if(item.id()==selectedId)select=i;}
        if(select>=0){int view=table.convertRowIndexToView(select);if(view>=0)table.setRowSelectionInterval(view,view);}summary.setText(tr("Activas: ")+BackgroundOperations.activeCount()+tr(" · En espera: ")+BackgroundOperations.queuedCount()+tr(" · Historial: ")+Math.max(0,rows.size()-BackgroundOperations.activeCount()));updateActions();
    }
    private Snapshot selected(){int row=table.getSelectedRow();if(row<0)return null;int modelRow=table.convertRowIndexToModel(row);return modelRow>=0&&modelRow<rows.size()?rows.get(modelRow):null;}
    private void updateActions(){Snapshot item=selected();cancel.setEnabled(item!=null&&item.canCancel());retry.setEnabled(item!=null&&item.canRetry());clear.setEnabled(rows.stream().anyMatch(row->!row.active()));}
    private static String duration(long millis){long seconds=millis/1000;return seconds<60?seconds+" s":String.format("%d:%02d",seconds/60,seconds%60);}
    private static String kind(Snapshot item){return tr(switch(item.kind()){case QUERY->"Consulta";case COUNT->"Conteo";case EXPORT->"Exportación";case REPORT->"Reporte";case RELOAD->"Recarga";case PREFETCH->"Precarga";case CONNECTION->"Conexión";});}
    private static String state(Snapshot item){return tr(switch(item.state()){case QUEUED->"En espera";case RUNNING->"Ejecutando…";case COMPLETED->"Completada";case CANCELLED->"Cancelada";case FAILED->"Fallida";case TIMED_OUT->"Tiempo agotado";});}
}
