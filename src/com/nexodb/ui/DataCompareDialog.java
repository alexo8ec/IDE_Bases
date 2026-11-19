package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;
import com.nexodb.model.DatabaseType;
import com.nexodb.util.BackgroundOperations;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Primary-key based data comparison between databases from one or two active connections. */
public final class DataCompareDialog extends JDialog {
    private final JComboBox<SessionOption> sourceConnection = new JComboBox<>();
    private final JComboBox<SessionOption> targetConnection = new JComboBox<>();
    private final BiConsumer<JdbcSession, String> scriptConsumer;
    private final JComboBox<String> source = new JComboBox<>();
    private final JComboBox<String> target = new JComboBox<>();
    private final JTextField tableFilter = new JTextField();
    private final JCheckBox includeViews = UiLanguage.text(new JCheckBox(tr("Incluir vistas")), UiLanguage.key("Incluir vistas"));
    private final JCheckBox ignoreCase = UiLanguage.text(new JCheckBox(tr("Ignorar mayúsculas/minúsculas en textos")), UiLanguage.key("Ignorar mayúsculas/minúsculas en textos"));
    private final JCheckBox trimText = UiLanguage.text(new JCheckBox(tr("Ignorar espacios al inicio y al final"), true), UiLanguage.key("Ignorar espacios al inicio y al final"));
    private final JCheckBox nullEqualsEmpty = UiLanguage.text(new JCheckBox(tr("Tratar NULL y texto vacío como iguales")), UiLanguage.key("Tratar NULL y texto vacío como iguales"));
    private final JCheckBox ignoreLobs = UiLanguage.text(new JCheckBox(tr("Ignorar columnas BLOB/CLOB"), true), UiLanguage.key("Ignorar columnas BLOB/CLOB"));
    private final JSpinner maxRows = new JSpinner(new SpinnerNumberModel(5000, 1, 1_000_000, 1000));
    private final JButton chooseTables = UiLanguage.text(new JButton(tr("Seleccionar tablas…"), IconFactory.of(IconFactory.Kind.TABLE, 16)), UiLanguage.key("Seleccionar tablas…"));
    private final JButton configureMapping = UiLanguage.text(new JButton(tr("Mapear columnas y claves…"), IconFactory.of(IconFactory.Kind.KEY, 16)), UiLanguage.key("Mapear columnas y claves…"));
    private final Set<String> selectedTables = new LinkedHashSet<>();
    private final Map<String, TableMapping> mappings = new LinkedHashMap<>();
    private boolean tableSelectionConfigured;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{tr("Tabla"), tr("Estado"), tr("Origen"), tr("Destino"), tr("Faltan"), tr("Adicionales"), tr("Modificadas"), tr("Detalle")}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable results = UiLanguage.headers(new JTable(model));
    private final JTextArea scriptPreview = new JTextArea();
    private final JTabbedPane outputTabs = new JTabbedPane();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = UiLanguage.text(new JLabel(tr("Selecciona las bases que deseas comparar.")), UiLanguage.key("Selecciona las bases que deseas comparar."));
    private final JButton compare = UiLanguage.text(new JButton(tr("Comparar datos"), IconFactory.of(IconFactory.Kind.COMPARE, 16)), UiLanguage.key("Comparar datos"));
    private final JButton cancelCompare = UiLanguage.text(new JButton(tr("Cancelar"), IconFactory.of(IconFactory.Kind.STOP, 16)), UiLanguage.key("Cancelar"));
    private final JButton generate = UiLanguage.text(new JButton(tr("Abrir script en editor"), IconFactory.of(IconFactory.Kind.SQL, 16)), UiLanguage.key("Abrir script en editor"));
    private final JButton saveScript = UiLanguage.text(new JButton(tr("Guardar .sql"), IconFactory.of(IconFactory.Kind.SAVE, 16)), UiLanguage.key("Guardar .sql"));
    private Comparison comparison;
    private final ComparisonChoices choices;
    private final ComparisonDatabaseSelector databaseSelector;
    private final ComparisonDatabaseSelector.Side sourceDatabases, targetDatabases;
    private boolean comparing, disposed, choosing;
    private long selectionRevision;
    private JdbcSession comparisonTarget;
    private SwingWorker<Comparison, Void> compareWorker;
    private ComparisonControl comparisonControl;
    private BackgroundOperations.Operation comparisonOperation;

    public DataCompareDialog(Window owner, JdbcSession activeSession, List<JdbcSession> sessions, BiConsumer<JdbcSession, String> scriptConsumer) {
        this(owner, activeSession, sessions, scriptConsumer, new ComparisonChoices());
    }
    public DataCompareDialog(Window owner, JdbcSession activeSession, List<JdbcSession> sessions, BiConsumer<JdbcSession, String> scriptConsumer, ComparisonChoices choices) {
        super(owner, tr("Comparar datos"), ModalityType.APPLICATION_MODAL);
        UiLanguage.title(this, UiLanguage.key("Comparar datos"));
        this.scriptConsumer = scriptConsumer; this.choices = choices;
        selectedTables.addAll(choices.tables()); tableSelectionConfigured = choices.tablesConfigured(); includeViews.setSelected(choices.includeViews());
        if (tableSelectionConfigured) UiLanguage.text(chooseTables, UiLanguage.key("Tablas seleccionadas: "), selectedTables.size());
        databaseSelector = new ComparisonDatabaseSelector(choices, this::selectionChanged,
                error -> UiLanguage.text(status, UiLanguage.key("No se pudieron cargar las bases: "), rootCause(error).getMessage()));
        sourceDatabases = databaseSelector.side(source, true); targetDatabases = databaseSelector.side(target, false);
        for (JdbcSession session : sessions) if (session != null && session.isOpen()) {
            SessionOption option = new SessionOption(session);
            sourceConnection.addItem(option); targetConnection.addItem(option);
        }
        selectSession(sourceConnection, choices.preferred(true,sessions) == null ? activeSession : choices.preferred(true,sessions));
        if (choices.preferred(false,sessions) == null) selectOtherSession(targetConnection, activeSession);
        else selectSession(targetConnection, choices.preferred(false,sessions));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(980, 620));
        setSize(1220, 760);
        setLocationRelativeTo(owner);
        setContentPane(buildContent());
        configureTable();
        loadDatabases(sourceSession(), source, true);
        loadDatabases(targetSession(), target, false);
    }

    public void showDialog() { setVisible(true); }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));
        root.add(buildOptions(), BorderLayout.NORTH);
        scriptPreview.setEditable(false);
        scriptPreview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        scriptPreview.setMargin(new Insets(10, 12, 10, 12));
        UiLanguage.addTab(outputTabs, new Object[]{UiLanguage.key("Resultados")}, IconFactory.of(IconFactory.Kind.COMPARE, 16), new JScrollPane(results));
        UiLanguage.addTab(outputTabs, new Object[]{UiLanguage.key("Script de sincronización")}, IconFactory.of(IconFactory.Kind.SQL, 16), new JScrollPane(scriptPreview));
        outputTabs.putClientProperty("JTabbedPane.tabType", "card");
        root.add(outputTabs, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        status.setIcon(IconFactory.of(IconFactory.Kind.INFO, 15));
        bottom.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(180, 18));
        progress.setVisible(false);
        generate.setEnabled(false);
        saveScript.setEnabled(false);
        JButton close = UiLanguage.text(new JButton(tr("Cerrar"), IconFactory.of(IconFactory.Kind.CANCEL, 16)), UiLanguage.key("Cerrar"));
        compare.addActionListener(e -> compare());
        cancelCompare.addActionListener(e -> requestComparisonCancel());
        generate.addActionListener(e -> generateScript());
        saveScript.addActionListener(e -> saveScript());
        close.addActionListener(e -> dispose());
        cancelCompare.setEnabled(false);
        actions.add(progress); actions.add(compare); actions.add(cancelCompare); actions.add(generate); actions.add(saveScript); actions.add(close);
        bottom.add(actions, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private JComponent buildOptions() {
        JPanel container = new JPanel(new BorderLayout(8, 8));
        JPanel endpoints = new JPanel(new GridBagLayout());
        endpoints.setBorder(UiLanguage.border("Origen y destino"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 7, 5, 7); c.fill = GridBagConstraints.HORIZONTAL; c.gridy = 0;
        endpoints.add(UiLanguage.text(new JLabel(tr("Instancia de origen:")), UiLanguage.key("Instancia de origen:")), c);
        c.gridx = 1; c.weightx = 1; endpoints.add(sourceConnection, c);
        c.gridx = 2; c.weightx = 0;
        JButton swap = new JButton("⇄", IconFactory.of(IconFactory.Kind.COMPARE, 16));
        swap.addActionListener(e -> {
            Object leftConnection = sourceConnection.getSelectedItem();
            String leftDatabase = (String) source.getSelectedItem();
            Object rightConnection = targetConnection.getSelectedItem();
            String rightDatabase = (String) target.getSelectedItem();
            sourceConnection.setSelectedItem(rightConnection); targetConnection.setSelectedItem(leftConnection);
            sourceDatabases.load(sourceSession(), rightDatabase); targetDatabases.load(targetSession(), leftDatabase);
        });
        endpoints.add(swap, c);
        c.gridx = 3; endpoints.add(UiLanguage.text(new JLabel(tr("Instancia de destino:")), UiLanguage.key("Instancia de destino:")), c);
        c.gridx = 4; c.weightx = 1; endpoints.add(targetConnection, c);
        c.gridx = 0; c.gridy = 1; c.weightx = 0; endpoints.add(UiLanguage.text(new JLabel(tr("Base de origen:")), UiLanguage.key("Base de origen:")), c);
        c.gridx = 1; c.weightx = 1; endpoints.add(source, c);
        c.gridx = 3; c.weightx = 0; endpoints.add(UiLanguage.text(new JLabel(tr("Base de destino:")), UiLanguage.key("Base de destino:")), c);
        c.gridx = 4; c.weightx = 1; endpoints.add(target, c);
        c.gridx = 0; c.gridy = 2; c.weightx = 0; endpoints.add(UiLanguage.text(new JLabel(tr("Filtrar tablas:")), UiLanguage.key("Filtrar tablas:")), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        UiLanguage.placeholder(tableFilter, "Vacío para comparar todas; admite texto parcial");
        endpoints.add(tableFilter, c);
        c.gridx = 3; c.gridwidth = 1; c.weightx = 0; endpoints.add(UiLanguage.text(new JLabel(tr("Máx. filas por tabla:")), UiLanguage.key("Máx. filas por tabla:")), c);
        c.gridx = 4; c.weightx = 1; endpoints.add(maxRows, c);
        c.gridx = 0; c.gridy = 3; c.gridwidth = 5; c.weightx = 1;
        chooseTables.addActionListener(e -> chooseTables());
        configureMapping.addActionListener(e -> configureMapping());
        JPanel tableActions = new JPanel(new GridLayout(1, 2, 8, 0));
        tableActions.add(chooseTables); tableActions.add(configureMapping);
        endpoints.add(tableActions, c);
        sourceConnection.addActionListener(e -> { loadDatabases(sourceSession(), source, true); });
        targetConnection.addActionListener(e -> { loadDatabases(targetSession(), target, false); });
        includeViews.addActionListener(e -> selectionChanged());
        container.add(endpoints, BorderLayout.NORTH);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 3));
        options.setBorder(UiLanguage.border("Opciones de comparación"));
        options.add(ignoreCase); options.add(trimText); options.add(nullEqualsEmpty); options.add(ignoreLobs); options.add(includeViews);
        container.add(options, BorderLayout.SOUTH);
        return container;
    }

    private void configureTable() {
        results.setAutoCreateRowSorter(true);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setRowHeight(27);
        results.setShowGrid(false);
        results.setIntercellSpacing(new Dimension(0, 1));
        int[] widths = {190, 130, 75, 75, 75, 85, 90, 360};
        for (int i = 0; i < widths.length; i++) results.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        results.getColumnModel().getColumn(1).setCellRenderer(new StatusRenderer());
        results.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) showDetails();
            }
        });
    }

    @Override public void dispose() { disposed = true; cancelActiveComparison(); if (databaseSelector != null) databaseSelector.close(); super.dispose(); }
    private void loadDatabases(JdbcSession endpoint, JComboBox<String> combo, boolean sourceSide) {
        (sourceSide ? sourceDatabases : targetDatabases).load(endpoint);
    }
    private void updateActions() {
        boolean ready = sourceDatabases.ready() && targetDatabases.ready() && !comparing && !choosing;
        compare.setEnabled(ready); chooseTables.setEnabled(ready); configureMapping.setEnabled(ready);
    }
    private void selectionChanged() {
        selectionRevision++; comparison = null; comparisonTarget = null;
        mappings.clear(); updateMappingCaption();
        generate.setEnabled(false); saveScript.setEnabled(false); scriptPreview.setText("");
        choices.tables(selectedTables, tableSelectionConfigured, includeViews.isSelected());
        updateActions();
        UiLanguage.text(status, UiLanguage.key("Selecciona las instancias, las bases y las tablas que deseas comparar."));
    }

    private void compare() {
        JdbcSession sourceSession = sourceSession();
        JdbcSession targetSession = targetSession();
        String sourceDb = (String) source.getSelectedItem();
        String targetDb = (String) target.getSelectedItem();
        if (sourceSession == null || targetSession == null || sourceDb == null || targetDb == null
                || (sourceSession == targetSession && sourceDb.equalsIgnoreCase(targetDb))) {
            JOptionPane.showMessageDialog(this, tr("Selecciona un origen y un destino diferentes."), tr("Comparar datos"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Options options = new Options(ignoreCase.isSelected(), trimText.isSelected(), nullEqualsEmpty.isSelected(),
                ignoreLobs.isSelected(), includeViews.isSelected(), ((Number) maxRows.getValue()).intValue(), tableFilter.getText().trim(),
                Set.copyOf(selectedTables), tableSelectionConfigured, Map.copyOf(mappings));
        long request = selectionRevision;
        comparing = true; updateActions(); cancelCompare.setEnabled(true); generate.setEnabled(false); model.setRowCount(0);
        saveScript.setEnabled(false); scriptPreview.setText(""); outputTabs.setSelectedIndex(0);
        progress.setValue(0); progress.setString(tr("Preparando…")); progress.setVisible(true);
        UiLanguage.text(status, UiLanguage.key("Comparando "), sourceSession.profile().name(), "/", sourceDb, UiLanguage.key(" con "), targetSession.profile().name(), "/", targetDb, "…");
        ComparisonControl control = new ComparisonControl(); comparisonControl = control;
        String operationConnection=sourceSession.profile().name()+" → "+targetSession.profile().name();
        BackgroundOperations.Operation operation=BackgroundOperations.create(BackgroundOperations.Kind.MIGRATION,tr("Comparar datos"),operationConnection,this::cancelActiveComparison,()->SwingUtilities.invokeLater(this::compare),Math.max(300,BackgroundOperations.timeoutSeconds()));comparisonOperation=operation;
        SwingWorker<Comparison, Void> worker = new SwingWorker<>() {
            @Override protected Comparison doInBackground() throws Exception {
                operation.awaitTurn();
                return buildComparison(sourceSession, sourceDb, targetSession, targetDb, options, value->{setProgress(value);operation.progress(value,tr("Comparando filas…"));},control);
            }
            @Override protected void done() {
                comparing = false; updateActions(); cancelCompare.setEnabled(false); progress.setVisible(false);
                if (disposed || request != selectionRevision) {operation.cancelled(tr("Resultado descartado porque cambió la selección"));if(compareWorker==this)compareWorker=null;if(comparisonControl==control)comparisonControl=null;if(comparisonOperation==operation)comparisonOperation=null;return;}
                try {
                    if(isCancelled()||control.cancelled())throw new CancellationException();
                    comparison = get(); comparisonTarget = targetSession;
                    for (TableDifference difference : comparison.tables()) {
                        model.addRow(new Object[]{difference.table(), difference.status(),
                                difference.sourceRows() < 0 ? "—" : difference.sourceRows(),
                                difference.targetRows() < 0 ? "—" : difference.targetRows(),
                                difference.missing(), difference.extra(), difference.changed(), difference.detail()});
                    }
                    generate.setEnabled(!comparison.script().isBlank());
                    saveScript.setEnabled(!comparison.script().isBlank());
                    scriptPreview.setText(comparison.script());
                    scriptPreview.setCaretPosition(0);
                    long changedTables = comparison.tables().stream().filter(t -> !t.status().equals(tr("Igual"))).count();
                    UiLanguage.text(status, (comparison.tables().size()), UiLanguage.key(" tablas revisadas · "), changedTables, UiLanguage.key(" con diferencias"));
                    operation.complete(comparison.tables().size()+tr(" tablas"));
                } catch (Exception error) {
                    Throwable cause=rootCause(error);
                    if(cause instanceof CancellationException||control.cancelled()){operation.cancelled(tr("Comparación cancelada"));UiLanguage.text(status,UiLanguage.key("Comparación cancelada de forma segura."));}
                    else{operation.fail(cause);UiLanguage.text(status, UiLanguage.key("Error al comparar datos"));JOptionPane.showMessageDialog(DataCompareDialog.this, cause.getMessage(),tr("No se pudieron comparar los datos"), JOptionPane.ERROR_MESSAGE);}
                } finally {
                    if(compareWorker==this)compareWorker=null;if(comparisonControl==control)comparisonControl=null;if(comparisonOperation==operation)comparisonOperation=null;
                }
            }
        };
        compareWorker=worker;
        worker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) {
                int value = (Integer) event.getNewValue(); progress.setValue(value); progress.setString(value + "%");
            }
        });
        worker.execute();
    }

    private void requestComparisonCancel(){BackgroundOperations.Operation operation=comparisonOperation;if(operation!=null)operation.requestCancel();else cancelActiveComparison();}
    private void cancelActiveComparison(){ComparisonControl control=comparisonControl;if(control!=null)control.cancel();SwingWorker<?,?> worker=compareWorker;if(worker!=null&&!worker.isDone())worker.cancel(true);}

    private void configureMapping() {
        JdbcSession sourceSession = sourceSession(), targetSession = targetSession();
        String sourceDb = (String) source.getSelectedItem(), targetDb = (String) target.getSelectedItem();
        if (sourceSession == null || targetSession == null || sourceDb == null || targetDb == null) return;
        long request = selectionRevision; choosing = true; updateActions();
        progress.setIndeterminate(true); progress.setString(tr("Buscando tablas compatibles…")); progress.setVisible(true);
        new SwingWorker<List<TablePair>, Void>() {
            @Override protected List<TablePair> doInBackground() throws Exception {
                Map<String,String> left=tableNames(sourceSession,sourceDb,includeViews.isSelected());
                Map<String,String> right=tableNames(targetSession,targetDb,includeViews.isSelected());
                return left.entrySet().stream().filter(e->right.containsKey(e.getKey()))
                        .filter(e->!tableSelectionConfigured||selectedTables.contains(e.getKey()))
                        .map(e->new TablePair(e.getKey(),e.getValue(),right.get(e.getKey())))
                        .sorted(Comparator.comparing(TablePair::source,String.CASE_INSENSITIVE_ORDER)).toList();
            }
            @Override protected void done(){
                choosing=false;updateActions();progress.setVisible(false);progress.setIndeterminate(false);
                if(disposed||request!=selectionRevision)return;
                try{
                    List<TablePair> pairs=get();
                    if(pairs.isEmpty()){JOptionPane.showMessageDialog(DataCompareDialog.this,tr("No hay tablas equivalentes disponibles para configurar."),tr("Mapeo de columnas"),JOptionPane.INFORMATION_MESSAGE);return;}
                    TablePair pair=(TablePair)JOptionPane.showInputDialog(DataCompareDialog.this,tr("Selecciona la tabla cuyo mapeo deseas configurar:"),tr("Mapeo de columnas y claves"),JOptionPane.PLAIN_MESSAGE,IconFactory.of(IconFactory.Kind.KEY,24),pairs.toArray(),pairs.get(0));
                    if(pair!=null)loadMappingEditor(sourceSession,sourceDb,targetSession,targetDb,pair,request);
                }catch(Exception error){JOptionPane.showMessageDialog(DataCompareDialog.this,rootCause(error).getMessage(),tr("No se pudo preparar el mapeo"),JOptionPane.ERROR_MESSAGE);}
            }
        }.execute();
    }

    private void loadMappingEditor(JdbcSession sourceSession,String sourceDb,JdbcSession targetSession,String targetDb,TablePair pair,long request){
        choosing=true;updateActions();progress.setIndeterminate(true);progress.setString(tr("Leyendo columnas y claves…"));progress.setVisible(true);
        new SwingWorker<MappingDraft,Void>(){
            @Override protected MappingDraft doInBackground()throws Exception{return new MappingDraft(pair,columns(sourceSession,sourceDb,pair.source(),false),columns(targetSession,targetDb,pair.target(),false),primaryKeys(sourceSession,sourceDb,pair.source()),primaryKeys(targetSession,targetDb,pair.target()),mappings.get(pair.key()));}
            @Override protected void done(){choosing=false;updateActions();progress.setVisible(false);progress.setIndeterminate(false);if(disposed||request!=selectionRevision)return;try{showMappingEditor(get());}catch(Exception error){JOptionPane.showMessageDialog(DataCompareDialog.this,rootCause(error).getMessage(),tr("No se pudieron leer las columnas"),JOptionPane.ERROR_MESSAGE);}}
        }.execute();
    }

    private void showMappingEditor(MappingDraft draft){
        DefaultTableModel mappingModel=new DefaultTableModel(new Object[]{tr("Comparar"),tr("Columna de origen"),tr("Tipo de origen"),tr("Columna de destino"),tr("Tipo de destino"),tr("Clave")},0){
            @Override public Class<?> getColumnClass(int column){return column==0||column==5?Boolean.class:String.class;}
            @Override public boolean isCellEditable(int row,int column){return column==0||column==3||column==5;}
        };
        Map<String,ColumnMeta> targetByName=new LinkedHashMap<>();for(ColumnMeta column:draft.targetColumns())targetByName.put(column.name().toLowerCase(Locale.ROOT),column);
        Map<String,ColumnMapping> configured=new HashMap<>();if(draft.current()!=null)for(ColumnMapping mapping:draft.current().columns())configured.put(mapping.source().toLowerCase(Locale.ROOT),mapping);
        for(ColumnMeta sourceColumn:draft.sourceColumns()){
            ColumnMapping saved=configured.get(sourceColumn.name().toLowerCase(Locale.ROOT));
            ColumnMeta targetColumn=saved==null?autoTarget(sourceColumn,draft.targetColumns()):targetByName.get(saved.target().toLowerCase(Locale.ROOT));
            boolean included=targetColumn!=null;
            boolean key=saved!=null?saved.key():included&&containsIgnoreCase(draft.sourceKeys(),sourceColumn.name())&&containsIgnoreCase(draft.targetKeys(),targetColumn.name());
            mappingModel.addRow(new Object[]{included,sourceColumn.name(),sourceColumn.typeName(),included?targetColumn.name():"",included?targetColumn.typeName():"",key});
        }
        JTable grid=new JTable(mappingModel);grid.setRowHeight(26);grid.setAutoCreateRowSorter(true);JComboBox<String> targets=new JComboBox<>();targets.addItem("");for(ColumnMeta column:draft.targetColumns())targets.addItem(column.name());grid.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(targets));
        mappingModel.addTableModelListener(e->{if(e.getColumn()!=3||e.getFirstRow()<0||e.getFirstRow()>=mappingModel.getRowCount())return;String selected=String.valueOf(mappingModel.getValueAt(e.getFirstRow(),3));ColumnMeta column=targetByName.get(selected.toLowerCase(Locale.ROOT));mappingModel.setValueAt(column==null?"":column.typeName(),e.getFirstRow(),4);mappingModel.setValueAt(column!=null,e.getFirstRow(),0);if(column==null)mappingModel.setValueAt(false,e.getFirstRow(),5);});
        JLabel help=new JLabel("<html>"+tr("Relaciona columnas aunque tengan nombres diferentes. Marca una o varias columnas Clave que identifiquen cada fila de forma única.")+"<br>"+tr("Las columnas sin Comparar se omiten del análisis y del script de sincronización.")+"</html>");
        JPanel content=new JPanel(new BorderLayout(8,8));content.add(help,BorderLayout.NORTH);content.add(new JScrollPane(grid));content.setPreferredSize(new Dimension(920,480));
        while(true){
            int answer=JOptionPane.showConfirmDialog(this,content,tr("Mapeo: ")+draft.pair().source()+" → "+draft.pair().target(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
            if(answer!=JOptionPane.OK_OPTION)return;if(grid.isEditing())grid.getCellEditor().stopCellEditing();
            try{
                List<ColumnMapping> selected=new ArrayList<>();Set<String> destinations=new HashSet<>();int keys=0;
                for(int row=0;row<mappingModel.getRowCount();row++)if(Boolean.TRUE.equals(mappingModel.getValueAt(row,0))){String sourceName=String.valueOf(mappingModel.getValueAt(row,1)),targetName=String.valueOf(mappingModel.getValueAt(row,3)).trim();boolean key=Boolean.TRUE.equals(mappingModel.getValueAt(row,5));if(targetName.isBlank())throw new IllegalArgumentException(tr("Cada columna incluida necesita una columna de destino."));if(!destinations.add(targetName.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException(tr("Una columna de destino no puede utilizarse dos veces."));selected.add(new ColumnMapping(sourceName,targetName,key));if(key)keys++;}
                if(selected.isEmpty())throw new IllegalArgumentException(tr("Selecciona al menos una columna para comparar."));if(keys==0)throw new IllegalArgumentException(tr("Selecciona al menos una columna Clave."));
                mappings.put(draft.pair().key(),new TableMapping(draft.pair().source(),draft.pair().target(),List.copyOf(selected)));updateMappingCaption();selectionRevision++;comparison=null;comparisonTarget=null;generate.setEnabled(false);saveScript.setEnabled(false);scriptPreview.setText("");status.setText(tr("Mapeo guardado para ")+draft.pair().source()+tr(". Pulsa Comparar datos para aplicarlo."));return;
            }catch(IllegalArgumentException error){JOptionPane.showMessageDialog(this,error.getMessage(),tr("Mapeo incompleto"),JOptionPane.WARNING_MESSAGE);}
        }
    }

    private void updateMappingCaption(){UiLanguage.text(configureMapping,UiLanguage.key(mappings.isEmpty()?"Mapear columnas y claves…":"Mapeos personalizados: "),mappings.isEmpty()?"":mappings.size());}
    private static ColumnMeta autoTarget(ColumnMeta source,List<ColumnMeta> targets){for(ColumnMeta target:targets)if(target.name().equalsIgnoreCase(source.name()))return target;String canonical=canonical(source.name());for(ColumnMeta target:targets)if(canonical(target.name()).equals(canonical))return target;return null;}
    private static boolean containsIgnoreCase(List<String> values,String expected){return values.stream().anyMatch(value->value.equalsIgnoreCase(expected));}
    private static String canonical(String value){return value.replaceAll("[^\\p{L}\\p{N}]","").toLowerCase(Locale.ROOT);}

    Comparison buildComparison(JdbcSession sourceSession, String sourceDb, JdbcSession targetSession, String targetDb, Options options,
                                       java.util.function.IntConsumer progressConsumer) throws SQLException {
        return buildComparison(sourceSession,sourceDb,targetSession,targetDb,options,progressConsumer,new ComparisonControl());
    }

    private Comparison buildComparison(JdbcSession sourceSession, String sourceDb, JdbcSession targetSession, String targetDb, Options options,
                                       java.util.function.IntConsumer progressConsumer,ComparisonControl control) throws SQLException {
        Map<String, String> sourceTables = tableNames(sourceSession, sourceDb, options.includeViews());
        Map<String, String> targetTables = tableNames(targetSession, targetDb, options.includeViews());
        Set<String> keys = new LinkedHashSet<>(); keys.addAll(sourceTables.keySet()); keys.addAll(targetTables.keySet());
        List<String> filtered = keys.stream()
                .filter(key -> !options.tableSelectionConfigured() || options.selectedTables().contains(key))
                .filter(key -> options.tableFilter().isBlank()
                        || key.contains(options.tableFilter().toLowerCase(Locale.ROOT))).toList();
        if (options.tableSelectionConfigured() && filtered.isEmpty())
            throw new SQLException(tr("Las tablas seleccionadas no están disponibles en estas bases. Revisa la selección."));
        List<TableDifference> differences = new ArrayList<>();
        StringBuilder script = new StringBuilder();
        int current = 0;
        for (String key : filtered) {
            control.check();
            String sourceTable = sourceTables.get(key), targetTable = targetTables.get(key);
            if (sourceTable == null) {
                differences.add(new TableDifference(targetTable, tr("Solo en destino"), 0, -1, 0, 0, 0, tr("No existe en origen"), List.of()));
            } else if (targetTable == null) {
                differences.add(new TableDifference(sourceTable, tr("Solo en origen"), -1, 0, 0, 0, 0, tr("No existe en destino"), List.of()));
            } else {
                int completed=current,total=Math.max(1,filtered.size());
                TableDifference difference = compareTable(sourceSession, sourceDb, targetSession, targetDb, sourceTable, targetTable, options, script,control,rows->{int within=(int)Math.min(99,rows*100L/Math.max(1L,options.maxRows()*2L));progressConsumer.accept(Math.min(99,(completed*100+within)/total));});
                differences.add(difference);
            }
            current++;
            progressConsumer.accept(filtered.isEmpty() ? 100 : Math.min(100, current * 100 / filtered.size()));
        }
        progressConsumer.accept(100);
        return new Comparison(differences, script.toString());
    }

    private Map<String, String> tableNames(JdbcSession endpoint, String database, boolean views) throws SQLException {
        Map<String, String> names = new LinkedHashMap<>();
        boolean catalog = usesCatalog(endpoint);
        String[] types = views ? new String[]{"TABLE", "VIEW"} : new String[]{"TABLE"};
        try (ResultSet rs = endpoint.connection().getMetaData().getTables(catalog ? database : null,
                catalog ? null : database, "%", types)) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                names.put(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return names;
    }

    private TableDifference compareTable(JdbcSession sourceSession, String sourceDb, JdbcSession targetSession, String targetDb,
                                         String sourceTable, String targetTable,
                                         Options options, StringBuilder script,ComparisonControl control,java.util.function.LongConsumer rowProgress) throws SQLException {
        List<ColumnMeta> sourceColumns = columns(sourceSession, sourceDb, sourceTable, options.ignoreLobs());
        List<ColumnMeta> targetColumns = columns(targetSession, targetDb, targetTable, options.ignoreLobs());
        TableMapping configured=options.mappings().get(sourceTable.toLowerCase(Locale.ROOT));
        Map<String, ColumnMeta> targetByName = new HashMap<>();
        for (ColumnMeta column : targetColumns) targetByName.put(column.name().toLowerCase(Locale.ROOT), column);
        List<String> sourcePk = primaryKeys(sourceSession, sourceDb, sourceTable);
        List<String> targetPk = primaryKeys(targetSession, targetDb, targetTable);
        Map<String,ColumnMeta> sourceByName=new HashMap<>();for(ColumnMeta column:sourceColumns)sourceByName.put(column.name().toLowerCase(Locale.ROOT),column);
        List<ColumnPair> pairs=new ArrayList<>();
        if(configured!=null){
            for(ColumnMapping mapping:configured.columns()){ColumnMeta left=sourceByName.get(mapping.source().toLowerCase(Locale.ROOT)),right=targetByName.get(mapping.target().toLowerCase(Locale.ROOT));if(left!=null&&right!=null)pairs.add(new ColumnPair(left,right,mapping.key()));}
        }else{
            boolean compatible=!sourcePk.isEmpty()&&lower(sourcePk).equals(lower(targetPk));
            for(ColumnMeta column:sourceColumns){ColumnMeta target=targetByName.get(column.name().toLowerCase(Locale.ROOT));if(target!=null)pairs.add(new ColumnPair(column,target,compatible&&containsIgnoreCase(sourcePk,column.name())));}
        }
        List<String> sourceKeys=pairs.stream().filter(ColumnPair::key).map(pair->pair.source().name()).toList();
        List<String> targetKeys=pairs.stream().filter(ColumnPair::key).map(pair->pair.target().name()).toList();
        if (pairs.isEmpty()) return new TableDifference(sourceTable, tr("Sin columnas comunes"), -1, -1, 0, 0, 0, tr("No se puede comparar"), List.of());
        if(sourceKeys.isEmpty()) {
            return new TableDifference(sourceTable, tr("Sin clave compatible"), -1, -1, 0, 0, 0,
                    tr("Se necesita una clave compatible; usa Mapear columnas y claves"), List.of());
        }
        int missing = 0, extra = 0, changed = 0;
        List<String> details = new ArrayList<>();
        boolean leftTruncated,rightTruncated;int leftRows,rightRows;
        try(RowCursor left=new RowCursor(sourceSession,sourceDb,sourceTable,pairs.stream().map(ColumnPair::source).toList(),sourceKeys,options,control);
            RowCursor right=new RowCursor(targetSession,targetDb,targetTable,pairs.stream().map(ColumnPair::target).toList(),targetKeys,options,control)){
            boolean hasLeft=left.advance(),hasRight=right.advance();long processed=0;
            while(hasLeft||hasRight){
                control.check();int order=!hasLeft?1:!hasRight?-1:compareKeys(left.key(),right.key());
                if(order<0){missing++;if(details.size()<100)details.add(tr("Falta en destino: ")+left.key().display());appendInsert(script,targetSession,targetDb,targetTable,pairs,left.row());hasLeft=left.advance();}
                else if(order>0){extra++;if(details.size()<100)details.add(tr("Adicional en destino: ")+right.key().display());hasRight=right.advance();}
                else{if(!left.row().normalized().equals(right.row().normalized())){changed++;if(details.size()<100)details.add(tr("Modificada: ")+left.key().display());appendUpdate(script,targetSession,targetDb,targetTable,pairs,left.row());}hasLeft=left.advance();hasRight=right.advance();}
                if((++processed&255)==0)rowProgress.accept(left.count()+right.count());
            }
            leftRows=left.count();rightRows=right.count();leftTruncated=left.truncated();rightTruncated=right.truncated();rowProgress.accept(leftRows+rightRows);
        }
        String state = missing == 0 && extra == 0 && changed == 0
                ? (leftTruncated || rightTruncated ? tr("Parcial") : tr("Igual")) : tr("Diferente");
        String note = (leftTruncated || rightTruncated) ? tr("Límite alcanzado; comparación parcial") : pairs.size() + tr(" columnas comparadas")+(configured==null?"":tr(" · mapeo personalizado"));
        return new TableDifference(sourceTable, state, leftRows, rightRows, missing, extra, changed, note, details);
    }

    private List<ColumnMeta> columns(JdbcSession endpoint, String database, String table, boolean ignoreLarge) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>();
        boolean catalog = usesCatalog(endpoint);
        try (ResultSet rs = endpoint.connection().getMetaData().getColumns(catalog ? database : null,
                catalog ? null : database, table, "%")) {
            while (rs.next()) {
                int type = rs.getInt("DATA_TYPE");
                if (ignoreLarge && isLargeType(type)) continue;
                columns.add(new ColumnMeta(rs.getString("COLUMN_NAME"), type, Objects.toString(rs.getString("TYPE_NAME"),jdbcTypeName(type))));
            }
        }
        return columns;
    }

    private List<String> primaryKeys(JdbcSession endpoint, String database, String table) throws SQLException {
        Map<Short, String> keys = new HashMap<>();
        boolean catalog = usesCatalog(endpoint);
        try (ResultSet rs = endpoint.connection().getMetaData().getPrimaryKeys(catalog ? database : null,
                catalog ? null : database, table)) {
            while (rs.next()) keys.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return keys.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    private final class RowCursor implements AutoCloseable{
        private final List<ColumnMeta> columns;private final List<String> keys;private final Options options;private final ComparisonControl control;private final Statement statement;private final ResultSet rows;private RowKey key,previous;private RowData row;private int count;private boolean done,truncated;
        RowCursor(JdbcSession endpoint,String database,String table,List<ColumnMeta> columns,List<String> keys,Options options,ComparisonControl control)throws SQLException{
            this.columns=columns;this.keys=keys;this.options=options;this.control=control;String selected=columns.stream().map(c->quote(endpoint,c.name())).collect(java.util.stream.Collectors.joining(", ")),order=keys.stream().map(value->quote(endpoint,value)).collect(java.util.stream.Collectors.joining(", "));
            Statement opened=endpoint.connection().createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);control.register(opened);try{opened.setFetchSize(Math.min(500,options.maxRows()));opened.setMaxRows(options.maxRows()+1);ResultSet result=opened.executeQuery("SELECT "+selected+" FROM "+qualified(endpoint,database,table)+" ORDER BY "+order);statement=opened;rows=result;}catch(SQLException|RuntimeException error){control.unregister(opened);try{opened.close();}catch(Exception ignored){}throw error;}
        }
        boolean advance()throws SQLException{control.check();if(done)return false;if(count>=options.maxRows()){truncated=rows.next();done=true;key=null;row=null;return false;}if(!rows.next()){done=true;key=null;row=null;return false;}Map<String,Object> raw=new LinkedHashMap<>();List<Object> normalized=new ArrayList<>();for(int i=0;i<columns.size();i++){Object value=safeValue(rows,i+1,columns.get(i).sqlType());raw.put(columns.get(i).name().toLowerCase(Locale.ROOT),value);normalized.add(normalize(value,options));}List<Object> values=new ArrayList<>();List<String> display=new ArrayList<>();for(String name:keys){int index=columnIndex(columns,name);Object value=index<0?null:raw.get(columns.get(index).name().toLowerCase(Locale.ROOT));values.add(normalize(value,options));display.add(name+"="+String.valueOf(value));}RowKey next=new RowKey(List.copyOf(values),String.join(", ",display));if(previous!=null){int order=compareKeys(previous,next);if(order>0)throw new SQLException(tr("El orden de la clave no es compatible entre los motores; selecciona otra clave o ajusta la collation."));if(order==0)throw new SQLException(tr("La clave seleccionada no es única después de aplicar las opciones de texto: ")+next.display());}previous=next;key=next;row=new RowData(raw,List.copyOf(normalized));count++;return true;}
        RowKey key(){return key;}RowData row(){return row;}int count(){return count;}boolean truncated(){return truncated;}
        public void close(){control.unregister(statement);try{rows.close();}catch(Exception ignored){}try{statement.close();}catch(Exception ignored){}}
    }

    private static int compareKeys(RowKey left,RowKey right){for(int i=0;i<Math.min(left.values().size(),right.values().size());i++){int compared=compareKeyValue(left.values().get(i),right.values().get(i));if(compared!=0)return compared;}return Integer.compare(left.values().size(),right.values().size());}
    @SuppressWarnings({"rawtypes","unchecked"}) private static int compareKeyValue(Object left,Object right){if(Objects.equals(left,right))return 0;if(left==NullValue.INSTANCE)return -1;if(right==NullValue.INSTANCE)return 1;if(left instanceof Number a&&right instanceof Number b)try{return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));}catch(NumberFormatException ignored){}if(left.getClass()==right.getClass()&&left instanceof Comparable value)try{return value.compareTo(right);}catch(RuntimeException ignored){}return left.toString().compareTo(right.toString());}

    private void appendInsert(StringBuilder sql, JdbcSession endpoint, String database, String table, List<ColumnPair> columns, RowData row) {
        sql.append("INSERT INTO ").append(qualified(endpoint, database, table)).append(" (")
                .append(columns.stream().map(c -> quote(endpoint, c.target().name())).collect(java.util.stream.Collectors.joining(", ")))
                .append(") VALUES (")
                .append(columns.stream().map(c -> literal(row.raw().get(c.source().name().toLowerCase(Locale.ROOT))))
                        .collect(java.util.stream.Collectors.joining(", "))).append(");\n");
    }

    private void appendUpdate(StringBuilder sql, JdbcSession endpoint, String database, String table, List<ColumnPair> columns,
                              RowData row) {
        String assignments = columns.stream().filter(c -> !c.key())
                .map(c -> quote(endpoint, c.target().name()) + "=" + literal(row.raw().get(c.source().name().toLowerCase(Locale.ROOT))))
                .collect(java.util.stream.Collectors.joining(", "));
        String where = columns.stream().filter(ColumnPair::key).map(c -> quote(endpoint, c.target().name()) + "=" + literal(row.raw().get(c.source().name().toLowerCase(Locale.ROOT))))
                .collect(java.util.stream.Collectors.joining(" AND "));
        if (!assignments.isBlank()) sql.append("UPDATE ").append(qualified(endpoint, database, table)).append(" SET ")
                .append(assignments).append(" WHERE ").append(where).append(";\n");
    }

    private void showDetails() {
        if (comparison == null || results.getSelectedRow() < 0) return;
        int row = results.convertRowIndexToModel(results.getSelectedRow());
        if (row >= comparison.tables().size()) return;
        TableDifference difference = comparison.tables().get(row);
        JTextArea text = new JTextArea(18, 80);
        text.setEditable(false); text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setText(difference.details().isEmpty() ? difference.detail() : String.join("\n", difference.details()));
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), tr("Diferencias de ") + difference.table(), JOptionPane.INFORMATION_MESSAGE);
    }

    private void generateScript() {
        if (comparison == null || comparison.script().isBlank()) return;
        scriptConsumer.accept(comparisonTarget, comparison.script());
        dispose();
    }

    private void saveScript() {
        if (comparison == null || comparison.script().isBlank()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(tr("Guardar script de sincronización"));
        chooser.setSelectedFile(new java.io.File("sincronizacion-datos.sql"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), comparison.script(), StandardCharsets.UTF_8);
            UiLanguage.text(status, UiLanguage.key("Script guardado: "), chooser.getSelectedFile().getName());
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this, error.getMessage(), tr("No se pudo guardar el script"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void chooseTables() {
        JdbcSession sourceSession = sourceSession();
        JdbcSession targetSession = targetSession();
        String sourceDb = (String) source.getSelectedItem();
        String targetDb = (String) target.getSelectedItem();
        if (sourceSession == null || targetSession == null || sourceDb == null || targetDb == null) return;
        long request = selectionRevision;
        boolean views = includeViews.isSelected();
        choosing = true; updateActions();
        progress.setIndeterminate(true);
        progress.setString(tr("Cargando tablas…"));
        progress.setVisible(true);
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                Set<String> names = new LinkedHashSet<>();
                names.addAll(tableNames(sourceSession, sourceDb, views).values());
                names.addAll(tableNames(targetSession, targetDb, views).values());
                return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }

            @Override protected void done() {
                choosing = false; updateActions();
                progress.setVisible(false);
                progress.setIndeterminate(false);
                if (disposed || request != selectionRevision) return;
                try { showTableChooser(get()); }
                catch (Exception error) {
                    JOptionPane.showMessageDialog(DataCompareDialog.this, rootCause(error).getMessage(),
                            tr("No se pudieron cargar las tablas"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void showTableChooser(List<String> names) {
        if (names.isEmpty()) { UiLanguage.text(status, UiLanguage.key("La base no contiene tablas.")); return; }
        var items = names.stream().map(name -> new ComparisonObjectChooser.Item(name.toLowerCase(Locale.ROOT), name)).toList();
        ComparisonObjectChooser.choose(this, items, selectedTables, tableSelectionConfigured).ifPresent(selected -> {
            selectedTables.clear(); selectedTables.addAll(selected); tableSelectionConfigured = true;
            UiLanguage.text(chooseTables, UiLanguage.key("Tablas seleccionadas: "), selectedTables.size()); selectionChanged();
        });
    }

    private JdbcSession sourceSession() { SessionOption option = (SessionOption) sourceConnection.getSelectedItem(); return option == null ? null : option.session(); }
    private JdbcSession targetSession() { SessionOption option = (SessionOption) targetConnection.getSelectedItem(); return option == null ? null : option.session(); }
    private static void selectSession(JComboBox<SessionOption> combo, JdbcSession session) {
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).session() == session) { combo.setSelectedIndex(i); return; }
    }
    private static void selectOtherSession(JComboBox<SessionOption> combo, JdbcSession active) {
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).session() != active) { combo.setSelectedIndex(i); return; }
        selectSession(combo, active);
    }
    private static boolean usesCatalog(JdbcSession endpoint) {
        return endpoint.profile().type() == DatabaseType.MYSQL || endpoint.profile().type() == DatabaseType.MARIADB;
    }
    private String qualified(JdbcSession endpoint, String database, String table) { return quote(endpoint, database) + "." + quote(endpoint, table); }
    private String quote(JdbcSession endpoint, String identifier) {
        try {
            String q = endpoint.connection().getMetaData().getIdentifierQuoteString(); q = q == null ? "" : q.trim();
            return q.isBlank() ? identifier : q + identifier.replace(q, q + q) + q;
        } catch (SQLException ignored) { return identifier; }
    }
    private static int columnIndex(List<ColumnMeta> columns, String name) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equalsIgnoreCase(name)) return i;
        return -1;
    }
    private static List<String> lower(List<String> values) { return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList(); }
    private static boolean isLargeType(int type) { return type == Types.BLOB || type == Types.CLOB || type == Types.NCLOB || type == Types.LONGVARBINARY || type == Types.LONGVARCHAR || type == Types.LONGNVARCHAR; }
    private static Object safeValue(ResultSet resultSet, int column, int sqlType) throws SQLException {
        return switch (sqlType) {
            case Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE
                    -> resultSet.getString(column);
            default -> resultSet.getObject(column);
        };
    }
    private static Object normalize(Object value, Options options) throws SQLException {
        if (value == null) return options.nullEqualsEmpty() ? "" : NullValue.INSTANCE;
        if (value instanceof String text) {
            if (options.trimText()) text = text.trim();
            if (options.ignoreCase()) text = text.toLowerCase(Locale.ROOT);
            if (options.nullEqualsEmpty() && text.isEmpty()) return "";
            return text;
        }
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof Blob blob) return "BLOB:" + blob.length();
        if (value instanceof Clob clob) return "CLOB:" + clob.length();
        return value;
    }
    private static String jdbcTypeName(int type){try{return JDBCType.valueOf(type).getName();}catch(Exception ignored){return "JDBC "+type;}}
    private static String literal(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean flag) return flag ? "1" : "0";
        if (value instanceof byte[] bytes) return "X'" + java.util.HexFormat.of().formatHex(bytes) + "'";
        String text = value.toString().replace("\\", "\\\\").replace("'", "''");
        return "'" + text + "'";
    }
    private static Throwable rootCause(Throwable error) { Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause(); return cause; }

    private enum NullValue { INSTANCE }
    static record Options(boolean ignoreCase, boolean trimText, boolean nullEqualsEmpty, boolean ignoreLobs,
                           boolean includeViews, int maxRows, String tableFilter, Set<String> selectedTables,
                           boolean tableSelectionConfigured,Map<String,TableMapping> mappings) {}
    static record ColumnMeta(String name, int sqlType,String typeName) {}
    static record ColumnPair(ColumnMeta source,ColumnMeta target,boolean key) {}
    static record ColumnMapping(String source,String target,boolean key) {}
    static record TableMapping(String sourceTable,String targetTable,List<ColumnMapping> columns) {}
    private record TablePair(String key,String source,String target){@Override public String toString(){return source.equals(target)?source:source+" → "+target;}}
    private record MappingDraft(TablePair pair,List<ColumnMeta> sourceColumns,List<ColumnMeta> targetColumns,List<String> sourceKeys,List<String> targetKeys,TableMapping current) {}
    private record RowKey(List<Object> values, String display) {
        @Override public boolean equals(Object other){return other instanceof RowKey key&&values.equals(key.values);}
        @Override public int hashCode(){return values.hashCode();}
    }
    private record RowData(Map<String, Object> raw, List<Object> normalized) {}
    static final class ComparisonControl{
        private final AtomicBoolean cancelled=new AtomicBoolean();private final Set<Statement> statements=java.util.Collections.synchronizedSet(new HashSet<>());
        void register(Statement statement){if(cancelled.get())try{statement.cancel();}catch(SQLException ignored){}else statements.add(statement);}void unregister(Statement statement){statements.remove(statement);}void cancel(){if(!cancelled.compareAndSet(false,true))return;Statement[] active; synchronized(statements){active=statements.toArray(Statement[]::new);}for(Statement statement:active)try{statement.cancel();}catch(SQLException ignored){}}boolean cancelled(){return cancelled.get();}void check(){if(cancelled.get()||Thread.currentThread().isInterrupted())throw new CancellationException(tr("Comparación cancelada"));}
    }
    static record TableDifference(String table, String status, int sourceRows, int targetRows, int missing,
                                   int extra, int changed, String detail, List<String> details) {}
    static record Comparison(List<TableDifference> tables, String script) {}
    private record SessionOption(JdbcSession session) {
        @Override public String toString() {
            String host = session.profile().host();
            return session.profile().name() + (host.isBlank() ? "" : " · " + host + ":" + session.profile().port());
        }
    }

    private static final class TableChoice {
        private final String name;
        private boolean selected;
        TableChoice(String name, boolean selected) { this.name = name; this.selected = selected; }
        String name() { return name; }
        boolean selected() { return selected; }
        void setSelected(boolean selected) { this.selected = selected; }
        void toggle() { selected = !selected; }
        @Override public String toString() { return name; }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                String text = String.valueOf(value);
                label.setForeground(text.equals(tr("Igual")) ? IconFactory.GREEN
                        : text.equals(tr("Diferente")) || text.equals(tr("Parcial")) ? IconFactory.AMBER : IconFactory.RED);
            }
            return label;
        }
    }
}
