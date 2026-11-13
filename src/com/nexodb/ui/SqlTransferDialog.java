package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;
import com.nexodb.db.*;
import com.nexodb.model.ConnectionProfile;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** A dedicated connection keeps transfers independent of the SQL editor's transactions. */
public final class SqlTransferDialog extends JDialog {
    private final boolean importing;
    private final ConnectionProfile profile;
    private final char[] password;
    private final String initialDatabase,initialTable;
    private final JComboBox<String> database=new JComboBox<>();
    private final JTextField file=new JTextField(32);
    private final JButton browse=button("Examinar…"),review=button("Revisar archivo"),start=button("Crear respaldo"),close=button("Cerrar"),cancel=button("Cancelar operación");
    private final JComboBox<String> mode=new JComboBox<>(new String[]{"Estructura y datos","Solo estructura","Solo datos"});
    private final JCheckBox create=check("Incluir CREATE DATABASE y USE",false),drop=check("Incluir DROP para reemplazar objetos",false),fk=check("Desactivar claves foráneas durante la restauración",true),consistent=check("Lectura consistente (tablas InnoDB)",true),omit=check("Omitir DEFINER para facilitar la restauración",true);
    private final JCheckBox createTarget=check("Crear la base de destino si no existe",false),preserve=check("Respetar los nombres de bases del archivo",false);
    private final ObjectsModel objects=new ObjectsModel();
    private final JTable table=new JTable(objects);
    private final JTextArea preview=new JTextArea(8,60);
    private final JLabel summary=new JLabel(" "),status=new JLabel(" ");
    private final JProgressBar progress=new JProgressBar();
    private final List<JComponent> inputs=new ArrayList<>();
    private final Map<SqlBackupService.Kind,JCheckBox> groups=new EnumMap<>(SqlBackupService.Kind.class);
    private JdbcSession dedicated;
    private SwingWorker<?,?> worker;
    private SqlTransferControl control;
    private SqlImportService.Analysis analysis;
    private boolean loading,restored,closed;
    private String baselineMode,baselineZone;
    private int baselineForeignKeys;

    public SqlTransferDialog(Frame owner,ConnectionProfile source,char[] password,boolean importing,String defaultDatabase,String defaultTable){
        super(owner,true);this.importing=importing;this.password=password;initialDatabase=Objects.toString(defaultDatabase,"");initialTable=Objects.toString(defaultTable,"");
        profile=new ConnectionProfile(source.id(),source.name(),source.type(),source.host(),source.port(),source.database(),source.username(),source.jdbcUrl(),source.driverClass(),source.driverJar(),source.authenticationMode(),!importing);
        UiLanguage.title(this,UiLanguage.key(importing?"Importar / restaurar SQL":"Respaldo SQL"));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){requestClose();}});
        JPanel content=new JPanel(new BorderLayout(10,12));content.setBorder(new EmptyBorder(18,20,16,20));setContentPane(content);
        JPanel heading=new JPanel(new BorderLayout(8,4));JLabel title=label(importing?"Restaura una tabla o una base desde un archivo SQL":"Guarda una tabla o tu base en un archivo SQL");title.setFont(title.getFont().deriveFont(Font.BOLD,18));heading.add(title,BorderLayout.NORTH);
        heading.add(new JLabel(source.name()+" · "+source.host()+":"+source.port()+" · "+source.username()),BorderLayout.SOUTH);content.add(heading,BorderLayout.NORTH);
        JPanel body=new JPanel(new BorderLayout(8,10));content.add(body,BorderLayout.CENTER);
        JPanel fields=new JPanel(new GridBagLayout());GridBagConstraints g=new GridBagConstraints();g.insets=new Insets(4,0,4,10);g.fill=GridBagConstraints.HORIZONTAL;
        addField(fields,g,0,label(importing?"Base de destino:":"Base de origen:"),database);
        JPanel fileRow=new JPanel(new BorderLayout(6,0));fileRow.add(file);fileRow.add(browse,BorderLayout.EAST);addField(fields,g,1,label(importing?"Archivo SQL (UTF-8):":"Guardar como:"),fileRow);
        body.add(fields,BorderLayout.NORTH);database.setEditable(importing);inputs.add(database);inputs.add(file);inputs.add(browse);inputs.add(omit);
        if(importing)buildImport(body);else buildBackup(body);
        JPanel footer=new JPanel(new BorderLayout(8,8));JPanel info=new JPanel(new BorderLayout(8,4));info.add(status,BorderLayout.NORTH);info.add(progress,BorderLayout.SOUTH);footer.add(info,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));if(importing)actions.add(review);actions.add(start);actions.add(cancel);actions.add(close);footer.add(actions,BorderLayout.EAST);content.add(footer,BorderLayout.SOUTH);
        if(importing)UiLanguage.text(start,UiLanguage.key("Importar SQL"));
        start.setIcon(IconFactory.of(importing?IconFactory.Kind.DATABASE:IconFactory.Kind.EXPORT,16));start.addActionListener(e->{if(importing)restore();else backup();});
        close.addActionListener(e->requestClose());cancel.addActionListener(e->cancelOperation());browse.addActionListener(e->chooseFile());review.addActionListener(e->review());
        database.addActionListener(e->{if(!loading&&!importing&&dedicated!=null)loadObjects();updateStart();});
        file.getDocument().addDocumentListener(new DocumentListener(){private void changed(){analysis=null;summary.setText(" ");updateStart();}public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){}});
        objects.addTableModelListener(e->updateStart());
        cancel.setVisible(false);progress.setVisible(false);setMinimumSize(new Dimension(790,590));setSize(900,680);setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(this::connect);
    }
    public boolean restored(){return restored;}
    private static JButton button(String text){return UiLanguage.text(new JButton(tr(text)),UiLanguage.key(text));}
    private static JCheckBox check(String text,boolean selected){return UiLanguage.text(new JCheckBox(tr(text),selected),UiLanguage.key(text));}
    private static JLabel label(String text){return UiLanguage.text(new JLabel(tr(text)),UiLanguage.key(text));}
    private static void addField(JPanel form,GridBagConstraints g,int row,JComponent label,JComponent field){g.gridy=row;g.gridx=0;g.weightx=0;form.add(label,g);g.gridx=1;g.weightx=1;form.add(field,g);}
    private void buildBackup(JPanel body){
        JPanel center=new JPanel(new BorderLayout(6,8));JPanel options=new JPanel(new BorderLayout(6,6));JPanel modeRow=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));modeRow.add(label("Contenido:"));UiLanguage.choices(mode);modeRow.add(mode);inputs.add(mode);options.add(modeRow,BorderLayout.NORTH);
        JPanel selection=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));JButton all=button("Todos"),none=button("Ninguno");selection.add(all);selection.add(none);inputs.add(all);inputs.add(none);all.addActionListener(e->objects.select(null,true));none.addActionListener(e->objects.select(null,false));
        for(SqlBackupService.Kind kind:SqlBackupService.Kind.values()){
            JCheckBox group=new JCheckBox(tr(kindCaption(kind)),true);selection.add(group);inputs.add(group);groups.put(kind,group);group.addActionListener(e->objects.select(kind,group.isSelected()));
        }
        options.add(summary,BorderLayout.CENTER);options.add(selection,BorderLayout.SOUTH);center.add(options,BorderLayout.NORTH);
        table.setRowHeight(25);table.setAutoCreateRowSorter(true);table.getColumnModel().getColumn(0).setMaxWidth(45);table.getColumnModel().getColumn(1).setPreferredWidth(120);table.getColumnModel().getColumn(2).setPreferredWidth(450);center.add(new JScrollPane(table),BorderLayout.CENTER);inputs.add(table);
        JPanel lower=new JPanel(new BorderLayout(6,6));lower.add(label("Se incluyen todas las filas. Selecciona también las dependencias de vistas y rutinas."),BorderLayout.NORTH);
        lower.add(advanced(List.of(create,drop,fk,consistent,omit)),BorderLayout.CENTER);center.add(lower,BorderLayout.SOUTH);body.add(center,BorderLayout.CENTER);
    }
    private void buildImport(JPanel body){
        JPanel center=new JPanel(new BorderLayout(6,8));JPanel choices=new JPanel(new GridLayout(0,1));choices.add(createTarget);inputs.add(createTarget);
        choices.add(label("El archivo se restaurará en la base seleccionada; sus datos actuales pueden cambiar."));center.add(choices,BorderLayout.NORTH);
        preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));JPanel reviewed=new JPanel(new BorderLayout(6,6));reviewed.setBorder(UiLanguage.border("Vista previa del archivo"));reviewed.add(summary,BorderLayout.NORTH);reviewed.add(new JScrollPane(preview),BorderLayout.CENTER);center.add(reviewed,BorderLayout.CENTER);
        JPanel lower=new JPanel(new BorderLayout(6,8));lower.add(advanced(List.of(omit,preserve)),BorderLayout.NORTH);
        JTextArea warning=new JTextArea(tr("La importación se detiene al primer error. MySQL/MariaDB confirma cambios de estructura de forma inmediata: esos cambios pueden permanecer aunque se cancele. Crea un respaldo antes de reemplazar datos."));warning.setEditable(false);warning.setLineWrap(true);warning.setWrapStyleWord(true);warning.setOpaque(false);warning.setRows(3);lower.add(warning,BorderLayout.SOUTH);center.add(lower,BorderLayout.SOUTH);body.add(center,BorderLayout.CENTER);
    }
    private JComponent advanced(List<JCheckBox> choices){
        JPanel panel=new JPanel(new BorderLayout());JToggleButton toggle=UiLanguage.text(new JToggleButton(tr("Opciones avanzadas")),UiLanguage.key("Opciones avanzadas"));toggle.setHorizontalAlignment(SwingConstants.LEFT);JPanel options=new JPanel(new GridLayout(0,1));for(JCheckBox choice:choices){options.add(choice);if(!inputs.contains(choice))inputs.add(choice);}options.setVisible(false);toggle.addActionListener(e->{options.setVisible(toggle.isSelected());revalidate();});panel.add(toggle,BorderLayout.NORTH);panel.add(options,BorderLayout.CENTER);return panel;
    }
    private static String kindCaption(SqlBackupService.Kind kind){return switch(kind){case TABLE->"Tablas";case VIEW->"Vistas";case PROCEDURE->"Procedimientos";case FUNCTION->"Funciones";case TRIGGER->"Triggers";case EVENT->"Eventos";};}
    private String selectedDatabase(){Object value=database.isEditable()?database.getEditor().getItem():database.getSelectedItem();return value==null?"":value.toString().trim();}
    private void connect(){
        run("Conectando…",()->{
            try{dedicated=JdbcSession.open(profile,password);}finally{Arrays.fill(password,'\0');}
            control.check();try(var s=dedicated.connection().createStatement();var r=s.executeQuery("SELECT @@SESSION.sql_mode,@@SESSION.time_zone,@@SESSION.foreign_key_checks")){r.next();baselineMode=r.getString(1);baselineZone=r.getString(2);baselineForeignKeys=r.getInt(3);}
            List<String> names=new ArrayList<>();try(var r=dedicated.connection().getMetaData().getCatalogs()){while(r.next())names.add(r.getString("TABLE_CAT"));}names.sort(String.CASE_INSENSITIVE_ORDER);return names;
        },names->{loading=true;database.removeAllItems();for(String name:names)database.addItem(name);database.setSelectedItem(initialDatabase.isBlank()?(names.isEmpty()?"":names.get(0)):initialDatabase);loading=false;if(!importing)loadObjects();});
    }
    private void loadObjects(){
        String selected=selectedDatabase();if(selected.isBlank())return;
        run("Leyendo objetos…",()->SqlBackupService.objects(dedicated.connection(),selected),values->{objects.set(values,initialDatabase.equals(selected)?initialTable:"");if(file.getText().isBlank())file.setText(Path.of(System.getProperty("user.home"),selected.replaceAll("[^\\p{L}\\p{N}_.-]","_")+"-"+java.time.LocalDate.now()+".sql").toString());});
    }
    private void chooseFile(){
        JFileChooser chooser=new JFileChooser();chooser.setFileFilter(new FileNameExtensionFilter(tr("Archivo SQL"),"sql"));if(!file.getText().isBlank())chooser.setSelectedFile(new java.io.File(file.getText()));
        if((importing?chooser.showOpenDialog(this):chooser.showSaveDialog(this))!=JFileChooser.APPROVE_OPTION)return;
        String path=chooser.getSelectedFile().getAbsolutePath();if(!importing&&!path.toLowerCase(Locale.ROOT).endsWith(".sql"))path+=".sql";file.setText(path);if(importing)review();
    }
    private void review(){
        Path path;try{path=Path.of(file.getText().trim());}catch(Exception error){showError(error);return;}
        run("Revisando archivo SQL…",()->SqlImportService.analyze(path,control),value->{analysis=value;preview.setText(value.preview());preview.setCaretPosition(0);summary.setText(tr("%s sentencias · %s modifican o eliminan objetos/datos",value.statements(),value.destructive()));});
    }
    private void backup(){
        if(table.isEditing())table.getCellEditor().stopCellEditing();List<SqlBackupService.ObjectInfo> selected=objects.selected();if(selected.isEmpty())return;
        String selectedDatabase=selectedDatabase();Path target;try{target=Path.of(file.getText().trim()).toAbsolutePath();}catch(Exception error){showError(error);return;}
        if(Files.exists(target)&&JOptionPane.showConfirmDialog(this,tr("¿Reemplazar el archivo existente?")+"\n"+target,tr("Respaldo SQL"),JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        var selectedMode=SqlBackupService.Mode.values()[mode.getSelectedIndex()];List<String> nonTransactional=SqlBackupService.nonTransactional(selected);
        if(consistent.isSelected()&&selectedMode!=SqlBackupService.Mode.STRUCTURE&&!nonTransactional.isEmpty()&&JOptionPane.showConfirmDialog(this,tr("Hay tablas que no son InnoDB. Su lectura no garantiza un estado consistente si cambian durante el respaldo. ¿Continuar?")+"\n"+String.join(", ",nonTransactional.subList(0,Math.min(nonTransactional.size(),12))),tr("Lectura del respaldo"),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
        var options=new SqlBackupService.Options(selectedMode,create.isSelected(),drop.isSelected(),fk.isSelected(),consistent.isSelected(),omit.isSelected());
        run("Creando respaldo…",()->SqlBackupService.write(dedicated.connection(),profile.type(),selectedDatabase,selected,options,target,control,p->updateProgress(p.object()+" · "+p.rows()+" "+tr("filas"))),report->{
            status.setText(tr("Respaldo completo: %s objetos · %s filas",report.objects(),report.rows()));JOptionPane.showMessageDialog(this,status.getText()+"\n"+report.file(),tr("Respaldo SQL"),JOptionPane.INFORMATION_MESSAGE);
        });
    }
    private void restore(){
        if(analysis==null)return;String target=selectedDatabase();
        if(!preserve.isSelected()&&analysis.databases().size()>1){showError(new IllegalArgumentException(tr("El archivo contiene varias bases. Usa un respaldo de una sola base o revisa la opción de respetar sus nombres originales.")));return;}
        String scope=preserve.isSelected()?tr("Bases del archivo: ")+String.join(", ",analysis.databases()):tr("Base de destino: ")+target;
        JTextArea confirmation=new JTextArea(profile.name()+" · "+profile.host()+":"+profile.port()+"\n"+scope+"\n"+analysis.file()+"\n\n"+tr("%s sentencias · %s modifican o eliminan objetos/datos",analysis.statements(),analysis.destructive())+"\n\n"+tr("¿Importar este archivo ahora? Los cambios de estructura pueden permanecer si hay un error."));confirmation.setEditable(false);confirmation.setLineWrap(true);confirmation.setWrapStyleWord(true);confirmation.setColumns(55);confirmation.setRows(9);confirmation.setOpaque(false);
        if(JOptionPane.showConfirmDialog(this,confirmation,tr("Confirmar importación SQL"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
        var options=new SqlImportService.Options(target,createTarget.isSelected(),preserve.isSelected(),omit.isSelected());SqlImportService.Analysis reviewed=analysis;
        run("Importando SQL…",()->SqlImportService.restore(dedicated.connection(),reviewed,options,control,p->updateProgress(tr("%s de %s sentencias · línea %s",p.completed(),p.total(),p.line()))),report->{restored=true;status.setText(tr("Importación completa: %s sentencias ejecutadas",report.executed()));JOptionPane.showMessageDialog(this,status.getText(),tr("Importar / restaurar SQL"),JOptionPane.INFORMATION_MESSAGE);});
    }
    private void updateProgress(String text){SwingUtilities.invokeLater(()->{if(!closed)status.setText(text);});}
    private void updateStart(){
        boolean busy=worker!=null;for(JComponent input:inputs)input.setEnabled(!busy&&dedicated!=null);review.setEnabled(!busy&&dedicated!=null&&!file.getText().isBlank());close.setEnabled(!busy);cancel.setVisible(busy);progress.setVisible(busy);progress.setIndeterminate(busy);
        start.setEnabled(!busy&&dedicated!=null&&!selectedDatabase().isBlank()&&!file.getText().isBlank()&&(importing?analysis!=null&&analysis.statements()>0:!objects.selected().isEmpty()));
        if(!importing){summary.setText(tr("%s de %s objetos seleccionados",objects.selected().size(),objects.getRowCount()));for(var entry:groups.entrySet()){var matches=objects.values.stream().filter(o->o.kind()==entry.getKey()).toList();entry.getValue().setSelected(!matches.isEmpty()&&objects.selected.containsAll(matches));}}
    }
    private void resetConnection()throws Exception{
        if(dedicated==null||baselineMode==null)return;
        var connection=dedicated.connection();
        try{
            if(!connection.getAutoCommit()){connection.rollback();connection.setAutoCommit(true);}
            try(var statement=connection.prepareStatement("SET SESSION sql_mode=?,time_zone=?,foreign_key_checks=?")){statement.setString(1,baselineMode);statement.setString(2,baselineZone);statement.setInt(3,baselineForeignKeys);statement.execute();}
        }catch(Exception error){JdbcSession failed=dedicated;dedicated=null;try{failed.close();}catch(Exception close){error.addSuppressed(close);}throw error;}
    }
    private <T> void run(String caption,Callable<T> operation,Consumer<T> success){
        if(worker!=null||closed)return;control=new SqlTransferControl();cancel.setEnabled(true);status.setText(tr(caption));
        SwingWorker<T,Void> task=new SwingWorker<>(){
            protected T doInBackground()throws Exception{try{return operation.call();}finally{resetConnection();}}
            protected void done(){worker=null;try{T value=get();if(!closed){status.setText(tr("Listo."));success.accept(value);}}catch(Exception error){Throwable cause=error;while(cause.getCause()!=null&&!(cause instanceof SqlImportService.ImportFailure))cause=cause.getCause();
                if(cause instanceof java.util.concurrent.CancellationException)status.setText(tr(importing?"Operación cancelada. Los cambios de estructura ya aplicados pueden permanecer.":"Respaldo cancelado. El archivo de destino se conservó."));else showError(cause);
            }finally{updateStart();}}
        };worker=task;updateStart();task.execute();
    }
    private void showError(Throwable error){String message=Objects.toString(error.getMessage(),error.getClass().getSimpleName());if(error instanceof SqlImportService.ImportFailure failure)message=tr("La importación se detuvo en la línea %s, después de %s sentencias. Los cambios de estructura ya aplicados pueden permanecer.",failure.line(),failure.completed())+"\n\n"+message;status.setText(tr("La operación no se completó."));JTextArea text=new JTextArea(message,8,60);text.setLineWrap(true);text.setWrapStyleWord(true);text.setEditable(false);JOptionPane.showMessageDialog(this,new JScrollPane(text),tr("Operación SQL"),JOptionPane.ERROR_MESSAGE);}
    private void cancelOperation(){SqlTransferControl active=control;if(active==null)return;cancel.setEnabled(false);status.setText(tr("Cancelando…"));Thread thread=new Thread(active::cancel,"nexodb-transfer-cancel");thread.setDaemon(true);thread.start();}
    private void requestClose(){if(worker!=null){cancelOperation();return;}closed=true;Arrays.fill(password,'\0');if(dedicated!=null){JdbcSession closing=dedicated;dedicated=null;Thread thread=new Thread(()->{try{closing.close();}catch(Exception ignored){}},"nexodb-transfer-close");thread.setDaemon(true);thread.start();}dispose();}
    private static final class ObjectsModel extends AbstractTableModel {
        private List<SqlBackupService.ObjectInfo> values=List.of();private final Set<SqlBackupService.ObjectInfo> selected=new HashSet<>();
        void set(List<SqlBackupService.ObjectInfo> values,String table){this.values=values;selected.clear();for(var value:values)if(table.isBlank()||value.name().equals(table)||value.kind()==SqlBackupService.Kind.TRIGGER&&value.parent().equals(table))selected.add(value);fireTableDataChanged();}
        void select(SqlBackupService.Kind kind,boolean include){for(var value:values)if(kind==null||value.kind()==kind){if(include)selected.add(value);else selected.remove(value);}fireTableDataChanged();}
        List<SqlBackupService.ObjectInfo> selected(){return values.stream().filter(selected::contains).toList();}
        public int getRowCount(){return values.size();}public int getColumnCount(){return 3;}public Class<?> getColumnClass(int column){return column==0?Boolean.class:String.class;}
        public String getColumnName(int column){return column==0?"✓":tr(column==1?"Tipo":"Nombre");}public boolean isCellEditable(int row,int column){return column==0;}
        public Object getValueAt(int row,int column){var value=values.get(row);return column==0?selected.contains(value):column==1?tr(kindCaption(value.kind())):value.name();}
        public void setValueAt(Object value,int row,int column){if(column!=0)return;if(Boolean.TRUE.equals(value))selected.add(values.get(row));else selected.remove(values.get(row));fireTableRowsUpdated(row,row);}
    }
}
