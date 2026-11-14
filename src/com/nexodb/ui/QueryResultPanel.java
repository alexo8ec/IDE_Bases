package com.nexodb.ui;

import com.nexodb.db.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextAttribute;
import java.util.EventObject;
import java.util.List;
import java.util.Map;
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
    private final JComboBox<String> mode=new JComboBox<>(new String[]{"Solo lectura","Editar datos"});
    private boolean updatingMode;
    private Runnable writeEnabled=()->{};
    private java.util.function.IntSupplier rowLimit;
    private java.util.function.BiPredicate<String,String> confirm=(title,text)->Confirmations.ask(this,title,text);
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
    private List<ForeignKeyLookup.Reference> references=List.of();
    private final JButton referenceButton=UiLanguage.button("Ver referencia");
    private int referenceGeneration;

    QueryResultPanel(JdbcSession origin, QueryResult result, ResultTableModel model, JTable table, String sql, int limit,
                     Consumer<Boolean> busy, Consumer<String> changed, Consumer<JTable> resize) {
        super(new BorderLayout());this.origin=origin;this.model=model;this.table=table;this.sql=sql;this.limit=limit;
        rowLimit=()->this.limit;
        this.busy=busy;this.changed=changed;this.resize=resize;
        target=result.editing()==null?null:result.editing().target();
        table.putClientProperty("terminateEditOnFocusLost",Boolean.TRUE);
        table.setDefaultEditor(Object.class,new DefaultCellEditor(new JTextField()){
            private Object original;
            public Component getTableCellEditorComponent(JTable grid,Object value,boolean selected,int row,int column){
                original=value;Component field=super.getTableCellEditorComponent(grid,value==QueryEditSupport.DefaultValue.INSTANCE?"":value,selected,row,column);
                int modelRow=grid.convertRowIndexToModel(row),modelColumn=grid.convertColumnIndexToModel(column);
                ((JTextField)field).getInputMap().remove(KeyStroke.getKeyStroke("control SPACE"));
                if(!hasReference(modelColumn))return field;
                Runnable open=()->{if(stopCellEditing())openReferenceModel(modelRow,modelColumn);};
                ((JTextField)field).getInputMap().put(KeyStroke.getKeyStroke("control SPACE"),"reference");((JTextField)field).getActionMap().put("reference",new AbstractAction(){public void actionPerformed(ActionEvent e){open.run();}});
                JPanel panel=new JPanel(new BorderLayout());panel.add(field);JButton lookup=new JButton("…");lookup.setName("reference-cell-button");lookup.setMargin(new Insets(0,5,0,5));lookup.addActionListener(e->open.run());panel.add(lookup,BorderLayout.EAST);return panel;
            }
            public Object getCellEditorValue(){Object value=super.getCellEditorValue();return "".equals(value)&&(original==null||original==QueryEditSupport.DefaultValue.INSTANCE)?original:value;}
        });
        table.setDefaultRenderer(Object.class,new CellRenderer());
        JToolBar toolbar=new JToolBar();toolbar.setFloatable(false);
        button(edit,"query-edit",IconFactory.Kind.EDIT,"Editar resultados");
        button(save,"query-save",IconFactory.Kind.SAVE,"Guardar cambios");
        button(discard,"query-discard",IconFactory.Kind.CANCEL,"Descartar cambios");
        button(setNull,"query-null",IconFactory.Kind.DELETE,"Establecer NULL");
        button(reload,"query-reload",IconFactory.Kind.REFRESH,"Recargar consulta");
        mode.setName("query-result-mode");UiLanguage.choices(mode);mode.setMaximumSize(new Dimension(180,28));toolbar.add(mode);toolbar.add(edit);toolbar.add(save);toolbar.add(discard);toolbar.add(setNull);toolbar.addSeparator();toolbar.add(reload);toolbar.addSeparator();
        mode.addActionListener(e->{if(!updatingMode){edit.setSelected(mode.getSelectedIndex()==1);toggle();}});
        JButton actions=new JButton("▾");actions.setName("query-actions");UiLanguage.tooltip(actions,UiLanguage.key("Acciones del resultado"));toolbar.add(actions);
        toolbar.add(new JLabel(IconFactory.of(IconFactory.Kind.SEARCH,15)));
        UiLanguage.placeholder(filter,"Filtrar resultados…");filter.setName("query-filter");filter.setMaximumSize(new Dimension(200,28));toolbar.add(filter);
        JPanel footer=new JPanel(new BorderLayout(8,0));footer.setBorder(BorderFactory.createEmptyBorder(4,5,4,5));footer.add(status);footer.add(count,BorderLayout.EAST);
        JScrollPane scroll=new JScrollPane(table);
        grid=new ResultGridActions(table,model,scroll,filter,this::updateCount,changed);
        grid.editingActions(new ResultGridActions.EditingActions(this::editSelectedCell,()->insertRow(false),this::deleteRows,this::save,this::requestDiscard,this::setSelectionValue,()->insertRow(true),()->{if(exportAction!=null)exportAction.run();},this::canWrite,this::hasChanges,this::canEditSelectedCell,()->origin.profile().type()!=com.nexodb.model.DatabaseType.SQLITE,()->exportAction!=null));
        grid.referenceAction(()->openReference(table.getSelectedRow(),table.getSelectedColumn()),()->table.getSelectedColumn()>=0&&hasReference(table.convertColumnIndexToModel(table.getSelectedColumn())));
        referenceButton.setName("query-reference");referenceButton.setIcon(IconFactory.of(IconFactory.Kind.KEY,16));referenceButton.addActionListener(e->openReference(table.getSelectedRow(),table.getSelectedColumn()));toolbar.add(referenceButton);
        TableCellRenderer normalHeader=table.getTableHeader().getDefaultRenderer();table.getTableHeader().setDefaultRenderer((t,v,s,f,r,c)->{Component component=normalHeader.getTableCellRendererComponent(t,v,s,f,r,c);if(component instanceof JLabel label){int index=table.convertColumnIndexToModel(c);Icon base=label.getIcon();if(base instanceof ReferenceHeaderIcon composed)base=composed.base();label.setIcon(hasReference(index)?new ReferenceHeaderIcon(base,IconFactory.of(IconFactory.Kind.KEY,14)):base);label.setToolTipText(references.stream().filter(ref->ref.contains(index)).map(ref->ref.label()+" · "+String.join(", ",ref.parts().stream().map(p->p.local()+" → "+p.referenced()).toList())).collect(java.util.stream.Collectors.joining("; ")));}return component;});
        actions.addActionListener(event->grid.showMenu(actions));
        add(toolbar,BorderLayout.NORTH);add(scroll,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH);
        setHint(result);
        edit.addActionListener(event->toggle());save.addActionListener(event->save());
        discard.addActionListener(event->requestDiscard());
        reload.addActionListener(event->reload());
        setNull.addActionListener(event->setSelectionValue(null));
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){if(SwingUtilities.isLeftMouseButton(event)&&event.getClickCount()==2){int row=table.rowAtPoint(event.getPoint()),column=table.columnAtPoint(event.getPoint());if(row>=0&&column>=0)editCell(row,column,event);}}});
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){int row=table.rowAtPoint(e.getPoint()),col=table.columnAtPoint(e.getPoint());if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==1&&row>=0&&col>=0&&hasReference(table.convertColumnIndexToModel(col))){Rectangle cell=table.getCellRect(row,col,false);if(e.getX()>=cell.x+cell.width-44&&e.getX()<cell.x+cell.width-18)openReference(row,col);}}});
        table.getInputMap().put(KeyStroke.getKeyStroke("control SPACE"),"lookup-reference");table.getActionMap().put("lookup-reference",new AbstractAction(){public void actionPerformed(ActionEvent e){openReference(table.getSelectedRow(),table.getSelectedColumn());}});
        table.getInputMap().put(KeyStroke.getKeyStroke("F2"),"edit-result-cell");table.getActionMap().put("edit-result-cell",new AbstractAction(){public void actionPerformed(ActionEvent event){editSelectedCell();}});
        table.getSelectionModel().addListSelectionListener(event->updateActions());
        table.getColumnModel().getSelectionModel().addListSelectionListener(event->updateActions());
        model.onChange(()->{updateActions();table.repaint();});
        setAvailable(origin,false);
        loadReferences();
    }
    private static void button(AbstractButton button,String name,IconFactory.Kind icon,String hint){button.setName(name);button.setIcon(IconFactory.of(icon,16));UiLanguage.tooltip(button,UiLanguage.key(hint));}
    JdbcSession origin(){return origin;}
    void setWriteEnabled(Runnable callback){writeEnabled=callback;}
    void setRowLimit(java.util.function.IntSupplier supplier){rowLimit=supplier;}
    void resetReadOnly(){finishEditing();edit.setSelected(false);updateActions();}
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
        else UiLanguage.text(status,UiLanguage.key("Selecciona Editar datos para habilitar cambios. Después usa doble clic y pulsa Guardar para aplicar."));
    }
    private void updateCount(){UiLanguage.text(count,UiLanguage.key("Filas: "),table.getRowCount(),UiLanguage.key(" · Marcadas: "),grid==null?0:grid.checkedCount(),UiLanguage.key(" · Cambios: "),model.changeCount());}
    private void updateActions(){
        boolean enabled=available&&!saving&&target!=null&&!origin.profile().readOnly();
        edit.setEnabled(enabled&&!stale);model.setEditable(enabled&&!stale&&edit.isSelected());
        mode.setEnabled(enabled&&!stale);updatingMode=true;try{mode.setSelectedIndex(edit.isSelected()?1:0);}finally{updatingMode=false;}
        save.setEnabled(enabled&&!stale&&edit.isSelected()&&hasChanges());discard.setEnabled(available&&!saving&&hasChanges());reload.setEnabled(available&&!saving&&target!=null);
        int row=table.getSelectedRow(),column=table.getSelectedColumn();
        setNull.setEnabled(canEditSelectedCell());
        referenceButton.setEnabled(available&&!saving&&column>=0&&hasReference(table.convertColumnIndexToModel(column))&&row>=0);
        updateCount();
    }
    private void toggle(){
        if(!edit.isSelected()){finishEditing();updateActions();return;}
        if(!LicenseGate.ensure(this)){edit.setSelected(false);updateActions();return;}
        if(origin.profile().readOnly()||!confirm.test("Habilitar edición de datos",origin.profile().name()+"\n"+target.qualifiedTable()+"\n"+tr("Podrás modificar, agregar o marcar filas para eliminar. Los cambios se aplican al pulsar Guardar. ¿Habilitar edición?"))){edit.setSelected(false);updateActions();return;}
        try {origin.setReadOnly(false);writeEnabled.run();setHint(model.definition());updateActions();}
        catch(Exception error){edit.setSelected(false);updateActions();showError(error,"No se pudo activar la edición");}
    }
    private boolean canWrite(){return available&&!saving&&!stale&&target!=null&&!origin.profile().readOnly()&&edit.isSelected();}
    private boolean canEditSelectedCell(){int row=table.getSelectedRow(),column=table.getSelectedColumn();return canWrite()&&row>=0&&column>=0&&!model.isDeleted(table.convertRowIndexToModel(row))&&target.columns().get(table.convertColumnIndexToModel(column)).writable();}
    private boolean enableEditing(){return canWrite();}
    private boolean hasReference(int column){return references.stream().anyMatch(r->r.contains(column));}
    private void loadReferences(){
        int generation=++referenceGeneration;references=List.of();if(origin==null||!origin.isOpen())return;
        QueryResult definition=model.definition();
        new SwingWorker<List<ForeignKeyLookup.Reference>,Void>(){
            protected List<ForeignKeyLookup.Reference> doInBackground()throws Exception{return ForeignKeyLookup.describe(origin.connection(),definition);}
            protected void done(){if(generation!=referenceGeneration||model.definition()!=definition)return;try{references=get();}catch(Exception unavailable){UiLanguage.tooltip(referenceButton,UiLanguage.key("No se pudieron leer las claves foráneas. Comprueba los permisos de metadatos."));}table.getTableHeader().repaint();table.repaint();updateActions();}
        }.execute();
    }
    private void openReference(int viewRow,int viewColumn){
        if(viewRow<0||viewColumn<0)return;int row=table.convertRowIndexToModel(viewRow),column=table.convertColumnIndexToModel(viewColumn);if(finishEditing())openReferenceModel(row,column);
    }
    private void openReferenceModel(int row,int column){
        if(!available||saving||row<0||row>=model.getRowCount())return;
        List<ForeignKeyLookup.Reference> matches=references.stream().filter(r->r.contains(column)).toList();if(matches.isEmpty())return;
        ForeignKeyLookup.Reference reference=matches.get(0);
        if(matches.size()>1){Object chosen=JOptionPane.showInputDialog(this,tr("Selecciona la relación que deseas consultar."),tr("Registro relacionado"),JOptionPane.QUESTION_MESSAGE,null,matches.stream().map(ForeignKeyLookup.Reference::label).toArray(),reference.label());if(chosen==null)return;for(var candidate:matches)if(candidate.label().equals(chosen)){reference=candidate;break;}}
        boolean writable=canWrite()&&!model.isDeleted(row)&&reference.complete()&&reference.parts().stream().allMatch(p->model.isCellEditable(row,p.column()));
        List<Object> values=new java.util.ArrayList<>();for(int i=0;i<model.getColumnCount();i++)values.add(model.getValueAt(row,i));
        Map<Integer,Object> chosen=new ForeignKeyLookupDialog(SwingUtilities.getWindowAncestor(this),origin,reference,values,column,writable).showSelection();
        if(chosen==null||!writable||!canWrite()||!confirm.test("Cambiar referencia",reference.childTable()+" → "+reference.parentTable()+"\n"+tr("Se actualizarán juntas las columnas de esta relación en el borrador. Pulsa Guardar para aplicar a la base. ¿Continuar?")))return;
        model.setReference(row,chosen);
    }
    private void editSelectedCell(){editCell(table.getSelectedRow(),table.getSelectedColumn(),null);}
    private void editCell(int row,int column,EventObject event){
        if(row<0||column<0||!canWrite()||!target.columns().get(table.convertColumnIndexToModel(column)).writable()||model.isDeleted(table.convertRowIndexToModel(row))||!enableEditing())return;
        if(table.isEditing()&&table.getEditingRow()==row&&table.getEditingColumn()==column)return;
        if(!finishEditing())return;
        table.changeSelection(row,column,false,false);
        if(table.editCellAt(row,column,event)){Component editor=table.getEditorComponent();JTextField field=editor instanceof JTextField text?text:editor instanceof JPanel panel&&panel.getComponentCount()>0&&panel.getComponent(0) instanceof JTextField text?text:null;if(field!=null){field.requestFocusInWindow();field.selectAll();}}
    }
    private void setSelectionValue(Object value){if(!canEditSelectedCell()||!finishEditing()||!enableEditing())return;if(!confirm.test("Cambiar valores seleccionados",target.qualifiedTable()+"\n"+tr("Se asignará %s a las celdas seleccionadas. Se aplicará al pulsar Guardar. ¿Continuar?",value==null?"NULL":value==QueryEditSupport.DefaultValue.INSTANCE?"DEFAULT":value.toString().isEmpty()?tr("cadena vacía"):value.toString())))return;model.setValues(grid.selectedRows(),grid.selectedColumns(),value);}
    private void requestDiscard(){if(!hasChanges()||confirm.test("Descartar cambios",tr("Se perderán %s cambios pendientes del resultado. Los datos de la base no se modificarán. ¿Descartar?",model.changeCount())))discardChanges();}
    private void discardChanges(){cancelEditing();grid.clearChecked();model.discard();setHint(model.definition());}
    private void insertRow(boolean duplicate){
        if(!finishEditing()||!enableEditing())return;
        if(!confirm.test(duplicate?"Duplicar fila":"Agregar fila",target.qualifiedTable()+"\n"+tr(duplicate?"Se duplicará la fila seleccionada en el resultado. Se insertará al pulsar Guardar. ¿Continuar?":"Se agregará una fila al resultado. Se insertará al pulsar Guardar. ¿Continuar?")))return;
        int selected=table.getSelectedRow();if(duplicate&&selected<0)return;
        int row=model.insertRow(duplicate?table.convertRowIndexToModel(selected):null);grid.clearChecked();
        if(row>=0){int view=table.convertRowIndexToView(row);if(view>=0){table.setRowSelectionInterval(view,view);int column=0;while(column<table.getColumnCount()&&!model.isCellEditable(row,table.convertColumnIndexToModel(column)))column++;if(column<table.getColumnCount()){table.setColumnSelectionInterval(column,column);table.scrollRectToVisible(table.getCellRect(view,column,true));editCell(view,column,null);}}}
    }
    private void deleteRows(){if(!finishEditing()||!enableEditing())return;List<Integer> rows=grid.selectedRows();if(rows.isEmpty()||!confirm.test("Marcar filas para eliminar",tr("Se marcarán %s filas para eliminar de %s. La eliminación se aplicará al pulsar Guardar. ¿Continuar?",rows.size(),target.qualifiedTable())))return;grid.clearChecked();model.deleteRows(rows);UiLanguage.text(status,UiLanguage.key("Filas marcadas para eliminar. Pulsa Guardar para aplicar o Cancelar para deshacer."));}
    private record SaveOutcome(QueryEditSupport.BatchSaved saved,QueryResult fresh,Exception refreshError){}
    private void save(){
        if(!LicenseGate.ensure(this))return;
        if(!available||saving||stale||!finishEditing())return;
        List<QueryEditSupport.RowChange> changes=model.changes();if(changes.isEmpty())return;
        long deletes=changes.stream().filter(row->row.kind()==QueryEditSupport.ChangeKind.DELETE).count();
        if(!canWrite()||!confirm.test("Guardar cambios en la base",origin.profile().name()+"\n"+target.qualifiedTable()+"\n"+tr("Se aplicarán %s cambios, incluidas %s eliminaciones. ¿Guardar en la base de datos?",changes.size(),deletes)))return;
        boolean refresh=changes.stream().anyMatch(row->row.kind()!=QueryEditSupport.ChangeKind.UPDATE||row.values().containsValue(QueryEditSupport.DefaultValue.INSTANCE));
        start();UiLanguage.text(status,UiLanguage.key("Guardando cambios…"));
        new SwingWorker<SaveOutcome,Void>() {
            protected SaveOutcome doInBackground()throws Exception{var saved=QueryEditSupport.saveChanges(origin.connection(),target,changes);if(refresh)try{return new SaveOutcome(saved,origin.execute(QueryEditSupport.qualifiedSelect(sql,target),rowLimit.getAsInt()),null);}catch(Exception error){return new SaveOutcome(saved,null,error);}return new SaveOutcome(saved,null,null);}
            protected void done(){
                try {
                    SaveOutcome outcome=get();QueryEditSupport.BatchSaved saved=outcome.saved();pendingCommit|=saved.pendingCommit();
                    if(outcome.fresh()!=null){grid.clearChecked();model.setResult(outcome.fresh());target=outcome.fresh().editing()==null?null:outcome.fresh().editing().target();resize.accept(table);loadReferences();}
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
            protected QueryResult doInBackground()throws Exception{return origin.execute(qualified,rowLimit.getAsInt());}
            protected void done(){
                try{QueryResult fresh=get();grid.clearChecked();model.setResult(fresh);target=fresh.editing()==null?null:fresh.editing().target();stale=false;edit.setSelected(false);resize.accept(table);setHint(fresh);loadReferences();}
                catch(Exception error){showError(error,"Error al ejecutar la consulta");}finally{end();}
            }
        }.execute();
    }
    private void start(){saving=true;updateActions();busy.accept(true);}
    private void end(){saving=false;busy.accept(false);updateActions();}
    private void showError(Throwable error,String title){while(error.getCause()!=null)error=error.getCause();JOptionPane.showMessageDialog(this,error.getMessage(),tr(title),JOptionPane.ERROR_MESSAGE);}
    private record ReferenceHeaderIcon(Icon base,Icon key) implements Icon{
        public int getIconWidth(){return key.getIconWidth()+(base==null?0:4+base.getIconWidth());}public int getIconHeight(){return Math.max(key.getIconHeight(),base==null?0:base.getIconHeight());}
        public void paintIcon(Component c,Graphics g,int x,int y){key.paintIcon(c,g,x,y);if(base!=null)base.paintIcon(c,g,x+key.getIconWidth()+4,y);}
    }
    private final class CellRenderer extends DefaultTableCellRenderer {
        private boolean arrow,reference;
        public Component getTableCellRendererComponent(JTable grid,Object value,boolean selected,boolean focus,int row,int column){
            int index=grid.convertRowIndexToModel(row);boolean marked=QueryResultPanel.this.grid!=null&&QueryResultPanel.this.grid.isMarked(index);
            super.getTableCellRendererComponent(grid,value,selected||marked,focus,row,column);
            Font font=grid.getFont().deriveFont(value==null||value==QueryEditSupport.DefaultValue.INSTANCE?Font.ITALIC:Font.PLAIN);
            if(model.isDeleted(index))font=font.deriveFont(java.util.Map.of(TextAttribute.STRIKETHROUGH,TextAttribute.STRIKETHROUGH_ON));setFont(font);setText(value==null?"(NULL)":String.valueOf(value));
            boolean dirty=model.isChanged(index,grid.convertColumnIndexToModel(column)),dark=com.formdev.flatlaf.FlatLaf.isLafDark();
            if(!selected&&!marked){Color background=UIManager.getColor("Table.background");setBackground(model.isDeleted(index)?(dark?new Color(85,34,34):new Color(255,221,221)):model.isNewRow(index)?(dark?new Color(25,69,49):new Color(222,247,230)):dirty?(dark?new Color(83,67,22):new Color(255,243,190)):background);setForeground(UIManager.getColor("Table.foreground"));}
            arrow=row==grid.getSelectedRow()&&column==grid.getSelectedColumn();reference=arrow&&hasReference(grid.convertColumnIndexToModel(column));if(arrow)setBorder(BorderFactory.createCompoundBorder(getBorder(),BorderFactory.createEmptyBorder(0,0,0,reference?44:18)));
            setToolTipText(model.isDeleted(index)?tr("Fila pendiente de eliminar"):dirty?tr("Cambio sin guardar"):arrow?tr("Doble clic para editar; clic en ▾ para abrir el menú."):null);return this;
        }
        protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(arrow){graphics.setColor(getForeground());int x=getWidth()-12,y=getHeight()/2;graphics.drawLine(x-3,y-1,x,y+2);graphics.drawLine(x,y+2,x+3,y-1);if(reference)graphics.drawString("…",getWidth()-36,y+4);}}
    }
}
