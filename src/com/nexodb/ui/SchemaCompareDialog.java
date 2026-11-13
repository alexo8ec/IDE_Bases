package com.nexodb.ui;

import com.nexodb.db.JdbcSession;
import com.nexodb.model.DatabaseType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Compares catalogs/schemas from one or two active connections. */
public final class SchemaCompareDialog extends JDialog {
    private final JComboBox<SessionOption> sourceConnection = new JComboBox<>();
    private final JComboBox<SessionOption> targetConnection = new JComboBox<>();
    private final BiConsumer<JdbcSession, String> scriptConsumer;
    private final JComboBox<String> source = new JComboBox<>();
    private final JComboBox<String> target = new JComboBox<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Objeto", "Nombre", "Estado", "Detalle"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable results = new JTable(model);
    private final JButton compare = new JButton("Comparar", IconFactory.of(IconFactory.Kind.COMPARE, 16));
    private final JButton generate = new JButton("Generar script", IconFactory.of(IconFactory.Kind.SQL, 16));
    private final JLabel status = new JLabel("Selecciona el origen y el destino.");
    private final JProgressBar progress = new JProgressBar();
    private Comparison lastComparison;

    public SchemaCompareDialog(Window owner, JdbcSession activeSession, List<JdbcSession> sessions, BiConsumer<JdbcSession, String> scriptConsumer) {
        super(owner, "Comparar esquemas", ModalityType.APPLICATION_MODAL);
        this.scriptConsumer = scriptConsumer;
        for (JdbcSession session : sessions) if (session != null && session.isOpen()) {
            SessionOption option = new SessionOption(session); sourceConnection.addItem(option); targetConnection.addItem(option);
        }
        selectSession(sourceConnection, activeSession); selectOtherSession(targetConnection, activeSession);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(850, 560));
        setSize(1040, 680);
        setLocationRelativeTo(owner);
        setContentPane(buildContent());
        configureTable();
        loadDatabases(sourceSession(), source);
        loadDatabases(targetSession(), target);
    }

    public void showDialog() { setVisible(true); }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(14, 16, 14, 16));
        root.add(buildSelectors(), BorderLayout.NORTH);
        root.add(new JScrollPane(results), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        status.setIcon(IconFactory.of(IconFactory.Kind.INFO, 15));
        bottom.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton close = new JButton("Cerrar", IconFactory.of(IconFactory.Kind.CANCEL, 16));
        compare.addActionListener(e -> compare());
        generate.addActionListener(e -> generateScript());
        generate.setEnabled(false);
        close.addActionListener(e -> dispose());
        actions.add(compare);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(130, 16));
        progress.setVisible(false);
        actions.add(progress);
        actions.add(generate);
        actions.add(close);
        bottom.add(actions, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private JComponent buildSelectors() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Origen y destino"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 8, 7, 8);
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        panel.add(new JLabel("Instancia de origen:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(sourceConnection, c);
        c.gridx = 2;
        c.weightx = 0;
        JButton swap = new JButton("⇄", IconFactory.of(IconFactory.Kind.COMPARE, 16));
        swap.setToolTipText("Intercambiar origen y destino");
        swap.addActionListener(e -> {
            Object left = sourceConnection.getSelectedItem();
            sourceConnection.setSelectedItem(targetConnection.getSelectedItem());
            targetConnection.setSelectedItem(left);
        });
        panel.add(swap, c);
        c.gridx = 3;
        panel.add(new JLabel("Instancia de destino:"), c);
        c.gridx = 4;
        c.weightx = 1;
        panel.add(targetConnection, c);
        c.gridy = 1; c.gridx = 0; c.weightx = 0; panel.add(new JLabel("Base de origen:"), c);
        c.gridx = 1; c.weightx = 1; panel.add(source, c);
        c.gridx = 3; c.weightx = 0; panel.add(new JLabel("Base de destino:"), c);
        c.gridx = 4; c.weightx = 1; panel.add(target, c);
        sourceConnection.addActionListener(e -> loadDatabases(sourceSession(), source));
        targetConnection.addActionListener(e -> loadDatabases(targetSession(), target));
        return panel;
    }

    private void configureTable() {
        results.setAutoCreateRowSorter(true);
        results.setRowHeight(27);
        results.setShowGrid(false);
        results.setIntercellSpacing(new Dimension(0, 1));
        results.getTableHeader().setReorderingAllowed(false);
        results.getColumnModel().getColumn(0).setPreferredWidth(95);
        results.getColumnModel().getColumn(1).setPreferredWidth(260);
        results.getColumnModel().getColumn(2).setPreferredWidth(140);
        results.getColumnModel().getColumn(3).setPreferredWidth(430);
        results.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
    }

    private void loadDatabases(JdbcSession endpoint, JComboBox<String> combo) {
        combo.removeAllItems(); combo.setEnabled(false);
        if (endpoint == null) return;
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
            List<String> names = new ArrayList<>();
            DatabaseMetaData metadata = endpoint.connection().getMetaData();
            try (ResultSet rs = metadata.getCatalogs()) {
                while (rs.next()) {
                    String value = rs.getString("TABLE_CAT");
                    if (value != null && !value.isBlank()) names.add(value);
                }
            }
            if (names.isEmpty()) {
                try (ResultSet rs = metadata.getSchemas()) {
                    while (rs.next()) {
                        String value = rs.getString("TABLE_SCHEM");
                        if (value != null && !value.isBlank()) names.add(value);
                    }
                }
            }
            return names.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }
            @Override protected void done() {
                try {
                    for (String name : get()) combo.addItem(name);
                    String requested = endpoint.profile().database();
                    if (requested != null && !requested.isBlank()) combo.setSelectedItem(requested);
                    combo.setEnabled(combo.getItemCount() > 0);
                    compare.setEnabled(source.getItemCount() > 0 && target.getItemCount() > 0);
                    status.setText("Selecciona las instancias y bases que deseas comparar.");
                } catch (Exception error) { status.setText("No se pudieron cargar las bases: " + rootCause(error).getMessage()); }
            }
        }.execute();
    }

    private void compare() {
        JdbcSession sourceSession = sourceSession();
        JdbcSession targetSession = targetSession();
        String sourceName = (String) source.getSelectedItem();
        String targetName = (String) target.getSelectedItem();
        if (sourceSession == null || targetSession == null || sourceName == null || targetName == null) return;
        if (sourceSession == targetSession && sourceName.equalsIgnoreCase(targetName)) {
            JOptionPane.showMessageDialog(this, "Selecciona un origen y un destino diferentes.", "Comparar esquemas", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        compare.setEnabled(false);
        generate.setEnabled(false);
        status.setText("Comparando " + sourceName + " con " + targetName + "…");
        progress.setVisible(true);
        model.setRowCount(0);
        new SwingWorker<Comparison, Void>() {
            @Override protected Comparison doInBackground() throws Exception {
                return buildComparison(sourceSession, sourceName, targetSession, targetName);
            }

            @Override protected void done() {
                compare.setEnabled(true);
                progress.setVisible(false);
                try {
                    lastComparison = get();
                    for (Difference difference : lastComparison.differences()) {
                        model.addRow(new Object[]{difference.type(), difference.name(), difference.status(), difference.detail()});
                    }
                    generate.setEnabled(!lastComparison.script().isBlank());
                    long changes = lastComparison.differences().stream().filter(d -> !d.status().equals("Igual")).count();
                    status.setText(lastComparison.differences().size() + " objetos revisados · " + changes + " diferencias");
                } catch (Exception error) {
                    Throwable cause = rootCause(error);
                    status.setText("Error al comparar");
                    JOptionPane.showMessageDialog(SchemaCompareDialog.this, cause.getMessage(),
                            "No se pudieron comparar los esquemas", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private Comparison buildComparison(JdbcSession sourceSession, String sourceName, JdbcSession targetSession, String targetName) throws SQLException {
        SchemaDef left = readSchema(sourceSession, sourceName);
        SchemaDef right = readSchema(targetSession, targetName);
        List<Difference> differences = new ArrayList<>();
        StringBuilder script = new StringBuilder("-- NexoDB Studio · Sincronización de esquema\n")
                .append("-- Origen: ").append(sourceName).append("\n")
                .append("-- Destino: ").append(targetName).append("\n")
                .append("-- Revisa cuidadosamente antes de ejecutar.\n\n");

        Set<String> objectKeys = new LinkedHashSet<>();
        objectKeys.addAll(left.objects().keySet());
        objectKeys.addAll(right.objects().keySet());
        for (String key : objectKeys) {
            DbObject sourceObject = left.objects().get(key);
            DbObject targetObject = right.objects().get(key);
            if (sourceObject == null) {
                differences.add(new Difference(targetObject.type(), targetObject.name(), "Solo en destino", "No se elimina automáticamente"));
                script.append("-- Solo en destino; no se genera DROP: ").append(targetObject.type()).append(' ').append(targetObject.name()).append("\n");
                continue;
            }
            if (targetObject == null) {
                differences.add(new Difference(sourceObject.type(), sourceObject.name(), "Solo en origen", "Debe crearse en el destino"));
                appendCreateObject(script, sourceSession, sourceName, targetSession, targetName, sourceObject);
                continue;
            }
            if (!sourceObject.type().equals("Tabla")) {
                differences.add(new Difference(sourceObject.type(), sourceObject.name(), "Existe en ambos", "La definición debe revisarse desde SQL / DDL"));
                continue;
            }
            compareColumns(sourceObject, targetObject, targetSession, targetName, differences, script);
        }
        return new Comparison(differences, script.toString());
    }

    private SchemaDef readSchema(JdbcSession endpoint, String database) throws SQLException {
        boolean catalog = endpoint.profile().type() == DatabaseType.MYSQL || endpoint.profile().type() == DatabaseType.MARIADB;
        Map<String, DbObject> objects = new LinkedHashMap<>();
        DatabaseMetaData metadata = endpoint.connection().getMetaData();
        try (ResultSet rs = metadata.getTables(catalog ? database : null, catalog ? null : database,
                "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                String type = "VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE")) ? "Vista" : "Tabla";
                Map<String, ColumnDef> columns = type.equals("Tabla") ? readColumns(endpoint, database, name, catalog) : Map.of();
                objects.put(key(type, name), new DbObject(type, name, columns));
            }
        }
        return new SchemaDef(objects);
    }

    private Map<String, ColumnDef> readColumns(JdbcSession endpoint, String database, String table, boolean catalog) throws SQLException {
        Map<String, ColumnDef> columns = new LinkedHashMap<>();
        try (ResultSet rs = endpoint.connection().getMetaData().getColumns(
                catalog ? database : null, catalog ? null : database, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                ColumnDef definition = new ColumnDef(name, rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"), rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        rs.getString("COLUMN_DEF"), optional(rs, "IS_AUTOINCREMENT"));
                columns.put(name.toLowerCase(Locale.ROOT), definition);
            }
        }
        return columns;
    }

    private void compareColumns(DbObject sourceTable, DbObject targetTable, JdbcSession targetSession, String targetDatabase,
                                List<Difference> differences, StringBuilder script) {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(sourceTable.columns().keySet());
        names.addAll(targetTable.columns().keySet());
        boolean tableEqual = true;
        for (String key : names) {
            ColumnDef left = sourceTable.columns().get(key);
            ColumnDef right = targetTable.columns().get(key);
            String displayName = sourceTable.name() + "." + (left != null ? left.name() : right.name());
            if (left == null) {
                tableEqual = false;
                differences.add(new Difference("Columna", displayName, "Solo en destino", "No se elimina automáticamente"));
                script.append("-- Solo en destino; no se genera DROP COLUMN: ").append(displayName).append("\n");
            } else if (right == null) {
                tableEqual = false;
                differences.add(new Difference("Columna", displayName, "Solo en origen", left.sqlDefinition()));
                script.append("ALTER TABLE ").append(qualified(targetSession, targetDatabase, sourceTable.name()))
                        .append(" ADD COLUMN ").append(quote(targetSession, left.name())).append(' ').append(left.sqlDefinition()).append(";\n");
            } else if (!left.normalized().equals(right.normalized())) {
                tableEqual = false;
                differences.add(new Difference("Columna", displayName, "Diferente",
                        "Origen: " + left.sqlDefinition() + " · Destino: " + right.sqlDefinition()));
                if (targetSession.profile().type() == DatabaseType.MYSQL || targetSession.profile().type() == DatabaseType.MARIADB) {
                    script.append("ALTER TABLE ").append(qualified(targetSession, targetDatabase, sourceTable.name()))
                            .append(" MODIFY COLUMN ").append(quote(targetSession, left.name())).append(' ').append(left.sqlDefinition()).append(";\n");
                } else {
                    script.append("-- Modificar ").append(displayName).append(" a ").append(left.sqlDefinition()).append("\n");
                }
            }
        }
        differences.add(new Difference("Tabla", sourceTable.name(), tableEqual ? "Igual" : "Diferente",
                tableEqual ? "Estructura de columnas equivalente" : "Revisa las diferencias de columnas"));
    }

    private void appendCreateObject(StringBuilder script, JdbcSession sourceSession, String sourceDatabase,
                                    JdbcSession targetSession, String targetDatabase, DbObject object) {
        if (object.type().equals("Tabla") && sourceSession == targetSession
                && (targetSession.profile().type() == DatabaseType.MYSQL || targetSession.profile().type() == DatabaseType.MARIADB)) {
            script.append("CREATE TABLE ").append(qualified(targetSession, targetDatabase, object.name()))
                    .append(" LIKE ").append(qualified(sourceSession, sourceDatabase, object.name())).append(";\n");
        } else if (object.type().equals("Tabla")) {
            script.append("CREATE TABLE ").append(qualified(targetSession, targetDatabase, object.name())).append(" (\n");
            int index = 0;
            for (ColumnDef column : object.columns().values()) {
                if (index++ > 0) script.append(",\n");
                script.append("    ").append(quote(targetSession, column.name())).append(' ').append(column.sqlDefinition());
            }
            script.append("\n);\n");
        } else {
            script.append("-- Crear ").append(object.type()).append(' ').append(object.name())
                    .append(" en ").append(targetDatabase).append("; revisa su DDL en el explorador.\n");
        }
    }

    private void generateScript() {
        if (lastComparison == null || lastComparison.script().isBlank()) return;
        scriptConsumer.accept(targetSession(), lastComparison.script());
        dispose();
    }

    private String qualified(JdbcSession endpoint, String database, String object) { return quote(endpoint, database) + "." + quote(endpoint, object); }

    private String quote(JdbcSession endpoint, String identifier) {
        try {
            String value = endpoint.connection().getMetaData().getIdentifierQuoteString();
            value = value == null ? "" : value.trim();
            return value.isBlank() ? identifier : value + identifier.replace(value, value + value) + value;
        } catch (SQLException ignored) {
            return identifier;
        }
    }

    private static String key(String type, String name) { return type.toLowerCase(Locale.ROOT) + ":" + name.toLowerCase(Locale.ROOT); }
    private static String optional(ResultSet rs, String name) { try { return rs.getString(name); } catch (SQLException ignored) { return ""; } }
    private static Throwable rootCause(Throwable error) { Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause(); return cause; }

    private JdbcSession sourceSession() { SessionOption option = (SessionOption) sourceConnection.getSelectedItem(); return option == null ? null : option.session(); }
    private JdbcSession targetSession() { SessionOption option = (SessionOption) targetConnection.getSelectedItem(); return option == null ? null : option.session(); }
    private static void selectSession(JComboBox<SessionOption> combo, JdbcSession session) {
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).session() == session) { combo.setSelectedIndex(i); return; }
    }
    private static void selectOtherSession(JComboBox<SessionOption> combo, JdbcSession active) {
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).session() != active) { combo.setSelectedIndex(i); return; }
        selectSession(combo, active);
    }

    private record SchemaDef(Map<String, DbObject> objects) {}
    private record DbObject(String type, String name, Map<String, ColumnDef> columns) {}
    private record Difference(String type, String name, String status, String detail) {}
    private record Comparison(List<Difference> differences, String script) {}
    private record SessionOption(JdbcSession session) {
        @Override public String toString() {
            String host = session.profile().host();
            return session.profile().name() + (host.isBlank() ? "" : " · " + host + ":" + session.profile().port());
        }
    }

    private record ColumnDef(String name, String type, int size, int decimals, boolean nullable,
                             String defaultValue, String autoIncrement) {
        String sqlDefinition() {
            String upper = type == null ? "" : type.toUpperCase(Locale.ROOT);
            boolean sized = upper.contains("CHAR") || upper.contains("BINARY") || upper.equals("DECIMAL") || upper.equals("NUMERIC");
            StringBuilder value = new StringBuilder(type == null ? "" : type);
            if (sized && size > 0) value.append('(').append(size).append(decimals > 0 ? "," + decimals : "").append(')');
            value.append(nullable ? " NULL" : " NOT NULL");
            if (defaultValue != null) value.append(" DEFAULT ").append(defaultValue);
            if ("YES".equalsIgnoreCase(autoIncrement)) value.append(" AUTO_INCREMENT");
            return value.toString();
        }

        String normalized() { return sqlDefinition().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT); }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                String status = value == null ? "" : value.toString();
                label.setForeground(status.equals("Igual") || status.equals("Existe en ambos")
                        ? IconFactory.GREEN : status.equals("Diferente") ? IconFactory.AMBER : IconFactory.RED);
            }
            return label;
        }
    }
}
