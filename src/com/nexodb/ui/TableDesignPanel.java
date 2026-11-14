package com.nexodb.ui;

import com.nexodb.db.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import static com.nexodb.util.I18n.tr;

/** Spreadsheet-style MySQL table editor. All fields are drafts until a reviewed, confirmed Save. */
final class TableDesignPanel extends JPanel {
    private final JdbcSession session;private final String database,table;private final Runnable changed;
    private MysqlTableDesign.Snapshot snapshot;private Map<String,SchemaMetadataReader.DbObject> objects=Map.of();
    private boolean loading=true,editing,busy,loaded;
    final JTabbedPane tabs=new JTabbedPane();
    final JCheckBox edit=UiLanguage.text(new JCheckBox(),UiLanguage.key("Editar estructura"));
    final JComboBox<String> engine=new JComboBox<>(),charset=new JComboBox<>(),collation=new JComboBox<>();
    final JTextArea comment=new JTextArea(3,30);
    final JTextField autoIncrement=new JTextField(),averageRowLength=new JTextField(),maxRows=new JTextField(),minRows=new JTextField();
    final JComboBox<String> rowFormat=new JComboBox<>(new String[]{"DEFAULT","DYNAMIC","COMPACT","REDUNDANT","COMPRESSED","FIXED","PAGE"});
    final JComboBox<String> checksum=new JComboBox<>(new String[]{"","0","1"}),delayKeyWrite=new JComboBox<>(new String[]{"","0","1"});
    final SqlEditorPane sql=new SqlEditorPane();private final JLabel status=UiLanguage.label("Selecciona Estructura para cargar el diseño.");
    final JButton save=button("Guardar cambios"),revert=button("Revertir cambios");
    private final List<String> columnOriginals=new ArrayList<>(),indexOriginals=new ArrayList<>(),keyOriginals=new ArrayList<>();
    private final List<MysqlTableDesign.Index> indexTemplates=new ArrayList<>();private final List<ForeignKeyDefinition> keyTemplates=new ArrayList<>();
    private final List<JComponent> editControls=new ArrayList<>();
    final DefaultTableModel columns=new DefaultTableModel(new Object[]{"✓",tr("Columna"),tr("Tipo"),tr("Longitud / precisión"),tr("Predeterminado SQL"),"PK", "NOT NULL","UNSIGNED",tr("Auto incremento"),"ZEROFILL","ON UPDATE",tr("Comentario"),tr("Generada"),tr("Expresión"),"Charset","Collation"},0){
        public Class<?> getColumnClass(int c){return Set.of(0,5,6,7,8,9).contains(c)?Boolean.class:String.class;}
        public boolean isCellEditable(int row,int col){return !busy&&(col==0||editing);}
    };
    final DefaultTableModel indexes=new DefaultTableModel(new Object[]{"✓",tr("Índice"),tr("Columnas"),tr("Tipo"),tr("Comentario")},0){public Class<?> getColumnClass(int c){return c==0?Boolean.class:String.class;}public boolean isCellEditable(int r,int c){return !busy&&(c==0||editing&&c!=2);}};
    final DefaultTableModel keys=new DefaultTableModel(new Object[]{"✓",tr("Relación"),tr("Columnas hijas"),tr("Base referenciada"),tr("Tabla referenciada"),tr("Columnas referenciadas"),tr("Al actualizar"),tr("Al eliminar")},0){public Class<?> getColumnClass(int c){return c==0?Boolean.class:String.class;}public boolean isCellEditable(int r,int c){return !busy&&(c==0||editing&&(c==1||c>=6));}};
    final JTable columnGrid=new JTable(columns),indexGrid=new JTable(indexes),keyGrid=new JTable(keys);
    TableDesignPanel(JdbcSession session,String database,String table,Runnable changed){
        super(new BorderLayout(8,8));this.session=session;this.database=database;this.table=table;this.changed=changed;setBorder(new EmptyBorder(10,8,8,8));
        JPanel heading=new JPanel(new GridLayout(0,4,10,6));heading.add(UiLanguage.label("Tabla"));JTextField name=new JTextField(table);name.setEditable(false);heading.add(name);heading.add(UiLanguage.label("Motor"));heading.add(engine);heading.add(UiLanguage.label("Base de datos"));JTextField db=new JTextField(database);db.setEditable(false);heading.add(db);heading.add(UiLanguage.label("Conjunto de caracteres"));heading.add(charset);heading.add(edit);heading.add(new JLabel());heading.add(new JLabel("Collation"));heading.add(collation);add(heading,BorderLayout.NORTH);
        for(JTable grid:List.of(columnGrid,indexGrid,keyGrid)){UiLanguage.headers(grid);grid.setRowHeight(27);grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);grid.getColumnModel().getColumn(0).setPreferredWidth(36);grid.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);for(int i=1;i<grid.getColumnCount();i++)grid.getColumnModel().getColumn(i).setPreferredWidth(i==1?165:125);}
        columnGrid.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(combo(new String[]{"INT","BIGINT","SMALLINT","TINYINT","DECIMAL","FLOAT","DOUBLE","VARCHAR","CHAR","TEXT","LONGTEXT","BLOB","DATE","TIME","DATETIME","TIMESTAMP","YEAR","JSON","ENUM","SET","BOOLEAN","BINARY","VARBINARY"},true)));
        columnGrid.getColumnModel().getColumn(12).setCellEditor(new DefaultCellEditor(combo(new String[]{"","VIRTUAL","STORED"},false)));
        int[] widths={36,160,105,115,160,45,80,85,100,80,165,190,100,220,110,170};for(int i=0;i<widths.length;i++)columnGrid.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        indexGrid.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(combo(new String[]{"INDEX","UNIQUE","PRIMARY","FULLTEXT","SPATIAL"},false)));
        for(int col:new int[]{6,7})keyGrid.getColumnModel().getColumn(col).setCellEditor(new DefaultCellEditor(combo(new String[]{"RESTRICT","NO ACTION","CASCADE","SET NULL"},false)));
        UiLanguage.addTab(tabs,new Object[]{UiLanguage.key("Columnas")},IconFactory.of(IconFactory.Kind.COLUMN,16),gridSection(columnGrid,()->addColumn(),()->{},()->removeRows(columnGrid),true));
        UiLanguage.addTab(tabs,new Object[]{UiLanguage.key("Índices")},IconFactory.of(IconFactory.Kind.KEY,16),gridSection(indexGrid,()->editIndex(false),()->editIndex(true),()->removeRows(indexGrid),false));
        UiLanguage.addTab(tabs,new Object[]{UiLanguage.key("Claves foráneas")},IconFactory.of(IconFactory.Kind.COMPARE,16),gridSection(keyGrid,()->editKey(false),()->editKey(true),()->removeRows(keyGrid),false));
        UiLanguage.addTab(tabs,new Object[]{UiLanguage.key("Avanzado")},IconFactory.of(IconFactory.Kind.EDIT,16),advanced());sql.setEditable(false);UiLanguage.addTab(tabs,new Object[]{UiLanguage.key("Vista previa SQL")},IconFactory.of(IconFactory.Kind.SQL,16),new JScrollPane(sql));add(tabs);
        JPanel foot=new JPanel(new BorderLayout(6,6));foot.add(status);JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(save);buttons.add(revert);foot.add(buttons,BorderLayout.SOUTH);add(foot,BorderLayout.SOUTH);
        editControls.addAll(List.of(engine,charset,collation,comment,autoIncrement,averageRowLength,maxRows,minRows,rowFormat,checksum,delayKeyWrite));
        edit.addActionListener(e->{if(loading)return;if(edit.isSelected()){
            if(session.profile().readOnly()||!Confirmations.ask(this,"Editar estructura",tr("Se habilitará la edición del diseño. Los cambios se aplicarán únicamente al pulsar Guardar y confirmar el SQL. ¿Continuar?"))){edit.setSelected(false);return;}editing=true;
        }else{if(hasChanges()&&!Confirmations.ask(this,"Revertir cambios",tr("Se descartarán los cambios de estructura pendientes. La base de datos no se modificará. ¿Continuar?"))){edit.setSelected(true);return;}editing=false;populate();}updateState();});
        columns.addTableModelListener(e->{if(!loading){if(e.getColumn()==5)syncPrimary();updateState();}});indexes.addTableModelListener(e->{if(!loading)updateState();});keys.addTableModelListener(e->{if(!loading)updateState();});
        charset.addActionListener(e->{if(!loading){Object selected=charset.getSelectedItem();loading=true;collation.removeAllItems();if(snapshot!=null)snapshot.collations().getOrDefault(selected,List.of()).forEach(collation::addItem);loading=false;updateState();}});
        for(JComboBox<?> input:List.of(engine,collation,rowFormat,checksum,delayKeyWrite))input.addActionListener(e->{if(!loading)updateState();});
        for(javax.swing.text.JTextComponent input:List.of(comment,autoIncrement,averageRowLength,maxRows,minRows))input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){updateState();}public void removeUpdate(javax.swing.event.DocumentEvent e){updateState();}public void changedUpdate(javax.swing.event.DocumentEvent e){updateState();}});
        revert.addActionListener(e->{stopEditing();if(Confirmations.ask(this,"Revertir cambios",tr("Se descartarán los cambios de estructura pendientes. La base de datos no se modificará. ¿Continuar?")))populate();});save.addActionListener(e->save());
        installRowMenu(columnGrid);installRowMenu(indexGrid);installRowMenu(keyGrid);
        MouseAdapter details=new MouseAdapter(){public void mouseClicked(MouseEvent e){if(editing&&SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==2){if(e.getSource()==indexGrid)editIndex(true);else editKey(true);}}};indexGrid.addMouseListener(details);keyGrid.addMouseListener(details);
        loading=false;updateState();
    }
    private static JButton button(String key){return UiLanguage.text(new JButton(tr(key)),UiLanguage.key(key));}
    private static JComboBox<String> combo(String[] values,boolean editable){JComboBox<String> combo=new JComboBox<>(values);combo.setEditable(editable);return combo;}
    private JPanel gridSection(JTable grid,Runnable add,Runnable editAction,Runnable remove,boolean reorder){
        JPanel panel=new JPanel(new BorderLayout(5,5));JToolBar actions=new JToolBar();actions.setFloatable(false);JButton plus=button("Agregar"),minus=button("Eliminar seleccionados"),modify=button("Modificar");actions.add(plus);if(!reorder)actions.add(modify);actions.add(minus);plus.addActionListener(e->add.run());minus.addActionListener(e->remove.run());modify.addActionListener(e->editAction.run());editControls.addAll(List.of(plus,minus,modify));
        if(reorder){JButton up=button("Subir"),down=button("Bajar");actions.add(up);actions.add(down);up.addActionListener(e->moveColumn(-1));down.addActionListener(e->moveColumn(1));editControls.addAll(List.of(up,down));}
        panel.add(actions,BorderLayout.NORTH);panel.add(new JScrollPane(grid));return panel;
    }
    private JPanel advanced(){
        JPanel panel=new JPanel(new GridBagLayout());GridBagConstraints g=new GridBagConstraints();g.insets=new Insets(6,8,6,8);g.anchor=GridBagConstraints.NORTHWEST;g.fill=GridBagConstraints.HORIZONTAL;
        String[] labels={"Comentario","Auto incremento","Longitud media de fila","Máximo de filas","Mínimo de filas","Formato de fila","Checksum","Escritura de índices diferida"};JComponent[] inputs={new JScrollPane(comment),autoIncrement,averageRowLength,maxRows,minRows,rowFormat,checksum,delayKeyWrite};
        for(int i=0;i<labels.length;i++){g.gridy=i;g.gridx=0;g.weightx=0;panel.add(UiLanguage.label(labels[i]),g);g.gridx=1;g.weightx=1;panel.add(inputs[i],g);}g.gridy=labels.length;g.weighty=1;panel.add(new JLabel(),g);JPanel wrapper=new JPanel(new BorderLayout());panel.setPreferredSize(new Dimension(800,450));wrapper.add(panel,BorderLayout.WEST);return wrapper;
    }
    void select(int index){tabs.setSelectedIndex(index);loadIfNeeded();}
    void loadIfNeeded(){if(!loaded&&!busy)reload();}
    void refreshIfLoaded(){if(loaded&&!busy&&!hasChanges())reload();}
    private record Loaded(MysqlTableDesign.Snapshot snapshot,Map<String,SchemaMetadataReader.DbObject> objects){}
    private void reload(){
        busy=true;status.setText(tr("Leyendo estructura…"));updateState();
        new SwingWorker<Loaded,Void>(){protected Loaded doInBackground()throws Exception{
            var read=MysqlTableDesign.read(session,database,table);Map<String,SchemaMetadataReader.DbObject> objects=new LinkedHashMap<>();SchemaMetadataReader.read(session,database).objects().values().stream().filter(o->o.type().equals("TABLE")).forEach(o->objects.put(o.name(),o));return new Loaded(read,objects);
        }protected void done(){busy=false;try{var result=get();snapshot=result.snapshot;objects=result.objects;loaded=true;populate();}catch(Exception error){status.setText(root(error).getMessage());updateState();}}}.execute();
    }
    private void populate(){
        if(snapshot==null)return;loading=true;
        try{
            columns.setRowCount(0);columnOriginals.clear();Set<String> primary=new HashSet<>();snapshot.indexes().stream().filter(i->i.kind().equals("PRIMARY")).forEach(i->i.columns().forEach(p->primary.add(p.name())));
            for(var c:snapshot.columns()){columnOriginals.add(c.name());columns.addRow(columnRow(c,primary.contains(c.name())));}
            indexes.setRowCount(0);indexOriginals.clear();indexTemplates.clear();for(var i:snapshot.indexes()){indexOriginals.add(i.name());indexTemplates.add(i);indexes.addRow(indexRow(i));}
            keys.setRowCount(0);keyOriginals.clear();keyTemplates.clear();for(var k:snapshot.keys()){keyOriginals.add(k.name());keyTemplates.add(k);keys.addRow(keyRow(k));}
            var o=snapshot.options();engine.removeAllItems();snapshot.engines().forEach(engine::addItem);engine.setSelectedItem(o.engine());charset.removeAllItems();snapshot.collations().keySet().stream().sorted().forEach(charset::addItem);charset.setSelectedItem(o.charset());collation.removeAllItems();snapshot.collations().getOrDefault(o.charset(),List.of()).forEach(collation::addItem);collation.setSelectedItem(o.collation());comment.setText(o.comment());autoIncrement.setText(o.autoIncrement());averageRowLength.setText(o.averageRowLength());maxRows.setText(o.maxRows());minRows.setText(o.minRows());rowFormat.setSelectedItem(o.rowFormat());checksum.setSelectedItem(o.checksum());delayKeyWrite.setSelectedItem(o.delayKeyWrite());
        }finally{loading=false;}updateState();
    }
    private Object[] columnRow(MysqlTableDesign.Column c,boolean pk){return new Object[]{false,c.name(),c.type(),c.length(),c.defaultSql(),pk,c.notNull(),c.unsigned(),c.autoIncrement(),c.zerofill(),c.onUpdate(),c.comment(),c.generated(),c.expression(),c.charset(),c.collation()};}
    private Object[] indexRow(MysqlTableDesign.Index i){return new Object[]{false,i.name(),String.join(", ",i.columns().stream().map(c->c.name()+(c.prefix().isBlank()?"":"("+c.prefix()+")")+(c.order().equals("DESC")?" DESC":"")).toList()),i.kind(),i.comment()};}
    private Object[] keyRow(ForeignKeyDefinition k){return new Object[]{false,k.name(),String.join(", ",k.columns().stream().map(ForeignKeyDefinition.ColumnPair::local).toList()),k.parentDatabase(),k.parentTable(),String.join(", ",k.columns().stream().map(ForeignKeyDefinition.ColumnPair::referenced).toList()),k.onUpdate(),k.onDelete()};}
    private String text(DefaultTableModel model,int row,int col){return Objects.toString(model.getValueAt(row,col),"").trim();}
    private boolean check(int row,int col){return Boolean.TRUE.equals(columns.getValueAt(row,col));}
    private List<MysqlTableDesign.ColumnChange> columnChanges(){
        List<MysqlTableDesign.ColumnChange> changes=new ArrayList<>();for(int r=0;r<columns.getRowCount();r++){
            String original=columnOriginals.get(r);var before=snapshot.columns().stream().filter(c->c.name().equals(original)).findFirst().orElse(null);
            var c=new MysqlTableDesign.Column(text(columns,r,1),text(columns,r,2),text(columns,r,3),check(r,6),check(r,7),check(r,8),check(r,9),text(columns,r,4),text(columns,r,10),Objects.toString(columns.getValueAt(r,11),""),text(columns,r,12),text(columns,r,13),text(columns,r,14),text(columns,r,15),before!=null&&before.invisible(),before==null?"":before.unsupported());changes.add(new MysqlTableDesign.ColumnChange(original,c));
        }return changes;
    }
    private List<MysqlTableDesign.IndexChange> indexChanges(){List<MysqlTableDesign.IndexChange> changes=new ArrayList<>();for(int r=0;r<indexes.getRowCount();r++){var template=indexTemplates.get(r);String kind=text(indexes,r,3);changes.add(new MysqlTableDesign.IndexChange(indexOriginals.get(r),new MysqlTableDesign.Index(kind.equals("PRIMARY")?"PRIMARY":text(indexes,r,1),kind,template.columns(),template.method(),Objects.toString(indexes.getValueAt(r,4),""),template.invisible(),template.unsupported())));}return changes;}
    private List<MysqlTableDesign.KeyChange> keyChanges(){List<MysqlTableDesign.KeyChange> changes=new ArrayList<>();for(int r=0;r<keys.getRowCount();r++){var k=keyTemplates.get(r);changes.add(new MysqlTableDesign.KeyChange(keyOriginals.get(r),new ForeignKeyDefinition(database,table,text(keys,r,1),k.parentDatabase(),k.parentTable(),k.columns(),text(keys,r,7),text(keys,r,6))));}return changes;}
    private MysqlTableDesign.Options options(){return new MysqlTableDesign.Options(Objects.toString(engine.getSelectedItem(),""),Objects.toString(charset.getSelectedItem(),""),Objects.toString(collation.getSelectedItem(),""),comment.getText(),autoIncrement.getText().trim(),Objects.toString(rowFormat.getSelectedItem(),""),averageRowLength.getText().trim(),maxRows.getText().trim(),minRows.getText().trim(),Objects.toString(checksum.getSelectedItem(),""),Objects.toString(delayKeyWrite.getSelectedItem(),""));}
    String pendingSql(){if(snapshot==null)return "";return MysqlTableDesign.plan(snapshot,columnChanges(),indexChanges(),keyChanges(),options());}
    private boolean hasChanges(){try{return !pendingSql().isBlank();}catch(Exception error){return true;}}
    private void updateState(){
        if(loading)return;edit.setEnabled(loaded&&!busy&&!session.profile().readOnly());for(JComponent c:editControls)c.setEnabled(editing&&!busy);save.setEnabled(false);revert.setEnabled(false);if(snapshot==null)return;
        try{String pending=pendingSql();sql.restoreText(pending.isBlank()?snapshot.ddl()+";\n":pending);boolean dirty=!pending.isBlank();save.setEnabled(dirty&&editing&&!busy&&!session.profile().readOnly());revert.setEnabled(dirty&&!busy);status.setText(tr(dirty?"Cambios pendientes. Revisa Vista previa SQL y pulsa Guardar.":"Sin cambios pendientes."));}catch(Exception error){sql.restoreText("");status.setText(error.getMessage());revert.setEnabled(!busy);}
    }
    private void stopEditing(){for(JTable grid:List.of(columnGrid,indexGrid,keyGrid))if(grid.isEditing())grid.getCellEditor().stopCellEditing();}
    private void addColumn(){stopEditing();String name="columna_nueva";columnOriginals.add(null);columns.addRow(columnRow(new MysqlTableDesign.Column(name,"VARCHAR","255",false,false,false,false,"","","","","","","",false,""),false));columnGrid.setRowSelectionInterval(columns.getRowCount()-1,columns.getRowCount()-1);}
    private void removeRows(JTable grid){
        stopEditing();DefaultTableModel model=(DefaultTableModel)grid.getModel();List<Integer> rows=new ArrayList<>();for(int r=0;r<model.getRowCount();r++)if(Boolean.TRUE.equals(model.getValueAt(r,0)))rows.add(r);if(rows.isEmpty())for(int row:grid.getSelectedRows())rows.add(grid.convertRowIndexToModel(row));if(rows.isEmpty())return;
        if(!Confirmations.ask(this,"Eliminar del diseño",tr("Se marcarán %s elementos para eliminar. Se aplicará únicamente al guardar. ¿Continuar?",rows.size())))return;
        loading=true;rows.sort(Comparator.reverseOrder());for(int row:rows){if(grid==columnGrid)columnOriginals.remove(row);else if(grid==indexGrid){indexOriginals.remove(row);indexTemplates.remove(row);}else{keyOriginals.remove(row);keyTemplates.remove(row);}model.removeRow(row);}loading=false;if(grid==columnGrid)syncPrimary();updateState();
    }
    private void moveColumn(int delta){stopEditing();int row=columnGrid.getSelectedRow(),next=row+delta;if(row<0||next<0||next>=columns.getRowCount())return;loading=true;String original=columnOriginals.remove(row);columnOriginals.add(next,original);columns.moveRow(row,row,next);columnGrid.setRowSelectionInterval(next,next);loading=false;updateState();}
    private void syncPrimary(){
        List<MysqlTableDesign.IndexColumn> primary=new ArrayList<>();for(int r=0;r<columns.getRowCount();r++)if(check(r,5))primary.add(new MysqlTableDesign.IndexColumn(text(columns,r,1),"","ASC"));
        loading=true;for(int r=0;r<columns.getRowCount();r++)if(check(r,5))columns.setValueAt(true,r,6);int row=-1;for(int r=0;r<indexes.getRowCount();r++)if(text(indexes,r,3).equals("PRIMARY")){row=r;break;}
        if(primary.isEmpty()){if(row>=0){indexes.removeRow(row);indexOriginals.remove(row);indexTemplates.remove(row);}}
        else{var key=new MysqlTableDesign.Index("PRIMARY","PRIMARY",primary,"BTREE","",false,"");if(row<0){indexOriginals.add(null);indexTemplates.add(key);indexes.addRow(indexRow(key));}else{indexTemplates.set(row,key);indexes.setValueAt(indexRow(key)[2],row,2);}}
        loading=false;
    }
    private void editIndex(boolean existing){
        stopEditing();int row=existing?indexGrid.getSelectedRow():-1;if(existing&&row<0)return;
        MysqlTableDesign.Index old=existing?indexChanges().get(row).value():null;if(old!=null&&!old.unsupported().isBlank()){JOptionPane.showMessageDialog(this,tr("Este índice usa opciones especiales. Modifícalo desde SQL para conservarlas: %s",old.name()));return;}
        JTextField name=new JTextField(old==null?"idx_"+table:old.name());JComboBox<String> kind=combo(new String[]{"INDEX","UNIQUE","PRIMARY","FULLTEXT","SPATIAL"},false);if(old!=null)kind.setSelectedItem(old.kind());JTextField note=new JTextField(old==null?"":old.comment());
        DefaultTableModel choices=new DefaultTableModel(new Object[]{"✓",tr("Columna"),tr("Tipo"),tr("Longitud de prefijo"),tr("Orden")},0){public Class<?> getColumnClass(int col){return col==0?Boolean.class:String.class;}public boolean isCellEditable(int r,int c){return c==0||c>=3;}};
        List<MysqlTableDesign.Column> available=columnChanges().stream().map(MysqlTableDesign.ColumnChange::value).toList();List<String> ordered=new ArrayList<>();if(old!=null)old.columns().forEach(p->ordered.add(p.name()));available.stream().map(MysqlTableDesign.Column::name).filter(n->!ordered.contains(n)).forEach(ordered::add);
        for(String column:ordered){var part=old==null?null:old.columns().stream().filter(p->p.name().equals(column)).findFirst().orElse(null);String type=available.stream().filter(c->c.name().equals(column)).map(MysqlTableDesign.Column::type).findFirst().orElse("");choices.addRow(new Object[]{part!=null,column,type,part==null?"":part.prefix(),part==null?"ASC":part.order()});}
        JTable picker=new JTable(choices);picker.setRowHeight(26);picker.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(combo(new String[]{"ASC","DESC"},false)));JPanel content=new JPanel(new BorderLayout(6,6));JPanel fields=new JPanel(new GridLayout(0,2,6,6));fields.add(UiLanguage.label("Nombre"));fields.add(name);fields.add(UiLanguage.label("Tipo"));fields.add(kind);fields.add(UiLanguage.label("Comentario"));fields.add(note);content.add(fields,BorderLayout.NORTH);content.add(new JScrollPane(picker));JPanel move=new JPanel(new FlowLayout(FlowLayout.LEFT));for(int delta:new int[]{-1,1}){JButton b=button(delta<0?"Subir":"Bajar");b.addActionListener(e->{if(picker.isEditing())picker.getCellEditor().stopCellEditing();int r=picker.getSelectedRow(),n=r+delta;if(r>=0&&n>=0&&n<choices.getRowCount()){choices.moveRow(r,r,n);picker.setRowSelectionInterval(n,n);}});move.add(b);}content.add(move,BorderLayout.SOUTH);content.setPreferredSize(new Dimension(720,420));
        if(JOptionPane.showConfirmDialog(this,content,tr("Seleccionar columnas del índice"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;if(picker.isEditing())picker.getCellEditor().stopCellEditing();List<MysqlTableDesign.IndexColumn> parts=new ArrayList<>();for(int i=0;i<choices.getRowCount();i++)if(Boolean.TRUE.equals(choices.getValueAt(i,0)))parts.add(new MysqlTableDesign.IndexColumn(text(choices,i,1),text(choices,i,3),text(choices,i,4)));
        var result=new MysqlTableDesign.Index(kind.getSelectedItem().equals("PRIMARY")?"PRIMARY":name.getText().trim(),kind.getSelectedItem().toString(),parts,old==null?"BTREE":old.method(),note.getText(),old!=null&&old.invisible(),"");
        try{MysqlTableDesign.indexSql(result,snapshot.noBackslashEscapes());loading=true;if(existing){indexTemplates.set(row,result);for(int c=0;c<indexes.getColumnCount();c++)indexes.setValueAt(indexRow(result)[c],row,c);}else{indexOriginals.add(null);indexTemplates.add(result);indexes.addRow(indexRow(result));}Set<String> primary=new HashSet<>();for(var index:indexChanges())if(index.value().kind().equals("PRIMARY"))index.value().columns().forEach(p->primary.add(p.name()));for(int r=0;r<columns.getRowCount();r++)columns.setValueAt(primary.contains(text(columns,r,1)),r,5);}catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage());}finally{loading=false;}updateState();
    }
    private void editKey(boolean existing){
        stopEditing();int row=existing?keyGrid.getSelectedRow():-1;if(existing&&row<0)return;var old=existing?keyChanges().get(row).value():null;
        Map<String,SchemaMetadataReader.DbObject> current=new LinkedHashMap<>(objects);Map<String,SchemaMetadataReader.ColumnDef> local=new LinkedHashMap<>();for(var col:columnChanges()){var c=col.value();local.put(c.name().toLowerCase(Locale.ROOT),new SchemaMetadataReader.ColumnDef(c.name(),c.type(),0,0,!c.notNull(),c.defaultSql(),c.autoIncrement()?"YES":"NO"));}current.put(table,new SchemaMetadataReader.DbObject("TABLE",table,local));
        ForeignKeyEditor editor=new ForeignKeyEditor(database,table,old==null?null:old.columns().get(0).local(),old==null?null:old.parentTable(),old==null?null:old.columns().get(0).referenced(),current,old,session.profile().type(),"`",session);
        if(JOptionPane.showConfirmDialog(this,editor,tr("Claves foráneas: editar borrador"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;editor.stopEditing();
        try{var result=editor.definition();result.createSql("`");loading=true;if(existing){keyTemplates.set(row,result);for(int c=0;c<keys.getColumnCount();c++)keys.setValueAt(keyRow(result)[c],row,c);}else{keyOriginals.add(null);keyTemplates.add(result);keys.addRow(keyRow(result));}}catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage());}finally{loading=false;}updateState();
    }
    private void installRowMenu(JTable grid){JPopupMenu menu=new JPopupMenu();JMenuItem change=new JMenuItem(tr("Modificar")),remove=new JMenuItem(tr("Eliminar seleccionados"));menu.add(change);menu.add(remove);change.addActionListener(e->{if(!editing)return;if(grid==indexGrid)editIndex(true);else if(grid==keyGrid)editKey(true);else if(grid.getSelectedRow()>=0)grid.editCellAt(grid.getSelectedRow(),1);});remove.addActionListener(e->{if(editing)removeRows(grid);});grid.addMouseListener(new MouseAdapter(){private void popup(MouseEvent e){if(!e.isPopupTrigger())return;int row=grid.rowAtPoint(e.getPoint());if(row>=0&&!grid.isRowSelected(row))grid.setRowSelectionInterval(row,row);change.setEnabled(editing&&!busy);remove.setEnabled(editing&&!busy);menu.show(grid,e.getX(),e.getY());}public void mousePressed(MouseEvent e){popup(e);}public void mouseReleased(MouseEvent e){popup(e);}});}
    private void save(){
        stopEditing();if(!editing||busy||session.profile().readOnly())return;String pending;
        try{pending=pendingSql();if(pending.isBlank())return;}catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage());return;}
        tabs.setSelectedIndex(4);if(!LicenseGate.ensure(this)||!Confirmations.ask(this,"Guardar estructura",database+" · "+table+"\n\n"+pending+"\n"+tr("Los cambios de estructura pueden eliminar columnas y sus datos. ¿Aplicar este SQL?")))return;
        busy=true;updateState();status.setText(tr("Aplicando cambio de estructura…"));MysqlTableDesign.Snapshot approved=snapshot;
        new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{MysqlTableDesign.apply(session,approved,pending);return null;}protected void done(){busy=false;try{get();reload();changed.run();}catch(Exception error){updateState();JOptionPane.showMessageDialog(TableDesignPanel.this,root(error).getMessage(),tr("No se aplicó el cambio"),JOptionPane.ERROR_MESSAGE);}}}.execute();
    }
    boolean canClose(){stopEditing();return !busy&&(!hasChanges()||Confirmations.ask(this,"Cerrar estructura",tr("Hay cambios de estructura sin guardar. ¿Cerrar y descartarlos?")));}
    void requestColumn(String name,boolean delete){loadIfNeeded();if(!loaded)return;tabs.setSelectedIndex(0);for(int r=0;r<columns.getRowCount();r++)if(text(columns,r,1).equals(name)){columnGrid.setRowSelectionInterval(r,r);columnGrid.scrollRectToVisible(columnGrid.getCellRect(r,1,true));if(delete&&editing)removeRows(columnGrid);return;}}
    private static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
}
