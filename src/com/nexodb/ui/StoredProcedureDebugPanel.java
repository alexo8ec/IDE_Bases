package com.nexodb.ui;

import com.nexodb.db.*;
import com.nexodb.debug.*;
import com.nexodb.model.ConnectionProfile;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultHighlighter;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import static com.nexodb.util.I18n.tr;

/** F9 toggles a real server breakpoint; F10 releases precisely one instrumented statement. */
public final class StoredProcedureDebugPanel extends JPanel {
    private final ConnectionProfile profile;private final char[] password;private final SchemaItem item;private final StoredObjectService.Definition definition;private final MysqlProcedure program;
    private final SqlEditorPane source=new SqlEditorPane();private final Set<Integer> breakpoints=new TreeSet<>();private int currentLine;private Object highlight;
    private final JLabel status=new JLabel(" ");private final JButton start=UiLanguage.button("Iniciar depuración"),resume=UiLanguage.button("Continuar (F5)"),step=UiLanguage.button("Paso sobre (F10)"),stop=UiLanguage.button("Detener (Shift+F5)");
    private final DefaultTableModel inputs=new DefaultTableModel(new String[]{"Nombre","Modo","Tipo","Valor","NULL"},0){public Class<?> getColumnClass(int c){return c==4?Boolean.class:String.class;}public boolean isCellEditable(int r,int c){return c>=3&&!"OUT".equals(getValueAt(r,1));}};
    private final DefaultTableModel variables=new DefaultTableModel(new String[]{"Variable","Tipo","Ámbito","Valor actual","Detalle"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable arguments=new JTable(inputs);private final JTabbedPane details=new JTabbedPane();private final JTextArea output=new JTextArea();private final Gutter gutter=new Gutter();
    private MysqlDebugSession debugger;private final Consumer<String> changed;
    private java.util.function.BiPredicate<String,String> confirm=(title,message)->Confirmations.ask(this,title,message);
    public StoredProcedureDebugPanel(ConnectionProfile profile,char[] password,SchemaItem item,StoredObjectService.Definition definition,Consumer<String> changed){
        super(new BorderLayout(6,6));this.profile=profile;this.password=password.clone();this.item=item;this.definition=definition;this.program=MysqlProcedure.compile(definition);this.changed=changed;
        setName("stored-procedure-debugger");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));source.restoreText(program.source());source.setEditable(false);source.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));
        JPanel top=new JPanel(new BorderLayout(5,5));JPanel bar=new JPanel(new com.nexodb.reporting.ui.WrapLayout());for(JButton button:List.of(start,resume,step,stop))bar.add(button);JButton toggle=UiLanguage.button("Punto de quiebre (F9)");bar.add(toggle);top.add(bar,BorderLayout.NORTH);
        JTextArea note=new JTextArea(tr("Depuración MySQL/MariaDB por copia temporal: se ejecuta SQL real y sus escrituras pueden persistir. Los puntos detienen antes de la instrucción. Requiere CREATE/DROP de tablas MEMORY y rutinas; el original se conserva. Las llamadas internas se ejecutan completas."));note.setRows(3);note.setEditable(false);note.setOpaque(false);note.setLineWrap(true);note.setWrapStyleWord(true);top.add(note);add(top,BorderLayout.NORTH);
        for(var p:program.parameters())inputs.addRow(new Object[]{p.name(),p.mode(),p.type(),"",p.mode().equals("OUT")});for(var v:program.sessionVariables())inputs.addRow(new Object[]{v.name(),"SESIÓN",v.type(),"",true});
        arguments.setName("debug-inputs");arguments.setRowHeight(26);UiLanguage.addTab(details,"Parámetros de entrada",null,new JScrollPane(arguments));JTable context=new JTable(variables);context.setName("debug-variables");context.setRowHeight(26);context.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);UiLanguage.addTab(details,"Variables en la pausa",null,new JScrollPane(context));output.setEditable(false);output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));UiLanguage.addTab(details,"Resultados de ejecución",null,new JScrollPane(output));
        var literal=new javax.swing.table.DefaultTableCellRenderer();literal.putClientProperty("html.disable",Boolean.TRUE);context.setDefaultRenderer(Object.class,literal);arguments.setDefaultRenderer(String.class,literal);
        JScrollPane code=new JScrollPane(source);code.setRowHeaderView(gutter);JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,code,details);split.setResizeWeight(.65);split.setDividerLocation(360);add(split);add(status,BorderLayout.SOUTH);
        start.addActionListener(e->start());resume.addActionListener(e->{if(debugger!=null)debugger.resume();});step.addActionListener(e->{if(debugger!=null)debugger.step();});stop.addActionListener(e->{if(debugger!=null){debugger.stop();stop.setEnabled(false);status.setText(tr("Deteniendo la ejecución y limpiando auxiliares…"));}});toggle.addActionListener(e->toggle(source.getDocument().getDefaultRootElement().getElementIndex(source.getCaretPosition())+1));
        bind("F9",()->toggle(source.getDocument().getDefaultRootElement().getElementIndex(source.getCaretPosition())+1));bind("F10",()->{if(debugger!=null)debugger.step();});bind("F5",()->{if(debugger!=null&&!debugger.finished())debugger.resume();else start();});bind("shift F5",()->{if(debugger!=null)debugger.stop();});updateButtons(false,false);status.setText(tr("Haz clic junto a una línea ejecutable para poner un punto de quiebre. La primera pausa es automática."));
    }
    private void bind(String key,Runnable action){getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key),key);getActionMap().put(key,new AbstractAction(){public void actionPerformed(ActionEvent e){action.run();}});}
    private void toggle(int line){if(!program.executableLines().contains(line)){status.setText(tr("Esta línea no tiene un punto ejecutable: elige una instrucción o END."));return;}if(!breakpoints.add(line))breakpoints.remove(line);if(debugger!=null&&!debugger.finished())debugger.setBreakpoints(breakpoints);gutter.repaint();}
    private void start(){
        if(debugger!=null&&!debugger.finished()||!LicenseGate.ensure(this))return;if(arguments.isEditing()&&!arguments.getCellEditor().stopCellEditing())return;
        String message=profile.name()+"\n"+StoredObjectService.qualified(item)+"\n"+tr("Se creará y ejecutará una copia temporal en dos conexiones independientes. Las instrucciones del SP pueden modificar datos realmente; Detener no revierte cambios ya confirmados. ¿Iniciar depuración?");
        if(!confirm.test("Depurar procedimiento",message))return;
        List<Object> args=new ArrayList<>();Map<String,Object> sessionValues=new LinkedHashMap<>();for(int i=0;i<inputs.getRowCount();i++){Object value=Boolean.TRUE.equals(inputs.getValueAt(i,4))?null:inputs.getValueAt(i,3);if(i<program.parameters().size())args.add(value);else sessionValues.put(inputs.getValueAt(i,0).toString(),value);}
        output.setText("");variables.setRowCount(0);updateButtons(true,false);try{debugger=new MysqlDebugSession(profile,password,item,definition,program,args,sessionValues,breakpoints,e->SwingUtilities.invokeLater(()->event(e)));debugger.start();}catch(RuntimeException error){updateButtons(false,false);status.setText(error.getMessage());}
    }
    private void event(MysqlDebugSession.Event event){
        boolean paused=event.state()==MysqlDebugSession.State.PAUSED,done=Set.of(MysqlDebugSession.State.COMPLETED,MysqlDebugSession.State.CANCELLED,MysqlDebugSession.State.FAILED).contains(event.state());updateButtons(!done,paused);currentLine=event.line();variables.setRowCount(0);for(var v:event.variables())variables.addRow(new Object[]{v.name(),v.type(),v.scope(),v.isNull()?"(NULL)":v.value(),v.truncated()?tr("Vista previa: primeros 4096 caracteres"):""});status.setText(event.message());changed.accept(event.message());
        if(highlight!=null)source.getHighlighter().removeHighlight(highlight);highlight=null;
        if(paused){details.setSelectedIndex(1);var root=source.getDocument().getDefaultRootElement();if(currentLine>0&&currentLine<=root.getElementCount())try{var point=program.points().stream().filter(p->p.id()==event.point()).findFirst().orElseThrow();int at=point.offset(),end=Math.min(root.getElement(currentLine-1).getEndOffset(),program.points().stream().filter(p->p.offset()>at).mapToInt(MysqlProcedure.Point::offset).min().orElse(source.getDocument().getLength()));highlight=source.getHighlighter().addHighlight(at,Math.min(end,source.getDocument().getLength()),new DefaultHighlighter.DefaultHighlightPainter(new Color(0xFFE6A4)));source.setCaretPosition(at);}catch(Exception ignored){}}
        if(done){StringBuilder text=new StringBuilder();int n=0;for(QueryResult result:event.results()){text.append("Resultado ").append(++n).append("\n").append(String.join("\t",result.columns())).append('\n');for(var row:result.rows())text.append(String.join("\t",row.stream().map(v->Objects.toString(v,"NULL")).toList())).append('\n');if(result.truncated())text.append("[Límite de visualización: 10.000 filas]\n");text.append('\n');}output.setText(text.toString());}
        gutter.repaint();
    }
    private void updateButtons(boolean running,boolean paused){start.setEnabled(!running);resume.setEnabled(paused);step.setEnabled(paused);stop.setEnabled(running);arguments.setEnabled(!running);}
    public boolean canClose(){if(debugger!=null&&!debugger.finished()){status.setText(tr("Detén la depuración y espera la limpieza antes de cerrar."));return false;}return true;}
    public void dispose(){if(debugger!=null)debugger.close();Arrays.fill(password,'\0');}
    private final class Gutter extends JComponent {
        Gutter(){setPreferredSize(new Dimension(65,1));setToolTipText(tr("Clic: punto de quiebre. F9: alternar en la línea del cursor."));addMouseListener(new MouseAdapter(){public void mousePressed(MouseEvent e){int offset=source.viewToModel2D(new Point(0,e.getY()));toggle(source.getDocument().getDefaultRootElement().getElementIndex(offset)+1);}});}
        protected void paintComponent(Graphics graphics){graphics.setColor(ThemeManager.color(0xF1F5F9,0x30343B));graphics.fillRect(0,0,getWidth(),getHeight());graphics.setFont(source.getFont());var root=source.getDocument().getDefaultRootElement();Rectangle clip=graphics.getClipBounds();for(int i=0;i<root.getElementCount();i++)try{var rect=source.modelToView2D(root.getElement(i).getStartOffset());if(rect==null||rect.getY()+rect.getHeight()<clip.y||rect.getY()>clip.y+clip.height)continue;int y=(int)rect.getY(),line=i+1;if(program.executableLines().contains(line)){graphics.setColor(breakpoints.contains(line)?new Color(0xD3303E):new Color(0xA5B7BD));graphics.fillOval(6,y+4,10,10);}if(line==currentLine&&debugger!=null&&debugger.state()==MysqlDebugSession.State.PAUSED){graphics.setColor(new Color(0xD89100));graphics.fillPolygon(new int[]{20,28,20},new int[]{y+4,y+9,y+14},3);}graphics.setColor(ThemeManager.color(0x64748B,0xBAC5D0));String text=""+line;graphics.drawString(text,getWidth()-6-graphics.getFontMetrics().stringWidth(text),y+graphics.getFontMetrics().getAscent());}catch(Exception ignored){}}
    }
}
