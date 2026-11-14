package com.nexodb.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.util.I18n;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Refuses to change any database outside this repository's explicitly isolated test server. */
public final class TableDesignIntegrationTest {
    private static final String DB="nexodb_design_fixture";
    public static void main(String[] args)throws Exception{
        if(!Boolean.getBoolean("nexodb.test.design"))throw new IllegalStateException("Explicit isolated-server opt-in required");
        System.setProperty("nexodb.settingsFile",Files.createTempDirectory("nexodb-design-ui-").resolve("settings.properties").toString());
        System.setProperty("nexodb.disableDraftPersistence","true");FlatLightLaf.setup();I18n.initialize("es");
        ConnectionProfile profile=new ConnectionProfile("design-test","Isolated design test",DatabaseType.MARIADB,"127.0.0.1",43181,"","root","jdbc:mariadb://127.0.0.1:43181/?sslMode=disable","org.mariadb.jdbc.Driver",Path.of("build/drivers/mariadb-java-client-4.0.0.jar").toAbsolutePath().toString(),false);
        try(JdbcSession session=JdbcSession.open(profile,new char[0])){
            try(Statement s=session.connection().createStatement();ResultSet r=s.executeQuery("SELECT @@datadir")){r.next();equal(Path.of("build/design-test-data").toRealPath(),Path.of(r.getString(1)).toRealPath());}
            Path fixture=Path.of("build/design-test-data",DB);if(Files.isDirectory(fixture))Files.setAttribute(fixture,"dos:readonly",false);
            exec(session,"DROP DATABASE IF EXISTS "+DB);exec(session,"CREATE DATABASE "+DB+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");session.connection().setCatalog(DB);
            exec(session,"CREATE TABLE parent(tenant INT,id INT,PRIMARY KEY(tenant,id)) ENGINE=InnoDB");
            String referenceDb=DB+"_ref";Path referenceDir=Path.of("build/design-test-data",referenceDb);if(Files.isDirectory(referenceDir))Files.setAttribute(referenceDir,"dos:readonly",false);exec(session,"DROP DATABASE IF EXISTS "+referenceDb);exec(session,"CREATE DATABASE "+referenceDb);exec(session,"CREATE TABLE "+referenceDb+".remote_parent(tenant INT,id INT,PRIMARY KEY(tenant,id)) ENGINE=InnoDB");
            exec(session,"CREATE TABLE child(id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant INT,parent_id INT,txt VARCHAR(80) DEFAULT 'it''s \\\\ test' COMMENT 'original',amount DECIMAL(12,2) DEFAULT 1.25,stamp TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,calculated INT GENERATED ALWAYS AS(amount+1) STORED,choice ENUM('a,b','c') DEFAULT 'a,b',KEY idx_txt(txt(8) DESC) COMMENT 'prefix',UNIQUE KEY uk_parent(tenant,parent_id)) ENGINE=InnoDB AUTO_INCREMENT=123 COMMENT='original table' ROW_FORMAT=DYNAMIC");
            var initial=MysqlTableDesign.read(session,DB,"child");
            equal("",plan(initial,initial.columns(),initial.indexes(),initial.keys(),initial.options()));
            var text=initial.columns().stream().filter(c->c.name().equals("txt")).findFirst().orElseThrow();equal(true,text.defaultSql().contains("it''s"));
            equal("'a,b'",initial.columns().stream().filter(c->c.name().equals("choice")).findFirst().orElseThrow().defaultSql());
            List<MysqlTableDesign.Column> columns=new ArrayList<>(initial.columns());int pos=columns.indexOf(text);columns.set(pos,comment(text,"changed á ' \\"));
            var composite=new ForeignKeyDefinition(DB,"child","fk_child_parent",DB,"parent",List.of(new ForeignKeyDefinition.ColumnPair("tenant","tenant"),new ForeignKeyDefinition.ColumnPair("parent_id","id")),"CASCADE","RESTRICT");
            List<MysqlTableDesign.Index> indexes=new ArrayList<>(initial.indexes());indexes.add(new MysqlTableDesign.Index("idx_amount","INDEX",List.of(new MysqlTableDesign.IndexColumn("amount","","DESC")),"BTREE","new index",false,""));
            var old=initial.options();var options=new MysqlTableDesign.Options(old.engine(),old.charset(),old.collation(),"new comment ' \\ á",old.autoIncrement(),old.rowFormat(),old.averageRowLength(),old.maxRows(),old.minRows(),old.checksum(),old.delayKeyWrite());
            String sql=plan(initial,columns,indexes,List.of(composite),options);equal(1L,sql.chars().filter(c->c==';').count());MysqlTableDesign.apply(session,initial,sql);
            var after=MysqlTableDesign.read(session,DB,"child");equal("new comment ' \\ á",after.options().comment());equal(text.defaultSql(),after.columns().get(pos).defaultSql());equal("changed á ' \\" ,after.columns().get(pos).comment());equal(1,after.keys().size());equal(2,after.keys().get(0).columns().size());equal("CASCADE",after.keys().get(0).onDelete());equal(4,after.indexes().size());equal("",plan(after,after.columns(),after.indexes(),after.keys(),after.options()));
            exec(session,"INSERT INTO parent VALUES(1,2)");exec(session,"INSERT INTO child(tenant,parent_id,amount) VALUES(1,2,3)");equal(4,((Number)session.execute("SELECT calculated FROM child",10).rows().get(0).get(0)).intValue());exec(session,"DELETE FROM parent WHERE tenant=1 AND id=2");equal(0,session.execute("SELECT * FROM child",10).rows().size());
            // Counter growth from data insertion is allowed; external schema edits and SQL mode changes are not.
            var base=MysqlTableDesign.read(session,DB,"child");exec(session,"INSERT INTO child(amount) VALUES(4)");String pending=plan(base,base.columns(),base.indexes(),base.keys(),new MysqlTableDesign.Options(old.engine(),old.charset(),old.collation(),"counter preserved",base.options().autoIncrement(),old.rowFormat(),old.averageRowLength(),old.maxRows(),old.minRows(),old.checksum(),old.delayKeyWrite()));MysqlTableDesign.apply(session,base,pending);
            base=MysqlTableDesign.read(session,DB,"child");exec(session,"ALTER TABLE child ADD external_column INT");boolean rejected=false;try{MysqlTableDesign.apply(session,base,pending);}catch(SQLException expected){rejected=true;}equal(true,rejected);
            base=MysqlTableDesign.read(session,DB,"child");exec(session,"SET SESSION sql_mode='NO_BACKSLASH_ESCAPES'");rejected=false;try{MysqlTableDesign.apply(session,base,pending);}catch(SQLException expected){rejected=true;}equal(true,rejected);
            base=MysqlTableDesign.read(session,DB,"child");var modeOptions=base.options();String modeSql=plan(base,base.columns(),base.indexes(),base.keys(),new MysqlTableDesign.Options(modeOptions.engine(),modeOptions.charset(),modeOptions.collation(),"mode ' \\ test",modeOptions.autoIncrement(),modeOptions.rowFormat(),modeOptions.averageRowLength(),modeOptions.maxRows(),modeOptions.minRows(),modeOptions.checksum(),modeOptions.delayKeyWrite()));session.setReadOnly(true);MysqlTableDesign.apply(session,base,modeSql);equal(true,session.getReadOnly());equal("mode ' \\ test",MysqlTableDesign.read(session,DB,"child").options().comment());
            // Actual Swing structure sheet, staged changes, confirmation No and confirmed save.
            AtomicReference<TableDesignPanel> panelRef=new AtomicReference<>();JFrame frame=edt(()->{JFrame f=new JFrame("Table structure integration");var p=new TableDesignPanel(session,DB,"child",()->{});panelRef.set(p);f.add(p);f.setSize(1500,850);f.setVisible(true);p.loadIfNeeded();return f;});
            try{
                var panel=panelRef.get();await(()->field(panel,"loaded",Boolean.class));equal(false,edt(()->panel.columnGrid.isCellEditable(0,1)));
                SwingUtilities.invokeLater(()->panel.edit.doClick());await(()->confirmation()!=null);SwingUtilities.invokeAndWait(()->confirmation().setValue("Sí"));equal(true,edt(()->panel.columnGrid.isCellEditable(0,1)));
                SwingUtilities.invokeLater(()->invoke(panel,"editIndex",false));await(()->dialog("Seleccionar columnas del índice")!=null);JDialog indexDialog=edt(()->dialog("Seleccionar columnas del índice"));
                SwingUtilities.invokeAndWait(()->{JTable picker=find(indexDialog,JTable.class);picker.setValueAt(true,0,0);picker.setValueAt(true,1,0);picker.setRowSelectionInterval(0,0);button(indexDialog,"Bajar").doClick();image(indexDialog,"table-design-index-picker.png");find(indexDialog,JOptionPane.class).setValue(JOptionPane.OK_OPTION);});
                equal(true,edt(()->panel.pendingSql().contains("(`tenant` ASC, `id` ASC)")));
                SwingUtilities.invokeLater(()->{panel.keyGrid.setRowSelectionInterval(0,0);invoke(panel,"editKey",true);});await(()->dialog("Claves foráneas: editar borrador")!=null);JDialog keyDialog=edt(()->dialog("Claves foráneas: editar borrador"));ForeignKeyEditor keyEditor=edt(()->find(keyDialog,ForeignKeyEditor.class));
                await(()->{for(int i=0;i<keyEditor.referenceDatabase.getItemCount();i++)if(keyEditor.referenceDatabase.getItemAt(i).equals(referenceDb))return true;return false;});SwingUtilities.invokeAndWait(()->keyEditor.referenceDatabase.setSelectedItem(referenceDb));await(()->!field(keyEditor,"loadingReferences",Boolean.class)&&"remote_parent".equals(keyEditor.parent.getSelectedItem()));
                SwingUtilities.invokeAndWait(()->{keyEditor.mapping.setValueAt("id",1,1);keyEditor.onUpdate.setSelectedItem("CASCADE");image(keyDialog,"table-design-foreign-key-picker.png");find(keyDialog,JOptionPane.class).setValue(JOptionPane.OK_OPTION);});equal(true,edt(()->panel.pendingSql().contains("`"+referenceDb+"`.`remote_parent`")));
                SwingUtilities.invokeAndWait(()->{panel.columns.setValueAt("UI comment",0,11);equal(true,panel.pendingSql().contains("UI comment"));image(panel,"table-design-columns.png");panel.tabs.setSelectedIndex(1);image(panel,"table-design-indexes.png");panel.tabs.setSelectedIndex(2);image(panel,"table-design-keys.png");panel.tabs.setSelectedIndex(3);panel.comment.setText("UI table comment");image(panel,"table-design-advanced.png");});
                String before=MysqlTableDesign.ddl(session.connection(),DB,"child");SwingUtilities.invokeLater(()->panel.save.doClick());await(()->confirmation()!=null);SwingUtilities.invokeAndWait(()->confirmation().setValue("No"));equal(before,MysqlTableDesign.ddl(session.connection(),DB,"child"));
                SwingUtilities.invokeLater(()->panel.save.doClick());await(()->confirmation()!=null);SwingUtilities.invokeAndWait(()->confirmation().setValue("Sí"));await(()->!field(panel,"busy",Boolean.class)&&panel.pendingSql().isBlank());equal("UI table comment",MysqlTableDesign.read(session,DB,"child").options().comment());
                var actual=MysqlTableDesign.read(session,DB,"child");equal(referenceDb,actual.keys().get(0).parentDatabase());equal("CASCADE",actual.keys().get(0).onUpdate());equal(true,actual.indexes().stream().anyMatch(i->i.name().equals("idx_child")&&i.columns().get(0).name().equals("tenant")));
                SwingUtilities.invokeAndWait(()->{UiLanguage.change("en",frame);equal("Columns",panel.tabs.getTitleAt(0));panel.tabs.setSelectedIndex(4);image(panel,"table-design-sql-en.png");});
            }finally{SwingUtilities.invokeAndWait(frame::dispose);}
            equal(true,session.getReadOnly());session.setReadOnly(false);
            try(PreparedStatement insert=session.connection().prepareStatement("INSERT INTO child(amount) VALUES(?)")){for(int i=0;i<1205;i++){insert.setInt(1,i);insert.addBatch();}insert.executeBatch();}
            equal(1000,session.execute("SELECT * FROM child",1000).rows().size());equal(true,session.execute("SELECT * FROM child",Integer.MAX_VALUE).rows().size()>1205);equal(7,session.execute("SELECT * FROM child LIMIT 7",Integer.MAX_VALUE).rows().size());
        }
        System.out.println("TableDesignIntegrationTest: OK (real MariaDB snapshot/no-op, SQL defaults/comments/modes, generated columns, composite FK/indexes, one ALTER, drift and counter guard, staged UI, No/Yes save and translated structure tabs)");System.exit(0);
    }
    private static String plan(MysqlTableDesign.Snapshot base,List<MysqlTableDesign.Column> cols,List<MysqlTableDesign.Index> idx,List<ForeignKeyDefinition> keys,MysqlTableDesign.Options options){return MysqlTableDesign.plan(base,cols.stream().map(c->new MysqlTableDesign.ColumnChange(base.columns().stream().anyMatch(o->o.name().equals(c.name()))?c.name():null,c)).toList(),idx.stream().map(i->new MysqlTableDesign.IndexChange(base.indexes().stream().anyMatch(o->o.name().equals(i.name()))?i.name():null,i)).toList(),keys.stream().map(k->new MysqlTableDesign.KeyChange(base.keys().stream().anyMatch(o->o.name().equals(k.name()))?k.name():null,k)).toList(),options);}
    private static MysqlTableDesign.Column comment(MysqlTableDesign.Column c,String note){return new MysqlTableDesign.Column(c.name(),c.type(),c.length(),c.notNull(),c.unsigned(),c.autoIncrement(),c.zerofill(),c.defaultSql(),c.onUpdate(),note,c.generated(),c.expression(),c.charset(),c.collation(),c.invisible(),c.unsupported());}
    private static void exec(JdbcSession s,String sql)throws Exception{s.execute(sql,1);}
    private static JOptionPane confirmation(){for(Window w:Window.getWindows())if(w.isVisible()){JOptionPane p=find(w,JOptionPane.class);if(p!=null&&p.getOptions()!=null&&Arrays.asList(p.getOptions()).contains("Sí"))return p;}return null;}
    private static JDialog dialog(String title){for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isVisible()&&d.getTitle().equals(title))return d;return null;}
    private static JButton button(Container c,String text){for(Component item:c.getComponents()){if(item instanceof JButton b&&b.getText().equals(text))return b;if(item instanceof Container nested){JButton found=button(nested,text);if(found!=null)return found;}}return null;}
    private static void invoke(Object target,String name,Object...args){try{Method m=Arrays.stream(target.getClass().getDeclaredMethods()).filter(x->x.getName().equals(name)&&x.getParameterCount()==args.length).findFirst().orElseThrow();m.setAccessible(true);m.invoke(target,args);}catch(Exception e){throw new RuntimeException(e);}}
    private static <T extends Component>T find(Container c,Class<T> type){for(Component child:c.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container nested){T found=find(nested,type);if(found!=null)return found;}}return null;}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(30);}throw new AssertionError("UI timed out");}
    private static <T>T edt(java.util.concurrent.Callable<T> work)throws Exception{AtomicReference<T> result=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{try{result.set(work.call());}catch(Exception e){throw new RuntimeException(e);}});return result.get();}
    private static <T>T field(Object target,String name,Class<T> type){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(target));}catch(Exception e){throw new RuntimeException(e);}}
    private static void image(Component c,String name){try{BufferedImage img=new BufferedImage(c.getWidth(),c.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();c.paint(g);g.dispose();Path file=Path.of("build/previews",name);Files.createDirectories(file.getParent());javax.imageio.ImageIO.write(img,"png",file.toFile());}catch(Exception e){throw new RuntimeException(e);}}
    private static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError("Expected "+a+"; actual "+b);}
}
