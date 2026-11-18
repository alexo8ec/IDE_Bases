package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;
import com.nexodb.db.*;
import com.nexodb.model.DatabaseType;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** Visual SELECT builder and schema explorer. SQL is handed to a separate editor for review. */
public final class VisualDatabasePanel extends JPanel {
    private final JdbcSession session;private final String database;private final boolean designer;
    private final Consumer<String> sqlConsumer;private final Consumer<SchemaItem> structureConsumer;private final Runnable schemaChanged;
    private final DefaultListModel<String> names=new DefaultListModel<>();private final JList<String> tables=new JList<>(names);
    private final JPanel canvas=new JPanel(null){protected void paintComponent(Graphics g){super.paintComponent(g);g.setColor(ThemeManager.color(0xF8FAFC,0x252A31));g.fillRect(0,0,getWidth(),getHeight());paintGrid((Graphics2D)g);paintLinks((Graphics2D)g);}};
    private final JCheckBox graphPaper=UiLanguage.text(new JCheckBox("",true),UiLanguage.key("Cuadrícula"));
    private final Map<String,Card> cards=new LinkedHashMap<>();private final Map<String,SchemaMetadataReader.DbObject> metadata=new LinkedHashMap<>();
    private final List<VisualQuery.Join> joins=new ArrayList<>();private final SqlEditorPane preview=new SqlEditorPane();
    private final Map<String,ForeignKeyDefinition> foreignKeys=new LinkedHashMap<>();
    private Card dragCard;private String dragColumn;private Point dragPoint;private ColumnHit dropTarget;
    private record ColumnHit(Card card,String column) {}
    private final DefaultTableModel fields=new DefaultTableModel(new Object[]{"✓",tr("Tabla"),tr("Columna"),tr("Agregado"),"Alias",tr("Orden"),tr("Criterio")},0){public Class<?> getColumnClass(int col){return col==0?Boolean.class:String.class;}public boolean isCellEditable(int r,int c){return c!=1&&c!=2;}};
    private final JTable grid=new JTable(fields);private final JComboBox<String> joinType=new JComboBox<>(new String[]{"INNER","LEFT","RIGHT"});
    private final JCheckBox distinct=new JCheckBox("DISTINCT"),link=new JCheckBox(tr("Relacionar columnas"));private final JSpinner limit=new JSpinner(new SpinnerNumberModel(1000,0,100000,100));
    private final JLabel status=new JLabel(tr("Leyendo objetos…"));private final JTextField filter=new JTextField();private String quote="\"",pendingAlias,pendingColumn,pendingCreatedTable;
    public VisualDatabasePanel(JdbcSession session,String database,boolean designer,Consumer<String> sqlConsumer,Consumer<SchemaItem> structureConsumer){
        this(session,database,designer,sqlConsumer,structureConsumer,()->{});
    }
    public VisualDatabasePanel(JdbcSession session,String database,boolean designer,Consumer<String> sqlConsumer,Consumer<SchemaItem> structureConsumer,Runnable schemaChanged){
        super(new BorderLayout(8,8));this.session=session;this.database=database;this.designer=designer;this.sqlConsumer=sqlConsumer;this.structureConsumer=structureConsumer;this.schemaChanged=schemaChanged;setBorder(new EmptyBorder(10,10,10,10));
        JToolBar bar=new JToolBar();bar.setFloatable(false);bar.add(new JLabel(session.profile().name()+" · "+database+"   "));JButton add=button("Agregar tabla"),remove=button("Quitar tabla"),send=button("Abrir SQL en consulta nueva"),refresh=button("Actualizar");bar.add(add);bar.add(remove);bar.add(refresh);
        if(designer){JButton create=button("Nueva tabla…"),structure=button("Editar estructura…"),save=button("Guardar diagrama…"),load=button("Abrir diagrama…");bar.add(create);bar.add(structure);bar.add(save);bar.add(load);bar.add(UiLanguage.text(link,UiLanguage.key("Crear clave foránea")));create.addActionListener(e->createTable());structure.addActionListener(e->openStructure());save.addActionListener(e->saveLayout());load.addActionListener(e->loadLayout());}
        else{bar.addSeparator();bar.add(UiLanguage.text(link,UiLanguage.key("Relacionar columnas")));bar.add(joinType);bar.add(distinct);bar.add(UiLanguage.label("Límite: "));bar.add(limit);bar.add(send);}add(bar,BorderLayout.NORTH);
        bar.addSeparator();bar.add(graphPaper);graphPaper.addActionListener(e->canvas.repaint());
        JPanel list=new JPanel(new BorderLayout(4,5));UiLanguage.placeholder(filter,"Buscar tablas…");list.add(filter,BorderLayout.NORTH);list.add(new JScrollPane(tables));tables.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){void update(){refreshNames();}public void insertUpdate(javax.swing.event.DocumentEvent e){update();}public void removeUpdate(javax.swing.event.DocumentEvent e){update();}public void changedUpdate(javax.swing.event.DocumentEvent e){}});
        canvas.setPreferredSize(new Dimension(1800,1200));canvas.setBackground(ThemeManager.color(0xF8FAFC,0x252A31));JSplitPane horizontal=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,list,new JScrollPane(canvas));horizontal.setDividerLocation(220);horizontal.setResizeWeight(.15);
        if(designer)add(horizontal,BorderLayout.CENTER);else{
            preview.setEditable(false);UiLanguage.headers(grid);grid.setRowHeight(25);grid.getColumnModel().getColumn(0).setMaxWidth(40);grid.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"","COUNT","SUM","AVG","MIN","MAX"})));grid.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"","ASC","DESC"})));
            JPanel options=new JPanel(new BorderLayout(4,4));options.add(new JScrollPane(grid));JButton delete=button("Quitar columna");delete.addActionListener(e->{int row=grid.getSelectedRow();if(row>=0)fields.removeRow(grid.convertRowIndexToModel(row));});JPanel fieldActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,3,0));fieldActions.add(delete);options.add(fieldActions,BorderLayout.SOUTH);
            JSplitPane lower=new JSplitPane(JSplitPane.VERTICAL_SPLIT,options,new JScrollPane(preview));lower.setResizeWeight(.5);lower.setDividerLocation(130);JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,horizontal,lower);split.setDividerLocation(300);split.setResizeWeight(.55);add(split);
        }
        JPanel foot=new JPanel(new BorderLayout());foot.add(status,BorderLayout.NORTH);foot.add(UiLanguage.label(designer?"Arrastra una columna hija a la columna referenciada. Clic derecho en columnas o líneas para gestionar relaciones. Esc cancela.":"Doble clic en una columna para agregarla. Activa Relacionar columnas y selecciona ambos extremos del JOIN."),BorderLayout.SOUTH);add(foot,BorderLayout.SOUTH);
        add.addActionListener(e->tables.getSelectedValuesList().forEach(this::addTable));remove.addActionListener(e->{for(String name:tables.getSelectedValuesList())removeTable(name);});refresh.addActionListener(e->load());send.addActionListener(e->{if(grid.isEditing())grid.getCellEditor().stopCellEditing();generate();if(!preview.getText().isBlank())sqlConsumer.accept(preview.getText());});
        tables.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)tables.getSelectedValuesList().forEach(VisualDatabasePanel.this::addTable);}});
        fields.addTableModelListener(e->generate());distinct.addActionListener(e->generate());limit.addChangeListener(e->generate());
        link.addActionListener(e->{if(!link.isSelected())cancelRelation();else status.setText(tr("Selecciona primero la columna hija y después la columna referenciada."));});
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"cancel-relation");getActionMap().put("cancel-relation",new AbstractAction(){public void actionPerformed(ActionEvent e){cancelRelation();}});
        if(designer)canvas.addMouseListener(new MouseAdapter(){public void mousePressed(MouseEvent e){canvasMenu(e);}public void mouseReleased(MouseEvent e){canvasMenu(e);}public void mouseClicked(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==2){var key=relationshipAt(e.getPoint());if(key!=null)editRelationship(key);}}});
        load();
    }
    private static JButton button(String text){return UiLanguage.text(new JButton(tr(text)),UiLanguage.key(text));}
    private SchemaItem item(String name){boolean catalog=SchemaMetadataReader.catalogNamespace(session.profile().type());return new SchemaItem(SchemaItem.Kind.TABLE,catalog?database:null,catalog?null:database,name,"");}
    private void refreshNames(){String text=filter.getText().toLowerCase(Locale.ROOT);names.clear();metadata.keySet().stream().filter(n->n.toLowerCase(Locale.ROOT).contains(text)).forEach(names::addElement);}
    private void load(){
        if(!LicenseGate.ensure(this))return;
        status.setText(tr("Leyendo objetos…"));
        new SwingWorker<SchemaMetadataReader.SchemaDef,Void>(){
            protected SchemaMetadataReader.SchemaDef doInBackground()throws Exception{quote=session.connection().getMetaData().getIdentifierQuoteString();return SchemaMetadataReader.read(session,database);}
            protected void done(){try{
                Map<String,Rectangle> positions=new LinkedHashMap<>();Map<String,Color> colors=new HashMap<>();cards.forEach((name,card)->colors.put(name,card.tint));if(designer)cards.forEach((name,card)->positions.put(name,card.getBounds()));
                metadata.clear();get().objects().values().stream().filter(o->o.type().equals("TABLE")).forEach(o->metadata.put(o.name(),o));refreshNames();
                if(designer){for(Card card:cards.values())canvas.remove(card);cards.clear();joins.clear();foreignKeys.values().removeIf(key->!metadata.containsKey(key.table()));cancelRelation();}
                else{for(String name:new ArrayList<>(cards.keySet()))removeTable(name);foreignKeys.clear();cancelRelation();}
                for(var entry:positions.entrySet()){addTable(entry.getKey());Card card=cards.get(entry.getKey());if(card!=null){card.setBounds(entry.getValue());card.setTint(colors.get(entry.getKey()));}}
                String created=pendingCreatedTable;pendingCreatedTable=null;if(created!=null&&metadata.containsKey(created)){addTable(created);tables.setSelectedValue(created,true);status.setText(tr("Tabla %s creada y agregada al diagrama.",created));}else status.setText(tr("%s tablas disponibles",names.size()));growCanvas();
            }catch(Exception error){status.setText(error.getCause()==null?error.getMessage():error.getCause().getMessage());}}
        }.execute();
    }
    private void addTable(String name){
        if(cards.containsKey(name)||!metadata.containsKey(name))return;Card card=new Card(metadata.get(name),SqlTableAliases.next(name,cards.values().stream().map(c->c.alias).toList()));cards.put(name,card);canvas.add(card);int index=cards.size()-1;card.setBounds(40+(index%4)*300,35+(index/4)*300,255,Math.min(260,65+card.columns.getModel().getSize()*23));canvas.revalidate();canvas.repaint();if(designer)loadForeignKeys(card);generate();
    }
    private void removeTable(String name){Card removed=cards.remove(name);if(removed==null)return;canvas.remove(removed);foreignKeys.values().removeIf(k->k.table().equals(name));joins.removeIf(j->j.left().equals(removed.alias)||j.right().equals(removed.alias)||designer&&(j.left().equals(name)||j.right().equals(name)));for(int i=fields.getRowCount()-1;i>=0;i--)if(fields.getValueAt(i,1).equals(removed.alias))fields.removeRow(i);if(Objects.equals(pendingAlias,removed.alias)||dragCard==removed)cancelRelation();canvas.repaint();generate();}
    private void chooseColumn(Card card,String column){
        if(link.isSelected()){
            if(pendingAlias==null){pendingAlias=card.alias;pendingColumn=column;card.columns.repaint();status.setText(tr("Selecciona la columna referenciada o usa el menú de la otra columna."));}
            else if(!pendingAlias.equals(card.alias)||designer&&!pendingColumn.equals(column)){
                Card left=cards.values().stream().filter(c->c.alias.equals(pendingAlias)).findFirst().orElseThrow();int leftIndex=new ArrayList<>(cards.values()).indexOf(left),rightIndex=new ArrayList<>(cards.values()).indexOf(card);
                if(designer){String local=pendingColumn;cancelRelation();showRelationship(left.name,local,card.name,column,null);}
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
        int generation=++card.fkReadGeneration;
        new SwingWorker<List<ForeignKeyDefinition>,Void>(){protected List<ForeignKeyDefinition> doInBackground()throws Exception{
            synchronized(session.connection()){return ForeignKeyDefinition.read(session.connection(),session.profile().type(),database,card.name);}
        }protected void done(){try{if(cards.get(card.name)!=card||generation!=card.fkReadGeneration)return;var read=get();foreignKeys.values().removeIf(k->k.table().equals(card.name));for(var key:read)foreignKeys.put(key.key(),key);joins.clear();for(var key:foreignKeys.values())for(var pair:key.columns())joins.add(new VisualQuery.Join(key.table(),pair.local(),key.parentTable(),pair.referenced(),"FK"));canvas.repaint();}catch(Exception error){status.setText(tr("No se pudieron leer algunas relaciones."));}}}.execute();
    }
    private Card byAlias(String alias){return designer?cards.get(alias):cards.values().stream().filter(c->c.alias.equals(alias)).findFirst().orElse(null);}
    private void paintGrid(Graphics2D g){if(!graphPaper.isSelected())return;Rectangle clip=g.getClipBounds();if(clip==null)clip=new Rectangle(0,0,canvas.getWidth(),canvas.getHeight());for(int x=Math.max(0,clip.x/10*10);x<clip.x+clip.width;x+=10){g.setColor(x%50==0?ThemeManager.color(0xD8E1EB,0x3A4653):ThemeManager.color(0xEAF0F5,0x2E3844));g.drawLine(x,clip.y,x,clip.y+clip.height);}for(int y=Math.max(0,clip.y/10*10);y<clip.y+clip.height;y+=10){g.setColor(y%50==0?ThemeManager.color(0xD8E1EB,0x3A4653):ThemeManager.color(0xEAF0F5,0x2E3844));g.drawLine(clip.x,y,clip.x+clip.width,y);}}
    private void paintLinks(Graphics2D g){
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setStroke(new BasicStroke(2));g.setColor(ThemeManager.color(0x2563EB,0x82AAFF));
        if(designer){for(var key:foreignKeys.values())for(var pair:key.columns()){
            List<Point> path=relationshipPath(key,pair);if(path.isEmpty())continue;
            for(int i=1;i<path.size();i++)g.drawLine(path.get(i-1).x,path.get(i-1).y,path.get(i).x,path.get(i).y);
            Point end=path.get(3),before=path.get(2);int direction=before.x<=end.x?1:-1;g.drawLine(end.x,end.y,end.x-7*direction,end.y-5);g.drawLine(end.x,end.y,end.x-7*direction,end.y+5);
            if(pair==key.columns().get(0))g.drawString("FK",path.get(1).x-7,Math.min(path.get(0).y,path.get(3).y)-9);
        }}else for(var join:joins){Card a=byAlias(join.left()),b=byAlias(join.right());if(a==null||b==null)continue;Point p=a.anchor(join.leftColumn()),q=b.anchor(join.rightColumn());int bend=(p.x+q.x)/2;g.drawLine(p.x,p.y,bend,p.y);g.drawLine(bend,p.y,bend,q.y);g.drawLine(bend,q.y,q.x,q.y);g.drawString(join.type(),bend+3,(p.y+q.y)/2-4);}
        if(dragCard!=null&&dragPoint!=null){Point start=dragCard.anchor(dragColumn);g.setStroke(new BasicStroke(2,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{6,5},0));g.setColor(dropTarget==null?ThemeManager.color(0x64748B,0xCBD5E1):ThemeManager.color(0x059669,0x34D399));g.drawLine(start.x,start.y,dragPoint.x,dragPoint.y);}
    }
    private List<Point> relationshipPath(ForeignKeyDefinition key,ForeignKeyDefinition.ColumnPair pair){
        Card child=cards.get(key.table()),parent=cards.get(key.parentTable());
        if(child==null||parent==null||key.parentDatabase()!=null&&!database.equals(key.parentDatabase()))return List.of();
        boolean right=child==parent||child.getX()+child.getWidth()/2<=parent.getX()+parent.getWidth()/2;
        Point from=child.anchor(pair.local(),right),to=parent.anchor(pair.referenced(),child==parent||!right);
        int bend=child==parent?Math.max(from.x,to.x)+45:(from.x+to.x)/2;
        return List.of(from,new Point(bend,from.y),new Point(bend,to.y),to);
    }
    private ForeignKeyDefinition relationshipAt(Point point){
        List<ForeignKeyDefinition> reversed=new ArrayList<>(foreignKeys.values());Collections.reverse(reversed);
        for(var key:reversed)for(var pair:key.columns()){
            List<Point> path=relationshipPath(key,pair);
            for(int i=1;i<path.size();i++)if(Line2D.ptSegDist(path.get(i-1).x,path.get(i-1).y,path.get(i).x,path.get(i).y,point.x,point.y)<=7)return key;
        }return null;
    }
    private void cancelRelation(){pendingAlias=null;pendingColumn=null;link.setSelected(false);dragCard=null;dragColumn=null;dragPoint=null;dropTarget=null;canvas.setCursor(Cursor.getDefaultCursor());cards.values().forEach(c->{c.columns.setCursor(Cursor.getDefaultCursor());c.columns.repaint();});canvas.repaint();}
    private ColumnHit columnAt(Point point){
        for(Card card:cards.values()){
            Point local=SwingUtilities.convertPoint(canvas,point,card.columns);int row=card.columns.locationToIndex(local);
            if(row>=0&&card.columns.getVisibleRect().contains(local)&&card.columns.getCellBounds(row,row).contains(local))return new ColumnHit(card,card.columns.getModel().getElementAt(row));
        }return null;
    }
    private JMenuItem menuItem(String key,Runnable action){JMenuItem item=UiLanguage.text(new JMenuItem(tr(key)),UiLanguage.key(key));item.addActionListener(e->action.run());return item;}
    private void canvasMenu(MouseEvent e){
        if(!e.isPopupTrigger())return;ForeignKeyDefinition key=relationshipAt(e.getPoint());JPopupMenu menu=new JPopupMenu();
        if(key!=null){menu.add(new JMenuItem(Objects.toString(key.name(),"FK"))).setEnabled(false);menu.add(menuItem("Modificar relación…",()->editRelationship(key)));menu.add(menuItem("Eliminar relación…",()->deleteRelationship(key)));menu.addSeparator();}
        menu.add(menuItem("Agregar tabla",()->tables.getSelectedValuesList().forEach(this::addTable)));menu.add(menuItem("Nueva tabla…",this::createTable));menu.add(menuItem("Actualizar",this::load));menu.add(menuItem("Cancelar relación",this::cancelRelation));menu.show(canvas,e.getX(),e.getY());
    }
    private void editRelationship(ForeignKeyDefinition key){
        if(key.parentDatabase()!=null&&!database.equals(key.parentDatabase())||!metadata.containsKey(key.parentTable())){structureConsumer.accept(item(key.table()));return;}
        var pair=key.columns().get(0);showRelationship(key.table(),pair.local(),key.parentTable(),pair.referenced(),key);
    }
    private void showRelationship(String child,String local,String parent,String referenced,ForeignKeyDefinition previous){
        cancelRelation();ForeignKeyEditor editor=new ForeignKeyEditor(database,child,local,parent,referenced,metadata,previous,session.profile().type(),quote);
        showRelationshipDialog(editor,previous==null?"Nueva clave foránea":"Modificar relación",()->{editor.stopEditing();ForeignKeyDefinition next=editor.definition();String script=previous==null?next.createSql(quote):next.replaceSql(previous,session.profile().type(),quote);return new DdlChange(script,()->{if(previous==null)ForeignKeyDefinition.apply(session,script);else ForeignKeyDefinition.replace(session,previous,next,quote);});},child);
    }
    private void deleteRelationship(ForeignKeyDefinition key){
        String script=key.dropSql(session.profile().type(),quote);SqlEditorPane sql=new SqlEditorPane();sql.setEditable(false);sql.restoreText(script);JPanel panel=new JPanel(new BorderLayout(8,8));panel.setBorder(new EmptyBorder(12,12,12,12));panel.add(new JLabel(key.table()+" → "+key.parentTable()+" · "+key.name()),BorderLayout.NORTH);panel.add(new JScrollPane(sql));panel.add(UiLanguage.label("Se eliminará únicamente la restricción de clave foránea. Las tablas y los datos se conservarán."),BorderLayout.SOUTH);panel.setPreferredSize(new Dimension(760,300));showRelationshipDialog(panel,"Eliminar relación",()->new DdlChange(script,()->ForeignKeyDefinition.apply(session,script)),key.table());
    }
    @FunctionalInterface private interface DdlAction{void run()throws Exception;}
    private record DdlChange(String sql,DdlAction apply){}
    private void showRelationshipDialog(JComponent form,String title,Supplier<DdlChange> change,String child){
        JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(this),tr(title),Dialog.ModalityType.APPLICATION_MODAL);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.add(form);
        JButton generate=button("Abrir SQL en consulta nueva"),apply=button("Aplicar relación…"),close=button("Cerrar");JLabel state=new JLabel(tr("Revisa el SQL antes de aplicar el cambio."));JPanel footer=new JPanel(new BorderLayout(6,6));footer.setBorder(new EmptyBorder(6,12,12,12));footer.add(state);JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(generate);buttons.add(apply);buttons.add(close);footer.add(buttons,BorderLayout.SOUTH);dialog.add(footer,BorderLayout.SOUTH);
        apply.setEnabled(!session.profile().readOnly());if(session.profile().readOnly())state.setText(tr("La conexión es de solo lectura. Puedes generar el SQL para revisarlo."));
        generate.addActionListener(e->{try{String script=change.get().sql();dialog.dispose();sqlConsumer.accept(script);}catch(Exception error){JOptionPane.showMessageDialog(dialog,error.getMessage(),tr(title),JOptionPane.WARNING_MESSAGE);}});close.addActionListener(e->dialog.dispose());
        apply.addActionListener(e->{try{
            DdlChange approved=change.get();String script=approved.sql();if(!LicenseGate.ensure(dialog)||!Confirmations.ask(dialog,title,database+" · "+child+"\n\n"+script+"\n"+tr("¿Aplicar este cambio en la base de datos?")))return;
            apply.setEnabled(false);generate.setEnabled(false);close.setEnabled(false);dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);state.setText(tr("Aplicando cambio de estructura…"));
            new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{approved.apply().run();return null;}protected void done(){try{get();dialog.dispose();Card card=cards.get(child);if(card!=null)loadForeignKeys(card);status.setText(tr("Relaciones actualizadas."));}catch(Exception error){apply.setEnabled(!session.profile().readOnly());generate.setEnabled(true);close.setEnabled(true);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);state.setText(tr("No se aplicó el cambio"));Throwable shown=error.getCause()==null?error:error.getCause();String message=shown.getMessage();if(shown.getCause()!=null&&shown.getCause().getMessage()!=null&&!shown.getCause().getMessage().equals(message))message+="\n\n"+tr("Detalle del servidor:")+" "+shown.getCause().getMessage();JOptionPane.showMessageDialog(dialog,message,tr(title),JOptionPane.ERROR_MESSAGE);}}}.execute();
        }catch(Exception error){JOptionPane.showMessageDialog(dialog,error.getMessage(),tr(title),JOptionPane.WARNING_MESSAGE);}});
        dialog.pack();dialog.setLocationRelativeTo(this);dialog.setVisible(true);
    }
    private void columnMenu(Card card,String column,Component source,int x,int y){
        tables.setSelectedValue(card.name,true);JPopupMenu menu=new JPopupMenu();
        menu.add(new JMenuItem(card.name+(column==null?"":" · "+column))).setEnabled(false);
        menu.add(menuItem("Crear clave foránea…",()->showRelationship(card.name,column,null,null,null)));
        if(column!=null){
            menu.add(menuItem("Usar como columna hija",()->{cancelRelation();pendingAlias=card.alias;pendingColumn=column;link.setSelected(true);card.columns.repaint();status.setText(tr("Selecciona la columna referenciada o usa el menú de la otra columna."));}));
            JMenuItem target=menuItem("Referenciar esta columna",()->{Card child=byAlias(pendingAlias);String local=pendingColumn;if(child!=null)showRelationship(child.name,local,card.name,column,null);});target.setEnabled(pendingAlias!=null&&(!pendingAlias.equals(card.alias)||!Objects.equals(pendingColumn,column)));menu.add(target);
        }
        JMenu relations=UiLanguage.text(new JMenu(tr("Relaciones de esta tabla")),UiLanguage.key("Relaciones de esta tabla"));
        for(var key:foreignKeys.values())if(key.table().equals(card.name)||key.parentTable().equals(card.name)&&Objects.equals(database,key.parentDatabase())){
            JMenu entry=new JMenu(Objects.toString(key.name(),"FK")+" · "+key.table()+" → "+key.parentTable());entry.add(menuItem("Modificar relación…",()->editRelationship(key)));entry.add(menuItem("Eliminar relación…",()->deleteRelationship(key)));relations.add(entry);
        }
        relations.setEnabled(relations.getItemCount()>0);menu.add(relations);menu.addSeparator();menu.add(colorMenu(card));menu.add(menuItem("Editar estructura…",()->structureConsumer.accept(item(card.name))));menu.add(menuItem("Quitar tabla del diagrama",()->removeTable(card.name)));menu.add(menuItem("Cancelar relación",this::cancelRelation));UiDesign.enhance(menu);menu.show(source,x,y);
    }
    private JMenu colorMenu(Card card){JMenu colors=UiLanguage.text(new JMenu(),UiLanguage.key("Color de la tabla"));String[] names={"Azul","Verde","Naranja","Violeta","Rosa","Turquesa"};for(int i=0;i<PALETTE.length;i++){Color color=new Color(PALETTE[i]);JMenuItem option=menuItem(names[i],()->card.setTint(color));option.setIcon(new javax.swing.Icon(){public int getIconWidth(){return 16;}public int getIconHeight(){return 16;}public void paintIcon(Component c,Graphics g,int x,int y){g.setColor(color);g.fillRoundRect(x+1,y+1,14,14,5,5);}});colors.add(option);}colors.addSeparator();colors.add(menuItem("Personalizar color…",()->{Color selected=JColorChooser.showDialog(this,tr("Color de la tabla"),card.tint);if(selected!=null)card.setTint(selected);}));return colors;}
    private void joinColumns(Card left,String local,Card right,String referenced){if(left==right)return;pendingAlias=left.alias;pendingColumn=local;link.setSelected(true);chooseColumn(right,referenced);}
    private static final int[] PALETTE={0x2563EB,0x16856B,0xD38A24,0x8055C5,0xC14F85,0x138FA8};
    private void openStructure(){String name=tables.getSelectedValue();if(name==null&&!cards.isEmpty())name=cards.keySet().iterator().next();if(name!=null)structureConsumer.accept(item(name));}
    private void createTable(){new NewTableDialog(SwingUtilities.getWindowAncestor(this)).setVisible(true);}
    private final class NewTableDialog extends JDialog {
        private static final int NAME=0,TYPE=1,PRIMARY=2,NULLABLE=3,AUTO=4,UNSIGNED=5,DEFAULT=6,COLLATION=7,ZEROFILL=8,BINARY=9,UPDATE=10,CHARSET=11,COMMENT=12,GENERATED=13,EXPRESSION=14,INVISIBLE=15,VALUES=16;
        private final CreateTableDefinition.Features features=CreateTableDefinition.features(session.profile().type());
        private final JTextField tableName=new JTextField("nueva_tabla"),defaultValue=new JTextField(),charset=new JTextField(),comment=new JTextField(),expression=new JTextField();
        private final JCheckBox primary=new JCheckBox("PRIMARY KEY"),notNull=new JCheckBox("NOT NULL"),autoIncrement=new JCheckBox(tr("Auto incremento")),unsigned=new JCheckBox("UNSIGNED"),zerofill=new JCheckBox("ZEROFILL"),binary=new JCheckBox(tr("Binario")),updateTimestamp=new JCheckBox("ON UPDATE CURRENT_TIMESTAMP"),invisible=new JCheckBox(tr("Invisible"));
        private final JComboBox<String> collation=new JComboBox<>(new String[]{""}),generated=new JComboBox<>(new String[]{"","VIRTUAL","STORED"});
        private final JLabel state=new JLabel(" ");private final JButton create=button("Crear tabla"),cancel=button("Cancelar"),add=button("Agregar columna"),remove=button("Quitar columna"),enumValues=button("Editar valores ENUM/SET…");
        private final SqlEditorPane sqlPreview=new SqlEditorPane();private boolean loadingProperties,busy;
        private final DefaultTableModel columns=new DefaultTableModel(new Object[]{tr("Nombre"),tr("Tipo SQL"),"PRIMARY KEY",tr("Permitir NULL"),tr("Auto incremento"),"UNSIGNED",tr("Predeterminado"),"Collation","ZEROFILL",tr("Binario"),"ON UPDATE","Charset",tr("Comentario"),tr("Generada"),tr("Expresión"),tr("Invisible"),tr("Valores")},0){
            public Class<?> getColumnClass(int c){return Set.of(PRIMARY,NULLABLE,AUTO,UNSIGNED,ZEROFILL,BINARY,UPDATE,INVISIBLE).contains(c)?Boolean.class:String.class;}
            public boolean isCellEditable(int row,int col){return !busy&&(col!=AUTO||features.autoIncrement())&&(col!=UNSIGNED||features.unsigned())&&(col!=COLLATION||features.collation());}
        };
        private final JTable columnGrid=new JTable(columns);
        NewTableDialog(Window owner){
            super(owner,tr("Nueva tabla"),ModalityType.APPLICATION_MODAL);setDefaultCloseOperation(DISPOSE_ON_CLOSE);setLayout(new BorderLayout(8,8));((JComponent)getContentPane()).setBorder(new EmptyBorder(10,10,10,10));
            JPanel heading=new JPanel(new BorderLayout(8,0));JPanel identity=new JPanel(new GridLayout(1,4,8,0));identity.add(UiLanguage.label("Nombre de la tabla"));identity.add(tableName);identity.add(UiLanguage.label("Motor"));JTextField engine=new JTextField(session.profile().type().displayName());engine.setEditable(false);identity.add(engine);heading.add(identity);add(heading,BorderLayout.NORTH);
            columns.addRow(row("id",features.types().get(0),true,false));UiLanguage.headers(columnGrid);columnGrid.setRowHeight(27);columnGrid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={155,170,90,100,115,90,145,175};for(int i=0;i<widths.length;i++)columnGrid.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            JComboBox<String> types=new JComboBox<>(features.types().toArray(String[]::new));types.setEditable(true);columnGrid.getColumnModel().getColumn(TYPE).setCellEditor(new DefaultCellEditor(types));JComboBox<String> collations=new JComboBox<>(new String[]{""});collations.setEditable(true);columnGrid.getColumnModel().getColumn(COLLATION).setCellEditor(new DefaultCellEditor(collations));
            while(columnGrid.getColumnModel().getColumnCount()>8)columnGrid.removeColumn(columnGrid.getColumnModel().getColumn(8));
            JPanel left=new JPanel(new BorderLayout(5,5));JToolBar actions=new JToolBar();actions.setFloatable(false);actions.add(add);actions.add(remove);left.add(actions,BorderLayout.NORTH);left.add(new JScrollPane(columnGrid));
            JPanel properties=propertiesPanel();JSplitPane columnsSplit=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,new JScrollPane(properties));columnsSplit.setResizeWeight(.72);columnsSplit.setDividerLocation(760);
            sqlPreview.setEditable(false);JPanel previewPanel=new JPanel(new BorderLayout(4,4));previewPanel.setBorder(UiLanguage.border("Vista previa SQL"));previewPanel.add(new JScrollPane(sqlPreview));JSplitPane center=new JSplitPane(JSplitPane.VERTICAL_SPLIT,columnsSplit,previewPanel);center.setResizeWeight(.68);center.setDividerLocation(410);add(center);
            JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(create);buttons.add(cancel);JPanel footer=new JPanel(new BorderLayout());footer.add(state);footer.add(buttons,BorderLayout.EAST);add(footer,BorderLayout.SOUTH);
            add.addActionListener(e->{stopEditing();String type=features.types().contains("VARCHAR(255)")?"VARCHAR(255)":features.types().get(Math.min(1,features.types().size()-1));columns.addRow(row("columna",type,false,true));int row=columns.getRowCount()-1;columnGrid.setRowSelectionInterval(row,row);columnGrid.scrollRectToVisible(columnGrid.getCellRect(row,0,true));});
            remove.addActionListener(e->{stopEditing();int row=columnGrid.getSelectedRow();if(row>=0)columns.removeRow(columnGrid.convertRowIndexToModel(row));if(columns.getRowCount()==0)columns.addRow(row("id",features.types().get(0),true,false));columnGrid.setRowSelectionInterval(Math.min(Math.max(0,row),columns.getRowCount()-1),Math.min(Math.max(0,row),columns.getRowCount()-1));});
            cancel.addActionListener(e->dispose());create.addActionListener(e->apply());columnGrid.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())loadProperties();});columns.addTableModelListener(e->{if(!loadingProperties){if(e.getColumn()==TYPE&&e.getFirstRow()==selected())loadProperties();preview();}});tableName.getDocument().addDocumentListener(listener(this::preview));
            enumValues.addActionListener(e->editEnumValues());installPropertyListeners();columnGrid.setRowSelectionInterval(0,0);loadProperties();loadCollations(collations);preview();setPreferredSize(new Dimension(1180,680));pack();setLocationRelativeTo(VisualDatabasePanel.this);
        }
        private Object[] row(String name,String type,boolean key,boolean nullable){return new Object[]{name,type,key,nullable,false,false,"","",false,false,false,"","","","",false,List.of()};}
        private JPanel propertiesPanel(){
            JPanel panel=new JPanel(new GridBagLayout());panel.setBorder(UiLanguage.border("Propiedades de la columna"));GridBagConstraints g=new GridBagConstraints();g.insets=new Insets(4,7,4,7);g.anchor=GridBagConstraints.WEST;g.fill=GridBagConstraints.HORIZONTAL;g.weightx=1;int row=0;
            for(JCheckBox option:List.of(primary,notNull,unsigned,zerofill,binary,autoIncrement,updateTimestamp,invisible)){g.gridy=row++;g.gridx=0;g.gridwidth=2;panel.add(option,g);}
            JComponent[] inputs={defaultValue,charset,collation,generated,enumValues,expression,comment};String[] labels={"Predeterminado SQL","Charset","Collation","Columna generada","Valores ENUM / SET","Expresión","Comentario"};for(int i=0;i<inputs.length;i++){g.gridy=row++;g.gridwidth=1;g.gridx=0;g.weightx=0;panel.add(UiLanguage.label(labels[i]),g);g.gridx=1;g.weightx=1;panel.add(inputs[i],g);}g.gridy=row;g.weighty=1;panel.add(new JLabel(),g);
            boolean mysql=session.profile().type()==DatabaseType.MYSQL||session.profile().type()==DatabaseType.MARIADB;unsigned.setEnabled(features.unsigned());for(JComponent c:List.of(zerofill,binary,updateTimestamp,charset,generated,expression,invisible,comment))c.setEnabled(mysql);autoIncrement.setEnabled(features.autoIncrement());collation.setEditable(true);collation.setEnabled(features.collation());return panel;
        }
        private void installPropertyListeners(){
            for(AbstractButton input:List.of(primary,notNull,autoIncrement,unsigned,zerofill,binary,updateTimestamp,invisible))input.addActionListener(e->storeProperties());for(JComboBox<?> input:List.of(collation,generated))input.addActionListener(e->storeProperties());
            for(javax.swing.text.JTextComponent input:List.of(defaultValue,charset,comment,expression))input.getDocument().addDocumentListener(listener(this::storeProperties));
        }
        private javax.swing.event.DocumentListener listener(Runnable action){return new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){action.run();}public void removeUpdate(javax.swing.event.DocumentEvent e){action.run();}public void changedUpdate(javax.swing.event.DocumentEvent e){action.run();}};}
        private int selected(){int row=columnGrid.getSelectedRow();return row<0?-1:columnGrid.convertRowIndexToModel(row);}
        private void loadProperties(){int row=selected();if(row<0)return;loadingProperties=true;try{primary.setSelected(check(row,PRIMARY));notNull.setSelected(!check(row,NULLABLE));autoIncrement.setSelected(check(row,AUTO));unsigned.setSelected(check(row,UNSIGNED));zerofill.setSelected(check(row,ZEROFILL));binary.setSelected(check(row,BINARY));updateTimestamp.setSelected(check(row,UPDATE));invisible.setSelected(check(row,INVISIBLE));defaultValue.setText(text(row,DEFAULT));charset.setText(text(row,CHARSET));collation.setSelectedItem(text(row,COLLATION));comment.setText(text(row,COMMENT));generated.setSelectedItem(text(row,GENERATED));expression.setText(text(row,EXPRESSION));}finally{loadingProperties=false;}enumValues.setEnabled(isEnum(row)&&!busy);}
        private void storeProperties(){int row=selected();if(loadingProperties||row<0)return;loadingProperties=true;try{columns.setValueAt(primary.isSelected(),row,PRIMARY);columns.setValueAt(!notNull.isSelected(),row,NULLABLE);columns.setValueAt(autoIncrement.isSelected(),row,AUTO);columns.setValueAt(unsigned.isSelected(),row,UNSIGNED);columns.setValueAt(defaultValue.getText(),row,DEFAULT);columns.setValueAt(Objects.toString(collation.getSelectedItem(),""),row,COLLATION);columns.setValueAt(zerofill.isSelected(),row,ZEROFILL);columns.setValueAt(binary.isSelected(),row,BINARY);columns.setValueAt(updateTimestamp.isSelected(),row,UPDATE);columns.setValueAt(charset.getText(),row,CHARSET);columns.setValueAt(comment.getText(),row,COMMENT);columns.setValueAt(Objects.toString(generated.getSelectedItem(),""),row,GENERATED);columns.setValueAt(expression.getText(),row,EXPRESSION);columns.setValueAt(invisible.isSelected(),row,INVISIBLE);}finally{loadingProperties=false;}preview();}
        private boolean check(int row,int col){return Boolean.TRUE.equals(columns.getValueAt(row,col));}private String text(int row,int col){return Objects.toString(columns.getValueAt(row,col),"").trim();}
        private List<CreateTableDefinition.Column> definition(){List<CreateTableDefinition.Column> result=new ArrayList<>();for(int row=0;row<columns.getRowCount();row++)result.add(new CreateTableDefinition.Column(text(row,NAME),text(row,TYPE),check(row,PRIMARY),check(row,NULLABLE),check(row,AUTO),check(row,UNSIGNED),text(row,DEFAULT),text(row,COLLATION),check(row,ZEROFILL),check(row,BINARY),check(row,UPDATE),text(row,CHARSET),Objects.toString(columns.getValueAt(row,COMMENT),""),text(row,GENERATED),text(row,EXPRESSION),check(row,INVISIBLE),values(row)));return result;}
        private boolean isEnum(int row){String type=text(row,TYPE).toUpperCase(Locale.ROOT);return type.equals("ENUM")||type.equals("SET");}
        private List<String> values(int row){Object raw=columns.getValueAt(row,VALUES);if(!(raw instanceof List<?> list))return List.of();return list.stream().map(String::valueOf).toList();}
        private void editEnumValues(){stopEditing();int row=selected();if(row<0||!isEnum(row))return;DefaultListModel<String> model=new DefaultListModel<>();values(row).forEach(model::addElement);JList<String> list=new JList<>(model);JTextField input=new JTextField();JButton addValue=button("Agregar"),replace=button("Reemplazar"),delete=button("Eliminar"),up=button("Subir"),down=button("Bajar");
            addValue.addActionListener(e->{model.addElement(input.getText());input.setText("");});replace.addActionListener(e->{int index=list.getSelectedIndex();if(index>=0)model.set(index,input.getText());});delete.addActionListener(e->{int index=list.getSelectedIndex();if(index>=0)model.remove(index);});up.addActionListener(e->moveValue(model,list,-1));down.addActionListener(e->moveValue(model,list,1));list.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&list.getSelectedValue()!=null)input.setText(list.getSelectedValue());});
            JPanel editor=new JPanel(new BorderLayout(6,6));editor.add(new JScrollPane(list));JPanel entry=new JPanel(new BorderLayout(5,5));entry.add(UiLanguage.label("Valor"),BorderLayout.WEST);entry.add(input);JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));actions.add(addValue);actions.add(replace);actions.add(delete);actions.add(up);actions.add(down);JPanel south=new JPanel(new BorderLayout(4,4));south.add(entry,BorderLayout.NORTH);south.add(actions);editor.add(south,BorderLayout.SOUTH);editor.setPreferredSize(new Dimension(480,430));
            if(JOptionPane.showConfirmDialog(this,editor,tr("Valores de ")+text(row,TYPE).toUpperCase(Locale.ROOT),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION){List<String> saved=new ArrayList<>();for(int i=0;i<model.size();i++)saved.add(model.get(i));columns.setValueAt(List.copyOf(saved),row,VALUES);preview();}
        }
        private void moveValue(DefaultListModel<String> model,JList<String> list,int delta){int row=list.getSelectedIndex(),next=row+delta;if(row<0||next<0||next>=model.size())return;String value=model.remove(row);model.add(next,value);list.setSelectedIndex(next);}
        private String script(){return CreateTableDefinition.createSql(session.profile().type(),quote,database,tableName.getText(),definition());}
        private void preview(){if(busy)return;try{sqlPreview.restoreText(script());state.setText(tr("Revisa la vista previa y pulsa Crear tabla."));create.setEnabled(!session.profile().readOnly());}catch(Exception error){sqlPreview.restoreText("");state.setText(error.getMessage());create.setEnabled(false);}}
        private void stopEditing(){if(columnGrid.isEditing())columnGrid.getCellEditor().stopCellEditing();storeProperties();}
        private void setBusy(boolean value){busy=value;create.setEnabled(!value);cancel.setEnabled(!value);add.setEnabled(!value);remove.setEnabled(!value);columnGrid.setEnabled(!value);enumValues.setEnabled(!value&&selected()>=0&&isEnum(selected()));}
        private void apply(){stopEditing();final String script,created=tableName.getText().trim();try{script=script();}catch(Exception error){state.setText(error.getMessage());JOptionPane.showMessageDialog(this,error.getMessage(),tr("No se pudo crear la tabla"),JOptionPane.WARNING_MESSAGE);return;}setBusy(true);state.setText(tr("Creando tabla…"));
            new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{ForeignKeyDefinition.apply(session,script);return null;}protected void done(){try{get();pendingCreatedTable=created;filter.setText("");load();schemaChanged.run();dispose();}catch(Exception error){setBusy(false);Throwable root=error;while(root.getCause()!=null)root=root.getCause();preview();state.setText(tr("No se creó la tabla. El formulario conserva todos los datos."));JOptionPane.showMessageDialog(NewTableDialog.this,root.getMessage(),tr("No se pudo crear la tabla"),JOptionPane.ERROR_MESSAGE);}}}.execute();
        }
        private void loadCollations(JComboBox<String> gridEditor){new SwingWorker<List<String>,Void>(){protected List<String> doInBackground(){return CreateTableDefinition.collations(session);}protected void done(){try{List<String> values=get();if(values.isEmpty())return;DefaultComboBoxModel<String> model=new DefaultComboBoxModel<>();model.addElement("");values.forEach(model::addElement);collation.setModel(model);DefaultComboBoxModel<String> gridModel=new DefaultComboBoxModel<>();gridModel.addElement("");values.forEach(gridModel::addElement);gridEditor.setModel(gridModel);}catch(Exception ignored){}}}.execute();}
    }
    private Properties layoutState(){Properties p=new Properties();p.setProperty("database",database);p.setProperty("grid",Boolean.toString(graphPaper.isSelected()));int i=0;for(Card card:cards.values()){p.setProperty(i+".table",card.name);p.setProperty(i+".x",""+card.getX());p.setProperty(i+".y",""+card.getY());p.setProperty(i+".width",""+card.getWidth());p.setProperty(i+".height",""+card.getHeight());p.setProperty(i+".color",String.format("%06x",card.tint.getRGB()&0xffffff));i++;}p.setProperty("count",""+i);return p;}
    private void restoreLayout(Properties p){if(!database.equals(p.getProperty("database")))throw new IllegalArgumentException(tr("El diagrama corresponde a otra base de datos."));int count=Integer.parseInt(p.getProperty("count","0"));if(count<0||count>1000)throw new IllegalArgumentException("Invalid diagram");graphPaper.setSelected(Boolean.parseBoolean(p.getProperty("grid","true")));for(String name:new ArrayList<>(cards.keySet()))removeTable(name);for(int i=0;i<count;i++){String name=p.getProperty(i+".table");addTable(name);Card card=cards.get(name);if(card!=null)card.setBounds(clamp(Integer.parseInt(p.getProperty(i+".x","0")),0,20000),clamp(Integer.parseInt(p.getProperty(i+".y","0")),0,20000),clamp(Integer.parseInt(p.getProperty(i+".width",""+card.getWidth())),190,3000),clamp(Integer.parseInt(p.getProperty(i+".height",""+card.getHeight())),85,4000));if(card!=null){String color=p.getProperty(i+".color");if(color!=null&&color.matches("[0-9a-fA-F]{6}"))card.setTint(new Color(Integer.parseInt(color,16)));}}growCanvas();canvas.repaint();}
    private void saveLayout(){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("diagrama.nexodiagram"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{com.nexodb.util.AtomicProperties.write(chooser.getSelectedFile().toPath(),layoutState());status.setText(tr("Diagrama guardado."));}catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage());}}
    private void loadLayout(){JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{restoreLayout(com.nexodb.util.AtomicProperties.read(chooser.getSelectedFile().toPath()));}catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage());}}
    private static int clamp(int value,int minimum,int maximum){return Math.max(minimum,Math.min(maximum,value));}
    private void growCanvas(){int width=1800,height=1200;for(Card card:cards.values()){width=Math.max(width,card.getX()+card.getWidth()+80);height=Math.max(height,card.getY()+card.getHeight()+80);}canvas.setPreferredSize(new Dimension(width,height));canvas.revalidate();canvas.repaint();}
    private final class Card extends JPanel {
        final String name,alias;final JList<String> columns;int fkReadGeneration;Color tint;
        Card(SchemaMetadataReader.DbObject object,String alias){super(new BorderLayout(0,4));this.name=object.name();this.alias=alias;tint=new Color(PALETTE[Math.floorMod(name.hashCode(),PALETTE.length)]);setTint(tint);JLabel title=new JLabel(name+(designer?"":"  ["+alias+"]"),IconFactory.of(IconFactory.Kind.TABLE,16),SwingConstants.LEFT);title.setFont(title.getFont().deriveFont(Font.BOLD));JPanel header=new JPanel(new BorderLayout(4,0)){public Color getBackground(){Color base=ThemeManager.color(0xFFFFFF,0x27313D);return new Color((base.getRed()*3+tint.getRed())/4,(base.getGreen()*3+tint.getGreen())/4,(base.getBlue()*3+tint.getBlue())/4);}};header.setBorder(new EmptyBorder(3,5,3,2));header.add(title);JButton close=new JButton("×");close.setName("close-diagram-table");close.setFocusable(false);close.setMargin(new Insets(0,4,0,4));close.putClientProperty("JButton.buttonType","toolBarButton");UiLanguage.tooltip(close,UiLanguage.key("Quitar del diagrama. La tabla y sus datos se conservan."));close.addActionListener(e->removeTable(name));header.add(close,BorderLayout.EAST);add(header,BorderLayout.NORTH);columns=new JList<>(object.columns().values().stream().map(SchemaMetadataReader.ColumnDef::name).toArray(String[]::new));columns.setFixedCellHeight(23);add(new JScrollPane(columns));installResize();
            MouseAdapter drag=new MouseAdapter(){Point start;public void mousePressed(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e)){start=e.getPoint();tables.setSelectedValue(name,true);}if(designer&&e.isPopupTrigger())columnMenu(Card.this,null,title,e.getX(),e.getY());}public void mouseReleased(MouseEvent e){start=null;if(designer&&e.isPopupTrigger())columnMenu(Card.this,null,title,e.getX(),e.getY());}public void mouseDragged(MouseEvent e){if(start==null)return;setLocation(Math.max(0,getX()+e.getX()-start.x),Math.max(0,getY()+e.getY()-start.y));canvas.repaint();}public void mouseClicked(MouseEvent e){if(designer&&SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==2)structureConsumer.accept(item(name));}};title.addMouseListener(drag);title.addMouseMotionListener(drag);
            MouseAdapter columnsMouse=new MouseAdapter(){Point start;String sourceColumn;boolean dragged;
                private int row(MouseEvent e){int index=columns.locationToIndex(e.getPoint());return index>=0&&columns.getCellBounds(index,index).contains(e.getPoint())?index:-1;}
                private void popup(MouseEvent e){if(!designer||!e.isPopupTrigger())return;int row=row(e);if(row>=0){columns.setSelectedIndex(row);columnMenu(Card.this,columns.getModel().getElementAt(row),columns,e.getX(),e.getY());}}
                public void mousePressed(MouseEvent e){popup(e);if(!SwingUtilities.isLeftMouseButton(e))return;dragged=false;int row=row(e);start=e.getPoint();sourceColumn=row<0?null:columns.getModel().getElementAt(row);}
                public void mouseDragged(MouseEvent e){if(start==null||sourceColumn==null||start.distance(e.getPoint())<5&&!dragged)return;dragged=true;dragCard=Card.this;dragColumn=sourceColumn;dragPoint=SwingUtilities.convertPoint(columns,e.getPoint(),canvas);dropTarget=columnAt(dragPoint);if(dropTarget!=null&&dropTarget.card()==Card.this&&(!designer||dropTarget.column().equals(sourceColumn)))dropTarget=null;columns.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));cards.values().forEach(c->c.columns.repaint());canvas.repaint();}
                public void mouseReleased(MouseEvent e){popup(e);start=null;sourceColumn=null;if(dragCard!=Card.this||!SwingUtilities.isLeftMouseButton(e))return;ColumnHit target=dropTarget;String local=dragColumn;cancelRelation();columns.setCursor(Cursor.getDefaultCursor());if(target!=null)SwingUtilities.invokeLater(()->{if(designer)showRelationship(name,local,target.card().name,target.column(),null);else joinColumns(Card.this,local,target.card(),target.column());});else status.setText(tr("Suelta la columna sobre una columna referenciada. No se ha modificado la base."));}
                public void mouseClicked(MouseEvent e){if(dragged||!SwingUtilities.isLeftMouseButton(e))return;int row=row(e);if(row>=0&&(link.isSelected()||e.getClickCount()==2))chooseColumn(Card.this,columns.getModel().getElementAt(row));}
            };columns.addMouseListener(columnsMouse);columns.addMouseMotionListener(columnsMouse);
            if(designer){columns.setToolTipText(tr("Arrastra desde la columna hija hasta la columna referenciada, o usa clic derecho."));columns.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){JLabel label=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);if(dropTarget!=null&&dropTarget.card()==Card.this&&Objects.equals(value,dropTarget.column())||Objects.equals(pendingAlias,alias)&&Objects.equals(value,pendingColumn)){label.setBackground(ThemeManager.color(0xD1FAE5,0x065F46));label.setForeground(ThemeManager.color(0x064E3B,0xECFDF5));}return label;}});((JScrollPane)columns.getParent().getParent()).getViewport().addChangeListener(e->canvas.repaint());}
            else{JPopupMenu menu=new JPopupMenu();menu.add(colorMenu(this));menu.add(menuItem("Quitar tabla",()->removeTable(name)));menu.add(menuItem("Quitar relaciones de esta tabla",()->{joins.removeIf(j->j.left().equals(alias)||j.right().equals(alias));canvas.repaint();generate();}));setComponentPopupMenu(menu);title.setComponentPopupMenu(menu);}
        }
        void setTint(Color color){tint=color;setBorder(new CompoundBorder(new LineBorder(color,2),new EmptyBorder(5,5,5,5)));repaint();}
        Point anchor(String column){return anchor(column,true);}
        private int edge(Point p){int edge=0;if(p.x<7)edge|=1;else if(p.x>=getWidth()-7)edge|=2;if(p.y<7)edge|=4;else if(p.y>=getHeight()-7)edge|=8;return edge;}
        private int resizeCursor(int edges){return switch(edges){case 1,2->Cursor.E_RESIZE_CURSOR;case 4,8->Cursor.N_RESIZE_CURSOR;case 5,10->Cursor.NW_RESIZE_CURSOR;case 6,9->Cursor.NE_RESIZE_CURSOR;default->Cursor.DEFAULT_CURSOR;};}
        private void installResize(){MouseAdapter resize=new MouseAdapter(){int edges;Point start;Rectangle before;
            public void mouseMoved(MouseEvent e){setCursor(Cursor.getPredefinedCursor(resizeCursor(edge(e.getPoint()))));}
            public void mouseExited(MouseEvent e){if(start==null)setCursor(Cursor.getDefaultCursor());}
            public void mousePressed(MouseEvent e){if(!SwingUtilities.isLeftMouseButton(e))return;edges=edge(e.getPoint());if(edges!=0){start=SwingUtilities.convertPoint(Card.this,e.getPoint(),canvas);before=getBounds();}}
            public void mouseDragged(MouseEvent e){if(start==null)return;Point current=SwingUtilities.convertPoint(Card.this,e.getPoint(),canvas);int dx=current.x-start.x,dy=current.y-start.y;Rectangle bounds=new Rectangle(before);if((edges&1)!=0){int width=clamp(before.width-dx,190,3000);bounds.x=Math.max(0,before.x+before.width-width);bounds.width=before.x+before.width-bounds.x;}else if((edges&2)!=0)bounds.width=clamp(before.width+dx,190,3000);if((edges&4)!=0){int height=clamp(before.height-dy,85,4000);bounds.y=Math.max(0,before.y+before.height-height);bounds.height=before.y+before.height-bounds.y;}else if((edges&8)!=0)bounds.height=clamp(before.height+dy,85,4000);setBounds(bounds);revalidate();growCanvas();}
            public void mouseReleased(MouseEvent e){start=null;edges=0;setCursor(Cursor.getDefaultCursor());}
        };addMouseListener(resize);addMouseMotionListener(resize);}
        Point anchor(String column,boolean right){int index=0;for(int i=0;i<columns.getModel().getSize();i++)if(columns.getModel().getElementAt(i).equals(column)){index=i;break;}Point point=SwingUtilities.convertPoint(columns,0,index*23+12,canvas);Point top=SwingUtilities.convertPoint(columns,columns.getVisibleRect().getLocation(),canvas);int y=Math.max(top.y+8,Math.min(top.y+columns.getVisibleRect().height-8,point.y));return new Point(right?getX()+getWidth():getX(),y);}
    }
}
