package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;
import com.nexodb.db.QueryResult;
import com.nexodb.db.SqlColumnCompletion;
import com.nexodb.util.DataExporter;
import com.nexodb.util.SqlText;
import com.nexodb.util.SqlDraftStore;

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
    private static final Set<String> SQL_KEYWORDS = Set.of(("SELECT FROM WHERE JOIN LEFT RIGHT INNER OUTER ON AS INSERT INTO VALUES UPDATE SET DELETE "
            + "CREATE ALTER DROP TABLE VIEW INDEX PRIMARY KEY FOREIGN REFERENCES GROUP BY ORDER HAVING LIMIT OFFSET "
            + "DISTINCT UNION ALL CASE WHEN THEN ELSE END AND OR NOT NULL IS LIKE IN EXISTS BETWEEN ASC DESC "
            + "USE CALL SHOW DESCRIBE EXPLAIN COUNT SUM AVG MIN MAX COMMIT ROLLBACK").split(" "));
    private static final String SELECT_DATABASE = "Seleccione una base…";
    private static final String RESULT_PROPERTY = "nexodb.queryResult";
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile(
            "(?is)\\b(?:FROM|UPDATE|INTO|TABLE)\\s+((?:[`\"\\w$-]+\\.)?[`\"\\w$-]+)");
    private final SqlEditorPane editor = new SqlEditorPane();
    private final JTextArea messages = new JTextArea();
    private final JScrollPane messagesScroll = new JScrollPane(messages);
    private final JTabbedPane outputTabs = new JTabbedPane();
    private final JSpinner maxRows = new JSpinner(new SpinnerNumberModel(1000, 1, 100000, 100));
    private final JCheckBox autoCommit = new JCheckBox("Auto-commit", true);
    private final JButton execute = UiLanguage.text(new JButton(tr("Ejecutar")), UiLanguage.key("Ejecutar"));
    private final JButton cancel = UiLanguage.text(new JButton(tr("Cancelar")), UiLanguage.key("Cancelar"));
    private final JButton commit = new JButton("Commit");
    private final JButton rollback = new JButton("Rollback");
    private final JButton export = UiLanguage.text(new JButton(tr("Exportar…")), UiLanguage.key("Exportar…"));
    private final JLabel state = UiLanguage.text(new JLabel(tr("Sin conexión")), UiLanguage.key("Sin conexión"));
    private final JComboBox<String> database = new JComboBox<>();
    private final JProgressBar progress = new JProgressBar();
    private final JPopupMenu completionPopup = new JPopupMenu();
    private final DefaultListModel<String> completionModel = new DefaultListModel<>();
    private final JList<String> completionList = new JList<>(completionModel);
    private final JScrollPane completionScroll = new JScrollPane(completionList);
    private final javax.swing.Timer completionTimer;
    private final Set<String> completionWords = new LinkedHashSet<>();
    private final Consumer<String> statusConsumer;
    private final Consumer<Boolean> busyConsumer;
    private Consumer<String> databaseChangeConsumer = name -> {};
    private JdbcSession session;
    private QueryResult currentResult;
    private SwingWorker<List<StatementOutcome>, Void> worker;
    private boolean savingResult;
    private SqlColumnCompletion columnCompletion;
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
        configureEditor();
        configureCompletionPopup();
        add(buildToolbar(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(editor), buildOutput());
        split.setResizeWeight(0.55);
        split.setDividerLocation(350);
        add(split, BorderLayout.CENTER);
        if(drafts!=null){
            try{drafts.load().ifPresent(draft->{restoringDraft=true;try{editor.restoreText(draft.text());editor.setCaretPosition(draft.caret());}finally{restoringDraft=false;}});}catch(Exception error){statusConsumer.accept(tr("No se pudo recuperar el borrador SQL."));}
            editor.getDocument().addDocumentListener(new DocumentListener(){private void changed(){scheduleDraft();}public void insertUpdate(DocumentEvent event){changed();}public void removeUpdate(DocumentEvent event){changed();}public void changedUpdate(DocumentEvent event){}});
            editor.addCaretListener(event->scheduleDraft());
        }
        updateActions();
    }
    private void scheduleDraft(){if(drafts!=null&&!restoringDraft){draftDirty=true;draftTimer.restart();}}
    private SqlDraftStore.Draft draftSnapshot(){return new SqlDraftStore.Draft(editor.getText(),editor.getCaretPosition());}
    private void saveDraftAsync(){
        if(drafts==null||!draftDirty)return;SqlDraftStore.Draft snapshot=draftSnapshot();draftDirty=false;
        draftWriter.submit(()->{try{drafts.save(snapshot);}catch(Exception error){SwingUtilities.invokeLater(()->{draftDirty=true;statusConsumer.accept(tr("No se pudieron guardar automáticamente las consultas."));});}});
    }
    public boolean saveDraftBeforeClose(){
        draftTimer.stop();if(drafts==null)return true;SqlDraftStore.Draft snapshot=draftSnapshot();
        try{draftWriter.submit(()->{drafts.save(snapshot);return null;}).get(5,java.util.concurrent.TimeUnit.SECONDS);draftDirty=false;return true;}
        catch(Exception error){return JOptionPane.showConfirmDialog(this,tr("No se pudo guardar el texto de las consultas. ¿Salir sin guardar el borrador?"),tr("Consultas sin guardar"),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)==JOptionPane.YES_OPTION;}
    }

    public void setSession(JdbcSession session) {
        if (this.session != session) for (int i=0;i<outputTabs.getTabCount();i++)
            if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel && panel.isShowing()) panel.finishEditing();
        this.session = session;
        completionGeneration++;completionCatalogGeneration++;
        if(columnWorker!=null)columnWorker.cancel(true);
        columnCompletion=session==null?null:new SqlColumnCompletion(session);
        columnWords.clear();completionWords.clear();addSqlKeywords();completionPopup.setVisible(false);
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
        if (!editor.getText().isBlank() && !editor.getText().endsWith("\n")) editor.append("\n\n");
        editor.append(sql);
        editor.requestFocusInWindow();
        editor.setCaretPosition(editor.getDocument().getLength());
    }

    public void runSql(String sql) {
        insertSql(sql);
        executeSql();
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

    private void configureEditor() {
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        editor.setMargin(new Insets(12, 14, 12, 14));
        editor.setTabSize(4);
        editor.setLineWrap(false);
        editor.setText(tr("-- NexoDB Studio\n-- Selecciona una consulta o coloca el cursor dentro de ella y presiona Ctrl+Enter.\n\n"));
        InputMap input = editor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = editor.getActionMap();
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "execute-sql");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "execute-sql");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-sql");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK), "export-csv");
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
            @Override public void insertUpdate(DocumentEvent e) { completionTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { completionTimer.restart(); }
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
        styleAction(execute, IconFactory.Kind.PLAY, tr("Ejecutar selección o sentencia actual (F5 o Ctrl+Enter)"));
        styleAction(cancel, IconFactory.Kind.STOP, tr("Cancelar la consulta en ejecución (Esc)"));
        styleAction(commit, IconFactory.Kind.COMMIT, tr("Confirmar la transacción"));
        styleAction(rollback, IconFactory.Kind.ROLLBACK, tr("Revertir la transacción"));
        styleAction(export, IconFactory.Kind.EXPORT, tr("Exportar a CSV, TSV, JSON, XML, HTML o SQL (Ctrl+S)"));
        execute.addActionListener(e -> executeSql());
        cancel.addActionListener(e -> cancelSql());
        commit.addActionListener(e -> transaction(true));
        rollback.addActionListener(e -> transaction(false));
        export.addActionListener(e -> exportData());
        autoCommit.addActionListener(e -> changeAutoCommit());
        toolbar.add(execute);
        toolbar.add(cancel);
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
        toolbar.add(UiLanguage.text(new JLabel(tr("Máx. filas: ")), UiLanguage.key("Máx. filas: ")));
        toolbar.add(maxRows);
        toolbar.addSeparator();
        toolbar.add(autoCommit);
        toolbar.add(commit);
        toolbar.add(rollback);
        toolbar.addSeparator();
        toolbar.add(export);
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
        if (savingResult || !finishResultEditing() || savingResult || !confirmDiscardResults()) return;
        if (session == null || !session.isOpen()) {
            JOptionPane.showMessageDialog(this, tr("Conecta primero una base de datos."), tr("Sin conexión"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String selected = editor.getSelectedText();
        boolean batchSelection = selected != null && !selected.isBlank();
        String sql = batchSelection ? selected.trim() : SqlText.selectedOrCurrent(editor);
        if (sql.isBlank()) return;
        List<String> statements;
        String incompleteTail = "";
        if (batchSelection) {
            SqlText.Batch batch = SqlText.splitCompleteStatements(sql);
            if (batch.statements().isEmpty()) {
                statements = List.of(sql);
            } else {
                statements = batch.statements();
                incompleteTail = batch.incompleteTail();
            }
        } else {
            statements = List.of(sql);
        }
        int limit = ((Number) maxRows.getValue()).intValue();
        clearResultTabs();
        appendMessage(tr("Ejecutando ") + statements.size() + (statements.size() == 1 ? tr(" sentencia") : tr(" sentencias")), preview(sql));
        if (!incompleteTail.isBlank()) {
            appendMessage(tr("Sentencia omitida"), tr("El texto final no termina en punto y coma:\n") + preview(incompleteTail));
        }
        JdbcSession executingSession = session;
        worker = new SwingWorker<>() {
            @Override protected List<StatementOutcome> doInBackground() throws Exception {
                applyUseFromEditor(executingSession);
                List<StatementOutcome> outcomes = new ArrayList<>();
                for (int i = 0; i < statements.size() && !isCancelled(); i++) {
                    String statement = statements.get(i);
                    try {
                        outcomes.add(new StatementOutcome(i + 1, statement, executingSession.execute(statement, limit), null));
                    } catch (Exception error) {
                        outcomes.add(new StatementOutcome(i + 1, statement, null, rootCause(error)));
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
                    statusConsumer.accept(outcomes.size() + tr(" sentencias · ") + totalRows + tr(" filas · ") + errors + tr(" errores"));
                } catch (Exception ex) {
                    Throwable cause = rootCause(ex);
                    appendMessage("Error", cause.getMessage());
                    outputTabs.setSelectedIndex(1);
                    statusConsumer.accept(tr("Error al ejecutar la consulta"));
                }
            }
        };
        setRunning(true);
        worker.execute();
    }

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
        execute.setEnabled(!running && session != null);
        cancel.setEnabled(running);
        state.setText(running ? tr("Ejecutando…") : session == null ? tr("Sin conexión") : session.profile().name());
        progress.setVisible(running);
        busyConsumer.accept(running);
        updateActions();
    }

    private void updateActions() {
        boolean connected = session != null && session.isOpen();
        boolean running = savingResult || worker != null && !worker.isDone();
        execute.setEnabled(connected && !running);
        cancel.setEnabled(connected && !savingResult && running);
        boolean manual = connected && !autoCommit.isSelected();
        commit.setEnabled(manual && !running);
        rollback.setEnabled(manual && !running);
        autoCommit.setEnabled(connected && !running);
        export.setEnabled(currentResult != null && currentResult.hasRows());
        database.setEnabled(connected && !running && database.getItemCount() > 1);
        for(int i=0;i<outputTabs.getTabCount();i++)if(outputTabs.getComponentAt(i) instanceof QueryResultPanel panel)panel.setAvailable(session,running);
    }

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
            @Override protected List<String> doInBackground() throws Exception {
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
                try {
                    database.addItem(SELECT_DATABASE);
                    for (String name : get()) database.addItem(name);
                    for (int i = 1; i < database.getItemCount(); i++) completionWords.add(database.getItemAt(i));
                    boolean postgres = target.profile().type() == com.nexodb.model.DatabaseType.POSTGRESQL;
                    database.setSelectedIndex(postgres ? 1 : 0);
                    if (!postgres) try { target.connection().setCatalog(null); } catch (SQLException ignored) {}
                    state.setText(session.profile().name());
                } catch (Exception ignored) {
                }
                updateActions();
                if (target.profile().type() == com.nexodb.model.DatabaseType.POSTGRESQL) changeDatabase();
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

    private void applyUseFromEditor(JdbcSession executingSession) throws SQLException {
        Matcher matcher = Pattern.compile("(?im)^\\s*USE\\s+[`\\\"]?([a-zA-Z0-9_$-]+)[`\\\"]?\\s*;?").matcher(editor.getText());
        String selected = null;
        while (matcher.find()) selected = matcher.group(1);
        if (selected != null && !selected.isBlank()) {
            executingSession.connection().setCatalog(selected);
            final String active = selected;
            SwingUtilities.invokeLater(() -> { if(session==executingSession) selectDatabase(active); });
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
        completionWords.addAll(SQL_KEYWORDS);
    }

    public void refreshCompletions(){
        if(columnCompletion!=null)columnCompletion.clear();
        if(!activeDatabase().isBlank())loadCompletionObjects(activeDatabase());
    }

    private void loadCompletionObjects(String selectedDatabase) {
        if (session == null || selectedDatabase == null || selectedDatabase.isBlank()) return;
        JdbcSession target = session;
        long request=++completionCatalogGeneration;
        completionGeneration++;columnWords.clear();completionWords.clear();addSqlKeywords();
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
                        completionWords.add(name);
                        if (!postgres) completionWords.add(selectedDatabase + "." + name);
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
        if (!SqlColumnCompletion.inCode(text.substring(0,caret)) || !forced && filterPrefix.length() < 2 && !token.contains(".")) { completionPopup.setVisible(false);completionGeneration++;return; }
        boolean tablePosition=Pattern.compile("(?is)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+[^\\s,()]*$").matcher(text.substring(0,caret)).find();
        boolean qualified=token.contains(".")&&!tablePosition;
        columnWords.clear();renderCompletions(filterPrefix,List.of(),qualified);
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
        completionList.setSelectedIndex(0);
        int visibleRows = Math.max(1, Math.min(10, completionModel.size()));
        completionScroll.setPreferredSize(new Dimension(330, visibleRows * completionList.getFixedCellHeight() + 5));
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
        int tail = token.lastIndexOf('.') + 1;
        int replaceLength = token.length() - tail;
        boolean column=columnWords.contains(selected);
        String insertion = !column && selected.contains(".") && token.contains(".") ? selected.substring(selected.lastIndexOf('.') + 1) : selected;
        String typed=token.substring(tail);
        if(column&&columnCompletion!=null&&(!insertion.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*")||SQL_KEYWORDS.contains(insertion.toUpperCase(Locale.ROOT))||typed.startsWith("`")||typed.startsWith("\"")||typed.startsWith("[")))insertion=columnCompletion.identifier(insertion);
        try {
            editor.getDocument().remove(caret - replaceLength, replaceLength);
            editor.getDocument().insertString(caret - replaceLength, insertion, null);
        } catch (Exception ignored) {
        }
        completionTimer.stop();
        completionPopup.setVisible(false);
        editor.requestFocusInWindow();
    }

    private static boolean isKeyword(String value) { return value != null && SQL_KEYWORDS.contains(value); }

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

