package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;
import com.nexodb.db.QueryResult;
import com.nexodb.db.SqlColumnCompletion;
import com.nexodb.db.SqlJoinCompletion;
import com.nexodb.db.SqlTableAliases;
import com.nexodb.db.SqlExecutionPolicy;
import com.nexodb.util.DataExporter;
import com.nexodb.util.SqlText;
import com.nexodb.util.SqlDraftStore;
import com.nexodb.util.SqlVocabulary;
import com.nexodb.util.SqlFiles;
import com.nexodb.util.QueryHistoryStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableColumn;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.nio.file.*;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public final class QueryPanel extends JPanel {
    private Set<String> sqlKeywords = SqlVocabulary.base();
    private static final String SELECT_DATABASE = "Seleccione una base…";
    private static final String RESULT_PROPERTY = "nexodb.queryResult";
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile(
            "(?is)\\b(?:FROM|UPDATE|INTO|TABLE)\\s+((?:[`\"\\w$-]+\\.)?[`\"\\w$-]+)");
    private SqlEditorPane editor;
    private final JTabbedPane queryTabs = new JTabbedPane();
    private final List<SqlEditorPane> queryEditors = new ArrayList<>();
    private static final class DocumentState {Path path;String savedHash="",diskHash="",title="";int number;JLabel label=new JLabel();}
    private final java.util.Map<SqlEditorPane,DocumentState> documents=new java.util.IdentityHashMap<>();
    private Consumer<String> toolHandler=name->{};
    private final JButton newQueryButton=new JButton("+");
    private final JButton openSqlButton=new JButton(IconFactory.of(IconFactory.Kind.FOLDER,17));
    private final JButton saveSqlButton=new JButton(IconFactory.of(IconFactory.Kind.SAVE,17));
    private final JButton undoButton=new JButton(IconFactory.of(IconFactory.Kind.UNDO,17)),redoButton=new JButton(IconFactory.of(IconFactory.Kind.REDO,17));
    private boolean fileBusy;
    private final QueryHistoryStore history=QueryHistoryStore.configured();
    private int activeQuery;
    private int querySequence;
    private boolean changingQuery;
    private final JTextArea messages = new JTextArea();
    private final JScrollPane messagesScroll = new JScrollPane(messages);
    private final JTabbedPane outputTabs = new JTabbedPane();
    private final JSpinner maxRows = new JSpinner(new SpinnerNumberModel(1000, 1, 100000, 100));
    private final JCheckBox limitRows=UiLanguage.text(new JCheckBox("",true),UiLanguage.key("Limitar filas"));
    private final JCheckBox readOnly=UiLanguage.text(new JCheckBox("",true),UiLanguage.key("Solo lectura"));
    private java.util.function.BiPredicate<String,String> confirm=(title,message)->Confirmations.ask(this,title,message);
    private final JCheckBox autoCommit = new JCheckBox("Auto-commit", true);
    private final JButton execute = UiLanguage.text(new JButton(tr("Ejecutar")), UiLanguage.key("Ejecutar"));
    private final JButton cancel = UiLanguage.text(new JButton(tr("Cancelar")), UiLanguage.key("Cancelar"));
    private final JButton commit = new JButton("Commit");
    private final JButton rollback = new JButton("Rollback");
    private final JButton export = UiLanguage.text(new JButton(tr("Exportar…")), UiLanguage.key("Exportar…"));
    private final JButton report = UiLanguage.text(new JButton(),UiLanguage.key("Reporte…"));
    private final JLabel state = UiLanguage.text(new JLabel(tr("Sin conexión")), UiLanguage.key("Sin conexión"));
    private final JComboBox<String> database = new JComboBox<>();
    private final JProgressBar progress = new JProgressBar();
    private final JPopupMenu completionPopup = new JPopupMenu();
    private final DefaultListModel<String> completionModel = new DefaultListModel<>();
    private final JList<String> completionList = new JList<>(completionModel);
    private final JScrollPane completionScroll = new JScrollPane(completionList);
    private final javax.swing.Timer completionTimer;
    private final Set<String> completionWords = new LinkedHashSet<>();
    private final Set<String> tableWords = new LinkedHashSet<>();
    private final Consumer<String> statusConsumer;
    private final Consumer<Boolean> busyConsumer;
    private Consumer<String> databaseChangeConsumer = name -> {};
    private JdbcSession session;
    private QueryResult currentResult;
    private SwingWorker<List<StatementOutcome>, Void> worker;
    private boolean savingResult;
    private boolean queryRunning;
    private int externalBusy;
    private SqlColumnCompletion columnCompletion;
    private SqlJoinCompletion joinCompletion;
    private final java.util.Map<String,String> joinInsertions=new java.util.LinkedHashMap<>();
    private SwingWorker<List<String>,Void> columnWorker;
    private final Set<String> columnWords = new LinkedHashSet<>();
    private long completionGeneration, completionCatalogGeneration;
    private QueryResultPanel.ColumnEditor columnEditor;
    private final SqlDraftStore drafts;
    private final javax.swing.Timer draftTimer;
    private final java.util.concurrent.ExecutorService draftWriter;
    private boolean restoringDraft, draftDirty;

    public QueryPanel(Consumer<String> statusConsumer, Consumer<Boolean> busyConsumer) {
        this(statusConsumer,busyConsumer,SqlDraftStore.configured());
    }
    QueryPanel(Consumer<String> statusConsumer,Consumer<Boolean> busyConsumer,SqlDraftStore drafts) {
        super(new BorderLayout());
        this.statusConsumer = statusConsumer;
        this.busyConsumer = busyConsumer;
        this.drafts=drafts;
        draftWriter=drafts==null?null:java.util.concurrent.Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"nexodb-sql-draft");thread.setDaemon(true);return thread;});
        draftTimer=new javax.swing.Timer(700,event->saveDraftAsync());draftTimer.setRepeats(false);
        this.completionTimer = new javax.swing.Timer(180, e -> showCompletions(false));
        completionTimer.setRepeats(false);
        addSqlKeywords();
        setBorder(new EmptyBorder(0, 8, 8, 8));
        configureCompletionPopup();
        add(buildToolbar(), BorderLayout.NORTH);
        JPanel queries=new JPanel(new BorderLayout());queries.add(queryTabs,BorderLayout.CENTER);
        UiLanguage.tooltip(newQueryButton,UiLanguage.key("Crear consulta o abrir una herramienta"));newQueryButton.setFocusable(false);newQueryButton.setMargin(new Insets(3,10,3,10));
        newQueryButton.addActionListener(event->newQueryMenu().show(newQueryButton,0,newQueryButton.getHeight()));
        JPanel tabActions=new JPanel(new FlowLayout(FlowLayout.LEFT,3,0));tabActions.setOpaque(false);tabActions.add(newQueryButton);queryTabs.putClientProperty("JTabbedPane.trailingComponent",tabActions);
        queryTabs.addChangeListener(event->changeQuery());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, queries, buildOutput());
        split.setResizeWeight(0.55);
        split.setDividerLocation(350);
        add(split, BorderLayout.CENTER);
        restoringDraft=true;changingQuery=true;
        try {
            if(drafts!=null)drafts.loadWorkspace().ifPresent(workspace->{
                for(SqlDraftStore.Draft draft:workspace.tabs()){
                    String text=withoutStartupHeader(draft.text());SqlEditorPane pane=addQuery(text);
                    DocumentState info=documents.get(pane);if(!draft.path().isBlank())try{info.path=Path.of(draft.path());}catch(InvalidPathException ignored){}
                    info.savedHash=draft.savedHash();info.diskHash=draft.diskHash();info.title=draft.title();refreshTitle(pane);
                    pane.setCaretPosition(Math.max(0,Math.min(text.length(),draft.caret()-(draft.text().length()-text.length()))));
                }
                activeQuery=workspace.activeTab();
            });
        }catch(Exception error){statusConsumer.accept(tr("No se pudo recuperar el borrador SQL."));}
        finally{if(queryEditors.isEmpty())addQuery("");editor=queryEditors.get(activeQuery);queryTabs.setSelectedIndex(activeQuery);changingQuery=false;restoringDraft=false;}
        updateActions();
    }
    private static String withoutStartupHeader(String text){
        for(String help:List.of("-- Selecciona una consulta o coloca el cursor dentro de ella y presiona Ctrl+Enter.","-- Select a query or place the cursor inside it and press Ctrl+Enter.")){
            String prefix="-- NexoDB Studio\n"+help+"\n\n";
            if(text.startsWith(prefix))return text.substring(prefix.length());
            prefix=prefix.replace("\n","\r\n");if(text.startsWith(prefix))return text.substring(prefix.length());
        }
        return text;
    }
    private SqlEditorPane addQuery(String sql){
        SqlEditorPane pane=new SqlEditorPane();configureEditor(pane);pane.setKeywords(sqlKeywords);pane.restoreText(sql);
        queryEditors.add(pane);JScrollPane scroll=new JScrollPane(pane);scroll.setRowHeaderView(new SqlLineNumbers(pane));int number=++querySequence;
        DocumentState info=new DocumentState();info.number=number;documents.put(pane,info);
        UiLanguage.addTab(queryTabs,new Object[]{UiLanguage.key("Consulta")," ",number},IconFactory.of(IconFactory.Kind.SQL,14),scroll);
        JPanel label=new JPanel(new FlowLayout(FlowLayout.LEFT,3,0));label.setOpaque(false);
        label.add(info.label);
        JButton close=new JButton("×");close.setMargin(new Insets(0,4,0,4));close.setFocusable(false);
        UiLanguage.tooltip(close,UiLanguage.key("Cerrar consulta"));close.addActionListener(event->closeQuery(pane));label.add(close);
        queryTabs.setTabComponentAt(queryEditors.size()-1,label);
        pane.getDocument().addDocumentListener(new DocumentListener(){private void changed(){refreshTitle(pane);scheduleDraft();}public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){}});
        pane.onUndoStateChanged(this::updateEditorActions);pane.addCaretListener(event->scheduleDraft());refreshTitle(pane);return pane;
    }
    private boolean canChangeQuery(){return !fileBusy&&!savingResult&&!queryRunning&&externalBusy==0&&finishResultEditing()&&confirmDiscardResults();}
    private void changeQuery(){
        if(changingQuery||queryTabs.getSelectedIndex()<0)return;
        int selected=queryTabs.getSelectedIndex();if(selected==activeQuery)return;
        if(!canChangeQuery()){changingQuery=true;try{queryTabs.setSelectedIndex(activeQuery);}finally{changingQuery=false;}return;}
        editor=queryEditors.get(selected);activeQuery=selected;completionGeneration++;completionTimer.stop();completionPopup.setVisible(false);
        readOnly.setSelected(true);
        clearResultTabs();messages.setText("");scheduleDraft();updateEditorActions();editor.requestFocusInWindow();
    }
    private void closeQuery(SqlEditorPane pane){
        if(!canChangeQuery())return;
        if(isDirty(pane)){
            int answer=JOptionPane.showOptionDialog(this,tr("¿Guardar los cambios de esta consulta en un archivo SQL antes de cerrarla?"),tr("Cerrar consulta"),JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,null,new String[]{tr("Guardar"),tr("No guardar"),tr("Cancelar")},tr("Guardar"));
            if(answer==0){saveSql(pane,false,()->removeQuery(pane));return;}if(answer!=1)return;
        }
        removeQuery(pane);
    }
    private void removeQuery(SqlEditorPane pane){
        int index=queryEditors.indexOf(pane);if(index<0)return;changingQuery=true;
        SqlEditorPane previous=editor;boolean active=pane==previous;
        try{queryEditors.remove(index);documents.remove(pane);queryTabs.removeTabAt(index);if(queryEditors.isEmpty())addQuery("");activeQuery=active?Math.min(index,queryEditors.size()-1):queryEditors.indexOf(previous);editor=queryEditors.get(activeQuery);queryTabs.setSelectedIndex(activeQuery);}
        finally{changingQuery=false;}clearResultTabs();messages.setText("");scheduleDraft();
    }
    private void scheduleDraft(){if(drafts!=null&&!restoringDraft){draftDirty=true;draftTimer.restart();}}
    private SqlDraftStore.Workspace draftSnapshot(){return new SqlDraftStore.Workspace(queryEditors.stream().map(pane->{DocumentState d=documents.get(pane);return new SqlDraftStore.Draft(pane.getText(),pane.getCaretPosition(),d.path==null?"":d.path.toString(),d.savedHash,d.diskHash,d.title);}).toList(),activeQuery);}
    private void saveDraftAsync(){
        if(drafts==null||!draftDirty)return;SqlDraftStore.Workspace snapshot=draftSnapshot();draftDirty=false;
        draftWriter.submit(()->{try{drafts.saveWorkspace(snapshot);}catch(Exception error){SwingUtilities.invokeLater(()->{draftDirty=true;statusConsumer.accept(tr("No se pudieron guardar automáticamente las consultas."));});}});
    }
    public boolean saveDraftBeforeClose(){
        if(fileBusy){statusConsumer.accept(tr("Espera a que termine la operación del archivo SQL."));return false;}
        draftTimer.stop();if(drafts==null)return true;SqlDraftStore.Workspace snapshot=draftSnapshot();
        try{draftWriter.submit(()->{drafts.saveWorkspace(snapshot);return null;}).get(5,java.util.concurrent.TimeUnit.SECONDS);draftDirty=false;return true;}
        catch(Exception error){return JOptionPane.showConfirmDialog(this,tr("No se pudo guardar el texto de las consultas. ¿Salir sin guardar el borrador?"),tr("Consultas sin guardar"),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)==JOptionPane.YES_OPTION;}
    }

    public void setSession(JdbcSession session) {
        if (this.session != session) for (int i=0;i<outputTabs.getTabCount();i++)
            if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel && panel.isShowing()) panel.finishEditing();
        this.session = session;
        readOnly.setSelected(true);
        sqlKeywords=SqlVocabulary.base();for(SqlEditorPane pane:queryEditors)pane.setKeywords(sqlKeywords);
        completionGeneration++;completionCatalogGeneration++;
        if(columnWorker!=null)columnWorker.cancel(true);
        columnCompletion=session==null?null:new SqlColumnCompletion(session);
        joinCompletion=session==null?null:new SqlJoinCompletion(session);joinInsertions.clear();
        columnWords.clear();tableWords.clear();completionWords.clear();addSqlKeywords();completionPopup.setVisible(false);
        if (session == null) {
            UiLanguage.text(state, UiLanguage.key("Sin conexión"));
            database.removeAllItems();
        } else {
            UiLanguage.text(state, session.profile().name(), session.profile().readOnly() ? UiLanguage.key(" · solo lectura") : "");
            try { autoCommit.setSelected(session.getAutoCommit()); } catch (SQLException ignored) {}
            loadDatabases();
        }
        updateActions();
    }

    public void insertSql(String sql) {
        openNewQuery(sql);
    }

    /** Generated SQL always receives an independent buffer and is never executed here. */
    public boolean openNewQuery(String sql){
        if(!canChangeQuery())return false;
        changingQuery=true;
        try{editor=addQuery(sql);activeQuery=queryEditors.size()-1;queryTabs.setSelectedIndex(activeQuery);editor.setCaretPosition(0);}
        finally{changingQuery=false;}
        readOnly.setSelected(true);
        completionGeneration++;completionPopup.setVisible(false);clearResultTabs();messages.setText("");scheduleDraft();updateEditorActions();editor.requestFocusInWindow();return true;
    }

    public void runSql(String sql) {
        if(openNewQuery(sql))executeSql();
    }

    public void executeCurrent() { executeSql(); }
    public void cancelCurrent() { cancelSql(); }
    void setColumnEditor(QueryResultPanel.ColumnEditor editor){columnEditor=editor;}

    public void selectDatabase(String name) {
        if (name == null || name.isBlank() || session == null) return;
        for (int i = 1; i < database.getItemCount(); i++) {
            if (name.equalsIgnoreCase(database.getItemAt(i))) {
                database.setSelectedIndex(i);
                return;
            }
        }
        database.addItem(name);
        database.setSelectedItem(name);
    }

    public String activeDatabase() {
        Object selected = database.getSelectedItem();
        return selected == null || SELECT_DATABASE.equals(selected.toString()) ? "" : selected.toString();
    }

    public void setDatabaseChangeConsumer(Consumer<String> consumer) {
        databaseChangeConsumer = consumer == null ? name -> {} : consumer;
    }

    public void setToolHandler(Consumer<String> handler){toolHandler=handler;}
    public String currentSql(){return editor==null?"":editor.getText();}
    public String analysisSql(){return editor==null?"":SqlText.selectedOrAll(editor);}
    public void setExternalBusy(boolean busy){externalBusy=Math.max(0,externalBusy+(busy?1:-1));updateActions();}
    public String aiContext(){
        String context="Motor: "+(session==null?"por especificar":session.profile().type())+"\nBase / esquema: "+activeDatabase()+"\nTablas conocidas (lista parcial):\n"+String.join("\n",tableWords.stream().limit(150).toList());
        if(currentResult!=null)context+="\nColumnas del resultado activo: "+String.join(", ",currentResult.columns());
        return context;
    }
    /** Snapshots ordered with the active result first; reporting never receives a database connection. */
    public List<com.nexodb.reporting.ReportData> reportSources(){
        List<com.nexodb.reporting.ReportData> sources=new ArrayList<>();
        for(int i=0;i<outputTabs.getTabCount();i++)if(outputTabs.getComponentAt(i) instanceof QueryResultPanel grid){
            QueryResult result=grid.result();
            var data=new com.nexodb.reporting.ReportData(outputTabs.getTitleAt(i)+(grid.hasChanges()?" · borrador":""),result.columns(),result.rows(),result.truncated());
            if(outputTabs.getSelectedIndex()==i)sources.add(0,data);else sources.add(data);
        }
        return List.copyOf(sources);
    }
    public JdbcSession currentSession(){return session;}
    public void undoCurrent(){if(editor!=null&&!fileBusy)editor.undoEdit();}
    public void redoCurrent(){if(editor!=null&&!fileBusy)editor.redoEdit();}
    private void updateEditorActions(){undoButton.setEnabled(editor!=null&&!fileBusy&&editor.canUndoEdit());redoButton.setEnabled(editor!=null&&!fileBusy&&editor.canRedoEdit());saveSqlButton.setEnabled(editor!=null&&!fileBusy);openSqlButton.setEnabled(!fileBusy);}
    boolean isDirty(SqlEditorPane pane){DocumentState info=documents.get(pane);return info!=null&&(info.savedHash.isEmpty()?!pane.getText().isEmpty():!SqlFiles.hash(pane.getText()).equals(info.savedHash));}
    private void refreshTitle(SqlEditorPane pane){
        DocumentState d=documents.get(pane);if(d==null)return;String mark=isDirty(pane)?" *":"";
        String name=d.path!=null?d.path.getFileName().toString():d.title;
        if(name.isBlank())UiLanguage.text(d.label,UiLanguage.key("Consulta")," ",d.number,mark);else UiLanguage.text(d.label,name,mark);
        int index=queryEditors.indexOf(pane);if(index>=0){queryTabs.setTitleAt(index,d.label.getText());queryTabs.setToolTipTextAt(index,d.path==null?d.label.getText():d.path.toString());}
        updateEditorActions();
    }
    private JMenuItem menuItem(JPopupMenu menu,String caption,IconFactory.Kind icon,String key,Runnable action){
        JMenuItem item=UiLanguage.text(new JMenuItem(tr(caption),IconFactory.of(icon,16)),UiLanguage.key(caption));if(key!=null)item.setAccelerator(KeyStroke.getKeyStroke(key));item.addActionListener(e->action.run());menu.add(item);return item;
    }
    JPopupMenu newQueryMenu(){
        JPopupMenu menu=new JPopupMenu();
        menuItem(menu,"Nueva consulta",IconFactory.Kind.ADD,"control T",()->openNewQuery(""));
        menuItem(menu,"Abrir archivo SQL…",IconFactory.Kind.FOLDER,"control O",this::openSqlFile);
        menu.addSeparator();
        menuItem(menu,"Constructor visual de consultas",IconFactory.Kind.TABLE,"control K",()->toolHandler.accept("builder"));
        menuItem(menu,"Diseñador de esquemas",IconFactory.Kind.SCHEMA,"control alt D",()->toolHandler.accept("designer"));
        menuItem(menu,"Buscar datos",IconFactory.Kind.SEARCH,"control shift D",()->toolHandler.accept("search"));
        menuItem(menu,"Diseñador de reportes",IconFactory.Kind.TABLE,null,()->toolHandler.accept("reports"));
        menuItem(menu,"Asistente de IA",IconFactory.Kind.SQL,null,()->toolHandler.accept("ai"));
        menuItem(menu,"Plan de ejecución",IconFactory.Kind.DIAGRAM,null,()->toolHandler.accept("plan"));
        menu.addSeparator();menuItem(menu,"Historial SQL",IconFactory.Kind.SQL,"control shift H",this::showHistory);return menu;
    }
    private JPopupMenu editorMenu(SqlEditorPane pane){
        JPopupMenu menu=new JPopupMenu();
        JMenuItem undo=menuItem(menu,"Deshacer",IconFactory.Kind.ROLLBACK,"control Z",pane::undoEdit),redo=menuItem(menu,"Rehacer",IconFactory.Kind.REFRESH,"control Y",pane::redoEdit);
        menu.addSeparator();menuItem(menu,"Cortar",IconFactory.Kind.EDIT,"control X",pane::cut);menuItem(menu,"Copiar",IconFactory.Kind.SQL,"control C",pane::copy);menuItem(menu,"Pegar",IconFactory.Kind.FOLDER,"control V",pane::paste);menuItem(menu,"Seleccionar todo",IconFactory.Kind.TABLE,"control A",pane::selectAll);
        menu.addSeparator();menuItem(menu,"Guardar consulta SQL…",IconFactory.Kind.SAVE,"control S",()->saveSql(pane,false,()->{}));
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener(){public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e){undo.setEnabled(!fileBusy&&pane.canUndoEdit());redo.setEnabled(!fileBusy&&pane.canRedoEdit());}public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e){}public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e){}});return menu;
    }
    public void renameCurrentQuery(){DocumentState d=documents.get(editor);String name=JOptionPane.showInputDialog(this,tr("Nombre de la consulta:"),d.title);if(name!=null&&!name.isBlank()){d.title=name.trim();refreshTitle(editor);scheduleDraft();}}
    private JFileChooser sqlChooser(){JFileChooser chooser=new JFileChooser();chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(tr("Archivo SQL"),"sql"));DocumentState d=documents.get(editor);if(d!=null&&d.path!=null)chooser.setSelectedFile(d.path.toFile());return chooser;}
    public void openSqlFile(){if(!canChangeQuery())return;JFileChooser chooser=sqlChooser();if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)openSqlPath(chooser.getSelectedFile().toPath());}
    void openSqlPath(Path path){fileOperation(()->SqlFiles.read(path),loaded->{openSqlLoaded(loaded);statusConsumer.accept(tr("Archivo SQL abierto: ")+loaded.path());});}
    boolean openSqlLoaded(SqlFiles.Loaded loaded){
        for(SqlEditorPane pane:queryEditors){DocumentState d=documents.get(pane);if(loaded.path().equals(d.path)){queryTabs.setSelectedIndex(queryEditors.indexOf(pane));return true;}}
        if(!openNewQuery(loaded.text()))return false;DocumentState d=documents.get(editor);d.path=loaded.path();d.savedHash=SqlFiles.hash(editor.getText());d.diskHash=loaded.diskHash();refreshTitle(editor);scheduleDraft();return true;
    }
    public void saveSqlFile(boolean saveAs){if(editor!=null)saveSql(editor,saveAs,()->{});}
    private void saveSql(SqlEditorPane pane,boolean saveAs,Runnable after){
        if(fileBusy)return;DocumentState d=documents.get(pane);Path target=d.path;
        if(saveAs||target==null){JFileChooser chooser=sqlChooser();chooser.setSelectedFile(new File(d.path!=null?d.path.toString():(d.title.isBlank()?"consulta":d.title)+".sql"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;target=SqlFiles.withExtension(chooser.getSelectedFile().toPath()).toAbsolutePath();
            if(Files.exists(target)&&JOptionPane.showConfirmDialog(this,tr("¿Reemplazar el archivo existente?")+"\n"+target,tr("Guardar SQL"),JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        }
        Path destination=target;String text=pane.getText();boolean check=d.path!=null&&d.path.equals(destination)&&!d.diskHash.isEmpty();String expected=d.diskHash;
        if(!confirm.test("Guardar archivo SQL",tr("Se guardará el texto de esta pestaña en el archivo indicado. ¿Continuar?")+"\n"+destination))return;
        pane.setEditable(false);
        fileOperation(()->{if(check&&(!Files.exists(destination)||!SqlFiles.diskHash(destination).equals(expected)))throw new java.io.IOException(tr("El archivo cambió fuera de NexoDB. Usa Guardar como para conservar tus cambios sin sobrescribirlo."));return SqlFiles.write(destination,text);},hash->{markSaved(pane,destination,text,hash);statusConsumer.accept(tr("Consulta guardada: ")+destination);if(!isDirty(pane))after.run();},()->pane.setEditable(true));
    }
    void markSaved(SqlEditorPane pane,Path path,String text,String diskHash){DocumentState d=documents.get(pane);d.path=path.toAbsolutePath();d.savedHash=SqlFiles.hash(text);d.diskHash=diskHash;refreshTitle(pane);scheduleDraft();}
    private <T> void fileOperation(java.util.concurrent.Callable<T> operation,Consumer<T> success){fileOperation(operation,success,()->{});}
    private <T> void fileOperation(java.util.concurrent.Callable<T> operation,Consumer<T> success,Runnable finished){
        if(fileBusy)return;fileBusy=true;updateEditorActions();busyConsumer.accept(true);
        new SwingWorker<T,Void>(){protected T doInBackground()throws Exception{return operation.call();}protected void done(){fileBusy=false;try{success.accept(get());}catch(Exception error){JOptionPane.showMessageDialog(QueryPanel.this,rootCause(error).getMessage(),tr("Archivo SQL"),JOptionPane.ERROR_MESSAGE);}finally{finished.run();busyConsumer.accept(false);updateEditorActions();}}}.execute();
    }
    public void showHistory(){
        var entries=history.entries();javax.swing.table.DefaultTableModel model=new javax.swing.table.DefaultTableModel(new Object[]{tr("Fecha"),tr("Conexión"),tr("Base"),"ms",tr("Filas"),tr("Estado")},0){public boolean isCellEditable(int r,int c){return false;}};
        for(var entry:entries)model.addRow(new Object[]{java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(entry.time()),entry.connection(),entry.database(),entry.millis(),entry.rows(),entry.error().isEmpty()?"OK":entry.error()});
        JTable table=new JTable(model);table.setAutoCreateRowSorter(true);SqlEditorPane sql=new SqlEditorPane();sql.setEditable(false);JTextField filter=new JTextField();UiLanguage.placeholder(filter,"Filtrar historial…");
        filter.getDocument().addDocumentListener(new DocumentListener(){void update(){@SuppressWarnings("unchecked") var sorter=(javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel>)table.getRowSorter();sorter.setRowFilter(filter.getText().isBlank()?null:RowFilter.regexFilter("(?iu)"+Pattern.quote(filter.getText())));}public void insertUpdate(DocumentEvent e){update();}public void removeUpdate(DocumentEvent e){update();}public void changedUpdate(DocumentEvent e){}});
        table.getSelectionModel().addListSelectionListener(e->{int row=table.getSelectedRow();if(row>=0)sql.restoreText(entries.get(table.convertRowIndexToModel(row)).sql());});
        JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(this),tr("Historial SQL"),Dialog.ModalityType.MODELESS);JPanel content=new JPanel(new BorderLayout(8,8));content.setBorder(new EmptyBorder(12,12,12,12));content.add(filter,BorderLayout.NORTH);JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(table),new JScrollPane(sql));split.setResizeWeight(.55);content.add(split);
        JButton reuse=new JButton(tr("Abrir en una consulta nueva")),clear=new JButton(tr("Limpiar historial"));JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));actions.add(clear);actions.add(reuse);content.add(actions,BorderLayout.SOUTH);
        reuse.addActionListener(e->{if(!sql.getText().isBlank()&&openNewQuery(sql.getText()))dialog.dispose();});clear.addActionListener(e->{if(JOptionPane.showConfirmDialog(dialog,tr("¿Borrar el historial local de consultas?"),tr("Historial SQL"),JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)try{history.clear();dialog.dispose();}catch(Exception error){statusConsumer.accept(error.getMessage());}});
        dialog.setContentPane(content);dialog.setSize(1000,650);dialog.setLocationRelativeTo(this);dialog.setVisible(true);
    }

    private void configureEditor(SqlEditorPane editor) {
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        editor.setMargin(new Insets(12, 14, 12, 14));
        editor.setTabSize(4);
        editor.setLineWrap(false);
        editor.setFocusTraversalKeysEnabled(false);
        UiLanguage.tooltip(editor,UiLanguage.key("Ejecutar selección o todas las sentencias (F5 o Ctrl+Enter)"));
        InputMap input = editor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = editor.getActionMap();
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_N,KeyEvent.CTRL_DOWN_MASK),"new-query");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_T,KeyEvent.CTRL_DOWN_MASK),"new-query");
        actions.put("new-query",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){openNewQuery("");}});
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "execute-sql");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "execute-sql");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-sql");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_S,KeyEvent.CTRL_DOWN_MASK),"save-sql");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_S,KeyEvent.CTRL_DOWN_MASK|KeyEvent.SHIFT_DOWN_MASK),"save-sql-as");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_O,KeyEvent.CTRL_DOWN_MASK),"open-sql");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_E,KeyEvent.CTRL_DOWN_MASK|KeyEvent.SHIFT_DOWN_MASK),"export-csv");
        actions.put("save-sql",new AbstractAction(){public void actionPerformed(ActionEvent e){saveSqlFile(false);}});
        actions.put("save-sql-as",new AbstractAction(){public void actionPerformed(ActionEvent e){saveSqlFile(true);}});
        actions.put("open-sql",new AbstractAction(){public void actionPerformed(ActionEvent e){openSqlFile();}});
        editor.setComponentPopupMenu(editorMenu(editor));
        actions.put("execute-sql", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { executeSql(); }});
        actions.put("cancel-sql", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { cancelSql(); }});
        actions.put("export-csv", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { exportData(); }});
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK), "show-completions");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "completion-down");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "completion-up");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "completion-enter");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "completion-tab");
        actions.put("show-completions", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { showCompletions(true); }});
        actions.put("completion-down", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { completionMove(1, DefaultEditorKit.downAction); }});
        actions.put("completion-up", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { completionMove(-1, DefaultEditorKit.upAction); }});
        actions.put("completion-enter", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { completionAcceptOrDefault(DefaultEditorKit.insertBreakAction); }});
        actions.put("completion-tab", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { completionAcceptOrDefault(DefaultEditorKit.insertTabAction); }});
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { if(editor==QueryPanel.this.editor)completionTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { if(editor==QueryPanel.this.editor)completionTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });
    }

    private JTable createResultTable(ResultTableModel model) {
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.getTableHeader().setReorderingAllowed(true);
        table.getTableHeader().setPreferredSize(new Dimension(0, 30));
        return table;
    }

    private JComponent buildToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(new EmptyBorder(7, 2, 7, 2));
        styleAction(execute, IconFactory.Kind.PLAY, tr("Ejecutar selección o todas las sentencias (F5 o Ctrl+Enter)"));
        styleAction(cancel, IconFactory.Kind.STOP, tr("Cancelar la consulta en ejecución (Esc)"));
        styleAction(commit, IconFactory.Kind.COMMIT, tr("Confirmar la transacción"));
        styleAction(rollback, IconFactory.Kind.ROLLBACK, tr("Revertir la transacción"));
        styleAction(export, IconFactory.Kind.EXPORT, tr("Exportar resultados (Ctrl+Shift+E)"));
        execute.addActionListener(e -> executeSql());
        cancel.addActionListener(e -> cancelSql());
        commit.addActionListener(e -> transaction(true));
        rollback.addActionListener(e -> transaction(false));
        export.addActionListener(e -> exportData());
        report.setIcon(IconFactory.of(IconFactory.Kind.TABLE,17));report.addActionListener(e->toolHandler.accept("reports"));
        autoCommit.addActionListener(e -> changeAutoCommit());
        UiLanguage.tooltip(openSqlButton,UiLanguage.key("Abrir archivo SQL (Ctrl+O)"));openSqlButton.addActionListener(e->openSqlFile());
        UiLanguage.tooltip(saveSqlButton,UiLanguage.key("Guardar consulta SQL (Ctrl+S)"));saveSqlButton.addActionListener(e->saveSqlFile(false));
        UiLanguage.tooltip(undoButton,UiLanguage.key("Deshacer (Ctrl+Z)"));undoButton.addActionListener(e->undoCurrent());
        UiLanguage.tooltip(redoButton,UiLanguage.key("Rehacer (Ctrl+Y)"));redoButton.addActionListener(e->redoCurrent());
        for(JButton button:List.of(openSqlButton,saveSqlButton,undoButton,redoButton)){button.putClientProperty("JButton.buttonType","toolBarButton");toolbar.add(button);}toolbar.addSeparator();
        toolbar.add(execute);
        toolbar.add(cancel);
        toolbar.add(readOnly);readOnly.addActionListener(e->{
            if(!readOnly.isSelected()&&(session==null||session.profile().readOnly()||!confirm.test("Habilitar cambios",tr("La conexión podrá ejecutar instrucciones que modifican datos o estructura. Cada ejecución de cambios pedirá confirmación. ¿Habilitar cambios?")+"\n"+session.profile().name())))readOnly.setSelected(true);
            if(readOnly.isSelected())for(int i=0;i<outputTabs.getTabCount();i++)if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel)panel.resetReadOnly();updateActions();
        });
        toolbar.addSeparator();
        toolbar.add(UiLanguage.text(new JLabel(tr("Base: ")), UiLanguage.key("Base: ")));
        database.setPrototypeDisplayValue("base_de_datos_XX");
        database.setMaximumSize(new Dimension(175, 30));
        database.setEnabled(false);
        database.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                if ((index == 0 || index == -1 && database.getSelectedIndex() == 0) && SELECT_DATABASE.equals(value)) {
                    label.setText(tr(SELECT_DATABASE));
                }
                return label;
            }
        });
        database.addActionListener(e -> changeDatabase());
        toolbar.add(database);
        toolbar.addSeparator();
        toolbar.add(limitRows);limitRows.addActionListener(e->maxRows.setEnabled(limitRows.isSelected()));UiLanguage.tooltip(limitRows,UiLanguage.key("Sin marcar: leer todas las filas que devuelva el SQL. Se respeta LIMIT o TOP del texto."));
        toolbar.add(maxRows);
        toolbar.addSeparator();
        toolbar.add(autoCommit);
        toolbar.add(commit);
        toolbar.add(rollback);
        toolbar.addSeparator();
        toolbar.add(export);
        toolbar.add(report);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(120, 16));
        progress.setMaximumSize(new Dimension(120, 16));
        progress.setVisible(false);
        toolbar.add(progress);
        toolbar.add(Box.createHorizontalGlue());
        state.setIcon(IconFactory.of(IconFactory.Kind.DATABASE, 16));
        state.setIconTextGap(6);
        state.setBorder(new EmptyBorder(4, 8, 4, 8));
        toolbar.add(state);
        return toolbar;
    }

    private void styleAction(AbstractButton button, IconFactory.Kind icon, String tooltip) {
        button.setIcon(IconFactory.of(icon, 17));
        button.setIconTextGap(6);
        UiLanguage.tooltip(button, UiLanguage.caption(tooltip));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setMargin(new Insets(5, 8, 5, 8));
    }

    private JComponent buildOutput() {
        messages.setEditable(false);
        messages.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        UiLanguage.addTab(outputTabs, new Object[]{UiLanguage.key("Mensajes")}, IconFactory.of(IconFactory.Kind.INFO, 16), messagesScroll);
        outputTabs.addChangeListener(e -> {
            Component selected = outputTabs.getSelectedComponent();
            currentResult = selected instanceof JComponent component
                    ? (QueryResult) component.getClientProperty(RESULT_PROPERTY) : null;
            updateActions();
        });
        outputTabs.putClientProperty("JTabbedPane.tabType", "card");
        return outputTabs;
    }

    private void executeSql() {
        if(!LicenseGate.ensure(this))return;
        if (queryRunning || externalBusy>0 || savingResult || !finishResultEditing() || savingResult || !confirmDiscardResults()) return;
        if (session == null || !session.isOpen()) {
            JOptionPane.showMessageDialog(this, tr("Conecta primero una base de datos."), tr("Sin conexión"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String sql = SqlText.selectedOrAll(editor);
        if (sql.isBlank()) return;
        List<String> statements;
        try {
            statements = SqlText.executionStatements(sql);
        } catch (java.io.IOException error) {
            appendMessage(tr("Error"), error.getMessage());
            outputTabs.setSelectedComponent(messagesScroll);
            return;
        }
        if (statements.isEmpty()) return;
        boolean readsOnly=statements.stream().allMatch(SqlExecutionPolicy::readOnly);
        if(!readsOnly&&(readOnly.isSelected()||session.profile().readOnly())){JOptionPane.showMessageDialog(this,tr("La consulta está en modo Solo lectura. Habilita cambios de forma explícita antes de ejecutar instrucciones de escritura."),tr("Solo lectura"),JOptionPane.INFORMATION_MESSAGE);return;}
        if(!readsOnly&&!confirm.test("Ejecutar cambios SQL",session.profile().name()+" · "+activeDatabase()+"\n"+tr("Se ejecutarán %s sentencias. Pueden modificar datos o estructura. ¿Continuar?",statements.size())+"\n\n"+sql.substring(0,Math.min(sql.length(),3000))))return;
        int limit = rowLimit();boolean executeReadOnly=readOnly.isSelected();
        clearResultTabs();
        appendMessage(tr("Ejecutando ") + statements.size() + (statements.size() == 1 ? tr(" sentencia") : tr(" sentencias")), preview(sql));
        JdbcSession executingSession = session;
        String executionDatabase=activeDatabase();
        worker = new SwingWorker<>() {
            @Override protected List<StatementOutcome> doInBackground() throws Exception {
                executingSession.setReadOnly(executeReadOnly);
                List<StatementOutcome> outcomes = new ArrayList<>();
                for (int i = 0; i < statements.size() && !isCancelled(); i++) {
                    String statement = statements.get(i);
                    long started=System.nanoTime();
                    try {
                        List<QueryResult> results=executingSession.executeAll(statement,limit);
                        for (QueryResult result : results) outcomes.add(new StatementOutcome(i+1,statement,result,null));
                        recordHistory(executingSession,executionDatabase,statement,(System.nanoTime()-started)/1_000_000,
                                results.stream().mapToLong(result->result.hasRows()?result.rows().size():Math.max(0,result.updateCount())).sum(),"");
                    } catch (Exception error) {
                        if (error instanceof JdbcSession.ExecutionException partial) {
                            for (QueryResult result : partial.results()) outcomes.add(new StatementOutcome(i+1,statement,result,null));
                        }
                        outcomes.add(new StatementOutcome(i + 1, statement, null, rootCause(error)));
                        recordHistory(executingSession,executionDatabase,statement,(System.nanoTime()-started)/1_000_000,0,rootCause(error).getMessage());
                        if(!readsOnly)break;
                    }
                }
                return outcomes;
            }

            @Override protected void done() {
                setRunning(false);
                if (isCancelled()) {
                    statusConsumer.accept(tr("Consulta cancelada"));
                    return;
                }
                try {
                    List<StatementOutcome> outcomes = get();
                    int resultTabs = 0;
                    int errors = 0;
                    long totalRows = 0;
                    for (StatementOutcome outcome : outcomes) {
                        if (outcome.error() != null) {
                            errors++;
                            appendMessage(tr("Error en sentencia ") + outcome.index(), preview(outcome.sql()) + "\n" + outcome.error().getMessage());
                            addMessageTab("Error " + outcome.index(), outcome.error().getMessage());
                        } else if (outcome.result().hasRows()) {
                            QueryResult result = outcome.result();
                            totalRows += result.rows().size();
                            addResultTab(resultTabName(outcome.sql(), outcome.index()), result, executingSession, outcome.sql(), limit);
                            resultTabs++;
                            String suffix = result.truncated() ? tr(" (límite alcanzado)") : "";
                            appendMessage(tr("Resultado ") + outcome.index(), result.rows().size() + tr(" filas") + suffix
                                    + tr(" en ") + result.elapsedMillis() + " ms");
                        } else {
                            QueryResult result = outcome.result();
                            appendMessage(tr("Sentencia ") + outcome.index(), result.updateCount() + tr(" filas afectadas en ")
                                    + result.elapsedMillis() + " ms");
                            addUpdateTab(tr("Sentencia ") + outcome.index(), result.updateCount(), result.elapsedMillis());
                        }
                    }
                    if (outputTabs.getTabCount() > 1) outputTabs.setSelectedIndex(0);
                    else outputTabs.setSelectedComponent(messagesScroll);
                    statusConsumer.accept(outcomes.stream().map(StatementOutcome::index).distinct().count() + tr(" sentencias · ") + totalRows + tr(" filas · ") + errors + tr(" errores"));
                } catch (Exception ex) {
                    Throwable cause = rootCause(ex);
                    appendMessage("Error", cause.getMessage());
                    outputTabs.setSelectedComponent(messagesScroll);
                    statusConsumer.accept(tr("Error al ejecutar la consulta"));
                }
            }
        };
        setRunning(true);
        worker.execute();
    }

    private void recordHistory(JdbcSession origin,String database,String sql,long millis,long rows,String error){try{history.add(new QueryHistoryStore.Entry(java.time.Instant.now(),origin.profile().name(),database,sql,millis,rows,error==null?"":error));}catch(Exception failure){SwingUtilities.invokeLater(()->statusConsumer.accept(tr("No se pudo guardar el historial SQL.")));}}

    private void cancelSql() {
        if (worker == null || worker.isDone() || session == null) return;
        try {
            session.cancel();
            worker.cancel(true);
            appendMessage(tr("Cancelada"), tr("La consulta fue cancelada."));
        } catch (SQLException e) {
            appendMessage(tr("Error al cancelar"), e.getMessage());
        }
    }

    private void transaction(boolean doCommit) {
        if (session == null || savingResult || !finishResultEditing() || savingResult) return;
        if(doCommit && hasPendingResultChanges()) {
            JOptionPane.showMessageDialog(this,tr("Pulsa Guardar en los resultados antes de hacer Commit."),tr("Cambios pendientes"),JOptionPane.INFORMATION_MESSAGE);return;
        }
        if(!confirm.test(doCommit?"Confirmar transacción":"Revertir transacción",tr(doCommit?"¿Confirmar los cambios pendientes de la transacción en esta conexión?":"¿Revertir los cambios pendientes de la transacción en esta conexión?")+"\n"+session.profile().name()))return;
        try {
            if (doCommit) session.commit(); else session.rollback();
            for (int i=0;i<outputTabs.getTabCount();i++) if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel && panel.origin()==session) panel.transactionFinished(!doCommit);
            appendMessage(doCommit ? "Commit" : "Rollback", tr("Transacción completada."));
        } catch (SQLException e) {
            appendMessage("Error", e.getMessage());
        }
    }

    private void changeAutoCommit() {
        if (session == null) return;
        if(savingResult || !finishResultEditing() || savingResult){try{autoCommit.setSelected(session.getAutoCommit());}catch(SQLException ignored){}return;}
        if(!confirm.test("Cambiar Auto-commit",tr(autoCommit.isSelected()?"Al activar Auto-commit se confirman los cambios de la transacción actual. ¿Continuar?":"Los siguientes cambios requerirán Commit o Rollback. ¿Desactivar Auto-commit?"))){autoCommit.setSelected(!autoCommit.isSelected());return;}
        try {
            session.setAutoCommit(autoCommit.isSelected());
            if(autoCommit.isSelected())for(int i=0;i<outputTabs.getTabCount();i++)if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel && panel.origin()==session)panel.transactionFinished(false);
        } catch (SQLException e) {
            autoCommit.setSelected(!autoCommit.isSelected());
            appendMessage("Error", e.getMessage());
        }
        updateActions();
    }

    private void exportData() {
        if(outputTabs.getSelectedComponent() instanceof QueryResultPanel panel)currentResult=panel.result();
        if (currentResult == null || !currentResult.hasRows()) return;
        String tableName = outputTabs.getTitleAt(outputTabs.getSelectedIndex());
        ExportChooser.Request request = ExportChooser.choose(this, tableName);
        if (request == null) return;
        QueryResult result = currentResult;
        export.setEnabled(false);
        progress.setVisible(true);
        busyConsumer.accept(true);
        statusConsumer.accept(tr("Exportando ") + tableName + "…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                DataExporter.write(result, request.path(), request.format(), tableName);
                return null;
            }
            @Override protected void done() {
                progress.setVisible(false);
                busyConsumer.accept(false);
                updateActions();
                try {
                    get();
                    statusConsumer.accept(request.format() + tr(" exportado: ") + request.path().getFileName());
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    JOptionPane.showMessageDialog(QueryPanel.this, cause.getMessage(), tr("No se pudo exportar"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void clearResultTabs() {
        for (int i = outputTabs.getTabCount() - 1; i >= 0; i--) {
            if (outputTabs.getComponentAt(i) != messagesScroll) outputTabs.removeTabAt(i);
        }
        currentResult = null;
        updateActions();
    }

    private void addResultTab(String requestedName, QueryResult result, JdbcSession origin, String sql, int limit) {
        ResultTableModel model = new ResultTableModel();
        model.setResult(result);
        JTable resultTable = createResultTable(model);
        resizeColumns(resultTable);
        QueryResultPanel panel = new QueryResultPanel(origin,result,model,resultTable,sql,limit, saving -> {
            savingResult=saving;progress.setVisible(saving);busyConsumer.accept(saving);updateActions();
        },statusConsumer,this::resizeColumns);
        panel.putClientProperty(RESULT_PROPERTY, result);
        panel.setColumnEditor(columnEditor);
        panel.setExportAction(this::exportData);
        panel.setWriteEnabled(()->readOnly.setSelected(false));panel.setRowLimit(this::rowLimit);
        panel.setAvailable(session, false);
        int messagesIndex = outputTabs.indexOfComponent(messagesScroll);
        outputTabs.insertTab(uniqueTabName(requestedName), IconFactory.of(IconFactory.Kind.TABLE, 16),
                panel, result.rows().size() + tr(" filas"), Math.max(0, messagesIndex));
    }

    private void addUpdateTab(String requestedName, int count, long elapsed) {
        JLabel label = new JLabel("<html><div style='text-align:center'><b>" + count
                + tr(" filas afectadas</b><br>Completado en ") + elapsed + " ms</div></html>", JLabel.CENTER);
        label.setBorder(new EmptyBorder(30, 30, 30, 30));
        JScrollPane panel = new JScrollPane(label);
        int messagesIndex = outputTabs.indexOfComponent(messagesScroll);
        outputTabs.insertTab(uniqueTabName(requestedName), IconFactory.of(IconFactory.Kind.INFO, 16),
                panel, null, Math.max(0, messagesIndex));
    }

    private void addMessageTab(String requestedName, String message) {
        JTextArea text = new JTextArea(message == null ? tr("Error desconocido") : message);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setMargin(new Insets(12, 14, 12, 14));
        JScrollPane panel = new JScrollPane(text);
        int messagesIndex = outputTabs.indexOfComponent(messagesScroll);
        outputTabs.insertTab(uniqueTabName(requestedName), IconFactory.of(IconFactory.Kind.CANCEL, 16),
                panel, null, Math.max(0, messagesIndex));
    }

    private String uniqueTabName(String requested) {
        String base = requested == null || requested.isBlank() ? tr("Resultado") : requested;
        String candidate = base;
        int suffix = 2;
        while (tabExists(candidate)) candidate = base + " (" + suffix++ + ")";
        return candidate;
    }

    private boolean tabExists(String name) {
        for (int i = 0; i < outputTabs.getTabCount(); i++) if (name.equalsIgnoreCase(outputTabs.getTitleAt(i))) return true;
        return false;
    }

    private static String resultTabName(String sql, int index) {
        Matcher matcher = TABLE_NAME_PATTERN.matcher(sql);
        if (!matcher.find()) return tr("Resultado ") + index;
        String name = matcher.group(1);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        name = name.replace("`", "").replace("\"", "");
        return name.isBlank() ? tr("Resultado ") + index : name;
    }

    private void resizeColumns(JTable table) {
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            int preferred = Math.max(90, Math.min(360, table.getColumnName(i).length() * 10 + 30));
            int sample = Math.min(table.getRowCount(), 30);
            for (int row = 0; row < sample; row++) {
                Object value = table.getValueAt(row, i);
                if (value != null) preferred = Math.max(preferred, Math.min(360, value.toString().length() * 8 + 24));
            }
            column.setPreferredWidth(preferred);
        }
    }

    private void setRunning(boolean running) {
        queryRunning=running;
        execute.setEnabled(!running && session != null);
        cancel.setEnabled(running);
        state.setText(running ? tr("Ejecutando…") : session == null ? tr("Sin conexión") : session.profile().name());
        progress.setVisible(running);
        busyConsumer.accept(running);
        updateActions();
    }

    private void updateActions() {
        boolean connected = session != null && session.isOpen();
        boolean running = savingResult || queryRunning || externalBusy>0;
        execute.setEnabled(connected && !running);
        cancel.setEnabled(connected && !savingResult && queryRunning);
        boolean manual = connected && !autoCommit.isSelected();
        commit.setEnabled(manual && !running);
        rollback.setEnabled(manual && !running);
        autoCommit.setEnabled(connected && !running);
        readOnly.setEnabled(connected&&!running&&!session.profile().readOnly());
        export.setEnabled(currentResult != null && currentResult.hasRows());
        report.setEnabled(currentResult!=null&&currentResult.hasRows()&&!running);
        database.setEnabled(connected && !running && database.getItemCount() > 1);
        for(int i=0;i<outputTabs.getTabCount();i++)if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel)panel.setAvailable(session,running);
    }
    private int rowLimit(){return limitRows.isSelected()?((Number)maxRows.getValue()).intValue():Integer.MAX_VALUE;}

    private boolean finishResultEditing() {
        for(int i=0;i<outputTabs.getTabCount();i++)if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel && !panel.finishEditing())return false;
        return true;
    }

    private boolean hasPendingResultChanges(){
        for(int i=0;i<outputTabs.getTabCount();i++)if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel && panel.origin()==session && panel.hasChanges())return true;
        return false;
    }
    public boolean confirmDiscardResults(){
        if(savingResult)return false;
        for(int i=0;i<outputTabs.getTabCount();i++)if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel && !panel.confirmDiscard())return false;
        return true;
    }

    private void loadDatabases() {
        database.removeAllItems();
        database.setEnabled(false);
        JdbcSession target = session;
        new SwingWorker<List<String>, Void>() {
            private Set<String> vocabulary=SqlVocabulary.base();
            @Override protected List<String> doInBackground() throws Exception {
                vocabulary=SqlVocabulary.forDatabase(target.connection().getMetaData());
                if (target.profile().type() == com.nexodb.model.DatabaseType.SQLITE) return List.of("main");
                if (target.profile().type() == com.nexodb.model.DatabaseType.POSTGRESQL) {
                    // PostgreSQL setCatalog is a no-op: another database needs a new connection.
                    return List.of(target.connection().getCatalog());
                }
                List<String> names = new ArrayList<>();
                try (ResultSet rs = target.connection().getMetaData().getCatalogs()) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_CAT");
                        if (name != null && !name.isBlank()) names.add(name);
                    }
                }
                if (names.isEmpty()) {
                    try (ResultSet rs = target.connection().getMetaData().getSchemas()) {
                        while (rs.next()) {
                            String name = rs.getString("TABLE_SCHEM");
                            if (name != null && !name.isBlank()) names.add(name);
                        }
                    }
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                return names.stream().distinct().toList();
            }

            @Override protected void done() {
                if (session != target) return;
                sqlKeywords=vocabulary;for(SqlEditorPane pane:queryEditors)pane.setKeywords(sqlKeywords);addSqlKeywords();
                try {
                    database.addItem(SELECT_DATABASE);
                    for (String name : get()) database.addItem(name);
                    for (int i = 1; i < database.getItemCount(); i++) completionWords.add(database.getItemAt(i));
                    boolean postgres = target.profile().type() == com.nexodb.model.DatabaseType.POSTGRESQL;
                    boolean sqlite = target.profile().type() == com.nexodb.model.DatabaseType.SQLITE;
                    database.setSelectedIndex(postgres || sqlite ? 1 : 0);
                    if (!postgres && !sqlite) try { target.connection().setCatalog(null); } catch (SQLException ignored) {}
                    state.setText(session.profile().name());
                } catch (Exception ignored) {
                }
                updateActions();
                if (target.profile().type() == com.nexodb.model.DatabaseType.POSTGRESQL || target.profile().type() == com.nexodb.model.DatabaseType.SQLITE) changeDatabase();
            }
        }.execute();
    }

    private void changeDatabase() {
        if (session == null || database.getSelectedItem() == null || !database.isEnabled()) return;
        String selected = database.getSelectedItem().toString();
        if (SELECT_DATABASE.equals(selected)) {
            try { session.connection().setCatalog(null); } catch (SQLException ignored) {}
            state.setText(session.profile().name());
            statusConsumer.accept(tr("Selecciona una base de datos"));
            databaseChangeConsumer.accept("");
            return;
        }
        try {
            if (session.profile().type() == com.nexodb.model.DatabaseType.POSTGRESQL
                    && !selected.equals(session.connection().getCatalog())) {
                throw new SQLException(tr("Para abrir otra base PostgreSQL, conecta de nuevo dejando Base vacía y selecciónala en la lista."));
            }
            session.connection().setCatalog(selected);
            state.setText(session.profile().name() + " · " + selected);
            statusConsumer.accept(tr("Base activa: ") + selected);
            loadCompletionObjects(selected);
            databaseChangeConsumer.accept(selected);
        } catch (SQLException error) {
            appendMessage(tr("No se pudo seleccionar la base"), error.getMessage());
        }
    }

    private void configureCompletionPopup() {
        completionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        completionList.setVisibleRowCount(10);
        completionList.setFixedCellHeight(24);
        completionList.setFocusable(false);
        completionList.setRequestFocusEnabled(false);
        completionList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.setIcon(IconFactory.of(columnWords.contains(String.valueOf(value)) ? IconFactory.Kind.COLUMN : isKeyword(String.valueOf(value)) ? IconFactory.Kind.SQL : IconFactory.Kind.TABLE, 15));
                label.setBorder(new EmptyBorder(2, 5, 2, 8));
                return label;
            }
        });
        completionList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int index = completionList.locationToIndex(e.getPoint());
                if (index >= 0 && completionList.getCellBounds(index, index).contains(e.getPoint())) {
                    completionList.setSelectedIndex(index);
                    acceptCompletion();
                }
            }
        });
        completionScroll.setFocusable(false);
        completionScroll.setRequestFocusEnabled(false);
        completionScroll.getViewport().setFocusable(false);
        completionScroll.setPreferredSize(new Dimension(330, 245));
        completionPopup.setFocusable(false);
        completionPopup.setRequestFocusEnabled(false);
        completionPopup.setLightWeightPopupEnabled(true);
        completionPopup.setBorder(UIManager.getBorder("PopupMenu.border"));
        completionPopup.add(completionScroll);
    }

    private void addSqlKeywords() {
        completionWords.addAll(sqlKeywords);
    }

    public void refreshCompletions(){
        if(columnCompletion!=null)columnCompletion.clear();
        if(!activeDatabase().isBlank())loadCompletionObjects(activeDatabase());
    }

    private void loadCompletionObjects(String selectedDatabase) {
        if (session == null || selectedDatabase == null || selectedDatabase.isBlank()) return;
        JdbcSession target = session;
        long request=++completionCatalogGeneration;
        completionGeneration++;columnWords.clear();tableWords.clear();completionWords.clear();addSqlKeywords();
        for(int i=1;i<database.getItemCount();i++)completionWords.add(database.getItemAt(i));
        if(columnCompletion!=null)columnCompletion.clear();
        boolean postgres = target.profile().type() == com.nexodb.model.DatabaseType.POSTGRESQL;
        busyConsumer.accept(true);
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                List<String> names = new ArrayList<>();
                boolean catalog = target.profile().type() == com.nexodb.model.DatabaseType.MYSQL
                        || target.profile().type() == com.nexodb.model.DatabaseType.MARIADB || postgres;
                try (ResultSet rs = target.connection().getMetaData().getTables(
                        catalog ? selectedDatabase : null, catalog ? null : selectedDatabase, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_NAME");
                        names.add(name);
                        if (postgres) {
                            String schema = rs.getString("TABLE_SCHEM");
                            if (schema != null && !schema.isBlank()) names.add(schema + "." + name);
                        }
                    }
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                return names;
            }

            @Override protected void done() {
                try {
                    if (session != target || request!=completionCatalogGeneration || !selectedDatabase.equals(activeDatabase())) return;
                    for (String name : get()) {
                        completionWords.add(name);tableWords.add(name);
                        if (!postgres) { completionWords.add(selectedDatabase + "." + name);tableWords.add(selectedDatabase + "." + name); }
                    }
                } catch (Exception ignored) {
                } finally {
                    busyConsumer.accept(false);
                }
            }
        }.execute();
    }

    private void showCompletions(boolean forced) {
        if (!editor.isFocusOwner()) return;
        String token = completionToken();
        String prefix = token.contains(".") ? token.substring(token.lastIndexOf('.') + 1) : token;
        final String filterPrefix = prefix.replace("`", "").replace("\"", "").replace("[", "").toLowerCase(Locale.ROOT);
        int caret=editor.getCaretPosition();String text=editor.getText();
        boolean joinPosition=SqlJoinCompletion.atJoin(text.substring(0,caret));
        if (!SqlColumnCompletion.inCode(text.substring(0,caret)) || !forced && !joinPosition && filterPrefix.length() < 2 && !token.contains(".")) { completionPopup.setVisible(false);completionGeneration++;return; }
        boolean tablePosition=Pattern.compile("(?is)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+[^\\s,()]*$").matcher(text.substring(0,caret)).find();
        boolean qualified=token.contains(".")&&!tablePosition;
        columnWords.clear();joinInsertions.clear();renderCompletions(filterPrefix,List.of(),qualified);
        if(tablePosition){for(int i=completionModel.size()-1;i>=0;i--)if(!tableWords.contains(completionModel.get(i)))completionModel.remove(i);if(completionModel.isEmpty())completionPopup.setVisible(false);else showCompletionList();}
        if(joinPosition&&joinCompletion!=null){
            long request=++completionGeneration;JdbcSession target=session;SqlJoinCompletion provider=joinCompletion;String db=activeDatabase();
            new SwingWorker<List<SqlJoinCompletion.Suggestion>,Void>(){protected List<SqlJoinCompletion.Suggestion> doInBackground()throws Exception{return provider.suggestions(text,caret,db);}protected void done(){if(request!=completionGeneration||session!=target||caret!=editor.getCaretPosition()||!text.equals(editor.getText())||!editor.isFocusOwner())return;try{List<String> fallback=new ArrayList<>();for(int i=0;i<completionModel.size();i++)fallback.add(completionModel.get(i));var suggestions=get();if(suggestions.isEmpty())return;completionModel.clear();joinInsertions.clear();for(var suggestion:suggestions){joinInsertions.put(suggestion.label(),suggestion.insertion());completionModel.addElement(suggestion.label());}for(String value:fallback)if(completionModel.size()<60)completionModel.addElement(value);showCompletionList();}catch(Exception ignored){}}}.execute();return;
        }
        if(tablePosition||columnCompletion==null)return;
        long request=++completionGeneration;
        if(columnWorker!=null)columnWorker.cancel(true);
        JdbcSession target=session;SqlColumnCompletion provider=columnCompletion;
        String statement=SqlText.currentStatement(text,caret),db=activeDatabase();
        String qualifier=qualified?token.substring(0,token.lastIndexOf('.')):"";
        List<String> known=List.copyOf(completionWords);
        columnWorker=new SwingWorker<>(){
            protected List<String> doInBackground()throws Exception{return provider.columns(statement,qualifier,db,known);}
            protected void done(){
                if(isCancelled()||request!=completionGeneration||session!=target||caret!=editor.getCaretPosition()||!text.equals(editor.getText())||!editor.isFocusOwner())return;
                try{List<String> columns=get();columnWords.clear();columnWords.addAll(columns);renderCompletions(filterPrefix,columns,qualified);}catch(Exception ignored){}
            }
        };
        columnWorker.execute();
    }

    private void renderCompletions(String filterPrefix,List<String> columns,boolean qualified) {
        completionModel.clear();
        Set<String> matches=new LinkedHashSet<>();
        columns.stream().filter(value->filterPrefix.isEmpty()||value.toLowerCase(Locale.ROOT).startsWith(filterPrefix)).sorted(String.CASE_INSENSITIVE_ORDER).forEach(matches::add);
        if(!qualified)completionWords.stream()
                .filter(value -> filterPrefix.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(filterPrefix))
                .sorted((a, b) -> {
                    boolean ak = isKeyword(a), bk = isKeyword(b);
                    if (ak != bk) return ak ? -1 : 1;
                    return a.compareToIgnoreCase(b);
                }).limit(60).forEach(matches::add);
        for (String match : matches.stream().limit(60).toList()) completionModel.addElement(match);
        if (completionModel.isEmpty()) { completionPopup.setVisible(false); return; }
        showCompletionList();
    }

    private void showCompletionList(){
        completionList.setSelectedIndex(0);
        int visibleRows = Math.max(1, Math.min(10, completionModel.size()));
        completionScroll.setPreferredSize(new Dimension(joinInsertions.isEmpty()?330:Math.min(780,Math.max(420,editor.getWidth()-30)), visibleRows * completionList.getFixedCellHeight() + 5));
        completionPopup.setPopupSize(completionScroll.getPreferredSize());
        try {
            Rectangle2D caret = editor.modelToView2D(editor.getCaretPosition());
            completionPopup.show(editor, (int) caret.getX(), (int) (caret.getY() + caret.getHeight()));
            SwingUtilities.invokeLater(editor::requestFocusInWindow);
        } catch (Exception ignored) {
            completionPopup.setVisible(false);
        }
    }

    private String completionToken() {
        int caret = editor.getCaretPosition();
        String text = editor.getText();
        int start = caret;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.' || c == '`' || c == '"' || c == '[' || c == ']')) break;
            start--;
        }
        return text.substring(start, caret);
    }

    private void completionMove(int direction, String defaultAction) {
        if (!completionPopup.isVisible()) { invokeEditorAction(defaultAction); return; }
        int next = Math.max(0, Math.min(completionModel.size() - 1, completionList.getSelectedIndex() + direction));
        completionList.setSelectedIndex(next);
        completionList.ensureIndexIsVisible(next);
    }

    private void completionAcceptOrDefault(String defaultAction) {
        if (completionPopup.isVisible() && completionList.getSelectedValue() != null) acceptCompletion();
        else invokeEditorAction(defaultAction);
    }

    private void invokeEditorAction(String name) {
        Action action = editor.getActionMap().get(name);
        if (action == null) {
            for (Action candidate : editor.getActions()) {
                if (name.equals(candidate.getValue(Action.NAME))) { action = candidate; break; }
            }
        }
        if (action != null) action.actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, name));
    }

    private void acceptCompletion() {
        String selected = completionList.getSelectedValue();
        if (selected == null) return;
        int caret = editor.getCaretPosition();
        String token = completionToken();
        if(joinInsertions.containsKey(selected)){replaceCompletion(caret-token.length(),caret,joinInsertions.get(selected));return;}
        if(tableWords.contains(selected)){
            String quote=session!=null&&(session.profile().type()==com.nexodb.model.DatabaseType.MYSQL||session.profile().type()==com.nexodb.model.DatabaseType.MARIADB)?"`":"\"";
            var edit=SqlTableAliases.complete(editor.getText(),caret,selected,quote);
            if(edit!=null){replaceCompletion(edit.start(),edit.end(),edit.insertion());return;}
        }
        int tail = token.lastIndexOf('.') + 1;
        int replaceLength = token.length() - tail;
        boolean column=columnWords.contains(selected);
        String insertion = !column && selected.contains(".") && token.contains(".") ? selected.substring(selected.lastIndexOf('.') + 1) : selected;
        String typed=token.substring(tail);
        if(column&&columnCompletion!=null&&(!insertion.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*")||sqlKeywords.contains(insertion.toUpperCase(Locale.ROOT))||typed.startsWith("`")||typed.startsWith("\"")||typed.startsWith("[")))insertion=columnCompletion.identifier(insertion);
        replaceCompletion(caret-replaceLength,caret,insertion);
    }

    private void replaceCompletion(int start, int end, String insertion) {
        editor.editAtomically(() -> {
            try {
                editor.getDocument().remove(start,end-start);
                editor.getDocument().insertString(start,insertion,null);
                editor.setCaretPosition(start+insertion.length());
            } catch (javax.swing.text.BadLocationException error) {
                throw new IllegalStateException(error);
            }
        });
        completionGeneration++;
        completionTimer.stop();
        completionPopup.setVisible(false);
        editor.requestFocusInWindow();
    }

    private boolean isKeyword(String value) { return value != null && sqlKeywords.contains(value.toUpperCase(Locale.ROOT)); }

    private record StatementOutcome(int index, String sql, QueryResult result, Throwable error) {}

    private void appendMessage(String title, String text) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        messages.append("[" + time + "] " + title + "\n" + (text == null ? "" : text) + "\n\n");
        messages.setCaretPosition(messages.getDocument().getLength());
    }

    private static String preview(String sql) {
        String singleLine = sql.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 240 ? singleLine.substring(0, 240) + "…" : singleLine;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }
}

