package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;
import com.nexodb.db.DriverDownloadService;
import com.nexodb.db.PostgresSupport;
import com.nexodb.model.AuthenticationMode;
import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import com.nexodb.util.SecretStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.io.File;
import java.util.Arrays;

public final class ConnectionDialog extends JDialog {
    public record Result(ConnectionProfile profile, char[] password, boolean connect, boolean rememberPassword) {}

    private enum TlsMode {
        AUTOMATIC("Automático (recomendado)"),
        REQUIRED("Requerir cifrado"),
        VERIFY_FULL("Verificar certificado y servidor"),
        DISABLED("Sin SSL/TLS (red confiable)" );

        private final String label;
        TlsMode(String label) { this.label = label; }
        @Override public String toString() { return tr(label); }
    }

    private final JTextField name = new JTextField(30);
    private final JComboBox<DatabaseType> type = new JComboBox<>(DatabaseType.values());
    private final JButton driverAction = UiLanguage.text(new JButton(tr("Verificar / descargar driver")), UiLanguage.key("Verificar / descargar driver"));
    private final JComboBox<AuthenticationMode> authentication = new JComboBox<>(AuthenticationMode.values());
    private final JComboBox<TlsMode> tlsMode = new JComboBox<>(TlsMode.values());
    private final JTextField host = new JTextField(30);
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JTextField database = new JTextField(30);
    private final JTextField username = new JTextField(30);
    private final JPasswordField password = new JPasswordField(30);
    private final JCheckBox rememberPassword = UiLanguage.text(new JCheckBox(tr("Recordar de forma segura con Windows")), UiLanguage.key("Recordar de forma segura con Windows"));
    private final JLabel headingIcon = new JLabel();
    private final JTextField jdbcUrl = new JTextField(30);
    private final JTextField driverClass = new JTextField(30);
    private final JTextField driverJar = new JTextField(30);
    private final JToggleButton advancedToggle = UiLanguage.text(new JToggleButton(tr("Configuración avanzada   >")), UiLanguage.key("Configuración avanzada   >"));
    private final JPanel advancedPanel = new JPanel(new GridBagLayout());
    private final JCheckBox readOnly = UiLanguage.text(new JCheckBox(tr("Solo lectura (recomendado para producción)")), UiLanguage.key("Solo lectura (recomendado para producción)"));
    private final JLabel status = new JLabel(" ");
    private final JProgressBar driverProgress = new JProgressBar(0, 100);
    private final JLabel authenticationNote = new JLabel();
    private final String profileId;
    private final SecretStore secretStore;
    private final boolean hadStoredPassword;
    private Result result;
    private boolean internalChange;

    public ConnectionDialog(Window owner, ConnectionProfile profile) {
        this(owner, profile, new SecretStore());
    }

    public ConnectionDialog(Window owner, ConnectionProfile profile, SecretStore secretStore) {
        super(owner, profile == null ? tr("Nueva conexión") : tr("Editar conexión"), ModalityType.APPLICATION_MODAL);
        UiLanguage.title(this, UiLanguage.key(profile == null ? "Nueva conexión" : "Editar conexión"));
        this.profileId = profile == null ? "" : profile.id();
        this.secretStore = secretStore;
        this.hadStoredPassword = profile != null && secretStore.has(profile.id());
        JSpinner.NumberEditor portEditor = new JSpinner.NumberEditor(port, "0");
        portEditor.getFormat().setGroupingUsed(false);
        port.setEditor(portEditor);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(690, 470));
        setContentPane(buildContent());
        bindActions();
        if (profile == null) {
            type.setSelectedItem(DatabaseType.POSTGRESQL);
            applyPreset(true);
            name.setText("PostgreSQL local");
        } else {
            populate(profile);
        }
        rememberPassword.setSelected(hadStoredPassword);
        UiLanguage.tooltip(rememberPassword, UiLanguage.key("Se cifra con DPAPI para el usuario actual de Windows; nunca se guarda en el perfil."));
        updateAuthenticationUi();
        pack();
        setLocationRelativeTo(owner);
    }

    public Result showDialog() {
        setVisible(true);
        return result;
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(18, 20, 16, 20));

        JPanel heading = new JPanel(new BorderLayout(12, 0));
        headingIcon.setIcon(IconFactory.database((DatabaseType) type.getSelectedItem(), 36));
        JLabel headingText = UiLanguage.text(new JLabel(tr("<html><b style='font-size:14px'>Configura tu conexión</b><br>Completa los datos básicos; NexoDB se encarga del controlador.</html>")), UiLanguage.key("<html><b style='font-size:14px'>Configura tu conexión</b><br>Completa los datos básicos; NexoDB se encarga del controlador.</html>"));
        headingText.setForeground(UIManager.getColor("Label.foreground"));
        heading.add(headingIcon, BorderLayout.WEST);
        heading.add(headingText, BorderLayout.CENTER);
        heading.setBorder(new EmptyBorder(0, 4, 8, 4));
        root.add(heading, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(5, 5, 5, 5);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 0;
        gc.gridx = 0;
        gc.gridy = 0;

        addRow(form, gc, tr("Nombre"), name);
        type.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.setIcon(IconFactory.database(value instanceof DatabaseType engine ? engine : DatabaseType.GENERIC, 18));
                label.setIconTextGap(8);
                return label;
            }
        });
        JPanel enginePanel = new JPanel(new BorderLayout(6, 0));
        enginePanel.add(type, BorderLayout.CENTER);
        driverAction.setIcon(IconFactory.of(IconFactory.Kind.REFRESH, 15));
        UiLanguage.tooltip(driverAction, UiLanguage.key("Verifica el controlador del motor y lo descarga si no está instalado"));
        driverAction.addActionListener(e -> verifyOrDownloadDriver());
        enginePanel.add(driverAction, BorderLayout.EAST);
        addRow(form, gc, tr("Motor"), enginePanel);
        addRow(form, gc, tr("Autenticación"), authentication);
        addRow(form, gc, "SSL / TLS", tlsMode);
        addRow(form, gc, "Host", host);
        addRow(form, gc, tr("Puerto"), port);
        addRow(form, gc, tr("Base / servicio / archivo"), database);
        addRow(form, gc, tr("Usuario"), username);
        addRow(form, gc, tr("Contraseña"), password);
        addRow(form, gc, tr("Seguridad"), rememberPassword);

        advancedToggle.setHorizontalAlignment(SwingConstants.LEFT);
        advancedToggle.setBorderPainted(false);
        advancedToggle.setContentAreaFilled(false);
        advancedToggle.setFocusPainted(false);
        advancedToggle.setFont(advancedToggle.getFont().deriveFont(Font.BOLD));
        advancedToggle.setIcon(IconFactory.of(IconFactory.Kind.SETTINGS, 16));
        advancedToggle.setIconTextGap(7);
        advancedToggle.addActionListener(e -> setAdvancedExpanded(advancedToggle.isSelected()));
        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.weightx = 1;
        form.add(advancedToggle, gc);
        gc.gridy++;

        GridBagConstraints advancedGc = new GridBagConstraints();
        advancedGc.insets = new Insets(4, 5, 4, 5);
        advancedGc.anchor = GridBagConstraints.WEST;
        advancedGc.fill = GridBagConstraints.HORIZONTAL;
        advancedGc.gridx = 0;
        advancedGc.gridy = 0;

        JPanel urlPanel = new JPanel(new BorderLayout(6, 0));
        urlPanel.add(jdbcUrl, BorderLayout.CENTER);
        JButton generate = UiLanguage.text(new JButton(tr("Generar")), UiLanguage.key("Generar"));
        generate.setIcon(IconFactory.of(IconFactory.Kind.REFRESH, 15));
        generate.addActionListener(e -> generateUrl());
        urlPanel.add(generate, BorderLayout.EAST);
        addRow(advancedPanel, advancedGc, "URL JDBC", urlPanel);
        addRow(advancedPanel, advancedGc, tr("Clase del controlador"), driverClass);

        JPanel jarPanel = new JPanel(new BorderLayout(6, 0));
        jarPanel.add(driverJar, BorderLayout.CENTER);
        JButton browse = UiLanguage.text(new JButton(tr("Examinar…")), UiLanguage.key("Examinar…"));
        browse.setIcon(IconFactory.of(IconFactory.Kind.FOLDER, 15));
        browse.addActionListener(e -> browseJar());
        jarPanel.add(browse, BorderLayout.EAST);
        addRow(advancedPanel, advancedGc, tr("Archivo del controlador"), jarPanel);
        advancedPanel.setBorder(BorderFactory.createEmptyBorder(0, 16, 4, 0));
        advancedPanel.setVisible(false);
        form.add(advancedPanel, gc);
        gc.gridy++;
        gc.gridwidth = 1;

        gc.gridx = 1;
        gc.weightx = 1;
        form.add(readOnly, gc);

        authenticationNote.setForeground(UIManager.getColor("Label.foreground"));
        gc.gridy++;
        form.add(authenticationNote, gc);

        root.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 8));
        status.setForeground(UIManager.getColor("Label.foreground"));
        JPanel activity = new JPanel(new BorderLayout(8, 0));
        activity.add(status, BorderLayout.CENTER);
        driverProgress.setStringPainted(true);
        driverProgress.setPreferredSize(new Dimension(190, 18));
        driverProgress.setVisible(false);
        activity.add(driverProgress, BorderLayout.EAST);
        footer.add(activity, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton test = UiLanguage.text(new JButton(tr("Probar")), UiLanguage.key("Probar"));
        JButton save = UiLanguage.text(new JButton(tr("Guardar")), UiLanguage.key("Guardar"));
        JButton connect = UiLanguage.text(new JButton(tr("Guardar y conectar")), UiLanguage.key("Guardar y conectar"));
        JButton cancel = UiLanguage.text(new JButton(tr("Cancelar")), UiLanguage.key("Cancelar"));
        test.setIcon(IconFactory.of(IconFactory.Kind.TEST, 16));
        save.setIcon(IconFactory.of(IconFactory.Kind.SAVE, 16));
        connect.setIcon(IconFactory.of(IconFactory.Kind.CONNECT, 16));
        cancel.setIcon(IconFactory.of(IconFactory.Kind.CANCEL, 16));
        connect.setBackground(IconFactory.BLUE);
        connect.setForeground(Color.WHITE);
        connect.setFont(connect.getFont().deriveFont(Font.BOLD));
        actions.add(test);
        actions.add(save);
        actions.add(connect);
        actions.add(cancel);
        footer.add(actions, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);

        test.addActionListener(e -> testConnection(test));
        save.addActionListener(e -> accept(false));
        connect.addActionListener(e -> accept(true));
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(connect);
        return root;
    }

    private void addRow(JPanel panel, GridBagConstraints gc, String label, Component component) {
        gc.gridx = 0;
        gc.weightx = 0;
        panel.add(UiLanguage.text(new JLabel(), UiLanguage.caption(label), ":"), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        panel.add(component, gc);
        gc.gridy++;
    }

    private void setAdvancedExpanded(boolean expanded) {
        advancedToggle.setSelected(expanded);
        advancedToggle.setText(expanded ? tr("Configuración avanzada   v") : tr("Configuración avanzada   >"));
        advancedPanel.setVisible(expanded);
        Point location = getLocation();
        pack();
        setLocation(location);
    }

    private void bindActions() {
        type.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && !internalChange) {
                applyPreset(false);
                updateAuthenticationUi();
                updateTlsUi();
            }
        });
        authentication.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) updateAuthenticationUi();
        });
        rememberPassword.addActionListener(e -> updateAuthenticationUi());
        tlsMode.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && !internalChange) generateUrl();
        });
        DocumentListener addressListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshGeneratedUrl(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshGeneratedUrl(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshGeneratedUrl(); }
        };
        host.getDocument().addDocumentListener(addressListener);
        database.getDocument().addDocumentListener(addressListener);
        password.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateAuthenticationUi(); }
            @Override public void removeUpdate(DocumentEvent e) { updateAuthenticationUi(); }
            @Override public void changedUpdate(DocumentEvent e) { updateAuthenticationUi(); }
        });
        port.addChangeListener(e -> refreshGeneratedUrl());
    }

    private void verifyOrDownloadDriver() {
        DatabaseType engine = (DatabaseType) type.getSelectedItem();
        if (engine == null) return;
        driverAction.setEnabled(false);
        driverProgress.setVisible(true);
        driverProgress.setIndeterminate(true);
        driverProgress.setString(tr("Verificando…"));
        status.setForeground(UIManager.getColor("Label.foreground"));
        UiLanguage.text(status, UiLanguage.key("Verificando el controlador de "), engine.displayName(), "…");
        ConnectionProfile profile = profileFromForm();

        new SwingWorker<JdbcSession.DriverCheck, Void>() {
            private boolean downloaded;

            @Override protected JdbcSession.DriverCheck doInBackground() throws Exception {
                JdbcSession.DriverCheck check = JdbcSession.checkDriver(profile);
                if (check.available()) return check;
                if (!DriverDownloadService.supports(engine)) {
                    throw new IllegalStateException(engine == DatabaseType.GENERIC
                            ? tr("Para JDBC genérico selecciona el archivo .jar en Configuración avanzada.")
                            : check.detail());
                }
                downloaded = true;
                DriverDownloadService.ensureDownloaded(engine, value -> SwingUtilities.invokeLater(() -> {
                    driverProgress.setIndeterminate(false);
                    driverProgress.setValue(value);
                    driverProgress.setString(value + "%");
                    UiLanguage.text(status, UiLanguage.key("Descargando el controlador oficial de "), engine.displayName(), "…");
                }));
                JdbcSession.DriverCheck installed = JdbcSession.checkDriver(profile);
                if (!installed.available()) throw new IllegalStateException(installed.detail());
                return installed;
            }

            @Override protected void done() {
                driverAction.setEnabled(true);
                driverProgress.setVisible(false);
                driverProgress.setIndeterminate(false);
                try {
                    JdbcSession.DriverCheck check = get();
                    status.setForeground(UIManager.getColor("Label.foreground"));
                    UiLanguage.text(status, UiLanguage.key("Controlador de "), engine.displayName(), UiLanguage.key(" listo."));
                    String location = check.file() == null ? tr("Incluido dentro de NexoDB Studio") : check.file().getAbsolutePath();
                    JOptionPane.showMessageDialog(ConnectionDialog.this,
                            (downloaded ? tr("El controlador fue descargado, verificado e instalado correctamente.")
                                    : tr("El controlador ya estaba instalado y funciona correctamente."))
                                    + tr("\n\nClase: ") + profile.driverClass() + tr("\nUbicación: ") + location,
                            tr("Controlador listo"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception error) {
                    status.setForeground(UIManager.getColor("Label.foreground"));
                    UiLanguage.text(status, UiLanguage.key("No se pudo preparar el controlador."));
                    showDriverError(error);
                }
            }
        }.execute();
    }

    private void refreshGeneratedUrl() {
        if (!internalChange && type.getSelectedItem() != DatabaseType.GENERIC) generateUrl();
    }

    private void applyPreset(boolean force) {
        DatabaseType selected = (DatabaseType) type.getSelectedItem();
        if (selected == null) return;
        headingIcon.setIcon(IconFactory.database(selected, 36));
        internalChange = true;
        try {
            tlsMode.setSelectedItem(TlsMode.AUTOMATIC);
            port.setValue(selected.defaultPort());
            driverClass.setText(selected.driverClass());
            if (force || host.getText().isBlank()) host.setText(selected == DatabaseType.SQLITE || selected == DatabaseType.H2 ? "" : "localhost");
            if (force && database.getText().isBlank()) database.setText(selected == DatabaseType.SQLITE ? "datos.db" : "");
            generateUrl();
            if (selected == DatabaseType.GENERIC) setAdvancedExpanded(true);
        } finally {
            internalChange = false;
        }
    }

    private void generateUrl() {
        DatabaseType selected = (DatabaseType) type.getSelectedItem();
        if (selected == null) return;
        String url = selected.buildUrl(host.getText(), ((Number) port.getValue()).intValue(), database.getText());
        TlsMode mode = (TlsMode) tlsMode.getSelectedItem();
        if (mode == null) mode = TlsMode.AUTOMATIC;
        if (selected == DatabaseType.MARIADB && mode != TlsMode.AUTOMATIC) {
            url = url.replace("?sslMode=disable", "");
        }
        url = switch (selected) {
            case POSTGRESQL -> switch (mode) {
                case AUTOMATIC -> url;
                case DISABLED -> addQuery(url, "sslmode=disable");
                case REQUIRED -> addQuery(url, "sslmode=require");
                case VERIFY_FULL -> addQuery(url, "sslmode=verify-full");
            };
            case MYSQL -> switch (mode) {
                case AUTOMATIC -> url;
                case DISABLED -> addQuery(url, "sslMode=DISABLED");
                case REQUIRED -> addQuery(url, "sslMode=REQUIRED");
                case VERIFY_FULL -> addQuery(url, "sslMode=VERIFY_IDENTITY");
            };
            case MARIADB -> switch (mode) {
                case AUTOMATIC -> url;
                case DISABLED -> addQuery(url, "sslMode=disable");
                case REQUIRED -> addQuery(url, "sslMode=trust");
                case VERIFY_FULL -> addQuery(url, "sslMode=verify-full");
            };
            case SQLSERVER -> switch (mode) {
                case AUTOMATIC, REQUIRED -> url;
                case DISABLED -> url.replace("encrypt=true", "encrypt=false");
                case VERIFY_FULL -> url.replace("trustServerCertificate=true", "trustServerCertificate=false");
            };
            case MONGODB -> switch (mode) {
                case AUTOMATIC -> url;
                case DISABLED -> addQuery(url, "ssl=false");
                case REQUIRED, VERIFY_FULL -> addQuery(url, "ssl=true");
            };
            default -> url;
        };
        jdbcUrl.setText(url);
    }

    private static String addQuery(String url, String option) {
        return url + (url.contains("?") ? "&" : "?") + option;
    }

    private void browseJar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(tr("Selecciona el controlador JDBC (.jar)"));
        if (!driverJar.getText().isBlank()) chooser.setSelectedFile(new File(driverJar.getText()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) driverJar.setText(chooser.getSelectedFile().getAbsolutePath());
    }

    private void populate(ConnectionProfile profile) {
        internalChange = true;
        try {
            name.setText(profile.name());
            type.setSelectedItem(profile.type());
            authentication.setSelectedItem(profile.authenticationMode());
            host.setText(profile.host());
            port.setValue(profile.port());
            database.setText(profile.database());
            username.setText(profile.username());
            tlsMode.setSelectedItem(detectTlsMode(profile.jdbcUrl()));
            jdbcUrl.setText(profile.jdbcUrl());
            driverClass.setText(profile.driverClass());
            driverJar.setText(profile.driverJar());
            readOnly.setSelected(profile.readOnly());
        } finally {
            internalChange = false;
        }
        updateAuthenticationUi();
        updateTlsUi();
    }

    private static TlsMode detectTlsMode(String url) {
        String value = url == null ? "" : url.toLowerCase();
        if (value.contains("sslmode=disable") || value.contains("sslmode=disabled")
                || value.contains("ssl=false") || value.contains("encrypt=false")) return TlsMode.DISABLED;
        if (value.contains("verify-full") || value.contains("verify_identity")
                || value.contains("trustservercertificate=false")) return TlsMode.VERIFY_FULL;
        if (value.contains("sslmode=require") || value.contains("sslmode=trust") || value.contains("ssl=true")) return TlsMode.REQUIRED;
        return TlsMode.AUTOMATIC;
    }

    private ConnectionProfile profileFromForm() {
        return new ConnectionProfile(profileId, name.getText(), (DatabaseType) type.getSelectedItem(), host.getText(),
                ((Number) port.getValue()).intValue(), database.getText(), username.getText(), jdbcUrl.getText(),
                driverClass.getText(), driverJar.getText(), (AuthenticationMode) authentication.getSelectedItem(), readOnly.isSelected());
    }

    private boolean validateForm() {
        if (name.getText().isBlank()) return fail(tr("Escribe un nombre para la conexión."), name);
        if (username.getText().isBlank()) return fail(tr("Escribe el usuario de la base de datos. La contraseña sí puede quedar vacía."), username);
        if (type.getSelectedItem() == DatabaseType.MONGODB && database.getText().isBlank()) {
            return fail(tr("Escribe la base de datos publicada en MongoDB Atlas SQL Interface."), database);
        }
        if (authentication.getSelectedItem() == AuthenticationMode.WINDOWS
                && type.getSelectedItem() != DatabaseType.MYSQL
                && type.getSelectedItem() != DatabaseType.MARIADB) {
            return fail(tr("La autenticación de Windows está disponible actualmente para MySQL/MariaDB."), authentication);
        }
        if (jdbcUrl.getText().isBlank() || !jdbcUrl.getText().startsWith("jdbc:")) return fail(tr("La URL debe comenzar con jdbc:."), jdbcUrl);
        if (!driverJar.getText().isBlank() && !new File(driverJar.getText()).isFile()) return fail(tr("No se encontró el archivo del controlador."), driverJar);
        if (!driverJar.getText().isBlank() && driverClass.getText().isBlank()) return fail(tr("Indica la clase del controlador."), driverClass);
        return true;
    }

    private void updateAuthenticationUi() {
        DatabaseType engine = (DatabaseType) type.getSelectedItem();
        boolean supported = SecretStore.isSupported() && (engine == DatabaseType.MYSQL || engine == DatabaseType.MARIADB);
        if (!supported && authentication.getSelectedItem() == AuthenticationMode.WINDOWS) {
            authentication.setSelectedItem(AuthenticationMode.DATABASE);
        }
        authentication.setEnabled(supported);
        boolean windows = authentication.getSelectedItem() == AuthenticationMode.WINDOWS;
        password.setEnabled(!windows);
        rememberPassword.setEnabled(!windows && SecretStore.isSupported());
        if (!SecretStore.isSupported()) {
            rememberPassword.setSelected(false);
            UiLanguage.text(rememberPassword, UiLanguage.key("Guardar contraseña no disponible en este sistema"));
            UiLanguage.tooltip(rememberPassword, UiLanguage.key("La contraseña se solicita al conectar y no se guarda en disco."));
        }
        password.setToolTipText(windows
                ? tr("En este modo se usa la sesión actual de Windows y no se envía contraseña.")
                : tr("Puede quedar vacía si la cuenta de la base de datos no tiene contraseña."));
        if (engine == DatabaseType.MONGODB) {
            UiLanguage.text(authenticationNote, UiLanguage.key("<html>MongoDB usa aquí su Atlas SQL Interface compatible con JDBC.<br>"), UiLanguage.key("Para MongoDB Community nativo se incorporará un explorador de documentos independiente.</html>"));
        } else if (!SecretStore.isSupported()) {
            UiLanguage.text(authenticationNote, UiLanguage.key("<html>El usuario es obligatorio; la contraseña puede quedar vacía.<br>"), UiLanguage.key("La contraseña se solicita al conectar y no se guarda en disco."), "</html>");
        } else UiLanguage.text(authenticationNote, (windows
                ? tr("<html>El usuario identifica la cuenta MariaDB (por ejemplo, root).<br>") + tr("La contraseña no se envía: se usa explícitamente la sesión actual de Windows mediante GSSAPI/SSPI.</html>")
                : hadStoredPassword && rememberPassword.isSelected() && password.getDocument().getLength() == 0
                    ? tr("<html>La contraseña guardada está protegida por Windows y no se muestra.<br>") + tr("Déjala vacía para conservarla, o escribe otra para reemplazarla.</html>")
                    : tr("<html>El usuario es obligatorio; la contraseña puede quedar vacía.<br>")), UiLanguage.key("Si eliges recordarla se cifra con Windows y no se guarda en el perfil.</html>"));
    }

    private void updateTlsUi() {
        DatabaseType engine = (DatabaseType) type.getSelectedItem();
        database.setToolTipText(engine == DatabaseType.POSTGRESQL
                ? tr("Déjala vacía para consultar las bases disponibles para este usuario.")
                : tr("Base de datos, servicio o archivo al que deseas conectarte"));
        boolean supported = engine == DatabaseType.POSTGRESQL || engine == DatabaseType.MYSQL
                || engine == DatabaseType.MARIADB || engine == DatabaseType.SQLSERVER
                || engine == DatabaseType.MONGODB;
        tlsMode.setEnabled(supported);
        tlsMode.setToolTipText(supported
                ? tr("Elige cómo debe protegerse la conexión con el servidor")
                : tr("Este motor administra la seguridad desde su propia URL o archivo local"));
    }

    private boolean fail(String message, JComponent component) {
        JOptionPane.showMessageDialog(this, message, tr("Revisa la conexión"), JOptionPane.WARNING_MESSAGE);
        component.requestFocusInWindow();
        return false;
    }

    private void accept(boolean connect) {
        if (!validateForm()) return;
        result = new Result(profileFromForm(), password.getPassword(), connect,
                SecretStore.isSupported() && password.isEnabled() && rememberPassword.isSelected());
        dispose();
    }

    private void testConnection(JButton button) {
        if (!validateForm()) return;
        button.setEnabled(false);
        UiLanguage.text(status, UiLanguage.key("Probando conexión…"));
        ConnectionProfile profile = profileFromForm();
        char[] secret = password.getPassword();
        if (secret.length == 0 && hadStoredPassword && rememberPassword.isSelected()) {
            try { secret = secretStore.load(profileId); }
            catch (Exception error) {
                button.setEnabled(true);
                status.setForeground(UIManager.getColor("Label.foreground"));
                UiLanguage.text(status, UiLanguage.key("Windows no pudo recuperar la contraseña protegida."));
                return;
            }
        }
        char[] testSecret = secret == null ? new char[0] : secret;
        new SwingWorker<String, Void>() {
            private java.util.List<String> availableDatabases;
            @Override protected String doInBackground() throws Exception {
                try (JdbcSession session = JdbcSession.open(profile, testSecret, value -> SwingUtilities.invokeLater(() -> {
                    driverProgress.setVisible(true);
                    driverProgress.setValue(value);
                    driverProgress.setString(value < 100 ? tr("Descargando adaptador ") + value + "%" : tr("Adaptador verificado"));
                    status.setText(value < 100 ? tr("Descargando el controlador oficial de ") + profile.type().displayName() + "…" : tr("Probando conexión…"));
                }))) {
                    String product = session.connection().getMetaData().getDatabaseProductName();
                    String version = session.connection().getMetaData().getDatabaseProductVersion();
                    if (PostgresSupport.needsDatabaseSelection(profile)) {
                        availableDatabases = PostgresSupport.databases(session.connection());
                    }
                    return product + " " + version;
                } finally {
                    Arrays.fill(testSecret, '\0');
                }
            }

            @Override protected void done() {
                button.setEnabled(true);
                driverProgress.setVisible(false);
                try {
                    status.setForeground(UIManager.getColor("Label.foreground"));
                    UiLanguage.text(status, UiLanguage.key("Conexión correcta: "), get());
                    if (availableDatabases != null && isDisplayable() && profile.equals(profileFromForm())) {
                        String selected = DatabaseSelectionDialog.choose(ConnectionDialog.this,
                                profile.username(), availableDatabases);
                        if (selected != null) database.setText(selected);
                    }
                } catch (Exception ex) {
                    status.setForeground(UIManager.getColor("Label.foreground"));
                    UiLanguage.text(status, UiLanguage.key("No se pudo conectar."));
                    showConnectionError(ex);
                }
            }
        }.execute();
    }

    private void showDriverError(Exception exception) {
        String message = null;
        boolean handshake = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String candidate = cause.getMessage();
            if (candidate == null || candidate.isBlank()) continue;
            if (candidate.contains("No se pudo descargar el controlador")) message = candidate;
            if (candidate.toLowerCase().contains("handshake_failure")) handshake = true;
            if (message == null) message = candidate;
        }
        if (message == null || message.isBlank()) message = exception.toString();
        if (handshake && !message.contains("No se pudo descargar el controlador")) {
            message = tr("No se pudo descargar el controlador mediante una conexión HTTPS segura. Revisa el proxy o los certificados de tu red.");
        }
        JOptionPane.showMessageDialog(this, message, tr("Controlador JDBC"), JOptionPane.ERROR_MESSAGE);
    }

    private void showConnectionError(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        if (message != null && message.toLowerCase().contains("handshake_failure")) {
            Object[] options = {tr("Usar sin SSL/TLS"), tr("Cancelar")};
            int answer = JOptionPane.showOptionDialog(this,
                    tr("El controlador JDBC está disponible, pero el servidor rechazó la negociación SSL/TLS.\n\n")
                            + tr("Puedes revisar el certificado del servidor o reintentar sin cifrado únicamente si es una red confiable."),
                    tr("El servidor rechazó SSL/TLS"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[1]);
            if (answer == 0) {
                tlsMode.setSelectedItem(TlsMode.DISABLED);
                generateUrl();
                status.setForeground(UIManager.getColor("Label.foreground"));
                UiLanguage.text(status, UiLanguage.key("Modo sin SSL/TLS seleccionado. Pulsa Probar para reintentar."));
            }
            return;
        }
        JOptionPane.showMessageDialog(this, message, tr("Error de conexión"), JOptionPane.ERROR_MESSAGE);
    }
}

