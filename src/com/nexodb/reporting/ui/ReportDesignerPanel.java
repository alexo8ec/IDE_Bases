package com.nexodb.reporting.ui;

import com.nexodb.reporting.*;
import static com.nexodb.reporting.ReportDesign.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.print.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** Standalone Swing module. Integration supplies snapshots and CSV/XLSX writers, never a JDBC session. */
public final class ReportDesignerPanel extends JPanel {
    private final Supplier<List<ReportData>> sources;
    private final ReportExporter.TabularWriter tabular;
    private final Consumer<String> status;
    private ReportData data;
    private ReportDesign design;
    private ReportLayout layout;
    private Path template;
    private boolean dirty,updating,exporting;
    private String selected;
    private int page;
    private final Deque<ReportDesign> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private final JComboBox<ReportData> source=new JComboBox<>();
    private final JLabel state=new JLabel(" "),pageLabel=new JLabel("1 / 1");
    private final ReportCanvas canvas=new ReportCanvas();
    private final ColumnModel columnModel=new ColumnModel();
    private final JTable columns=new JTable(columnModel);
    private final DefaultListModel<Block> blockModel=new DefaultListModel<>();
    private final JList<Block> blocks=new JList<>(blockModel);
    private final DefaultListModel<Integer> groupModel=new DefaultListModel<>();
    private final JList<Integer> groups=new JList<>(groupModel);
    private final JComboBox<String> groupField=new JComboBox<>(),blockField=new JComboBox<>();
    private final JTextField blockText=new JTextField(),reportTitle=new JTextField(),footer=new JTextField();
    private final JSpinner bx=number(32,24,818),by=number(32,24,810),bw=number(160,40,778),bh=number(44,24,650),font=number(18,8,48),band=number(190,90,682);
    private final JCheckBox bold=new JCheckBox("Negrita"),landscape=new JCheckBox("Horizontal");
    private final JComboBox<Aggregate> aggregate=new JComboBox<>(Aggregate.values());
    private int blockColor=0x176B65;
    private final JButton export=new JButton("Exportar…"),undoButton=new JButton("Deshacer"),redoButton=new JButton("Rehacer");
    private final javax.swing.Timer previewTimer;
    private SwingWorker<ReportLayout,Void> previewWorker;
    private long previewGeneration;

    public ReportDesignerPanel(Supplier<List<ReportData>> sources,ReportData initial,ReportExporter.TabularWriter tabular,Consumer<String> status) {
        super(new BorderLayout(8,8));this.sources=sources;this.tabular=tabular;this.status=status;
        this.data=initial==null?ReportData.empty():initial;this.design=ReportDesign.create(this.data);
        setBorder(new EmptyBorder(8,8,8,8));setName("report-designer");
        previewTimer=new javax.swing.Timer(180,e->buildPreview());previewTimer.setRepeats(false);
        add(toolbar(),BorderLayout.NORTH);
        JTabbedPane properties=new JTabbedPane();properties.addTab("Columnas",columnSettings());properties.addTab("Agrupar",groupSettings());properties.addTab("Objetos",blockSettings());properties.addTab("Página",pageSettings());
        properties.setPreferredSize(new Dimension(310,500));properties.setMinimumSize(new Dimension(240,200));
        JPanel preview=new JPanel(new BorderLayout());JScrollPane paper=new JScrollPane(canvas);paper.getViewport().setBackground(new Color(0xDDE5E8));preview.add(paper);
        JPanel navigation=new JPanel(new FlowLayout(FlowLayout.CENTER));button(navigation,"◀",()->showPage(page-1));navigation.add(pageLabel);button(navigation,"▶",()->showPage(page+1));
        JComboBox<String> zoom=new JComboBox<>(new String[]{"60%","80%","100%","125%"});zoom.setSelectedIndex(1);zoom.addActionListener(e->{canvas.scale=Integer.parseInt(zoom.getSelectedItem().toString().replace("%",""))/100.0;canvas.revalidate();canvas.repaint();});navigation.add(zoom);
        JCheckBox editing=new JCheckBox("Modo diseño",true);editing.addActionListener(e->{canvas.editing=editing.isSelected();canvas.repaint();});navigation.add(editing);preview.add(navigation,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,properties,preview);split.setResizeWeight(0);split.setDividerLocation(310);add(split);
        state.setBorder(new EmptyBorder(5,8,5,8));add(state,BorderLayout.SOUTH);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Z"),"report-undo");getActionMap().put("report-undo",new AbstractAction(){public void actionPerformed(ActionEvent e){undo();}});
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Y"),"report-redo");getActionMap().put("report-redo",new AbstractAction(){public void actionPerformed(ActionEvent e){redo();}});
        reloadSources();sync();buildPreview();
    }
    private JComponent toolbar() {
        JPanel top=new JPanel(new BorderLayout(4,5));JPanel actions=new JPanel(new WrapLayout());
        button(actions,"Nuevo",()->{if(confirmClose()){data=selectedSource();if(data==null)data=ReportData.empty();design=ReportDesign.create(data);template=null;dirty=false;undo.clear();redo.clear();selected=null;sync();buildPreview();}});
        button(actions,"Abrir plantilla…",this::open);button(actions,"Guardar plantilla…",()->save(false));button(actions,"Guardar como…",()->save(true));
        undoButton.addActionListener(e->undo());redoButton.addActionListener(e->redo());actions.add(undoButton);actions.add(redoButton);
        button(actions,"+ Texto",()->addBlock(Kind.TEXT));button(actions,"+ Indicador",()->addBlock(Kind.METRIC));button(actions,"+ Gráfico",()->addBlock(Kind.BAR_CHART));
        export.addActionListener(e->export());actions.add(export);button(actions,"Imprimir…",this::print);top.add(actions);
        JPanel dataBar=new JPanel(new WrapLayout());dataBar.add(new JLabel("Resultado de origen:"));source.setPreferredSize(new Dimension(300,28));dataBar.add(source);
        button(dataBar,"Actualizar lista",this::reloadSources);button(dataBar,"Usar resultado",()->{var next=selectedSource();if(next!=null)useData(next);});
        JLabel hint=new JLabel("Arrastra objetos o el inicio del detalle. Doble clic para editar texto.");hint.setForeground(new Color(0x607080));dataBar.add(hint);top.add(dataBar,BorderLayout.SOUTH);return top;
    }
    private JComponent columnSettings() {
        JPanel panel=new JPanel(new BorderLayout(4,8));panel.setBorder(new EmptyBorder(8,6,8,6));
        columns.setName("report-columns");columns.setRowHeight(27);columns.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);columns.putClientProperty("terminateEditOnFocusLost",true);
        columns.getColumnModel().getColumn(0).setPreferredWidth(40);columns.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(Aggregate.values())));
        panel.add(new JScrollPane(columns));JPanel bar=new JPanel(new FlowLayout(FlowLayout.LEFT));button(bar,"Subir",()->moveColumn(-1));button(bar,"Bajar",()->moveColumn(1));panel.add(bar,BorderLayout.SOUTH);
        panel.add(new JLabel("Edita títulos, anchos relativos y totales por columna."),BorderLayout.NORTH);return panel;
    }
    private JComponent groupSettings() {
        JPanel panel=new JPanel(new BorderLayout(6,8));panel.setBorder(new EmptyBorder(8,8,8,8));
        groups.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){return super.getListCellRendererComponent(list,value instanceof Integer i?design.sourceColumns().get(i):value,index,selected,focus);}});
        JPanel top=new JPanel(new BorderLayout(4,4));top.add(new JLabel("Encabezados de grupo, de mayor a menor nivel"),BorderLayout.NORTH);top.add(groupField);JButton add=new JButton("Agregar");add.addActionListener(e->{int field=groupField.getSelectedIndex();if(field>=0&&!design.groups().contains(field))attempt(()->change(design.withGroups(append(design.groups(),field))));});top.add(add,BorderLayout.SOUTH);panel.add(top,BorderLayout.NORTH);panel.add(new JScrollPane(groups));
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));button(controls,"Subir",()->moveGroup(-1));button(controls,"Bajar",()->moveGroup(1));button(controls,"Quitar",()->{int i=groups.getSelectedIndex();if(i>=0){var next=new ArrayList<>(design.groups());next.remove(i);change(design.withGroups(next));}});panel.add(controls,BorderLayout.SOUTH);return panel;
    }
    private JComponent blockSettings() {
        JPanel panel=new JPanel(new BorderLayout(4,8));panel.setBorder(new EmptyBorder(8,8,8,8));blocks.setVisibleRowCount(4);blocks.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        blocks.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){return super.getListCellRendererComponent(list,value instanceof Block b?b.kind()+" · "+b.text():value,index,selected,focus);}});
        blocks.addListSelectionListener(e->{if(!updating&&!e.getValueIsAdjusting()&&blocks.getSelectedValue()!=null){selected=blocks.getSelectedValue().id();syncBlock();canvas.repaint();}});
        panel.add(new JScrollPane(blocks),BorderLayout.NORTH);
        JPanel form=new JPanel(new GridBagLayout());int row=0;row=field(form,row,"Texto / título",blockText);row=field(form,row,"Campo del indicador",blockField);row=field(form,row,"Operación",aggregate);
        row=field(form,row,"Posición X",bx);row=field(form,row,"Posición Y",by);row=field(form,row,"Ancho",bw);row=field(form,row,"Alto",bh);row=field(form,row,"Tamaño de letra",font);row=field(form,row,"",bold);
        JButton color=new JButton("Color…");color.addActionListener(e->{Color picked=JColorChooser.showDialog(this,"Color del objeto",new Color(blockColor));if(picked!=null)blockColor=picked.getRGB()&0xffffff;});field(form,row,"",color);
        panel.add(new JScrollPane(form));JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));button(actions,"Aplicar",this::applyBlock);button(actions,"Duplicar",this::duplicateBlock);button(actions,"Eliminar",this::deleteBlock);panel.add(actions,BorderLayout.SOUTH);return panel;
    }
    private JComponent pageSettings() {
        JPanel panel=new JPanel(new BorderLayout());JPanel form=new JPanel(new GridBagLayout());form.setBorder(new EmptyBorder(8,8,8,8));
        int row=field(form,0,"Nombre del reporte",reportTitle);row=field(form,row,"Orientación",landscape);row=field(form,row,"Inicio del detalle",band);row=field(form,row,"Pie de página",footer);
        JButton color=new JButton("Color de encabezados…");color.addActionListener(e->{Color value=JColorChooser.showDialog(this,"Color de encabezados",new Color(design.accent()));if(value!=null)attempt(()->change(new ReportDesign(design.title(),design.landscape(),design.headerHeight(),design.footer(),value.getRGB()&0xffffff,design.sourceColumns(),design.columns(),design.groups(),design.blocks())));});row=field(form,row,"",color);
        JButton apply=new JButton("Aplicar página");apply.addActionListener(e->attempt(()->change(new ReportDesign(reportTitle.getText(),landscape.isSelected(),(Integer)band.getValue(),footer.getText(),design.accent(),design.sourceColumns(),design.columns(),design.groups(),design.blocks()))));field(form,row,"",apply);
        panel.add(form,BorderLayout.NORTH);JTextArea help=new JTextArea("El encabezado contiene los objetos que puedes ubicar libremente. El detalle repite las columnas y los encabezados de grupo, calcula subtotales y continúa en las páginas siguientes. El pie incluye origen y número de página.\n\nCSV y Excel conservan columnas, agrupaciones y totales como datos. HTML y PDF conservan el diseño visual.");help.setEditable(false);help.setLineWrap(true);help.setWrapStyleWord(true);help.setOpaque(false);help.setBorder(new EmptyBorder(12,12,12,12));panel.add(help);return panel;
    }
    private static JSpinner number(int value,int min,int max){return new JSpinner(new SpinnerNumberModel(value,min,max,1));}
    private static int field(JPanel panel,int row,String label,JComponent input){GridBagConstraints c=new GridBagConstraints();c.insets=new Insets(4,2,4,4);c.anchor=GridBagConstraints.WEST;c.gridx=0;c.gridy=row;panel.add(new JLabel(label),c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;panel.add(input,c);return row+1;}
    private static void button(JComponent parent,String label,Runnable action){JButton button=new JButton(label);button.addActionListener(e->action.run());parent.add(button);}
    private static <T> List<T> append(List<T> list,T value){var result=new ArrayList<>(list);result.add(value);return result;}
    private void attempt(Runnable action){try{action.run();}catch(RuntimeException error){error(error);}}
    private void error(Throwable error){JOptionPane.showMessageDialog(this,error.getMessage(),"Reportería",JOptionPane.ERROR_MESSAGE);}
    private void change(ReportDesign next){if(next.equals(design))return;undo.push(design);while(undo.size()>80)undo.removeLast();redo.clear();design=next;dirty=true;page=0;sync();previewTimer.restart();}
    private void undo(){if(!undo.isEmpty()){redo.push(design);design=undo.pop();dirty=true;sync();previewTimer.restart();}}
    private void redo(){if(!redo.isEmpty()){undo.push(design);design=redo.pop();dirty=true;sync();previewTimer.restart();}}
    private void sync() {
        updating=true;
        try {
            columnModel.fireTableDataChanged();blockModel.clear();for(Block b:design.blocks())blockModel.addElement(b);
            for(int i=0;i<blockModel.size();i++)if(blockModel.get(i).id().equals(selected))blocks.setSelectedIndex(i);
            groupModel.clear();design.groups().forEach(groupModel::addElement);groupField.removeAllItems();blockField.removeAllItems();blockField.addItem("Todas las filas");for(String field:design.sourceColumns()){groupField.addItem(field);blockField.addItem(field);}
            reportTitle.setText(design.title());footer.setText(design.footer());landscape.setSelected(design.landscape());band.setValue(design.headerHeight());syncBlock();
            undoButton.setEnabled(!undo.isEmpty());redoButton.setEnabled(!redo.isEmpty());export.setEnabled(!exporting&&!data.columns().isEmpty());
            canvas.revalidate();canvas.repaint();state.setText((dirty?"Diseño modificado · ":"")+data+" · "+(layout==null?"calculando…":layout.pages().size()+" páginas"));
        }finally{updating=false;}
    }
    private Block selectedBlock(){return design.blocks().stream().filter(b->b.id().equals(selected)).findFirst().orElse(null);}
    private void syncBlock(){Block b=selectedBlock();if(b==null)return;blockText.setText(b.text());bx.setValue(b.x());by.setValue(b.y());bw.setValue(b.width());bh.setValue(b.height());font.setValue(b.fontSize());bold.setSelected(b.bold());blockColor=b.color();blockField.setSelectedIndex(b.field()+1);aggregate.setSelectedItem(b.aggregate());}
    private void applyBlock(){Block b=selectedBlock();if(b!=null)attempt(()->change(design.replace(new Block(b.id(),b.kind(),(Integer)bx.getValue(),(Integer)by.getValue(),(Integer)bw.getValue(),(Integer)bh.getValue(),blockText.getText(),(Integer)font.getValue(),bold.isSelected(),blockColor,blockField.getSelectedIndex()-1,(Aggregate)aggregate.getSelectedItem()))));}
    private void addBlock(Kind kind) {
        attempt(()->{
            int y=design.blocks().stream().mapToInt(b->b.y()+b.height()).max().orElse(24)+12,h=kind==Kind.BAR_CHART?160:48,w=kind==Kind.BAR_CHART?design.pageWidth()-64:220;
            int header=Math.max(design.headerHeight(),y+h+12);
            if(header>design.pageHeight()-160)throw new IllegalArgumentException("No hay espacio en el encabezado. Reubica o reduce los objetos existentes.");
            Block block=new Block(UUID.randomUUID().toString(),kind,32,y,w,h,kind==Kind.TEXT?"Nuevo título":kind==Kind.METRIC?"Registros":"Categorías principales",kind==Kind.TEXT?18:20,true,design.accent(),-1,kind==Kind.TEXT?Aggregate.NONE:Aggregate.COUNT);
            selected=block.id();change(new ReportDesign(design.title(),design.landscape(),header,design.footer(),design.accent(),design.sourceColumns(),design.columns(),design.groups(),append(design.blocks(),block)));
        });
    }
    private void duplicateBlock(){Block b=selectedBlock();if(b==null)return;attempt(()->{Block next=new Block(UUID.randomUUID().toString(),b.kind(),Math.min(b.x()+12,design.pageWidth()-24-b.width()),Math.min(b.y()+12,design.headerHeight()-8-b.height()),b.width(),b.height(),b.text(),b.fontSize(),b.bold(),b.color(),b.field(),b.aggregate());selected=next.id();change(design.withBlocks(append(design.blocks(),next)));});}
    private void deleteBlock(){if(selectedBlock()!=null){String id=selected;selected=null;change(design.withBlocks(design.blocks().stream().filter(b->!b.id().equals(id)).toList()));}}
    private void moveColumn(int delta){if(columns.isEditing()&&!columns.getCellEditor().stopCellEditing())return;int row=columns.getSelectedRow(),next=row+delta;if(row>=0&&next>=0&&next<design.columns().size()){var list=new ArrayList<>(design.columns());Collections.swap(list,row,next);change(design.withColumns(list));columns.setRowSelectionInterval(next,next);}}
    private void moveGroup(int delta){int row=groups.getSelectedIndex(),next=row+delta;if(row>=0&&next>=0&&next<design.groups().size()){var list=new ArrayList<>(design.groups());Collections.swap(list,row,next);change(design.withGroups(list));groups.setSelectedIndex(next);}}
    private void reloadSources(){ReportData selected=selectedSource();source.removeAllItems();for(var item:sources.get())source.addItem(item);if(selected!=null)for(int i=0;i<source.getItemCount();i++)if(source.getItemAt(i).name().equals(selected.name()))source.setSelectedIndex(i);}
    private ReportData selectedSource(){return (ReportData)source.getSelectedItem();}
    private void useData(ReportData next) {
        if(!design.sourceColumns().equals(next.columns())) {
            if(JOptionPane.showConfirmDialog(this,"Las columnas son distintas. ¿Crear un diseño nuevo para este resultado?","Cambiar origen",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            if(!confirmClose())return;data=next;design=ReportDesign.create(next);template=null;undo.clear();redo.clear();dirty=false;selected=null;
        } else data=next;
        sync();buildPreview();
    }
    private void buildPreview() {
        if(previewWorker!=null)previewWorker.cancel(true);long generation=++previewGeneration;ReportDesign snapshot=design;ReportData input=data;
        previewWorker=new SwingWorker<>() {
            protected ReportLayout doInBackground(){return ReportLayout.build(ReportPlan.build(snapshot,input));}
            protected void done(){if(generation!=previewGeneration||isCancelled())return;try{layout=get();page=Math.min(page,layout.pages().size()-1);pageLabel.setText((page+1)+" / "+layout.pages().size());sync();}catch(Exception error){layout=null;state.setText("Vista previa: "+root(error).getMessage());canvas.repaint();}}
        };previewWorker.execute();
    }
    private static Throwable root(Throwable error){while(error.getCause()!=null)error=error.getCause();return error;}
    private void showPage(int value){if(layout!=null){page=Math.max(0,Math.min(layout.pages().size()-1,value));pageLabel.setText((page+1)+" / "+layout.pages().size());canvas.repaint();}}
    private void open() {
        if(!confirmClose())return;JFileChooser chooser=templateChooser();if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        try{ReportDesign next=ReportStore.load(chooser.getSelectedFile().toPath());if(!data.columns().isEmpty())next.requireCompatible(data);else data=new ReportData("Plantilla sin datos",next.sourceColumns(),List.of(),false);design=next;template=chooser.getSelectedFile().toPath();dirty=false;undo.clear();redo.clear();selected=null;sync();buildPreview();}catch(Exception error){error(error);}
    }
    private boolean save(boolean as) {
        if(columns.isEditing()&&!columns.getCellEditor().stopCellEditing())return false;
        Path target=as?null:template;
        if(target==null){JFileChooser chooser=templateChooser();chooser.setSelectedFile(new java.io.File("reporte.nxreport"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return false;target=extension(chooser.getSelectedFile().toPath(),"nxreport");if(Files.exists(target)&&JOptionPane.showConfirmDialog(this,"¿Reemplazar la plantilla existente?","Guardar plantilla",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return false;}
        try{ReportStore.save(target,design);template=target;dirty=false;sync();status.accept("Plantilla de reporte guardada: "+target.getFileName());return true;}catch(Exception error){error(error);return false;}
    }
    private static JFileChooser templateChooser(){JFileChooser chooser=new JFileChooser();chooser.setFileFilter(new FileNameExtensionFilter("Plantilla AdisDB (*.nxreport)","nxreport"));chooser.setAcceptAllFileFilterUsed(false);return chooser;}
    private static Path extension(Path path,String extension){return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("."+extension)?path:path.resolveSibling(path.getFileName()+"."+extension);}
    public boolean canClose() {
        if(columns.isEditing()&&!columns.getCellEditor().stopCellEditing())return false;
        if(exporting){JOptionPane.showMessageDialog(this,"Espera a que termine la exportación.");return false;}
        return confirmClose();
    }
    private boolean confirmClose(){if(!dirty)return true;int answer=JOptionPane.showOptionDialog(this,"El diseño tiene cambios sin guardar.","Plantilla de reporte",JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,null,new String[]{"Guardar","Descartar","Cancelar"},"Guardar");return answer==1||answer==0&&save(false);}
    public void dispose(){previewGeneration++;previewTimer.stop();if(previewWorker!=null)previewWorker.cancel(true);}
    private void export() {
        if(columns.isEditing()&&!columns.getCellEditor().stopCellEditing())return;
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Exportar reporte");chooser.setAcceptAllFileFilterUsed(false);Map<javax.swing.filechooser.FileFilter,ReportExporter.Format> formats=new LinkedHashMap<>();
        for(var format:ReportExporter.Format.values()){var filter=new FileNameExtensionFilter(format+" (*."+format.extension()+")",format.extension());formats.put(filter,format);chooser.addChoosableFileFilter(filter);}
        chooser.setFileFilter(formats.keySet().iterator().next());chooser.setSelectedFile(new java.io.File("reporte.html"));
        chooser.addPropertyChangeListener(JFileChooser.FILE_FILTER_CHANGED_PROPERTY,e->{var format=formats.get(chooser.getFileFilter());if(format!=null){String name=chooser.getSelectedFile()==null?"reporte":chooser.getSelectedFile().getName();int dot=name.lastIndexOf('.');if(dot>0)name=name.substring(0,dot);chooser.setSelectedFile(new java.io.File(name+"."+format.extension()));}});
        if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;var format=formats.get(chooser.getFileFilter());Path target=extension(chooser.getSelectedFile().toPath(),format.extension());
        if(Files.exists(target)&&JOptionPane.showConfirmDialog(this,"¿Reemplazar el archivo existente?","Exportar reporte",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        ReportDesign snapshot=design;ReportData input=data;exporting=true;sync();state.setText("Exportando "+format+"…");
        new SwingWorker<Void,Void>() {
            protected Void doInBackground()throws Exception{ReportExporter.write(ReportPlan.build(snapshot,input),target,format,tabular);return null;}
            protected void done(){exporting=false;sync();try{get();status.accept("Reporte exportado: "+target.getFileName());state.setText("Reporte guardado: "+target);}catch(Exception error){error(root(error));}}
        }.execute();
    }
    private void print() {
        if(layout==null||layout.plan().design()!=design){state.setText("Espera a que termine de actualizar la vista previa.");return;}ReportLayout snapshot=layout;PrinterJob job=PrinterJob.getPrinterJob();PageFormat format=job.defaultPage();format.setOrientation(design.landscape()?PageFormat.LANDSCAPE:PageFormat.PORTRAIT);
        job.setJobName(design.title());job.setPrintable((graphics,pf,index)->{if(index>=snapshot.pages().size())return Printable.NO_SUCH_PAGE;Graphics2D g=(Graphics2D)graphics.create();try{g.translate(pf.getImageableX(),pf.getImageableY());double scale=Math.min(pf.getImageableWidth()/snapshot.plan().design().pageWidth(),pf.getImageableHeight()/snapshot.plan().design().pageHeight());g.scale(scale,scale);ReportRenderer.paint(g,snapshot,index);}finally{g.dispose();}return Printable.PAGE_EXISTS;},format);
        if(job.printDialog())new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{job.print();return null;}protected void done(){try{get();}catch(Exception error){error(root(error));}}}.execute();
    }
    private final class ColumnModel extends AbstractTableModel {
        private final String[] names={"Ver","Título","Ancho","Total"};
        public int getRowCount(){return design==null?0:design.columns().size();}public int getColumnCount(){return names.length;}public String getColumnName(int column){return names[column];}
        public Class<?> getColumnClass(int column){return column==0?Boolean.class:column==2?Integer.class:column==3?Aggregate.class:String.class;}public boolean isCellEditable(int r,int c){return true;}
        public Object getValueAt(int row,int col){Column c=design.columns().get(row);return switch(col){case 0->c.visible();case 1->c.title();case 2->c.width();default->c.aggregate();};}
        public void setValueAt(Object value,int row,int col){attempt(()->{Column c=design.columns().get(row);var next=new Column(c.source(),col==1?value.toString():c.title(),col==2?Integer.parseInt(value.toString()):c.width(),col==0?(Boolean)value:c.visible(),col==3?(Aggregate)value:c.aggregate());var list=new ArrayList<>(design.columns());list.set(row,next);change(design.withColumns(list));});}
    }
    private final class ReportCanvas extends JComponent {
        double scale=.8;boolean editing=true;Point anchor;Block original,draft;boolean movingBand;int draftBand;
        ReportCanvas() {
            setName("report-canvas");setFocusable(true);
            MouseAdapter mouse=new MouseAdapter(){
                public void mousePressed(MouseEvent e){requestFocusInWindow();if(!editing||page!=0)return;Point p=point(e);anchor=p;original=null;draft=null;movingBand=Math.abs(p.y-design.headerHeight())<=7;draftBand=design.headerHeight();if(movingBand)return;List<Block> list=design.blocks();selected=null;for(int i=list.size()-1;i>=0;i--){Block b=list.get(i);if(new Rectangle(b.x(),b.y(),b.width(),b.height()).contains(p)){selected=b.id();original=b;break;}}sync();}
                public void mouseDragged(MouseEvent e){if(anchor==null)return;Point p=point(e);if(movingBand){int bottom=design.blocks().stream().mapToInt(b->b.y()+b.height()+8).max().orElse(90);draftBand=Math.max(bottom,Math.min(design.pageHeight()-160,p.y));repaint();return;}if(original==null)return;
                    int dx=p.x-anchor.x,dy=p.y-anchor.y;boolean resize=anchor.x>=original.x()+original.width()-10&&anchor.y>=original.y()+original.height()-10;
                    if(resize)draft=original.bounds(original.x(),original.y(),Math.max(40,Math.min(design.pageWidth()-24-original.x(),snap(original.width()+dx))),Math.max(24,Math.min(design.headerHeight()-8-original.y(),snap(original.height()+dy))));
                    else draft=original.bounds(Math.max(24,Math.min(design.pageWidth()-24-original.width(),snap(original.x()+dx))),Math.max(24,Math.min(design.headerHeight()-8-original.height(),snap(original.y()+dy))),original.width(),original.height());repaint();}
                public void mouseReleased(MouseEvent e){if(movingBand){int value=draftBand;movingBand=false;change(new ReportDesign(design.title(),design.landscape(),value,design.footer(),design.accent(),design.sourceColumns(),design.columns(),design.groups(),design.blocks()));}else if(draft!=null)change(design.replace(draft));anchor=null;original=null;draft=null;repaint();}
                public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&selectedBlock()!=null&&editing&&page==0){Block b=selectedBlock();String text=JOptionPane.showInputDialog(ReportDesignerPanel.this,"Texto / título",b.text());if(text!=null)change(design.replace(new Block(b.id(),b.kind(),b.x(),b.y(),b.width(),b.height(),text,b.fontSize(),b.bold(),b.color(),b.field(),b.aggregate())));}}
            };addMouseListener(mouse);addMouseMotionListener(mouse);
            getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE,0),"delete-block");getActionMap().put("delete-block",new AbstractAction(){public void actionPerformed(ActionEvent e){if(editing)deleteBlock();}});
        }
        private int snap(int value){return Math.round(value/4f)*4;}
        private Point point(MouseEvent e){return new Point((int)((e.getX()-16)/scale),(int)((e.getY()-16)/scale));}
        @Override public Dimension getPreferredSize(){return new Dimension((int)(design.pageWidth()*scale)+32,(int)(design.pageHeight()*scale)+32);}
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g=(Graphics2D)graphics.create();try {
                g.setColor(new Color(0xDDE5E8));g.fillRect(0,0,getWidth(),getHeight());
                g.translate(16,16);g.scale(scale,scale);
                if(layout==null){g.setColor(Color.WHITE);g.fillRect(0,0,design.pageWidth(),design.pageHeight());g.setColor(Color.GRAY);g.drawString("Preparando vista previa…",32,40);return;}
                ReportRenderer.paint(g,layout,Math.min(page,layout.pages().size()-1));
                if(page==0&&editing) {
                    g.setColor(Color.WHITE);g.fillRect(0,6,design.pageWidth(),design.headerHeight()-6);
                    for(Block b:design.blocks())ReportRenderer.paintBlock(g,layout.plan(),draft!=null&&b.id().equals(draft.id())?draft:b);
                    g.setColor(new Color(0x789797));g.setStroke(new BasicStroke(1,0,0,1,new float[]{4,4},0));int y=movingBand?draftBand:design.headerHeight();g.drawLine(24,y,design.pageWidth()-24,y);
                    g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,8));g.drawString("Encabezado  /  Detalle agrupado · arrastra esta línea",32,y-3);
                    Block b=draft==null?selectedBlock():draft;if(b!=null){g.setColor(new Color(0x14837B));g.setStroke(new BasicStroke(1));g.drawRect(b.x()-2,b.y()-2,b.width()+4,b.height()+4);g.fillRect(b.x()+b.width()-4,b.y()+b.height()-4,8,8);}
                }
            }finally{g.dispose();}
        }
    }
}
