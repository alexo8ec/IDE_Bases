package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.model.DatabaseType;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import static com.nexodb.util.I18n.tr;

/** Shared form for mouse drops, column menus and existing relationship lines. Does not execute SQL. */
final class ForeignKeyEditor extends JPanel {
    final JTextField name=new JTextField();
    final JComboBox<String> parent=new JComboBox<>();
    final JComboBox<String> referenceDatabase=new JComboBox<>();
    final JComboBox<String> onDelete=new JComboBox<>(new String[]{"RESTRICT","NO ACTION","CASCADE","SET NULL"});
    final JComboBox<String> onUpdate=new JComboBox<>(new String[]{"RESTRICT","NO ACTION","CASCADE","SET NULL"});
    final DefaultTableModel mapping=new DefaultTableModel(new Object[]{tr("Columna hija"),tr("Columna referenciada")},0);
    final JTable grid=new JTable(mapping);
    final SqlEditorPane preview=new SqlEditorPane();
    final JLabel state=new JLabel();
    private final String database,child,quote;
    private final DatabaseType type;
    private final Map<String,SchemaMetadataReader.DbObject> objects;
    private Map<String,SchemaMetadataReader.DbObject> referenceObjects;
    private final JdbcSession session;private String referenceNamespace;private boolean loadingReferences;private int referenceRequest;
    private final ForeignKeyDefinition previous;
    ForeignKeyEditor(String database,String child,String local,String referencedTable,String referencedColumn,
                     Map<String,SchemaMetadataReader.DbObject> objects,ForeignKeyDefinition previous,DatabaseType type,String quote){
        this(database,child,local,referencedTable,referencedColumn,objects,previous,type,quote,null);
    }
    ForeignKeyEditor(String database,String child,String local,String referencedTable,String referencedColumn,
                     Map<String,SchemaMetadataReader.DbObject> objects,ForeignKeyDefinition previous,DatabaseType type,String quote,JdbcSession session){
        super(new BorderLayout(8,8));this.database=database;this.child=child;this.objects=Map.copyOf(objects);this.referenceObjects=this.objects;this.previous=previous;this.type=type;this.quote=quote;this.session=session;
        referenceNamespace=previous==null?database:Objects.toString(previous.parentDatabase(),database);referenceDatabase.addItem(database);if(!referenceNamespace.equals(database))referenceDatabase.addItem(referenceNamespace);referenceDatabase.setSelectedItem(referenceNamespace);referenceDatabase.setEnabled(session!=null);
        setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        JPanel form=new JPanel(new GridLayout(0,2,8,8));
        addField(form,"Tabla hija",new JLabel(database+" · "+child));addField(form,"Nombre de la relación",name);
        addField(form,"Base referenciada",referenceDatabase);
        objects.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(parent::addItem);
        parent.setSelectedItem(referencedTable!=null?referencedTable:objects.keySet().stream().filter(n->!n.equals(child)).sorted().findFirst().orElse(child));
        addField(form,"Tabla referenciada",parent);addField(form,"Al eliminar",onDelete);addField(form,"Al actualizar",onUpdate);
        String base=("fk_"+child+"_"+Objects.toString(local,"")).replaceAll("[^\\p{L}\\p{N}_]","_");
        name.setText(previous==null?base.substring(0,Math.min(48,base.length()))+"_"+UUID.randomUUID().toString().substring(0,6):previous.name());
        if(previous!=null){onDelete.setSelectedItem(previous.onDelete());onUpdate.setSelectedItem(previous.onUpdate());}
        grid.setRowHeight(26);UiLanguage.headers(grid);installEditors();
        if(previous!=null)previous.columns().forEach(p->mapping.addRow(new Object[]{p.local(),p.referenced()}));
        else mapping.addRow(new Object[]{local==null?firstLocalColumn():local,referencedColumn==null?firstColumn(selectedParent()):referencedColumn});
        JPanel pairs=new JPanel(new BorderLayout(4,4));pairs.add(new JScrollPane(grid));JPanel pairButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
        JButton add=button("Agregar pareja de columnas"),remove=button("Quitar pareja de columnas");pairButtons.add(add);pairButtons.add(remove);pairs.add(pairButtons,BorderLayout.SOUTH);
        add.addActionListener(e->{stopEditing();mapping.addRow(new Object[]{"",""});grid.editCellAt(mapping.getRowCount()-1,0);});
        remove.addActionListener(e->{stopEditing();int row=grid.getSelectedRow();if(row>=0)mapping.removeRow(grid.convertRowIndexToModel(row));});
        JPanel top=new JPanel(new BorderLayout(8,8));top.add(form,BorderLayout.NORTH);top.add(pairs);add(top,BorderLayout.NORTH);pairs.setPreferredSize(new Dimension(700,140));
        preview.setEditable(false);add(new JScrollPane(preview));
        JPanel footer=new JPanel(new GridLayout(0,1,0,3));footer.add(UiLanguage.label("La columna referenciada debe tener un índice compatible. Revisa los tipos de ambas columnas."));footer.add(state);add(footer,BorderLayout.SOUTH);
        parent.addActionListener(e->{if(loadingReferences)return;stopEditing();installEditors();for(int r=0;r<mapping.getRowCount();r++)mapping.setValueAt(firstColumn(selectedParent()),r,1);if(mapping.getRowCount()==0)mapping.addRow(new Object[]{firstLocalColumn(),firstColumn(selectedParent())});});
        referenceDatabase.addActionListener(e->{String selected=Objects.toString(referenceDatabase.getSelectedItem(),database);if(!selected.equals(referenceNamespace))loadReferences(selected,null);});
        mapping.addTableModelListener(e->updatePreview());onDelete.addActionListener(e->updatePreview());onUpdate.addActionListener(e->updatePreview());
        name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){updatePreview();}public void removeUpdate(javax.swing.event.DocumentEvent e){updatePreview();}public void changedUpdate(javax.swing.event.DocumentEvent e){updatePreview();}});
        setPreferredSize(new Dimension(760,545));updatePreview();
        if(session!=null){
            new SwingWorker<List<String>,Void>(){protected List<String> doInBackground()throws Exception{List<String> names=new ArrayList<>();synchronized(session.connection()){try(var rows=session.connection().getMetaData().getCatalogs()){while(rows.next())names.add(rows.getString(1));}}return names;}protected void done(){try{for(String value:get()){boolean present=false;for(int i=0;i<referenceDatabase.getItemCount();i++)if(referenceDatabase.getItemAt(i).equals(value))present=true;if(!present)referenceDatabase.addItem(value);}}catch(Exception ignored){}}}.execute();
            if(!referenceNamespace.equals(database))loadReferences(referenceNamespace,previous);
        }
    }
    private static JButton button(String key){return UiLanguage.text(new JButton(tr(key)),UiLanguage.key(key));}
    private static void addField(JPanel form,String key,JComponent control){form.add(UiLanguage.label(key));form.add(control);}
    private String selectedParent(){return Objects.toString(parent.getSelectedItem(),"");}
    private String firstColumn(String table){var object=referenceObjects.get(table);return object==null?"":object.columns().values().stream().map(SchemaMetadataReader.ColumnDef::name).findFirst().orElse("");}
    private String firstLocalColumn(){return objects.get(child).columns().values().stream().map(SchemaMetadataReader.ColumnDef::name).findFirst().orElse("");}
    private void installEditors(){
        grid.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JComboBox<>(objects.get(child).columns().values().stream().map(SchemaMetadataReader.ColumnDef::name).toArray(String[]::new))));
        grid.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(columnNames(selectedParent()))));
    }
    private String[] columnNames(String table){var object=referenceObjects.get(table);return object==null?new String[0]:object.columns().values().stream().map(SchemaMetadataReader.ColumnDef::name).toArray(String[]::new);}
    private void loadReferences(String namespace,ForeignKeyDefinition initial){
        if(session==null)return;stopEditing();referenceNamespace=namespace;int request=++referenceRequest;loadingReferences=true;parent.setEnabled(false);grid.setEnabled(false);updatePreview();
        new SwingWorker<Map<String,SchemaMetadataReader.DbObject>,Void>(){protected Map<String,SchemaMetadataReader.DbObject> doInBackground()throws Exception{Map<String,SchemaMetadataReader.DbObject> read=new LinkedHashMap<>();SchemaMetadataReader.read(session,namespace).objects().values().stream().filter(o->o.type().equals("TABLE")).forEach(o->read.put(o.name(),o));return read;}protected void done(){if(request!=referenceRequest)return;try{referenceObjects=get();parent.removeAllItems();referenceObjects.keySet().stream().sorted().forEach(parent::addItem);if(initial!=null)parent.setSelectedItem(initial.parentTable());installEditors();if(initial!=null){mapping.setRowCount(0);initial.columns().forEach(p->mapping.addRow(new Object[]{p.local(),p.referenced()}));}else for(int r=0;r<mapping.getRowCount();r++)mapping.setValueAt(firstColumn(selectedParent()),r,1);loadingReferences=false;parent.setEnabled(true);grid.setEnabled(true);updatePreview();}catch(Exception error){loadingReferences=false;referenceObjects=Map.of();state.setText(tr("No se pudieron cargar las tablas de la base referenciada."));preview.restoreText("");}}}.execute();
    }
    void stopEditing(){if(grid.isEditing())grid.getCellEditor().stopCellEditing();}
    ForeignKeyDefinition definition(){
        if(loadingReferences)throw new IllegalArgumentException(tr("Leyendo tablas de la base referenciada…"));
        List<ForeignKeyDefinition.ColumnPair> pairs=new ArrayList<>();
        var object=referenceObjects.get(selectedParent());if(object==null)throw new IllegalArgumentException(tr("Selecciona una tabla referenciada disponible."));
        var local=objects.get(child).columns();var target=object.columns();
        for(int i=0;i<mapping.getRowCount();i++){
            String a=Objects.toString(mapping.getValueAt(i,0),""),b=Objects.toString(mapping.getValueAt(i,1),"");
            var column=local.get(a.toLowerCase(Locale.ROOT));
            if(column==null||!target.containsKey(b.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException(tr("Selecciona columnas existentes de ambas tablas."));
            if(("SET NULL".equals(onDelete.getSelectedItem())||"SET NULL".equals(onUpdate.getSelectedItem()))&&!column.nullable())throw new IllegalArgumentException(tr("SET NULL requiere que todas las columnas hijas permitan NULL."));
            pairs.add(new ForeignKeyDefinition.ColumnPair(a,b));
        }
        return new ForeignKeyDefinition(database,child,name.getText().trim(),referenceNamespace,selectedParent(),pairs,onDelete.getSelectedItem().toString(),onUpdate.getSelectedItem().toString());
    }
    String sql(){var key=definition();return previous==null?key.createSql(quote):key.replaceSql(previous,type,quote);}
    private void updatePreview(){try{preview.restoreText(sql());state.setText(tr("SQL listo para revisar. No se ha ejecutado."));}catch(IllegalArgumentException error){preview.restoreText("");state.setText(error.getMessage());}}
}
