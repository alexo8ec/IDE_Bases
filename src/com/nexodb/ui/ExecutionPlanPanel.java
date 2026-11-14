package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.model.DatabaseType;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
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
    private final JTextArea raw=new JTextArea(),suggestions=new JTextArea(),diagnostics=new JTextArea();
    private final DefaultTableModel properties=new DefaultTableModel(new String[]{"Propiedad","Valor"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final DefaultTableModel operators=new DefaultTableModel(new String[]{"Operador","Filas estimadas / ejecución","Costo estimado (unidades)","Tiempo reportado (ms)","Tabla","Índice utilizado","Forma de acceso","Filas reales totales","Filas leídas totales","Ejecuciones / loops","CPU total (ms)","Alcance del tiempo","Diagnóstico"},0){public boolean isCellEditable(int r,int c){return false;}public Class<?> getColumnClass(int c){return Set.of(1,2,3,7,8,9,10).contains(c)?Double.class:String.class;}};
    private final List<QueryPlan.Node> operatorNodes=new ArrayList<>();
    private final PlanGraph graph=new PlanGraph();
    private final JLabel status=new JLabel(" ");
    private final JLabel summary=new JLabel(" ");
    private final JButton askAi=UiLanguage.button("Analizar con IA");
    private BiConsumer<String,String> aiHandler;
    private QueryPlan.Result result;private QueryPlan.Control control;private boolean running;
    public ExecutionPlanPanel(JdbcSession origin,String input,Consumer<Boolean> busy,Consumer<String> openSql) {
        super(new BorderLayout(6,6));this.origin=origin;this.busy=busy;this.openSql=openSql;setName("execution-plan");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));sql.restoreText(input);sql.setPreferredSize(new Dimension(400,90));
        JPanel top=new JPanel(new BorderLayout());JPanel toolbar=new JPanel(new com.nexodb.reporting.ui.WrapLayout());toolbar.add(generate);toolbar.add(actual);toolbar.add(cancel);JButton save=UiLanguage.button("Guardar plan…");toolbar.add(save);JButton open=UiLanguage.button("Abrir sugerencias SQL");toolbar.add(open);toolbar.add(askAi);askAi.setEnabled(false);askAi.addActionListener(e->{if(result!=null&&aiHandler!=null)aiHandler.accept(sql.getText(),result.raw());});top.add(toolbar,BorderLayout.NORTH);top.add(new JScrollPane(sql));add(top,BorderLayout.NORTH);
        raw.setEditable(false);raw.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));suggestions.setEditable(false);suggestions.setFont(raw.getFont());diagnostics.setEditable(false);diagnostics.setLineWrap(true);diagnostics.setWrapStyleWord(true);diagnostics.setName("execution-plan-diagnostics");diagnostics.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));summary.setName("execution-plan-summary");top.add(summary,BorderLayout.SOUTH);
        JTabbedPane tabs=new JTabbedPane();JSplitPane visual=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(graph),new JScrollPane(new JTable(properties)));visual.setResizeWeight(.8);visual.setDividerLocation(340);UiLanguage.addTab(tabs,"Plan visual",null,visual);
        JTable operatorTable=new JTable(operators){@Override public String getToolTipText(MouseEvent e){int row=rowAtPoint(e.getPoint()),col=columnAtPoint(e.getPoint());return row<0||col<0?null:Objects.toString(getValueAt(row,col),null);}};operatorTable.setAutoCreateRowSorter(true);operatorTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);operatorTable.setName("execution-plan-operators");for(int i=0;i<operators.getColumnCount();i++)operatorTable.getColumnModel().getColumn(i).setPreferredWidth(i==0?250:i==11||i==12?420:170);operatorTable.getSelectionModel().addListSelectionListener(e->{int row=operatorTable.getSelectedRow();if(row>=0&&row<operatorNodes.size())graph.select(operatorNodes.get(operatorTable.convertRowIndexToModel(row)));});JPanel operatorPanel=new JPanel(new BorderLayout());operatorPanel.add(new JScrollPane(operatorTable));operatorPanel.add(UiLanguage.label("Los tiempos pueden incluir hijos o promedios por loop; no deben sumarse."),BorderLayout.SOUTH);UiLanguage.addTab(tabs,"Operadores y métricas",null,operatorPanel);
        UiLanguage.addTab(tabs,"XML / JSON / Texto",null,new JScrollPane(raw));UiLanguage.addTab(tabs,"Sugerencias de índices",null,new JScrollPane(suggestions));UiLanguage.addTab(tabs,"Diagnóstico de rendimiento",null,new JScrollPane(diagnostics));add(tabs);add(status,BorderLayout.SOUTH);
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
    private void setResult(QueryPlan.Result value){
        result=value;raw.setText(value.raw());raw.setCaretPosition(0);suggestions.setText(value.suggestions().isEmpty()?tr("El motor no incluyó recomendaciones de índices. Esto no garantiza que los índices existentes sean óptimos."):String.join("\n\n",value.suggestions()));suggestions.setCaretPosition(0);
        var findings=PlanDiagnostics.findings(value.root());long high=findings.stream().filter(n->"Alta".equals(n.attributes().get(PlanDiagnostics.LEVEL))).count();
        String timing=value.actual()?tr("Tiempos reales por operador cuando el motor los reporta."):tr("Plan estimado: no hay tiempos medidos. Activa Plan real para medir.");
        boolean measured=hasTime(value.root());if(value.actual()&&!measured)timing=tr("El motor no reportó tiempos por operador; no se pueden deducir de los costos.");
        var queryTimes=PlanDiagnostics.queryTimes(value.root());String queryTiming=queryTimes.size()==1?tr("Tiempo de ejecución reportado: ")+queryTimes.get(0)+" ms · ":"";
        summary.setText("<html>"+queryTiming+timing+"<br>"+high+tr(" alertas de carga alta · ")+value.suggestions().size()+tr(" sugerencias del optimizador")+"</html>");summary.setToolTipText(summary.getText());summary.setForeground(high>0?new Color(0xB34713):UIManager.getColor("Label.foreground"));
        StringBuilder report=new StringBuilder(timing).append('\n').append(tr("La obtención del plan incluye comunicación y lectura del plan; no es el tiempo de ejecución de la consulta.")).append("\n\n");
        if(findings.isEmpty())report.append(tr("No se detectaron señales de carga alta con las métricas disponibles. Esto no garantiza un plan óptimo."));
        for(var node:findings){var a=node.attributes();report.append(a.get(PlanDiagnostics.LEVEL)).append(" · ").append(node.title()).append('\n');for(String key:List.of(PlanDiagnostics.TABLE,PlanDiagnostics.INDEX,PlanDiagnostics.TIME,PlanDiagnostics.SCOPE,PlanDiagnostics.ROWS,PlanDiagnostics.READ,PlanDiagnostics.ESTIMATED_READ))if(a.containsKey(key))report.append(key).append(": ").append(a.get(key)).append('\n');report.append(a.get(PlanDiagnostics.WARNING)).append("\n\n");}
        if(!value.suggestions().isEmpty())report.append(tr("El optimizador reportó posibles índices faltantes. Revisa el SQL en Sugerencias de índices; el impacto es estimado y no garantiza una reducción equivalente del tiempo."));
        diagnostics.setText(report.toString());diagnostics.setCaretPosition(0);graph.setPlan(value.root());operators.setRowCount(0);operatorNodes.clear();addOperator(value.root());
        graph.select(findings.stream().filter(n->"Alta".equals(n.attributes().get(PlanDiagnostics.LEVEL))).findFirst().orElse(value.root().children().isEmpty()?value.root():value.root().children().get(0)));
        askAi.setEnabled(aiHandler!=null);status.setText((value.actual()?tr("Plan real"):tr("Plan estimado"))+tr(" · Obtención del plan: ")+value.elapsedMillis()+" ms · "+high+tr(" alertas de carga alta. Selecciona un operador para ver sus métricas.")+(graph.truncated?tr(" Vista visual limitada a 2000 operadores; el documento original está completo."):""));
    }
    private boolean hasTime(QueryPlan.Node node){return node.attributes().containsKey(PlanDiagnostics.TIME)||node.children().stream().anyMatch(this::hasTime);}
    public void setAiHandler(BiConsumer<String,String> handler){aiHandler=handler;askAi.setEnabled(result!=null&&handler!=null);}
    private void addOperator(QueryPlan.Node node){
        var a=node.attributes();operatorNodes.add(node);
        operators.addRow(new Object[]{node.title(),PlanDiagnostics.value(node,PlanDiagnostics.ESTIMATED),PlanDiagnostics.value(node,PlanDiagnostics.COST),PlanDiagnostics.value(node,PlanDiagnostics.TIME),a.get(PlanDiagnostics.TABLE),a.get(PlanDiagnostics.INDEX),a.get(PlanDiagnostics.ACCESS),PlanDiagnostics.value(node,PlanDiagnostics.ROWS),PlanDiagnostics.value(node,PlanDiagnostics.READ),PlanDiagnostics.value(node,PlanDiagnostics.LOOPS),PlanDiagnostics.value(node,PlanDiagnostics.CPU),a.get(PlanDiagnostics.SCOPE),a.get(PlanDiagnostics.WARNING)});for(var child:node.children())addOperator(child);
    }
    private void save(){if(result==null)return;JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("plan."+result.extension()));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path target=chooser.getSelectedFile().toPath();if(Files.exists(target)&&JOptionPane.showConfirmDialog(this,tr("¿Reemplazar el archivo existente?"),tr("Guardar plan"),JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;try{Files.writeString(target,result.raw());}catch(Exception error){status.setText(error.getMessage());}}
    public boolean canClose(){if(running){UiLanguage.text(status,UiLanguage.key("Cancela el análisis o espera a que termine antes de cerrar."));return false;}return true;}
    public String rawPlan(){return result==null?"":result.raw();}
    private final class PlanGraph extends JComponent {
        private record Box(QueryPlan.Node node,Rectangle rect,Integer parent){}
        private final List<Box> boxes=new ArrayList<>();private int selected=-1;private boolean truncated;
        PlanGraph(){
            setName("execution-plan-graph");setOpaque(true);setToolTipText("");
            addMouseListener(new MouseAdapter(){
                public void mouseClicked(MouseEvent e){
                    for(int i=0;i<boxes.size();i++)if(boxes.get(i).rect().contains(e.getPoint())){
                        select(boxes.get(i).node());return;
                    }
                }
            });
        }
        @Override public String getToolTipText(MouseEvent e){for(Box box:boxes)if(box.rect().contains(e.getPoint())){var a=box.node().attributes();return box.node().title()+" · "+a.getOrDefault(PlanDiagnostics.SCOPE,"")+" "+a.getOrDefault(PlanDiagnostics.WARNING,"");}return null;}
        void setPlan(QueryPlan.Node root){boxes.clear();truncated=false;add(root,0,null,0);selected=-1;revalidate();repaint();}
        void select(QueryPlan.Node node){
            selected=-1;for(int i=0;i<boxes.size();i++)if(boxes.get(i).node()==node){selected=i;break;}
            properties.setRowCount(0);var attrs=node.attributes();
            var priority=List.of(PlanDiagnostics.LEVEL,PlanDiagnostics.TABLE,PlanDiagnostics.INDEX,PlanDiagnostics.ACCESS,PlanDiagnostics.QUERY_TIME,PlanDiagnostics.TIME,PlanDiagnostics.SCOPE,PlanDiagnostics.CPU,PlanDiagnostics.LOOPS,PlanDiagnostics.ESTIMATED,PlanDiagnostics.ESTIMATED_READ,PlanDiagnostics.ROWS,PlanDiagnostics.READ,PlanDiagnostics.WARNING);
            for(String key:priority)if(attrs.containsKey(key))properties.addRow(new Object[]{key,attrs.get(key)});
            attrs.forEach((k,v)->{if(!priority.contains(k))properties.addRow(new Object[]{k,v});});repaint();
        }
        private int add(QueryPlan.Node node,int depth,Integer parent,int row){if(boxes.size()>=2000){truncated=true;return row;}int index=boxes.size();Rectangle rect=new Rectangle(24+depth*330,24+row*146,300,126);boxes.add(new Box(node,rect,parent));int next=row;for(var child:node.children())next=add(child,depth+1,index,next);int end=Math.max(row+1,next);rect.y=24+(row+end-1)*73;return end;}
        @Override public Dimension getPreferredSize(){return new Dimension(boxes.stream().mapToInt(b->b.rect().x+330).max().orElse(800),Math.max(300,boxes.stream().mapToInt(b->b.rect().y+b.rect().height+24).max().orElse(300)));}
        @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{g.setColor(UIManager.getColor("Panel.background"));g.fillRect(0,0,getWidth(),getHeight());g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            for(Box box:boxes)if(box.parent()!=null){Rectangle a=boxes.get(box.parent()).rect(),b=box.rect();g.setColor(new Color(0x8B9DA7));int x=a.x+a.width+12;g.drawLine(a.x+a.width,a.y+a.height/2,x,a.y+a.height/2);g.drawLine(x,a.y+a.height/2,x,b.y+b.height/2);g.drawLine(x,b.y+b.height/2,b.x,b.y+b.height/2);g.fillPolygon(new int[]{b.x,b.x-6,b.x-6},new int[]{b.y+b.height/2,b.y+b.height/2-4,b.y+b.height/2+4},3);}
            for(int i=0;i<boxes.size();i++){Box box=boxes.get(i);Rectangle r=box.rect();var a=box.node().attributes();boolean high="Alta".equals(a.get(PlanDiagnostics.LEVEL));g.setColor(i==selected?new Color(0xD9EDE9):high?new Color(0xFFF0DE):new Color(0xF0F5FA));g.fillRoundRect(r.x,r.y,r.width,r.height,12,12);g.setColor(high?new Color(0xA84A12):new Color(0x357B76));g.drawRoundRect(r.x,r.y,r.width,r.height,12,12);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,11));g.drawString(com.nexodb.reporting.ReportRenderer.fit(g,(high?"⚠ ":"")+box.node().title(),r.width-16),r.x+8,r.y+20);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,10));
                List<String> lines=new ArrayList<>();String unit=a.getOrDefault(PlanDiagnostics.SCOPE,"").startsWith("Promedio")?" ms/loop":" ms";lines.add(a.containsKey(PlanDiagnostics.TIME)?tr("Tiempo: ")+a.get(PlanDiagnostics.TIME)+unit+" · "+a.getOrDefault(PlanDiagnostics.LOOPS,"?")+tr(" ejecuciones"):tr("Tiempo: no reportado"));
                lines.add(tr("Índice: ")+a.getOrDefault(PlanDiagnostics.INDEX,a.containsKey(PlanDiagnostics.TABLE)?tr("no reportado"):"—"));
                lines.add(tr("Filas reales: ")+a.getOrDefault(PlanDiagnostics.ROWS,"—")+tr(" · Leídas: ")+a.getOrDefault(PlanDiagnostics.READ,"—"));
                lines.add(tr("Filas estimadas: ")+a.getOrDefault(PlanDiagnostics.ESTIMATED,"—")+tr(" · Costo: ")+a.getOrDefault(PlanDiagnostics.COST,"—"));
                lines.add(high?tr("Carga alta · Ver diagnóstico"):a.getOrDefault(PlanDiagnostics.ACCESS,tr("Selecciona para ver propiedades")));
                for(int line=0;line<lines.size();line++)g.drawString(com.nexodb.reporting.ReportRenderer.fit(g,lines.get(line),r.width-16),r.x+8,r.y+40+line*17);
            }
        }finally{g.dispose();}}
    }
}
