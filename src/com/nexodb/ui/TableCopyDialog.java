package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;
import com.nexodb.db.SchemaItem;
import com.nexodb.model.DatabaseType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Copies a table between databases or active connections without exposing credentials. */
public final class TableCopyDialog extends JDialog {
    private final JdbcSession source;
    private final SchemaItem sourceTable;
    private final JComboBox<SessionOption> targetConnection = new JComboBox<>();
    private final JComboBox<String> targetDatabase = new JComboBox<>();
    private final JTextField targetName = new JTextField(28);
    private final JCheckBox copyStructure = UiLanguage.text(new JCheckBox(tr("Copiar estructura"), true), UiLanguage.key("Copiar estructura"));
    private final JCheckBox copyData = UiLanguage.text(new JCheckBox(tr("Copiar datos"), true), UiLanguage.key("Copiar datos"));
    private final JCheckBox replace = UiLanguage.text(new JCheckBox(tr("Reemplazar la tabla si ya existe"), false), UiLanguage.key("Reemplazar la tabla si ya existe"));
    private final JComboBox<DataMode> dataMode = new JComboBox<>(DataMode.values());
    private final JButton copy = UiLanguage.text(new JButton(tr("Copiar tabla")), UiLanguage.key("Copiar tabla"));
    private final JButton verify = UiLanguage.text(new JButton(tr("Verificar copia")), UiLanguage.key("Verificar copia"));
    private final JButton close = UiLanguage.text(new JButton(tr("Cerrar")), UiLanguage.key("Cerrar"));
    private final JProgressBar progress = new JProgressBar();
    private final JLabel state = UiLanguage.text(new JLabel(tr("Selecciona el destino.")), UiLanguage.key("Selecciona el destino."));
    private final Runnable completed;

    public TableCopyDialog(Window owner, JdbcSession source, SchemaItem sourceTable,
                           List<JdbcSession> sessions, Runnable completed) {
        super(owner, tr("Copiar tabla a otra base"), ModalityType.APPLICATION_MODAL);
        UiLanguage.title(this, UiLanguage.key("Copiar tabla a otra base"));
        this.source = source;
        this.sourceTable = sourceTable;
        this.completed = completed == null ? () -> {} : completed;
        for (JdbcSession session : sessions) if (session != null && session.isOpen()) targetConnection.addItem(new SessionOption(session));
        targetName.setText(sourceTable.name());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(build());
        bind();
        setMinimumSize(new Dimension(650, 400));
        pack();
        setLocationRelativeTo(owner);
        selectInitialTarget();
        loadDatabases();
    }

    public void showDialog() { setVisible(true); }

    private JComponent build() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(18, 20, 16, 20));
        JLabel title = new JLabel(tr("<html><b style='font-size:14px'>Copiar ") + html(sourceTable.qualifiedName())
                + tr("</b><br>El origen no se modifica. Selecciona otra base o conexión como destino.</html>"),
                IconFactory.of(IconFactory.Kind.TABLE, 32), JLabel.LEFT);
        title.setIconTextGap(12);
        root.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6); gc.fill = GridBagConstraints.HORIZONTAL; gc.gridy = 0;
        row(form, gc, tr("Conexión de destino"), targetConnection);
        row(form, gc, tr("Base / esquema de destino"), targetDatabase);
        row(form, gc, tr("Nombre de la tabla"), targetName);
        JPanel options = new JPanel(new GridLayout(0, 1, 3, 3));
        options.add(copyStructure); options.add(copyData); options.add(replace);
        row(form, gc, tr("Opciones"), options);
        row(form, gc, tr("Modo de datos"), dataMode);
        root.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 8));
        progress.setIndeterminate(true); progress.setVisible(false);
        JPanel activity = new JPanel(new BorderLayout(8, 0)); activity.add(state, BorderLayout.CENTER); activity.add(progress, BorderLayout.EAST);
        footer.add(activity, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        copy.setIcon(IconFactory.of(IconFactory.Kind.TABLE, 16));
        verify.setIcon(IconFactory.of(IconFactory.Kind.TEST, 16));
        close.setIcon(IconFactory.of(IconFactory.Kind.CANCEL, 16));
        copy.setBackground(IconFactory.BLUE); copy.setForeground(Color.WHITE);
        actions.add(verify); actions.add(copy); actions.add(close); footer.add(actions, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private static void row(JPanel panel, GridBagConstraints gc, String label, Component field) {
        gc.gridx = 0; gc.weightx = 0; panel.add(UiLanguage.text(new JLabel(), UiLanguage.caption(label), ":"), gc);
        gc.gridx = 1; gc.weightx = 1; panel.add(field, gc); gc.gridy++;
    }

    private void bind() {
        targetConnection.addActionListener(e -> loadDatabases());
        copyStructure.addActionListener(e -> replace.setEnabled(copyStructure.isSelected()));
        copy.addActionListener(e -> startCopy());
        verify.addActionListener(e -> verifyCopy());
        close.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(copy);
    }

    private void selectInitialTarget() {
        for (int i = 0; i < targetConnection.getItemCount(); i++) {
            if (targetConnection.getItemAt(i).session() != source) { targetConnection.setSelectedIndex(i); return; }
        }
    }

    private JdbcSession target() {
        SessionOption option = (SessionOption) targetConnection.getSelectedItem();
        return option == null ? null : option.session();
    }

    private void loadDatabases() {
        JdbcSession target = target();
        targetDatabase.removeAllItems();
        copy.setEnabled(false);
        if (target == null) return;
        UiLanguage.text(state, UiLanguage.key("Cargando bases de "), target.profile().name(), "…");
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                List<String> names = new ArrayList<>();
                DatabaseMetaData metadata = target.connection().getMetaData();
                try (ResultSet rs = metadata.getCatalogs()) {
                    while (rs.next()) { String name = rs.getString(1); if (name != null && !name.isBlank()) names.add(name); }
                }
                if (names.isEmpty()) try (ResultSet rs = metadata.getSchemas()) {
                    while (rs.next()) { String name = rs.getString(1); if (name != null && !name.isBlank()) names.add(name); }
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                return names.stream().distinct().toList();
            }
            @Override protected void done() {
                try {
                    for (String name : get()) targetDatabase.addItem(name);
                    String sourceDb = namespace(sourceTable);
                    for (int i = 0; i < targetDatabase.getItemCount(); i++) {
                        if (sourceDb.equalsIgnoreCase(targetDatabase.getItemAt(i))) { targetDatabase.setSelectedIndex(i); break; }
                    }
                    state.setText(targetDatabase.getItemCount() == 0 ? tr("No se encontraron bases en el destino.") : tr("Destino listo."));
                    copy.setEnabled(targetDatabase.getItemCount() > 0 && !target.profile().readOnly());
                    if (target.profile().readOnly()) UiLanguage.text(state, UiLanguage.key("La conexión de destino está configurada como solo lectura."));
                } catch (Exception error) {
                    UiLanguage.text(state, UiLanguage.key("No se pudieron cargar las bases del destino."));
                }
            }
        }.execute();
    }

    private void startCopy() {
        JdbcSession target = target();
        String database = (String) targetDatabase.getSelectedItem();
        String name = targetName.getText().trim();
        if (target == null || database == null || database.isBlank()) return;
        if (name.isBlank()) { warn(tr("Escribe el nombre de la tabla de destino.")); return; }
        if (!copyStructure.isSelected() && !copyData.isSelected()) { warn(tr("Selecciona estructura, datos o ambos.")); return; }
        if (target.profile().readOnly()) { warn(tr("La conexión de destino es de solo lectura.")); return; }
        if (source == target && namespace(sourceTable).equalsIgnoreCase(database) && sourceTable.name().equalsIgnoreCase(name)) {
            warn(tr("El origen y el destino son la misma tabla.")); return;
        }
        setRunning(true, tr("Copiando tabla…"));
        new SwingWorker<CopyReport, Integer>() {
            @Override protected CopyReport doInBackground() throws Exception {
                return copyTable(source, sourceTable, target, database, name, copyStructure.isSelected(), copyData.isSelected(),
                        replace.isSelected(), dataMode.getSelectedItem() == DataMode.MISSING_ONLY);
            }
            @Override protected void done() {
                setRunning(false, "");
                try {
                    CopyReport report = get();
                    UiLanguage.text(state, UiLanguage.key("Copia completada: "), report.inserted(), UiLanguage.key(" nuevas; "), report.skipped(), UiLanguage.key(" ya existían."));
                    completed.run();
                    JOptionPane.showMessageDialog(TableCopyDialog.this,
                            tr("La copia terminó correctamente.\n\nDestino: ") + report.destination()
                                    + tr("\nFilas insertadas: ") + report.inserted() + tr("\nFilas que ya existían: ") + report.skipped(),
                            tr("Copia completada"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    UiLanguage.text(state, UiLanguage.key("No se pudo copiar la tabla."));
                    JOptionPane.showMessageDialog(TableCopyDialog.this, cause.getMessage(), tr("Error al copiar"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setRunning(boolean running, String text) {
        progress.setVisible(running); copy.setEnabled(!running); verify.setEnabled(!running); close.setEnabled(!running);
        targetConnection.setEnabled(!running); targetDatabase.setEnabled(!running); targetName.setEnabled(!running);
        copyStructure.setEnabled(!running); copyData.setEnabled(!running); dataMode.setEnabled(!running);
        replace.setEnabled(!running && copyStructure.isSelected());
        if (!text.isBlank()) state.setText(text);
    }

    private void verifyCopy() {
        JdbcSession target = target();
        String database = (String) targetDatabase.getSelectedItem();
        String name = targetName.getText().trim();
        if (target == null || database == null || name.isBlank()) return;
        setRunning(true, tr("Verificando filas por clave primaria…"));
        new SwingWorker<Verification, Void>() {
            @Override protected Verification doInBackground() throws Exception {
                if (!tableExists(target, database, name)) throw new SQLException(tr("La tabla de destino todavía no existe."));
                return verifyTable(source, sourceTable, target, database, name);
            }
            @Override protected void done() {
                setRunning(false, "");
                try {
                    Verification result = get();
                    String detail = result.hasKeys()
                            ? tr("Filas en origen: ") + result.sourceRows() + tr("\nFilas en destino: ") + result.targetRows()
                                + tr("\nFaltan en destino: ") + result.missing() + tr("\nAdicionales en destino: ") + result.extra()
                            : tr("Filas en origen: ") + result.sourceRows() + tr("\nFilas en destino: ") + result.targetRows()
                                + tr("\n\nLa tabla no tiene clave primaria; solamente se pudieron comparar los conteos.");
                    UiLanguage.text(state, (result.missing() == 0 ? tr("Verificación completa: no faltan filas.") : tr("Verificación: faltan ") + result.missing()), UiLanguage.key(" filas."));
                    JOptionPane.showMessageDialog(TableCopyDialog.this, detail, tr("Resultado de la verificación"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    UiLanguage.text(state, UiLanguage.key("No se pudo verificar la copia."));
                    JOptionPane.showMessageDialog(TableCopyDialog.this, cause.getMessage(), tr("Error al verificar"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    static CopyReport copyTable(JdbcSession source, SchemaItem table, JdbcSession target, String targetDatabase,
                                String targetName, boolean structure, boolean data, boolean replace, boolean missingOnly) throws Exception {
        List<ColumnDef> columns = columns(source, table);
        if (columns.isEmpty()) throw new SQLException(tr("La tabla de origen no tiene columnas visibles."));
        String destination = qualified(target, targetDatabase, targetName);
        boolean exists = tableExists(target, targetDatabase, targetName);
        if (structure) {
            if (exists && !replace && !missingOnly) throw new SQLException(tr("La tabla de destino ya existe. Activa “Reemplazar” o usa “Solo filas faltantes”."));
            if (!exists || replace) try (Statement statement = target.connection().createStatement()) {
                if (exists) statement.executeUpdate("DROP TABLE " + destination);
                statement.executeUpdate(createSql(source, table, target, targetDatabase, targetName, columns));
            }
        } else if (!exists) {
            throw new SQLException(tr("La tabla de destino no existe. Activa “Copiar estructura”."));
        }
        CopyData result = data ? copyRows(source, table, target, destination, columns, missingOnly) : new CopyData(0, 0);
        return new CopyReport(destination, result.inserted(), result.skipped());
    }

    private static CopyData copyRows(JdbcSession source, SchemaItem table, JdbcSession target, String destination,
                                     List<ColumnDef> columns, boolean missingOnly) throws SQLException {
        StringBuilder insert = new StringBuilder("INSERT INTO ").append(destination).append(" (");
        String quote = identifierQuote(target);
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) insert.append(", "); insert.append(quote(columns.get(i).name(), quote));
        }
        insert.append(") VALUES (").append("?,".repeat(columns.size()));
        insert.setLength(insert.length() - 1); insert.append(')');
        List<String> primaryKeys = primaryKeys(source, table);
        if (missingOnly && primaryKeys.isEmpty()) throw new SQLException(
                tr("La tabla no tiene clave primaria. No es posible identificar de forma segura cuáles filas faltan; usa “Insertar todas” o crea una clave primaria."));
        Set<RowKey> existing = missingOnly ? readKeys(target, destination, primaryKeys) : Set.of();
        boolean originalAutoCommit = target.connection().getAutoCommit();
        Savepoint savepoint = null;
        int inserted = 0, skipped = 0, pending = 0;
        try {
            if (originalAutoCommit) target.connection().setAutoCommit(false);
            else savepoint = target.connection().setSavepoint("nexodb_copy_table");
            try (Statement select = source.connection().createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                 ResultSet rows = select.executeQuery("SELECT * FROM " + qualified(source, namespace(table), table.name()));
                 PreparedStatement write = target.connection().prepareStatement(insert.toString())) {
                while (rows.next()) {
                    if (missingOnly && existing.contains(sourceKey(rows, columns, primaryKeys))) { skipped++; continue; }
                    for (int col = 1; col <= columns.size(); col++) {
                        Object value = transferValue(rows, col, columns.get(col - 1).jdbcType());
                        if (value == null) write.setNull(col, columns.get(col - 1).jdbcType()); else write.setObject(col, value);
                    }
                    write.addBatch(); inserted++; pending++;
                    if (pending == 250) { write.executeBatch(); pending = 0; }
                }
                if (pending > 0) write.executeBatch();
            }
            if (originalAutoCommit) target.connection().commit();
            return new CopyData(inserted, skipped);
        } catch (SQLException error) {
            if (originalAutoCommit) target.connection().rollback();
            else if (savepoint != null) target.connection().rollback(savepoint);
            throw error;
        } finally {
            if (originalAutoCommit) target.connection().setAutoCommit(true);
            else if (savepoint != null) try { target.connection().releaseSavepoint(savepoint); } catch (SQLException ignored) {}
        }
    }

    private static String createSql(JdbcSession source, SchemaItem table, JdbcSession target, String database,
                                    String name, List<ColumnDef> columns) throws SQLException {
        String quote = identifierQuote(target);
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(qualified(target, database, name)).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            ColumnDef col = columns.get(i);
            if (i > 0) sql.append(",\n");
            sql.append("  ").append(quote(col.name(), quote)).append(' ').append(typeFor(col, target.profile().type()));
            if (!col.nullable()) sql.append(" NOT NULL");
            if (col.defaultValue() != null && !col.defaultValue().isBlank() && !col.autoIncrement()) sql.append(" DEFAULT ").append(col.defaultValue());
            if (col.autoIncrement()) {
                if (target.profile().type() == DatabaseType.MYSQL || target.profile().type() == DatabaseType.MARIADB) sql.append(" AUTO_INCREMENT");
                else if (target.profile().type() == DatabaseType.SQLSERVER) sql.append(" IDENTITY(1,1)");
                else sql.append(" GENERATED BY DEFAULT AS IDENTITY");
            }
        }
        List<String> primary = primaryKeys(source, table);
        if (!primary.isEmpty()) {
            sql.append(",\n  PRIMARY KEY (");
            for (int i = 0; i < primary.size(); i++) { if (i > 0) sql.append(", "); sql.append(quote(primary.get(i), quote)); }
            sql.append(')');
        }
        return sql.append("\n)").toString();
    }

    private static Verification verifyTable(JdbcSession source, SchemaItem table, JdbcSession target,
                                            String targetDatabase, String targetName) throws SQLException {
        String sourceQualified = qualified(source, namespace(table), table.name());
        String targetQualified = qualified(target, targetDatabase, targetName);
        long sourceRows = countRows(source, sourceQualified);
        long targetRows = countRows(target, targetQualified);
        List<String> keys = primaryKeys(source, table);
        if (keys.isEmpty()) return new Verification(sourceRows, targetRows, Math.max(0, sourceRows - targetRows),
                Math.max(0, targetRows - sourceRows), false);
        Set<RowKey> sourceKeys = readKeys(source, sourceQualified, keys);
        Set<RowKey> targetKeys = readKeys(target, targetQualified, keys);
        long missing = sourceKeys.stream().filter(key -> !targetKeys.contains(key)).count();
        long extra = targetKeys.stream().filter(key -> !sourceKeys.contains(key)).count();
        return new Verification(sourceRows, targetRows, missing, extra, true);
    }

    private static long countRows(JdbcSession session, String table) throws SQLException {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static Set<RowKey> readKeys(JdbcSession session, String table, List<String> keys) throws SQLException {
        String quote = identifierQuote(session);
        String selected = keys.stream().map(key -> quote(key, quote)).collect(java.util.stream.Collectors.joining(", "));
        Set<RowKey> values = new HashSet<>();
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery("SELECT " + selected + " FROM " + table)) {
            ResultSetMetaData metadata = rs.getMetaData();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= keys.size(); i++) row.add(keyPart(transferValue(rs, i, metadata.getColumnType(i))));
                values.add(new RowKey(List.copyOf(row)));
            }
        }
        return values;
    }

    private static RowKey sourceKey(ResultSet row, List<ColumnDef> columns, List<String> keys) throws SQLException {
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            int index = -1;
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equalsIgnoreCase(key)) { index = i + 1; break; }
            if (index < 0) throw new SQLException(tr("No se encontró la clave primaria ") + key + tr(" entre las columnas de origen."));
            values.add(keyPart(transferValue(row, index, columns.get(index - 1).jdbcType())));
        }
        return new RowKey(List.copyOf(values));
    }

    private static Object transferValue(ResultSet rows, int column, int type) throws SQLException {
        return switch (type) {
            case Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> rows.getString(column);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> rows.getBytes(column);
            default -> rows.getObject(column);
        };
    }

    private static String keyPart(Object value) {
        if (value == null) return "<NULL>";
        if (value instanceof byte[] bytes) return java.util.Base64.getEncoder().encodeToString(bytes);
        if (value instanceof java.math.BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        return value.toString();
    }

    private static String typeFor(ColumnDef column, DatabaseType target) {
        int type = column.jdbcType();
        if (target == DatabaseType.POSTGRESQL && type == Types.TINYINT) return "SMALLINT";
        if (target == DatabaseType.SQLSERVER && type == Types.BOOLEAN) return "BIT";
        String name = column.typeName();
        boolean sized = switch (type) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.BINARY, Types.VARBINARY -> true;
            case Types.DECIMAL, Types.NUMERIC -> true;
            default -> false;
        };
        if (!sized || column.size() <= 0 || name.contains("(")) return name;
        if ((type == Types.DECIMAL || type == Types.NUMERIC) && column.scale() >= 0) return name + "(" + column.size() + "," + column.scale() + ")";
        return name + "(" + column.size() + ")";
    }

    private static List<ColumnDef> columns(JdbcSession session, SchemaItem table) throws SQLException {
        List<ColumnDef> values = new ArrayList<>();
        try (ResultSet rs = session.connection().getMetaData().getColumns(table.catalog(), table.schema(), table.name(), "%")) {
            while (rs.next()) values.add(new ColumnDef(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME"),
                    rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"), rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                    rs.getString("COLUMN_DEF"), "YES".equalsIgnoreCase(safe(rs, "IS_AUTOINCREMENT"))));
        }
        return values;
    }

    private static List<String> primaryKeys(JdbcSession session, SchemaItem table) throws SQLException {
        record Key(int order, String name) {}
        List<Key> keys = new ArrayList<>();
        try (ResultSet rs = session.connection().getMetaData().getPrimaryKeys(table.catalog(), table.schema(), table.name())) {
            while (rs.next()) keys.add(new Key(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME")));
        }
        keys.sort(Comparator.comparingInt(Key::order));
        return keys.stream().map(Key::name).toList();
    }

    private static boolean tableExists(JdbcSession target, String database, String name) throws SQLException {
        String catalog = catalogNamespace(target.profile().type()) ? database : null;
        String schema = catalogNamespace(target.profile().type()) ? null : database;
        try (ResultSet rs = target.connection().getMetaData().getTables(catalog, schema, name, new String[]{"TABLE"})) { return rs.next(); }
    }

    private static String qualified(JdbcSession session, String database, String name) throws SQLException {
        String quote = identifierQuote(session);
        return (database == null || database.isBlank() ? "" : quote(database, quote) + ".") + quote(name, quote);
    }
    private static String identifierQuote(JdbcSession session) throws SQLException {
        String quote = session.connection().getMetaData().getIdentifierQuoteString();
        return quote == null ? "" : quote.trim();
    }
    private static String quote(String value, String quote) { return quote.isBlank() ? value : quote + value.replace(quote, quote + quote) + quote; }
    private static String namespace(SchemaItem item) { return item.catalog() != null && !item.catalog().isBlank() ? item.catalog() : item.schema() == null ? "" : item.schema(); }
    private static boolean catalogNamespace(DatabaseType type) { return type == DatabaseType.MYSQL || type == DatabaseType.MARIADB || type == DatabaseType.SQLSERVER; }
    private static String safe(ResultSet rs, String column) { try { return rs.getString(column); } catch (SQLException ignored) { return ""; } }
    private void warn(String message) { JOptionPane.showMessageDialog(this, message, tr("Revisa la copia"), JOptionPane.WARNING_MESSAGE); }
    private static String html(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private record SessionOption(JdbcSession session) {
        @Override public String toString() { return session.profile().name() + " · " + session.profile().host() + ":" + session.profile().port(); }
    }
    private record ColumnDef(String name, int jdbcType, String typeName, int size, int scale, boolean nullable,
                             String defaultValue, boolean autoIncrement) {}
    private record RowKey(List<String> values) {}
    private record CopyData(int inserted, int skipped) {}
    private record Verification(long sourceRows, long targetRows, long missing, long extra, boolean hasKeys) {}
    public record CopyReport(String destination, int inserted, int skipped) {}

    private enum DataMode {
        MISSING_ONLY("Solo filas faltantes / reanudar (recomendado)"),
        ALL("Insertar todas las filas");
        private final String label;
        DataMode(String label) { this.label = label; }
        @Override public String toString() { return tr(label); }
    }
}
