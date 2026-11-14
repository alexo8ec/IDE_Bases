package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;
import com.nexodb.db.QueryResult;
import com.nexodb.db.SchemaItem;
import com.nexodb.model.DatabaseType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Table workspace inspired by the object editors in SQLyog and dbForge. */
public final class TableWorkbenchPanel extends JPanel {
    public static final int DATA = 0;
    public static final int COLUMNS = 1;
    public static final int INDEXES = 2;
    public static final int FOREIGN_KEYS = 3;
    public static final int DDL = 4;

    private final JdbcSession session;
    private final SchemaItem item;
    private final String qualifiedName;
    private final Consumer<String> statusConsumer;
    private final JTabbedPane sections = new JTabbedPane();
    private final EditableDataModel dataModel = new EditableDataModel();
    private final JTable dataTable = createTable(dataModel);
    private final DefaultTableModel columnsModel = readOnlyModel(
            tr("Columna"), tr("Tipo"), tr("Tamaño"), tr("Nulos"), tr("Predeterminado"), tr("Auto incremento"), tr("Comentario"));
    private final JTable columnsTable = UiLanguage.headers(createTable(columnsModel));
    private final DefaultTableModel indexesModel = readOnlyModel(
            tr("Índice"), tr("Único"), tr("Columna"), tr("Posición"), tr("Tipo"));
    private final JTable indexesTable = UiLanguage.headers(createTable(indexesModel));
    private final DefaultTableModel foreignKeysModel = readOnlyModel(
            tr("Relación"), tr("Columna"), tr("Tabla referenciada"), tr("Columna referenciada"), tr("Al eliminar"), tr("Al actualizar"));
    private final JTable foreignKeysTable = UiLanguage.headers(createTable(foreignKeysModel));
    private final JTextArea ddl = new JTextArea();
    private final JLabel state = UiLanguage.text(new JLabel(tr("Cargando…")), UiLanguage.key("Cargando…"));
    private final JProgressBar progress = new JProgressBar();
    private final JComboBox<String> editMode = new JComboBox<>(new String[]{"Solo lectura", "Editable · guardado inmediato"});
    private final JSpinner maxRows = new JSpinner(new SpinnerNumberModel(1000, 1, 100000, 100));
    private final JCheckBox limitRows=UiLanguage.text(new JCheckBox("",true),UiLanguage.key("Limitar filas"));
    private SwingWorker<Snapshot, Void> worker;
    private List<String> primaryKeys = List.of();
    private boolean updatingMode;
    private boolean structureWriteConfirmed;
    private Runnable pendingColumnAction;
    private Runnable structureChanged=()->{};

    public TableWorkbenchPanel(JdbcSession session, SchemaItem item, String qualifiedName,
                               Consumer<String> statusConsumer) {
        super(new BorderLayout());
        this.session = session;
        this.item = item;
        this.qualifiedName = qualifiedName;
        this.statusConsumer = statusConsumer;
        setBorder(new EmptyBorder(0, 8, 8, 8));
        add(buildToolbar(), BorderLayout.NORTH);
        configureDdl();
        UiLanguage.addTab(sections, new Object[]{UiLanguage.key("Datos")}, IconFactory.of(IconFactory.Kind.TABLE, 16), new JScrollPane(dataTable));
        UiLanguage.addTab(sections, new Object[]{UiLanguage.key("Columnas")}, IconFactory.of(IconFactory.Kind.COLUMN, 16), columnSection());
        UiLanguage.addTab(sections, new Object[]{UiLanguage.key("Índices")}, IconFactory.of(IconFactory.Kind.KEY, 16), indexSection());
        UiLanguage.addTab(sections, new Object[]{UiLanguage.key("Relaciones")}, IconFactory.of(IconFactory.Kind.COMPARE, 16), foreignKeySection());
        sections.addTab("SQL / DDL", IconFactory.of(IconFactory.Kind.SQL, 16), new JScrollPane(ddl));
        sections.putClientProperty("JTabbedPane.tabType", "card");
        add(sections, BorderLayout.CENTER);
        refresh();
    }

    public SchemaItem item() { return item; }
    public JdbcSession session() { return session; }
    public void selectSection(int section) { sections.setSelectedIndex(Math.max(0, Math.min(section, sections.getTabCount() - 1))); }
    public void setStructureChanged(Runnable callback){structureChanged=callback;}
    public void requestColumnAction(String column,boolean delete){
        selectSection(COLUMNS);
        pendingColumnAction=()->{
            if(!delete){editColumn(false);return;}
            for(int row=0;row<columnsModel.getRowCount();row++)if(column.equals(value(columnsModel,row,0))){int view=columnsTable.convertRowIndexToView(row);columnsTable.setRowSelectionInterval(view,view);deleteColumn();return;}
            info(tr("La columna ya no existe. Actualiza la consulta."));
        };
        if(worker==null||worker.isDone())refresh();
    }

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(new EmptyBorder(7, 2, 7, 2));
        JLabel title = new JLabel(qualifiedName, IconFactory.of(
                item.kind() == SchemaItem.Kind.VIEW ? IconFactory.Kind.VIEW : IconFactory.Kind.TABLE, 18), JLabel.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(new EmptyBorder(0, 4, 0, 14));
        JButton refresh = UiLanguage.text(new JButton(tr("Actualizar"), IconFactory.of(IconFactory.Kind.REFRESH, 16)), UiLanguage.key("Actualizar"));
        refresh.putClientProperty("JButton.buttonType", "toolBarButton");
        refresh.addActionListener(e -> refresh());
        bar.add(title);
        bar.addSeparator();
        bar.add(UiLanguage.text(new JLabel(tr("Modo: ")), UiLanguage.key("Modo: ")));
        editMode.setMaximumSize(new Dimension(210, 30));
        editMode.setEnabled(false);
        UiLanguage.choices(editMode);
        editMode.addActionListener(e -> changeEditMode());
        bar.add(editMode);
        bar.addSeparator();
        bar.add(limitRows);limitRows.addActionListener(e->maxRows.setEnabled(limitRows.isSelected()));
        bar.add(maxRows);
        bar.add(refresh);
        bar.add(Box.createHorizontalGlue());
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(120, 15));
        progress.setMaximumSize(new Dimension(120, 15));
        progress.setVisible(false);
        bar.add(progress);
        state.setBorder(new EmptyBorder(3, 8, 3, 5));
        bar.add(state);
        return bar;
    }

    private JComponent columnSection() {
        JToolBar actions = actionBar();
        actions.add(actionButton(tr("Nueva columna"), IconFactory.Kind.ADD, e -> editColumn(false)));
        actions.add(actionButton(tr("Modificar"), IconFactory.Kind.EDIT, e -> editColumn(true)));
        actions.add(actionButton(tr("Eliminar"), IconFactory.Kind.DELETE, e -> deleteColumn()));
        return section(actions, columnsTable);
    }

    private JComponent indexSection() {
        JToolBar actions = actionBar();
        actions.add(actionButton(tr("Nuevo índice"), IconFactory.Kind.ADD, e -> editIndex(false)));
        actions.add(actionButton(tr("Modificar índice"), IconFactory.Kind.EDIT, e -> editIndex(true)));
        actions.add(actionButton(tr("Eliminar índice"), IconFactory.Kind.DELETE, e -> deleteIndex()));
        return section(actions, indexesTable);
    }

    private JComponent foreignKeySection() {
        JToolBar actions = actionBar();
        actions.add(actionButton(tr("Nueva clave foránea"), IconFactory.Kind.ADD, e -> editForeignKey(false)));
        actions.add(actionButton(tr("Modificar relación"), IconFactory.Kind.EDIT, e -> editForeignKey(true)));
        actions.add(actionButton(tr("Eliminar relación"), IconFactory.Kind.DELETE, e -> deleteForeignKey()));
        return section(actions, foreignKeysTable);
    }

    private static JToolBar actionBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(new EmptyBorder(5, 2, 5, 2));
        return bar;
    }

    private static JButton actionButton(String text, IconFactory.Kind icon, java.awt.event.ActionListener action) {
        JButton button = new JButton(text, IconFactory.of(icon, 15));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.addActionListener(action);
        return button;
    }

    private static JComponent section(JToolBar actions, JTable table) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(actions, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private void configureDdl() {
        ddl.setEditable(false);
        ddl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        ddl.setMargin(new Insets(12, 14, 12, 14));
        ddl.setTabSize(4);
    }

    private void refresh() {
        if(!LicenseGate.ensure(this))return;
        if (worker != null && !worker.isDone()) return;
        UiLanguage.text(state, UiLanguage.key("Cargando…"));
        progress.setVisible(true);
        int limit = limitRows.isSelected()?((Number)maxRows.getValue()).intValue():Integer.MAX_VALUE;
        worker = new SwingWorker<>() {
            @Override protected Snapshot doInBackground() throws Exception {
                QueryResult data = session.execute("SELECT * FROM " + qualifiedName, limit);
                return new Snapshot(data, readColumns(), readIndexes(), readForeignKeys(), readDdl(), readPrimaryKeys());
            }

            @Override protected void done() {
                try {
                    Snapshot snapshot = get();
                    primaryKeys = snapshot.primaryKeys();
                    dataModel.setResult(snapshot.data());
                    editMode.setEnabled(item.kind() == SchemaItem.Kind.TABLE && !primaryKeys.isEmpty()&&!session.profile().readOnly());
                    replaceRows(columnsModel, snapshot.columns());
                    replaceRows(indexesModel, snapshot.indexes());
                    replaceRows(foreignKeysModel, snapshot.foreignKeys());
                    ddl.setText(snapshot.ddl());
                    ddl.setCaretPosition(0);
                    resizeColumns(dataTable);
                    resizeColumns(columnsTable);
                    resizeColumns(indexesTable);
                    String suffix = snapshot.data().truncated() ? tr(" · límite alcanzado") : "";
                    UiLanguage.text(state, (snapshot.data().rows().size()), UiLanguage.key(" filas"), suffix);
                    statusConsumer.accept(qualifiedName + " · " + snapshot.data().rows().size() + tr(" filas"));
                    if(pendingColumnAction!=null){Runnable action=pendingColumnAction;pendingColumnAction=null;SwingUtilities.invokeLater(action);}
                } catch (Exception error) {
                    pendingColumnAction=null;
                    Throwable cause = rootCause(error);
                    UiLanguage.text(state, UiLanguage.key("Error al cargar"));
                    statusConsumer.accept(tr("No se pudo abrir ") + qualifiedName);
                    JOptionPane.showMessageDialog(TableWorkbenchPanel.this,
                            cause.getMessage(), tr("No se pudo abrir la tabla"), JOptionPane.ERROR_MESSAGE);
                } finally {
                    progress.setVisible(false);
                }
            }
        };
        worker.execute();
    }

    private List<Object[]> readColumns() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        DatabaseMetaData metadata = session.connection().getMetaData();
        try (ResultSet rs = metadata.getColumns(item.catalog(), item.schema(), item.name(), "%")) {
            while (rs.next()) {
                int nullable = rs.getInt("NULLABLE");
                rows.add(new Object[]{
                        rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), formatSize(rs),
                        nullable == DatabaseMetaData.columnNoNulls ? "No" : tr("Sí"),
                        rs.getString("COLUMN_DEF"), optional(rs, "IS_AUTOINCREMENT"), rs.getString("REMARKS")
                });
            }
        }
        return rows;
    }

    private List<Object[]> readIndexes() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        DatabaseMetaData metadata = session.connection().getMetaData();
        try (ResultSet rs = metadata.getIndexInfo(item.catalog(), item.schema(), item.name(), false, true)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                rows.add(new Object[]{rs.getString("INDEX_NAME"), rs.getBoolean("NON_UNIQUE") ? "No" : tr("Sí"),
                        rs.getString("COLUMN_NAME"), rs.getShort("ORDINAL_POSITION"), indexType(rs.getShort("TYPE"))});
            }
        }
        return rows;
    }

    private List<Object[]> readForeignKeys() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        DatabaseMetaData metadata = session.connection().getMetaData();
        try (ResultSet rs = metadata.getImportedKeys(item.catalog(), item.schema(), item.name())) {
            while (rs.next()) rows.add(new Object[]{
                    rs.getString("FK_NAME"), rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_NAME"),
                    rs.getString("PKCOLUMN_NAME"), rule(rs.getShort("DELETE_RULE")), rule(rs.getShort("UPDATE_RULE"))
            });
        }
        return rows;
    }

    private List<String> readPrimaryKeys() throws SQLException {
        Map<Short, String> ordered = new HashMap<>();
        try (ResultSet rs = session.connection().getMetaData().getPrimaryKeys(item.catalog(), item.schema(), item.name())) {
            while (rs.next()) ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return ordered.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    private void changeEditMode() {
        if (updatingMode) return;
        if(session.profile().readOnly()){resetEditMode();return;}
        boolean editable = editMode.getSelectedIndex() == 1;
        if (!editable) {
            dataModel.setEditable(false);
            return;
        }
        if (item.kind() != SchemaItem.Kind.TABLE || primaryKeys.isEmpty()) {
            JOptionPane.showMessageDialog(this, tr("La edición requiere una tabla con clave primaria."),
                    tr("Solo lectura"), JOptionPane.INFORMATION_MESSAGE);
            resetEditMode();
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                tr("Los cambios se guardarán en la base de datos al terminar de editar cada celda.\n\n¿Activar el modo editable?"),
                tr("Edición en tiempo real"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            resetEditMode();
            return;
        }
        try {
            session.setReadOnly(false);
            dataModel.setEditable(true);
            UiLanguage.text(state, UiLanguage.key("Editable · cambios inmediatos"));
        } catch (SQLException error) {
            resetEditMode();
            JOptionPane.showMessageDialog(this, error.getMessage(), tr("No se pudo activar la edición"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void resetEditMode() {
        updatingMode = true;
        editMode.setSelectedIndex(0);
        updatingMode = false;
        dataModel.setEditable(false);
    }

    private void editColumn(boolean editing) {
        if (!ensureStructureWritable()) return;
        int row = editing ? selectedModelRow(columnsTable) : -1;
        if (editing && row < 0) { info(tr("Selecciona la columna que deseas modificar.")); return; }
        JTextField name = new JTextField(editing ? value(columnsModel, row, 0) : "", 24);
        name.setEditable(!editing);
        JComboBox<String> type = new JComboBox<>(new String[]{"INT", "BIGINT", "DECIMAL", "VARCHAR", "CHAR", "TEXT", "DATE", "DATETIME", "TIMESTAMP", "BOOLEAN", "BLOB", "JSON"});
        type.setEditable(true);
        JTextField size = new JTextField(editing ? value(columnsModel, row, 2) : "", 12);
        JCheckBox nullable = UiLanguage.text(new JCheckBox(tr("Permitir NULL"), !editing || tr("Sí").equals(value(columnsModel, row, 3))), UiLanguage.key("Permitir NULL"));
        JTextField defaultValue = new JTextField(editing ? value(columnsModel, row, 4) : "", 20);
        JCheckBox autoIncrement = UiLanguage.text(new JCheckBox(tr("Auto incremento"), editing && "YES".equalsIgnoreCase(value(columnsModel, row, 5))), UiLanguage.key("Auto incremento"));
        if (editing) type.setSelectedItem(value(columnsModel, row, 1));
        JPanel form = form(new String[]{tr("Nombre"), tr("Tipo"), tr("Longitud / precisión"), tr("Valor predeterminado"), tr("Opciones")},
                new JComponent[]{name, type, size, defaultValue, options(nullable, autoIncrement)});
        JLabel hint = UiLanguage.text(new JLabel(tr("<html>Ejemplos de longitud: <b>255</b> o <b>10,2</b>. El valor predeterminado es una expresión SQL, por ejemplo <b>0</b>, <b>'texto'</b> o <b>CURRENT_TIMESTAMP</b>.</html>")), UiLanguage.key("<html>Ejemplos de longitud: <b>255</b> o <b>10,2</b>. El valor predeterminado es una expresión SQL, por ejemplo <b>0</b>, <b>'texto'</b> o <b>CURRENT_TIMESTAMP</b>.</html>"));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel content = new JPanel(new BorderLayout(6, 8)); content.add(form, BorderLayout.CENTER); content.add(hint, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, content, editing ? tr("Modificar columna") : tr("Nueva columna"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String columnName = name.getText().trim();
        String dataType = String.valueOf(type.getEditor().getItem()).trim().toUpperCase();
        String length = size.getText().trim();
        if (columnName.isBlank() || !dataType.matches("[A-Z0-9_ ]+(?:\\([0-9 ,]+\\))?") || !length.matches("[0-9 ,]*")) {
            warn(tr("Revisa el nombre, tipo y longitud de la columna.")); return;
        }
        if (!length.isBlank() && !dataType.contains("(")) dataType += "(" + length.replace(" ", "") + ")";
        StringBuilder definition = new StringBuilder(quoteIdentifier(columnName)).append(' ').append(dataType);
        definition.append(nullable.isSelected() ? " NULL" : " NOT NULL");
        if (!defaultValue.getText().isBlank()) definition.append(" DEFAULT ").append(defaultValue.getText().trim());
        if (autoIncrement.isSelected()) {
            if (session.profile().type() == DatabaseType.MYSQL || session.profile().type() == DatabaseType.MARIADB) definition.append(" AUTO_INCREMENT");
            else if (session.profile().type() == DatabaseType.SQLSERVER) definition.append(" IDENTITY(1,1)");
            else definition.append(" GENERATED BY DEFAULT AS IDENTITY");
        }
        String sql;
        if (!editing) sql = "ALTER TABLE " + qualifiedName
                + (session.profile().type() == DatabaseType.SQLSERVER ? " ADD " : session.profile().type() == DatabaseType.ORACLE ? " ADD (" : " ADD COLUMN ")
                + definition + (session.profile().type() == DatabaseType.ORACLE ? ")" : "");
        else if (session.profile().type() == DatabaseType.MYSQL || session.profile().type() == DatabaseType.MARIADB)
            sql = "ALTER TABLE " + qualifiedName + " MODIFY COLUMN " + definition;
        else if (session.profile().type() == DatabaseType.SQLSERVER)
            sql = "ALTER TABLE " + qualifiedName + " ALTER COLUMN " + definition;
        else sql = "ALTER TABLE " + qualifiedName + " ALTER COLUMN " + quoteIdentifier(columnName) + " TYPE " + dataType;
        executeDdl(sql, editing ? tr("Columna modificada") : tr("Columna creada"));
    }

    private void deleteColumn() {
        if (!ensureStructureWritable()) return;
        int row = selectedModelRow(columnsTable);
        if (row < 0) { info(tr("Selecciona la columna que deseas eliminar.")); return; }
        String name = value(columnsModel, row, 0);
        if (!confirm(tr("Se eliminará la columna “") + name + tr("” y todos sus datos.\n\nEsta operación no se puede deshacer."))) return;
        executeDdl("ALTER TABLE " + qualifiedName + " DROP COLUMN " + quoteIdentifier(name), tr("Columna eliminada"));
    }

    private void editIndex(boolean editing) {
        if (!ensureStructureWritable()) return;
        List<String> columns = columnNames();
        if (columns.isEmpty()) { info(tr("La tabla no tiene columnas disponibles.")); return; }
        int selectedRow = editing ? selectedModelRow(indexesTable) : -1;
        if (editing && selectedRow < 0) { info(tr("Selecciona el índice que deseas modificar.")); return; }
        String originalName = editing ? value(indexesModel, selectedRow, 0) : "";
        if ("PRIMARY".equalsIgnoreCase(originalName)) { warn(tr("La clave primaria no se modifica como un índice normal.")); return; }
        JList<String> selectedColumns = new JList<>(columns.toArray(String[]::new));
        selectedColumns.setVisibleRowCount(Math.min(8, columns.size()));
        selectedColumns.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        if (editing) {
            List<Integer> selected = new ArrayList<>();
            for (int row = 0; row < indexesModel.getRowCount(); row++) if (originalName.equalsIgnoreCase(value(indexesModel, row, 0))) {
                int index = columns.indexOf(value(indexesModel, row, 2)); if (index >= 0) selected.add(index);
            }
            selectedColumns.setSelectedIndices(selected.stream().mapToInt(Integer::intValue).toArray());
        } else selectedColumns.setSelectedIndex(0);
        JTextField name = new JTextField(editing ? originalName : "idx_" + item.name() + "_" + columns.get(0), 25);
        JCheckBox unique = UiLanguage.text(new JCheckBox(tr("Índice único"), editing && tr("Sí").equals(value(indexesModel, selectedRow, 1))), UiLanguage.key("Índice único"));
        JPanel form = form(new String[]{tr("Nombre"), tr("Columnas"), tr("Tipo")},
                new JComponent[]{name, new JScrollPane(selectedColumns), unique});
        if (JOptionPane.showConfirmDialog(this, form, editing ? tr("Modificar índice") : tr("Crear índice"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        List<String> chosen = selectedColumns.getSelectedValuesList();
        if (name.getText().isBlank() || chosen.isEmpty()) { warn(tr("Escribe un nombre y selecciona al menos una columna.")); return; }
        String fields = chosen.stream().map(this::quoteIdentifier).collect(java.util.stream.Collectors.joining(", "));
        String create = "CREATE " + (unique.isSelected() ? "UNIQUE " : "") + "INDEX " + quoteIdentifier(name.getText().trim())
                + " ON " + qualifiedName + " (" + fields + ")";
        if (!editing) executeDdl(create, tr("Índice creado"));
        else if (session.profile().type() == DatabaseType.MYSQL || session.profile().type() == DatabaseType.MARIADB) {
            String sql = "ALTER TABLE " + qualifiedName + " DROP INDEX " + quoteIdentifier(originalName)
                    + ", ADD " + (unique.isSelected() ? "UNIQUE " : "") + "INDEX " + quoteIdentifier(name.getText().trim()) + " (" + fields + ")";
            executeDdl(sql, tr("Índice modificado"));
        } else {
            executeDdl(List.of("DROP INDEX " + quoteIdentifier(originalName), create), tr("Índice modificado"));
        }
    }

    private void deleteIndex() {
        if (!ensureStructureWritable()) return;
        int row = selectedModelRow(indexesTable);
        if (row < 0) { info(tr("Selecciona el índice que deseas eliminar.")); return; }
        String name = value(indexesModel, row, 0);
        if ("PRIMARY".equalsIgnoreCase(name)) { warn(tr("La clave primaria se administra desde la estructura de la tabla y no como un índice normal.")); return; }
        if (!confirm(tr("¿Eliminar el índice “") + name + "”?")) return;
        String sql = session.profile().type() == DatabaseType.MYSQL || session.profile().type() == DatabaseType.MARIADB
                ? "DROP INDEX " + quoteIdentifier(name) + " ON " + qualifiedName
                : "DROP INDEX " + quoteIdentifier(name);
        executeDdl(sql, tr("Índice eliminado"));
    }

    private void editForeignKey(boolean editing) {
        if (!ensureStructureWritable()) return;
        List<String> columns = columnNames();
        if (columns.isEmpty()) { info(tr("La tabla no tiene columnas disponibles.")); return; }
        int selectedRow = editing ? selectedModelRow(foreignKeysTable) : -1;
        if (editing && selectedRow < 0) { info(tr("Selecciona la relación que deseas modificar.")); return; }
        String originalName = editing ? value(foreignKeysModel, selectedRow, 0) : "";
        String preferredTable = editing ? value(foreignKeysModel, selectedRow, 2) : null;
        String preferredColumn = editing ? value(foreignKeysModel, selectedRow, 3) : null;
        JTextField name = new JTextField(editing ? originalName : "fk_" + item.name() + "_", 24);
        JComboBox<String> localColumn = new JComboBox<>(columns.toArray(String[]::new));
        if (editing) localColumn.setSelectedItem(value(foreignKeysModel, selectedRow, 1));
        JComboBox<String> referenceTable = new JComboBox<>(); referenceTable.setEditable(true);
        JComboBox<String> referenceColumn = new JComboBox<>(); referenceColumn.setEditable(true);
        JComboBox<String> onDelete = new JComboBox<>(new String[]{"NO ACTION", "CASCADE", "SET NULL", "RESTRICT"});
        JComboBox<String> onUpdate = new JComboBox<>(new String[]{"NO ACTION", "CASCADE", "SET NULL", "RESTRICT"});
        if (editing) {
            onDelete.setSelectedItem(value(foreignKeysModel, selectedRow, 4));
            onUpdate.setSelectedItem(value(foreignKeysModel, selectedRow, 5));
        }
        referenceTable.addItem(tr("Cargando tablas…"));
        loadReferenceTables(referenceTable, referenceColumn, preferredTable, preferredColumn);
        referenceTable.addActionListener(e -> {
            Object selected = referenceTable.getSelectedItem();
            if (selected != null && !selected.toString().startsWith(tr("Cargando"))) loadReferenceColumns(selected.toString(), referenceColumn);
        });
        JPanel form = form(new String[]{tr("Nombre de la relación"), tr("Columna local"), tr("Tabla referenciada"), tr("Columna referenciada"), tr("Al eliminar"), tr("Al actualizar")},
                new JComponent[]{name, localColumn, referenceTable, referenceColumn, onDelete, onUpdate});
        if (JOptionPane.showConfirmDialog(this, form, editing ? tr("Modificar relación") : tr("Nueva clave foránea"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String relation = name.getText().trim();
        String referencedTable = String.valueOf(referenceTable.getEditor().getItem()).trim();
        String referencedColumn = String.valueOf(referenceColumn.getEditor().getItem()).trim();
        if (relation.isBlank() || referencedTable.isBlank() || referencedColumn.isBlank()
                || referencedTable.startsWith(tr("Cargando")) || "null".equalsIgnoreCase(referencedColumn)) {
            warn(tr("Completa el nombre, la tabla y la columna referenciada.")); return;
        }
        String reference = qualifiedReference(referencedTable);
        String add = "ADD CONSTRAINT " + quoteIdentifier(relation)
                + " FOREIGN KEY (" + quoteIdentifier(String.valueOf(localColumn.getSelectedItem())) + ") REFERENCES "
                + reference + " (" + quoteIdentifier(referencedColumn) + ") ON DELETE " + onDelete.getSelectedItem()
                + " ON UPDATE " + onUpdate.getSelectedItem();
        if (!editing) executeDdl("ALTER TABLE " + qualifiedName + " " + add, tr("Clave foránea creada"));
        else if (session.profile().type() == DatabaseType.MYSQL || session.profile().type() == DatabaseType.MARIADB) {
            executeDdl("ALTER TABLE " + qualifiedName + " DROP FOREIGN KEY " + quoteIdentifier(originalName) + ", " + add,
                    tr("Relación modificada"));
        } else {
            executeDdl(List.of("ALTER TABLE " + qualifiedName + " DROP CONSTRAINT " + quoteIdentifier(originalName),
                    "ALTER TABLE " + qualifiedName + " " + add), tr("Relación modificada"));
        }
    }

    private void deleteForeignKey() {
        if (!ensureStructureWritable()) return;
        int row = selectedModelRow(foreignKeysTable);
        if (row < 0) { info(tr("Selecciona la relación que deseas eliminar.")); return; }
        String name = value(foreignKeysModel, row, 0);
        if (!confirm(tr("¿Eliminar la clave foránea “") + name + "”?")) return;
        String action = session.profile().type() == DatabaseType.MYSQL || session.profile().type() == DatabaseType.MARIADB
                ? " DROP FOREIGN KEY " : " DROP CONSTRAINT ";
        executeDdl("ALTER TABLE " + qualifiedName + action + quoteIdentifier(name), tr("Clave foránea eliminada"));
    }

    private void executeDdl(String sql, String success) {
        executeDdl(List.of(sql), success);
    }

    private void executeDdl(List<String> statements, String success) {
        if(!LicenseGate.ensure(this)||!Confirmations.ask(this,"Aplicar cambio de estructura",qualifiedName+"\n\n"+String.join(";\n",statements)+"\n\n"+tr("¿Aplicar este cambio en la base de datos?")))return;
        progress.setVisible(true); UiLanguage.text(state, UiLanguage.key("Aplicando cambio de estructura…")); sections.setEnabled(false);
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                int updated = 0;
                for (String sql : statements) updated += Math.max(0, session.execute(sql, 1).updateCount());
                return updated;
            }
            @Override protected void done() {
                progress.setVisible(false); sections.setEnabled(true);
                try { get(); state.setText(success); statusConsumer.accept(success + " · " + item.name()); structureChanged.run(); refresh(); }
                catch (Exception error) {
                    UiLanguage.text(state, UiLanguage.key("No se aplicó el cambio"));
                    JOptionPane.showMessageDialog(TableWorkbenchPanel.this, rootCause(error).getMessage(),
                            tr("No se pudo modificar la estructura"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private boolean ensureStructureWritable() {
        if(session.profile().readOnly()){info(tr("La conexión es de solo lectura."));return false;}
        if (item.kind() != SchemaItem.Kind.TABLE) { info(tr("Las vistas no permiten modificar columnas, índices ni relaciones desde este editor.")); return false; }
        try {
            if (session.connection().isReadOnly() || (session.profile().readOnly() && !structureWriteConfirmed)) {
                if (!confirm(tr("Esta conexión está configurada como solo lectura.\n\n¿Activar escritura para modificar la estructura durante esta sesión?"))) return false;
                session.setReadOnly(false);
                structureWriteConfirmed = true;
            }
            return true;
        } catch (SQLException error) {
            warn(error.getMessage()); return false;
        }
    }

    private void loadReferenceTables(JComboBox<String> tables, JComboBox<String> columns,
                                     String preferredTable, String preferredColumn) {
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                List<String> values = new ArrayList<>();
                try (ResultSet rs = session.connection().getMetaData().getTables(item.catalog(), item.schema(), "%", new String[]{"TABLE"})) {
                    while (rs.next()) values.add(rs.getString("TABLE_NAME"));
                }
                values.sort(String.CASE_INSENSITIVE_ORDER); return values;
            }
            @Override protected void done() {
                try {
                    tables.removeAllItems(); for (String value : get()) tables.addItem(value);
                    if (preferredTable != null && !preferredTable.isBlank()) tables.setSelectedItem(preferredTable);
                    if (tables.getItemCount() > 0) loadReferenceColumns(String.valueOf(tables.getSelectedItem()), columns, preferredColumn);
                } catch (Exception ignored) { tables.removeAllItems(); }
            }
        }.execute();
    }

    private void loadReferenceColumns(String table, JComboBox<String> columns) {
        loadReferenceColumns(table, columns, null);
    }

    private void loadReferenceColumns(String table, JComboBox<String> columns, String preferredColumn) {
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                List<String> values = new ArrayList<>();
                try (ResultSet rs = session.connection().getMetaData().getColumns(item.catalog(), item.schema(), table, "%")) {
                    while (rs.next()) values.add(rs.getString("COLUMN_NAME"));
                }
                return values;
            }
            @Override protected void done() {
                try {
                    columns.removeAllItems(); for (String value : get()) columns.addItem(value);
                    if (preferredColumn != null && !preferredColumn.isBlank()) columns.setSelectedItem(preferredColumn);
                }
                catch (Exception ignored) { columns.removeAllItems(); }
            }
        }.execute();
    }

    private String qualifiedReference(String table) {
        String namespace = item.catalog() != null && !item.catalog().isBlank() ? item.catalog() : item.schema();
        return (namespace == null || namespace.isBlank() ? "" : quoteIdentifier(namespace) + ".") + quoteIdentifier(table);
    }

    private List<String> columnNames() {
        List<String> values = new ArrayList<>();
        for (int row = 0; row < columnsModel.getRowCount(); row++) values.add(value(columnsModel, row, 0));
        return values;
    }

    private static JPanel form(String[] labels, JComponent[] fields) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints(); gc.insets = new Insets(5, 5, 5, 5); gc.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < labels.length; i++) {
            gc.gridy = i; gc.gridx = 0; gc.weightx = 0; panel.add(UiLanguage.text(new JLabel(), UiLanguage.caption(labels[i]), ":"), gc);
            gc.gridx = 1; gc.weightx = 1; panel.add(fields[i], gc);
        }
        return panel;
    }

    private static JPanel options(JComponent... values) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); for (JComponent value : values) panel.add(value); return panel;
    }
    private static int selectedModelRow(JTable table) { int row = table.getSelectedRow(); return row < 0 ? -1 : table.convertRowIndexToModel(row); }
    private static String value(DefaultTableModel model, int row, int column) { Object value = model.getValueAt(row, column); return value == null ? "" : value.toString(); }
    private boolean confirm(String message) { return JOptionPane.showConfirmDialog(this, message, tr("Confirmar cambio"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION; }
    private void info(String message) { JOptionPane.showMessageDialog(this, message, tr("Estructura de tabla"), JOptionPane.INFORMATION_MESSAGE); }
    private void warn(String message) { JOptionPane.showMessageDialog(this, message, tr("Revisa la estructura"), JOptionPane.WARNING_MESSAGE); }

    private void saveCell(int row, int column, Object oldValue, Object newValue, List<Object> originalRow) {
        if(!LicenseGate.ensure(this)||!Confirmations.ask(this,"Guardar cambio de celda",qualifiedName+" · "+dataModel.getColumnName(column)+"\n"+tr("Valor anterior: ")+oldValue+"\n"+tr("Nuevo valor: ")+newValue+"\n"+tr("¿Guardar este cambio en la base de datos?"))){dataModel.revertCell(row,column,oldValue);return;}
        dataTable.setEnabled(false);
        progress.setVisible(true);
        UiLanguage.text(state, UiLanguage.key("Guardando cambio…"));
        String columnName = dataModel.getColumnName(column);
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                StringBuilder sql = new StringBuilder("UPDATE ").append(qualifiedName)
                        .append(" SET ").append(quoteIdentifier(columnName)).append("=? WHERE ");
                List<Object> keyValues = new ArrayList<>();
                for (int i = 0; i < primaryKeys.size(); i++) {
                    if (i > 0) sql.append(" AND ");
                    String key = primaryKeys.get(i);
                    sql.append(quoteIdentifier(key)).append("=?");
                    int keyColumn = dataModel.columnIndex(key);
                    if (keyColumn < 0) throw new SQLException(tr("No se encontró la clave primaria ") + key + tr(" en el resultado."));
                    keyValues.add(originalRow.get(keyColumn));
                }
                try (PreparedStatement statement = session.connection().prepareStatement(sql.toString())) {
                    statement.setObject(1, newValue);
                    for (int i = 0; i < keyValues.size(); i++) statement.setObject(i + 2, keyValues.get(i));
                    return statement.executeUpdate();
                }
            }

            @Override protected void done() {
                try {
                    int updated = get();
                    if (updated != 1) throw new SQLException(tr("Se esperaba actualizar una fila, pero el servidor actualizó ") + updated + ".");
                    dataModel.commitCell(row, column, newValue);
                    UiLanguage.text(state, UiLanguage.key("Cambio guardado"));
                    statusConsumer.accept(qualifiedName + tr(" actualizado"));
                } catch (Exception error) {
                    dataModel.revertCell(row, column, oldValue);
                    Throwable cause = rootCause(error);
                    UiLanguage.text(state, UiLanguage.key("No se guardó el cambio"));
                    JOptionPane.showMessageDialog(TableWorkbenchPanel.this, cause.getMessage(),
                            tr("No se pudo actualizar la fila"), JOptionPane.ERROR_MESSAGE);
                } finally {
                    dataTable.setEnabled(true);
                    progress.setVisible(false);
                }
            }
        }.execute();
    }

    private String quoteIdentifier(String name) {
        try {
            String quote = session.connection().getMetaData().getIdentifierQuoteString();
            quote = quote == null ? "" : quote.trim();
            return quote.isBlank() ? name : quote + name.replace(quote, quote + quote) + quote;
        } catch (SQLException ignored) {
            return name;
        }
    }

    private String readDdl() throws SQLException {
        DatabaseType type = session.profile().type();
        if (type == DatabaseType.MYSQL || type == DatabaseType.MARIADB) {
            String command = item.kind() == SchemaItem.Kind.VIEW ? "SHOW CREATE VIEW " : "SHOW CREATE TABLE ";
            QueryResult result = session.execute(command + qualifiedName, 1);
            if (!result.rows().isEmpty()) {
                List<Object> row = result.rows().get(0);
                if (row.size() > 1 && row.get(1) != null) return row.get(1).toString() + ";\n";
            }
        }
        return genericDdl();
    }

    private String genericDdl() throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(qualifiedName).append(" (\n");
        List<Object[]> columns = readColumns();
        for (int i = 0; i < columns.size(); i++) {
            Object[] column = columns.get(i);
            sql.append("    ").append(column[0]).append(' ').append(column[1]);
            if ("No".equals(column[3])) sql.append(" NOT NULL");
            if (column[4] != null) sql.append(" DEFAULT ").append(column[4]);
            if (i + 1 < columns.size()) sql.append(',');
            sql.append('\n');
        }
        return sql.append(");\n").toString();
    }

    private static String formatSize(ResultSet rs) throws SQLException {
        int size = rs.getInt("COLUMN_SIZE");
        int decimals = rs.getInt("DECIMAL_DIGITS");
        if (size <= 0) return "";
        return decimals > 0 ? size + "," + decimals : Integer.toString(size);
    }

    private static String optional(ResultSet rs, String name) {
        try { return rs.getString(name); } catch (SQLException ignored) { return ""; }
    }

    private static String indexType(short type) {
        return switch (type) {
            case DatabaseMetaData.tableIndexClustered -> tr("Agrupado");
            case DatabaseMetaData.tableIndexHashed -> "Hash";
            case DatabaseMetaData.tableIndexOther -> tr("Índice");
            default -> "";
        };
    }

    private static String rule(short rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private static JTable createTable(javax.swing.table.TableModel model) {
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

    private static void replaceRows(DefaultTableModel model, List<Object[]> rows) {
        model.setRowCount(0);
        for (Object[] row : rows) model.addRow(row);
    }

    private static void resizeColumns(JTable table) {
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            int width = Math.max(90, Math.min(360, table.getColumnName(i).length() * 10 + 30));
            for (int row = 0; row < Math.min(30, table.getRowCount()); row++) {
                Object value = table.getValueAt(row, i);
                if (value != null) width = Math.max(width, Math.min(360, value.toString().length() * 8 + 24));
            }
            column.setPreferredWidth(width);
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private final class EditableDataModel extends AbstractTableModel {
        private List<String> columns = List.of();
        private final List<List<Object>> rows = new ArrayList<>();
        private final List<List<Object>> originalRows = new ArrayList<>();
        private boolean editable;

        void setResult(QueryResult result) {
            columns = result.columns();
            rows.clear();
            originalRows.clear();
            for (List<Object> source : result.rows()) {
                rows.add(new ArrayList<>(source));
                originalRows.add(new ArrayList<>(source));
            }
            fireTableStructureChanged();
        }

        void setEditable(boolean value) { editable = value; fireTableDataChanged(); }
        int columnIndex(String name) {
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).equalsIgnoreCase(name)) return i;
            return -1;
        }
        void commitCell(int row, int column, Object value) { originalRows.get(row).set(column, value); }
        void revertCell(int row, int column, Object value) { rows.get(row).set(column, value); fireTableCellUpdated(row, column); }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.size(); }
        @Override public String getColumnName(int column) { return columns.get(column); }
        @Override public Object getValueAt(int row, int column) { return rows.get(row).get(column); }
        @Override public boolean isCellEditable(int row, int column) { return editable; }
        @Override public Class<?> getColumnClass(int column) {
            for (List<Object> row : rows) if (row.get(column) != null) return row.get(column).getClass();
            return Object.class;
        }
        @Override public void setValueAt(Object value, int row, int column) {
            Object old = rows.get(row).get(column);
            if (java.util.Objects.equals(old, value)) return;
            List<Object> original = new ArrayList<>(originalRows.get(row));
            rows.get(row).set(column, value);
            fireTableCellUpdated(row, column);
            saveCell(row, column, old, value, original);
        }
    }

    private record Snapshot(QueryResult data, List<Object[]> columns, List<Object[]> indexes, List<Object[]> foreignKeys, String ddl,
                            List<String> primaryKeys) {}
}
