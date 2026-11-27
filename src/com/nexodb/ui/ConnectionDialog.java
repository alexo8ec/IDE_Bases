package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;
import com.nexodb.db.DriverDownloadService;
import com.nexodb.db.PostgresSupport;
import com.nexodb.db.SQLiteSupport;
import com.nexodb.model.AuthenticationMode;
import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import com.nexodb.model.SshConfig;
import com.nexodb.util.SecretStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.im.InputContext;
import java.io.File;
import java.util.Arrays;

public final class ConnectionDialog extends JDialog {
    public record Result(ConnectionProfile profile,char[] password,char[] sshSecret,boolean connect,boolean rememberPassword,boolean rememberSshSecret) {}
    private record ConnectionCheck(String server, String database, long elapsedMillis) {}

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
    private final JPanel databasePanel = new JPanel(new BorderLayout(6, 0));
    private final JButton browseDatabase = UiLanguage.button("Elegir archivo…");
    private final java.util.Map<Component, JLabel> rowLabels = new java.util.IdentityHashMap<>();
    private final JTextField username = new JTextField(30);
    private final JPasswordField password = new JPasswordField(30);
    private final JCheckBox rememberPassword = UiLanguage.text(new JCheckBox(tr("Recordar en el almacén seguro del sistema")), UiLanguage.key("Recordar en el almacén seguro del sistema"));
    private final JLabel headingIcon = new JLabel();
    private final JTextField jdbcUrl = new JTextField(30);
    private final JTextField driverClass = new JTextField(30);
    private final JTextField driverJar = new JTextField(30);
    private final JToggleButton advancedToggle = UiLanguage.text(new JToggleButton(tr("Configuración avanzada   >")), UiLanguage.key("Configuración avanzada   >"));
    private final JPanel advancedPanel = new JPanel(new GridBagLayout());
    private final JTabbedPane connectionTabs=new JTabbedPane();
    private final JCheckBox readOnly = UiLanguage.text(new JCheckBox(tr("Solo lectura (recomendado para producción)")), UiLanguage.key("Solo lectura (recomendado para producción)"));
    private final JCheckBox sshEnabled=new JCheckBox("Usar túnel SSH");
    private final JTextField sshHost=new JTextField(28),sshUsername=new JTextField(28),sshPrivateKey=new JTextField(28),sshKnownHosts=new JTextField(28);
    private final JSpinner sshPort=new JSpinner(new SpinnerNumberModel(22,1,65535,1)),sshLocalPort=new JSpinner(new SpinnerNumberModel(0,0,65535,1)),sshTimeout=new JSpinner(new SpinnerNumberModel(10,1,120,1)),sshKeepAlive=new JSpinner(new SpinnerNumberModel(30,0,3600,1));
    private final JComboBox<SshConfig.Authentication> sshAuthentication=new JComboBox<>(SshConfig.Authentication.values());
    private final JPasswordField sshPassword=new JPasswordField(28);
    private final JCheckBox rememberSshPassword=new JCheckBox("Recordar contraseña/frase SSH de forma segura");
    private final JCheckBox sshStrictHostKey=new JCheckBox("Verificar identidad con known_hosts");
    private final JLabel sshSecurityNote=new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JProgressBar driverProgress = new JProgressBar(0, 100);
    private final JLabel authenticationNote = new JLabel();
    private final String profileId;
    private final SecretStore secretStore;
    private final boolean hadStoredPassword;
    private final boolean hadStoredSshPassword;
    private Result result;
    private boolean internalChange;
    private boolean nameProgrammaticChange;
    private boolean nameManuallyEdited;
    private DatabaseType currentEngine;
    private final java.util.Map<DatabaseType, EngineDraft> engineDrafts = new java.util.EnumMap<>(DatabaseType.class);
    private record EngineDraft(String host, int port, String database, String user, String url, String driverClass, String driverJar, TlsMode tls, AuthenticationMode authentication, boolean readOnly, boolean advanced) {}

    public ConnectionDialog(Window owner, ConnectionProfile profile) {
        this(owner, profile, new SecretStore());
    }

    public ConnectionDialog(Window owner, ConnectionProfile profile, SecretStore secretStore) {
        super(owner, profile == null ? tr("Nueva conexión") : tr("Editar conexión"), ModalityType.APPLICATION_MODAL);
        UiLanguage.title(this, UiLanguage.key(profile == null ? "Nueva conexión" : "Editar conexión"));
        this.profileId = profile == null ? "" : profile.id();
        this.secretStore = secretStore;
        this.hadStoredPassword = profile != null && secretStore.has(profile.id());
        this.hadStoredSshPassword=profile!=null&&secretStore.has(SshConfig.secretId(profile.id()));
        JSpinner.NumberEditor portEditor = new JSpinner.NumberEditor(port, "0");
        portEditor.getFormat().setGroupingUsed(false);
        port.setEditor(portEditor);
        for(JSpinner spinner:new JSpinner[]{sshPort,sshLocalPort,sshTimeout,sshKeepAlive}){JSpinner.NumberEditor editor=new JSpinner.NumberEditor(spinner,"0");editor.getFormat().setGroupingUsed(false);spinner.setEditor(editor);}
        sshKnownHosts.setText(SshConfig.defaultKnownHosts());sshStrictHostKey.setSelected(!sshKnownHosts.getText().isBlank());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(690, 470));
        setContentPane(buildContent());
        bindActions();
        if (profile == null) {
            type.setSelectedItem(DatabaseType.POSTGRESQL);
            applyPreset(true);
            setSuggestedName("PostgreSQL local");
        } else {
            populate(profile);
            nameManuallyEdited = true;
        }
        rememberPassword.setSelected(hadStoredPassword);
        rememberSshPassword.setSelected(hadStoredSshPassword);
        UiLanguage.tooltip(rememberPassword, UiLanguage.key("Se protege con "), SecretStore.backendName(), UiLanguage.key("; nunca se guarda en el perfil."));
        updateAuthenticationUi();
        currentEngine = (DatabaseType) type.getSelectedItem();
        pack();
        setLocationRelativeTo(owner);
    }

    public Result showDialog() {
        setVisible(true);
        return result;
    }

    @Override public void dispose(){char[] databaseSecret=password.getPassword(),tunnelSecret=sshPassword.getPassword();Arrays.fill(databaseSecret,'\0');Arrays.fill(tunnelSecret,'\0');password.setText("");sshPassword.setText("");super.dispose();}

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(18, 20, 16, 20));

        JPanel heading = new JPanel(new BorderLayout(12, 0));
        headingIcon.setIcon(IconFactory.database((DatabaseType) type.getSelectedItem(), 36));
        JLabel headingText = UiLanguage.text(new JLabel(tr("<html><b style='font-size:14px'>Configura tu conexión</b><br>Completa los datos básicos; AdisDB se encarga del controlador.</html>")), UiLanguage.key("<html><b style='font-size:14px'>Configura tu conexión</b><br>Completa los datos básicos; AdisDB se encarga del controlador.</html>"));
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
        databasePanel.add(database, BorderLayout.CENTER);
        browseDatabase.setIcon(IconFactory.of(IconFactory.Kind.FOLDER, 16));
        browseDatabase.addActionListener(e -> browseDatabase());
        databasePanel.add(browseDatabase, BorderLayout.EAST);
        addRow(form, gc, tr("Base / servicio / archivo"), databasePanel);
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

        connectionTabs.addTab(tr("Base de datos"),form);JScrollPane sshScroll=new JScrollPane(sshPanel());sshScroll.setBorder(null);sshScroll.getVerticalScrollBar().setUnitIncrement(16);sshScroll.setPreferredSize(new Dimension(650,420));connectionTabs.addTab("SSH",sshScroll);
        root.add(connectionTabs, BorderLayout.CENTER);

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

    private JComponent sshPanel(){
        JPanel panel=new JPanel(new GridBagLayout());panel.setBorder(new EmptyBorder(10,8,10,8));GridBagConstraints gc=new GridBagConstraints();gc.insets=new Insets(5,5,5,5);gc.anchor=GridBagConstraints.WEST;gc.fill=GridBagConstraints.HORIZONTAL;gc.gridx=0;gc.gridy=0;
        gc.gridwidth=2;gc.weightx=1;panel.add(sshEnabled,gc);gc.gridy++;gc.gridwidth=1;
        addRow(panel,gc,"Host SSH",sshHost);addRow(panel,gc,"Puerto SSH",sshPort);addRow(panel,gc,"Usuario SSH",sshUsername);addRow(panel,gc,"Autenticación SSH",sshAuthentication);addRow(panel,gc,"Contraseña / frase de clave",sshPassword);addRow(panel,gc,"Seguridad",rememberSshPassword);
        JPanel keyPanel=new JPanel(new BorderLayout(6,0));keyPanel.add(sshPrivateKey);JButton keyBrowse=new JButton(tr("Examinar…"));keyBrowse.addActionListener(e->browseSshFile(sshPrivateKey,tr("Selecciona la clave privada SSH")));keyPanel.add(keyBrowse,BorderLayout.EAST);addRow(panel,gc,"Clave privada",keyPanel);
        addRow(panel,gc,"Puerto local (0 = automático)",sshLocalPort);addRow(panel,gc,"Tiempo de espera (segundos)",sshTimeout);addRow(panel,gc,"Keep-alive (segundos)",sshKeepAlive);
        gc.gridx=1;gc.weightx=1;panel.add(sshStrictHostKey,gc);gc.gridy++;
        JPanel knownPanel=new JPanel(new BorderLayout(6,0));knownPanel.add(sshKnownHosts);JButton knownBrowse=new JButton(tr("Examinar…"));knownBrowse.addActionListener(e->browseSshFile(sshKnownHosts,"Selecciona known_hosts"));knownPanel.add(knownBrowse,BorderLayout.EAST);addRow(panel,gc,"Archivo known_hosts",knownPanel);
        sshSecurityNote.setText("<html>El host y puerto de la pestaña Base de datos son el destino visto desde el servidor SSH.<br>Si no verificas known_hosts, el tráfico va cifrado pero la identidad del servidor no se confirma.</html>");
        gc.gridx=0;gc.gridwidth=2;gc.weightx=1;panel.add(sshSecurityNote,gc);gc.gridy++;gc.weighty=1;panel.add(Box.createVerticalGlue(),gc);
        updateSshUi();return panel;
    }

    private void browseSshFile(JTextField target,String title){JFileChooser chooser=new JFileChooser();chooser.setDialogTitle(title);if(!target.getText().isBlank())chooser.setSelectedFile(new File(target.getText()));if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)target.setText(chooser.getSelectedFile().getAbsolutePath());}

    private void addRow(JPanel panel, GridBagConstraints gc, String label, Component component) {
        gc.gridx = 0;
        gc.weightx = 0;
        JLabel caption = UiLanguage.text(new JLabel(), UiLanguage.caption(label), ":");
        caption.setLabelFor(component); rowLabels.put(component, caption);
        panel.add(caption, gc);
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
        name.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!internalChange && !nameProgrammaticChange) nameManuallyEdited = true;
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });
        type.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && !internalChange) {
                commitPendingNameInput();
                switchEngine((DatabaseType)e.getItem());
                updateAuthenticationUi();
                updateTlsUi();
                updateSshUi();
                refreshDialogLayout();
            }
        });
        authentication.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) updateAuthenticationUi();
        });
        rememberPassword.addActionListener(e -> updateAuthenticationUi());
        sshEnabled.addActionListener(e->updateSshUi());
        sshAuthentication.addActionListener(e->updateSshUi());
        sshStrictHostKey.addActionListener(e->updateSshUi());
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
                    String location = check.file() == null ? tr("Incluido dentro de AdisDB") : check.file().getAbsolutePath();
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

    private void switchEngine(DatabaseType next) {
        if (currentEngine != null) engineDrafts.put(currentEngine, new EngineDraft(host.getText(), ((Number)port.getValue()).intValue(), database.getText(), username.getText(), jdbcUrl.getText(), driverClass.getText(), driverJar.getText(), (TlsMode)tlsMode.getSelectedItem(), (AuthenticationMode)authentication.getSelectedItem(), readOnly.isSelected(), advancedToggle.isSelected()));
        internalChange = true;
        try {
            EngineDraft saved = engineDrafts.get(next);
            if (saved == null) {
                host.setText(next == DatabaseType.H2 || next == DatabaseType.SQLITE ? "" : "localhost"); database.setText(""); username.setText(next == DatabaseType.H2 ? "sa" : ""); driverJar.setText("");
                authentication.setSelectedItem(AuthenticationMode.DATABASE); applyPreset(false);
                readOnly.setSelected(next == DatabaseType.SQLITE); advancedToggle.setSelected(next == DatabaseType.GENERIC); advancedPanel.setVisible(next == DatabaseType.GENERIC);
            } else {
                host.setText(saved.host()); port.setValue(saved.port()); database.setText(saved.database()); username.setText(saved.user()); driverClass.setText(saved.driverClass()); driverJar.setText(saved.driverJar()); tlsMode.setSelectedItem(saved.tls()); authentication.setSelectedItem(saved.authentication()); readOnly.setSelected(saved.readOnly()); jdbcUrl.setText(saved.url()); advancedToggle.setSelected(saved.advanced()); advancedPanel.setVisible(saved.advanced());
                headingIcon.setIcon(IconFactory.database(next,36));
            }
            password.setText(""); rememberPassword.setSelected(false);
            if (!nameManuallyEdited) setSuggestedName(next.displayName() + " local");
            currentEngine = next;
            advancedToggle.setText(advancedToggle.isSelected() ? tr("Configuración avanzada   v") : tr("Configuración avanzada   >"));
        } finally { internalChange = false; }
    }

    private void commitPendingNameInput() {
        InputContext context = name.getInputContext();
        if (context != null) context.endComposition();
    }

    private void setSuggestedName(String value) {
        nameProgrammaticChange = true;
        try { name.setText(value); }
        finally { nameProgrammaticChange = false; }
    }

    private void refreshDialogLayout() {
        connectionTabs.revalidate();
        getContentPane().revalidate();
        repaint();
        SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) return;
            Dimension preferred = getPreferredSize();
            Dimension current = getSize();
            if (preferred.width > current.width || preferred.height > current.height) {
                setSize(Math.max(current.width, preferred.width), Math.max(current.height, preferred.height));
            }
        });
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
            if (selected == DatabaseType.SQLITE) readOnly.setSelected(true);
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
            case SNOWFLAKE -> url;
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

    private void browseDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(tr("Selecciona tu base de datos SQLite"));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(tr("Bases SQLite (*.sqlite, *.sqlite3, *.db, *.db3)"), "sqlite", "sqlite3", "db", "db3"));
        if (!database.getText().isBlank()) chooser.setSelectedFile(new File(database.getText()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile(); database.setText(file.getAbsolutePath());
            if (!nameManuallyEdited) setSuggestedName(file.getName());
        }
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
            SshConfig ssh=profile.ssh();sshEnabled.setSelected(ssh.enabled());sshHost.setText(ssh.host());sshPort.setValue(ssh.port());sshUsername.setText(ssh.username());sshAuthentication.setSelectedItem(ssh.authentication());sshPrivateKey.setText(ssh.privateKey());sshKnownHosts.setText(ssh.knownHosts());sshStrictHostKey.setSelected(ssh.strictHostKeyChecking());sshLocalPort.setValue(ssh.localPort());sshTimeout.setValue(ssh.connectTimeoutSeconds());sshKeepAlive.setValue(ssh.keepAliveSeconds());
        } finally {
            internalChange = false;
        }
        updateAuthenticationUi();
        updateTlsUi();
        updateSshUi();
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
        SshConfig ssh=new SshConfig(sshEnabled.isEnabled()&&sshEnabled.isSelected(),sshHost.getText(),((Number)sshPort.getValue()).intValue(),sshUsername.getText(),(SshConfig.Authentication)sshAuthentication.getSelectedItem(),sshPrivateKey.getText(),sshKnownHosts.getText(),sshStrictHostKey.isSelected(),((Number)sshLocalPort.getValue()).intValue(),((Number)sshTimeout.getValue()).intValue(),((Number)sshKeepAlive.getValue()).intValue());
        if (type.getSelectedItem() == DatabaseType.SQLITE) return new ConnectionProfile(profileId, name.getText(), DatabaseType.SQLITE, "", 0,
                database.getText(), "", DatabaseType.SQLITE.buildUrl("",0,database.getText()), driverClass.getText(), driverJar.getText(), AuthenticationMode.DATABASE, SshConfig.disabled(), readOnly.isSelected());
        return new ConnectionProfile(profileId, name.getText(), (DatabaseType) type.getSelectedItem(), host.getText(),
                ((Number) port.getValue()).intValue(), database.getText(), username.getText(), jdbcUrl.getText(),
                driverClass.getText(), driverJar.getText(), (AuthenticationMode) authentication.getSelectedItem(), ssh, readOnly.isSelected());
    }

    private boolean validateForm() {
        if (name.getText().isBlank()) return fail(tr("Escribe un nombre para la conexión."), name);
        DatabaseType engine = (DatabaseType) type.getSelectedItem();
        if (engine == DatabaseType.SQLITE) {
            try { SQLiteSupport.prepare(profileFromForm()); }
            catch (Exception error) { return fail(error.getMessage(), database); }
        } else if (engine != DatabaseType.H2 && engine != DatabaseType.GENERIC && username.getText().isBlank()) return fail(tr("Escribe el usuario de la base de datos. La contraseña sí puede quedar vacía."), username);
        if ((engine == DatabaseType.H2 || engine == DatabaseType.ORACLE) && database.getText().isBlank()) return fail(tr("Indica la base de datos o el servicio al que deseas conectarte."), database);
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
        SshConfig ssh=profileFromForm().ssh();
        if(ssh.enabled()){
            if(ssh.host().isBlank())return fail("Escribe el host del servidor SSH.",sshHost);
            if(ssh.username().isBlank())return fail("Escribe el usuario SSH.",sshUsername);
            if(ssh.authentication()==SshConfig.Authentication.PASSWORD&&sshPassword.getPassword().length==0&&!(hadStoredSshPassword&&rememberSshPassword.isSelected()))return fail("Escribe la contraseña SSH o conserva la contraseña guardada.",sshPassword);
            if(ssh.authentication()==SshConfig.Authentication.PRIVATE_KEY&&(ssh.privateKey().isBlank()||!new File(ssh.privateKey()).isFile()))return fail("Selecciona una clave privada SSH válida.",sshPrivateKey);
            if(ssh.strictHostKeyChecking()&&(ssh.knownHosts().isBlank()||!new File(ssh.knownHosts()).isFile()))return fail("Selecciona un archivo known_hosts válido o desactiva la verificación explícitamente.",sshKnownHosts);
        }
        return true;
    }

    private void updateAuthenticationUi() {
        DatabaseType engine = (DatabaseType) type.getSelectedItem();
        updateEngineRows(engine);
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
        if (engine == DatabaseType.SQLITE) {
            UiLanguage.text(authenticationNote, UiLanguage.key("<html>Elige el archivo de tu computadora. No se sube ni se copia.<br>No necesitas servidor, usuario ni contraseña; cada archivo es una conexión.</html>"));
        } else if (engine == DatabaseType.H2) {
            UiLanguage.text(authenticationNote, UiLanguage.key("<html>Ejemplos: ~/datos, mem:pruebas o tcp://servidor/~/datos.<br>H2 sí admite usuarios. Para una base nueva suele usarse sa.</html>"));
        } else if (engine == DatabaseType.GENERIC) {
            UiLanguage.text(authenticationNote, UiLanguage.key("<html>Indica la URL JDBC, la clase y el archivo del controlador.<br>Usuario y contraseña son opcionales según el controlador.</html>"));
        } else if (engine == DatabaseType.ORACLE) {
            UiLanguage.text(authenticationNote, UiLanguage.key("<html>Indica host, puerto y nombre de servicio Oracle (por ejemplo, XEPDB1).<br>Para SID, TNS o SSL configura la URL JDBC avanzada.</html>"));
        } else if (engine == DatabaseType.MONGODB) {
            UiLanguage.text(authenticationNote, UiLanguage.key("<html>MongoDB usa aquí su Atlas SQL Interface compatible con JDBC.<br>"), UiLanguage.key("Para MongoDB Community nativo se incorporará un explorador de documentos independiente.</html>"));
        } else if(engine==DatabaseType.SNOWFLAKE){
            UiLanguage.text(authenticationNote,UiLanguage.key("<html>Escribe el host completo de la cuenta (por ejemplo, cuenta.region.snowflakecomputing.com).<br>Selecciona el controlador JDBC oficial de Snowflake en las opciones avanzadas.</html>"));
        } else if (!SecretStore.isSupported()) {
            UiLanguage.text(authenticationNote, UiLanguage.key("<html>El usuario es obligatorio; la contraseña puede quedar vacía.<br>"), UiLanguage.key("La contraseña se solicita al conectar y no se guarda en disco."), "</html>");
        } else UiLanguage.text(authenticationNote, (windows
                ? tr("<html>El usuario identifica la cuenta MariaDB (por ejemplo, root).<br>") + tr("La contraseña no se envía: se usa explícitamente la sesión actual de Windows mediante GSSAPI/SSPI.</html>")
                : hadStoredPassword && rememberPassword.isSelected() && password.getDocument().getLength() == 0
                    ? tr("<html>La contraseña guardada está protegida por el almacén seguro del sistema y no se muestra.<br>") + tr("Déjala vacía para conservarla, o escribe otra para reemplazarla.</html>")
                    : tr("<html>El usuario es obligatorio; la contraseña puede quedar vacía.<br>")), UiLanguage.key("Si eliges recordarla se protege con el almacén seguro del sistema y no se guarda en el perfil.</html>"));
    }

    private void rowVisible(Component component, boolean visible) {
        component.setVisible(visible); JLabel label = rowLabels.get(component); if (label != null) label.setVisible(visible);
    }
    private void updateEngineRows(DatabaseType engine) {
        boolean sqlite = engine == DatabaseType.SQLITE, generic = engine == DatabaseType.GENERIC;
        boolean network = !sqlite && !generic && engine != DatabaseType.H2;
        rowVisible(host, network); rowVisible(port, network);
        rowVisible(authentication, engine == DatabaseType.MYSQL || engine == DatabaseType.MARIADB);
        rowVisible(tlsMode, network && engine != DatabaseType.ORACLE);
        rowVisible(username, !sqlite); rowVisible(password, !sqlite); rowVisible(rememberPassword, !sqlite);
        rowVisible(databasePanel, !generic); browseDatabase.setVisible(sqlite);
        jdbcUrl.setEditable(!sqlite);
        String caption = sqlite ? "Archivo SQLite" : engine == DatabaseType.ORACLE ? "Servicio Oracle" : engine == DatabaseType.H2 ? "Ubicación / modo H2" : engine == DatabaseType.POSTGRESQL ? "Base de datos (opcional)" : "Base de datos";
        UiLanguage.text(rowLabels.get(databasePanel), UiLanguage.key(caption), ":");
        advancedToggle.setVisible(!generic);
        if (generic) { advancedPanel.setVisible(true); advancedToggle.setSelected(true); }
        databasePanel.revalidate();
    }

    private void updateTlsUi() {
        DatabaseType engine = (DatabaseType) type.getSelectedItem();
        database.setToolTipText(engine == DatabaseType.POSTGRESQL
                ? tr("Déjala vacía para consultar las bases disponibles para este usuario.")
                : tr("Base de datos, servicio o archivo al que deseas conectarte"));
        boolean supported = engine == DatabaseType.POSTGRESQL || engine == DatabaseType.MYSQL
                || engine == DatabaseType.MARIADB || engine == DatabaseType.SQLSERVER
                || engine == DatabaseType.MONGODB || engine==DatabaseType.SNOWFLAKE;
        tlsMode.setEnabled(supported);
        tlsMode.setToolTipText(supported
                ? tr("Elige cómo debe protegerse la conexión con el servidor")
                : tr("Este motor administra la seguridad desde su propia URL o archivo local"));
    }

    private void updateSshUi(){
        DatabaseType engine=(DatabaseType)type.getSelectedItem();boolean network=engine!=null&&engine!=DatabaseType.SQLITE&&engine!=DatabaseType.H2&&engine!=DatabaseType.GENERIC;
        sshEnabled.setEnabled(network);boolean active=network&&sshEnabled.isSelected();boolean key=sshAuthentication.getSelectedItem()==SshConfig.Authentication.PRIVATE_KEY;
        for(Component component:new Component[]{sshHost,sshPort,sshUsername,sshAuthentication,sshPassword,rememberSshPassword,sshLocalPort,sshTimeout,sshKeepAlive,sshStrictHostKey})component.setEnabled(active);
        sshPrivateKey.setEnabled(active&&key);sshKnownHosts.setEnabled(active&&sshStrictHostKey.isSelected());
        rememberSshPassword.setEnabled(active&&SecretStore.isSupported());
        sshPassword.setToolTipText(key?tr("Frase de la clave privada; puede quedar vacía si la clave no está cifrada."):tr("Contraseña del usuario SSH."));
        if(!SecretStore.isSupported())rememberSshPassword.setSelected(false);
    }

    private boolean fail(String message, JComponent component) {
        JOptionPane.showMessageDialog(this, message, tr("Revisa la conexión"), JOptionPane.WARNING_MESSAGE);
        component.requestFocusInWindow();
        return false;
    }

    private void accept(boolean connect) {
        if (!validateForm()) return;
        result = new Result(profileFromForm(), type.getSelectedItem() == DatabaseType.SQLITE ? new char[0] : password.getPassword(),sshEnabled.isSelected()?sshPassword.getPassword():new char[0], connect,
                SecretStore.isSupported() && password.isEnabled() && rememberPassword.isSelected(),SecretStore.isSupported()&&sshEnabled.isSelected()&&rememberSshPassword.isSelected());
        dispose();
    }

    private void testConnection(JButton button) {
        if (!validateForm()) return;
        button.setEnabled(false);
        status.setIcon(IconFactory.of(IconFactory.Kind.REFRESH, 18));
        UiLanguage.text(status, UiLanguage.key("Probando conexión…"));
        ConnectionProfile profile = profileFromForm();
        char[] secret = type.getSelectedItem() == DatabaseType.SQLITE ? new char[0] : password.getPassword();
        if (type.getSelectedItem() != DatabaseType.SQLITE && secret.length == 0 && hadStoredPassword && rememberPassword.isSelected()) {
            try { secret = secretStore.load(profileId); }
            catch (Exception error) {
                button.setEnabled(true);
                status.setForeground(UIManager.getColor("Label.foreground"));
                UiLanguage.text(status, UiLanguage.key("Windows no pudo recuperar la contraseña protegida."));
                return;
            }
        }
        char[] testSecret = secret == null ? new char[0] : secret;
        char[] tunnelSecret=profile.ssh().enabled()?sshPassword.getPassword():new char[0];
        if(profile.ssh().enabled()&&tunnelSecret.length==0&&hadStoredSshPassword&&rememberSshPassword.isSelected()){
            try{char[] stored=secretStore.load(SshConfig.secretId(profileId));tunnelSecret=stored==null?new char[0]:stored;}
            catch(Exception error){button.setEnabled(true);status.setText("Windows no pudo recuperar la contraseña SSH protegida.");Arrays.fill(testSecret,'\0');return;}
        }
        char[] testTunnelSecret=tunnelSecret;
        long started = System.nanoTime();
        new SwingWorker<ConnectionCheck, Void>() {
            private java.util.List<String> availableDatabases;
            @Override protected ConnectionCheck doInBackground() throws Exception {
                try (JdbcSession session = JdbcSession.open(profile, testSecret,testTunnelSecret, value -> SwingUtilities.invokeLater(() -> {
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
                    return new ConnectionCheck(product + " " + version, session.connection().getCatalog(),
                            (System.nanoTime() - started) / 1_000_000L);
                } finally {
                    Arrays.fill(testSecret, '\0');
                    Arrays.fill(testTunnelSecret,'\0');
                }
            }

            @Override protected void done() {
                button.setEnabled(true);
                driverProgress.setVisible(false);
                if (!isDisplayable()) return;
                try {
                    ConnectionCheck check = get();
                    status.setForeground(UIManager.getColor("Label.foreground"));
                    status.setIcon(IconFactory.of(IconFactory.Kind.TEST, 20));
                    UiLanguage.text(status, UiLanguage.key("Conexión correcta: "), check.server());
                    JOptionPane.showMessageDialog(ConnectionDialog.this,
                            ConnectionTestFeedback.success(profile, check.server(), check.database(), check.elapsedMillis()),
                            tr("Resultado de la prueba de conexión"), JOptionPane.PLAIN_MESSAGE);
                    if (availableDatabases != null && isDisplayable() && profile.equals(profileFromForm())) {
                        String selected = DatabaseSelectionDialog.choose(ConnectionDialog.this,
                                profile.username(), availableDatabases);
                        if (selected != null) database.setText(selected);
                    }
                } catch (Exception ex) {
                    status.setForeground(UIManager.getColor("Label.foreground"));
                    status.setIcon(IconFactory.of(IconFactory.Kind.STOP, 20));
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
        String message = connectionFailureMessage(exception);
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
        JOptionPane.showMessageDialog(this, ConnectionTestFeedback.failure(message), tr("Error de conexión"), JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Keeps the actionable message produced by the SSH/JDBC layer instead of
     * replacing it with a low-level cause such as "Connection refused:
     * getsockopt".  SwingWorker.get() commonly wraps the original exception
     * in ExecutionException, so walk from the outside and skip only wrapper or
     * raw Java-network messages when a more useful message is available.
     */
    static String connectionFailureMessage(Throwable error) {
        String fallback = null;
        for (Throwable current = error; current != null; current = current.getCause()) {
            String candidate = current.getMessage();
            if (candidate == null || candidate.isBlank()) continue;
            candidate = candidate.trim();
            if (fallback == null) fallback = candidate;
            String lower = candidate.toLowerCase(java.util.Locale.ROOT);
            boolean wrapper = current instanceof java.util.concurrent.ExecutionException
                    || current instanceof java.util.concurrent.CompletionException;
            boolean rawJava = lower.startsWith("java.") || lower.startsWith("javax.")
                    || lower.startsWith("com.jcraft.");
            boolean rawSocket = lower.equals("connection refused: getsockopt")
                    || lower.equals("connection timed out: getsockopt")
                    || lower.startsWith("connect timed out");
            if (!wrapper && !rawJava && !(rawSocket && current.getCause() != null)) return candidate;
        }
        return fallback == null ? tr("Error desconocido") : fallback;
    }
}

