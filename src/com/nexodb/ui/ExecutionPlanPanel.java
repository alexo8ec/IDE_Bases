package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.model.DatabaseType;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import static com.nexodb.util.I18n.tr;

/** Visual operators, original XML/JSON and optimizer index recommendations side by side. */
public final class ExecutionPlanPanel extends JPanel {
    private final JdbcSession origin;private final Consumer<Boolean> busy;private final Consumer<String> openSql;
    private final SqlEditorPane sql=new SqlEditorPane();
    private final JCheckBox actual=UiLanguage.text(new JCheckBox(),UiLanguage.key("Plan real (ejecuta la consulta)"));
    private final JButton generate=UiLanguage.button("Obtener plan"),cancel=UiLanguage.button("Cancelar");
    private final JTextArea raw=new JTextArea(),suggestions=new JTextArea();
    private final DefaultTableModel properties=new DefaultTableModel(new String[]{"Propiedad","Valor"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final DefaultTableModel operators=new DefaultTableModel(new String[]{"Operador","Filas estimadas","Costo estimado (unidades)","Tiempo del operador (ms)"},0){public boolean isCellEditable(int r,int c){return false;}public Class<?> getColumnClass(int c){return c==2||c==3?Double.class:String.class;}};
    private final PlanGraph graph=new PlanGraph();
    private final JLabel status=new JLabel(" ");
    private final JButton askAi=UiLanguage.button("Analizar con IA");
    private BiConsumer<String,String> aiHandler;
    private QueryPlan.Result result;private QueryPlan.Control control;private boolean running;
    public ExecutionPlanPanel(JdbcSession origin,String input,Consumer<Boolean> busy,Consumer<String> openSql) {
        super(new BorderLayout(6,6));this.origin=origin;this.busy=busy;this.openSql=openSql;setName("execution-plan");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));sql.restoreText(input);sql.setPreferredSize(new Dimension(400,90));
        JPanel top=new JPanel(new BorderLayout());JPanel toolbar=new JPanel(new com.nexodb.reporting.ui.WrapLayout());toolbar.add(generate);toolbar.add(actual);toolbar.add(cancel);JButton save=UiLanguage.button("Guardar plan…");toolbar.add(save);JButton open=UiLanguage.button("Abrir sugerencias SQL");toolbar.add(open);toolbar.add(askAi);askAi.setEnabled(false);askAi.addActionListener(e->{if(result!=null&&aiHandler!=null)aiHandler.accept(sql.getText(),result.raw());});top.add(toolbar,BorderLayout.NORTH);top.add(new JScrollPane(sql));add(top,BorderLayout.NORTH);
        raw.setEditable(false);raw.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));suggestions.setEditable(false);suggestions.setFont(raw.getFont());
        JTabbedPane tabs=new JTabbedPane();JSplitPane visual=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(graph),new JScrollPane(new JTable(properties)));visual.setResizeWeight(.8);visual.setDividerLocation(340);UiLanguage.addTab(tabs,"Plan visual",null,visual);
        JTable operatorTable=new JTable(operators);operatorTable.setAutoCreateRowSorter(true);operatorTable.setName("execution-plan-operators");JPanel operatorPanel=new JPanel(new BorderLayout());operatorPanel.add(new JScrollPane(operatorTable));operatorPanel.add(UiLanguage.label("Los tiempos pueden incluir hijos o promedios por loop; no deben sumarse."),BorderLayout.SOUTH);UiLanguage.addTab(tabs,"Operadores y métricas",null,operatorPanel);
        UiLanguage.addTab(tabs,"XML / JSON / Texto",null,new JScrollPane(raw));UiLanguage.addTab(tabs,"Sugerencias de índices",null,new JScrollPane(suggestions));add(tabs);add(status,BorderLayout.SOUTH);
        generate.addActionListener(e->generate());cancel.addActionListener(e->{if(control!=null){UiLanguage.text(status,UiLanguage.key("Cancelando análisis…"));Thread thread=new Thread(control::cancel,"nexodb-plan-cancel");thread.setDaemon(true);thread.start();}});cancel.setEnabled(false);
        save.addActionListener(e->save());open.addActionListener(e->{if(result!=null&&!result.suggestions().isEmpty())openSql.accept(String.join("\n\n",result.suggestions()));});
        UiLanguage.text(status,UiLanguage.key("El plan estimado no ejecuta la consulta. Los costos no son tiempos medidos; el plan real solicita métricas al servidor."));
        actual.setEnabled(origin.profile().type()!=DatabaseType.SQLITE);
    }
    private void generate() {
        if(running||!origin.isOpen()||!LicenseGate.ensure(this))return;String query=sql.getText().trim();boolean run=actual.isSelected();
        try{QueryPlan.validate(origin.profile().type(),query,run);}catch(Exception error){status.setText(error.getMessage());return;}
        if(run&&!Confirmations.ask(this,"Plan real",origin.profile().name()+"\n"+tr("Se ejecutará el SELECT para medir su plan. Puede tardar y consumir recursos del servidor. ¿Continuar?")+"\n\n"+query.substring(0,Math.min(2000,query.length()))))return;
        running=true;control=new QueryPlan.Control();generate.setEnabled(false);cancel.setEnabled(true);actual.setEnabled(false);busy.accept(true);UiLanguage.text(status,UiLanguage.key("Obteniendo plan del servidor…"));
        new SwingWorker<QueryPlan.Result,Void>() {
            protected QueryPlan.Result doInBackground()throws Exception{return QueryPlan.capture(origin,query,run,control);}
            protected void done(){running=false;busy.accept(false);generate.setEnabled(origin.isOpen());cancel.setEnabled(false);actual.setEnabled(origin.profile().type()!=DatabaseType.SQLITE);try{setResult(get());}catch(Exception error){Throwable cause=error instanceof java.util.concurrent.ExecutionException?error.getCause():error;status.setText(cause.getMessage());}}
        }.execute();
    }
    private void setResult(QueryPlan.Result value){result=value;raw.setText(value.raw());raw.setCaretPosition(0);suggestions.setText(value.suggestions().isEmpty()?tr("El motor no incluyó recomendaciones de índices. Esto no garantiza que los índices existentes sean óptimos."):String.join("\n\n",value.suggestions()));graph.setPlan(value.root());operators.setRowCount(0);addOperator(value.root());askAi.setEnabled(aiHandler!=null);status.setText((value.actual()?tr("Plan real"):tr("Plan estimado"))+tr(" · Obtención del plan: ")+value.elapsedMillis()+" ms · "+value.suggestions().size()+tr(" sugerencias de índices. Selecciona un operador para ver sus métricas.")+(graph.truncated?tr(" Vista visual limitada a 2000 operadores; el documento original está completo."):""));}
    public void setAiHandler(BiConsumer<String,String> handler){aiHandler=handler;askAi.setEnabled(result!=null&&handler!=null);}
    private void addOperator(QueryPlan.Node node){
        Double cost=null,time=null;String rows="";
        for(var attr:node.attributes().entrySet()){
            String name=attr.getKey();if(Set.of("EstimateRows","Plan Rows","rows_examined_per_scan","rows").contains(name))rows=attr.getValue();
            try{if(Set.of("EstimatedTotalSubtreeCost","Total Cost","query_cost").contains(name))cost=Double.valueOf(attr.getValue());
                if(name.endsWith("ActualElapsedms")||Set.of("Actual Total Time","r_total_time_ms","Tiempo del iterador (ms, promedio por loop)").contains(name)){double value=Double.parseDouble(attr.getValue());time=time==null?value:Math.max(time,value);}}
            catch(NumberFormatException ignored){}
        }
        operators.addRow(new Object[]{node.title(),rows,cost,time});for(var child:node.children())addOperator(child);
    }
    private void save(){if(result==null)return;JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("plan."+result.extension()));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path target=chooser.getSelectedFile().toPath();if(Files.exists(target)&&JOptionPane.showConfirmDialog(this,tr("¿Reemplazar el archivo existente?"),tr("Guardar plan"),JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;try{Files.writeString(target,result.raw());}catch(Exception error){status.setText(error.getMessage());}}
    public boolean canClose(){if(running){UiLanguage.text(status,UiLanguage.key("Cancela el análisis o espera a que termine antes de cerrar."));return false;}return true;}
    public String rawPlan(){return result==null?"":result.raw();}
    private final class PlanGraph extends JComponent {
        private record Box(QueryPlan.Node node,Rectangle rect,Integer parent){}
        private final List<Box> boxes=new ArrayList<>();private int selected=-1;private boolean truncated;
        PlanGraph(){
            setName("execution-plan-graph");setOpaque(true);
            addMouseListener(new MouseAdapter(){
                public void mouseClicked(MouseEvent e){
                    for(int i=0;i<boxes.size();i++)if(boxes.get(i).rect().contains(e.getPoint())){
                        selected=i;properties.setRowCount(0);boxes.get(i).node().attributes().forEach((k,v)->properties.addRow(new Object[]{k,v}));repaint();return;
                    }
                }
            });
        }
        void setPlan(QueryPlan.Node root){boxes.clear();truncated=false;add(root,0,null);selected=-1;revalidate();repaint();}
        private void add(QueryPlan.Node node,int depth,Integer parent){if(boxes.size()>=2000){truncated=true;return;}int index=boxes.size();boxes.add(new Box(node,new Rectangle(24+depth*220,24+index*86,190,62),parent));for(var child:node.children())add(child,depth+1,index);}
        @Override public Dimension getPreferredSize(){return new Dimension(boxes.stream().mapToInt(b->b.rect().x+220).max().orElse(800),Math.max(300,boxes.size()*86+48));}
        @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{g.setColor(UIManager.getColor("Panel.background"));g.fillRect(0,0,getWidth(),getHeight());g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            for(Box box:boxes)if(box.parent()!=null){Rectangle a=boxes.get(box.parent()).rect(),b=box.rect();g.setColor(new Color(0x8B9DA7));int x=a.x+a.width+12;g.drawLine(a.x+a.width,a.y+a.height/2,x,a.y+a.height/2);g.drawLine(x,a.y+a.height/2,x,b.y+b.height/2);g.drawLine(x,b.y+b.height/2,b.x,b.y+b.height/2);g.fillPolygon(new int[]{b.x,b.x-6,b.x-6},new int[]{b.y+b.height/2,b.y+b.height/2-4,b.y+b.height/2+4},3);}
            for(int i=0;i<boxes.size();i++){Box box=boxes.get(i);Rectangle r=box.rect();g.setColor(i==selected?new Color(0xD9EDE9):new Color(0xF0F5FA));g.fillRoundRect(r.x,r.y,r.width,r.height,12,12);g.setColor(new Color(0x357B76));g.drawRoundRect(r.x,r.y,r.width,r.height,12,12);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,11));g.drawString(com.nexodb.reporting.ReportRenderer.fit(g,box.node().title(),r.width-16),r.x+8,r.y+20);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,10));String metric=box.node().attributes().entrySet().stream().filter(e->e.getKey().toLowerCase(Locale.ROOT).matches(".*(cost|rows|elapsed|time).*" )).map(e->e.getKey()+": "+e.getValue()).findFirst().orElse("Selecciona para ver propiedades");g.drawString(com.nexodb.reporting.ReportRenderer.fit(g,metric,r.width-16),r.x+8,r.y+43);}
        }finally{g.dispose();}}
    }
}
