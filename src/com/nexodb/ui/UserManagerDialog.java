package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;
import com.nexodb.db.JdbcSession;
import com.nexodb.db.ManagedConnection;
import com.nexodb.db.UserAdministration;
import com.nexodb.db.UserAdministration.Account;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;

/** Native account administration, with explicit capability and scope per engine. */
public final class UserManagerDialog extends JDialog {
    private final JdbcSession session;
    private UserAdministration admin;
    private final List<Account> accounts = new ArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{tr("Usuario"), tr("Ámbito / estado"), tr("Autenticación / detalle")}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable users = UiLanguage.headers(new JTable(model));
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel(" "), explanation = new JLabel(" "), title = new JLabel();
    private final JTextField search = new JTextField(25);
    private final JButton create = button("Nuevo usuario", IconFactory.Kind.ADD, this::createUser);
    private final JButton password = button("Cambiar contraseña", IconFactory.Kind.KEY, this::changePassword);
    private final JButton grants = button("Permisos", IconFactory.Kind.USER, this::grantPrivileges);
    private final JButton view = button("Ver permisos", IconFactory.Kind.VIEW, this::showGrants);
    private final JButton delete = button("Eliminar", IconFactory.Kind.DELETE, this::deleteUser);
    private final JButton refresh = button("Actualizar", IconFactory.Kind.REFRESH, this::loadUsers);
    private boolean busy;

    public UserManagerDialog(Window owner, JdbcSession session) {
        super(owner, tr("Administrar usuarios"), ModalityType.APPLICATION_MODAL);
        this.session = session;
        UiLanguage.title(this, UiLanguage.key("Administrar usuarios"));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE); setMinimumSize(new Dimension(860, 520)); setSize(980, 620);
        setContentPane(buildContent()); setLocationRelativeTo(owner); loadUsers();
    }
    public void showDialog() { setVisible(true); }
    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(10, 12)); root.setBorder(new EmptyBorder(16, 16, 14, 16));
        JPanel header = new JPanel(new BorderLayout(8, 10));
        title.setText(tr("Administrar usuarios") + " · " + session.profile().type().displayName());
        title.setIcon(IconFactory.of(IconFactory.Kind.USER, 24)); title.setFont(title.getFont().deriveFont(Font.BOLD, 17)); header.add(title, BorderLayout.NORTH);
        explanation.setPreferredSize(new Dimension(820, 52)); header.add(explanation, BorderLayout.CENTER);
        JPanel filter = new JPanel(new BorderLayout(8, 0)); filter.add(new JLabel(tr("Buscar usuario:"), IconFactory.of(IconFactory.Kind.SEARCH, 16), JLabel.LEFT), BorderLayout.WEST); filter.add(search);
        header.add(filter, BorderLayout.SOUTH); root.add(header, BorderLayout.NORTH);
        users.setRowSorter(sorter); users.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); users.setRowHeight(29); users.setShowGrid(false);
        users.getSelectionModel().addListSelectionListener(e -> updateActions());
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); } public void removeUpdate(DocumentEvent e) { filter(); } public void changedUpdate(DocumentEvent e) { filter(); }
            private void filter() { sorter.setRowFilter(search.getText().isBlank() ? null : RowFilter.regexFilter("(?iu)" + java.util.regex.Pattern.quote(search.getText()))); }
        });
        root.add(new JScrollPane(users));
        JPanel bottom = new JPanel(new BorderLayout(8, 12)), actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        for (JButton b : List.of(create, password, grants, view, delete, refresh)) actions.add(b);
        bottom.add(actions, BorderLayout.NORTH);
        JPanel state = new JPanel(new BorderLayout(8, 0)); status.setIcon(IconFactory.of(IconFactory.Kind.INFO, 15)); state.add(status);
        progress.setIndeterminate(true); progress.setPreferredSize(new Dimension(130, 16)); progress.setVisible(false); state.add(progress, BorderLayout.EAST);
        bottom.add(state, BorderLayout.SOUTH); root.add(bottom, BorderLayout.SOUTH); updateActions(); return root;
    }
    private JButton button(String text, IconFactory.Kind icon, Runnable action) {
        JButton b = UiLanguage.button(text); b.setIcon(IconFactory.of(icon, 16)); b.addActionListener(e -> action.run()); return b;
    }
    private void updateActions() {
        boolean supported = admin != null && admin.supported(), writable = supported && !busy && !session.profile().readOnly();
        Account a = selected(); create.setEnabled(writable); grants.setEnabled(writable && a != null); delete.setEnabled(writable && a != null);
        password.setEnabled(writable && a != null && admin.canChangePassword(a)); view.setEnabled(supported && !busy && a != null); refresh.setEnabled(!busy);
    }
    private void loadUsers() {
        if (busy) return;
        Account previous = selected();
        run(tr("Leyendo usuarios…"), () -> {
            synchronized (session.connection()) {
                UserAdministration service = new UserAdministration(session.connection(), session.profile().type());
                return new Snapshot(service, service.accounts());
            }
        }, snapshot -> {
            admin = snapshot.admin(); accounts.clear(); accounts.addAll(snapshot.accounts()); model.setRowCount(0);
            title.setText(tr("Administrar usuarios") + " · " + admin.type().displayName());
            explanation.setText("<html>" + escape(admin.explanation()) + "<br>" + escape(session.profile().name()) + (session.profile().readOnly() ? " · " + tr("Solo lectura") : "") + "</html>");
            if (admin.usesHost()) model.setColumnIdentifiers(new Object[]{tr("Usuario"), "Host", "Plugin"});
            for (Account a : accounts) model.addRow(new Object[]{a.name(), a.scope(), a.detail()});
            if (previous != null) for (int i=0;i<accounts.size();i++) if (accounts.get(i).name().equals(previous.name()) && accounts.get(i).scope().equals(previous.scope())) { int v = users.convertRowIndexToView(i); if (v >= 0) users.setRowSelectionInterval(v,v); }
            status.setText(admin.supported() ? accounts.size() + tr(" cuentas cargadas") + " · " + tr("Selecciona una cuenta para ver sus acciones.") : admin.explanation()); updateActions();
        });
    }
    private void createUser() { editCredentials(null); }
    private void changePassword() { Account a = selected(); if (a != null) editCredentials(a); }
    private void editCredentials(Account existing) {
        if (busy || admin == null || !admin.supported()) return;
        JTextField name = new JTextField(existing == null ? "" : existing.name(), 28); name.setEditable(existing == null);
        JComboBox<String> host = new JComboBox<>(new String[]{"localhost", "127.0.0.1", "%"}); host.setEditable(true);
        if (existing != null) host.setSelectedItem(existing.scope()); host.setEnabled(existing == null);
        PasswordEditorPanel secret = new PasswordEditorPanel();
        JPanel form = new JPanel(new BorderLayout(8, 12)); form.setBorder(new EmptyBorder(8, 8, 8, 8));
        JLabel heading = new JLabel((existing == null ? tr("Nuevo usuario") : tr("Cambiar contraseña")) + " · " + admin.type().displayName(), IconFactory.of(IconFactory.Kind.USER, 22), JLabel.LEFT);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 16)); form.add(heading, BorderLayout.NORTH);
        JPanel fields = new JPanel(new GridBagLayout()); GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(5,0,5,8); gc.anchor = GridBagConstraints.WEST; gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx=0; gc.gridy=0; fields.add(new JLabel(tr("Usuario:")),gc); gc.gridx=1; gc.weightx=1; fields.add(name,gc);
        if (admin.usesHost()) { gc.gridy++; gc.gridx=0; gc.weightx=0; fields.add(new JLabel("Host:"),gc); gc.gridx=1; gc.weightx=1; fields.add(host,gc); UiLanguage.tooltip(host, UiLanguage.key("Host de acceso (localhost limita al equipo del servidor):")); }
        gc.gridy++; gc.gridx=0; gc.gridwidth=2; fields.add(secret,gc);
        gc.gridy++; gc.weighty=1; gc.fill=GridBagConstraints.BOTH; fields.add(Box.createGlue(),gc); form.add(fields);
        JTextArea help = new JTextArea(admin.explanation() + "\n" + tr("Crear una cuenta no le concede permisos de administración. Generar o copiar una clave no modifica el servidor.") + "\n" + tr("La contraseña no se guarda: cópiala antes de continuar."), 4, 62);
        help.setEditable(false); help.setOpaque(false); help.setLineWrap(true); help.setWrapStyleWord(true); form.add(help, BorderLayout.SOUTH);
        Object[] buttons = {existing == null ? tr("Crear usuario") : tr("Actualizar contraseña"), tr("Cancelar")};
        char[] passwordChars = null;
        try {
            while (true) {
                int answer = JOptionPane.showOptionDialog(this, form, heading.getText(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[0]);
                if (answer != 0) return;
                String user = name.getText().trim(), userHost = Objects.toString(host.getSelectedItem(), "").trim();
                String error = user.isBlank() || user.chars().anyMatch(Character::isISOControl) ? tr("Indica un nombre válido.") : admin.usesHost() && userHost.isBlank() ? tr("Indica el host de acceso.") : secret.validation();
                if (!error.isEmpty()) { JOptionPane.showMessageDialog(this, error, tr("Revisa los datos"), JOptionPane.WARNING_MESSAGE); continue; }
                passwordChars = secret.password.getPassword();
                Account account = existing == null ? admin.newAccount(user, userHost) : existing;
                char[] retained = passwordChars;
                boolean started = change((existing == null ? tr("Crear usuario") : tr("Cambiar contraseña")) + ": " + admin.display(account), true, () -> {
                    try { return List.of(existing == null ? admin.createSql(account, new String(retained)) : admin.passwordSql(account, new String(retained))); }
                    finally { Arrays.fill(retained, '\0'); }
                }, () -> Arrays.fill(retained, '\0'));
                if (started) { passwordChars = null; return; }
                Arrays.fill(passwordChars, '\0'); passwordChars = null;
            }
        } finally { secret.close(); if (passwordChars != null) Arrays.fill(passwordChars, '\0'); }
    }
    private void grantPrivileges() {
        Account a = selected(); if (a == null || busy) return;
        run(tr("Leyendo permisos…"), () -> { synchronized (session.connection()) { return admin.scopes(); } }, scopes -> {
            JComboBox<String> scope = new JComboBox<>(scopes.toArray(String[]::new));
            JPanel form = new JPanel(new BorderLayout(8, 12)), choices = new JPanel(new GridLayout(0, 2, 12, 8)); List<JCheckBox> checks = new ArrayList<>();
            for (String p : admin.privileges()) { JCheckBox check = new JCheckBox(p); checks.add(check); choices.add(check); }
            form.add(scope, BorderLayout.NORTH); form.add(choices); JTextArea help = new JTextArea(admin.explanation(), 3, 65); help.setEditable(false); help.setLineWrap(true); help.setWrapStyleWord(true); help.setOpaque(false);
            JCheckBox grantOption=UiLanguage.text(new JCheckBox(),UiLanguage.key("Permitir delegar permisos (GRANT OPTION)"));grantOption.setVisible(admin.usesHost());
            JPanel footer=new JPanel(new BorderLayout(8,8));footer.add(help);footer.add(grantOption,BorderLayout.SOUTH);form.add(footer,BorderLayout.SOUTH);
            if (JOptionPane.showConfirmDialog(this, form, tr("Asignar permisos a ") + admin.display(a), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            List<String> chosen = checks.stream().filter(JCheckBox::isSelected).map(JCheckBox::getText).toList();
            if (chosen.isEmpty() || scope.getSelectedItem() == null) { JOptionPane.showMessageDialog(this, tr("Selecciona al menos un permiso y un ámbito.")); return; }
            String target = scope.getSelectedItem().toString();
            boolean delegate=admin.usesHost() && grantOption.isSelected();
            change(tr("Asignar permisos a ") + admin.display(a) + "\n" + target + "\n" + String.join(", ", chosen) + (delegate ? "\nWITH GRANT OPTION" : ""), false,
                    () -> admin.grantSql(a, target, chosen).stream().map(sql->sql+(delegate ? " WITH GRANT OPTION" : "")).toList(), () -> {});
        });
    }
    private void showGrants() {
        Account a = selected(); if (a == null || busy) return;
        run(tr("Leyendo permisos…"), () -> { synchronized (session.connection()) { return admin.grants(a); } }, text -> {
            JTextArea area = new JTextArea(admin.explanation() + "\n\n" + text, 20, 90); area.setEditable(false); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JOptionPane.showMessageDialog(this, new JScrollPane(area), tr("Permisos de ") + admin.display(a), JOptionPane.INFORMATION_MESSAGE);
        });
    }
    private void deleteUser() {
        Account a = selected(); if (a == null) return;
        change(tr("Eliminar usuario") + ": " + admin.display(a) + "\n" + tr("Se eliminará la cuenta. No se eliminarán sus tablas ni objetos en cascada."), false, () -> List.of(admin.dropSql(a)), () -> {});
    }
    private boolean change(String detail, boolean sensitive, Callable<List<String>> statements, Runnable cleanup) {
        if (busy || session.profile().readOnly() || !LicenseGate.ensure(this)) return false;
        if (!Confirmations.ask(this, "Modificar usuarios o permisos", session.profile().name() + "\n" + detail + "\n" + tr("¿Aplicar esta operación de administración?"))) return false;
        run(tr("Aplicando cambios…"), () -> {
            try {
                synchronized (session.connection()) {
                    Connection c = session.connection();
                    if (ManagedConnection.hasActiveTransaction(c)) throw new SQLException(tr("Finaliza la transacción con Commit o Rollback y activa Auto-commit antes de administrar usuarios."));
                    boolean original = c.isReadOnly(); int applied = 0;
                    try {
                        c.setReadOnly(false);
                        for (String sql : statements.call()) { try (Statement s = c.createStatement()) { s.execute(sql); applied++; } }
                    } catch (SQLException error) {
                        if (sensitive) throw new SQLException(tr("El servidor rechazó el cambio. Comprueba permisos, nombre de cuenta y política de contraseñas.") + " SQLState=" + error.getSQLState() + " · " + error.getErrorCode());
                        throw new SQLException(tr("Sentencias aplicadas: ") + applied + ". " + tr("Revisa los permisos antes de reintentar.") + "\n" + error.getMessage());
                    } finally { c.setReadOnly(original); }
                }
                return true;
            } finally { cleanup.run(); }
        }, ignored -> { loadUsers(); JOptionPane.showMessageDialog(this, tr("Cambios aplicados correctamente."), tr("Administrar usuarios"), JOptionPane.INFORMATION_MESSAGE); });
        return true;
    }
    private <T> void run(String message, Callable<T> task, java.util.function.Consumer<T> done) {
        busy = true; updateActions(); progress.setVisible(true); status.setText(message);
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception { return task.call(); }
            protected void done() {
                busy = false; progress.setVisible(false); updateActions(); if (!isDisplayable()) return;
                try { done.accept(get()); }
                catch (Exception error) {
                    Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
                    status.setText(tr("Operación fallida")); JOptionPane.showMessageDialog(UserManagerDialog.this, Objects.toString(cause.getMessage(), tr("Operación fallida")) + "\n" + tr("La cuenta conectada debe tener permisos para administrar usuarios en este motor."), tr("Operación fallida"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
    private Account selected() { int row = users.getSelectedRow(); if (row < 0) return null; int index = users.convertRowIndexToModel(row); return index < accounts.size() ? accounts.get(index) : null; }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private record Snapshot(UserAdministration admin, List<Account> accounts) {}
}
