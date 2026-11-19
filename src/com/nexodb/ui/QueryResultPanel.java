package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.util.DataExporter;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextAttribute;
import java.util.EventObject;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import static com.nexodb.util.I18n.tr;

/** Editable result grid: changes are staged until the user explicitly saves them. */
final class QueryResultPanel extends JPanel {
    interface ColumnEditor {void open(JdbcSession session,QueryEditSupport.Target target,String column,boolean delete);}
    private final JdbcSession origin;
    private QueryEditSupport.Target target;
    private List<QueryEditSupport.Target> editTargets=List.of();
    private final ResultTableModel model;
    private final JTable table;
    private final JScrollPane scroll;
    private final String sql;
    private final int limit;
    private String viewSql;
    private List<Object> viewParameters=List.of();
    private final boolean serverView;
    private final List<String> viewColumns;
    private final List<Integer> viewTypes;
    private final QueryEditSupport.Eligibility viewEditing;
    private final List<QueryResult.Origin> viewOrigins;
    private final Timer viewTimer,countTimer;
    private ResultGridActions.ViewState activeView=new ResultGridActions.ViewState("",Map.of(),List.of()),pendingView=activeView;
    private final JToggleButton edit = UiLanguage.text(new JToggleButton(),UiLanguage.key("Editar"));
    private final JComboBox<String> mode=new JComboBox<>(new String[]{"Solo lectura"});
    private boolean updatingMode;
    private Runnable writeEnabled=()->{};
    private java.util.function.IntSupplier rowLimit;
    private java.util.function.BiPredicate<String,String> confirm=(title,text)->Confirmations.ask(this,title,text);
    private final JButton save = UiLanguage.text(new JButton(),UiLanguage.key("Guardar"));
    private final JButton addRow = UiLanguage.text(new JButton(),UiLanguage.key("Nueva fila"));
    private final JButton discard = UiLanguage.text(new JButton(),UiLanguage.key("Descartar"));
    private final JButton setNull = new JButton("NULL");
    private final JButton reload = UiLanguage.text(new JButton(),UiLanguage.key("Recargar"));
    private final JTextField filter = new JTextField(14);
    private final JLabel status = new JLabel(), count = new JLabel();
    private final JLabel elapsed=new JLabel(),pageLabel=new JLabel(),totalLabel=new JLabel();
    private final JButton firstPage=new JButton("⏮"),previous=new JButton("◀"),next=new JButton("▶"),lastPage=new JButton("⏭"),countRows=UiLanguage.button("Contar");
    private final JTextField pageNumber=new JTextField(4);
    private final JComboBox<String> pageSizeChoice=new JComboBox<>(new String[]{"100","500","1000","Personalizado…"});
    private long pageOffset;
    private int pageSize=1000;
    private boolean changingPageSize;
    private boolean pagingRequested;
    private final boolean pageable;
    private final String pageCatalog,pageSchema;
    private final Consumer<Boolean> busy;
    private final Consumer<String> changed;
    private final Consumer<JTable> resize;
    private final ResultGridActions grid;
    private Runnable exportAction;
    private SwingWorker<Long,Long> exportWorker;
    private SwingWorker<Long,Void> countWorker;
    private long totalRows=-1,estimatedRows;
    private int countGeneration;
    private boolean pendingLastPage;
    private boolean available, saving, pendingCommit, stale;
    private List<ForeignKeyLookup.Reference> references=List.of();
    private final JButton referenceButton=UiLanguage.button("Ver referencia");
    private int referenceGeneration;
    private int enumGeneration;
    private int activeModelRow=-1;
    private long renderMillis;
    private final Set<Integer> autoSaveRows=new LinkedHashSet<>();
    private boolean autoSaveScheduled;
    private record PageKey(long offset,int size) {}
    private record PageAnchor(List<QueryPaging.Order> order,List<Object> values) {private PageAnchor{order=List.copyOf(order);values=List.copyOf(values);}}
    private record LoadedPage(QueryResult result,boolean keyset) {}
    private final Map<PageKey,QueryResult> pageCache=new LinkedHashMap<>(4,0.75f,true){protected boolean removeEldestEntry(Map.Entry<PageKey,QueryResult> eldest){return size()>3;}};
    private final Map<PageKey,PageAnchor> pageAnchors=new LinkedHashMap<>();
    private final Set<PageKey> keysetPages=new LinkedHashSet<>();
    private boolean keysetActive;

    QueryResultPanel(JdbcSession origin, QueryResult result, ResultTableModel model, JTable table, String sql, int limit,
                     Consumer<Boolean> busy, Consumer<String> changed, Consumer<JTable> resize) {
        super(new BorderLayout());this.origin=origin;this.model=model;this.table=table;this.sql=sql;this.viewSql=sql;this.limit=limit;
        pageable=QueryPaging.supported(sql);
        viewColumns=List.copyOf(result.columns().subList(0,result.visibleColumnCount()));viewTypes=List.copyOf(result.jdbcTypes().subList(0,result.visibleColumnCount()));viewEditing=result.editing();viewOrigins=result.origins();
        serverView=(result.truncated()||limit==Integer.MAX_VALUE)&&result.hiddenColumns()==0&&ResultQuery.supported(sql,origin.profile().type(),viewColumns);
        pageCatalog=namespace(origin,true);pageSchema=namespace(origin,false);
        pageSize=Math.min(10_000,Math.max(1,limit==Integer.MAX_VALUE?1000:limit));
        rowLimit=()->this.limit;
        this.busy=busy;this.changed=changed;this.resize=resize;
        configureTargets(result,null);
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
        button(addRow,"query-add-row",IconFactory.Kind.ADD,"Agregar una fila nueva");
        button(save,"query-save",IconFactory.Kind.SAVE,"Guardar cambios");
        button(discard,"query-discard",IconFactory.Kind.CANCEL,"Descartar cambios");
        button(setNull,"query-null",IconFactory.Kind.DELETE,"Establecer NULL");
        button(reload,"query-reload",IconFactory.Kind.REFRESH,"Recargar consulta");
        mode.setName("query-result-mode");UiLanguage.choices(mode);mode.setMaximumSize(new Dimension(180,28));toolbar.add(mode);toolbar.add(edit);toolbar.add(addRow);toolbar.add(save);toolbar.add(discard);toolbar.add(setNull);toolbar.addSeparator();toolbar.add(reload);toolbar.addSeparator();
        mode.addActionListener(e->{if(!updatingMode)selectMode();});
        JButton actions=new JButton("▾");actions.setName("query-actions");UiLanguage.tooltip(actions,UiLanguage.key("Acciones del resultado"));toolbar.add(actions);
        toolbar.add(new JLabel(IconFactory.of(IconFactory.Kind.SEARCH,15)));
        UiLanguage.placeholder(filter,"Filtrar resultados…");filter.setName("query-filter");filter.setMaximumSize(new Dimension(200,28));toolbar.add(filter);
        JPanel footer=new JPanel(new BorderLayout(8,3));footer.setBorder(BorderFactory.createEmptyBorder(4,5,4,5));
        JPanel counts=new JPanel(new BorderLayout(8,0));counts.add(status);counts.add(count,BorderLayout.EAST);footer.add(counts,BorderLayout.NORTH);
        JPanel paging=new JPanel(new FlowLayout(FlowLayout.LEFT,7,0));paging.add(firstPage);paging.add(previous);paging.add(pageLabel);paging.add(next);paging.add(lastPage);paging.add(UiLanguage.label("Página:"));paging.add(pageNumber);paging.add(totalLabel);paging.add(countRows);paging.add(UiLanguage.label("Filas por página:"));paging.add(pageSizeChoice);paging.add(elapsed);footer.add(paging,BorderLayout.SOUTH);
        firstPage.setName("query-page-first");previous.setName("query-page-previous");next.setName("query-page-next");lastPage.setName("query-page-last");pageNumber.setName("query-page-number");totalLabel.setName("query-total-rows");countRows.setName("query-count-rows");pageSizeChoice.setName("query-page-size");elapsed.setName("query-elapsed");
        for(JButton button:List.of(firstPage,previous,next,lastPage))button.setMargin(new Insets(2,7,2,7));pageNumber.setHorizontalAlignment(JTextField.RIGHT);pageNumber.setMaximumSize(new Dimension(60,26));countRows.setMargin(new Insets(2,7,2,7));
        UiLanguage.tooltip(firstPage,UiLanguage.key("Primera página"));UiLanguage.tooltip(previous,UiLanguage.key("Página anterior"));UiLanguage.tooltip(next,UiLanguage.key("Página siguiente"));UiLanguage.tooltip(lastPage,UiLanguage.key("Última página"));UiLanguage.tooltip(pageNumber,UiLanguage.key("Escribe el número de página y presiona Enter"));
        changingPageSize=true;String initialSize=""+pageSize;if(!java.util.Set.of("100","500","1000").contains(initialSize))pageSizeChoice.insertItemAt(initialSize,0);pageSizeChoice.setSelectedItem(initialSize);changingPageSize=false;
        UiLanguage.tooltip(pageSizeChoice,UiLanguage.key(serverView?"La paginación, los filtros y el orden se ejecutan sobre todo el resultado en la base de datos.":"Cambiar de página vuelve a ejecutar el SELECT. Usa ORDER BY para mantener un orden estable."));
        firstPage.addActionListener(e->loadPage(0,pageSize));previous.addActionListener(e->loadPage(Math.max(0,pageOffset-pageSize),pageSize));next.addActionListener(e->loadPage(pageOffset+model.dataRowCount(),pageSize));lastPage.addActionListener(e->openLastPage());pageNumber.addActionListener(e->openPageNumber());countRows.addActionListener(e->{if(countWorker!=null&&!countWorker.isDone())cancelCount(true);else startCount(true);});
        pageSizeChoice.addActionListener(e->{if(changingPageSize||!pageable)return;String choice=(String)pageSizeChoice.getSelectedItem();int size=pageSize;
            try{if("Personalizado…".equals(choice)){String value=JOptionPane.showInputDialog(this,tr("Filas por página (1 a 10000):"),pageSize);if(value==null){restorePageChoice();return;}size=Integer.parseInt(value.trim());}else size=Integer.parseInt(choice);if(size<1||size>10000)throw new NumberFormatException();loadPage(0,size);}catch(NumberFormatException error){JOptionPane.showMessageDialog(this,tr("El tamaño de página debe estar entre 1 y 10.000 filas."));restorePageChoice();}});
        scroll=new JScrollPane(table);
        grid=new ResultGridActions(table,model,scroll,filter,this::updateCount,changed);
        viewTimer=new Timer(350,event->applyServerView());viewTimer.setRepeats(false);countTimer=new Timer(700,event->{if(totalRows<0&&countWorker==null&&QueryPaging.cheapCount(viewSql))startCount(false);});countTimer.setRepeats(false);
        if(serverView){
            grid.serverView(this::scheduleServerView);
            filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){private void changed(){scheduleServerView(grid.viewState());}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}});
            UiLanguage.tooltip(filter,UiLanguage.key("Busca en todo el resultado; el filtro se ejecuta en la base de datos."));
        }
        grid.exportAction(this::exportResult);
        toolbar.add(ResultExports.button(this,target==null?"resultado":target.qualifiedTable(),this::exportResult));
        grid.editingActions(new ResultGridActions.EditingActions(this::editSelectedCell,()->insertRow(false),this::deleteRows,this::save,this::requestDiscard,this::setSelectionValue,this::pasteSelectionValue,()->insertRow(true),()->{if(exportAction!=null)exportAction.run();},this::canWrite,this::hasChanges,this::canEditSelectedCell,()->origin.profile().type()!=com.nexodb.model.DatabaseType.SQLITE,()->exportAction!=null));
        grid.referenceAction(()->openReference(table.getSelectedRow(),table.getSelectedColumn()),()->table.getSelectedColumn()>=0&&hasReference(table.convertColumnIndexToModel(table.getSelectedColumn())));
        referenceButton.setName("query-reference");referenceButton.setIcon(IconFactory.of(IconFactory.Kind.KEY,16));referenceButton.addActionListener(e->openReference(table.getSelectedRow(),table.getSelectedColumn()));toolbar.add(referenceButton);
        TableCellRenderer normalHeader=table.getTableHeader().getDefaultRenderer();table.getTableHeader().setDefaultRenderer((t,v,s,f,r,c)->{Component component=normalHeader.getTableCellRendererComponent(t,v,s,f,r,c);if(component instanceof JLabel label){int index=table.convertColumnIndexToModel(c);Icon base=label.getIcon();if(base instanceof ReferenceHeaderIcon composed)base=composed.base();label.setIcon(hasReference(index)?new ReferenceHeaderIcon(base,IconFactory.of(IconFactory.Kind.KEY,14)):base);label.setToolTipText(references.stream().filter(ref->ref.contains(index)).map(ref->ref.label()+" · "+String.join(", ",ref.parts().stream().map(p->p.local()+" → "+p.referenced()).toList())).collect(java.util.stream.Collectors.joining("; ")));}return component;});
        actions.addActionListener(event->grid.showMenu(actions));
        add(toolbar,BorderLayout.NORTH);add(scroll,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH);
        setHint(result);
        edit.addActionListener(event->toggle());save.addActionListener(event->save());
        addRow.addActionListener(event->insertRow(false));
        discard.addActionListener(event->requestDiscard());
        reload.addActionListener(event->reload());
        setNull.addActionListener(event->setSelectionValue(null));
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){if(SwingUtilities.isLeftMouseButton(event)&&event.getClickCount()==2){int row=table.rowAtPoint(event.getPoint()),column=table.columnAtPoint(event.getPoint());if(row>=0&&column>=0)openOrEditCell(row,column,event);}}});
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){int row=table.rowAtPoint(e.getPoint()),col=table.columnAtPoint(e.getPoint());if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==1&&row>=0&&col>=0&&hasReference(table.convertColumnIndexToModel(col))){Rectangle cell=table.getCellRect(row,col,false);if(e.getX()>=cell.x+cell.width-44&&e.getX()<cell.x+cell.width-18)openReference(row,col);}}});
        table.getInputMap().put(KeyStroke.getKeyStroke("control SPACE"),"lookup-reference");table.getActionMap().put("lookup-reference",new AbstractAction(){public void actionPerformed(ActionEvent e){openReference(table.getSelectedRow(),table.getSelectedColumn());}});
        table.getInputMap().put(KeyStroke.getKeyStroke("F2"),"edit-result-cell");table.getActionMap().put("edit-result-cell",new AbstractAction(){public void actionPerformed(ActionEvent event){editSelectedCell();}});
        installVirtualPaging();
        table.getSelectionModel().addListSelectionListener(event->rowSelectionChanged(event.getValueIsAdjusting()));
        table.getColumnModel().getSelectionModel().addListSelectionListener(event->updateActions());
        model.onChange(()->{updateActions();table.repaint();});
        setAvailable(origin,false);
        estimatedRows=result.rows().size()+(result.truncated()?1:0);if(!result.truncated())totalRows=result.rows().size();
        if(pageable){PageKey first=new PageKey(0,pageSize);pageCache.put(first,result);List<QueryPaging.Order> order=seekOrder();if(initialKeysetReady(order)){keysetPages.add(first);rememberNextAnchor(0,pageSize,result,order);keysetActive=true;}else if(result.truncated()&&!order.isEmpty())SwingUtilities.invokeLater(()->loadPage(0,pageSize,(fresh,error)->{},false));}
        updateCount();if(totalRows<0&&pageable)countTimer.restart();
        loadReferences();
    }
    private static void button(AbstractButton button,String name,IconFactory.Kind icon,String hint){button.setName(name);button.setIcon(IconFactory.of(icon,16));UiLanguage.tooltip(button,UiLanguage.key(hint));}
    private void configureTargets(QueryResult result,String preferred){
        autoSaveRows.clear();activeModelRow=-1;
        editTargets=result.editing()==null?List.of():result.editing().targets();
        target=editTargets.stream().filter(item->preferred!=null&&item.qualifiedTable().equals(preferred)).findFirst().orElse(editTargets.isEmpty()?null:editTargets.get(0));
        model.setTarget(target);
        updatingMode=true;
        try{
            mode.removeAllItems();mode.addItem("Solo lectura");
            for(QueryEditSupport.Target item:editTargets)mode.addItem(editTargets.size()==1?"Editar datos · "+item.qualifiedTable():item.qualifiedTable());
            mode.setMaximumSize(new Dimension(editTargets.size()>1?260:210,28));mode.setSelectedIndex(0);
        }finally{updatingMode=false;}
        loadEnumEditors();
    }
    private void selectMode(){
        int index=mode.getSelectedIndex();
        if(index<=0){edit.setSelected(false);toggle();return;}
        QueryEditSupport.Target selected=editTargets.get(index-1);
        if(target!=selected&&hasChanges()&&!confirmDiscard()){updateActions();return;}
        target=selected;model.setTarget(target);edit.setSelected(true);toggle();
    }
    JdbcSession origin(){return origin;}
    void setWriteEnabled(Runnable callback){writeEnabled=callback;}
    void setRowLimit(java.util.function.IntSupplier supplier){rowLimit=supplier;}
    void setRenderMillis(long value){renderMillis=Math.max(0,value);updateCount();}
    void resetReadOnly(){finishEditing();edit.setSelected(false);updateActions();}
    void setExportAction(Runnable action){exportAction=action;}
    boolean canCancel(){return exportWorker!=null&&!exportWorker.isDone()||countWorker!=null&&!countWorker.isDone();}
    void cancelOperation(){if(exportWorker!=null&&!exportWorker.isDone()){exportWorker.cancel(true);try{origin.cancel();}catch(java.sql.SQLException ignored){}changed.accept(tr("Exportación cancelada"));return;}cancelCount(true);}
    void exportData(){exportResult(null);}
    private void exportResult(DataExporter.Format initial){
        String name=target==null?"resultado":target.qualifiedTable();QueryResult snapshot=model.snapshot();
        boolean full=pageable&&!hasChanges()&&(serverView||pagingRequested||limit==Integer.MAX_VALUE||snapshot.truncated());
        if(!full){ResultExports.write(this,snapshot,name,initial==null?DataExporter.Format.CSV:initial,changed);return;}
        ExportChooser.Request request=initial==null?ExportChooser.choose(this,name):ExportChooser.choose(this,name,initial);if(request==null)return;
        String exportSql=viewSql;List<Object> parameters=List.copyOf(viewParameters);
        exportWorker=new SwingWorker<Long,Long>(){
            protected Long doInBackground()throws Exception{
                return DataExporter.writeStreaming(request.path(),request.format(),name,writer->origin.streamResult(exportSql,parameters,Long.MAX_VALUE,new JdbcSession.ResultStream(){
                    public void start(List<String> columns,List<Integer> types)throws Exception{writer.start(columns);}
                    public void row(List<Object> values)throws Exception{writer.row(values);}
                },rows->{if(!isCancelled())publish(rows);}));
            }
            protected void process(List<Long> values){if(!values.isEmpty())changed.accept(tr("Exportando ")+values.get(values.size()-1)+tr(" filas…"));}
            protected void done(){try{if(isCancelled()){changed.accept(tr("Exportación cancelada"));return;}long rows=get();changed.accept(request.format()+tr(" exportado · ")+rows+tr(" filas · ")+request.path().getFileName());}catch(Exception error){if(!isCancellation(error))showError(root(error),"No se pudo exportar");}finally{exportWorker=null;end();}}
        };start();UiLanguage.text(status,UiLanguage.key("Exportando resultado completo…"));exportWorker.execute();
    }
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
        else UiLanguage.text(status,UiLanguage.key("Selecciona una tabla para editar. Al pasar a otra fila se guarda la fila anterior; Guardar aplica los demás cambios pendientes."));
    }
    private void updateCount(){
        UiLanguage.text(count,UiLanguage.key("Filas: "),model.dataRowCount(),UiLanguage.key(" · Marcadas: "),grid==null?0:grid.checkedCount(),UiLanguage.key(" · Cambios: "),model.changeCount());
        QueryResult.Timing timing=model.definition().timing();
        String text=tr("Tiempo: ")+timing.totalMillis()+" ms · "+tr("servidor ")+timing.serverMillis()+" · "+tr("lectura ")+timing.fetchMillis()+" · "+tr("preparación ")+timing.metadataMillis()+" · "+tr("interfaz ")+renderMillis;
        elapsed.setText(text);
        elapsed.setToolTipText("<html>"+tr("Tiempo total hasta recibir y preparar el resultado:")+" <b>"+timing.totalMillis()+" ms</b><br>"+tr("Servidor/red hasta responder:")+" "+timing.serverMillis()+" ms<br>"+tr("Lectura y conversión de filas:")+" "+timing.fetchMillis()+" ms<br>"+tr("Metadatos y edición segura:")+" "+timing.metadataMillis()+" ms<br>"+tr("Construcción de la cuadrícula:")+" "+renderMillis+" ms</html>");
        pageLabel.setText((model.dataRowCount()==0?0:pageOffset+1)+"–"+(pageOffset+model.dataRowCount())+(model.definition().truncated()?" · +":"")+(keysetActive?" · clave":""));
        pageLabel.setToolTipText(keysetActive?tr("Paginación por clave activa: esta página no recorrió las filas anteriores."):tr("Paginación compatible por desplazamiento."));
        long pages=totalRows<0?-1:Math.max(1,(totalRows+pageSize-1)/pageSize);if(!pageNumber.isFocusOwner())pageNumber.setText(""+(pageOffset/pageSize+1));
        totalLabel.setText(totalRows>=0?tr("de ")+totalRows+tr(" filas · ")+pages+tr(" páginas"):tr("al menos ")+Math.max(estimatedRows,pageOffset+model.dataRowCount()+(model.definition().truncated()?1:0))+tr(" filas"));
        boolean idle=available&&!saving&&pageable;firstPage.setEnabled(idle&&pageOffset>0);previous.setEnabled(idle&&pageOffset>0);next.setEnabled(idle&&model.definition().truncated());lastPage.setEnabled(idle&&(totalRows<0||pageOffset+model.dataRowCount()<totalRows));pageNumber.setEnabled(idle);pageSizeChoice.setEnabled(idle);
        boolean counting=countWorker!=null&&!countWorker.isDone();countRows.setText(tr(counting?"Cancelar conteo":"Contar"));countRows.setEnabled(pageable&&(counting||available&&!saving&&totalRows<0));
    }

    private void startCount(boolean requested){
        if(!pageable||!available||saving||totalRows>=0)return;if(countWorker!=null&&!countWorker.isDone()){if(requested)cancelCount(true);return;}
        boolean cheap=QueryPaging.cheapCount(viewSql);if(requested&&!cheap&&!confirm.test("Conteo exacto",tr("Esta consulta puede requerir un conteo costoso en el servidor y se cancelará automáticamente después de 20 segundos. ¿Continuar?"))){pendingLastPage=false;return;}
        int generation=++countGeneration;String source=viewSql;List<Object> parameters=List.copyOf(viewParameters);UiLanguage.text(status,UiLanguage.key("Calculando total…"));updateCount();
        countWorker=new SwingWorker<>(){
            protected Long doInBackground()throws Exception{return origin.countRows(source,parameters,20);}
            protected void done(){
                if(generation!=countGeneration)return;boolean openLast=pendingLastPage;pendingLastPage=false;
                try{totalRows=get();estimatedRows=Math.max(estimatedRows,totalRows);UiLanguage.text(status,UiLanguage.key("Total calculado: "),totalRows,UiLanguage.key(" filas"));}
                catch(Exception error){Throwable cause=root(error);if(!isCancellation(cause)){String message=cause instanceof java.sql.SQLTimeoutException||java.util.Objects.toString(cause.getMessage(),"").toLowerCase(java.util.Locale.ROOT).contains("timeout")?tr("El conteo superó 20 segundos y fue cancelado. Puedes continuar navegando con la estimación."):tr("No se pudo calcular el total: ")+java.util.Objects.toString(cause.getMessage(),cause.getClass().getSimpleName());UiLanguage.text(status,UiLanguage.key(message));changed.accept(message);}openLast=false;}
                finally{countWorker=null;busy.accept(false);updateCount();}
                if(openLast)SwingUtilities.invokeLater(QueryResultPanel.this::loadLastPage);
            }
        };busy.accept(true);countWorker.execute();updateCount();
    }
    private void cancelCount(boolean report){
        countTimer.stop();pendingLastPage=false;SwingWorker<Long,Void> worker=countWorker;if(worker==null||worker.isDone())return;countGeneration++;worker.cancel(true);countWorker=null;try{origin.cancel();}catch(java.sql.SQLException ignored){}busy.accept(false);if(report){UiLanguage.text(status,UiLanguage.key("Conteo cancelado"));changed.accept(tr("Conteo cancelado"));}updateCount();
    }
    private void resetTotal(){cancelCount(false);totalRows=-1;estimatedRows=0;pendingLastPage=false;updateCount();}
    private void scheduleCount(){if(totalRows<0&&pageable&&QueryPaging.cheapCount(viewSql))countTimer.restart();}

    private void openPageNumber(){
        if(!pageable||saving)return;try{long page=Long.parseLong(pageNumber.getText().trim());if(page<1)throw new NumberFormatException();long offset=Math.multiplyExact(page-1L,pageSize);if(totalRows>=0&&offset>=Math.max(totalRows,1))throw new NumberFormatException();
            if(offset>100_000&&!pageAnchors.containsKey(new PageKey(offset,pageSize))&&!confirm.test("Abrir página",tr("Ir directamente a una página profunda puede ser lento porque todavía no existe un cursor para esa posición. ¿Continuar?"))){updateCount();return;}loadPage(offset,pageSize);
        }catch(ArithmeticException|NumberFormatException error){JOptionPane.showMessageDialog(this,totalRows>=0?tr("Escribe una página entre 1 y ")+Math.max(1,(totalRows+pageSize-1)/pageSize)+".":tr("Escribe un número de página válido."));updateCount();}
    }
    private void openLastPage(){if(totalRows<0){pendingLastPage=true;startCount(true);return;}loadLastPage();}
    private void loadLastPage(){
        if(totalRows<0||!available||saving||!pageable)return;long offset=totalRows==0?0:((totalRows-1)/pageSize)*pageSize;if(offset==pageOffset)return;PageKey key=new PageKey(offset,pageSize);QueryResult cached=pageCache.get(key);if(cached!=null){showPage(cached,offset,pageSize,keysetPages.contains(key));return;}
        if(!confirmDiscard())return;if(!pageCatalog.equals(namespace(origin,true))||!pageSchema.equals(namespace(origin,false))){showError(new IllegalStateException(tr("La base o el esquema activo cambió.")),"Última página");return;}cancelCount(false);start();UiLanguage.text(status,UiLanguage.key("Cargando última página…"));
        new SwingWorker<LoadedPage,Void>(){
            protected LoadedPage doInBackground()throws Exception{
                List<QueryPaging.Order> order=seekOrder();if(!order.isEmpty())try{int rows=(int)Math.max(1,totalRows-offset);QueryResult reverse=origin.executeSeekPage(viewSql,viewParameters,rows,viewColumns,QueryPaging.reverse(order),List.of());List<List<Object>> values=new java.util.ArrayList<>(reverse.rows());java.util.Collections.reverse(values);return new LoadedPage(reverse.withRows(values,false).withContext(viewEditing,viewOrigins),true);}catch(IllegalArgumentException error){}catch(java.sql.SQLException error){String state=java.util.Objects.toString(error.getSQLState(),"");if(state.startsWith("08")||state.equals("57014")||state.equalsIgnoreCase("HY008"))throw error;}
                QueryResult fallback=origin.executePage(viewSql,viewParameters,pageSize,offset);return new LoadedPage(!viewSql.equals(sql)&&viewEditing!=null?fallback.withContext(viewEditing,viewOrigins):fallback,false);
            }
            protected void done(){try{LoadedPage loaded=get();pageCache.put(key,loaded.result());if(loaded.keyset())keysetPages.add(key);showPage(loaded.result(),offset,pageSize,loaded.keyset());}catch(Exception error){showError(root(error),"No se pudo cargar la última página");}finally{end();}}
        }.execute();
    }
    private static String namespace(JdbcSession origin,boolean catalog){if(origin==null)return "";try{return java.util.Objects.toString(catalog?origin.connection().getCatalog():origin.connection().getSchema(),"");}catch(Exception error){return "";}}
    private void restorePageChoice(){changingPageSize=true;String value=""+pageSize;boolean present=false;for(int i=0;i<pageSizeChoice.getItemCount();i++)if(value.equals(pageSizeChoice.getItemAt(i)))present=true;if(!present)pageSizeChoice.insertItemAt(value,0);pageSizeChoice.setSelectedItem(value);changingPageSize=false;}
    private void scheduleServerView(ResultGridActions.ViewState state){if(!serverView)return;pendingView=state;viewTimer.restart();}
    private void applyServerView(){
        if(!serverView||pendingView.equals(activeView))return;if(saving||hasChanges()){viewTimer.restart();return;}
        try{
            List<ResultQuery.Filter> filters=new java.util.ArrayList<>();
            for(var entry:pendingView.filters().entrySet())filters.add(resultFilter(entry.getKey(),entry.getValue()));
            List<ResultQuery.Sort> sorts=pendingView.sorts().stream().filter(key->key.getSortOrder()!=SortOrder.UNSORTED).map(key->new ResultQuery.Sort(key.getColumn(),key.getSortOrder()==SortOrder.DESCENDING)).toList();
            ResultQuery.Prepared prepared=ResultQuery.prepare(sql,origin.profile().type(),viewColumns,viewTypes,pendingView.search(),filters,sorts);
            String previousSql=viewSql;List<Object> previousParameters=viewParameters;ResultGridActions.ViewState previousView=activeView;
            activeView=pendingView;viewSql=prepared.sql();viewParameters=prepared.parameters();resetTotal();resetPagingState();
            loadPage(0,pageSize,(fresh,error)->{if(error!=null){viewSql=previousSql;viewParameters=previousParameters;activeView=previousView;resetTotal();resetPagingState();scheduleCount();showError(error,"No se pudo aplicar el filtro u orden");}},false);
        }catch(Exception error){showError(error,"No se pudo aplicar el filtro u orden");}
    }
    private static ResultQuery.Filter resultFilter(int column,ResultGridActions.Filter filter){
        return new ResultQuery.Filter(column,resultCondition(filter.first()),ResultQuery.Junction.valueOf(filter.junction().name()),resultCondition(filter.second()),filter.caseSensitive());
    }
    private static ResultQuery.Condition resultCondition(ResultGridActions.Condition condition){return condition==null?null:new ResultQuery.Condition(ResultQuery.Operator.valueOf(condition.operator().name()),condition.value());}
    private List<QueryPaging.Order> seekOrder(){
        if(viewEditing==null||viewEditing.targets().size()!=1)return List.of();QueryEditSupport.Target source=viewEditing.targets().get(0);if(source.keys().isEmpty())return List.of();
        List<QueryPaging.Order> result=new java.util.ArrayList<>();
        if(!activeView.sorts().isEmpty())for(RowSorter.SortKey key:activeView.sorts())if(key.getSortOrder()!=SortOrder.UNSORTED)result.add(new QueryPaging.Order(key.getColumn(),key.getSortOrder()==SortOrder.DESCENDING));
        else{QueryPaging.OrderAnalysis original=QueryPaging.order(sql,viewColumns);if(!original.supported())return List.of();result.addAll(original.orders());}
        for(int key:source.keys()){if(key<0||key>=viewColumns.size())return List.of();if(result.stream().noneMatch(item->item.column()==key))result.add(new QueryPaging.Order(key,false));}
        Set<Integer> unique=new LinkedHashSet<>();for(QueryPaging.Order item:result)if(item.column()<0||item.column()>=viewTypes.size()||!unique.add(item.column())||!QueryPaging.comparable(viewTypes.get(item.column())))return List.of();
        return List.copyOf(result);
    }
    private boolean initialKeysetReady(List<QueryPaging.Order> order){
        if(order.isEmpty()||!activeView.sorts().isEmpty())return false;QueryPaging.OrderAnalysis original=QueryPaging.order(sql,viewColumns);return original.present()&&original.supported()&&original.orders().equals(order);
    }
    private LoadedPage executeViewPage(int size,long offset)throws java.sql.SQLException{
        List<QueryPaging.Order> order=seekOrder();PageAnchor anchor=offset==0?new PageAnchor(order,List.of()):pageAnchors.get(new PageKey(offset,size));
        if(!order.isEmpty()&&anchor!=null&&anchor.order().equals(order))try{
            QueryResult fresh=origin.executeSeekPage(viewSql,viewParameters,size,viewColumns,order,anchor.values());
            return new LoadedPage(fresh.withContext(viewEditing,viewOrigins),true);
        }catch(IllegalArgumentException error){}catch(java.sql.SQLException error){String state=java.util.Objects.toString(error.getSQLState(),"");if(Thread.currentThread().isInterrupted()||state.startsWith("08")||state.equals("57014")||state.equalsIgnoreCase("HY008"))throw error;}
        QueryResult fresh=origin.executePage(viewSql,viewParameters,size,offset);return new LoadedPage(!viewSql.equals(sql)&&viewEditing!=null?fresh.withContext(viewEditing,viewOrigins):fresh,false);
    }
    private void resetPagingState(){pageCache.clear();pageAnchors.clear();keysetPages.clear();keysetActive=false;}
    private void rememberNextAnchor(long offset,int size,QueryResult result,List<QueryPaging.Order> order){
        if(order.isEmpty()||result.rows().isEmpty()||!result.truncated())return;List<Object> row=result.rows().get(result.rows().size()-1),values=new java.util.ArrayList<>();
        for(QueryPaging.Order item:order){if(item.column()>=row.size()||row.get(item.column())==null)return;values.add(row.get(item.column()));}
        pageAnchors.put(new PageKey(offset+result.rows().size(),size),new PageAnchor(order,values));
    }
    private void loadPage(long offset,int size){loadPage(offset,size,(fresh,error)->{if(error!=null)showError(error,"No se pudo cargar la página");});}
    private void loadPage(long offset,int size,java.util.function.BiConsumer<QueryResult,Throwable> completion){
        loadPage(offset,size,completion,true);
    }
    private void loadPage(long offset,int size,java.util.function.BiConsumer<QueryResult,Throwable> completion,boolean useCache){
        if(!available||saving||!pageable){restorePageChoice();completion.accept(null,new IllegalStateException(tr("El resultado no está disponible para actualizarse.")));return;}
        if(!confirmDiscard()){restorePageChoice();completion.accept(null,new java.util.concurrent.CancellationException(tr("Actualización cancelada.")));return;}
        if(!pageCatalog.equals(namespace(origin,true))||!pageSchema.equals(namespace(origin,false))){restorePageChoice();completion.accept(null,new IllegalStateException(tr("La base o el esquema activo cambió. Vuelve al origen del resultado antes de paginar.")));return;}
        if(useCache){PageKey current=new PageKey(pageOffset,pageSize),requested=new PageKey(offset,size);pageCache.put(current,model.definition());QueryResult cached=pageCache.get(requested);if(cached!=null){showPage(cached,offset,size,keysetPages.contains(requested));completion.accept(cached,null);return;}}
        cancelCount(false);
        start();UiLanguage.text(status,UiLanguage.key("Cargando página…"));
        new SwingWorker<LoadedPage,Void>() {
            protected LoadedPage doInBackground()throws Exception{return executeViewPage(size,offset);}
            protected void done(){try{LoadedPage loaded=get();PageKey key=new PageKey(offset,size);pageCache.put(key,loaded.result());if(loaded.keyset())keysetPages.add(key);else keysetPages.remove(key);showPage(loaded.result(),offset,size,loaded.keyset());completion.accept(loaded.result(),null);}catch(Exception error){completion.accept(null,root(error));}finally{restorePageChoice();end();}}
        }.execute();
    }
    private void showPage(QueryResult fresh,long offset,int size,boolean keyset){
        long renderStarted=System.nanoTime();String selected=target==null?null:target.qualifiedTable();int horizontal=scroll.getHorizontalScrollBar().getValue();pageOffset=offset;pageSize=size;pagingRequested=true;keysetActive=keyset;estimatedRows=Math.max(estimatedRows,offset+fresh.rows().size()+(fresh.truncated()?1:0));if(!fresh.truncated())totalRows=offset+fresh.rows().size();grid.clearChecked();grid.refreshing(()->model.setResult(fresh));configureTargets(fresh,selected);stale=false;edit.setSelected(false);resize.accept(table);scroll.getViewport().setViewPosition(new Point(horizontal,0));if(keyset)rememberNextAnchor(offset,size,fresh,seekOrder());setHint(fresh);setRenderMillis((System.nanoTime()-renderStarted)/1_000_000L);scheduleCount();loadReferences();
    }
    private void installVirtualPaging(){
        scroll.addMouseWheelListener(e->{if(e.getWheelRotation()>0)SwingUtilities.invokeLater(this::loadNextIfNeeded);});
        scroll.getVerticalScrollBar().addMouseListener(new MouseAdapter(){public void mouseReleased(MouseEvent e){loadNextIfNeeded();}});
        table.addKeyListener(new KeyAdapter(){public void keyReleased(KeyEvent e){if(e.getKeyCode()==KeyEvent.VK_END||e.getKeyCode()==KeyEvent.VK_PAGE_DOWN||e.getKeyCode()==KeyEvent.VK_DOWN)SwingUtilities.invokeLater(QueryResultPanel.this::loadNextIfNeeded);}});
    }
    private void loadNextIfNeeded(){
        if(!pageable||!available||saving||hasChanges()||!model.definition().truncated())return;
        JScrollBar bar=scroll.getVerticalScrollBar();int margin=Math.max(48,table.getRowHeight()*3);
        if(bar.isVisible()&&bar.getValue()+bar.getVisibleAmount()>=bar.getMaximum()-margin)loadPage(pageOffset+model.dataRowCount(),pageSize);
    }
    private void updateActions(){
        boolean enabled=available&&!saving&&target!=null&&!origin.profile().readOnly();
        CellCursor cursor=currentCursor();edit.setEnabled(enabled&&!stale);model.setEditable(enabled&&!stale&&edit.isSelected());if(table.getSelectedRow()<0)restoreCursor(cursor);
        mode.setEnabled(enabled&&!stale);updatingMode=true;try{int index=edit.isSelected()?editTargets.indexOf(target)+1:0;mode.setSelectedIndex(Math.max(0,index));}finally{updatingMode=false;}
        addRow.setEnabled(enabled&&!stale&&edit.isSelected());save.setEnabled(enabled&&!stale&&edit.isSelected()&&hasChanges());discard.setEnabled(available&&!saving&&hasChanges());reload.setEnabled(available&&!saving&&SqlExecutionPolicy.readOnly(sql));
        int row=table.getSelectedRow(),column=table.getSelectedColumn();
        setNull.setEnabled(canEditSelectedCell());
        referenceButton.setEnabled(available&&!saving&&column>=0&&hasReference(table.convertColumnIndexToModel(column))&&row>=0);
        updateCount();
    }
    private void rowSelectionChanged(boolean adjusting){
        updateActions();if(adjusting)return;
        int view=table.getSelectedRow(),current=view<0?-1:table.convertRowIndexToModel(view);
        if(current==activeModelRow)return;
        int previous=activeModelRow;activeModelRow=current;
        if(previous>=0)queueAutoSave(previous);
    }
    private void queueAutoSave(int row){
        autoSaveRows.add(row);if(autoSaveScheduled)return;autoSaveScheduled=true;
        SwingUtilities.invokeLater(()->{autoSaveScheduled=false;flushAutoSave();});
    }
    private void flushAutoSave(){
        if(saving||!canWrite()||autoSaveRows.isEmpty())return;
        int row=autoSaveRows.iterator().next();List<QueryEditSupport.RowChange> changes=model.changesForRow(row);
        if(changes.isEmpty()){autoSaveRows.remove(row);flushAutoSave();return;}
        if(!finishEditing())return;
        start();UiLanguage.text(status,UiLanguage.key("Guardando la fila anterior…"));
        new SwingWorker<QueryEditSupport.BatchSaved,Void>(){
            protected QueryEditSupport.BatchSaved doInBackground()throws Exception{return QueryEditSupport.saveChanges(origin.connection(),target,changes);}
            protected void done(){
                try{QueryEditSupport.BatchSaved saved=get();pendingCommit|=saved.pendingCommit();CellCursor cursor=currentCursor();model.accept(saved.rows());restoreCursor(cursor);UiLanguage.text(status,UiLanguage.key(saved.pendingCommit()?"Fila aplicada · pendiente de Commit":"Fila guardada automáticamente"));changed.accept(tr(saved.pendingCommit()?"Fila aplicada · pendiente de Commit":"Fila guardada automáticamente"));}
                catch(Exception error){UiLanguage.text(status,UiLanguage.key("No se pudo guardar la fila; el cambio continúa pendiente."));showError(error,"No se pudo actualizar la fila");}
                finally{autoSaveRows.remove(row);end();SwingUtilities.invokeLater(()->flushAutoSave());}
            }
        }.execute();
    }
    private record CellCursor(int row,int column){}
    private CellCursor currentCursor(){int row=table.getSelectedRow(),column=table.getSelectedColumn();return new CellCursor(row<0?-1:table.convertRowIndexToModel(row),column<0?-1:table.convertColumnIndexToModel(column));}
    private void restoreCursor(CellCursor cursor){
        if(cursor==null||cursor.row()<0||cursor.column()<0||cursor.row()>=model.getRowCount()||cursor.column()>=model.getColumnCount())return;
        int row=table.convertRowIndexToView(cursor.row()),column=table.convertColumnIndexToView(cursor.column());if(row<0||column<0)return;
        table.changeSelection(row,column,false,false);table.scrollRectToVisible(table.getCellRect(row,column,true));
    }
    private void toggle(){
        if(!edit.isSelected()){finishEditing();updateActions();return;}
        if(!LicenseGate.ensure(this)){edit.setSelected(false);updateActions();return;}
        if(origin.profile().readOnly()||!confirm.test("Habilitar edición de datos",origin.profile().name()+"\n"+target.qualifiedTable()+"\n"+tr("Podrás modificar, agregar o marcar filas para eliminar. Las filas modificadas se guardan al pasar a otra fila; Guardar aplica los demás cambios pendientes. ¿Habilitar edición?"))){edit.setSelected(false);updateActions();return;}
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
    private void loadEnumEditors(){
        int generation=++enumGeneration;
        for(int column=0;column<table.getColumnModel().getColumnCount();column++)table.getColumnModel().getColumn(column).setCellEditor(null);
        if(origin==null||!origin.isOpen()||target==null||(target.type()!=com.nexodb.model.DatabaseType.MYSQL&&target.type()!=com.nexodb.model.DatabaseType.MARIADB))return;
        QueryEditSupport.Target requested=target;
        new SwingWorker<Map<Integer,List<String>>,Void>(){
            protected Map<Integer,List<String>> doInBackground()throws Exception{
                String database=requested.table().schema();if(database==null||database.isBlank())database=requested.table().catalog();
                if(database==null||database.isBlank())return Map.of();
                Map<String,String> types=new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                synchronized(origin.connection()){
                    try(var statement=origin.connection().prepareStatement("SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){
                        statement.setString(1,database);statement.setString(2,requested.table().name());
                        try(var rows=statement.executeQuery()){while(rows.next())types.put(rows.getString(1),rows.getString(2));}
                    }
                }
                Map<Integer,List<String>> choices=new LinkedHashMap<>();
                for(int column=0;column<requested.columns().size();column++){
                    List<String> values=enumChoices(types.get(requested.columns().get(column).sourceName()));if(!values.isEmpty())choices.put(column,values);
                }
                return choices;
            }
            protected void done(){
                if(generation!=enumGeneration||target!=requested)return;
                try{
                    for(var entry:get().entrySet())if(entry.getKey()<table.getColumnModel().getColumnCount()){
                        JComboBox<Object> choices=new JComboBox<>();choices.addItem(QueryEditSupport.DefaultValue.INSTANCE);entry.getValue().forEach(choices::addItem);
                        table.getColumnModel().getColumn(entry.getKey()).setCellEditor(new DefaultCellEditor(choices));
                    }
                }catch(Exception ignored){}
            }
        }.execute();
    }
    static List<String> enumChoices(String columnType){
        if(columnType==null)return List.of();String value=columnType.trim();
        if(value.length()<7||!value.regionMatches(true,0,"enum(",0,5)||value.charAt(value.length()-1)!=')')return List.of();
        try{return MysqlTableDesign.enumValues(value.substring(5,value.length()-1),false);}catch(IllegalArgumentException error){return List.of();}
    }
    private void openReference(int viewRow,int viewColumn){
        if(viewRow<0||viewColumn<0)return;int row=table.convertRowIndexToModel(viewRow),column=table.convertColumnIndexToModel(viewColumn);if(finishEditing())openReferenceModel(row,column);
    }
    private void openReferenceModel(int row,int column){
        if(!available||saving||row<0||row>=model.getRowCount())return;
        List<ForeignKeyLookup.Reference> matches=references.stream().filter(r->r.contains(column)).toList();if(matches.isEmpty())return;
        ForeignKeyLookup.Reference reference=matches.stream().filter(ForeignKeyLookup.Reference::complete).findFirst().orElse(matches.get(0));
        boolean writable=canWrite()&&!model.isDeleted(row)&&reference.complete()&&reference.parts().stream().allMatch(p->model.isCellEditable(row,p.column()));
        List<Object> values=new java.util.ArrayList<>();for(int i=0;i<model.getColumnCount();i++)values.add(model.getValueAt(row,i));
        Map<Integer,Object> chosen=new ForeignKeyLookupDialog(SwingUtilities.getWindowAncestor(this),origin,reference,values,column,writable).showSelection();
        if(chosen==null||!writable||!canWrite()||!confirm.test("Cambiar referencia",reference.childTable()+" → "+reference.parentTable()+"\n"+tr("Se actualizarán juntas las columnas de esta relación en el borrador. Pulsa Guardar para aplicar a la base. ¿Continuar?")))return;
        model.setReference(row,chosen);
    }
    private void editSelectedCell(){openOrEditCell(table.getSelectedRow(),table.getSelectedColumn(),null);}
    private void openOrEditCell(int row,int column,EventObject event){
        if(row<0||column<0)return;int modelRow=table.convertRowIndexToModel(row),modelColumn=table.convertColumnIndexToModel(column);
        ResultCellEditorDialog.Kind kind=ResultCellEditorDialog.kind(model.jdbcType(modelColumn),model.getValueAt(modelRow,modelColumn));
        if(kind==ResultCellEditorDialog.Kind.PLAIN){editCell(row,column,event);return;}
        if(!finishEditing())return;table.changeSelection(row,column,false,false);
        boolean editable=canWrite()&&!model.isDeleted(modelRow)&&model.isCellEditable(modelRow,modelColumn);
        ResultCellEditorDialog.Outcome outcome=ResultCellEditorDialog.show(this,model.getColumnName(modelColumn),model.getValueAt(modelRow,modelColumn),model.jdbcType(modelColumn),editable);
        if(outcome.accepted()&&editable)model.setValueAt(outcome.value(),modelRow,modelColumn);
    }
    private void editCell(int row,int column,EventObject event){
        if(row<0||column<0||!canWrite()||!target.columns().get(table.convertColumnIndexToModel(column)).writable()||model.isDeleted(table.convertRowIndexToModel(row))||!enableEditing())return;
        if(table.isEditing()&&table.getEditingRow()==row&&table.getEditingColumn()==column)return;
        if(!finishEditing())return;
        table.changeSelection(row,column,false,false);
        if(table.editCellAt(row,column,event)){Component editor=table.getEditorComponent();JTextField field=editor instanceof JTextField text?text:editor instanceof JPanel panel&&panel.getComponentCount()>0&&panel.getComponent(0) instanceof JTextField text?text:null;if(field!=null){field.requestFocusInWindow();field.selectAll();}}
    }
    private void setSelectionValue(Object value){if(!canEditSelectedCell()||!finishEditing()||!enableEditing())return;if(!confirm.test("Cambiar valores seleccionados",target.qualifiedTable()+"\n"+tr("Se asignará %s a las celdas seleccionadas. La fila se guardará al cambiar de fila o al pulsar Guardar. ¿Continuar?",value==null?"NULL":value==QueryEditSupport.DefaultValue.INSTANCE?"DEFAULT":value.toString().isEmpty()?tr("cadena vacía"):value.toString())))return;model.setValues(grid.selectedRows(),grid.selectedColumns(),value);}
    private void pasteSelectionValue(String value){
        if(!canEditSelectedCell()||!finishEditing())return;
        int row=table.getSelectedRow(),column=table.getSelectedColumn();if(row<0||column<0)return;
        model.setValueAt(value,table.convertRowIndexToModel(row),table.convertColumnIndexToModel(column));
        changed.accept(tr("Valor pegado en la celda. Pulsa Guardar o cambia de fila para aplicarlo."));
    }
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
            protected SaveOutcome doInBackground()throws Exception{var saved=QueryEditSupport.saveChanges(origin.connection(),target,changes);if(refresh)try{String qualified=QueryEditSupport.qualifiedSelect(sql,target);return new SaveOutcome(saved,pagingRequested?executeViewPage(pageSize,pageOffset).result():origin.executeEditable(qualified,rowLimit.getAsInt()),null);}catch(Exception error){return new SaveOutcome(saved,null,error);}return new SaveOutcome(saved,null,null);}
            protected void done(){
                try {
                    SaveOutcome outcome=get();QueryEditSupport.BatchSaved saved=outcome.saved();pendingCommit|=saved.pendingCommit();
                    if(outcome.fresh()!=null){resetTotal();resetPagingState();long renderStarted=System.nanoTime();String selected=target.qualifiedTable();grid.clearChecked();grid.refreshing(()->model.setResult(outcome.fresh()));configureTargets(outcome.fresh(),selected);estimatedRows=pageOffset+outcome.fresh().rows().size()+(outcome.fresh().truncated()?1:0);if(!outcome.fresh().truncated())totalRows=pageOffset+outcome.fresh().rows().size();resize.accept(table);setRenderMillis((System.nanoTime()-renderStarted)/1_000_000L);pageCache.put(new PageKey(pageOffset,pageSize),outcome.fresh());scheduleCount();loadReferences();}
                    else if(outcome.refreshError()!=null){grid.clearChecked();model.discard();stale=true;edit.setSelected(false);UiLanguage.text(status,UiLanguage.key("Los cambios se guardaron, pero no se pudo recargar el resultado. Pulsa Recargar."));showError(outcome.refreshError(),"No se pudo recargar el resultado");return;}
                    else model.accept(saved.rows());
                    UiLanguage.text(status,UiLanguage.key(saved.pendingCommit()?"Cambio aplicado · pendiente de Commit":"Cambio guardado"));
                    changed.accept(tr(saved.pendingCommit()?"Cambio aplicado · pendiente de Commit":"Cambio guardado"));
                }catch(Exception error){UiLanguage.text(status,UiLanguage.key("No se guardaron los cambios. Puedes corregirlos o descartarlos."));showError(error,"No se pudo actualizar la fila");}
                finally{end();}
            }
        }.execute();
    }
    private void reload(){reloadForReport((fresh,error)->{if(error!=null&&!isCancellation(error))showError(error,"Error al ejecutar la consulta");});}
    void reloadForReport(java.util.function.BiConsumer<QueryResult,Throwable> completion){
        if(!available||saving||!SqlExecutionPolicy.readOnly(sql)){completion.accept(null,new IllegalStateException(tr("El resultado no está disponible para actualizarse.")));return;}
        if(!pageCatalog.equals(namespace(origin,true))||!pageSchema.equals(namespace(origin,false))){completion.accept(null,new IllegalStateException(tr("La base o el esquema activo cambió. Vuelve al origen del resultado antes de recargar.")));return;}
        if(pageable){resetTotal();pageCache.clear();loadPage(pageOffset,pageSize,completion,false);return;}
        if(!confirmDiscard()){completion.accept(null,new java.util.concurrent.CancellationException(tr("Actualización cancelada.")));return;}
        String replay=target==null?sql:QueryEditSupport.qualifiedSelect(sql,target);start();UiLanguage.text(status,UiLanguage.key("Recargando solo esta consulta…"));
        new SwingWorker<QueryResult,Void>() {
            protected QueryResult doInBackground()throws Exception{return origin.executeEditable(replay,rowLimit.getAsInt());}
            protected void done(){
                try{QueryResult fresh=get();long renderStarted=System.nanoTime();String selected=target==null?null:target.qualifiedTable();grid.clearChecked();grid.refreshing(()->model.setResult(fresh));configureTargets(fresh,selected);stale=false;edit.setSelected(false);resize.accept(table);setHint(fresh);setRenderMillis((System.nanoTime()-renderStarted)/1_000_000L);loadReferences();completion.accept(fresh,null);}
                catch(Exception error){completion.accept(null,root(error));}finally{end();}
            }
        }.execute();
    }
    private static boolean isCancellation(Throwable error){while(error!=null){if(error instanceof java.util.concurrent.CancellationException)return true;error=error.getCause();}return false;}
    private static Throwable root(Throwable error){while(error.getCause()!=null)error=error.getCause();return error;}
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
            if(model.isDeleted(index))font=font.deriveFont(java.util.Map.of(TextAttribute.STRIKETHROUGH,TextAttribute.STRIKETHROUGH_ON));setFont(font);
            int modelColumn=grid.convertColumnIndexToModel(column);boolean automatic=value==QueryEditSupport.DefaultValue.INSTANCE&&model.isNewRow(index)&&target!=null&&modelColumn<target.columns().size()&&target.columns().get(modelColumn).autoIncrement();
            setText(value==null?"(NULL)":automatic?"(AUTO)":String.valueOf(value));
            boolean dirty=model.isChanged(index,grid.convertColumnIndexToModel(column)),dark=com.formdev.flatlaf.FlatLaf.isLafDark();
            if(!selected&&!marked){Color background=UIManager.getColor("Table.background");setBackground(model.isDeleted(index)?(dark?new Color(85,34,34):new Color(255,221,221)):model.isNewRow(index)?(dark?new Color(25,69,49):new Color(222,247,230)):dirty?(dark?new Color(83,67,22):new Color(255,243,190)):background);setForeground(UIManager.getColor("Table.foreground"));}
            arrow=row==grid.getSelectedRow()&&column==grid.getSelectedColumn();reference=arrow&&hasReference(grid.convertColumnIndexToModel(column));if(arrow)setBorder(BorderFactory.createCompoundBorder(getBorder(),BorderFactory.createEmptyBorder(0,0,0,reference?44:18)));
            boolean specialized=ResultCellEditorDialog.kind(model.jdbcType(grid.convertColumnIndexToModel(column)),value)!=ResultCellEditorDialog.Kind.PLAIN;
            setToolTipText(model.isDeleted(index)?tr("Fila pendiente de eliminar"):automatic?tr("MySQL generará este identificador automáticamente al guardar."):dirty?tr("Cambio sin guardar"):arrow?tr(specialized?"Doble clic para abrir el editor completo.":"Doble clic para editar; clic en ▾ para abrir el menú."):null);return this;
        }
        protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(arrow){graphics.setColor(getForeground());int x=getWidth()-12,y=getHeight()/2;graphics.drawLine(x-3,y-1,x,y+2);graphics.drawLine(x,y+2,x+3,y-1);if(reference)graphics.drawString("…",getWidth()-36,y+4);}}
    }
}
