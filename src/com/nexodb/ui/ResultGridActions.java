package com.nexodb.ui;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.StringSelection;
import java.math.BigDecimal;
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
    enum Operator {
        NONE("Sin segunda condición",false), EXACT("Igual al valor seleccionado",true), EQUALS("Igual a",true), NOT_EQUALS("No igual a",true),
        CONTAINS("Contiene",true), NOT_CONTAINS("No contiene",true), STARTS_WITH("Empieza con",true), ENDS_WITH("Termina con",true),
        GREATER("Mayor que",true), GREATER_OR_EQUAL("Mayor o igual que",true), LESS("Menor que",true), LESS_OR_EQUAL("Menor o igual que",true),
        IS_NULL("Es NULL",false), IS_NOT_NULL("No es NULL",false), IS_EMPTY("Está vacío",false), IS_NOT_EMPTY("No está vacío",false);
        final String label;final boolean needsValue;Operator(String label,boolean needsValue){this.label=label;this.needsValue=needsValue;}@Override public String toString(){return tr(label);}
    }
    enum Junction { AND, OR }
    record Condition(Operator operator,Object value) {}
    record Filter(Condition first,Junction junction,Condition second,boolean caseSensitive) {}
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
    private Runnable reference;private BooleanSupplier hasReference=()->false;
    private Consumer<com.nexodb.util.DataExporter.Format> exporter;
    void exportAction(Consumer<com.nexodb.util.DataExporter.Format> action){exporter=action;}
    void referenceAction(Runnable action,BooleanSupplier available){reference=action;hasReference=available;}

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
                for(var item:filters.entrySet())if(!matches(entry.getValue(item.getKey()),item.getValue()))return false;
                return true;
            }
        });
        table.getTableHeader().repaint();rows.fireTableDataChanged();changed.run();
    }
    private void installRowHeader(JScrollPane scroll){
        JTable header=new JTable(rows);header.setName("query-row-selector");header.setRowHeight(table.getRowHeight());header.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        header.getColumnModel().getColumn(0).setPreferredWidth(25);header.getColumnModel().getColumn(1).setPreferredWidth(39);
        header.setPreferredScrollableViewportSize(new Dimension(64,0));header.setSelectionModel(table.getSelectionModel());header.setFocusable(false);
        TableCellRenderer checkbox=header.getDefaultRenderer(Boolean.class);
        header.setDefaultRenderer(Boolean.class,(t,value,selected,focus,row,col)->checkbox.getTableCellRendererComponent(t,value,selected||checked.contains(table.convertRowIndexToModel(row)),focus,row,col));
        header.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int col){boolean marked=selected||checked.contains(table.convertRowIndexToModel(row));super.getTableCellRendererComponent(t,value,marked,focus,row,col);setHorizontalAlignment(RIGHT);if(!marked){setBackground(UIManager.getColor("TableHeader.background"));setForeground(UIManager.getColor("TableHeader.foreground"));}return this;}});
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
                if(event.getID()==MouseEvent.MOUSE_CLICKED&&SwingUtilities.isLeftMouseButton(event)&&event.getClickCount()==1){
                    int column=columnAtPoint(event.getPoint());if(column<0)return;Rectangle cell=getHeaderRect(column);selectColumn(column);
                    if(event.getX()>=cell.x+cell.width-28){columnFilterMenu(table.convertColumnIndexToModel(column)).show(this,cell.x,cell.y+cell.height);}
                    return;
                }
                super.processMouseEvent(event);
            }
        };
        header.setPreferredSize(new Dimension(0,30));table.setTableHeader(header);
        TableCellRenderer normal=header.getDefaultRenderer();header.setDefaultRenderer((grid,value,selected,focus,row,column)->{
            int index=table.convertColumnIndexToModel(column);Component component=normal.getTableCellRendererComponent(grid,value,selected,focus,row,column);
            if(component instanceof JLabel label){
                Icon filter=IconFactory.of(IconFactory.Kind.FILTER,14,filters.containsKey(index)?IconFactory.CYAN:IconFactory.SLATE);
                label.setIcon(new HeaderFilterIcon(label.getIcon(),filter));label.setHorizontalTextPosition(SwingConstants.LEFT);label.setIconTextGap(7);
            }
            return component;
        });
        UiLanguage.tooltip(header,UiLanguage.key("Clic para seleccionar la columna. Pulsa ▾ para filtrar; clic derecho muestra más acciones."));
        header.addMouseListener(new MouseAdapter(){private void popup(MouseEvent event){if(event.isPopupTrigger()){selectColumn(header.columnAtPoint(event.getPoint()));menu(true).show(header,event.getX(),event.getY());}}public void mousePressed(MouseEvent e){popup(e);}public void mouseReleased(MouseEvent e){popup(e);}});
    }
    /** Keeps the look-and-feel sort indicator and adds a permanent filter button. */
    private record HeaderFilterIcon(Icon sort,Icon filter) implements Icon{
        public int getIconWidth(){return (sort==null?0:sort.getIconWidth()+4)+filter.getIconWidth();}
        public int getIconHeight(){return Math.max(sort==null?0:sort.getIconHeight(),filter.getIconHeight());}
        public void paintIcon(Component component,Graphics graphics,int x,int y){
            int offset=0;if(sort!=null){sort.paintIcon(component,graphics,x,y+(getIconHeight()-sort.getIconHeight())/2);offset=sort.getIconWidth()+4;}
            filter.paintIcon(component,graphics,x+offset,y+(getIconHeight()-filter.getIconHeight())/2);
        }
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
        if(!heading){
            action(menu,"Copiar celda al portapapeles",()->{int row=table.getSelectedRow();if(row>=0&&column>=0)copy(List.of(table.convertRowIndexToModel(row)),List.of(column));},table.getSelectedRow()>=0&&column>=0);
            action(menu,"Copiar todas las filas al portapapeles",()->copy(visibleRows(),allColumns()),table.getRowCount()>0);
            action(menu,"Copiar filas seleccionadas al portapapeles",()->copy(selectedRows(),allColumns()),!selectedRows().isEmpty());
            if(exporter!=null){JMenu formats=UiLanguage.text(new JMenu(),UiLanguage.key("Exportar datos cargados"));for(var format:com.nexodb.util.DataExporter.Format.values())submenu(menu,formats,format.toString()+"…",()->exporter.accept(format));menu.add(formats);}
            menu.addSeparator();
        }
        if(!heading&&reference!=null&&hasReference.getAsBoolean()){action(menu,"Ver registro relacionado…",reference,true);menu.addSeparator();}
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
        if(!heading)item(menu,"Filtrar por este valor",()->{int row=table.getSelectedRow();if(column>=0&&row>=0){filters.put(column,exactFilter(model.getValueAt(table.convertRowIndexToModel(row),column)));applyFilter();}});
        item(menu,"Quitar filtro de esta columna",()->{filters.remove(column);applyFilter();});
        item(menu,"Limpiar todos los filtros",()->{filters.clear();search.setText("");applyFilter();});
        menu.addSeparator();item(menu,"Ordenar ascendente",()->sort(column,SortOrder.ASCENDING));item(menu,"Ordenar descendente",()->sort(column,SortOrder.DESCENDING));
        }else item(menu,"Filtrar por este valor",()->{int row=table.getSelectedRow();if(column>=0&&row>=0){filters.put(column,exactFilter(model.getValueAt(table.convertRowIndexToModel(row),column)));applyFilter();}});
        if(columnEditor!=null){menu.addSeparator();item(menu,"Agregar columna a la tabla…",()->columnEditor.open(column,false));item(menu,"Eliminar columna de la tabla…",()->columnEditor.open(column,true));}
        return menu;
    }
    private void filterColumn(int column){filterColumn(column,null);}
    private void filterColumn(int column,String suggested){
        if(column<0)return;Filter previous=filters.get(column);
        JComboBox<Operator> firstOperator=operators(previous==null?Operator.CONTAINS:previous.first().operator());JTextField firstValue=new JTextField(previous==null&&suggested!=null?suggested:conditionText(previous==null?null:previous.first()),24);
        JComboBox<Junction> junction=new JComboBox<>(Junction.values());if(previous!=null)junction.setSelectedItem(previous.junction());
        JComboBox<Operator> secondOperator=operators(previous==null||previous.second()==null?Operator.NONE:previous.second().operator());JTextField secondValue=new JTextField(conditionText(previous==null?null:previous.second()),24);
        JCheckBox caseSensitive=UiLanguage.text(new JCheckBox(),UiLanguage.key("Distinguir mayúsculas y minúsculas"));caseSensitive.setSelected(previous!=null&&previous.caseSensitive());
        Runnable enabled=()->{firstValue.setEnabled(((Operator)firstOperator.getSelectedItem()).needsValue);secondValue.setEnabled(((Operator)secondOperator.getSelectedItem()).needsValue);};
        firstOperator.addActionListener(e->enabled.run());secondOperator.addActionListener(e->enabled.run());enabled.run();
        JPanel form=new JPanel(new GridBagLayout());GridBagConstraints gc=new GridBagConstraints();gc.insets=new Insets(4,4,4,4);gc.fill=GridBagConstraints.HORIZONTAL;gc.weightx=1;
        gc.gridx=0;gc.gridy=0;gc.gridwidth=2;form.add(new JLabel(tr("Columna: ")+model.getColumnName(column)),gc);
        gc.gridy++;gc.gridwidth=1;form.add(firstOperator,gc);gc.gridx=1;form.add(firstValue,gc);
        gc.gridx=0;gc.gridy++;form.add(junction,gc);gc.gridx=1;form.add(new JLabel(tr("Combina las dos condiciones; los filtros de distintas columnas siempre se combinan con AND.")),gc);
        gc.gridx=0;gc.gridy++;form.add(secondOperator,gc);gc.gridx=1;form.add(secondValue,gc);
        gc.gridx=0;gc.gridy++;gc.gridwidth=2;form.add(caseSensitive,gc);
        Component dialogOwner=SwingUtilities.getWindowAncestor(table);if(dialogOwner==null)dialogOwner=table;
        if(JOptionPane.showConfirmDialog(dialogOwner,form,tr("Filtro personalizado · ")+model.getColumnName(column),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
        Condition first=condition((Operator)firstOperator.getSelectedItem(),firstValue.getText()),second=condition((Operator)secondOperator.getSelectedItem(),secondValue.getText());
        if(first==null){filters.remove(column);applyFilter();return;}filters.put(column,new Filter(first,(Junction)junction.getSelectedItem(),second,caseSensitive.isSelected()));applyFilter();
    }
    private JPopupMenu columnFilterMenu(int column){
        JPopupMenu menu=new JPopupMenu();
        JTextField valueSearch=new JTextField(filterSeed(column),24);valueSearch.setName("column-filter-search");UiLanguage.placeholder(valueSearch,"Buscar valores…");
        JMenuItem custom=UiLanguage.text(new JMenuItem(tr("Filtro personalizado…"),IconFactory.of(IconFactory.Kind.FILTER,15)),UiLanguage.key("Filtro personalizado…"));
        custom.addActionListener(event->filterColumn(column,valueSearch.getText().trim()));menu.add(custom);
        action(menu,"Quitar filtro de esta columna",()->{filters.remove(column);applyFilter();},filters.containsKey(column));
        menu.addSeparator();item(menu,"(NULL)",()->{filters.put(column,exactFilter(null));applyFilter();});
        item(menu,"(No NULL)",()->{filters.put(column,new Filter(new Condition(Operator.IS_NOT_NULL,null),Junction.AND,null,false));applyFilter();});
        menu.addSeparator();
        LinkedHashMap<String,Object> values=new LinkedHashMap<>();
        for(int row=0;row<model.getRowCount();row++){Object value=model.getValueAt(row,column);if(value!=null)values.putIfAbsent(display(value),value);}
        List<ColumnValue> available=values.entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)).map(entry->new ColumnValue(columnValueLabel(entry.getKey()),entry.getValue())).toList();
        DefaultListModel<ColumnValue> visible=new DefaultListModel<>();JList<ColumnValue> list=new JList<>(visible);list.setName("column-filter-values");list.setVisibleRowCount(Math.min(10,Math.max(4,available.size())));list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Runnable rebuild=()->{String typed=valueSearch.getText().trim().toLowerCase(Locale.ROOT);visible.clear();available.stream().filter(value->typed.isEmpty()||value.label().toLowerCase(Locale.ROOT).contains(typed)).limit(100).forEach(visible::addElement);if(!visible.isEmpty())list.setSelectedIndex(0);};
        valueSearch.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){rebuild.run();}public void removeUpdate(DocumentEvent e){rebuild.run();}public void changedUpdate(DocumentEvent e){rebuild.run();}});rebuild.run();
        Runnable choose=()->{ColumnValue selected=list.getSelectedValue();if(selected==null){filterColumn(column,valueSearch.getText().trim());return;}filters.put(column,exactFilter(selected.value()));applyFilter();menu.setVisible(false);};
        list.addMouseListener(new MouseAdapter(){public void mouseReleased(MouseEvent event){if(SwingUtilities.isLeftMouseButton(event)){int index=list.locationToIndex(event.getPoint());if(index>=0&&list.getCellBounds(index,index).contains(event.getPoint())){list.setSelectedIndex(index);choose.run();}}}});
        valueSearch.addActionListener(event->choose.run());valueSearch.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN,0),"values");valueSearch.getActionMap().put("values",new AbstractAction(){public void actionPerformed(ActionEvent event){list.requestFocusInWindow();if(!visible.isEmpty())list.setSelectedIndex(0);}});
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"choose");list.getActionMap().put("choose",new AbstractAction(){public void actionPerformed(ActionEvent event){choose.run();}});
        JPanel searchBox=new JPanel(new BorderLayout(5,0));searchBox.setBorder(BorderFactory.createEmptyBorder(4,6,4,6));searchBox.add(new JLabel(IconFactory.of(IconFactory.Kind.SEARCH,14)),BorderLayout.WEST);searchBox.add(valueSearch);menu.add(searchBox);
        JScrollPane scroll=new JScrollPane(list);scroll.setBorder(BorderFactory.createEmptyBorder());scroll.setPreferredSize(new Dimension(360,Math.min(240,Math.max(96,available.size()*24+4))));menu.add(scroll);
        if(values.size()>100){JMenuItem more=new JMenuItem(tr("Escribe para buscar entre todos los valores cargados."));more.setEnabled(false);menu.add(more);}
        menu.addPopupMenuListener(new PopupMenuListener(){public void popupMenuWillBecomeVisible(PopupMenuEvent event){SwingUtilities.invokeLater(()->{valueSearch.requestFocusInWindow();valueSearch.selectAll();});}public void popupMenuWillBecomeInvisible(PopupMenuEvent event){}public void popupMenuCanceled(PopupMenuEvent event){}});
        return menu;
    }
    private String filterSeed(int column){Filter current=filters.get(column);if(current!=null&&current.first()!=null&&current.first().operator().needsValue)return conditionText(current.first());return search.getText().trim();}
    private static String columnValueLabel(String text){String label=text.replace('\r',' ').replace('\n',' ');return label.length()>90?label.substring(0,87)+"…":label;}
    private record ColumnValue(String label,Object value){@Override public String toString(){return label;}}
    private static Filter exactFilter(Object value){return new Filter(new Condition(value==null?Operator.IS_NULL:Operator.EXACT,value),Junction.AND,null,true);}
    private static JComboBox<Operator> operators(Operator selected){
        JComboBox<Operator> combo=new JComboBox<>(Arrays.stream(Operator.values()).filter(value->value!=Operator.EXACT).toArray(Operator[]::new));
        combo.setSelectedItem(selected==Operator.EXACT?Operator.EQUALS:selected);return combo;
    }
    private static String conditionText(Condition condition){return condition==null||condition.value()==null?"":String.valueOf(condition.value());}
    private static Condition condition(Operator operator,String value){return operator==null||operator==Operator.NONE?null:new Condition(operator,operator.needsValue?value:null);}

    static boolean matches(Object actual,Filter filter){
        boolean first=matches(actual,filter.first(),filter.caseSensitive());if(filter.second()==null)return first;
        boolean second=matches(actual,filter.second(),filter.caseSensitive());return filter.junction()==Junction.AND?first&&second:first||second;
    }
    private static boolean matches(Object actual,Condition condition,boolean caseSensitive){
        Operator operator=condition.operator();Object expected=condition.value();
        if(operator==Operator.NONE)return true;if(operator==Operator.IS_NULL)return actual==null;if(operator==Operator.IS_NOT_NULL)return actual!=null;
        if(operator==Operator.EXACT)return Objects.equals(actual,expected);if(actual==null)return false;
        String left=display(actual),right=expected==null?"":String.valueOf(expected);String a=caseSensitive?left:left.toLowerCase(Locale.ROOT),b=caseSensitive?right:right.toLowerCase(Locale.ROOT);
        return switch(operator){
            case EQUALS->a.equals(b);case NOT_EQUALS->!a.equals(b);case CONTAINS->a.contains(b);case NOT_CONTAINS->!a.contains(b);case STARTS_WITH->a.startsWith(b);case ENDS_WITH->a.endsWith(b);
            case GREATER->compare(actual,right,caseSensitive)>0;case GREATER_OR_EQUAL->compare(actual,right,caseSensitive)>=0;case LESS->compare(actual,right,caseSensitive)<0;case LESS_OR_EQUAL->compare(actual,right,caseSensitive)<=0;
            case IS_EMPTY->left.isEmpty();case IS_NOT_EMPTY->!left.isEmpty();default->true;
        };
    }
    private static int compare(Object actual,String expected,boolean caseSensitive){
        if(actual instanceof Number)try{return new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.trim()));}catch(NumberFormatException ignored){}
        String left=display(actual),right=expected;return caseSensitive?left.compareTo(right):left.compareToIgnoreCase(right);
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
