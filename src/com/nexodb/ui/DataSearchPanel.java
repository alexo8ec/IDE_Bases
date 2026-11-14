package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;
import com.nexodb.db.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public final class DataSearchPanel extends JPanel {
    private final JdbcSession session;private final String database;private final Consumer<String> open;
    private final JTextField search=new JTextField();private final JButton run=new JButton(tr("Buscar")),cancel=new JButton(tr("Cancelar")),results=new JButton(tr("Abrir resultados"));
    private final DefaultTableModel tables=new DefaultTableModel(new Object[]{"✓",tr("Tabla")},0){public Class<?> getColumnClass(int c){return c==0?Boolean.class:String.class;}public boolean isCellEditable(int r,int c){return c==0;}};
    private final DefaultTableModel matches=new DefaultTableModel(new Object[]{tr("Tabla"),tr("Coincidencias"),tr("Detalle")},0){public boolean isCellEditable(int r,int c){return false;}};
    private final Map<String,SchemaMetadataReader.DbObject> objects=new LinkedHashMap<>();private final List<String> queries=new ArrayList<>();private final JTable found=new JTable(matches),selected=new JTable(tables);private final JLabel status=new JLabel(tr("Leyendo objetos…"));
    private String quote="\"";private SwingWorker<?,?> worker;private SqlTransferControl control;
    public DataSearchPanel(JdbcSession session,String database,Consumer<String> open){super(new BorderLayout(8,8));this.session=session;this.database=database;this.open=open;setBorder(new javax.swing.border.EmptyBorder(12,12,12,12));
        UiLanguage.text(run,UiLanguage.key("Buscar"));UiLanguage.text(cancel,UiLanguage.key("Cancelar"));UiLanguage.text(results,UiLanguage.key("Abrir resultados"));
        JPanel top=new JPanel(new BorderLayout(8,6));top.add(new JLabel(session.profile().name()+" · "+database),BorderLayout.NORTH);UiLanguage.placeholder(search,"Palabras, \"frases\", OR y -exclusiones");top.add(search);JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));buttons.add(run);buttons.add(cancel);top.add(buttons,BorderLayout.EAST);add(top,BorderLayout.NORTH);
        UiLanguage.headers(selected);UiLanguage.headers(found);selected.setAutoCreateRowSorter(true);selected.getColumnModel().getColumn(0).setMaxWidth(45);found.setAutoCreateRowSorter(true);JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(selected),new JScrollPane(found));split.setDividerLocation(300);split.setResizeWeight(.25);add(split);
        JPanel foot=new JPanel(new BorderLayout());foot.add(status);foot.add(results,BorderLayout.EAST);add(foot,BorderLayout.SOUTH);results.setEnabled(false);run.setEnabled(false);cancel.setEnabled(false);
        run.addActionListener(e->search());search.addActionListener(e->search());results.addActionListener(e->openSelected());found.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)openSelected();}});found.getSelectionModel().addListSelectionListener(e->results.setEnabled(worker==null&&found.getSelectedRow()>=0));cancel.addActionListener(e->{SqlTransferControl active=control;if(active!=null){Thread t=new Thread(active::cancel,"nexodb-search-cancel");t.setDaemon(true);t.start();}});
        new SwingWorker<SchemaMetadataReader.SchemaDef,Void>(){protected SchemaMetadataReader.SchemaDef doInBackground()throws Exception{quote=session.connection().getMetaData().getIdentifierQuoteString();return SchemaMetadataReader.read(session,database);}protected void done(){try{get().objects().values().stream().filter(o->o.type().equals("TABLE")).forEach(o->{objects.put(o.name(),o);tables.addRow(new Object[]{true,o.name()});});run.setEnabled(true);status.setText(tr("Se muestran hasta 100 coincidencias por tabla. La búsqueda solo lee datos."));}catch(Exception error){status.setText(error.getCause().getMessage());}}}.execute();
    }
    private void openSelected(){int row=found.getSelectedRow();if(row>=0){String sql=queries.get(found.convertRowIndexToModel(row));if(!sql.isBlank())open.accept(sql);}}
    private void search(){
        if(!LicenseGate.ensure(this))return;
        if(worker!=null||search.getText().isBlank())return;if(selected.isEditing())selected.getCellEditor().stopCellEditing();List<String> names=new ArrayList<>();for(int i=0;i<tables.getRowCount();i++)if(Boolean.TRUE.equals(tables.getValueAt(i,0)))names.add(tables.getValueAt(i,1).toString());if(names.isEmpty())return;
        String text=search.getText();matches.setRowCount(0);queries.clear();control=new SqlTransferControl();SqlTransferControl request=control;run.setEnabled(false);cancel.setEnabled(true);selected.setEnabled(false);results.setEnabled(false);
        worker=new SwingWorker<Void,Object[]>(){protected Void doInBackground()throws Exception{for(String name:names){request.check();var object=objects.get(name);List<String> columns=object.columns().values().stream().filter(c->!c.type().toUpperCase(Locale.ROOT).matches(".*(BLOB|BINARY|GEOMETRY|POINT|IMAGE|BYTEA).*" )).map(SchemaMetadataReader.ColumnDef::name).toList();if(columns.isEmpty())continue;
            DataSearch.Condition condition=DataSearch.condition(session.profile().type(),quote,columns,text);String base="SELECT * FROM "+VisualQuery.qualified(database,name,quote)+" WHERE ";String prepared=base+condition.sql();try(var statement=session.connection().prepareStatement(prepared)){
                request.active(statement);statement.setMaxRows(101);try{statement.setQueryTimeout(30);}catch(java.sql.SQLException ignored){}for(int i=0;i<condition.parameters().size();i++)statement.setString(i+1,condition.parameters().get(i));int count=0;try(var rows=statement.executeQuery()){while(rows.next()){request.check();count++;}}
                if(count>0)publish(new Object[]{name,count>100?"100+":""+count,"",base+DataSearch.inline(session.profile().type(),condition)+";"});
            }catch(java.sql.SQLException error){request.check();publish(new Object[]{name,"",error.getMessage(),""});}finally{request.active(null);}
        }return null;}protected void process(List<Object[]> chunks){for(Object[] row:chunks){matches.addRow(new Object[]{row[0],row[1],row[2]});queries.add(row[3].toString());}status.setText(tr("%s tablas con resultados o avisos",matches.getRowCount()));}protected void done(){worker=null;run.setEnabled(true);cancel.setEnabled(false);selected.setEnabled(true);try{get();status.setText(tr("Búsqueda terminada: %s tablas",matches.getRowCount()));}catch(Exception error){status.setText(error.getCause() instanceof java.util.concurrent.CancellationException?tr("Búsqueda cancelada"):Objects.toString(error.getCause(),error.toString()));}results.setEnabled(found.getSelectedRow()>=0);}};worker.execute();
    }
}
