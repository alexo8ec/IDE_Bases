package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;
import com.nexodb.db.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/** Read-only parent rows; selecting a row only returns values to the staged child grid. */
final class ForeignKeyLookupDialog extends JDialog {
    private final JdbcSession session;
    private final ForeignKeyLookup.Reference reference;
    private final List<Object> child;
    private final int clicked;
    private final boolean writable;
    private final JTextField search = new JTextField(25);
    private final JComboBox<String> column = new JComboBox<>();
    private final JCheckBox limit = new JCheckBox(tr("Limitar filas"),true), others = new JCheckBox(tr("Filtrar por las otras claves de la fila"),true);
    private final JSpinner size = new JSpinner(new SpinnerNumberModel(25,1,10000,1)), offset = new JSpinner(new SpinnerNumberModel(0,0,Integer.MAX_VALUE,25));
    private final JButton find = UiLanguage.button("Buscar"), select = UiLanguage.button("Seleccionar referencia"), previous = new JButton("‹"), next = new JButton("›");
    private final JLabel status = new JLabel(" ");
    private final DefaultTableModel rows = new DefaultTableModel(){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table = new JTable(rows);
    private QueryResult result;
    private Map<Integer,Object> selection;
    private SwingWorker<ForeignKeyLookup.Page,Void> worker;
    private boolean initializing;
    ForeignKeyLookupDialog(Window owner,JdbcSession session,ForeignKeyLookup.Reference reference,List<Object> child,int clicked,boolean writable){
        super(owner,tr("Registro relacionado")+" · "+reference.parentTable(),ModalityType.APPLICATION_MODAL);
        this.session=session;this.reference=reference;this.child=new ArrayList<>(child);this.clicked=clicked;this.writable=writable&&reference.complete();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);setMinimumSize(new Dimension(720,430));setSize(960,570);setLocationRelativeTo(owner);
        JPanel root=new JPanel(new BorderLayout(10,12));root.setBorder(BorderFactory.createEmptyBorder(14,14,14,14));
        JPanel header=new JPanel(new BorderLayout(8,10));JLabel heading=new JLabel(reference.childTable()+" → "+reference.parentTable(),IconFactory.of(IconFactory.Kind.KEY,22),JLabel.LEFT);heading.setFont(heading.getFont().deriveFont(Font.BOLD,16));
        header.add(heading,BorderLayout.NORTH);
        JTextArea relation=new JTextArea(Objects.toString(reference.name(),"")+" · "+String.join(", ",reference.parts().stream().map(p->p.local()+" → "+p.referenced()).toList())+"\n"+tr(this.writable?"Seleccionar cambia el borrador de la fila. Pulsa Guardar en el resultado para aplicar.":"Solo lectura: puedes consultar la referencia; los datos no se modificarán."),2,70);relation.setEditable(false);relation.setLineWrap(true);relation.setWrapStyleWord(true);relation.setOpaque(false);header.add(relation);
        JPanel filters=new JPanel(new BorderLayout(8,6));column.addItem(tr("Todas las columnas"));column.setName("reference-column");search.setName("reference-search");search.setText(clicked<child.size()&&child.get(clicked)!=null&&child.get(clicked)!=QueryEditSupport.DefaultValue.INSTANCE?child.get(clicked).toString():"");
        JPanel controls=new JPanel(new BorderLayout(7,0));controls.add(search);controls.add(column,BorderLayout.WEST);find.setIcon(IconFactory.of(IconFactory.Kind.SEARCH,16));find.setName("reference-find");controls.add(find,BorderLayout.EAST);filters.add(controls,BorderLayout.NORTH);
        JPanel paging=new JPanel(new FlowLayout(FlowLayout.LEFT,7,0));paging.add(limit);paging.add(size);paging.add(new JLabel(tr("Primera fila:")));paging.add(offset);paging.add(previous);paging.add(next);others.setVisible(reference.parts().size()>1);paging.add(others);filters.add(paging,BorderLayout.SOUTH);header.add(filters,BorderLayout.SOUTH);root.add(header,BorderLayout.NORTH);
        table.setName("reference-rows");table.setAutoCreateRowSorter(true);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setRowHeight(28);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);root.add(new JScrollPane(table));
        JPanel footer=new JPanel(new BorderLayout(8,8));footer.add(status);JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));select.setName("reference-select");select.setEnabled(false);select.setVisible(this.writable);JButton close=UiLanguage.button(this.writable?"Cancelar":"Cerrar");close.addActionListener(e->dispose());select.addActionListener(e->choose());actions.add(select);actions.add(close);footer.add(actions,BorderLayout.EAST);root.add(footer,BorderLayout.SOUTH);setContentPane(root);
        find.addActionListener(e->{offset.setValue(0);load();});search.addActionListener(e->find.doClick());column.addActionListener(e->{if(!initializing){offset.setValue(0);load();}});others.addActionListener(e->{offset.setValue(0);load();});limit.addActionListener(e->{offset.setValue(0);load();});
        previous.addActionListener(e->{offset.setValue(Math.max(0,((Number)offset.getValue()).intValue()-((Number)size.getValue()).intValue()));load();});next.addActionListener(e->{long pos=((Number)offset.getValue()).longValue()+((Number)size.getValue()).longValue();if(pos<=Integer.MAX_VALUE){offset.setValue((int)pos);load();}});
        offset.addChangeListener(e->{if(worker==null)load();});size.addChangeListener(e->{if(worker==null){offset.setValue(0);load();}});
        table.getSelectionModel().addListSelectionListener(e->select.setEnabled(this.writable&&worker==null&&table.getSelectedRow()>=0));table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&SwingUtilities.isLeftMouseButton(e)&&ForeignKeyLookupDialog.this.writable)choose();}});
        getRootPane().setDefaultButton(find);getRootPane().registerKeyboardAction(e->dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);load();
    }
    Map<Integer,Object> showSelection(){setVisible(true);return selection;}
    private void load(){
        if(worker!=null)return;
        try{size.commitEdit();offset.commitEdit();}catch(java.text.ParseException invalid){status.setText(tr("Límite o posición no válidos."));return;}
        String text=search.getText(),field=column.getSelectedIndex()<=0?"":Objects.toString(column.getSelectedItem(),"");boolean match=others.isSelected();int first=limit.isSelected()?((Number)offset.getValue()).intValue():0,maximum=limit.isSelected()?((Number)size.getValue()).intValue():0;
        find.setEnabled(false);select.setEnabled(false);next.setEnabled(false);previous.setEnabled(false);size.setEnabled(false);offset.setEnabled(false);limit.setEnabled(false);column.setEnabled(false);others.setEnabled(false);status.setText(tr("Leyendo tabla referenciada…"));
        worker=new SwingWorker<>(){
            protected ForeignKeyLookup.Page doInBackground()throws Exception{return ForeignKeyLookup.search(session.connection(),session.profile().type(),reference,child,clicked,text,field,match,first,maximum);}
            protected void done(){
                worker=null;if(!isDisplayable())return;
                try{ForeignKeyLookup.Page page=get();result=page.result();rows.setDataVector(result.rows().stream().map(List::toArray).toArray(Object[][]::new),result.columns().toArray());
                    initializing=true;try{Object chosen=column.getSelectedItem();column.removeAllItems();column.addItem(tr("Todas las columnas"));for(String name:result.columns())column.addItem(name);if(field.isEmpty())column.setSelectedIndex(0);else column.setSelectedItem(chosen);}finally{initializing=false;}
                    for(int i=0;i<table.getColumnCount();i++)table.getColumnModel().getColumn(i).setPreferredWidth(Math.max(110,Math.min(280,result.columns().get(i).length()*10+30)));
                    if(table.getRowCount()>0)table.setRowSelectionInterval(0,0);
                    next.setEnabled(maximum>0&&result.truncated());previous.setEnabled(maximum>0&&first>0);status.setText(result.rows().size()+tr(" filas")+" · "+tr(page.exact()?"Coincidencia exacta":"Resultados de búsqueda"));
                }catch(Exception error){result=null;rows.setRowCount(0);Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();status.setText(tr("No se pudo consultar la referencia."));JOptionPane.showMessageDialog(ForeignKeyLookupDialog.this,cause.getMessage(),tr("Registro relacionado"),JOptionPane.ERROR_MESSAGE);}
                finally{find.setEnabled(true);size.setEnabled(limit.isSelected());offset.setEnabled(limit.isSelected());limit.setEnabled(true);column.setEnabled(true);others.setEnabled(true);select.setEnabled(writable&&result!=null&&table.getSelectedRow()>=0);}
            }
        };worker.execute();
    }
    private void choose(){if(!writable||worker!=null||result==null||table.getSelectedRow()<0)return;try{selection=ForeignKeyLookup.selection(reference,result,table.convertRowIndexToModel(table.getSelectedRow()));dispose();}catch(IllegalArgumentException error){JOptionPane.showMessageDialog(this,error.getMessage(),tr("Registro relacionado"),JOptionPane.INFORMATION_MESSAGE);}}
    @Override public void dispose(){if(worker!=null)worker.cancel(true);super.dispose();}
}
