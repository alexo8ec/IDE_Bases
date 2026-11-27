package com.nexodb.ui;

import com.nexodb.db.JdbcSession;
import com.nexodb.db.MigrationService;
import com.nexodb.util.AppSettings;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import static com.nexodb.util.I18n.tr;

/** Review-first Database DevOps workspace for versioned SQL migrations. */
public final class MigrationPanel extends JPanel {
    private final JdbcSession session;
    private final Consumer<String> openSql;
    private final JTextField folder=new JTextField(45);
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{tr("Versión"),tr("Descripción"),tr("Estado"),"SHA-256",tr("Archivo")},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);
    private final JTextArea preview=new JTextArea();
    private final JLabel status=new JLabel(" ");
    private final JButton apply=UiLanguage.button("Aplicar pendientes"),cancel=UiLanguage.button("Cancelar"),refresh=UiLanguage.button("Actualizar");
    private volatile boolean cancelled;
    private SwingWorker<?,?> worker;
    private List<MigrationService.Plan> plan=List.of();

    public MigrationPanel(JdbcSession session,Consumer<String> openSql){
        super(new BorderLayout(8,8));this.session=session;this.openSql=openSql;setBorder(BorderFactory.createEmptyBorder(10,10,10,10));setName("migration-panel");
        folder.setText(AppSettings.get("migrations.folder",Path.of(System.getProperty("user.home"),"AdisDB-Migrations").toString()));
        JToolBar tools=new JToolBar();tools.setFloatable(false);tools.add(new JLabel(tr("Carpeta: ")));tools.add(folder);JButton browse=UiLanguage.button("Elegir…"),create=UiLanguage.button("Nueva migración"),open=UiLanguage.button("Abrir SQL");tools.add(browse);tools.add(refresh);tools.add(create);tools.add(open);tools.addSeparator();tools.add(apply);tools.add(cancel);add(tools,BorderLayout.NORTH);
        preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));preview.setName("migration-preview");table.setRowHeight(26);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getSelectionModel().addListSelectionListener(e->showSelected());
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(table),new JScrollPane(preview));split.setResizeWeight(.55);split.setDividerLocation(300);add(split);add(status,BorderLayout.SOUTH);
        browse.addActionListener(e->chooseFolder());refresh.addActionListener(e->reload());create.addActionListener(e->createMigration());open.addActionListener(e->{MigrationService.Plan selected=selected();if(selected!=null&&selected.migration()!=null)try{openSql.accept(Files.readString(selected.migration().file()));}catch(Exception error){showError(error);}});apply.addActionListener(e->migrate());cancel.addActionListener(e->{cancelled=true;if(worker!=null)worker.cancel(true);});cancel.setEnabled(false);reload();
    }

    public JdbcSession origin(){return session;}
    public void disposePanel(){cancelled=true;if(worker!=null)worker.cancel(true);}

    private Path folder(){return Path.of(folder.getText().trim()).toAbsolutePath().normalize();}
    private void chooseFolder(){JFileChooser chooser=new JFileChooser(folder().toFile());chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(chooser.showOpenDialog(DialogPosition.owner(this))==JFileChooser.APPROVE_OPTION){folder.setText(chooser.getSelectedFile().getAbsolutePath());reload();}}
    private MigrationService.Plan selected(){int row=table.getSelectedRow();return row<0?null:plan.get(table.convertRowIndexToModel(row));}
    private void showSelected(){MigrationService.Plan item=selected();if(item==null){preview.setText("");return;}StringBuilder text=new StringBuilder();text.append("Estado: ").append(item.detail()).append('\n');if(item.history()!=null)text.append("Aplicada: ").append(item.history().installedOn()).append(" · ").append(item.history().executionMs()).append(" ms · ").append(item.history().installedBy()).append('\n');if(item.migration()!=null)try{text.append("\n").append(Files.readString(item.migration().file()));}catch(Exception error){text.append("\n").append(error.getMessage());}preview.setText(text.toString());preview.setCaretPosition(0);}
    private void reload(){run(false);}
    private void run(boolean migrate){
        if(worker!=null&&!worker.isDone())return;Path root=folder();AppSettings.put("migrations.folder",root.toString());cancelled=false;busy(true);status.setText(tr(migrate?"Aplicando migraciones…":"Leyendo historial y migraciones…"));
        worker=new SwingWorker<Object,Void>(){protected Object doInBackground()throws Exception{return migrate?MigrationService.migrate(session.connection(),session.profile().type(),root,()->cancelled||isCancelled()):MigrationService.plan(session.connection(),session.profile().type(),root);}protected void done(){try{Object value=get();if(value instanceof MigrationService.Result result){status.setText(tr("Migraciones aplicadas: ")+result.applied()+" · "+result.executionMs()+" ms");loadPlan(MigrationService.plan(session.connection(),session.profile().type(),root));}else loadPlan((List<MigrationService.Plan>)value);}catch(Exception error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();if(cause instanceof CancellationException)status.setText(tr("Operación cancelada."));else showError(cause);}finally{busy(false);}}};worker.execute();
    }
    private void migrate(){long pending=plan.stream().filter(item->item.status()==MigrationService.Status.PENDING).count();if(pending==0){JOptionPane.showMessageDialog(DialogPosition.owner(this),tr("No hay migraciones pendientes."));return;}if(!LicenseGate.ensure(this))return;if(JOptionPane.showConfirmDialog(DialogPosition.owner(this),tr("Se aplicarán ")+pending+tr(" migraciones en ")+session.profile().name()+".\n"+tr("Los archivos ya aplicados no podrán modificarse sin generar una alerta de checksum. ¿Continuar?"),tr("Aplicar migraciones"),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)==JOptionPane.YES_OPTION)run(true);}
    private void loadPlan(List<MigrationService.Plan> value){plan=value;model.setRowCount(0);for(var item:value){var migration=item.migration();model.addRow(new Object[]{migration==null?item.history().version():migration.version(),migration==null?item.history().description():migration.description(),tr(item.status().name()),migration==null?item.history().checksum():migration.checksum(),migration==null?item.history().script():migration.script()});}if(!value.isEmpty())table.setRowSelectionInterval(0,0);status.setText(value.stream().filter(item->item.status()==MigrationService.Status.PENDING).count()+tr(" pendientes · ")+value.stream().filter(item->item.status()==MigrationService.Status.APPLIED).count()+tr(" aplicadas"));}
    private void createMigration(){try{Files.createDirectories(folder());String description=JOptionPane.showInputDialog(DialogPosition.owner(this),tr("Descripción breve de la migración:"),"cambio_esquema");if(description==null)return;String safe=description.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");if(safe.isBlank())throw new IllegalArgumentException(tr("Escribe una descripción válida."));String version=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));Path file=folder().resolve("V"+version+"__"+safe+".sql");Files.writeString(file,"-- Migración "+version+" · "+description.trim()+"\n-- Revisa y prueba este SQL antes de aplicarlo.\n\n",java.nio.charset.StandardCharsets.UTF_8);openSql.accept(Files.readString(file));reload();}catch(Exception error){showError(error);}}
    private void busy(boolean value){refresh.setEnabled(!value);apply.setEnabled(!value);folder.setEnabled(!value);cancel.setEnabled(value);}
    private void showError(Throwable error){status.setText(error.getMessage());JOptionPane.showMessageDialog(DialogPosition.owner(this),error.getMessage(),tr("Migraciones versionadas"),JOptionPane.ERROR_MESSAGE);}
}
