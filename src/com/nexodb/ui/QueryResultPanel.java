package com.nexodb.ui;

import com.nexodb.db.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;
import static com.nexodb.util.I18n.tr;

/** Editable result grid: changes are staged until the user explicitly saves them. */
final class QueryResultPanel extends JPanel {
    interface ColumnEditor {void open(JdbcSession session,QueryEditSupport.Target target,String column,boolean delete);}
    private final JdbcSession origin;
    private QueryEditSupport.Target target;
    private final ResultTableModel model;
    private final JTable table;
    private final String sql;
    private final int limit;
    private final JToggleButton edit = UiLanguage.text(new JToggleButton(),UiLanguage.key("Editar"));
    private final JButton save = UiLanguage.text(new JButton(),UiLanguage.key("Guardar"));
    private final JButton discard = UiLanguage.text(new JButton(),UiLanguage.key("Descartar"));
    private final JButton setNull = new JButton("NULL");
    private final JButton reload = UiLanguage.text(new JButton(),UiLanguage.key("Recargar"));
    private final JTextField filter = new JTextField(14);
    private final JLabel status = new JLabel(), count = new JLabel();
    private final Consumer<Boolean> busy;
    private final Consumer<String> changed;
    private final Consumer<JTable> resize;
    private final ResultGridActions grid;
    private boolean available, saving, pendingCommit, stale;

    QueryResultPanel(JdbcSession origin, QueryResult result, ResultTableModel model, JTable table, String sql, int limit,
                     Consumer<Boolean> busy, Consumer<String> changed, Consumer<JTable> resize) {
        super(new BorderLayout());this.origin=origin;this.model=model;this.table=table;this.sql=sql;this.limit=limit;
        this.busy=busy;this.changed=changed;this.resize=resize;
        target=result.editing()==null?null:result.editing().target();
        table.putClientProperty("terminateEditOnFocusLost",Boolean.TRUE);
        table.setDefaultEditor(Object.class,new DefaultCellEditor(new JTextField()));
        table.setDefaultRenderer(Object.class,new CellRenderer());
        JToolBar toolbar=new JToolBar();toolbar.setFloatable(false);
        button(edit,"query-edit",IconFactory.Kind.EDIT,"Editar resultados");
        button(save,"query-save",IconFactory.Kind.SAVE,"Guardar cambios");
        button(discard,"query-discard",IconFactory.Kind.CANCEL,"Descartar cambios");
        button(setNull,"query-null",IconFactory.Kind.DELETE,"Establecer NULL");
        button(reload,"query-reload",IconFactory.Kind.REFRESH,"Recargar consulta");
        toolbar.add(edit);toolbar.add(save);toolbar.add(discard);toolbar.add(setNull);toolbar.addSeparator();toolbar.add(reload);toolbar.addSeparator();
        toolbar.add(new JLabel(IconFactory.of(IconFactory.Kind.SEARCH,15)));
        UiLanguage.placeholder(filter,"Filtrar resultados…");filter.setName("query-filter");filter.setMaximumSize(new Dimension(200,28));toolbar.add(filter);
        JPanel footer=new JPanel(new BorderLayout(8,0));footer.setBorder(BorderFactory.createEmptyBorder(4,5,4,5));footer.add(status);footer.add(count,BorderLayout.EAST);
        JScrollPane scroll=new JScrollPane(table);
        grid=new ResultGridActions(table,model,scroll,filter,this::updateCount,changed);
        add(toolbar,BorderLayout.NORTH);add(scroll,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH);
        setHint(result);
        edit.addActionListener(event->toggle());save.addActionListener(event->save());
        discard.addActionListener(event->{cancelEditing();model.discard();setHint(model.definition());});
        reload.addActionListener(event->reload());
        setNull.addActionListener(event->{
            if(!finishEditing())return;
            model.setValues(grid.selectedRows(),grid.selectedColumns(),null);
        });
        table.getSelectionModel().addListSelectionListener(event->updateActions());
        table.getColumnModel().getSelectionModel().addListSelectionListener(event->updateActions());
        model.onChange(()->{updateActions();table.repaint();});
        setAvailable(origin,false);
    }
    private static void button(AbstractButton button,String name,IconFactory.Kind icon,String hint){button.setName(name);button.setIcon(IconFactory.of(icon,16));UiLanguage.tooltip(button,UiLanguage.key(hint));}
    JdbcSession origin(){return origin;}
    void setColumnEditor(ColumnEditor editor){
        if(editor!=null&&target!=null)grid.columnEditor((column,delete)->{
            if(!available||saving||!confirmDiscard()||column<0||column>=target.columns().size())return;
            editor.open(origin,target,target.columns().get(column).sourceName(),delete);
        });
    }
    QueryResult result(){return model.snapshot();}
    boolean hasChanges(){return model.changeCount()!=0;}
    boolean finishEditing(){return !table.isEditing()||table.getCellEditor().stopCellEditing();}
    private void cancelEditing(){if(table.isEditing())table.getCellEditor().cancelCellEditing();}
    boolean confirmDiscard(){
        if(!finishEditing())return false;
        if(!hasChanges())return true;
        if(JOptionPane.showConfirmDialog(this,tr("Hay cambios sin guardar en este resultado. ¿Deseas descartarlos?"),tr("Cambios pendientes"),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return false;
        model.discard();return true;
    }
    void setAvailable(JdbcSession current,boolean operationBusy){available=current==origin&&origin!=null&&origin.isOpen()&&!operationBusy;updateActions();}
    void transactionFinished(boolean rollback){
        if(rollback){cancelEditing();model.discard();}
        if(!pendingCommit)return;
        pendingCommit=false;
        if(rollback){stale=true;edit.setSelected(false);UiLanguage.text(status,UiLanguage.key("Cambios revertidos. Recarga la consulta para actualizar el resultado."));}
        else UiLanguage.text(status,UiLanguage.key("Cambio guardado"));
        updateActions();
    }
    private void setHint(QueryResult result){
        if(target==null){String reason=result.editing()==null?"Este resultado es de solo lectura.":result.editing().reason();UiLanguage.text(status,UiLanguage.key(reason));UiLanguage.tooltip(status,UiLanguage.key(reason));}
        else UiLanguage.text(status,UiLanguage.key("Haz doble clic para editar. Revisa los cambios y pulsa Guardar."));
    }
    private void updateCount(){UiLanguage.text(count,UiLanguage.key("Filas: "),table.getRowCount(),UiLanguage.key(" · Marcadas: "),grid==null?0:grid.checkedCount(),UiLanguage.key(" · Cambios: "),model.changeCount());}
    private void updateActions(){
        boolean enabled=available&&!saving&&target!=null;
        edit.setEnabled(enabled&&!stale);model.setEditable(enabled&&!stale&&edit.isSelected());
        save.setEnabled(enabled&&!stale&&hasChanges());discard.setEnabled(available&&!saving&&hasChanges());reload.setEnabled(enabled);
        int row=table.getSelectedRow(),column=table.getSelectedColumn();
        setNull.setEnabled(enabled&&!stale&&edit.isSelected()&&row>=0&&column>=0&&model.isCellEditable(table.convertRowIndexToModel(row),table.convertColumnIndexToModel(column)));
        updateCount();
    }
    private void toggle(){
        if(!edit.isSelected()){finishEditing();updateActions();return;}
        try {origin.setReadOnly(false);setHint(model.definition());updateActions();}
        catch(Exception error){edit.setSelected(false);updateActions();showError(error,"No se pudo activar la edición");}
    }
    private void save(){
        if(!available||saving||stale||!finishEditing())return;
        List<QueryEditSupport.RowChange> changes=model.changes();if(changes.isEmpty())return;
        start();UiLanguage.text(status,UiLanguage.key("Guardando cambios…"));
        new SwingWorker<QueryEditSupport.BatchSaved,Void>() {
            protected QueryEditSupport.BatchSaved doInBackground()throws Exception{return QueryEditSupport.saveChanges(origin.connection(),target,changes);}
            protected void done(){
                try {
                    QueryEditSupport.BatchSaved saved=get();model.accept(saved.rows());pendingCommit|=saved.pendingCommit();
                    UiLanguage.text(status,UiLanguage.key(saved.pendingCommit()?"Cambio aplicado · pendiente de Commit":"Cambio guardado"));
                    changed.accept(tr(saved.pendingCommit()?"Cambio aplicado · pendiente de Commit":"Cambio guardado"));
                }catch(Exception error){UiLanguage.text(status,UiLanguage.key("No se guardaron los cambios. Puedes corregirlos o descartarlos."));showError(error,"No se pudo actualizar la fila");}
                finally{end();}
            }
        }.execute();
    }
    private void reload(){
        if(!available||saving||target==null||!confirmDiscard())return;
        String qualified=QueryEditSupport.qualifiedSelect(sql,target);start();UiLanguage.text(status,UiLanguage.key("Recargando consulta…"));
        new SwingWorker<QueryResult,Void>() {
            protected QueryResult doInBackground()throws Exception{return origin.execute(qualified,limit);}
            protected void done(){
                try{QueryResult fresh=get();model.setResult(fresh);target=fresh.editing()==null?null:fresh.editing().target();stale=false;edit.setSelected(false);resize.accept(table);setHint(fresh);}
                catch(Exception error){showError(error,"Error al ejecutar la consulta");}finally{end();}
            }
        }.execute();
    }
    private void start(){saving=true;updateActions();busy.accept(true);}
    private void end(){saving=false;busy.accept(false);updateActions();}
    private void showError(Throwable error,String title){while(error.getCause()!=null)error=error.getCause();JOptionPane.showMessageDialog(this,error.getMessage(),tr(title),JOptionPane.ERROR_MESSAGE);}
    private final class CellRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable grid,Object value,boolean selected,boolean focus,int row,int column){
            super.getTableCellRendererComponent(grid,value,selected,focus,row,column);
            setFont(grid.getFont().deriveFont(value==null?Font.ITALIC:Font.PLAIN));setText(value==null?"(NULL)":String.valueOf(value));
            boolean dirty=model.isChanged(grid.convertRowIndexToModel(row),grid.convertColumnIndexToModel(column));
            if(!selected){Color background=UIManager.getColor("Table.background");setBackground(dirty?(com.formdev.flatlaf.FlatLaf.isLafDark()?new Color(83,67,22):new Color(255,243,190)):background);setForeground(UIManager.getColor("Table.foreground"));}
            setToolTipText(dirty?tr("Cambio sin guardar"):null);return this;
        }
    }
}
