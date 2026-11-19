package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.util.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;
import static com.nexodb.util.I18n.tr;

/** Live server-side sessions and blockers. SQL text stays hidden unless explicitly enabled. */
final class ServerMonitorPanel extends JPanel {
    private final JdbcSession origin;
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{tr("Sesión"),tr("Usuario"),tr("Base"),tr("Estado"),tr("Duración"),tr("Bloqueada por"),tr("Objeto / espera"),tr("Transacción"),tr("SQL")},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);private final JLabel status=new JLabel(" ");
    private final JCheckBox showSql=UiLanguage.text(new JCheckBox(),UiLanguage.key("Mostrar texto SQL")),automatic=UiLanguage.text(new JCheckBox(),UiLanguage.key("Actualización automática"));
    private final JSpinner interval=new JSpinner(new SpinnerNumberModel(5,2,300,1)),limit=new JSpinner(new SpinnerNumberModel(500,10,5000,10));
    private final JButton refresh=UiLanguage.button("Actualizar"),cancel=UiLanguage.button("Cancelar consulta"),terminate=UiLanguage.button("Cerrar sesión");
    private final javax.swing.Timer timer=new javax.swing.Timer(5_000,e->refresh());private SwingWorker<ServerActivity.Snapshot,Void> worker;private BackgroundOperations.Operation operation;private volatile JdbcSession.ReadLease monitorLease;private List<ServerActivity.Session> rows=List.of();private boolean disposed;
    ServerMonitorPanel(JdbcSession origin){
        super(new BorderLayout(6,6));this.origin=origin;setName("server-monitor");setBorder(new EmptyBorder(8,8,8,8));
        JToolBar bar=new JToolBar();bar.setFloatable(false);refresh.setIcon(IconFactory.of(IconFactory.Kind.REFRESH,16));bar.add(refresh);bar.add(cancel);bar.add(terminate);bar.addSeparator();bar.add(showSql);bar.addSeparator();bar.add(automatic);bar.add(new JLabel(tr("Cada (s): ")));bar.add(interval);bar.addSeparator();bar.add(new JLabel(tr("Máx. sesiones: ")));bar.add(limit);add(bar,BorderLayout.NORTH);
        table.setName("server-activity-table");table.setAutoCreateRowSorter(true);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.setRowHeight(25);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);for(int i=0;i<table.getColumnCount();i++)table.getColumnModel().getColumn(i).setPreferredWidth(i==8?380:i==6||i==7?180:120);add(new JScrollPane(table));
        status.setBorder(new EmptyBorder(4,4,2,4));add(status,BorderLayout.SOUTH);refresh.addActionListener(e->refresh());showSql.addActionListener(e->render());automatic.addActionListener(e->{if(automatic.isSelected())timer.restart();else timer.stop();});interval.addChangeListener(e->{timer.setDelay((Integer)interval.getValue()*1000);if(timer.isRunning())timer.restart();});table.getSelectionModel().addListSelectionListener(e->actions());cancel.addActionListener(e->control(false));terminate.addActionListener(e->control(true));actions();
        if(!ServerActivity.supported(origin.profile().type())){status.setText(tr("El monitor no está disponible para este motor."));refresh.setEnabled(false);automatic.setEnabled(false);}else refresh();
    }
    JdbcSession origin(){return origin;}
    void disposePanel(){disposed=true;timer.stop();if(operation!=null)operation.requestCancel();else if(worker!=null)worker.cancel(true);}
    private void refresh(){
        if(disposed||worker!=null&&!worker.isDone()||!origin.isOpen())return;int maximum=(Integer)limit.getValue();BackgroundOperations.Operation active=BackgroundOperations.create(BackgroundOperations.Kind.RELOAD,tr("Monitor del servidor"),origin.profile().name(),()->{SwingWorker<?,?> current=worker;if(current!=null)current.cancel(true);JdbcSession.ReadLease lease=monitorLease;if(lease!=null)try{lease.session().cancel();}catch(Exception ignored){}},this::refresh);operation=active;refresh.setEnabled(false);status.setText(tr("Leyendo sesiones y bloqueos…"));
        worker=new SwingWorker<>(){protected ServerActivity.Snapshot doInBackground()throws Exception{active.awaitTurn();try(JdbcSession.ReadLease lease=origin.borrowReadOnly(10_000)){monitorLease=lease;return ServerActivity.read(lease.session(),maximum);}finally{monitorLease=null;}}
            protected void done(){try{if(isCancelled()){active.cancelled(tr("Monitor cancelado"));return;}ServerActivity.Snapshot snapshot=get();active.complete(snapshot.sessions().size()+tr(" sesiones"));rows=snapshot.sessions();render();long blocked=rows.stream().filter(row->!row.blockedBy().isBlank()).count(),prolonged=rows.stream().filter(row->!row.blockedBy().isBlank()&&row.seconds()>=30).count(),transactions=rows.stream().filter(row->!row.transaction().isBlank()).count();boolean saturated=rows.size()>=maximum;status.setForeground(prolonged>0||saturated?new Color(190,75,25):UIManager.getColor("Label.foreground"));status.setText(rows.size()+tr(" sesiones · ")+blocked+tr(" bloqueadas · ")+transactions+tr(" transacciones abiertas")+(prolonged>0?tr(" · ALERTA: ")+prolonged+tr(" bloqueos prolongados"):"")+(saturated?tr(" · posible saturación o límite alcanzado"):"")+" · "+snapshot.elapsedMillis()+" ms"+(snapshot.notice().isBlank()?"":" · "+snapshot.notice()));}
                catch(Exception failure){Throwable cause=root(failure);active.fail(cause);status.setForeground(new Color(190,75,25));status.setText(tr("No se pudo leer la actividad. La cuenta puede no tener permisos de monitorización: ")+Objects.toString(cause.getMessage(),cause.getClass().getSimpleName()));rows=List.of();render();}finally{worker=null;operation=null;refresh.setEnabled(!disposed&&origin.isOpen());if(automatic.isSelected()&&!disposed)timer.restart();}}
        };worker.execute();
    }
    private void render(){model.setRowCount(0);for(ServerActivity.Session row:rows)model.addRow(new Object[]{row.id(),row.user(),row.database(),row.state(),duration(row.seconds()),row.blockedBy(),row.object(),row.transaction(),showSql.isSelected()?row.query():row.query().isBlank()?"":"(oculto)"});actions();}
    private void actions(){ServerActivity.Session selected=selected();boolean allowed=selected!=null&&!selected.ownSession()&&worker==null;cancel.setEnabled(allowed);terminate.setEnabled(allowed);}
    private ServerActivity.Session selected(){int selected=table.getSelectedRow();if(selected<0)return null;int index=table.convertRowIndexToModel(selected);return index<rows.size()?rows.get(index):null;}
    private void control(boolean close){ServerActivity.Session selected=selected();if(selected==null)return;String action=close?tr("cerrar la sesión"):tr("cancelar la consulta");if(!Confirmations.ask(this,close?"Cerrar sesión":"Cancelar consulta",origin.profile().name()+" · "+selected.id()+" · "+selected.user()+"\n"+tr("Se intentará ")+action+tr(". El servidor verificará tus permisos. ¿Continuar?")))return;refresh.setEnabled(false);cancel.setEnabled(false);terminate.setEnabled(false);
        new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{ServerActivity.control(origin,selected.id(),close);return null;}protected void done(){try{get();AuditLog.configured().record(close?"SESSION_TERMINATE":"QUERY_CANCEL",origin.profile().name(),selected.database(),"OK",selected.id());status.setText(tr(close?"Sesión cerrada.":"Consulta cancelada."));SwingUtilities.invokeLater(ServerMonitorPanel.this::refresh);}catch(Exception error){AuditLog.configured().record(close?"SESSION_TERMINATE":"QUERY_CANCEL",origin.profile().name(),selected.database(),"FAILED",selected.id());status.setText(tr("El servidor rechazó la acción: ")+Objects.toString(root(error).getMessage(),root(error).getClass().getSimpleName()));refresh.setEnabled(true);actions();}}}.execute();}
    private static String duration(long seconds){return seconds<60?seconds+" s":seconds<3600?(seconds/60)+" min "+(seconds%60)+" s":(seconds/3600)+" h "+((seconds%3600)/60)+" min";}
    private static Throwable root(Throwable error){while(error.getCause()!=null)error=error.getCause();return error;}
}
