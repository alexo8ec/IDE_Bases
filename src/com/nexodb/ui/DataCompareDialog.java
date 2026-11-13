package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;
import com.nexodb.model.DatabaseType;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final Set<String> selectedTables = new LinkedHashSet<>();
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
    private final JButton generate = UiLanguage.text(new JButton(tr("Abrir script en editor"), IconFactory.of(IconFactory.Kind.SQL, 16)), UiLanguage.key("Abrir script en editor"));
    private final JButton saveScript = UiLanguage.text(new JButton(tr("Guardar .sql"), IconFactory.of(IconFactory.Kind.SAVE, 16)), UiLanguage.key("Guardar .sql"));
    private Comparison comparison;

    public DataCompareDialog(Window owner, JdbcSession activeSession, List<JdbcSession> sessions, BiConsumer<JdbcSession, String> scriptConsumer) {
        super(owner, tr("Comparar datos"), ModalityType.APPLICATION_MODAL);
        UiLanguage.title(this, UiLanguage.key("Comparar datos"));
        this.scriptConsumer = scriptConsumer;
        for (JdbcSession session : sessions) if (session != null && session.isOpen()) {
            SessionOption option = new SessionOption(session);
            sourceConnection.addItem(option); targetConnection.addItem(option);
        }
        selectSession(sourceConnection, activeSession);
        selectOtherSession(targetConnection, activeSession);
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
        generate.addActionListener(e -> generateScript());
        saveScript.addActionListener(e -> saveScript());
        close.addActionListener(e -> dispose());
        actions.add(progress); actions.add(compare); actions.add(generate); actions.add(saveScript); actions.add(close);
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
            SwingUtilities.invokeLater(() -> { source.setSelectedItem(rightDatabase); target.setSelectedItem(leftDatabase); });
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
        endpoints.add(chooseTables, c);
        source.addActionListener(e -> clearTableSelection());
        target.addActionListener(e -> clearTableSelection());
        sourceConnection.addActionListener(e -> { clearTableSelection(); loadDatabases(sourceSession(), source, true); });
        targetConnection.addActionListener(e -> { clearTableSelection(); loadDatabases(targetSession(), target, false); });
        includeViews.addActionListener(e -> clearTableSelection());
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

    private void loadDatabases(JdbcSession endpoint, JComboBox<String> combo, boolean sourceSide) {
        combo.removeAllItems(); combo.setEnabled(false);
        if (endpoint == null) return;
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception { return databaseNames(endpoint); }
            @Override protected void done() {
                try {
                    for (String name : get()) combo.addItem(name);
                    String requested = endpoint.profile().database();
                    if (requested != null && !requested.isBlank()) combo.setSelectedItem(requested);
                    if (!sourceSide && combo.getSelectedIndex() < 0 && combo.getItemCount() > 0) combo.setSelectedIndex(0);
                    combo.setEnabled(combo.getItemCount() > 0);
                    compare.setEnabled(source.getItemCount() > 0 && target.getItemCount() > 0);
                    UiLanguage.text(status, UiLanguage.key("Selecciona las instancias, las bases y las tablas que deseas comparar."));
                } catch (Exception error) {
                    UiLanguage.text(status, UiLanguage.key("No se pudieron cargar las bases: "), rootCause(error).getMessage());
                }
            }
        }.execute();
    }

    private List<String> databaseNames(JdbcSession endpoint) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = endpoint.connection().getMetaData().getCatalogs()) {
            while (rs.next()) { String value = rs.getString("TABLE_CAT"); if (value != null && !value.isBlank()) names.add(value); }
        }
        if (names.isEmpty()) {
            try (ResultSet rs = endpoint.connection().getMetaData().getSchemas()) {
                while (rs.next()) { String value = rs.getString("TABLE_SCHEM"); if (value != null && !value.isBlank()) names.add(value); }
            }
        }
        return names.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
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
                Set.copyOf(selectedTables), tableSelectionConfigured);
        compare.setEnabled(false); generate.setEnabled(false); model.setRowCount(0);
        saveScript.setEnabled(false); scriptPreview.setText(""); outputTabs.setSelectedIndex(0);
        progress.setValue(0); progress.setString(tr("Preparando…")); progress.setVisible(true);
        UiLanguage.text(status, UiLanguage.key("Comparando "), sourceSession.profile().name(), "/", sourceDb, UiLanguage.key(" con "), targetSession.profile().name(), "/", targetDb, "…");
        SwingWorker<Comparison, Void> worker = new SwingWorker<>() {
            @Override protected Comparison doInBackground() throws Exception {
                return buildComparison(sourceSession, sourceDb, targetSession, targetDb, options, this::setProgress);
            }
            @Override protected void done() {
                compare.setEnabled(true); progress.setVisible(false);
                try {
                    comparison = get();
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
                } catch (Exception error) {
                    UiLanguage.text(status, UiLanguage.key("Error al comparar datos"));
                    JOptionPane.showMessageDialog(DataCompareDialog.this, rootCause(error).getMessage(),
                            tr("No se pudieron comparar los datos"), JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) {
                int value = (Integer) event.getNewValue(); progress.setValue(value); progress.setString(value + "%");
            }
        });
        worker.execute();
    }

    private Comparison buildComparison(JdbcSession sourceSession, String sourceDb, JdbcSession targetSession, String targetDb, Options options,
                                       java.util.function.IntConsumer progressConsumer) throws SQLException {
        Map<String, String> sourceTables = tableNames(sourceSession, sourceDb, options.includeViews());
        Map<String, String> targetTables = tableNames(targetSession, targetDb, options.includeViews());
        Set<String> keys = new LinkedHashSet<>(); keys.addAll(sourceTables.keySet()); keys.addAll(targetTables.keySet());
        List<String> filtered = keys.stream()
                .filter(key -> !options.tableSelectionConfigured() || options.selectedTables().contains(key))
                .filter(key -> options.tableFilter().isBlank()
                        || key.contains(options.tableFilter().toLowerCase(Locale.ROOT))).toList();
        List<TableDifference> differences = new ArrayList<>();
        StringBuilder script = new StringBuilder();
        int current = 0;
        for (String key : filtered) {
            String sourceTable = sourceTables.get(key), targetTable = targetTables.get(key);
            if (sourceTable == null) {
                differences.add(new TableDifference(targetTable, tr("Solo en destino"), 0, -1, 0, 0, 0, tr("No existe en origen"), List.of()));
            } else if (targetTable == null) {
                differences.add(new TableDifference(sourceTable, tr("Solo en origen"), -1, 0, 0, 0, 0, tr("No existe en destino"), List.of()));
            } else {
                TableDifference difference = compareTable(sourceSession, sourceDb, targetSession, targetDb, sourceTable, targetTable, options, script);
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
                                         Options options, StringBuilder script) throws SQLException {
        List<ColumnMeta> sourceColumns = columns(sourceSession, sourceDb, sourceTable, options.ignoreLobs());
        List<ColumnMeta> targetColumns = columns(targetSession, targetDb, targetTable, options.ignoreLobs());
        Map<String, ColumnMeta> targetByName = new HashMap<>();
        for (ColumnMeta column : targetColumns) targetByName.put(column.name().toLowerCase(Locale.ROOT), column);
        List<ColumnMeta> common = sourceColumns.stream()
                .filter(column -> targetByName.containsKey(column.name().toLowerCase(Locale.ROOT))).toList();
        List<String> sourcePk = primaryKeys(sourceSession, sourceDb, sourceTable);
        List<String> targetPk = primaryKeys(targetSession, targetDb, targetTable);
        Set<String> commonNames = new LinkedHashSet<>();
        for (ColumnMeta column : common) commonNames.add(column.name().toLowerCase(Locale.ROOT));
        if (sourcePk.isEmpty() || !lower(sourcePk).equals(lower(targetPk)) || !commonNames.containsAll(lower(sourcePk))) {
            return new TableDifference(sourceTable, tr("Sin clave compatible"), -1, -1, 0, 0, 0,
                    tr("Se necesita la misma clave primaria en origen y destino"), List.of());
        }
        if (common.isEmpty()) return new TableDifference(sourceTable, tr("Sin columnas comunes"), -1, -1, 0, 0, 0, tr("No se puede comparar"), List.of());

        TableData left = readRows(sourceSession, sourceDb, sourceTable, common, sourcePk, options);
        TableData right = readRows(targetSession, targetDb, targetTable, common, sourcePk, options);
        int missing = 0, extra = 0, changed = 0;
        List<String> details = new ArrayList<>();
        for (Map.Entry<RowKey, RowData> entry : left.rows().entrySet()) {
            RowData targetRow = right.rows().get(entry.getKey());
            if (targetRow == null) {
                missing++; if (details.size() < 100) details.add(tr("Falta en destino: ") + entry.getKey().display());
                appendInsert(script, targetSession, targetDb, targetTable, common, entry.getValue());
            } else if (!entry.getValue().normalized().equals(targetRow.normalized())) {
                changed++; if (details.size() < 100) details.add(tr("Modificada: ") + entry.getKey().display());
                appendUpdate(script, targetSession, targetDb, targetTable, common, sourcePk, entry.getValue());
            }
        }
        for (RowKey key : right.rows().keySet()) {
            if (!left.rows().containsKey(key)) {
                extra++; if (details.size() < 100) details.add(tr("Adicional en destino: ") + key.display());
            }
        }
        String state = missing == 0 && extra == 0 && changed == 0
                ? (left.truncated() || right.truncated() ? tr("Parcial") : tr("Igual")) : tr("Diferente");
        String note = (left.truncated() || right.truncated()) ? tr("Límite alcanzado; comparación parcial") : common.size() + tr(" columnas comparadas");
        return new TableDifference(sourceTable, state, left.rows().size(), right.rows().size(), missing, extra, changed, note, details);
    }

    private List<ColumnMeta> columns(JdbcSession endpoint, String database, String table, boolean ignoreLarge) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>();
        boolean catalog = usesCatalog(endpoint);
        try (ResultSet rs = endpoint.connection().getMetaData().getColumns(catalog ? database : null,
                catalog ? null : database, table, "%")) {
            while (rs.next()) {
                int type = rs.getInt("DATA_TYPE");
                if (ignoreLarge && isLargeType(type)) continue;
                columns.add(new ColumnMeta(rs.getString("COLUMN_NAME"), type));
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

    private TableData readRows(JdbcSession endpoint, String database, String table, List<ColumnMeta> columns, List<String> primaryKeys,
                               Options options) throws SQLException {
        String selectedColumns = columns.stream().map(c -> quote(endpoint, c.name())).collect(java.util.stream.Collectors.joining(", "));
        String order = primaryKeys.stream().map(key -> quote(endpoint, key)).collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT " + selectedColumns + " FROM " + qualified(endpoint, database, table) + " ORDER BY " + order;
        Map<RowKey, RowData> rows = new LinkedHashMap<>();
        boolean truncated = false;
        try (Statement statement = endpoint.connection().createStatement()) {
            statement.setFetchSize(Math.min(500, options.maxRows()));
            statement.setMaxRows(options.maxRows() + 1);
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    if (rows.size() >= options.maxRows()) { truncated = true; break; }
                    Map<String, Object> raw = new LinkedHashMap<>();
                    List<Object> normalized = new ArrayList<>();
                    for (int i = 0; i < columns.size(); i++) {
                        Object value = safeValue(rs, i + 1, columns.get(i).sqlType());
                        raw.put(columns.get(i).name().toLowerCase(Locale.ROOT), value);
                        normalized.add(normalize(value, options));
                    }
                    List<Object> keyValues = new ArrayList<>();
                    List<String> display = new ArrayList<>();
                    for (String key : primaryKeys) {
                        int index = columnIndex(columns, key);
                        Object value = index < 0 ? null : raw.get(columns.get(index).name().toLowerCase(Locale.ROOT));
                        keyValues.add(normalize(value, options)); display.add(key + "=" + String.valueOf(value));
                    }
                    rows.put(new RowKey(List.copyOf(keyValues), String.join(", ", display)), new RowData(raw, List.copyOf(normalized)));
                }
            }
        }
        return new TableData(rows, truncated);
    }

    private void appendInsert(StringBuilder sql, JdbcSession endpoint, String database, String table, List<ColumnMeta> columns, RowData row) {
        sql.append("INSERT INTO ").append(qualified(endpoint, database, table)).append(" (")
                .append(columns.stream().map(c -> quote(endpoint, c.name())).collect(java.util.stream.Collectors.joining(", ")))
                .append(") VALUES (")
                .append(columns.stream().map(c -> literal(row.raw().get(c.name().toLowerCase(Locale.ROOT))))
                        .collect(java.util.stream.Collectors.joining(", "))).append(");\n");
    }

    private void appendUpdate(StringBuilder sql, JdbcSession endpoint, String database, String table, List<ColumnMeta> columns,
                              List<String> primaryKeys, RowData row) {
        Set<String> keyNames = new LinkedHashSet<>(lower(primaryKeys));
        String assignments = columns.stream().filter(c -> !keyNames.contains(c.name().toLowerCase(Locale.ROOT)))
                .map(c -> quote(endpoint, c.name()) + "=" + literal(row.raw().get(c.name().toLowerCase(Locale.ROOT))))
                .collect(java.util.stream.Collectors.joining(", "));
        String where = primaryKeys.stream().map(key -> quote(endpoint, key) + "=" + literal(row.raw().get(key.toLowerCase(Locale.ROOT))))
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
        scriptConsumer.accept(targetSession(), comparison.script());
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

    private void clearTableSelection() {
        selectedTables.clear();
        tableSelectionConfigured = false;
        UiLanguage.text(chooseTables, UiLanguage.key("Seleccionar tablas…"));
    }

    private void chooseTables() {
        JdbcSession sourceSession = sourceSession();
        JdbcSession targetSession = targetSession();
        String sourceDb = (String) source.getSelectedItem();
        String targetDb = (String) target.getSelectedItem();
        if (sourceSession == null || targetSession == null || sourceDb == null || targetDb == null) return;
        chooseTables.setEnabled(false);
        progress.setIndeterminate(true);
        progress.setString(tr("Cargando tablas…"));
        progress.setVisible(true);
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                Set<String> names = new LinkedHashSet<>();
                names.addAll(tableNames(sourceSession, sourceDb, includeViews.isSelected()).values());
                names.addAll(tableNames(targetSession, targetDb, includeViews.isSelected()).values());
                return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }

            @Override protected void done() {
                chooseTables.setEnabled(true);
                progress.setVisible(false);
                progress.setIndeterminate(false);
                try { showTableChooser(get()); }
                catch (Exception error) {
                    JOptionPane.showMessageDialog(DataCompareDialog.this, rootCause(error).getMessage(),
                            tr("No se pudieron cargar las tablas"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void showTableChooser(List<String> names) {
        DefaultListModel<TableChoice> choices = new DefaultListModel<>();
        for (String name : names) choices.addElement(new TableChoice(name,
                !tableSelectionConfigured || selectedTables.contains(name.toLowerCase(Locale.ROOT))));
        JList<TableChoice> list = new JList<>(choices);
        list.setVisibleRowCount(18);
        list.setFixedCellHeight(25);
        list.setCellRenderer((component, value, index, selected, focus) -> {
            JCheckBox check = new JCheckBox(value.name(), value.selected());
            check.setOpaque(true);
            check.setBackground(selected ? component.getSelectionBackground() : component.getBackground());
            check.setForeground(selected ? component.getSelectionForeground() : component.getForeground());
            check.setBorder(new EmptyBorder(2, 6, 2, 6));
            return check;
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0) {
                    choices.get(index).toggle();
                    list.repaint(list.getCellBounds(index, index));
                }
            }
        });
        JButton all = UiLanguage.text(new JButton(tr("Todas")), UiLanguage.key("Todas"));
        JButton none = UiLanguage.text(new JButton(tr("Ninguna")), UiLanguage.key("Ninguna"));
        all.addActionListener(e -> { for (int i = 0; i < choices.size(); i++) choices.get(i).setSelected(true); list.repaint(); });
        none.addActionListener(e -> { for (int i = 0; i < choices.size(); i++) choices.get(i).setSelected(false); list.repaint(); });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(all); buttons.add(none);
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(UiLanguage.text(new JLabel(tr("Marca las tablas o vistas que deseas comparar:")), UiLanguage.key("Marca las tablas o vistas que deseas comparar:")), BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(480, 500));
        if (JOptionPane.showConfirmDialog(this, panel, tr("Seleccionar tablas"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        selectedTables.clear();
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).selected()) selectedTables.add(choices.get(i).name().toLowerCase(Locale.ROOT));
        }
        if (selectedTables.isEmpty()) {
            JOptionPane.showMessageDialog(this, tr("Selecciona al menos una tabla."), tr("Comparar datos"), JOptionPane.INFORMATION_MESSAGE);
            clearTableSelection();
            return;
        }
        tableSelectionConfigured = true;
        UiLanguage.text(chooseTables, UiLanguage.key("Tablas seleccionadas: "), selectedTables.size());
        UiLanguage.text(status, (selectedTables.size()), UiLanguage.key(" tablas seleccionadas para comparar"));
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
    private record Options(boolean ignoreCase, boolean trimText, boolean nullEqualsEmpty, boolean ignoreLobs,
                           boolean includeViews, int maxRows, String tableFilter, Set<String> selectedTables,
                           boolean tableSelectionConfigured) {}
    private record ColumnMeta(String name, int sqlType) {}
    private record RowKey(List<Object> values, String display) {}
    private record RowData(Map<String, Object> raw, List<Object> normalized) {}
    private record TableData(Map<RowKey, RowData> rows, boolean truncated) {}
    private record TableDifference(String table, String status, int sourceRows, int targetRows, int missing,
                                   int extra, int changed, String detail, List<String> details) {}
    private record Comparison(List<TableDifference> tables, String script) {}
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
