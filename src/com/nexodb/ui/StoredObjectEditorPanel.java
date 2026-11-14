package com.nexodb.ui;

import com.nexodb.db.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.function.Consumer;
import static com.nexodb.util.I18n.tr;

/** A whole stored-program body is sent as one JDBC statement, without splitting its internal semicolons. */
public final class StoredObjectEditorPanel extends JPanel {
    private final JdbcSession session;private final SchemaItem item;private StoredObjectService.Definition original;
    private final SqlEditorPane editor=new SqlEditorPane();private final JLabel status=new JLabel(" ");
    private final JButton apply=UiLanguage.button("Guardar y aplicar"),export=UiLanguage.button("Exportar definición…");
    private String saved;private boolean busy;private final Consumer<JdbcSession> changed;
    private java.util.function.BiPredicate<String,String> confirm=(title,text)->Confirmations.ask(this,title,text);
    public StoredObjectEditorPanel(JdbcSession session,SchemaItem item,StoredObjectService.Definition original,Consumer<JdbcSession> changed){
        super(new BorderLayout(6,6));this.session=session;this.item=item;this.original=original;this.changed=changed;
        setName("stored-object-editor");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));saved=original==null?StoredObjectService.template(item):original.sql();editor.restoreText(saved);editor.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));
        JPanel top=new JPanel(new BorderLayout(6,6));top.add(new JLabel(item.kind()+" · "+StoredObjectService.qualified(item)),BorderLayout.NORTH);JPanel bar=new JPanel(new FlowLayout(FlowLayout.LEFT));bar.add(apply);bar.add(export);top.add(bar);JTextArea note=new JTextArea(original==null?tr("Completa la definición y pulsa Guardar y aplicar. Se enviará todo el cuerpo como una única sentencia."):StoredObjectService.replacementNote(item.kind()));note.setEditable(false);note.setOpaque(false);note.setLineWrap(true);note.setWrapStyleWord(true);note.setRows(2);top.add(note,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);
        JScrollPane scroll=new JScrollPane(editor);scroll.setRowHeaderView(new SqlLineNumbers(editor));add(scroll);add(status,BorderLayout.SOUTH);
        apply.setEnabled(!session.profile().readOnly());apply.addActionListener(e->save());export.addActionListener(e->export());
        editor.getInputMap().put(KeyStroke.getKeyStroke("control S"),"save-object");editor.getActionMap().put("save-object",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){save();}});
    }
    private void save(){
        if(busy||!LicenseGate.ensure(this)||!session.isOpen())return;String sql=editor.getText();
        try{StoredObjectService.header(sql,item.kind());StoredObjectService.requireWritable(session);}catch(Exception error){status.setText(error.getMessage());return;}
        String preview=(original==null?"":StoredObjectService.replacementNote(item.kind())+"\n\n")+sql;
        if(!confirm.test("Aplicar definición SQL",session.profile().name()+"\n"+StoredObjectService.qualified(item)+"\n\n"+preview))return;
        busy=true;apply.setEnabled(false);editor.setEditable(false);status.setText(tr("Aplicando definición…"));
        new SwingWorker<StoredObjectService.Definition,Void>(){protected StoredObjectService.Definition doInBackground()throws Exception{StoredObjectService.save(session,item,sql,original);return StoredObjectService.read(session,item);}protected void done(){busy=false;apply.setEnabled(!session.profile().readOnly());editor.setEditable(true);try{original=get();saved=original.sql();editor.restoreText(saved);status.setText(tr("Objeto guardado."));changed.accept(session);}catch(Exception error){Throwable cause=error.getCause()==null?error:error.getCause();status.setText(cause.getMessage());JOptionPane.showMessageDialog(StoredObjectEditorPanel.this,cause.getMessage(),tr("No se pudo guardar el objeto"),JOptionPane.ERROR_MESSAGE);}}}.execute();
    }
    private void export(){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File(item.name()+".sql"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path file=chooser.getSelectedFile().toPath();if(Files.exists(file)&&!confirm.test("Reemplazar archivo",file.toString()))return;try{Files.writeString(file,StoredObjectService.exportSql(new StoredObjectService.Definition(editor.getText(),original==null?"":original.sqlMode(),"","")));status.setText(tr("Definición exportada: ")+file.getFileName());}catch(Exception error){status.setText(error.getMessage());}}
    public boolean canClose(){if(busy){status.setText(tr("Espera a que termine la operación."));return false;}return editor.getText().equals(saved)||JOptionPane.showConfirmDialog(this,tr("Hay cambios de definición sin aplicar. ¿Descartarlos?"),tr("Cerrar editor"),JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION;}
}
