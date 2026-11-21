package com.nexodb.ui;

import com.nexodb.tools.SqlProjectService;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;

/** Structured Git history with a colored branch graph, relative dates and commit details. */
final class GitHistoryPanel extends JPanel {
    @FunctionalInterface interface DetailsLoader{String load(String hash)throws Exception;}
    private static final int GRAPH_WIDTH=150,DATE_WIDTH=115,AUTHOR_WIDTH=170,REFS_WIDTH=230;
    private static final int[] LIGHT={0x16A36A,0x2589D8,0xE05260,0x7C59C7,0xD38B20,0x0C91A6,0xD04A91,0x5667D8};
    private static final int[] DARK={0x45D99A,0x58B7F2,0xFA7B86,0xB994F4,0xF1B852,0x4ECBDD,0xEE83BE,0x8E9CF2};
    private final DefaultListModel<SqlProjectService.GitGraphLine> model=new DefaultListModel<>();
    private final JList<SqlProjectService.GitGraphLine> list=new JList<>(model);
    private final JTextArea details=new JTextArea();private final DetailsLoader loader;

    GitHistoryPanel(DetailsLoader loader){
        super(new BorderLayout());this.loader=loader;list.setCellRenderer(new HistoryRenderer());list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setVisibleRowCount(12);list.addListSelectionListener(e->{if(!e.getValueIsAdjusting())loadDetails(list.getSelectedValue());});
        JPanel timeline=new JPanel(new BorderLayout());timeline.add(new HistoryHeader(),BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(list);scroll.setBorder(BorderFactory.createEmptyBorder());timeline.add(scroll);
        details.setEditable(false);details.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));details.setRows(7);details.setLineWrap(false);details.setText("Selecciona un commit para ver sus archivos y estadísticas.");
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,timeline,new JScrollPane(details));split.setResizeWeight(.72);split.setDividerLocation(410);add(split);
    }
    void setHistory(List<SqlProjectService.GitGraphLine> lines){model.clear();for(SqlProjectService.GitGraphLine line:lines)model.addElement(line);details.setText(lines.isEmpty()?"Todavía no hay commits.":"Selecciona un commit para ver sus archivos y estadísticas.");if(!lines.isEmpty()){for(int i=0;i<lines.size();i++)if(lines.get(i).commit()!=null){list.setSelectedIndex(i);break;}}}
    int historySize(){return model.size();}
    private void loadDetails(SqlProjectService.GitGraphLine line){if(line==null||line.commit()==null)return;String hash=line.commit().hash();details.setText("Leyendo commit "+line.commit().shortHash()+"…");new SwingWorker<String,Void>(){protected String doInBackground()throws Exception{return loader.load(hash);}protected void done(){try{if(list.getSelectedValue()!=null&&list.getSelectedValue().commit()!=null&&hash.equals(list.getSelectedValue().commit().hash())){details.setText(get());details.setCaretPosition(0);}}catch(Exception error){details.setText("No se pudo leer el commit: "+root(error).getMessage());}}}.execute();}
    private static Throwable root(Throwable value){Throwable result=value;while(result.getCause()!=null&&result.getCause()!=result)result=result.getCause();return result;}
    private static Color lane(int index){int slot=Math.floorMod(index,LIGHT.length);return ThemeManager.color(LIGHT[slot],DARK[slot]);}
    private static String html(String value){return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static String relativeDate(String value,Instant now){
        try{Instant date=OffsetDateTime.parse(value).toInstant();long seconds=Duration.between(date,now).getSeconds();if(seconds<0)return exactDate(date);if(seconds<60)return "ahora";if(seconds<3600)return "hace "+seconds/60+" min";if(seconds<86_400)return "hace "+seconds/3600+" h";if(seconds<604_800)return "hace "+seconds/86_400+(seconds/86_400==1?" día":" días");return exactDate(date);}catch(Exception ignored){return value;}
    }
    private static String exactDate(Instant value){return DateTimeFormatter.ofPattern("dd MMM yyyy",new Locale("es","ES")).withZone(ZoneId.systemDefault()).format(value);}

    private static final class HistoryHeader extends JPanel{
        HistoryHeader(){super(new BorderLayout());setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,ThemeManager.color(0xD8DEE6,0x59616B)),new EmptyBorder(8,8,8,8)));JLabel graph=header("Ramas",GRAPH_WIDTH),date=header("Fecha",DATE_WIDTH),author=header("Autor",AUTHOR_WIDTH),message=header("Mensaje",0),refs=header("Ramas y etiquetas",REFS_WIDTH);add(graph,BorderLayout.WEST);JPanel columns=new JPanel(new BorderLayout());columns.setOpaque(false);columns.add(date,BorderLayout.WEST);JPanel center=new JPanel(new BorderLayout());center.setOpaque(false);center.add(author,BorderLayout.WEST);center.add(message);center.add(refs,BorderLayout.EAST);columns.add(center);add(columns);}
        private static JLabel header(String text,int width){JLabel label=new JLabel(text);label.setFont(label.getFont().deriveFont(Font.BOLD));if(width>0){Dimension size=new Dimension(width,22);label.setPreferredSize(size);label.setMinimumSize(size);}return label;}
    }

    private final class HistoryRenderer extends JPanel implements ListCellRenderer<SqlProjectService.GitGraphLine>{
        private final Graph graph=new Graph();private final JLabel date=new JLabel(),author=new JLabel(),message=new JLabel();private final RefBadges refs=new RefBadges();
        HistoryRenderer(){super(new BorderLayout());setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,ThemeManager.color(0xE2E7ED,0x4B535C)),new EmptyBorder(4,8,4,8)));graph.setPreferredSize(new Dimension(GRAPH_WIDTH,46));date.setPreferredSize(new Dimension(DATE_WIDTH,46));author.setPreferredSize(new Dimension(AUTHOR_WIDTH,46));author.setIconTextGap(8);refs.setPreferredSize(new Dimension(REFS_WIDTH,46));add(graph,BorderLayout.WEST);JPanel columns=new JPanel(new BorderLayout());columns.setOpaque(false);columns.add(date,BorderLayout.WEST);JPanel center=new JPanel(new BorderLayout());center.setOpaque(false);center.add(author,BorderLayout.WEST);center.add(message);center.add(refs,BorderLayout.EAST);columns.add(center);add(columns);}
        public Component getListCellRendererComponent(JList<? extends SqlProjectService.GitGraphLine> source,SqlProjectService.GitGraphLine item,int index,boolean selected,boolean focus){graph.value=item;SqlProjectService.GitCommit commit=item.commit();if(commit==null){date.setText("");author.setText("");author.setIcon(null);message.setText("");refs.value="";}else{date.setText(relativeDate(commit.date(),Instant.now()));date.setToolTipText(commit.date());author.setText("<html>"+html(commit.author())+"</html>");author.setIcon(new AvatarIcon(commit.author()));String merge=commit.parents().size()>1?"<font color='#8B929A'>[M]</font> ":"";message.setText("<html>"+merge+"<b>"+html(commit.subject())+"</b><br><font color='"+(ThemeManager.isDark()?"#B8C1CC":"#67717E")+"'>"+html(commit.shortHash())+"</font></html>");refs.value=commit.refs();}Color background=selected?source.getSelectionBackground():source.getBackground(),foreground=selected?source.getSelectionForeground():source.getForeground();setBackground(background);date.setForeground(foreground);author.setForeground(foreground);message.setForeground(foreground);graph.setBackground(background);refs.setBackground(background);setToolTipText(commit==null?null:commit.hash());return this;}
    }
    private static final class AvatarIcon implements Icon{
        private final String text;private final Color color;AvatarIcon(String author){String[] words=Objects.toString(author,"").trim().split("\\s+");text=words.length==0||words[0].isBlank()?"?":(""+words[0].charAt(0)+(words.length>1?words[words.length-1].charAt(0):' ')).trim().toUpperCase(Locale.ROOT);color=lane(Objects.toString(author,"").hashCode());}
        public int getIconWidth(){return 26;}public int getIconHeight(){return 26;}public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(color);g.fillOval(x,y,25,25);g.setColor(Color.WHITE);g.setFont(c.getFont().deriveFont(Font.BOLD,10f));FontMetrics fm=g.getFontMetrics();g.drawString(text,x+(25-fm.stringWidth(text))/2,y+(25-fm.getHeight())/2+fm.getAscent());g.dispose();}
    }
    private static final class RefBadges extends JComponent{
        private String value="";RefBadges(){setOpaque(true);}protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(value==null||value.isBlank())return;Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setFont(getFont().deriveFont(Font.BOLD,11f));int x=4,y=(getHeight()-23)/2,index=0;String[] tokens=value.split(",\\s*");for(String raw:tokens){String token=raw.replace("HEAD -> ","").replace("tag: ","").trim();if(token.isBlank())continue;if(index==2)token="+"+(tokens.length-index);FontMetrics fm=g.getFontMetrics();int width=Math.min(REFS_WIDTH-x-6,fm.stringWidth(token)+18);if(width<25)break;g.setColor(lane(index+1));g.fillRoundRect(x,y,width,23,12,12);g.setColor(Color.WHITE);String shown=token;while(fm.stringWidth(shown)>width-16&&shown.length()>2)shown=shown.substring(0,shown.length()-2)+"…";g.drawString(shown,x+9,y+16);x+=width+6;index++;if(index>=3||x>=REFS_WIDTH-20)break;}g.dispose();}
    }
    private static final class Graph extends JComponent{
        private SqlProjectService.GitGraphLine value;Graph(){setOpaque(true);}protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(value==null)return;Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);String text=value.graph();int h=getHeight(),step=10;g.setStroke(new BasicStroke(2.4f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));for(int i=0;i<text.length();i++){char ch=text.charAt(i);int x=12+i*step,colorIndex=i/2;g.setColor(lane(colorIndex));switch(ch){case '|':g.drawLine(x,0,x,h);break;case '/':g.drawLine(x+step,0,x,h);break;case '\\':g.drawLine(x,0,x+step,h);break;case '-':case '_':g.drawLine(x-step,h/2,x+step,h/2);break;case '*':g.drawLine(x,0,x,h);g.fillOval(x-5,h/2-5,10,10);g.setColor(getBackground());g.fillOval(x-2,h/2-2,4,4);break;default:break;}}g.dispose();}
    }
}
