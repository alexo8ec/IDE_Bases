package com.nexodb.ui;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import static com.nexodb.util.I18n.tr;

/** View-only grid operations. Row selections always retain model indexes across sorting/filtering. */
final class ResultGridActions {
    interface ColumnEditor { void open(int column,boolean delete); }
    record EditingActions(Runnable edit,Runnable insert,Runnable delete,Runnable save,Runnable discard,Consumer<Object> setValue,
                          Runnable duplicate,Runnable export,BooleanSupplier canEdit,BooleanSupplier hasChanges,
                          BooleanSupplier canSetValue,BooleanSupplier canDefault,BooleanSupplier canExport) {}
    private record Filter(String text,Object value,boolean exact) {}
    private final JTable table;
    private final ResultTableModel model;
    private final JTextField search;
    private final Runnable changed;
    private final Set<Integer> checked=new TreeSet<>();
    private final Map<Integer,Filter> filters=new HashMap<>();
    private final RowHeader rows=new RowHeader();
    private final JCheckBox all=new JCheckBox();
    private final Consumer<String> copied;
    private ColumnEditor columnEditor;
    private EditingActions editing;

    ResultGridActions(JTable table,ResultTableModel model,JScrollPane scroll,JTextField search,Runnable changed,Consumer<String> copied){
        this.table=table;this.model=model;this.search=search;this.changed=changed;this.copied=copied;
        installRowHeader(scroll);installHeaders();installPopup();
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){applyFilter();}public void removeUpdate(DocumentEvent e){applyFilter();}public void changedUpdate(DocumentEvent e){applyFilter();}});
        model.addTableModelListener(event->{if(event.getFirstRow()==TableModelEvent.HEADER_ROW){checked.clear();filters.clear();SwingUtilities.invokeLater(this::applyFilter);}rows.fireTableDataChanged();changed.run();});
        table.addPropertyChangeListener("rowSorter",event->{listenSorter();applyFilter();});listenSorter();
        for(String key:List.of("control C","meta C"))table.getInputMap().put(KeyStroke.getKeyStroke(key),"copy-grid");
        table.getActionMap().put("copy-grid",new AbstractAction(){public void actionPerformed(ActionEvent event){copySelection();}});
    }
    int checkedCount(){return checked.size();}
    boolean isMarked(int row){return checked.contains(row);}
    void clearChecked(){checked.clear();all.setSelected(false);rows.fireTableDataChanged();table.repaint();changed.run();}
    void editingActions(EditingActions actions){editing=actions;}
    void columnEditor(ColumnEditor editor){columnEditor=editor;}
    List<Integer> selectedRows(){
        if(!checked.isEmpty())return List.copyOf(checked);
        return Arrays.stream(table.getSelectedRows()).map(table::convertRowIndexToModel).boxed().toList();
    }
    List<Integer> selectedColumns(){return Arrays.stream(table.getSelectedColumns()).map(table::convertColumnIndexToModel).boxed().toList();}
    private List<Integer> visibleRows(){List<Integer> values=new ArrayList<>();for(int i=0;i<table.getRowCount();i++)values.add(table.convertRowIndexToModel(i));return values;}
    private void listenSorter(){if(table.getRowSorter()!=null)table.getRowSorter().addRowSorterListener(event->{rows.fireTableDataChanged();changed.run();if(!checked.isEmpty())SwingUtilities.invokeLater(()->{if(!checked.isEmpty())selectMarkedRows();});});}
    private void selectMarkedRows(){
        var selection=table.getSelectionModel();selection.setValueIsAdjusting(true);
        try{selection.clearSelection();for(int row:checked)if(row<model.getRowCount()){int view=table.convertRowIndexToView(row);if(view>=0)selection.addSelectionInterval(view,view);}if(!checked.isEmpty()&&table.getColumnCount()>0)table.setColumnSelectionInterval(0,table.getColumnCount()-1);}
        finally{selection.setValueIsAdjusting(false);}
        boolean every=table.getRowCount()>0;for(int row:visibleRows())every&=checked.contains(row);all.setSelected(every);table.repaint();
    }
    private void applyFilter(){
        if(!(table.getRowSorter() instanceof TableRowSorter<?> sorter))return;
        String global=search.getText().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(new RowFilter<TableModel,Integer>(){
            public boolean include(Entry<? extends TableModel,? extends Integer> entry){
                if(model.isNewRow(entry.getIdentifier()))return true;
                if(!global.isBlank()){boolean found=false;for(int i=0;i<entry.getValueCount();i++)if(display(entry.getValue(i)).toLowerCase(Locale.ROOT).contains(global)){found=true;break;}if(!found)return false;}
                for(var item:filters.entrySet()){
                    Object value=entry.getValue(item.getKey());Filter filter=item.getValue();
                    if(filter.exact()?!Objects.equals(value,filter.value()):!display(value).toLowerCase(Locale.ROOT).contains(filter.text().toLowerCase(Locale.ROOT)))return false;
                }
                return true;
            }
        });
        table.getTableHeader().repaint();rows.fireTableDataChanged();changed.run();
    }
    private void installRowHeader(JScrollPane scroll){
        JTable header=new JTable(rows);header.setName("query-row-selector");header.setRowHeight(table.getRowHeight());header.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        header.getColumnModel().getColumn(0).setPreferredWidth(25);header.getColumnModel().getColumn(1).setPreferredWidth(39);
        header.setPreferredScrollableViewportSize(new Dimension(64,0));header.setSelectionModel(table.getSelectionModel());header.setFocusable(false);
        header.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int col){super.getTableCellRendererComponent(t,value,selected,focus,row,col);setHorizontalAlignment(RIGHT);if(!selected){setBackground(UIManager.getColor("TableHeader.background"));setForeground(UIManager.getColor("TableHeader.foreground"));}return this;}});
        header.addMouseListener(new MouseAdapter(){public void mousePressed(MouseEvent event){int row=header.rowAtPoint(event.getPoint());if(row>=0&&header.columnAtPoint(event.getPoint())==1){table.setRowSelectionInterval(row,row);if(table.getColumnCount()>0)table.setColumnSelectionInterval(0,table.getColumnCount()-1);}}});
        all.setHorizontalAlignment(SwingConstants.CENTER);UiLanguage.tooltip(all,UiLanguage.key("Seleccionar todas las filas visibles"));
        all.addActionListener(event->{boolean selected=all.isSelected();for(int row:visibleRows())if(selected)checked.add(row);else checked.remove(row);selectMarkedRows();rows.fireTableDataChanged();changed.run();});
        scroll.setRowHeaderView(header);scroll.setCorner(JScrollPane.UPPER_LEFT_CORNER,all);
    }
    private final class RowHeader extends AbstractTableModel {
        public int getRowCount(){return table==null?0:table.getRowCount();}public int getColumnCount(){return 2;}
        public Class<?> getColumnClass(int column){return column==0?Boolean.class:String.class;}
        public Object getValueAt(int row,int column){int index=table.convertRowIndexToModel(row);return column==0?checked.contains(index):(model.isNewRow(index)?"*":"")+Integer.toString(index+1);}
        public boolean isCellEditable(int row,int column){return column==0;}
        public void setValueAt(Object value,int row,int column){int index=table.convertRowIndexToModel(row);if(Boolean.TRUE.equals(value))checked.add(index);else checked.remove(index);selectMarkedRows();fireTableCellUpdated(row,column);changed.run();}
    }
    private void installHeaders(){
        JTableHeader header=new JTableHeader(table.getColumnModel()){
            protected void processMouseEvent(MouseEvent event){
                if(event.getID()==MouseEvent.MOUSE_CLICKED&&SwingUtilities.isLeftMouseButton(event)&&event.getClickCount()==1){selectColumn(columnAtPoint(event.getPoint()));return;}
                super.processMouseEvent(event);
            }
        };
        header.setPreferredSize(new Dimension(0,30));table.setTableHeader(header);
        TableCellRenderer normal=header.getDefaultRenderer();header.setDefaultRenderer((grid,value,selected,focus,row,column)->{
            int index=table.convertColumnIndexToModel(column);return normal.getTableCellRendererComponent(grid,(filters.containsKey(index)?"⏷ ":"")+value,selected,focus,row,column);
        });
        UiLanguage.tooltip(header,UiLanguage.key("Clic para seleccionar la columna. Clic derecho para copiar, ordenar o filtrar."));
        header.addMouseListener(new MouseAdapter(){private void popup(MouseEvent event){if(event.isPopupTrigger()){selectColumn(header.columnAtPoint(event.getPoint()));menu(true).show(header,event.getX(),event.getY());}}public void mousePressed(MouseEvent e){popup(e);}public void mouseReleased(MouseEvent e){popup(e);}});
    }
    private void selectColumn(int column){if(column<0)return;table.setColumnSelectionInterval(column,column);if(table.getRowCount()>0)table.setRowSelectionInterval(0,table.getRowCount()-1);}
    private void installPopup(){
        table.addMouseListener(new MouseAdapter(){
            private void popup(MouseEvent event){if(event.isPopupTrigger())showAt(event);}
            private void showAt(MouseEvent event){int row=table.rowAtPoint(event.getPoint()),column=table.columnAtPoint(event.getPoint());if(row<0||column<0)return;table.changeSelection(row,column,false,false);menu(false).show(table,event.getX(),event.getY());}
            public void mousePressed(MouseEvent e){popup(e);}public void mouseReleased(MouseEvent e){popup(e);}
            public void mouseClicked(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==1&&!table.isEditing()){int row=table.rowAtPoint(e.getPoint()),col=table.columnAtPoint(e.getPoint());if(row>=0&&col>=0){Rectangle cell=table.getCellRect(row,col,false);if(e.getX()>=cell.x+cell.width-18)showAt(e);}}}
        });
        table.getInputMap().put(KeyStroke.getKeyStroke("shift F10"),"result-menu");table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU,0),"result-menu");
        table.getActionMap().put("result-menu",new AbstractAction(){public void actionPerformed(ActionEvent event){showMenu(table);}});
    }
    private void item(JPopupMenu menu,String label,Runnable action){JMenuItem item=UiLanguage.text(new JMenuItem(),UiLanguage.key(label));item.addActionListener(event->action.run());menu.add(item);}
    private void action(JPopupMenu menu,String label,Runnable action,boolean enabled){item(menu,label,action);menu.getComponent(menu.getComponentCount()-1).setEnabled(enabled);}
    private void submenu(JPopupMenu menu,JMenu submenu,String label,Runnable action){JMenuItem item=UiLanguage.text(new JMenuItem(),UiLanguage.key(label));item.addActionListener(event->action.run());submenu.add(item);}
    void showMenu(Component anchor){JPopupMenu menu=menu(false);if(anchor==table){int row=table.getSelectedRow(),col=table.getSelectedColumn();Rectangle rect=row>=0&&col>=0?table.getCellRect(row,col,true):new Rectangle();menu.show(table,rect.x,rect.y+rect.height);}else menu.show(anchor,0,anchor.getHeight());}
    private int column(){int column=table.getSelectedColumn();return column<0?-1:table.convertColumnIndexToModel(column);}
    private JPopupMenu menu(boolean heading){
        JPopupMenu menu=new JPopupMenu();int column=column();
        if(!heading&&editing!=null){
            boolean writable=editing.canEdit().getAsBoolean(),cell=editing.canSetValue().getAsBoolean();
            action(menu,"Editar celda",editing.edit(),cell);action(menu,"Insertar nueva fila",editing.insert(),writable);
            action(menu,"Guardar cambios",editing.save(),writable&&editing.hasChanges().getAsBoolean());
            action(menu,"Eliminar filas seleccionadas",editing.delete(),writable&&!selectedRows().isEmpty());
            action(menu,"Cancelar cambios",editing.discard(),writable&&editing.hasChanges().getAsBoolean());
            menu.addSeparator();action(menu,"Establecer cadena vacía",()->editing.setValue().accept(""),cell);
            action(menu,"Establecer NULL",()->editing.setValue().accept(null),cell);
            action(menu,"Establecer valor predeterminado",()->editing.setValue().accept(com.nexodb.db.QueryEditSupport.DefaultValue.INSTANCE),cell&&editing.canDefault().getAsBoolean());
            menu.addSeparator();action(menu,"Quitar ordenación",()->table.getRowSorter().setSortKeys(List.of()),!table.getRowSorter().getSortKeys().isEmpty());
            JMenu filters=UiLanguage.text(new JMenu(),UiLanguage.key("Filtro"));
            submenu(menu,filters,"Filtrar esta columna…",()->filterColumn(column));
            submenu(menu,filters,"Quitar filtro de esta columna",()->{this.filters.remove(column);applyFilter();});
            submenu(menu,filters,"Limpiar todos los filtros",()->{this.filters.clear();search.setText("");applyFilter();});
            menu.add(filters);menu.addSeparator();action(menu,"Exportar resultado…",editing.export(),editing.canExport().getAsBoolean());
            JMenu copy=UiLanguage.text(new JMenu(),UiLanguage.key("Copiar"));
            submenu(menu,copy,"Copiar selección",this::copySelection);submenu(menu,copy,"Copiar filas completas",()->copy(selectedRows(),allColumns()));submenu(menu,copy,"Copiar columna completa",()->{if(column>=0)copy(visibleRows(),List.of(column));});menu.add(copy);
            action(menu,"Duplicar fila actual",editing.duplicate(),writable&&table.getSelectedRow()>=0);
            menu.addSeparator();
        }
        if(heading||editing==null){
        if(!heading){item(menu,"Copiar selección",this::copySelection);item(menu,"Copiar filas completas",()->copy(selectedRows(),allColumns()));}
        item(menu,"Copiar columna completa",()->{if(column>=0)copy(visibleRows(),List.of(column));});
        menu.addSeparator();item(menu,"Filtrar esta columna…",()->filterColumn(column));
        if(!heading)item(menu,"Filtrar por este valor",()->{int row=table.getSelectedRow();if(column>=0&&row>=0){filters.put(column,new Filter("",model.getValueAt(table.convertRowIndexToModel(row),column),true));applyFilter();}});
        item(menu,"Quitar filtro de esta columna",()->{filters.remove(column);applyFilter();});
        item(menu,"Limpiar todos los filtros",()->{filters.clear();search.setText("");applyFilter();});
        menu.addSeparator();item(menu,"Ordenar ascendente",()->sort(column,SortOrder.ASCENDING));item(menu,"Ordenar descendente",()->sort(column,SortOrder.DESCENDING));
        }else item(menu,"Filtrar por este valor",()->{int row=table.getSelectedRow();if(column>=0&&row>=0){filters.put(column,new Filter("",model.getValueAt(table.convertRowIndexToModel(row),column),true));applyFilter();}});
        if(columnEditor!=null){menu.addSeparator();item(menu,"Agregar columna a la tabla…",()->columnEditor.open(column,false));item(menu,"Eliminar columna de la tabla…",()->columnEditor.open(column,true));}
        return menu;
    }
    private void filterColumn(int column){
        if(column<0)return;Filter previous=filters.get(column);
        String text=JOptionPane.showInputDialog(table,tr("Texto para filtrar la columna %s (vacío para quitar el filtro):",model.getColumnName(column)),previous==null?"":previous.text());
        if(text==null)return;if(text.isEmpty())filters.remove(column);else filters.put(column,new Filter(text,null,false));applyFilter();
    }
    private void sort(int column,SortOrder order){if(column>=0)table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(column,order)));}
    private List<Integer> allColumns(){List<Integer> result=new ArrayList<>();for(int i=0;i<model.getColumnCount();i++)result.add(i);return result;}
    private void copySelection(){copy(selectedRows(),selectedColumns());}
    private void copy(List<Integer> rows,List<Integer> columns){if(rows.isEmpty()||columns.isEmpty())return;Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text(model,rows,columns)),null);copied.accept(tr("Datos copiados al portapapeles"));}
    static String text(ResultTableModel model,List<Integer> rows,List<Integer> columns){
        StringBuilder text=new StringBuilder();for(int row:rows){if(text.length()>0)text.append('\n');for(int i=0;i<columns.size();i++){if(i>0)text.append('\t');String value=display(model.getValueAt(row,columns.get(i)));if(value.indexOf('\t')>=0||value.indexOf('\n')>=0||value.indexOf('\r')>=0||value.indexOf('"')>=0)text.append('"').append(value.replace("\"","\"\"")).append('"');else text.append(value);}}return text.toString();
    }
    private static String display(Object value){return value==null?"(NULL)":value.toString();}
}
