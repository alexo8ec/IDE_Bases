package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;
import com.nexodb.db.*;
import com.nexodb.model.DatabaseType;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** Visual SELECT builder and schema explorer. SQL is handed to a separate editor for review. */
public final class VisualDatabasePanel extends JPanel {
    private final JdbcSession session;private final String database;private final boolean designer;
    private final Consumer<String> sqlConsumer;private final Consumer<SchemaItem> structureConsumer;
    private final DefaultListModel<String> names=new DefaultListModel<>();private final JList<String> tables=new JList<>(names);
    private final JPanel canvas=new JPanel(null){protected void paintComponent(Graphics g){super.paintComponent(g);g.setColor(ThemeManager.color(0xF8FAFC,0x252A31));g.fillRect(0,0,getWidth(),getHeight());paintLinks((Graphics2D)g);}};
    private final Map<String,Card> cards=new LinkedHashMap<>();private final Map<String,SchemaMetadataReader.DbObject> metadata=new LinkedHashMap<>();
    private final List<VisualQuery.Join> joins=new ArrayList<>();private final SqlEditorPane preview=new SqlEditorPane();
    private final DefaultTableModel fields=new DefaultTableModel(new Object[]{"✓",tr("Tabla"),tr("Columna"),tr("Agregado"),"Alias",tr("Orden"),tr("Criterio")},0){public Class<?> getColumnClass(int col){return col==0?Boolean.class:String.class;}public boolean isCellEditable(int r,int c){return c!=1&&c!=2;}};
    private final JTable grid=new JTable(fields);private final JComboBox<String> joinType=new JComboBox<>(new String[]{"INNER","LEFT","RIGHT"});
    private final JCheckBox distinct=new JCheckBox("DISTINCT"),link=new JCheckBox(tr("Relacionar columnas"));private final JSpinner limit=new JSpinner(new SpinnerNumberModel(1000,0,100000,100));
    private final JLabel status=new JLabel(tr("Leyendo objetos…"));private String quote="\"",pendingAlias,pendingColumn;private int nextAlias;
    public VisualDatabasePanel(JdbcSession session,String database,boolean designer,Consumer<String> sqlConsumer,Consumer<SchemaItem> structureConsumer){
        super(new BorderLayout(8,8));this.session=session;this.database=database;this.designer=designer;this.sqlConsumer=sqlConsumer;this.structureConsumer=structureConsumer;setBorder(new EmptyBorder(10,10,10,10));
        JToolBar bar=new JToolBar();bar.setFloatable(false);bar.add(new JLabel(session.profile().name()+" · "+database+"   "));JButton add=button("Agregar tabla"),remove=button("Quitar tabla"),send=button("Abrir SQL en consulta nueva"),refresh=button("Actualizar");bar.add(add);bar.add(remove);bar.add(refresh);
        if(designer){JButton create=button("Nueva tabla…"),structure=button("Editar estructura…"),save=button("Guardar diagrama…"),load=button("Abrir diagrama…");bar.add(create);bar.add(structure);bar.add(save);bar.add(load);bar.add(UiLanguage.text(link,UiLanguage.key("Crear clave foránea")));create.addActionListener(e->createTable());structure.addActionListener(e->openStructure());save.addActionListener(e->saveLayout());load.addActionListener(e->loadLayout());}
        else{bar.addSeparator();bar.add(UiLanguage.text(link,UiLanguage.key("Relacionar columnas")));bar.add(joinType);bar.add(distinct);bar.add(UiLanguage.label("Límite: "));bar.add(limit);bar.add(send);}add(bar,BorderLayout.NORTH);
        JPanel list=new JPanel(new BorderLayout(4,5));JTextField filter=new JTextField();UiLanguage.placeholder(filter,"Buscar tablas…");list.add(filter,BorderLayout.NORTH);list.add(new JScrollPane(tables));tables.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){void update(){names.clear();metadata.keySet().stream().filter(n->n.toLowerCase(Locale.ROOT).contains(filter.getText().toLowerCase(Locale.ROOT))).forEach(names::addElement);}public void insertUpdate(javax.swing.event.DocumentEvent e){update();}public void removeUpdate(javax.swing.event.DocumentEvent e){update();}public void changedUpdate(javax.swing.event.DocumentEvent e){}});
        canvas.setPreferredSize(new Dimension(1800,1200));canvas.setBackground(ThemeManager.color(0xF8FAFC,0x252A31));JSplitPane horizontal=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,list,new JScrollPane(canvas));horizontal.setDividerLocation(220);horizontal.setResizeWeight(.15);
        if(designer)add(horizontal,BorderLayout.CENTER);else{
            preview.setEditable(false);UiLanguage.headers(grid);grid.setRowHeight(25);grid.getColumnModel().getColumn(0).setMaxWidth(40);grid.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"","COUNT","SUM","AVG","MIN","MAX"})));grid.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"","ASC","DESC"})));
            JPanel options=new JPanel(new BorderLayout(4,4));options.add(new JScrollPane(grid));JButton delete=button("Quitar columna");delete.addActionListener(e->{int row=grid.getSelectedRow();if(row>=0)fields.removeRow(grid.convertRowIndexToModel(row));});JPanel fieldActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,3,0));fieldActions.add(delete);options.add(fieldActions,BorderLayout.SOUTH);
            JSplitPane lower=new JSplitPane(JSplitPane.VERTICAL_SPLIT,options,new JScrollPane(preview));lower.setResizeWeight(.5);lower.setDividerLocation(130);JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,horizontal,lower);split.setDividerLocation(300);split.setResizeWeight(.55);add(split);
        }
        JPanel foot=new JPanel(new BorderLayout());foot.add(status,BorderLayout.NORTH);foot.add(UiLanguage.label(designer?"Arrastra las tablas. Doble clic en una tabla para editar columnas, índices o relaciones.":"Doble clic en una columna para agregarla. Activa Relacionar columnas y selecciona ambos extremos del JOIN."),BorderLayout.SOUTH);add(foot,BorderLayout.SOUTH);
        add.addActionListener(e->tables.getSelectedValuesList().forEach(this::addTable));remove.addActionListener(e->{for(String name:tables.getSelectedValuesList())removeTable(name);});refresh.addActionListener(e->load());send.addActionListener(e->{if(grid.isEditing())grid.getCellEditor().stopCellEditing();generate();if(!preview.getText().isBlank())sqlConsumer.accept(preview.getText());});
        tables.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)tables.getSelectedValuesList().forEach(VisualDatabasePanel.this::addTable);}});
        fields.addTableModelListener(e->generate());distinct.addActionListener(e->generate());limit.addChangeListener(e->generate());load();
    }
    private static JButton button(String text){return UiLanguage.text(new JButton(tr(text)),UiLanguage.key(text));}
    private SchemaItem item(String name){boolean catalog=SchemaMetadataReader.catalogNamespace(session.profile().type());return new SchemaItem(SchemaItem.Kind.TABLE,catalog?database:null,catalog?null:database,name,"");}
    private void load(){
        if(!LicenseGate.ensure(this))return;
        new SwingWorker<SchemaMetadataReader.SchemaDef,Void>(){protected SchemaMetadataReader.SchemaDef doInBackground()throws Exception{quote=session.connection().getMetaData().getIdentifierQuoteString();return SchemaMetadataReader.read(session,database);}protected void done(){try{metadata.clear();get().objects().values().stream().filter(o->o.type().equals("TABLE")).forEach(o->metadata.put(o.name(),o));names.clear();metadata.keySet().forEach(names::addElement);for(String name:new ArrayList<>(cards.keySet()))removeTable(name);status.setText(tr("%s tablas disponibles",names.size()));}catch(Exception error){status.setText(error.getCause()==null?error.getMessage():error.getCause().getMessage());}}}.execute();
    }
    private void addTable(String name){
        if(cards.containsKey(name)||!metadata.containsKey(name))return;Card card=new Card(metadata.get(name),"t"+(++nextAlias));cards.put(name,card);canvas.add(card);int index=cards.size()-1;card.setBounds(40+(index%4)*300,35+(index/4)*300,255,Math.min(260,65+card.columns.getModel().getSize()*23));canvas.revalidate();canvas.repaint();if(designer)loadForeignKeys(card);generate();
    }
    private void removeTable(String name){Card removed=cards.remove(name);if(removed==null)return;canvas.remove(removed);joins.removeIf(j->j.left().equals(removed.alias)||j.right().equals(removed.alias));for(int i=fields.getRowCount()-1;i>=0;i--)if(fields.getValueAt(i,1).equals(removed.alias))fields.removeRow(i);canvas.repaint();generate();}
    private void chooseColumn(Card card,String column){
        if(link.isSelected()){
            if(pendingAlias==null){pendingAlias=card.alias;pendingColumn=column;status.setText(tr("Selecciona la columna de la otra tabla."));}
            else if(!pendingAlias.equals(card.alias)){
                Card left=cards.values().stream().filter(c->c.alias.equals(pendingAlias)).findFirst().orElseThrow();int leftIndex=new ArrayList<>(cards.values()).indexOf(left),rightIndex=new ArrayList<>(cards.values()).indexOf(card);
                if(designer){String constraint=("fk_"+left.name+"_"+pendingColumn).replaceAll("[^\\p{L}\\p{N}_]","_");if(constraint.length()>60)constraint=constraint.substring(0,60);sqlConsumer.accept("ALTER TABLE "+VisualQuery.qualified(database,left.name,quote)+"\nADD CONSTRAINT "+VisualQuery.quote(constraint,quote)+" FOREIGN KEY ("+VisualQuery.quote(pendingColumn,quote)+")\nREFERENCES "+VisualQuery.qualified(database,card.name,quote)+" ("+VisualQuery.quote(column,quote)+");\n");}
                else{String type=joinType.getSelectedItem().toString();joins.add(leftIndex<rightIndex?new VisualQuery.Join(left.alias,pendingColumn,card.alias,column,type):new VisualQuery.Join(card.alias,column,left.alias,pendingColumn,type.equals("LEFT")?"RIGHT":type.equals("RIGHT")?"LEFT":type));}pendingAlias=null;pendingColumn=null;link.setSelected(false);canvas.repaint();generate();
            }
        }else if(!designer)fields.addRow(new Object[]{true,card.alias,column,"","","",""});
    }
    private void generate(){if(designer)return;try{
        List<VisualQuery.Table> from=cards.values().stream().map(c->new VisualQuery.Table(c.name,c.alias)).toList();List<VisualQuery.Field> selected=new ArrayList<>();for(int i=0;i<fields.getRowCount();i++)selected.add(new VisualQuery.Field(value(i,1),value(i,2),Boolean.TRUE.equals(fields.getValueAt(i,0)),value(i,3),value(i,4),value(i,5),value(i,6)));
        preview.restoreText(VisualQuery.select(session.profile().type(),quote,database,from,joins,selected,distinct.isSelected(),((Number)limit.getValue()).intValue()));status.setText(tr("SQL listo para revisar. No se ha ejecutado."));
    }catch(Exception error){preview.restoreText("");status.setText(error.getMessage());}}
    private String value(int row,int col){return Objects.toString(fields.getValueAt(row,col),"").trim();}
    private void loadForeignKeys(Card card){
        new SwingWorker<List<VisualQuery.Join>,Void>(){protected List<VisualQuery.Join> doInBackground()throws Exception{
            List<VisualQuery.Join> found=new ArrayList<>();SchemaItem item=item(card.name);synchronized(session.connection()){try(var rows=session.connection().getMetaData().getImportedKeys(item.catalog(),item.schema(),card.name)){while(rows.next())found.add(new VisualQuery.Join(card.name,rows.getString("FKCOLUMN_NAME"),rows.getString("PKTABLE_NAME"),rows.getString("PKCOLUMN_NAME"),"FK"));}}return found;
        }protected void done(){try{if(cards.get(card.name)!=card)return;for(var j:get())if(!joins.contains(j))joins.add(j);canvas.repaint();}catch(Exception error){status.setText(tr("No se pudieron leer algunas relaciones."));}}}.execute();
    }
    private Card byAlias(String alias){return designer?cards.get(alias):cards.values().stream().filter(c->c.alias.equals(alias)).findFirst().orElse(null);}
    private void paintLinks(Graphics2D g){
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setStroke(new BasicStroke(2));g.setColor(ThemeManager.color(0x2563EB,0x82AAFF));
        for(var join:joins){Card a=byAlias(join.left()),b=byAlias(join.right());if(a==null||b==null)continue;Point p=a.anchor(join.leftColumn()),q=b.anchor(join.rightColumn());int bend=(p.x+q.x)/2;g.drawLine(p.x,p.y,bend,p.y);g.drawLine(bend,p.y,bend,q.y);g.drawLine(bend,q.y,q.x,q.y);g.drawString(join.type(),bend+3,(p.y+q.y)/2-4);}
    }
    private void openStructure(){String name=tables.getSelectedValue();if(name==null&&!cards.isEmpty())name=cards.keySet().iterator().next();if(name!=null)structureConsumer.accept(item(name));}
    private void createTable(){
        JTextField name=new JTextField("nueva_tabla");DefaultTableModel columns=new DefaultTableModel(new Object[]{tr("Nombre"),tr("Tipo SQL"),"PRIMARY KEY",tr("Permitir NULL")},0){public Class<?> getColumnClass(int c){return c>=2?Boolean.class:String.class;}};columns.addRow(new Object[]{"id","INT",true,false});JTable table=new JTable(columns);JPanel form=new JPanel(new BorderLayout(6,6));form.add(name,BorderLayout.NORTH);form.add(new JScrollPane(table));JButton add=button("Agregar columna");add.addActionListener(e->columns.addRow(new Object[]{"columna","VARCHAR(255)",false,true}));form.add(add,BorderLayout.SOUTH);form.setPreferredSize(new Dimension(650,300));
        if(JOptionPane.showConfirmDialog(this,form,tr("Nueva tabla: generar SQL"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;if(table.isEditing())table.getCellEditor().stopCellEditing();try{
            List<String> definitions=new ArrayList<>(),keys=new ArrayList<>();for(int i=0;i<columns.getRowCount();i++){String col=Objects.toString(columns.getValueAt(i,0),"").trim(),type=Objects.toString(columns.getValueAt(i,1),"").trim();if(col.isBlank()||!type.matches("[\\w ]+(?:\\([0-9, ]+\\))?"))throw new IllegalArgumentException(tr("Revisa los nombres y tipos de las columnas."));boolean primary=Boolean.TRUE.equals(columns.getValueAt(i,2));definitions.add("    "+VisualQuery.quote(col,quote)+" "+type+(!primary&&Boolean.TRUE.equals(columns.getValueAt(i,3))?" NULL":" NOT NULL"));if(primary)keys.add(VisualQuery.quote(col,quote));}
            if(!keys.isEmpty())definitions.add("    PRIMARY KEY ("+String.join(", ",keys)+")");sqlConsumer.accept("CREATE TABLE "+VisualQuery.qualified(database,name.getText().trim(),quote)+" (\n"+String.join(",\n",definitions)+"\n);\n");
        }catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage());}
    }
    private void saveLayout(){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("diagrama.nexodiagram"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Properties p=new Properties();p.setProperty("database",database);int i=0;for(Card card:cards.values()){p.setProperty(i+".table",card.name);p.setProperty(i+".x",""+card.getX());p.setProperty(i+".y",""+card.getY());i++;}p.setProperty("count",""+i);com.nexodb.util.AtomicProperties.write(chooser.getSelectedFile().toPath(),p);status.setText(tr("Diagrama guardado."));}catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage());}}
    private void loadLayout(){JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Properties p=com.nexodb.util.AtomicProperties.read(chooser.getSelectedFile().toPath());if(!database.equals(p.getProperty("database")))throw new IllegalArgumentException(tr("El diagrama corresponde a otra base de datos."));int count=Integer.parseInt(p.getProperty("count","0"));if(count<0||count>1000)throw new IllegalArgumentException("Invalid diagram");for(String name:new ArrayList<>(cards.keySet()))removeTable(name);for(int i=0;i<count;i++){String name=p.getProperty(i+".table");addTable(name);Card card=cards.get(name);if(card!=null)card.setLocation(Math.max(0,Integer.parseInt(p.getProperty(i+".x","0"))),Math.max(0,Integer.parseInt(p.getProperty(i+".y","0"))));}canvas.repaint();}catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage());}}
    private final class Card extends JPanel {
        final String name,alias;final JList<String> columns;
        Card(SchemaMetadataReader.DbObject object,String alias){super(new BorderLayout(0,4));this.name=object.name();this.alias=alias;setBorder(new CompoundBorder(new LineBorder(ThemeManager.color(0x94A3B8,0x64748B)),new EmptyBorder(6,6,6,6)));JLabel title=new JLabel(name+(designer?"":"  ["+alias+"]"),IconFactory.of(IconFactory.Kind.TABLE,16),SwingConstants.LEFT);title.setFont(title.getFont().deriveFont(Font.BOLD));add(title,BorderLayout.NORTH);columns=new JList<>(object.columns().values().stream().map(SchemaMetadataReader.ColumnDef::name).toArray(String[]::new));columns.setFixedCellHeight(23);add(new JScrollPane(columns));
            MouseAdapter drag=new MouseAdapter(){Point start;public void mousePressed(MouseEvent e){start=e.getPoint();}public void mouseDragged(MouseEvent e){setLocation(Math.max(0,getX()+e.getX()-start.x),Math.max(0,getY()+e.getY()-start.y));canvas.repaint();}public void mouseClicked(MouseEvent e){if(designer&&e.getClickCount()==2)structureConsumer.accept(item(name));}};title.addMouseListener(drag);title.addMouseMotionListener(drag);
            columns.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){int row=columns.locationToIndex(e.getPoint());if(row>=0&&(link.isSelected()||e.getClickCount()==2))chooseColumn(Card.this,columns.getModel().getElementAt(row));}});
            JPopupMenu menu=new JPopupMenu();JMenuItem remove=new JMenuItem(tr("Quitar tabla")),clear=new JMenuItem(tr("Quitar relaciones de esta tabla"));remove.addActionListener(e->removeTable(name));clear.addActionListener(e->{joins.removeIf(j->j.left().equals(alias)||j.right().equals(alias)||j.left().equals(name)||j.right().equals(name));canvas.repaint();generate();});menu.add(remove);menu.add(clear);setComponentPopupMenu(menu);title.setComponentPopupMenu(menu);
        }
        Point anchor(String column){int index=0;for(int i=0;i<columns.getModel().getSize();i++)if(columns.getModel().getElementAt(i).equals(column)){index=i;break;}Point point=SwingUtilities.convertPoint(columns,columns.getWidth(),index*23+12,canvas);return new Point(point.x,Math.min(getY()+getHeight()-10,point.y));}
    }
}
