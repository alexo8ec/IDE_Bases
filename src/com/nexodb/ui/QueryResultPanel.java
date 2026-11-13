package com.nexodb.ui;

import com.nexodb.db.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextAttribute;
import java.util.EventObject;
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
    private Runnable exportAction;
    private boolean available, saving, pendingCommit, stale;

    QueryResultPanel(JdbcSession origin, QueryResult result, ResultTableModel model, JTable table, String sql, int limit,
                     Consumer<Boolean> busy, Consumer<String> changed, Consumer<JTable> resize) {
        super(new BorderLayout());this.origin=origin;this.model=model;this.table=table;this.sql=sql;this.limit=limit;
        this.busy=busy;this.changed=changed;this.resize=resize;
        target=result.editing()==null?null:result.editing().target();
        table.putClientProperty("terminateEditOnFocusLost",Boolean.TRUE);
        table.setDefaultEditor(Object.class,new DefaultCellEditor(new JTextField()){
            private Object original;
            public Component getTableCellEditorComponent(JTable grid,Object value,boolean selected,int row,int column){original=value;return super.getTableCellEditorComponent(grid,value==QueryEditSupport.DefaultValue.INSTANCE?"":value,selected,row,column);}
            public Object getCellEditorValue(){Object value=super.getCellEditorValue();return "".equals(value)&&(original==null||original==QueryEditSupport.DefaultValue.INSTANCE)?original:value;}
        });
        table.setDefaultRenderer(Object.class,new CellRenderer());
        JToolBar toolbar=new JToolBar();toolbar.setFloatable(false);
        button(edit,"query-edit",IconFactory.Kind.EDIT,"Editar resultados");
        button(save,"query-save",IconFactory.Kind.SAVE,"Guardar cambios");
        button(discard,"query-discard",IconFactory.Kind.CANCEL,"Descartar cambios");
        button(setNull,"query-null",IconFactory.Kind.DELETE,"Establecer NULL");
        button(reload,"query-reload",IconFactory.Kind.REFRESH,"Recargar consulta");
        toolbar.add(edit);toolbar.add(save);toolbar.add(discard);toolbar.add(setNull);toolbar.addSeparator();toolbar.add(reload);toolbar.addSeparator();
        JButton actions=new JButton("▾");actions.setName("query-actions");UiLanguage.tooltip(actions,UiLanguage.key("Acciones del resultado"));toolbar.add(actions);
        toolbar.add(new JLabel(IconFactory.of(IconFactory.Kind.SEARCH,15)));
        UiLanguage.placeholder(filter,"Filtrar resultados…");filter.setName("query-filter");filter.setMaximumSize(new Dimension(200,28));toolbar.add(filter);
        JPanel footer=new JPanel(new BorderLayout(8,0));footer.setBorder(BorderFactory.createEmptyBorder(4,5,4,5));footer.add(status);footer.add(count,BorderLayout.EAST);
        JScrollPane scroll=new JScrollPane(table);
        grid=new ResultGridActions(table,model,scroll,filter,this::updateCount,changed);
        grid.editingActions(new ResultGridActions.EditingActions(this::editSelectedCell,()->insertRow(false),this::deleteRows,this::save,this::discardChanges,this::setSelectionValue,()->insertRow(true),()->{if(exportAction!=null)exportAction.run();},this::canWrite,this::hasChanges,this::canEditSelectedCell,()->origin.profile().type()!=com.nexodb.model.DatabaseType.SQLITE,()->exportAction!=null));
        actions.addActionListener(event->grid.showMenu(actions));
        add(toolbar,BorderLayout.NORTH);add(scroll,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH);
        setHint(result);
        edit.addActionListener(event->toggle());save.addActionListener(event->save());
        discard.addActionListener(event->discardChanges());
        reload.addActionListener(event->reload());
        setNull.addActionListener(event->setSelectionValue(null));
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){if(SwingUtilities.isLeftMouseButton(event)&&event.getClickCount()==2){int row=table.rowAtPoint(event.getPoint()),column=table.columnAtPoint(event.getPoint());if(row>=0&&column>=0)editCell(row,column,event);}}});
        table.getInputMap().put(KeyStroke.getKeyStroke("F2"),"edit-result-cell");table.getActionMap().put("edit-result-cell",new AbstractAction(){public void actionPerformed(ActionEvent event){editSelectedCell();}});
        table.getSelectionModel().addListSelectionListener(event->updateActions());
        table.getColumnModel().getSelectionModel().addListSelectionListener(event->updateActions());
        model.onChange(()->{updateActions();table.repaint();});
        setAvailable(origin,false);
    }
    private static void button(AbstractButton button,String name,IconFactory.Kind icon,String hint){button.setName(name);button.setIcon(IconFactory.of(icon,16));UiLanguage.tooltip(button,UiLanguage.key(hint));}
    JdbcSession origin(){return origin;}
    void setExportAction(Runnable action){exportAction=action;}
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
        discardChanges();return true;
    }
    void setAvailable(JdbcSession current,boolean operationBusy){available=current==origin&&origin!=null&&origin.isOpen()&&!operationBusy;updateActions();}
    void transactionFinished(boolean rollback){
        if(rollback){cancelEditing();grid.clearChecked();model.discard();}
        if(!pendingCommit)return;
        pendingCommit=false;
        if(rollback){stale=true;edit.setSelected(false);UiLanguage.text(status,UiLanguage.key("Cambios revertidos. Recarga la consulta para actualizar el resultado."));}
        else UiLanguage.text(status,UiLanguage.key("Cambio guardado"));
        updateActions();
    }
    private void setHint(QueryResult result){
        if(target==null){String reason=result.editing()==null?"Este resultado es de solo lectura.":result.editing().reason();UiLanguage.text(status,UiLanguage.key(reason));UiLanguage.tooltip(status,UiLanguage.key(reason));}
        else UiLanguage.text(status,UiLanguage.key("Doble clic para editar. Clic derecho o ▾ para acciones. Pulsa Guardar para aplicar."));
    }
    private void updateCount(){UiLanguage.text(count,UiLanguage.key("Filas: "),table.getRowCount(),UiLanguage.key(" · Marcadas: "),grid==null?0:grid.checkedCount(),UiLanguage.key(" · Cambios: "),model.changeCount());}
    private void updateActions(){
        boolean enabled=available&&!saving&&target!=null;
        edit.setEnabled(enabled&&!stale);model.setEditable(enabled&&!stale&&edit.isSelected());
        save.setEnabled(enabled&&!stale&&hasChanges());discard.setEnabled(available&&!saving&&hasChanges());reload.setEnabled(enabled);
        int row=table.getSelectedRow(),column=table.getSelectedColumn();
        setNull.setEnabled(canEditSelectedCell());
        updateCount();
    }
    private void toggle(){
        if(!edit.isSelected()){finishEditing();updateActions();return;}
        try {origin.setReadOnly(false);setHint(model.definition());updateActions();}
        catch(Exception error){edit.setSelected(false);updateActions();showError(error,"No se pudo activar la edición");}
    }
    private boolean canWrite(){return available&&!saving&&!stale&&target!=null;}
    private boolean canEditSelectedCell(){int row=table.getSelectedRow(),column=table.getSelectedColumn();return canWrite()&&row>=0&&column>=0&&!model.isDeleted(table.convertRowIndexToModel(row))&&target.columns().get(table.convertColumnIndexToModel(column)).writable();}
    private boolean enableEditing(){if(!canWrite())return false;if(!edit.isSelected()){edit.setSelected(true);toggle();}return edit.isSelected();}
    private void editSelectedCell(){editCell(table.getSelectedRow(),table.getSelectedColumn(),null);}
    private void editCell(int row,int column,EventObject event){
        if(row<0||column<0||!canWrite()||!target.columns().get(table.convertColumnIndexToModel(column)).writable()||model.isDeleted(table.convertRowIndexToModel(row))||!enableEditing())return;
        if(table.isEditing()&&table.getEditingRow()==row&&table.getEditingColumn()==column)return;
        if(!finishEditing())return;
        table.changeSelection(row,column,false,false);
        if(table.editCellAt(row,column,event)&&table.getEditorComponent() instanceof JTextField field){field.requestFocusInWindow();field.selectAll();}
    }
    private void setSelectionValue(Object value){if(!canEditSelectedCell()||!finishEditing()||!enableEditing())return;model.setValues(grid.selectedRows(),grid.selectedColumns(),value);}
    private void discardChanges(){cancelEditing();grid.clearChecked();model.discard();setHint(model.definition());}
    private void insertRow(boolean duplicate){
        if(!finishEditing()||!enableEditing())return;
        int selected=table.getSelectedRow();if(duplicate&&selected<0)return;
        int row=model.insertRow(duplicate?table.convertRowIndexToModel(selected):null);grid.clearChecked();
        if(row>=0){int view=table.convertRowIndexToView(row);if(view>=0){table.setRowSelectionInterval(view,view);int column=0;while(column<table.getColumnCount()&&!model.isCellEditable(row,table.convertColumnIndexToModel(column)))column++;if(column<table.getColumnCount()){table.setColumnSelectionInterval(column,column);table.scrollRectToVisible(table.getCellRect(view,column,true));editCell(view,column,null);}}}
    }
    private void deleteRows(){if(!finishEditing()||!enableEditing())return;List<Integer> rows=grid.selectedRows();grid.clearChecked();model.deleteRows(rows);UiLanguage.text(status,UiLanguage.key("Filas marcadas para eliminar. Pulsa Guardar para aplicar o Cancelar para deshacer."));}
    private record SaveOutcome(QueryEditSupport.BatchSaved saved,QueryResult fresh,Exception refreshError){}
    private void save(){
        if(!available||saving||stale||!finishEditing())return;
        List<QueryEditSupport.RowChange> changes=model.changes();if(changes.isEmpty())return;
        long deletes=changes.stream().filter(row->row.kind()==QueryEditSupport.ChangeKind.DELETE).count();
        if(deletes>0&&JOptionPane.showConfirmDialog(this,tr("Se eliminarán %s filas de %s. ¿Guardar estos cambios?",deletes,target.qualifiedTable()),tr("Confirmar eliminación"),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
        boolean refresh=changes.stream().anyMatch(row->row.kind()!=QueryEditSupport.ChangeKind.UPDATE||row.values().containsValue(QueryEditSupport.DefaultValue.INSTANCE));
        start();UiLanguage.text(status,UiLanguage.key("Guardando cambios…"));
        new SwingWorker<SaveOutcome,Void>() {
            protected SaveOutcome doInBackground()throws Exception{var saved=QueryEditSupport.saveChanges(origin.connection(),target,changes);if(refresh)try{return new SaveOutcome(saved,origin.execute(QueryEditSupport.qualifiedSelect(sql,target),limit),null);}catch(Exception error){return new SaveOutcome(saved,null,error);}return new SaveOutcome(saved,null,null);}
            protected void done(){
                try {
                    SaveOutcome outcome=get();QueryEditSupport.BatchSaved saved=outcome.saved();pendingCommit|=saved.pendingCommit();
                    if(outcome.fresh()!=null){grid.clearChecked();model.setResult(outcome.fresh());target=outcome.fresh().editing()==null?null:outcome.fresh().editing().target();resize.accept(table);}
                    else if(outcome.refreshError()!=null){grid.clearChecked();model.discard();stale=true;edit.setSelected(false);UiLanguage.text(status,UiLanguage.key("Los cambios se guardaron, pero no se pudo recargar el resultado. Pulsa Recargar."));showError(outcome.refreshError(),"No se pudo recargar el resultado");return;}
                    else model.accept(saved.rows());
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
                try{QueryResult fresh=get();grid.clearChecked();model.setResult(fresh);target=fresh.editing()==null?null:fresh.editing().target();stale=false;edit.setSelected(false);resize.accept(table);setHint(fresh);}
                catch(Exception error){showError(error,"Error al ejecutar la consulta");}finally{end();}
            }
        }.execute();
    }
    private void start(){saving=true;updateActions();busy.accept(true);}
    private void end(){saving=false;busy.accept(false);updateActions();}
    private void showError(Throwable error,String title){while(error.getCause()!=null)error=error.getCause();JOptionPane.showMessageDialog(this,error.getMessage(),tr(title),JOptionPane.ERROR_MESSAGE);}
    private final class CellRenderer extends DefaultTableCellRenderer {
        private boolean arrow;
        public Component getTableCellRendererComponent(JTable grid,Object value,boolean selected,boolean focus,int row,int column){
            int index=grid.convertRowIndexToModel(row);boolean marked=QueryResultPanel.this.grid!=null&&QueryResultPanel.this.grid.isMarked(index);
            super.getTableCellRendererComponent(grid,value,selected||marked,focus,row,column);
            Font font=grid.getFont().deriveFont(value==null||value==QueryEditSupport.DefaultValue.INSTANCE?Font.ITALIC:Font.PLAIN);
            if(model.isDeleted(index))font=font.deriveFont(java.util.Map.of(TextAttribute.STRIKETHROUGH,TextAttribute.STRIKETHROUGH_ON));setFont(font);setText(value==null?"(NULL)":String.valueOf(value));
            boolean dirty=model.isChanged(index,grid.convertColumnIndexToModel(column)),dark=com.formdev.flatlaf.FlatLaf.isLafDark();
            if(!selected&&!marked){Color background=UIManager.getColor("Table.background");setBackground(model.isDeleted(index)?(dark?new Color(85,34,34):new Color(255,221,221)):model.isNewRow(index)?(dark?new Color(25,69,49):new Color(222,247,230)):dirty?(dark?new Color(83,67,22):new Color(255,243,190)):background);setForeground(UIManager.getColor("Table.foreground"));}
            arrow=row==grid.getSelectedRow()&&column==grid.getSelectedColumn();if(arrow)setBorder(BorderFactory.createCompoundBorder(getBorder(),BorderFactory.createEmptyBorder(0,0,0,18)));
            setToolTipText(model.isDeleted(index)?tr("Fila pendiente de eliminar"):dirty?tr("Cambio sin guardar"):arrow?tr("Doble clic para editar; clic en ▾ para abrir el menú."):null);return this;
        }
        protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(arrow){graphics.setColor(getForeground());int x=getWidth()-12,y=getHeight()/2;graphics.drawLine(x-3,y-1,x,y+2);graphics.drawLine(x,y+2,x+3,y-1);}}
    }
}
