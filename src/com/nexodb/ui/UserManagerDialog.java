package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Basic MySQL/MariaDB account administration without exposing password hashes. */
public final class UserManagerDialog extends JDialog {
    private final JdbcSession session;
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{tr("Usuario"), "Host", "Plugin"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable users = new JTable(model);
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel(tr("Cargando usuarios…"));

    public UserManagerDialog(Window owner, JdbcSession session) {
        super(owner, tr("Administrar usuarios"), ModalityType.APPLICATION_MODAL);
        this.session = session;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(720, 480));
        setSize(850, 590);
        setLocationRelativeTo(owner);
        setContentPane(buildContent());
        loadUsers();
    }

    public void showDialog() { setVisible(true); }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));
        JLabel title = new JLabel(tr("Usuarios de MySQL / MariaDB"), IconFactory.of(IconFactory.Kind.USER, 22), JLabel.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        root.add(title, BorderLayout.NORTH);
        users.setAutoCreateRowSorter(true);
        users.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        users.setRowHeight(27);
        users.setShowGrid(false);
        users.getColumnModel().getColumn(0).setPreferredWidth(220);
        users.getColumnModel().getColumn(1).setPreferredWidth(180);
        users.getColumnModel().getColumn(2).setPreferredWidth(260);
        root.add(new JScrollPane(users), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        JButton create = button(tr("Nuevo usuario"), IconFactory.Kind.ADD, e -> createUser());
        JButton password = button(tr("Cambiar contraseña"), IconFactory.Kind.KEY, e -> changePassword());
        JButton grants = button(tr("Permisos"), IconFactory.Kind.USER, e -> grantPrivileges());
        JButton showGrants = button(tr("Ver permisos"), IconFactory.Kind.VIEW, e -> showGrants());
        JButton delete = button(tr("Eliminar"), IconFactory.Kind.DELETE, e -> deleteUser());
        JButton refresh = button(tr("Actualizar"), IconFactory.Kind.REFRESH, e -> loadUsers());
        actions.add(create); actions.add(password); actions.add(grants); actions.add(showGrants); actions.add(delete); actions.add(refresh);
        bottom.add(actions, BorderLayout.NORTH);

        JPanel state = new JPanel(new BorderLayout(8, 0));
        status.setIcon(IconFactory.of(IconFactory.Kind.INFO, 15));
        state.add(status, BorderLayout.CENTER);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(140, 16));
        progress.setVisible(false);
        state.add(progress, BorderLayout.EAST);
        bottom.add(state, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private JButton button(String text, IconFactory.Kind icon, java.awt.event.ActionListener action) {
        JButton button = new JButton(text, IconFactory.of(icon, 16));
        button.addActionListener(action);
        return button;
    }

    private void loadUsers() {
        run(tr("Leyendo usuarios…"), () -> {
            List<Object[]> rows = new ArrayList<>();
            try (Statement statement = session.connection().createStatement();
                 ResultSet rs = statement.executeQuery("SELECT User, Host, plugin FROM mysql.user ORDER BY User, Host")) {
                while (rs.next()) rows.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            }
            return rows;
        }, rows -> {
            model.setRowCount(0);
            for (Object[] row : rows) model.addRow(row);
            status.setText(rows.size() + tr(" cuentas cargadas"));
        });
    }

    private void createUser() {
        JTextField name = new JTextField(20);
        JTextField host = new JTextField("localhost", 20);
        JPasswordField password = new JPasswordField(20);
        JPanel form = form(new String[]{tr("Usuario:"), "Host:", tr("Contraseña:")}, new JComponent[]{name, host, password});
        if (JOptionPane.showConfirmDialog(this, form, tr("Nuevo usuario"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String user = validIdentity(name.getText(), "usuario");
        String userHost = validIdentity(host.getText(), "host");
        if (user == null || userHost == null) return;
        String sql = "CREATE USER " + account(user, userHost) + " IDENTIFIED BY " + literal(new String(password.getPassword()));
        executeChange(tr("Creando usuario…"), sql, tr("Usuario creado correctamente"));
    }

    private void changePassword() {
        Account selected = selectedAccount();
        if (selected == null) return;
        JPasswordField password = new JPasswordField(22);
        if (JOptionPane.showConfirmDialog(this, password, tr("Nueva contraseña para ") + selected.display(),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        executeChange(tr("Cambiando contraseña…"), "ALTER USER " + account(selected.user(), selected.host())
                + " IDENTIFIED BY " + literal(new String(password.getPassword())), tr("Contraseña actualizada"));
    }

    private void grantPrivileges() {
        Account selected = selectedAccount();
        if (selected == null) return;
        JComboBox<String> database = new JComboBox<>();
        try (ResultSet rs = session.connection().getMetaData().getCatalogs()) {
            while (rs.next()) database.addItem(rs.getString("TABLE_CAT"));
        } catch (SQLException error) {
            showError(error);
            return;
        }
        String[] names = {"SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "INDEX", "EXECUTE", "SHOW VIEW"};
        JPanel privileges = new JPanel(new GridLayout(0, 2, 6, 4));
        List<JCheckBox> checks = new ArrayList<>();
        for (String name : names) { JCheckBox check = new JCheckBox(name); checks.add(check); privileges.add(check); }
        JCheckBox grantOption = new JCheckBox(tr("Permitir delegar permisos (GRANT OPTION)"));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(new JLabel(tr("Base de datos:"))); form.add(database); form.add(Box.createVerticalStrut(8)); form.add(privileges); form.add(grantOption);
        if (JOptionPane.showConfirmDialog(this, form, tr("Asignar permisos a ") + selected.display(),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        List<String> chosen = new ArrayList<>();
        for (JCheckBox check : checks) if (check.isSelected()) chosen.add(check.getText());
        if (chosen.isEmpty() || database.getSelectedItem() == null) {
            JOptionPane.showMessageDialog(this, tr("Selecciona al menos un permiso y una base."));
            return;
        }
        String sql = "GRANT " + String.join(", ", chosen) + " ON " + identifier(database.getSelectedItem().toString())
                + ".* TO " + account(selected.user(), selected.host()) + (grantOption.isSelected() ? " WITH GRANT OPTION" : "");
        executeChange(tr("Asignando permisos…"), sql, tr("Permisos actualizados"));
    }

    private void showGrants() {
        Account selected = selectedAccount();
        if (selected == null) return;
        run(tr("Leyendo permisos…"), () -> {
            StringBuilder text = new StringBuilder();
            try (Statement statement = session.connection().createStatement();
                 ResultSet rs = statement.executeQuery("SHOW GRANTS FOR " + account(selected.user(), selected.host()))) {
                while (rs.next()) text.append(rs.getString(1)).append(";\n\n");
            }
            return text.toString();
        }, text -> {
            JTextArea area = new JTextArea(text, 15, 75);
            area.setEditable(false); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JOptionPane.showMessageDialog(this, new JScrollPane(area), tr("Permisos de ") + selected.display(), JOptionPane.INFORMATION_MESSAGE);
            status.setText(tr("Permisos cargados"));
        });
    }

    private void deleteUser() {
        Account selected = selectedAccount();
        if (selected == null) return;
        if (JOptionPane.showConfirmDialog(this, tr("¿Eliminar definitivamente ") + selected.display() + "?",
                tr("Eliminar usuario"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        executeChange(tr("Eliminando usuario…"), "DROP USER " + account(selected.user(), selected.host()), tr("Usuario eliminado"));
    }

    private void executeChange(String running, String sql, String success) {
        run(running, () -> {
            session.setReadOnly(false);
            try (Statement statement = session.connection().createStatement()) { statement.execute(sql); }
            return success;
        }, message -> { status.setText(message); loadUsers(); });
    }

    private <T> void run(String message, Callable<T> task, java.util.function.Consumer<T> done) {
        progress.setVisible(true);
        status.setText(message);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                progress.setVisible(false);
                try { done.accept(get()); }
                catch (Exception error) { status.setText(tr("Operación fallida")); showError(rootCause(error)); }
            }
        }.execute();
    }

    private Account selectedAccount() {
        int viewRow = users.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(this, tr("Selecciona un usuario."), tr("Administrar usuarios"), JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        int row = users.convertRowIndexToModel(viewRow);
        return new Account(model.getValueAt(row, 0).toString(), model.getValueAt(row, 1).toString());
    }

    private String validIdentity(String value, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || !clean.matches("[A-Za-z0-9_.:%$-]+")) {
            JOptionPane.showMessageDialog(this, "El " + label + tr(" está vacío o contiene caracteres no permitidos."),
                    tr("Dato no válido"), JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return clean;
    }

    private JPanel form(String[] labels, JComponent[] fields) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5); c.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < labels.length; i++) {
            c.gridx = 0; c.gridy = i; c.weightx = 0; panel.add(new JLabel(labels[i]), c);
            c.gridx = 1; c.weightx = 1; panel.add(fields[i], c);
        }
        return panel;
    }

    private String account(String user, String host) { return literal(user) + "@" + literal(host); }
    private String literal(String value) { return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"; }
    private String identifier(String value) { return "`" + value.replace("`", "``") + "`"; }
    private void showError(Throwable error) { JOptionPane.showMessageDialog(this, error.getMessage(), tr("Operación fallida"), JOptionPane.ERROR_MESSAGE); }
    private static Throwable rootCause(Throwable error) { Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause(); return cause; }
    private record Account(String user, String host) { String display() { return "'" + user + "'@'" + host + "'"; } }
}
