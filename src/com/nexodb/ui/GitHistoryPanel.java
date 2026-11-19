package com.nexodb.ui;

import com.nexodb.tools.SqlProjectService;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/** Visual Git graph with colored branches, merge lines and commit details. */
final class GitHistoryPanel extends JPanel {
    @FunctionalInterface interface DetailsLoader{String load(String hash)throws Exception;}
    private static final int[] LIGHT={0x2563EB,0xDC2626,0x059669,0x7C3AED,0xD97706,0x0891B2,0xDB2777,0x4F46E5};
    private static final int[] DARK={0x60A5FA,0xF87171,0x34D399,0xC084FC,0xFBBF24,0x22D3EE,0xF472B6,0xA5B4FC};
    private final DefaultListModel<SqlProjectService.GitGraphLine> model=new DefaultListModel<>();
    private final JList<SqlProjectService.GitGraphLine> list=new JList<>(model);
    private final JTextArea details=new JTextArea();private final DetailsLoader loader;

    GitHistoryPanel(DetailsLoader loader){
        super(new BorderLayout());this.loader=loader;list.setCellRenderer(new HistoryRenderer());list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setVisibleRowCount(12);list.addListSelectionListener(e->{if(!e.getValueIsAdjusting())loadDetails(list.getSelectedValue());});
        details.setEditable(false);details.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));details.setRows(7);details.setLineWrap(false);details.setText("Selecciona un commit para ver sus archivos y estadísticas.");
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(list),new JScrollPane(details));split.setResizeWeight(.76);split.setDividerLocation(390);add(split);
    }
    void setHistory(List<SqlProjectService.GitGraphLine> lines){model.clear();for(SqlProjectService.GitGraphLine line:lines)model.addElement(line);details.setText(lines.isEmpty()?"Todavía no hay commits.":"Selecciona un commit para ver sus archivos y estadísticas.");if(!lines.isEmpty()){for(int i=0;i<lines.size();i++)if(lines.get(i).commit()!=null){list.setSelectedIndex(i);break;}}}
    int historySize(){return model.size();}
    private void loadDetails(SqlProjectService.GitGraphLine line){if(line==null||line.commit()==null)return;String hash=line.commit().hash();details.setText("Leyendo commit "+line.commit().shortHash()+"…");new SwingWorker<String,Void>(){protected String doInBackground()throws Exception{return loader.load(hash);}protected void done(){try{if(list.getSelectedValue()!=null&&list.getSelectedValue().commit()!=null&&hash.equals(list.getSelectedValue().commit().hash())){details.setText(get());details.setCaretPosition(0);}}catch(Exception error){details.setText("No se pudo leer el commit: "+root(error).getMessage());}}}.execute();}
    private static Throwable root(Throwable value){Throwable result=value;while(result.getCause()!=null&&result.getCause()!=result)result=result.getCause();return result;}
    private static Color lane(int index){int slot=Math.floorMod(index,LIGHT.length);return ThemeManager.color(LIGHT[slot],DARK[slot]);}
    private static String html(String value){return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}

    private final class HistoryRenderer extends JPanel implements ListCellRenderer<SqlProjectService.GitGraphLine>{
        private final Graph graph=new Graph();private final JLabel caption=new JLabel();private SqlProjectService.GitGraphLine value;
        HistoryRenderer(){super(new BorderLayout(8,0));setBorder(new EmptyBorder(3,4,3,8));caption.setOpaque(false);add(graph,BorderLayout.WEST);add(caption);}
        public Component getListCellRendererComponent(JList<? extends SqlProjectService.GitGraphLine> source,SqlProjectService.GitGraphLine item,int index,boolean selected,boolean focus){value=item;graph.value=item;SqlProjectService.GitCommit commit=item.commit();if(commit==null)caption.setText(" ");else{String refs=commit.refs().isBlank()?"":" &nbsp; <font color='#0f8b80'>["+html(commit.refs())+"]</font>";caption.setText("<html><b>"+html(commit.subject())+"</b>"+refs+"<br><font color='"+(ThemeManager.isDark()?"#B8C1CC":"#56616F")+"'>"+html(commit.shortHash())+" · "+html(commit.author())+" · "+html(commit.date())+"</font></html>");}Color background=selected?source.getSelectionBackground():source.getBackground();Color foreground=selected?source.getSelectionForeground():source.getForeground();setBackground(background);caption.setForeground(foreground);graph.setBackground(background);setToolTipText(commit==null?null:commit.hash());return this;}
    }
    private static final class Graph extends JComponent{
        private SqlProjectService.GitGraphLine value;Graph(){setOpaque(true);}
        public Dimension getPreferredSize(){String text=value==null?"":value.graph();return new Dimension(Math.max(30,14+text.length()*7),value!=null&&value.commit()!=null?38:16);}
        protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(value==null)return;Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);String text=value.graph();int h=getHeight(),step=7;g.setStroke(new BasicStroke(2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));for(int i=0;i<text.length();i++){char ch=text.charAt(i);int x=8+i*step,colorIndex=i/2;g.setColor(lane(colorIndex));switch(ch){case '|':g.drawLine(x,0,x,h);break;case '/':g.drawLine(x+step,0,x,h);break;case '\\':g.drawLine(x,0,x+step,h);break;case '-':case '_':g.drawLine(x-step,h/2,x+step,h/2);break;case '*':g.drawLine(x,0,x,h);g.fillOval(x-4,h/2-4,8,8);g.setColor(getBackground());g.fillOval(x-1,h/2-1,2,2);break;default:break;}}g.dispose();}
    }
}
