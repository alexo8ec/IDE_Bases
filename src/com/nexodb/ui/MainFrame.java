package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.db.JdbcSession;
import com.nexodb.db.PostgresSupport;
import com.nexodb.db.QueryResult;
import com.nexodb.db.SchemaItem;
import com.nexodb.db.StoredObjectService;
import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import com.nexodb.update.UpdateService;
import com.nexodb.util.ProfileStore;
import com.nexodb.util.SecretStore;
import com.nexodb.util.DataExporter;

import javax.swing.*;
import javax.imageio.ImageIO;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MainFrame extends JFrame {
    private final ProfileStore profileStore = new ProfileStore();
    private final SecretStore secretStore = new SecretStore();
    private final UpdateService updateService = new UpdateService();
    private final DefaultListModel<ConnectionProfile> profileModel = new DefaultListModel<>();
    private final JList<ConnectionProfile> profileList = new JList<>(profileModel);
    private final LazyNode objectRoot = new LazyNode(new SchemaItem(SchemaItem.Kind.ROOT, "", "", tr("Conexiones activas"), ""), true, null);
    private final DefaultTreeModel objectModel = new DefaultTreeModel(objectRoot);
    private final JTree objectTree = new JTree(objectModel);
    private final JTextField objectSearch = new JTextField();
    private final DefaultListModel<SchemaItem> searchResultModel = new DefaultListModel<>();
    private final JList<SchemaItem> searchResults = new JList<>(searchResultModel);
    private final CardLayout objectCards = new CardLayout();
    private final JPanel objectCenter = new JPanel(objectCards);
    private final JLabel status = UiLanguage.text(new JLabel(tr("Listo")), UiLanguage.key("Listo"));
    private final JProgressBar busyBar = new JProgressBar();
    private final QueryPanel queryPanel = new QueryPanel(this::setStatus, this::setBusy);
    private final JTabbedPane workspaceTabs = new JTabbedPane();
    private final JButton connectButton = UiLanguage.text(new JButton(tr("Conectar")), UiLanguage.key("Conectar"));
    private final JButton disconnectButton = UiLanguage.text(new JButton(tr("Desconectar")), UiLanguage.key("Desconectar"));
    private final JButton refreshButton = UiLanguage.text(new JButton(tr("Actualizar objetos")), UiLanguage.key("Actualizar objetos"));
    private final JButton compareButton = UiLanguage.text(new JButton(tr("Comparar esquemas")), UiLanguage.key("Comparar esquemas"));
    private final Map<String, JdbcSession> sessions = new LinkedHashMap<>();
    private JdbcSession session;
    private int busyDepth;
    private int searchGeneration;
    private boolean checkingUpdates;
    private final ComparisonChoices schemaChoices = new ComparisonChoices();
    private final ComparisonChoices dataChoices = new ComparisonChoices();
    private final com.nexodb.db.ScheduledTasks scheduledTasks=new com.nexodb.db.ScheduledTasks(
            Boolean.getBoolean("nexodb.disableDraftPersistence")?null:com.nexodb.util.AppSettings.file().resolveSibling("scheduled-tasks.properties"),
            id->profileStore.load().stream().filter(p->p.id().equals(id)).findFirst().orElse(null),
            id->secretStore.has(id)?secretStore.load(id):null,
            message->SwingUtilities.invokeLater(()->setStatus(message)),!Boolean.getBoolean("nexodb.disableDraftPersistence"));

    public MainFrame() {
        super("NexoDB Studio");
        loadApplicationIcons();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 680));
        setSize(1320, 840);
        setLocationRelativeTo(null);
        queryPanel.setDatabaseChangeConsumer(database -> {
            if (!objectSearch.getText().isBlank()) searchObjects();
        });
        queryPanel.setColumnEditor((origin,target,column,delete)->{
            TableWorkbenchPanel panel=openTable(origin,target.table(),TableWorkbenchPanel.COLUMNS);
            if(panel!=null)panel.requestColumnAction(column,delete);
        });
        queryPanel.setToolHandler(this::openQueryTool);
        scheduledTasks.setLicenseCheck(LicenseGate::licensed);
        setJMenuBar(buildMenu());
        setContentPane(buildContent());
        UiDesign.enhance(this);
        configureProfiles();
        configureTree();
        loadProfilesAsync();
        updateConnectionActions();
        scheduleAutomaticUpdateCheck();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { exitApplication(); }
        });
    }

    private void loadApplicationIcons() {
        try {
            BufferedImage source = ImageIO.read(MainFrame.class.getResource("/com/nexodb/assets/nexodb-icon.png"));
            if (source == null) return;
            List<Image> icons = new ArrayList<>();
            for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
                icons.add(source.getScaledInstance(size, size, Image.SCALE_SMOOTH));
            }
            setIconImages(icons);
        } catch (Exception ignored) {
        }
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.add(buildMainToolbar(), BorderLayout.NORTH);

        JTabbedPane navigator = new JTabbedPane();
        UiLanguage.addTab(navigator, new Object[]{UiLanguage.key("Conexiones")}, IconFactory.of(IconFactory.Kind.CONNECTIONS), buildProfilesPanel());
        UiLanguage.addTab(navigator, new Object[]{UiLanguage.key("Objetos")}, IconFactory.of(IconFactory.Kind.OBJECTS), buildObjectsPanel());
        navigator.putClientProperty("JTabbedPane.tabType", "card");
        navigator.setMinimumSize(new Dimension(250, 0));
        navigator.setPreferredSize(new Dimension(310, 0));

        UiLanguage.addTab(workspaceTabs, new Object[]{UiLanguage.key("Consulta SQL")}, IconFactory.of(IconFactory.Kind.SQL, 16), queryPanel);
        workspaceTabs.putClientProperty("JTabbedPane.tabType", "card");
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigator, workspaceTabs);
        split.setDividerLocation(310);
        split.setResizeWeight(0.0);
        root.add(split, BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(new EmptyBorder(7, 12, 7, 12));
        status.setIcon(IconFactory.of(IconFactory.Kind.INFO, 15));
        status.setIconTextGap(7);
        statusBar.add(status, BorderLayout.WEST);
        busyBar.setIndeterminate(true);
        busyBar.setPreferredSize(new Dimension(190, 15));
        busyBar.setVisible(false);
        JPanel progressHolder = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        progressHolder.add(busyBar);
        statusBar.add(progressHolder, BorderLayout.CENTER);
        JLabel version = new JLabel("NexoDB Studio " + UpdateService.CURRENT_VERSION);
        version.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusBar.add(version, BorderLayout.EAST);
        root.add(statusBar, BorderLayout.SOUTH);
        return root;
    }

    private JComponent buildMainToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(new EmptyBorder(7, 10, 7, 10));
        JButton add = UiLanguage.text(new JButton(tr("Nueva conexión")), UiLanguage.key("Nueva conexión"));
        styleToolbarButton(add, IconFactory.Kind.ADD, tr("Crear un nuevo perfil de conexión"));
        styleToolbarButton(connectButton, IconFactory.Kind.CONNECT, tr("Conectar el perfil seleccionado"));
        styleToolbarButton(disconnectButton, IconFactory.Kind.DISCONNECT, tr("Cerrar la conexión activa"));
        styleToolbarButton(refreshButton, IconFactory.Kind.REFRESH, tr("Actualizar bases, esquemas y tablas"));
        styleToolbarButton(compareButton, IconFactory.Kind.COMPARE, tr("Comparar dos bases de datos de la conexión activa"));
        add.addActionListener(e -> newConnection());
        connectButton.addActionListener(e -> connectSelected());
        disconnectButton.addActionListener(e -> disconnect());
        refreshButton.addActionListener(e -> refreshObjects());
        compareButton.addActionListener(e -> compareSchemas());
        toolbar.add(add);
        toolbar.addSeparator();
        toolbar.add(connectButton);
        toolbar.add(disconnectButton);
        toolbar.addSeparator();
        toolbar.add(refreshButton);
        toolbar.addSeparator();
        toolbar.add(compareButton);
        return toolbar;
    }

    private JComponent buildProfilesPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.setFixedCellHeight(52);
        profileList.setCellRenderer(new ProfileRenderer());
        profileList.putClientProperty("List.selectionArc", 10);
        panel.add(new JScrollPane(profileList), BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1, 3, 5, 0));
        JButton add = UiLanguage.text(new JButton(tr("Nueva")), UiLanguage.key("Nueva"));
        JButton edit = UiLanguage.text(new JButton(tr("Editar")), UiLanguage.key("Editar"));
        JButton delete = UiLanguage.text(new JButton(tr("Eliminar")), UiLanguage.key("Eliminar"));
        add.setIcon(IconFactory.of(IconFactory.Kind.ADD, 16));
        edit.setIcon(IconFactory.of(IconFactory.Kind.EDIT, 16));
        delete.setIcon(IconFactory.of(IconFactory.Kind.DELETE, 16));
        for (JButton button : List.of(add, edit, delete)) {
            button.setMargin(new Insets(5, 4, 5, 4));
            button.setIconTextGap(5);
        }
        add.addActionListener(e -> newConnection());
        edit.addActionListener(e -> editSelected());
        delete.addActionListener(e -> deleteSelected());
        actions.add(add);
        actions.add(edit);
        actions.add(delete);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildObjectsPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        UiLanguage.placeholder(objectSearch, "Buscar objetos en la base activa…");
        objectSearch.putClientProperty("JTextField.leadingIcon", IconFactory.of(IconFactory.Kind.SEARCH, 16));
        JButton clearSearch = new JButton("×");
        UiLanguage.tooltip(clearSearch, UiLanguage.key("Limpiar búsqueda (Esc)"));
        clearSearch.setFocusable(false);
        clearSearch.setBorderPainted(false);
        clearSearch.setContentAreaFilled(false);
        clearSearch.setMargin(new Insets(0, 5, 0, 5));
        clearSearch.setVisible(false);
        clearSearch.addActionListener(e -> { objectSearch.setText(""); objectSearch.requestFocusInWindow(); });
        objectSearch.putClientProperty("JTextField.trailingComponent", clearSearch);
        Action clearSearchAction = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { objectSearch.setText(""); objectSearch.requestFocusInWindow(); }
        };
        objectSearch.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear-object-search");
        objectSearch.getActionMap().put("clear-object-search", clearSearchAction);
        UiLanguage.tooltip(objectSearch, UiLanguage.key("Busca tablas, vistas, procedimientos, funciones, triggers y eventos en la base seleccionada"));
        panel.add(objectSearch, BorderLayout.NORTH);
        objectCenter.add(new JScrollPane(objectTree), "tree");
        searchResults.setCellRenderer(new SearchResultRenderer());
        searchResults.setFixedCellHeight(42);
        searchResults.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        objectCenter.add(new JScrollPane(searchResults), "search");
        panel.add(objectCenter, BorderLayout.CENTER);
        javax.swing.Timer searchTimer = new javax.swing.Timer(280, e -> searchObjects());
        searchTimer.setRepeats(false);
        objectSearch.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                clearSearch.setVisible(!objectSearch.getText().isBlank());
                if (objectSearch.getText().isBlank()) {
                    searchTimer.stop();
                    searchResultModel.clear();
                    objectCards.show(objectCenter, "tree");
                } else {
                    objectCards.show(objectCenter, "search");
                    searchTimer.restart();
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });
        searchResults.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                SchemaItem selected = searchResults.getSelectedValue();
                if (e.getClickCount() == 2 && selected != null
                        && !selected.name().equals(tr("Buscando…")) && !selected.name().equals(tr("Sin resultados"))
                        && !selected.name().equals(tr("Error al buscar"))) {
                    if (selected.kind() == SchemaItem.Kind.TABLE || selected.kind() == SchemaItem.Kind.VIEW) {
                        openTable(session, selected, selected.kind()==SchemaItem.Kind.TABLE?TableWorkbenchPanel.COLUMNS:TableWorkbenchPanel.DATA);
                    } else if (isProgrammableObject(selected.kind())) {
                        openDefinition(session, selected);
                    }
                }
            }
        });
        searchResults.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear-object-search");
        searchResults.getActionMap().put("clear-object-search", clearSearchAction);
        JButton refresh = UiLanguage.text(new JButton(tr("Actualizar")), UiLanguage.key("Actualizar"));
        refresh.setIcon(IconFactory.of(IconFactory.Kind.REFRESH, 16));
        refresh.addActionListener(e -> refreshObjects());
        panel.add(refresh, BorderLayout.SOUTH);
        return panel;
    }

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = UiLanguage.text(new JMenu(tr("Archivo")), UiLanguage.key("Archivo"));
        file.setIcon(IconFactory.of(IconFactory.Kind.FOLDER, 16));
        JMenuItem newConnection = UiLanguage.text(new JMenuItem(tr("Nueva conexión…")), UiLanguage.key("Nueva conexión…"));
        JMenuItem connect = UiLanguage.text(new JMenuItem(tr("Conectar")), UiLanguage.key("Conectar"));
        JMenuItem disconnect = UiLanguage.text(new JMenuItem(tr("Desconectar")), UiLanguage.key("Desconectar"));
        JMenuItem exit = UiLanguage.text(new JMenuItem(tr("Salir")), UiLanguage.key("Salir"));
        newConnection.setIcon(IconFactory.of(IconFactory.Kind.ADD));
        connect.setIcon(IconFactory.of(IconFactory.Kind.CONNECT));
        disconnect.setIcon(IconFactory.of(IconFactory.Kind.DISCONNECT));
        exit.setIcon(IconFactory.of(IconFactory.Kind.EXIT));
        newConnection.addActionListener(e -> newConnection());
        connect.addActionListener(e -> connectSelected());
        disconnect.addActionListener(e -> disconnect());
        exit.addActionListener(e -> exitApplication());
        file.add(newConnection);
        file.add(connect);
        file.add(disconnect);
        file.addSeparator();
        file.add(toolMenuItem("Nueva consulta",IconFactory.Kind.ADD,KeyEvent.VK_T,KeyEvent.CTRL_DOWN_MASK,()->{workspaceTabs.setSelectedIndex(0);queryPanel.openNewQuery("");}));
        file.add(toolMenuItem("Abrir archivo SQL…",IconFactory.Kind.FOLDER,KeyEvent.VK_O,KeyEvent.CTRL_DOWN_MASK,queryPanel::openSqlFile));
        file.add(toolMenuItem("Guardar consulta SQL…",IconFactory.Kind.SAVE,KeyEvent.VK_S,KeyEvent.CTRL_DOWN_MASK,()->queryPanel.saveSqlFile(false)));
        file.add(toolMenuItem("Guardar SQL como…",IconFactory.Kind.SAVE,KeyEvent.VK_S,KeyEvent.CTRL_DOWN_MASK|KeyEvent.SHIFT_DOWN_MASK,()->queryPanel.saveSqlFile(true)));
        file.addSeparator();
        file.add(exit);

        JMenu edit=UiLanguage.text(new JMenu(tr("Editar")),UiLanguage.key("Editar"));
        edit.add(toolMenuItem("Deshacer",IconFactory.Kind.ROLLBACK,KeyEvent.VK_Z,KeyEvent.CTRL_DOWN_MASK,queryPanel::undoCurrent));
        edit.add(toolMenuItem("Rehacer",IconFactory.Kind.REFRESH,KeyEvent.VK_Y,KeyEvent.CTRL_DOWN_MASK,queryPanel::redoCurrent));

        JMenu database = UiLanguage.text(new JMenu(tr("Base de datos")), UiLanguage.key("Base de datos"));
        database.setIcon(IconFactory.of(IconFactory.Kind.DATABASE, 16));
        JMenuItem users = UiLanguage.text(new JMenuItem(tr("Administrar usuarios…"), IconFactory.of(IconFactory.Kind.USER)), UiLanguage.key("Administrar usuarios…"));
        JMenuItem refreshObjects = UiLanguage.text(new JMenuItem(tr("Actualizar objetos"), IconFactory.of(IconFactory.Kind.REFRESH)), UiLanguage.key("Actualizar objetos"));
        users.addActionListener(e -> manageUsers());
        refreshObjects.addActionListener(e -> refreshObjects());
        database.add(users);
        JMenuItem backupSql=UiLanguage.text(new JMenuItem(tr("Respaldo SQL…"),IconFactory.of(IconFactory.Kind.EXPORT)),UiLanguage.key("Respaldo SQL…"));
        JMenuItem importSql=UiLanguage.text(new JMenuItem(tr("Importar / restaurar SQL…"),IconFactory.of(IconFactory.Kind.DATABASE)),UiLanguage.key("Importar / restaurar SQL…"));
        backupSql.addActionListener(e->transferSql(false,session,null));importSql.addActionListener(e->transferSql(true,session,null));
        database.add(backupSql);database.add(importSql);
        database.addSeparator();
        database.add(refreshObjects);

        JMenu comparison = UiLanguage.text(new JMenu(tr("Comparación")), UiLanguage.key("Comparación"));
        comparison.setIcon(IconFactory.of(IconFactory.Kind.COMPARE, 16));
        JMenuItem compareSchemas = UiLanguage.text(new JMenuItem(tr("Nueva comparación de esquemas…"), IconFactory.of(IconFactory.Kind.COMPARE)), UiLanguage.key("Nueva comparación de esquemas…"));
        JMenuItem compareData = UiLanguage.text(new JMenuItem(tr("Nueva comparación de datos…"), IconFactory.of(IconFactory.Kind.TABLE)), UiLanguage.key("Nueva comparación de datos…"));
        compareSchemas.addActionListener(e -> compareSchemas());
        compareData.addActionListener(e -> compareData());
        comparison.add(compareSchemas);
        comparison.add(compareData);

        JMenu query = UiLanguage.text(new JMenu(tr("Consulta")), UiLanguage.key("Consulta"));
        query.setIcon(IconFactory.of(IconFactory.Kind.SQL, 16));
        JMenuItem newQuery=UiLanguage.text(new JMenuItem(tr("Nueva consulta"),IconFactory.of(IconFactory.Kind.ADD)),UiLanguage.key("Nueva consulta"));
        newQuery.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N,KeyEvent.CTRL_DOWN_MASK));
        newQuery.addActionListener(e->{workspaceTabs.setSelectedIndex(0);queryPanel.openNewQuery("");});
        query.add(newQuery);query.addSeparator();
        query.add(toolMenuItem("Constructor visual de consultas",IconFactory.Kind.TABLE,KeyEvent.VK_K,KeyEvent.CTRL_DOWN_MASK,()->openQueryTool("builder")));
        query.add(toolMenuItem("Diseñador de esquemas",IconFactory.Kind.SCHEMA,KeyEvent.VK_D,KeyEvent.CTRL_DOWN_MASK|KeyEvent.ALT_DOWN_MASK,()->openQueryTool("designer")));
        query.add(toolMenuItem("Buscar datos",IconFactory.Kind.SEARCH,KeyEvent.VK_D,KeyEvent.CTRL_DOWN_MASK|KeyEvent.SHIFT_DOWN_MASK,()->openQueryTool("search")));
        query.add(toolMenuItem("Historial SQL",IconFactory.Kind.SQL,KeyEvent.VK_H,KeyEvent.CTRL_DOWN_MASK|KeyEvent.SHIFT_DOWN_MASK,queryPanel::showHistory));
        query.add(toolMenuItem("Renombrar consulta…",IconFactory.Kind.EDIT,KeyEvent.VK_F2,KeyEvent.ALT_DOWN_MASK,queryPanel::renameCurrentQuery));query.addSeparator();
        JMenuItem executeQuery = UiLanguage.text(new JMenuItem(tr("Ejecutar"), IconFactory.of(IconFactory.Kind.PLAY)), UiLanguage.key("Ejecutar"));
        executeQuery.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        executeQuery.addActionListener(e -> queryPanel.executeCurrent());
        JMenuItem cancelQuery = UiLanguage.text(new JMenuItem(tr("Cancelar"), IconFactory.of(IconFactory.Kind.STOP)), UiLanguage.key("Cancelar"));
        cancelQuery.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        cancelQuery.addActionListener(e -> queryPanel.cancelCurrent());
        query.add(executeQuery);
        query.add(cancelQuery);

        JMenu power=UiLanguage.text(new JMenu(tr("Herramientas avanzadas")),UiLanguage.key("Herramientas avanzadas"));
        power.add(toolMenuItem("Sincronizar bases de datos…",IconFactory.Kind.COMPARE,KeyEvent.VK_W,KeyEvent.CTRL_DOWN_MASK|KeyEvent.ALT_DOWN_MASK,()->{
            Object[] options={tr("Comparar esquemas"),tr("Comparar datos")};int choice=JOptionPane.showOptionDialog(this,tr("Selecciona qué deseas comparar. El script se abrirá en una consulta nueva para revisarlo antes de ejecutar."),tr("Sincronizar bases de datos"),JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,options,options[0]);if(choice==0)compareSchemas();if(choice==1)compareData();
        }));
        power.add(toolMenuItem("Comparación visual de datos…",IconFactory.Kind.TABLE,KeyEvent.VK_Q,KeyEvent.CTRL_DOWN_MASK|KeyEvent.ALT_DOWN_MASK,this::compareData));
        power.add(toolMenuItem("Sincronizar esquemas…",IconFactory.Kind.COMPARE,KeyEvent.VK_Q,KeyEvent.CTRL_DOWN_MASK,this::compareSchemas));power.addSeparator();
        power.add(toolMenuItem("Importar datos externos…",IconFactory.Kind.DATABASE,KeyEvent.VK_O,KeyEvent.CTRL_DOWN_MASK|KeyEvent.ALT_DOWN_MASK,this::importExternal));power.addSeparator();
        power.add(toolMenuItem("Programar consultas y reportes…",IconFactory.Kind.SQL,KeyEvent.VK_N,KeyEvent.CTRL_DOWN_MASK|KeyEvent.ALT_DOWN_MASK,()->openTasks(com.nexodb.db.ScheduledTasks.Kind.REPORT)));
        power.add(toolMenuItem("Respaldos programados…",IconFactory.Kind.SAVE,KeyEvent.VK_S,KeyEvent.CTRL_DOWN_MASK|KeyEvent.ALT_DOWN_MASK,()->openTasks(com.nexodb.db.ScheduledTasks.Kind.BACKUP)));
        power.add(toolMenuItem("Tareas programadas…",IconFactory.Kind.SETTINGS,0,0,()->openTasks(null)));power.addSeparator();
        power.add(toolMenuItem("Reconstruir autocompletado",IconFactory.Kind.REFRESH,0,0,()->{queryPanel.refreshCompletions();setStatus(tr("Autocompletado actualizado."));}));

        JMenu reporting=UiLanguage.text(new JMenu(),UiLanguage.key("Reportería"));
        reporting.add(toolMenuItem("Diseñador de reportes",IconFactory.Kind.TABLE,0,0,this::openReports));
        JMenu ai=UiLanguage.text(new JMenu(),UiLanguage.key("Inteligencia artificial"));
        ai.add(toolMenuItem("Asistente de IA",IconFactory.Kind.SQL,0,0,this::openAi));
        query.add(toolMenuItem("Plan de ejecución",IconFactory.Kind.DIAGRAM,KeyEvent.VK_L,KeyEvent.CTRL_DOWN_MASK,this::openPlan));

        JMenu help = UiLanguage.text(new JMenu(tr("Ayuda")), UiLanguage.key("Ayuda"));
        JMenu view = UiLanguage.text(new JMenu(com.nexodb.util.I18n.tr("Preferencias")), UiLanguage.key("Preferencias"));
        JMenuItem preferences = UiLanguage.text(new JMenuItem(com.nexodb.util.I18n.tr("Idioma y apariencia…"), IconFactory.of(IconFactory.Kind.SETTINGS)), UiLanguage.key("Idioma y apariencia…"));
        preferences.addActionListener(e -> PreferencesDialog.show(this));
        view.add(preferences);
        help.setIcon(IconFactory.of(IconFactory.Kind.INFO, 16));
        JMenuItem updates = UiLanguage.text(new JMenuItem(tr("Buscar actualizaciones…")), UiLanguage.key("Buscar actualizaciones…"));
        JMenuItem updateChannel = UiLanguage.text(new JMenuItem(tr("Canal de actualizaciones (administrador)…")), UiLanguage.key("Canal de actualizaciones (administrador)…"));
        JMenuItem about = UiLanguage.text(new JMenuItem(tr("Acerca de NexoDB Studio")), UiLanguage.key("Acerca de NexoDB Studio"));
        updates.setIcon(IconFactory.of(IconFactory.Kind.UPDATE));
        updateChannel.setIcon(IconFactory.of(IconFactory.Kind.SETTINGS));
        about.setIcon(IconFactory.of(IconFactory.Kind.INFO));
        updates.addActionListener(e -> checkForUpdates(true));
        updateChannel.addActionListener(e -> configureUpdateChannel());
        about.addActionListener(e -> showAbout());
        help.add(updates);
        help.add(updateChannel);
        help.addSeparator();
        help.add(about);
        help.add(toolMenuItem("Licencia de NexoDB…",IconFactory.Kind.KEY,0,0,()->LicenseGate.show(this)));
        bar.add(file);
        bar.add(edit);
        bar.add(database);
        bar.add(query);
        bar.add(comparison);
        bar.add(power);
        bar.add(reporting);
        bar.add(ai);
        bar.add(view);
        bar.add(help);
        return bar;
    }

    private JMenuItem toolMenuItem(String text,IconFactory.Kind icon,int key,int modifiers,Runnable action){JMenuItem item=UiLanguage.text(new JMenuItem(tr(text),IconFactory.of(icon,16)),UiLanguage.key(text));if(key!=0)item.setAccelerator(KeyStroke.getKeyStroke(key,modifiers));item.addActionListener(e->action.run());return item;}
    private void addToolTab(String title,JComponent panel){UiDesign.enhance(panel);workspaceTabs.addTab(title,IconFactory.of(panel instanceof VisualDatabasePanel?IconFactory.Kind.DIAGRAM:panel instanceof DataSearchPanel?IconFactory.Kind.SEARCH:panel instanceof ScheduledTasksPanel?IconFactory.Kind.EVENT:IconFactory.Kind.SQL,16),panel);int index=workspaceTabs.indexOfComponent(panel);workspaceTabs.setTabComponentAt(index,closableTab(title,panel));workspaceTabs.setSelectedComponent(panel);}
    private boolean requireDatabase(){if(!LicenseGate.ensure(this))return false;if(session==null||!session.isOpen()||queryPanel.activeDatabase().isBlank()){JOptionPane.showMessageDialog(this,tr("Conecta y selecciona una base de datos primero."));return false;}return true;}
    private void openQueryTool(String tool){
        if(tool.equals("reports")){openReports();return;}
        if(tool.equals("ai")){openAi();return;}
        if(tool.equals("plan")){openPlan();return;}
        if(!requireDatabase())return;JdbcSession origin=session;String database=queryPanel.activeDatabase();
        if(origin.profile().type()!=DatabaseType.MYSQL&&origin.profile().type()!=DatabaseType.MARIADB&&origin.profile().type()!=DatabaseType.H2){JOptionPane.showMessageDialog(this,tr("Las herramientas visuales de esta versión están disponibles para MySQL y MariaDB. El editor SQL sigue disponible para esta conexión."));return;}
        java.util.function.Consumer<String> open=sql->insertTemplate(origin,sql);
        if(tool.equals("search")){addToolTab(tr("Buscar datos")+" · "+database,new DataSearchPanel(origin,database,sql->{activateSession(origin);queryPanel.runSql(sql);workspaceTabs.setSelectedIndex(0);}));return;}
        boolean designer=tool.equals("designer");addToolTab(tr(designer?"Diseñador de esquemas":"Constructor visual de consultas")+" · "+database,new VisualDatabasePanel(origin,database,designer,open,item->openTable(origin,item,TableWorkbenchPanel.COLUMNS)));
    }
    private void openReports(){
        if(!LicenseGate.ensure(this))return;
        var sources=queryPanel.reportSources();
        var panel=new com.nexodb.reporting.ui.ReportDesignerPanel(queryPanel::reportSources,sources.isEmpty()?null:sources.get(0),
                (data,path,format)->DataExporter.write(new QueryResult(data.columns(),data.rows(),-1,0,data.limited()),path,
                        format==com.nexodb.reporting.ReportExporter.Format.XLSX?DataExporter.Format.XLSX:DataExporter.Format.CSV,"reporte"),this::setStatus);
        addToolTab(tr("Reportería"),panel);
    }
    private void openAi(){
        openAi(queryPanel.analysisSql(),"");
    }
    private void openAi(String input,String plan){
        if(!LicenseGate.ensure(this))return;JdbcSession origin=session;
        var panel=new AiAssistantPanel(input,queryPanel.aiContext(),sql->{if(origin!=null&&origin.isOpen())activateSession(origin);if(queryPanel.openNewQuery(sql))workspaceTabs.setSelectedIndex(0);});
        if(!plan.isBlank())panel.setPlanContext(plan);addToolTab(tr("Asistente de IA"),panel);
    }
    private void openPlan(){
        if(!LicenseGate.ensure(this))return;if(session==null||!session.isOpen()){JOptionPane.showMessageDialog(this,tr("Conecta primero una base de datos."));return;}JdbcSession origin=session;
        var panel=new ExecutionPlanPanel(origin,queryPanel.analysisSql(),busy->{queryPanel.setExternalBusy(busy);setBusy(busy);},sql->{if(origin.isOpen())activateSession(origin);if(queryPanel.openNewQuery(sql))workspaceTabs.setSelectedIndex(0);});
        panel.setAiHandler((query,rawPlan)->{if(origin.isOpen())activateSession(origin);openAi(query,rawPlan);});addToolTab(tr("Plan de ejecución"),panel);
    }
    private void openTasks(com.nexodb.db.ScheduledTasks.Kind kind){
        ScheduledTasksPanel panel=null;for(int i=1;i<workspaceTabs.getTabCount();i++)if(workspaceTabs.getComponentAt(i) instanceof ScheduledTasksPanel found){panel=found;break;}
        if(panel==null){panel=new ScheduledTasksPanel(scheduledTasks,profileStore::load,session==null?"":session.profile().id(),queryPanel.activeDatabase());addToolTab(tr("Tareas programadas"),panel);}else workspaceTabs.setSelectedComponent(panel);
        if(kind!=null)panel.create(kind);
    }
    private void importExternal(){
        if(!requireDatabase())return;Object[] options={tr("Archivo CSV / TSV"),tr("Otra tabla o conexión")};int choice=JOptionPane.showOptionDialog(this,tr("Origen de los datos:"),tr("Importar datos externos"),JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,options,options[0]);
        if(choice==1){chooseTableToCopy(session,new SchemaItem(SchemaItem.Kind.SCHEMA,queryPanel.activeDatabase(),null,queryPanel.activeDatabase(),""));return;}if(choice!=0)return;ConnectionProfile profile=session.profile();if(profile.readOnly()){JOptionPane.showMessageDialog(this,tr("La conexión es de solo lectura."));return;}
        if(profile.type()!=DatabaseType.MYSQL&&profile.type()!=DatabaseType.MARIADB){JOptionPane.showMessageDialog(this,tr("La importación CSV de esta versión está disponible para MySQL y MariaDB."));return;}
        char[] password=null;try{if(secretStore.has(profile.id()))password=secretStore.load(profile.id());else{JPasswordField field=new JPasswordField(24);if(JOptionPane.showConfirmDialog(this,new Object[]{tr("Contraseña para abrir una conexión independiente:"),field},tr("Importar datos"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;password=field.getPassword();}new CsvImportDialog(this,profile,password,queryPanel.activeDatabase()).setVisible(true);refreshConnectionObjects(session);}catch(Exception error){showError(tr("Importar datos"),error);}finally{if(password!=null)Arrays.fill(password,'\0');}
    }

    private void showAbout() {
        JTextPane details = new JTextPane();
        details.setContentType("text/html");
        details.setText("<html><body style='font-family:Segoe UI;font-size:11px'>"
                + "<b style='font-size:15px'>NexoDB Studio " + UpdateService.CURRENT_VERSION + "</b><br>"
                + tr("Cliente universal de bases de datos mediante JDBC.<br><br>")
                + tr("<b>Creador:</b> Alex Mite Salazar<br>")
                + tr("<b>Correo:</b> alexo8ec@hotmail.com<br><br>")
                + tr("MySQL y MariaDB incluyen sus controladores oficiales.<br>Las contraseñas no se guardan.")
                + "</body></html>");
        details.setEditable(false);
        details.setOpaque(false);
        details.setBorder(new EmptyBorder(8, 8, 8, 8));
        JOptionPane.showMessageDialog(this, details, tr("Acerca de NexoDB Studio"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void styleToolbarButton(AbstractButton button, IconFactory.Kind icon, String tooltip) {
        button.setIcon(IconFactory.of(icon));
        UiLanguage.tooltip(button, UiLanguage.caption(tooltip));
        button.setIconTextGap(7);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setMargin(new Insets(6, 10, 6, 10));
    }

    private void scheduleAutomaticUpdateCheck() {
        if (updateService.getManifestUrl().isBlank()) return;
        javax.swing.Timer timer = new javax.swing.Timer(5000, e -> checkForUpdates(false));
        timer.setRepeats(false);
        timer.start();
    }

    private void configureUpdateChannel() {
        JTextField url = new JTextField(updateService.getManifestUrl(), 48);
        url.putClientProperty("JTextField.placeholderText", "https://servidor.example/nexodb/update-manifest.json");
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel explanation = new JLabel(tr("<html><b>Las actualizaciones normales no requieren configurar nada.</b><br>")
                + tr("Esta opción es para quien publica NexoDB en un servidor propio. Debe apuntar directamente<br>")
                + tr("al archivo <code>update-manifest.json</code> mediante HTTPS.</html>"));
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel fieldLabel = UiLanguage.text(new JLabel(tr("Dirección del manifiesto:")), UiLanguage.key("Dirección del manifiesto:"));
        fieldLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        url.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel verification = UiLanguage.text(new JLabel(tr("<html>NexoDB comprobará la versión y la firma SHA-256 antes de instalar.</html>")), UiLanguage.key("<html>NexoDB comprobará la versión y la firma SHA-256 antes de instalar.</html>"));
        verification.setForeground(UIManager.getColor("Label.disabledForeground"));
        verification.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(explanation);
        panel.add(Box.createVerticalStrut(12));
        panel.add(fieldLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(url);
        panel.add(Box.createVerticalStrut(7));
        panel.add(verification);
        int answer = JOptionPane.showConfirmDialog(this, panel, tr("Canal de actualizaciones"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        try {
            updateService.setManifestUrl(url.getText());
            if (url.getText().isBlank()) {
                setStatus(new Object[]{UiLanguage.key("Canal predeterminado de actualizaciones restaurado")});
            } else {
                setStatus(new Object[]{UiLanguage.key("Canal de actualizaciones guardado")});
                checkForUpdates(true);
            }
        } catch (Exception error) {
            showError(tr("Canal no válido"), rootCause(error));
        }
    }

    private void checkForUpdates(boolean manual) {
        if(manual&&!LicenseGate.ensure(this)||!manual&&!LicenseGate.licensed())return;
        if (checkingUpdates) return;
        if (updateService.getManifestUrl().isBlank()) {
            if (manual) JOptionPane.showMessageDialog(this,
                    "Esta copia todavía no tiene un canal público de actualizaciones.\n\n"
                            + "El administrador debe publicar update-manifest.json y el ZIP en un servidor HTTPS, "
                            + "y generar NexoDB con esa dirección. La configuración manual está en Ayuda → "
                            + "Canal de actualizaciones (administrador).",
                    tr("Canal aún no publicado"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        setStatus(new Object[]{UiLanguage.key("Buscando actualizaciones…")});
        checkingUpdates = true;
        new SwingWorker<UpdateService.UpdateInfo, Void>() {
            @Override protected UpdateService.UpdateInfo doInBackground() throws Exception { return updateService.check(); }

            @Override protected void done() {
                try {
                    UpdateService.UpdateInfo update = get();
                    if (UpdateService.isNewer(update.version(), UpdateService.CURRENT_VERSION)) {
                        setStatus(new Object[]{UiLanguage.key("Actualización "), update.version(), UiLanguage.key(" disponible")});
                        offerUpdate(update);
                    } else {
                        setStatus(new Object[]{UiLanguage.key("NexoDB Studio está actualizado")});
                        if (manual) JOptionPane.showMessageDialog(MainFrame.this,
                                tr("Ya tienes la versión más reciente (") + UpdateService.CURRENT_VERSION + ").",
                                tr("Sin actualizaciones"), JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception error) {
                    setStatus(new Object[]{UiLanguage.key("No se pudo comprobar la actualización")});
                    if (manual) JOptionPane.showMessageDialog(MainFrame.this,
                            UpdateService.explainError(error), tr("No se pudo buscar actualizaciones"), JOptionPane.WARNING_MESSAGE);
                } finally {
                    checkingUpdates = false;
                }
            }
        }.execute();
    }

    private void offerUpdate(UpdateService.UpdateInfo update) {
        JTextArea notes = new JTextArea(update.notes().isBlank() ? tr("Nueva versión disponible.") : update.notes());
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setRows(6);
        notes.setColumns(46);
        notes.setBackground(UIManager.getColor("Panel.background"));
        JPanel panel = new JPanel(new BorderLayout(6, 8));
        panel.add(new JLabel("NexoDB Studio " + update.version() + tr(" está disponible.")), BorderLayout.NORTH);
        panel.add(new JScrollPane(notes), BorderLayout.CENTER);
        int answer = JOptionPane.showConfirmDialog(this, panel, tr("Actualización disponible"),
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) downloadAndInstall(update);
    }

    private void downloadAndInstall(UpdateService.UpdateInfo update) {
        if(!LicenseGate.ensure(this))return;
        JDialog progressDialog = new JDialog(this, tr("Descargando actualización"), true);
        JProgressBar progress = new JProgressBar(0, 100);
        progress.setStringPainted(true);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(18, 18, 18, 18));
        panel.add(new JLabel(tr("Descargando y verificando NexoDB Studio ") + update.version() + "…"), BorderLayout.NORTH);
        panel.add(progress, BorderLayout.CENTER);
        progressDialog.setContentPane(panel);
        progressDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        progressDialog.pack();
        progressDialog.setSize(Math.max(470, progressDialog.getWidth()), progressDialog.getHeight());
        progressDialog.setLocationRelativeTo(this);

        SwingWorker<Path, Void> download = new SwingWorker<>() {
            @Override protected Path doInBackground() throws Exception {
                return updateService.download(update, value -> SwingUtilities.invokeLater(() -> progress.setValue(value)));
            }

            @Override protected void done() {
                progressDialog.dispose();
                try {
                    Path zip = get();
                    if(!LicenseGate.ensure(MainFrame.this))return;
                    int install = JOptionPane.showConfirmDialog(MainFrame.this,
                            UpdateService.canInstallInPlace()
                                    ? tr("La actualización fue descargada y verificada.\n\n¿Instalarla ahora? NexoDB se cerrará y volverá a abrir.")
                                    : tr("La actualización fue descargada y verificada.\n\n¿Abrir el paquete con el instalador de tu sistema?"),
                            tr("Instalar actualización"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                    if (install == JOptionPane.YES_OPTION) {
                        if(!canCloseStoredWorkspaces()||!queryPanel.confirmDiscardResults()||!queryPanel.saveDraftBeforeClose())return;
                        updateService.launchInstaller(zip);
                        if (UpdateService.canInstallInPlace()) {
                            closeAllSessions();
                            dispose();
                            System.exit(0);
                        }
                    }
                } catch (Exception error) {
                    JOptionPane.showMessageDialog(MainFrame.this, UpdateService.explainError(error),
                            tr("No se pudo instalar la actualización"), JOptionPane.WARNING_MESSAGE);
                }
            }
        };
        download.execute();
        progressDialog.setVisible(true);
    }

    private void configureProfiles() {
        profileList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && profileList.getSelectedValue() != null) connectSelected();
            }
        });
        profileList.addListSelectionListener(e -> updateConnectionActions());
    }

    private void configureTree() {
        objectTree.setRootVisible(true);
        objectTree.setShowsRootHandles(true);
        objectTree.setRowHeight(25);
        objectTree.putClientProperty("Tree.wideSelection", true);
        objectTree.putClientProperty("Tree.selectionArc", 8);
        objectTree.setCellRenderer(new ObjectTreeRenderer());
        bindObjectKey(KeyEvent.VK_F4,"object-create",()->selectedStoredAction("create"));
        bindObjectKey(KeyEvent.VK_F6,"object-edit",()->selectedStoredAction("edit"));
        bindObjectKey(KeyEvent.VK_DELETE,"object-drop",()->selectedStoredAction("drop"));
        bindObjectKey(KeyEvent.VK_F2,"object-rename",()->selectedStoredAction("rename"));
        objectTree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                Object node = event.getPath().getLastPathComponent();
                if (node instanceof LazyNode lazy) loadNode(lazy);
            }
            @Override public void treeWillCollapse(TreeExpansionEvent event) {}
        });
        objectTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showTreeMenuIfNeeded(e); }
            @Override public void mouseReleased(MouseEvent e) { showTreeMenuIfNeeded(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                TreePath path = objectTree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                LazyNode node = (LazyNode) path.getLastPathComponent();
                SchemaItem item = node.item();
                JdbcSession target = node.nodeSession();
                if (item.kind() == SchemaItem.Kind.ROOT && target != null) activateSession(target);
                else if (item.kind() == SchemaItem.Kind.TABLE || item.kind() == SchemaItem.Kind.VIEW) openTable(target, item, item.kind()==SchemaItem.Kind.TABLE?TableWorkbenchPanel.COLUMNS:TableWorkbenchPanel.DATA);
                else if (isProgrammableObject(item.kind())) openDefinition(target, item);
            }
        });
    }

    private void loadProfiles() {
        profileModel.clear();
        for (ConnectionProfile profile : profileStore.load()) profileModel.addElement(profile);
        if (!profileModel.isEmpty()) profileList.setSelectedIndex(0);
    }

    private void loadProfilesAsync() {
        setStatus(new Object[]{UiLanguage.key("Cargando perfiles…")});
        setBusy(true);
        new SwingWorker<List<ConnectionProfile>, Void>() {
            @Override protected List<ConnectionProfile> doInBackground() { return profileStore.load(); }

            @Override protected void done() {
                profileModel.clear();
                try {
                    for (ConnectionProfile profile : get()) profileModel.addElement(profile);
                    if (!profileModel.isEmpty()) profileList.setSelectedIndex(0);
                    setStatus(new Object[]{UiLanguage.key("Listo")});
                } catch (Exception ex) {
                    setStatus(new Object[]{UiLanguage.key("No se pudieron cargar los perfiles; puedes crear uno nuevo.")});
                }
                updateConnectionActions();
                setBusy(false);
            }
        }.execute();
    }

    private void newConnection() {
        ConnectionDialog.Result answer = new ConnectionDialog(this, null, secretStore).showDialog();
        if (answer == null) return;
        ConnectionProfile saved = profileStore.save(answer.profile());
        char[] secret = persistSecret(saved, answer);
        loadProfiles();
        selectProfile(saved.id());
        if (answer.connect()) connect(saved, secret);
        else Arrays.fill(secret, '\0');
    }

    private void editSelected() {
        ConnectionProfile selected = profileList.getSelectedValue();
        if (selected == null) return;
        ConnectionDialog.Result answer = new ConnectionDialog(this, selected, secretStore).showDialog();
        if (answer == null) return;
        ConnectionProfile saved = profileStore.save(answer.profile());
        char[] secret = persistSecret(saved, answer);
        loadProfiles();
        selectProfile(saved.id());
        if (answer.connect()) connect(saved, secret);
        else Arrays.fill(secret, '\0');
    }

    private void deleteSelected() {
        ConnectionProfile selected = profileList.getSelectedValue();
        if (selected == null) return;
        if (sessions.containsKey(selected.id())) {
            JOptionPane.showMessageDialog(this, tr("Desconecta este perfil antes de eliminarlo."), tr("Conexión activa"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this, tr("¿Eliminar el perfil “") + selected.name() + "”?", tr("Eliminar perfil"), JOptionPane.YES_NO_OPTION);
        if (answer == JOptionPane.YES_OPTION) {
            profileStore.delete(selected);
            secretStore.delete(selected.id());
            loadProfiles();
        }
    }

    private void selectProfile(String id) {
        for (int i = 0; i < profileModel.size(); i++) {
            if (profileModel.get(i).id().equals(id)) {
                profileList.setSelectedIndex(i);
                break;
            }
        }
    }

    private void connectSelected() {
        ConnectionProfile profile = profileList.getSelectedValue();
        if (profile == null) {
            newConnection();
            return;
        }
        JdbcSession open = sessions.get(profile.id());
        if (open != null && open.isOpen()) {
            activateSession(open);
            setStatus(new Object[]{UiLanguage.key("Conexión activa: "), profile.name()});
            return;
        }
        if (profile.type() == DatabaseType.SQLITE) { connect(profile, new char[0]); return; }
        if (secretStore.has(profile.id())) {
            try {
                char[] protectedPassword = secretStore.load(profile.id());
                connect(profile, protectedPassword == null ? new char[0] : protectedPassword);
                return;
            } catch (Exception error) {
                showError(tr("Windows no pudo recuperar la contraseña protegida"), error);
            }
        }
        JPasswordField password = new JPasswordField(24);
        JCheckBox remember = UiLanguage.text(new JCheckBox(tr("Recordar de forma segura con Windows"), true), UiLanguage.key("Recordar de forma segura con Windows"));
        remember.setEnabled(SecretStore.isSupported());
        remember.setSelected(SecretStore.isSupported());
        if (!SecretStore.isSupported()) UiLanguage.text(remember, UiLanguage.key("Guardar contraseña no disponible en este sistema"));
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JLabel(tr("Contraseña para ") + profile.name() + ":"), BorderLayout.NORTH);
        panel.add(password, BorderLayout.CENTER);
        panel.add(remember, BorderLayout.SOUTH);
        int answer = JOptionPane.showConfirmDialog(this, panel, tr("Conectar"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) connect(profile, password.getPassword(), SecretStore.isSupported() && remember.isSelected());
    }

    private char[] persistSecret(ConnectionProfile profile, ConnectionDialog.Result answer) {
        char[] entered = answer.password();
        try {
            if (!answer.rememberPassword()) {
                secretStore.delete(profile.id());
                return entered;
            }
            if (entered.length > 0 || !secretStore.has(profile.id())) secretStore.save(profile.id(), entered);
            if (entered.length == 0 && secretStore.has(profile.id())) {
                char[] stored = secretStore.load(profile.id());
                if (stored != null) return stored;
            }
        } catch (Exception error) {
            showError(tr("No se pudo proteger la contraseña con Windows"), error);
        }
        return entered;
    }

    private void connect(ConnectionProfile profile, char[] password) {
        connect(profile, password, false);
    }

    private void connect(ConnectionProfile profile, char[] password, boolean rememberAfterSuccess) {
        char[] savedCandidate = rememberAfterSuccess ? Arrays.copyOf(password, password.length) : new char[0];
        setStatus(new Object[]{UiLanguage.key("Conectando a "), profile.name(), "…"});
        setBusy(true);
        connectButton.setEnabled(false);
        new SwingWorker<JdbcSession, Void>() {
            @Override protected JdbcSession doInBackground() throws Exception {
                try {
                    JdbcSession opened = JdbcSession.open(profile, password, value -> SwingUtilities.invokeLater(() -> {
                        busyBar.setIndeterminate(false);
                        busyBar.setStringPainted(true);
                        busyBar.setValue(value);
                        busyBar.setString(value + "%");
                        setStatus(value < 100
                                ? tr("Descargando adaptador oficial de ") + profile.type().displayName() + "…"
                                : tr("Adaptador verificado; conectando a ") + profile.name() + "…");
                    }));
                    if (!PostgresSupport.needsDatabaseSelection(profile)) return opened;
                    List<String> databases;
                    try (opened) {
                        databases = PostgresSupport.databases(opened.connection());
                    }
                    String[] selected = new String[1];
                    SwingUtilities.invokeAndWait(() -> selected[0] = DatabaseSelectionDialog.choose(
                            MainFrame.this, profile.username(), databases));
                    if (selected[0] == null) return null;
                    return JdbcSession.open(PostgresSupport.withDatabase(profile, selected[0]), password);
                }
                finally { Arrays.fill(password, '\0'); }
            }

            @Override protected void done() {
                try {
                    JdbcSession next = get();
                    if (next == null) {
                        setStatus(new Object[]{UiLanguage.key("Conexión cancelada: no se seleccionó una base de datos.")});
                        return;
                    }
                    JdbcSession previous = sessions.put(profile.id(), next);
                    if (previous != null && previous != next) {
                        removeConnectionNode(previous);
                        removeWorkspaceTabs(previous);
                        closeSpecificSession(previous);
                    }
                    if (rememberAfterSuccess) {
                        try { secretStore.save(profile.id(), savedCandidate); }
                        catch (Exception error) {
                            JOptionPane.showMessageDialog(MainFrame.this,
                                    tr("La conexión se abrió, pero Windows no pudo proteger la contraseña.\n") + rootCause(error).getMessage(),
                                    tr("Contraseña no guardada"), JOptionPane.WARNING_MESSAGE);
                        }
                    }
                    activateSession(next);
                    setStatus(new Object[]{UiLanguage.key("Conectado a "), next.connection().getMetaData().getDatabaseProductName()});
                    refreshConnectionObjects(next);
                } catch (Exception ex) {
                    showError(tr("No se pudo conectar"), rootCause(ex));
                    setStatus(new Object[]{UiLanguage.key("Error de conexión")});
                } finally {
                    Arrays.fill(savedCandidate, '\0');
                    busyBar.setIndeterminate(true);
                    busyBar.setStringPainted(false);
                    busyBar.setValue(0);
                    updateConnectionActions();
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void disconnect() {
        if (session == null) return;
        if(!queryPanel.confirmDiscardResults())return;
        JdbcSession closing = session;
        sessions.remove(closing.profile().id());
        closeSpecificSession(closing);
        removeConnectionNode(closing);
        removeWorkspaceTabs(closing);
        session = sessions.values().stream().findFirst().orElse(null);
        queryPanel.setSession(session);
        searchGeneration++;
        objectSearch.setText("");
        if (session == null) {
            objectRoot.removeAllChildren();
            objectModel.reload();
            setTitle("NexoDB Studio");
            setStatus(new Object[]{UiLanguage.key("Sin conexiones activas")});
        } else {
            activateSession(session);
            setStatus(new Object[]{UiLanguage.key("Conexión activa: "), session.profile().name()});
        }
        updateConnectionActions();
    }

    private void closeSession() {
        if (session == null) return;
        JdbcSession closing = session;
        sessions.remove(closing.profile().id());
        closeSpecificSession(closing);
        session = null;
    }

    private void closeSpecificSession(JdbcSession target) {
        try { target.close(); }
        catch (Exception e) { setStatus(new Object[]{UiLanguage.key("Conexión cerrada con advertencias")}); }
    }

    private LazyNode findConnectionNode(String profileId) {
        for (int i = 0; i < objectRoot.getChildCount(); i++) {
            Object child = objectRoot.getChildAt(i);
            if (child instanceof LazyNode node && node.nodeSession() != null
                    && node.nodeSession().profile().id().equals(profileId)) return node;
        }
        return null;
    }

    private void removeConnectionNode(JdbcSession target) {
        if (target == null) return;
        LazyNode node = findConnectionNode(target.profile().id());
        if (node != null) objectRoot.remove(node);
        objectRoot.setUserObject(new SchemaItem(SchemaItem.Kind.ROOT, "", "",
                sessions.isEmpty() ? tr("Conexiones activas") : tr("Conexiones activas (") + sessions.size() + ")", ""));
        objectModel.reload(objectRoot);
        objectTree.expandRow(0);
    }

    private void removeWorkspaceTabs(JdbcSession target) {
        for (int i = workspaceTabs.getTabCount() - 1; i >= 1; i--) {
            Component component = workspaceTabs.getComponentAt(i);
            if (component instanceof TableWorkbenchPanel panel && panel.session() == target) workspaceTabs.remove(i);
        }
    }

    private void closeAllSessions() {
        scheduledTasks.close();
        for (JdbcSession open : new ArrayList<>(sessions.values())) closeSpecificSession(open);
        sessions.clear();
        session = null;
    }

    private void activateSession(JdbcSession target) {
        if (target == null || !target.isOpen()) return;
        boolean changed = session != target;
        session = target;
        if (changed) {
            searchGeneration++;
            objectSearch.setText("");
            queryPanel.setSession(target);
        }
        selectProfile(target.profile().id());
        setTitle("NexoDB Studio — " + target.profile().name());
        updateConnectionActions();
    }

    private void refreshObjects() {
        if (session == null) return;
        queryPanel.refreshCompletions();
        refreshConnectionObjects(session);
    }

    private void refreshConnectionObjects(JdbcSession target) {
        if (target == null || !target.isOpen()) return;
        LazyNode connectionNode = findConnectionNode(target.profile().id());
        if (connectionNode == null) {
            connectionNode = new LazyNode(new SchemaItem(SchemaItem.Kind.ROOT, "", "", target.profile().name(),
                    target.profile().host() + (target.profile().port() > 0 ? ":" + target.profile().port() : "")), true, target);
            objectRoot.add(connectionNode);
            objectModel.reload(objectRoot);
        }
        LazyNode finalConnectionNode = connectionNode;
        finalConnectionNode.removeAllChildren();
        finalConnectionNode.add(new LazyNode(new SchemaItem(SchemaItem.Kind.COLUMN, "", "", tr("Cargando objetos…"), ""), true, target));
        objectModel.reload(finalConnectionNode);
        setBusy(true);
        new SwingWorker<List<SchemaItem>, Void>() {
            @Override protected List<SchemaItem> doInBackground() throws Exception { return loadSchemas(target); }
            @Override protected void done() {
                try {
                    if (sessions.get(target.profile().id()) != target) return;
                    List<SchemaItem> schemas = get();
                    finalConnectionNode.removeAllChildren();
                    for (SchemaItem schema : schemas) {
                        LazyNode schemaNode = new LazyNode(schema, true, target);
                        for (SchemaItem.Kind kind : List.of(SchemaItem.Kind.TABLES, SchemaItem.Kind.VIEWS,
                                SchemaItem.Kind.PROCEDURES, SchemaItem.Kind.FUNCTIONS,
                                SchemaItem.Kind.TRIGGERS, SchemaItem.Kind.EVENTS)) {
                            LazyNode group = new LazyNode(new SchemaItem(kind, schema.catalog(), schema.schema(), groupLabel(kind), ""), false, target);
                            group.add(placeholder(target));
                            schemaNode.add(group);
                        }
                        finalConnectionNode.add(schemaNode);
                    }
                    objectRoot.setUserObject(new SchemaItem(SchemaItem.Kind.ROOT, "", "", tr("Conexiones activas (") + sessions.size() + ")", ""));
                    objectModel.reload(finalConnectionNode);
                    objectTree.expandRow(0);
                    objectTree.expandPath(new TreePath(finalConnectionNode.getPath()));
                    setStatus(new Object[]{(target.profile().name()), " · ", schemas.size(), UiLanguage.key(" esquemas cargados")});
                } catch (Exception ex) {
                    finalConnectionNode.removeAllChildren();
                    finalConnectionNode.add(new LazyNode(new SchemaItem(SchemaItem.Kind.COLUMN, "", "", tr("Error al cargar objetos"), ""), true, target));
                    objectModel.reload(finalConnectionNode);
                    showError(tr("No se pudo leer el catálogo"), rootCause(ex));
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void searchObjects() {
        if (session == null || !session.isOpen()) return;
        String query = objectSearch.getText().trim();
        if (query.isBlank()) return;
        String database = queryPanel.activeDatabase();
        if (database.isBlank()) {
            searchResultModel.clear();
            searchResultModel.addElement(new SchemaItem(SchemaItem.Kind.TABLE, "", "", "Selecciona una base", ""));
            setStatus(new Object[]{UiLanguage.key("Selecciona una base antes de buscar objetos")});
            return;
        }
        int generation = ++searchGeneration;
        searchResultModel.clear();
        searchResultModel.addElement(new SchemaItem(SchemaItem.Kind.TABLE, "", "", tr("Buscando…"), ""));
        setBusy(true);
        new SwingWorker<List<SchemaItem>, Void>() {
            @Override protected List<SchemaItem> doInBackground() throws Exception {
                DatabaseMetaData metadata = session.connection().getMetaData();
                String escape = metadata.getSearchStringEscape();
                String pattern = query;
                if (escape != null && !escape.isEmpty()) {
                    pattern = pattern.replace(escape, escape + escape).replace("%", escape + "%").replace("_", escape + "_");
                }
                List<SchemaItem> matches = new ArrayList<>();
                boolean catalogMode = session.profile().type() == DatabaseType.MYSQL || session.profile().type() == DatabaseType.MARIADB;
                boolean postgres = session.profile().type() == DatabaseType.POSTGRESQL;
                String catalog = catalogMode || postgres ? database : null;
                String schema = catalogMode || postgres ? null : database;
                try (ResultSet rs = metadata.getTables(catalog, schema, "%" + pattern + "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next() && matches.size() < 300) {
                        String name = rs.getString("TABLE_NAME");
                        if (!containsIgnoreCase(name, query)) continue;
                        String type = rs.getString("TABLE_TYPE");
                        SchemaItem.Kind kind = "VIEW".equalsIgnoreCase(type) ? SchemaItem.Kind.VIEW : SchemaItem.Kind.TABLE;
                        matches.add(new SchemaItem(kind, rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), name,
                                kind == SchemaItem.Kind.VIEW ? tr("Vista") : tr("Tabla")));
                    }
                }
                try (ResultSet rs = metadata.getProcedures(catalog, schema, "%" + pattern + "%")) {
                    while (rs.next() && matches.size() < 300) {
                        String name = rs.getString("PROCEDURE_NAME");
                        if (containsIgnoreCase(name, query)) matches.add(new SchemaItem(SchemaItem.Kind.PROCEDURE,
                                rs.getString("PROCEDURE_CAT"), rs.getString("PROCEDURE_SCHEM"), name, tr("Procedimiento almacenado")));
                    }
                } catch (SQLException ignored) {
                }
                try (ResultSet rs = metadata.getFunctions(catalog, schema, "%" + pattern + "%")) {
                    while (rs.next() && matches.size() < 300) {
                        String name = rs.getString("FUNCTION_NAME");
                        if (containsIgnoreCase(name, query)) matches.add(new SchemaItem(SchemaItem.Kind.FUNCTION,
                                rs.getString("FUNCTION_CAT"), rs.getString("FUNCTION_SCHEM"), name, tr("Función")));
                    }
                } catch (SQLException ignored) {
                }
                if (catalogMode && matches.size() < 300) {
                    String sql = "SELECT TRIGGER_NAME, 'TRIGGER' FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? "
                            + "UNION ALL SELECT EVENT_NAME, 'EVENT' FROM information_schema.EVENTS WHERE EVENT_SCHEMA=?";
                    try (PreparedStatement statement = session.connection().prepareStatement(sql)) {
                        statement.setString(1, database);
                        statement.setString(2, database);
                        try (ResultSet rs = statement.executeQuery()) {
                            while (rs.next() && matches.size() < 300) {
                                String name = rs.getString(1);
                                if (!containsIgnoreCase(name, query)) continue;
                                SchemaItem.Kind kind = "TRIGGER".equals(rs.getString(2)) ? SchemaItem.Kind.TRIGGER : SchemaItem.Kind.EVENT;
                                matches.add(new SchemaItem(kind, database, null, name,
                                        kind == SchemaItem.Kind.TRIGGER ? "Trigger" : tr("Evento")));
                            }
                        }
                    }
                }
                matches.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                return matches;
            }

            @Override protected void done() {
                try {
                    if (generation != searchGeneration) return;
                    searchResultModel.clear();
                    for (SchemaItem item : get()) searchResultModel.addElement(item);
                    if (searchResultModel.isEmpty()) {
                        searchResultModel.addElement(new SchemaItem(SchemaItem.Kind.TABLE, "", "", tr("Sin resultados"), query));
                    }
                    setStatus(new Object[]{(searchResultModel.size()), UiLanguage.key(" coincidencias en "), database, UiLanguage.key(" para “"), query, "”"});
                } catch (Exception error) {
                    if (generation == searchGeneration) {
                        searchResultModel.clear();
                        searchResultModel.addElement(new SchemaItem(SchemaItem.Kind.TABLE, "", "", tr("Error al buscar"), rootCause(error).getMessage()));
                    }
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT));
    }

    private List<SchemaItem> loadSchemas(JdbcSession target) throws SQLException {
        if (target.profile().type() == DatabaseType.SQLITE) return List.of(new SchemaItem(SchemaItem.Kind.SCHEMA, null, null, "main", target.profile().database()));
        DatabaseMetaData metadata = target.connection().getMetaData();
        List<SchemaItem> schemas = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (ResultSet rs = metadata.getSchemas()) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String catalog;
                try { catalog = rs.getString("TABLE_CATALOG"); } catch (SQLException ignored) { catalog = target.connection().getCatalog(); }
                String key = String.valueOf(catalog) + "|" + schema;
                if (seen.add(key)) schemas.add(new SchemaItem(SchemaItem.Kind.SCHEMA, catalog, schema, schema, catalog == null ? "" : "[" + catalog + "]"));
            }
        }
        if (schemas.isEmpty()) {
            String requestedDatabase = target.profile().database();
            try (ResultSet rs = metadata.getCatalogs()) {
                while (rs.next()) {
                    String catalog = rs.getString("TABLE_CAT");
                    if (catalog == null || catalog.isBlank()) continue;
                    if (!requestedDatabase.isBlank() && !catalog.equalsIgnoreCase(requestedDatabase)) continue;
                    if (seen.add(catalog + "|")) {
                        schemas.add(new SchemaItem(SchemaItem.Kind.SCHEMA, catalog, null, catalog, ""));
                    }
                }
            }
            if (schemas.isEmpty() && !requestedDatabase.isBlank()) {
                schemas.add(new SchemaItem(SchemaItem.Kind.SCHEMA, requestedDatabase, null, requestedDatabase, ""));
            }
        }
        if (schemas.isEmpty()) {
            String catalog = target.connection().getCatalog();
            String schema;
            try { schema = target.connection().getSchema(); } catch (SQLException ignored) { schema = null; }
            String name = schema != null && !schema.isBlank() ? schema : catalog != null && !catalog.isBlank() ? catalog : tr("Base de datos");
            schemas.add(new SchemaItem(SchemaItem.Kind.SCHEMA, catalog, schema, name, ""));
        }
        schemas.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return schemas;
    }

    private void loadNode(LazyNode node) {
        JdbcSession target = node.nodeSession();
        if (node.loaded || target == null || !target.isOpen()) return;
        node.loaded = true;
        setBusy(true);
        SchemaItem item = node.item();
        new SwingWorker<List<SchemaItem>, Void>() {
            @Override protected List<SchemaItem> doInBackground() throws Exception {
                if (item.kind() == SchemaItem.Kind.TABLES) return loadTables(target, item, "TABLE", SchemaItem.Kind.TABLE);
                if (item.kind() == SchemaItem.Kind.VIEWS) return loadTables(target, item, "VIEW", SchemaItem.Kind.VIEW);
                if (item.kind() == SchemaItem.Kind.PROCEDURES) return loadRoutines(target, item, false);
                if (item.kind() == SchemaItem.Kind.FUNCTIONS) return loadRoutines(target, item, true);
                if (item.kind() == SchemaItem.Kind.TRIGGERS) return loadInformationSchemaObjects(target, item, true);
                if (item.kind() == SchemaItem.Kind.EVENTS) return loadInformationSchemaObjects(target, item, false);
                if (item.kind() == SchemaItem.Kind.TABLE || item.kind() == SchemaItem.Kind.VIEW) return loadColumns(target, item);
                return List.of();
            }

            @Override protected void done() {
                try {
                    node.removeAllChildren();
                    for (SchemaItem child : get()) {
                        boolean lazy = child.kind() == SchemaItem.Kind.TABLE || child.kind() == SchemaItem.Kind.VIEW;
                        LazyNode childNode = new LazyNode(child, !lazy, target);
                        if (lazy) childNode.add(placeholder(target));
                        node.add(childNode);
                    }
                    objectModel.reload(node);
                } catch (Exception ex) {
                    node.loaded = false;
                    showError(tr("No se pudieron cargar los objetos"), rootCause(ex));
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private List<SchemaItem> loadTables(JdbcSession target, SchemaItem parent, String tableType, SchemaItem.Kind kind) throws SQLException {
        List<SchemaItem> items = new ArrayList<>();
        try (ResultSet rs = target.connection().getMetaData().getTables(parent.catalog(), parent.schema(), "%", new String[]{tableType})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                String remarks = rs.getString("REMARKS");
                items.add(new SchemaItem(kind, rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), name, remarks));
            }
        }
        items.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return items;
    }

    private List<SchemaItem> loadRoutines(JdbcSession target, SchemaItem parent, boolean functions) throws SQLException {
        List<SchemaItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        DatabaseMetaData metadata = target.connection().getMetaData();
        if (functions) {
            try (ResultSet rs = metadata.getFunctions(parent.catalog(), parent.schema(), "%")) {
                while (rs.next()) {
                    String name = rs.getString("FUNCTION_NAME");
                    if (name != null && seen.add(name)) items.add(new SchemaItem(SchemaItem.Kind.FUNCTION,
                            rs.getString("FUNCTION_CAT"), rs.getString("FUNCTION_SCHEM"), name, rs.getString("REMARKS")));
                }
            }
        } else {
            try (ResultSet rs = metadata.getProcedures(parent.catalog(), parent.schema(), "%")) {
                while (rs.next()) {
                    String name = rs.getString("PROCEDURE_NAME");
                    if (name != null && seen.add(name)) items.add(new SchemaItem(SchemaItem.Kind.PROCEDURE,
                            rs.getString("PROCEDURE_CAT"), rs.getString("PROCEDURE_SCHEM"), name, rs.getString("REMARKS")));
                }
            }
        }
        items.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return items;
    }

    private List<SchemaItem> loadInformationSchemaObjects(JdbcSession target, SchemaItem parent, boolean triggers) throws SQLException {
        if (target.profile().type() != DatabaseType.MYSQL && target.profile().type() != DatabaseType.MARIADB) return List.of();
        String database = parent.catalog() != null && !parent.catalog().isBlank() ? parent.catalog() : parent.schema();
        if (database == null || database.isBlank()) return List.of();
        String sql = triggers
                ? "SELECT TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? ORDER BY TRIGGER_NAME"
                : "SELECT EVENT_NAME, STATUS, EVENT_TYPE FROM information_schema.EVENTS WHERE EVENT_SCHEMA=? ORDER BY EVENT_NAME";
        List<SchemaItem> items = new ArrayList<>();
        try (PreparedStatement statement = target.connection().prepareStatement(sql)) {
            statement.setString(1, database);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String detail = triggers ? rs.getString(2) + " · " + rs.getString(3) : rs.getString(2) + " · " + rs.getString(3);
                    items.add(new SchemaItem(triggers ? SchemaItem.Kind.TRIGGER : SchemaItem.Kind.EVENT,
                            database, null, name, detail));
                }
            }
        }
        return items;
    }

    private List<SchemaItem> loadColumns(JdbcSession target, SchemaItem table) throws SQLException {
        List<SchemaItem> items = new ArrayList<>();
        try (ResultSet rs = target.connection().getMetaData().getColumns(table.catalog(), table.schema(), table.name(), "%")) {
            while (rs.next()) {
                String type = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                String nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls ? " NOT NULL" : "";
                String detail = type + (size > 0 ? "(" + size + ")" : "") + nullable;
                items.add(new SchemaItem(SchemaItem.Kind.COLUMN, table.catalog(), table.schema(), rs.getString("COLUMN_NAME"), detail));
            }
        }
        return items;
    }

    private void showTreeMenuIfNeeded(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        TreePath path = objectTree.getPathForLocation(event.getX(), event.getY());
        if (path == null) return;
        objectTree.setSelectionPath(path);
        LazyNode node = (LazyNode) path.getLastPathComponent();
        JPopupMenu menu=objectMenu(node);
        if(menu.getComponentCount()>0)menu.show(objectTree,event.getX(),event.getY());
    }

    private JPopupMenu objectMenu(LazyNode node){
        SchemaItem item = node.item();
        JdbcSession target = node.nodeSession();
        JPopupMenu menu = new JPopupMenu();
        if(target!=null&&StoredObjectService.supported(target.profile().type())&&StoredObjectService.objectKind(item.kind())!=null){storedObjectMenu(menu,node);return menu;}
        if (item.kind() == SchemaItem.Kind.TABLE || item.kind() == SchemaItem.Kind.VIEW) {
            addMenu(menu, tr("Abrir datos"), IconFactory.Kind.TABLE, e -> openTable(target, item, TableWorkbenchPanel.DATA));
            addMenu(menu, tr("Ver estructura"), IconFactory.Kind.COLUMN, e -> openTable(target, item, TableWorkbenchPanel.COLUMNS));
            addMenu(menu, tr("Ver índices"), IconFactory.Kind.KEY, e -> openTable(target, item, TableWorkbenchPanel.INDEXES));
            addMenu(menu, tr("Relaciones / claves foráneas"), IconFactory.Kind.COMPARE, e -> openTable(target, item, TableWorkbenchPanel.FOREIGN_KEYS));
            addMenu(menu, tr("Ver SQL / DDL"), IconFactory.Kind.SQL, e -> openTable(target, item, TableWorkbenchPanel.DDL));
            menu.addSeparator();
            addMenu(menu, tr("Copiar tabla a otra base…"), IconFactory.Kind.COMPARE, e -> copyTable(target, item));
            addMenu(menu, tr("Exportar datos…"), IconFactory.Kind.EXPORT, e -> exportTable(target, item));
            addMenu(menu,tr("Respaldo SQL…"),IconFactory.Kind.EXPORT,e->transferSql(false,target,item));
            addMenu(menu,tr("Importar / restaurar SQL…"),IconFactory.Kind.DATABASE,e->transferSql(true,target,item));
            menu.addSeparator();
            addMenu(menu, "SELECT en el editor", IconFactory.Kind.PLAY, e -> insertSelect(target, item));
            if (item.kind() == SchemaItem.Kind.TABLE) {
                addMenu(menu, tr("Preparar INSERT"), IconFactory.Kind.ADD, e -> insertTemplate(target, "INSERT INTO " + qualifiedName(target, item) + " (...)\nVALUES (...);\n"));
                addMenu(menu, tr("Preparar UPDATE"), IconFactory.Kind.EDIT, e -> insertTemplate(target, "UPDATE " + qualifiedName(target, item) + "\nSET columna = valor\nWHERE condicion;\n"));
                addMenu(menu, tr("Preparar DELETE"), IconFactory.Kind.DELETE, e -> insertTemplate(target, "DELETE FROM " + qualifiedName(target, item) + "\nWHERE condicion;\n"));
            }
            menu.addSeparator();
            addMenu(menu, tr("Copiar nombre completo"), IconFactory.Kind.SQL, e -> copyText(qualifiedName(target, item)));
        } else if (isProgrammableObject(item.kind())) {
            addMenu(menu, tr("Abrir definición SQL"), IconFactory.Kind.SQL, e -> openDefinition(target, item));
            if (item.kind() == SchemaItem.Kind.PROCEDURE) {
                addMenu(menu, tr("Preparar CALL"), IconFactory.Kind.PLAY, e -> insertTemplate(target, "CALL " + qualifiedName(target, item) + "();\n"));
            } else if (item.kind() == SchemaItem.Kind.FUNCTION) {
                addMenu(menu, tr("Preparar SELECT"), IconFactory.Kind.PLAY, e -> insertTemplate(target, "SELECT " + qualifiedName(target, item) + "();\n"));
            }
            menu.addSeparator();
            addMenu(menu, tr("Copiar nombre completo"), IconFactory.Kind.SQL, e -> copyText(qualifiedName(target, item)));
        } else if (isGroup(item.kind())) {
            addMenu(menu, tr("Actualizar"), IconFactory.Kind.REFRESH, e -> refreshNode(node));
            if (item.kind() == SchemaItem.Kind.TABLES) {
                addMenu(menu, tr("Preparar CREATE TABLE"), IconFactory.Kind.ADD,
                        e -> insertTemplate(target, "CREATE TABLE " + qualifiedNamespace(target, item) + "nueva_tabla (\n    id INT NOT NULL PRIMARY KEY\n);\n"));
            }
        } else if (item.kind() == SchemaItem.Kind.SCHEMA) {
            addMenu(menu,tr("Respaldo SQL…"),IconFactory.Kind.EXPORT,e->transferSql(false,target,item));
            addMenu(menu,tr("Importar / restaurar SQL…"),IconFactory.Kind.DATABASE,e->transferSql(true,target,item));
            addMenu(menu, tr("Usar esta base en una consulta"), IconFactory.Kind.DATABASE,
                    e -> activateDatabase(target, item));
            addMenu(menu, tr("Copiar una tabla a otra base…"), IconFactory.Kind.COMPARE,
                    e -> chooseTableToCopy(target, item));
            addMenu(menu, tr("Actualizar objetos"), IconFactory.Kind.REFRESH, e -> refreshConnectionObjects(target));
        } else if (item.kind() == SchemaItem.Kind.ROOT && target != null) {
            addMenu(menu,tr("Respaldo SQL…"),IconFactory.Kind.EXPORT,e->transferSql(false,target,null));
            addMenu(menu,tr("Importar / restaurar SQL…"),IconFactory.Kind.DATABASE,e->transferSql(true,target,null));
            addMenu(menu, tr("Usar esta conexión"), IconFactory.Kind.CONNECT, e -> activateSession(target));
            addMenu(menu, tr("Actualizar objetos"), IconFactory.Kind.REFRESH, e -> refreshConnectionObjects(target));
        }
        return menu;
    }

    private void bindObjectKey(int key,String name,Runnable action){objectTree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key,0),name);objectTree.getActionMap().put(name,new AbstractAction(){public void actionPerformed(ActionEvent e){action.run();}});}
    private void selectedStoredAction(String action){Object selected=objectTree.getLastSelectedPathComponent();if(!(selected instanceof LazyNode node)||node.nodeSession()==null)return;JdbcSession target=node.nodeSession();SchemaItem item=node.item();var kind=StoredObjectService.objectKind(item.kind());if(kind==null||!StoredObjectService.supported(target.profile().type())||target.profile().readOnly())return;
        switch(action){case "create"->createStoredObject(target,item);case "edit"->{if(item.kind()==kind)editStoredObject(target,item);}case "drop"->{if(item.kind()==kind)dropStoredObject(target,item);}case "rename"->{if(item.kind()==kind&&(kind==SchemaItem.Kind.VIEW||kind==SchemaItem.Kind.EVENT))renameStoredObject(target,item);}}
    }
    private static String storedCaption(SchemaItem.Kind kind,String action){return switch(action){
        case "create"->switch(kind){case VIEW->"Crear vista…";case PROCEDURE->"Crear procedimiento…";case FUNCTION->"Crear función…";case TRIGGER->"Crear trigger…";case EVENT->"Crear evento…";default->"";};
        case "edit"->switch(kind){case VIEW->"Modificar vista…";case PROCEDURE->"Modificar procedimiento…";case FUNCTION->"Modificar función…";case TRIGGER->"Modificar trigger…";case EVENT->"Modificar evento…";default->"";};
        default->switch(kind){case VIEW->"Eliminar vista…";case PROCEDURE->"Eliminar procedimiento…";case FUNCTION->"Eliminar función…";case TRIGGER->"Eliminar trigger…";case EVENT->"Eliminar evento…";default->"";};};}
    private void storedObjectMenu(JPopupMenu menu,LazyNode node){
        SchemaItem item=node.item();JdbcSession target=node.nodeSession();SchemaItem.Kind kind=StoredObjectService.objectKind(item.kind());boolean selected=item.kind()==kind,writable=!target.profile().readOnly()&&target.isOpen();
        objectMenuEntry(menu,storedCaption(kind,"create"),"object-create",IconFactory.Kind.ADD,KeyEvent.VK_F4,writable,()->createStoredObject(target,item));
        objectMenuEntry(menu,storedCaption(kind,"edit"),"object-edit",IconFactory.Kind.EDIT,KeyEvent.VK_F6,writable&&selected,()->editStoredObject(target,item));
        objectMenuEntry(menu,storedCaption(kind,"drop"),"object-drop",IconFactory.Kind.DELETE,KeyEvent.VK_DELETE,writable&&selected,()->dropStoredObject(target,item));
        if(kind==SchemaItem.Kind.VIEW||kind==SchemaItem.Kind.EVENT||kind==SchemaItem.Kind.TRIGGER){menu.addSeparator();var rename=objectMenuEntry(menu,"Renombrar…","object-rename",IconFactory.Kind.EDIT,KeyEvent.VK_F2,writable&&selected&&kind!=SchemaItem.Kind.TRIGGER,()->renameStoredObject(target,item));if(kind==SchemaItem.Kind.TRIGGER)UiLanguage.tooltip(rename,UiLanguage.key("MySQL/MariaDB no permiten renombrar un trigger directamente; modifica su definición y créalo con otro nombre."));}
        menu.addSeparator();objectMenuEntry(menu,"Exportar definición…","object-export",IconFactory.Kind.EXPORT,0,selected,()->exportStoredObject(target,item));
        if(kind==SchemaItem.Kind.VIEW){objectMenuEntry(menu,"Ver datos","object-data",IconFactory.Kind.TABLE,0,selected,()->openTable(target,item,TableWorkbenchPanel.DATA));objectMenuEntry(menu,"Exportar datos…","object-export-data",IconFactory.Kind.EXPORT,0,selected,()->exportTable(target,item));}
        if(kind==SchemaItem.Kind.PROCEDURE){objectMenuEntry(menu,"Depurar procedimiento…","object-debug",IconFactory.Kind.PLAY,0,writable&&selected,()->debugStoredProcedure(target,item));if(selected)objectMenuEntry(menu,"Preparar CALL","object-call",IconFactory.Kind.SQL,0,true,()->insertTemplate(target,"CALL "+StoredObjectService.qualified(item)+"();\n"));}
        if(kind==SchemaItem.Kind.FUNCTION&&selected)objectMenuEntry(menu,"Preparar SELECT","object-select",IconFactory.Kind.SQL,0,true,()->insertTemplate(target,"SELECT "+StoredObjectService.qualified(item)+"();\n"));
        menu.addSeparator();objectMenuEntry(menu,"Actualizar","object-refresh",IconFactory.Kind.REFRESH,0,true,()->{if(isGroup(item.kind()))refreshNode(node);else if(node.getParent() instanceof LazyNode parent)refreshNode(parent);});
    }
    private JMenuItem objectMenuEntry(JPopupMenu menu,String caption,String name,IconFactory.Kind icon,int key,boolean enabled,Runnable action){JMenuItem entry=UiLanguage.text(new JMenuItem(tr(caption),IconFactory.of(icon,16)),UiLanguage.key(caption));entry.setName(name);entry.setEnabled(enabled);if(key!=0)entry.setAccelerator(KeyStroke.getKeyStroke(key,0));entry.addActionListener(e->action.run());menu.add(entry);return entry;}
    private void createStoredObject(JdbcSession target,SchemaItem parent){if(!LicenseGate.ensure(this))return;String name=JOptionPane.showInputDialog(this,tr("Nombre del nuevo objeto:"));if(name==null)return;try{StoredObjectService.quote(name);SchemaItem item=new SchemaItem(StoredObjectService.objectKind(parent.kind()),StoredObjectService.namespace(parent),null,name,"");addToolTab(tr("Crear objeto")+" · "+name,new StoredObjectEditorPanel(target,item,null,this::storedObjectsChanged));}catch(Exception error){showError(tr("Crear objeto"),error);}}
    private void storedObjectsChanged(JdbcSession target){if(target.isOpen()){refreshConnectionObjects(target);if(target==session)queryPanel.refreshCompletions();}}
    private void readStoredObject(JdbcSession target,SchemaItem item,java.util.function.Consumer<StoredObjectService.Definition> action){if(!LicenseGate.ensure(this)||!target.isOpen())return;setBusy(true);new SwingWorker<StoredObjectService.Definition,Void>(){protected StoredObjectService.Definition doInBackground()throws Exception{return StoredObjectService.read(target,item);}protected void done(){setBusy(false);try{action.accept(get());}catch(Exception error){showError(tr("No se pudo abrir la definición"),rootCause(error));}}}.execute();}
    private void editStoredObject(JdbcSession target,SchemaItem item){readStoredObject(target,item,definition->addToolTab(tr("Modificar objeto")+" · "+item.name(),new StoredObjectEditorPanel(target,item,definition,this::storedObjectsChanged)));}
    private void runStoredDdl(JdbcSession target,String sql){if(!LicenseGate.ensure(this))return;try{StoredObjectService.requireWritable(target);}catch(Exception error){showError(tr("Modificar objeto"),error);return;}if(!Confirmations.ask(this,"Modificar objeto",target.profile().name()+"\n\n"+sql+"\n\n"+tr("Esta operación modifica la estructura y no se puede deshacer con Rollback. ¿Continuar?")))return;setBusy(true);new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{StoredObjectService.apply(target,sql);return null;}protected void done(){setBusy(false);try{get();storedObjectsChanged(target);setStatus(tr("Objeto actualizado."));}catch(Exception error){showError(tr("No se pudo modificar el objeto"),rootCause(error));}}}.execute();}
    private void dropStoredObject(JdbcSession target,SchemaItem item){runStoredDdl(target,StoredObjectService.dropSql(item));}
    private void renameStoredObject(JdbcSession target,SchemaItem item){String name=JOptionPane.showInputDialog(this,tr("Nuevo nombre:"),item.name());if(name==null||name.equals(item.name()))return;try{runStoredDdl(target,StoredObjectService.renameSql(item,name));}catch(Exception error){showError(tr("Renombrar"),error);}}
    private void exportStoredObject(JdbcSession target,SchemaItem item){readStoredObject(target,item,definition->{JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File(item.name()+".sql"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();if(java.nio.file.Files.exists(path)&&!Confirmations.ask(this,"Reemplazar archivo",path.toString()))return;try{java.nio.file.Files.writeString(path,StoredObjectService.exportSql(definition));setStatus(tr("Definición exportada: ")+path.getFileName());}catch(Exception error){showError(tr("Exportar definición"),error);}});}
    private void debugStoredProcedure(JdbcSession target,SchemaItem item){readStoredObject(target,item,definition->{
        com.nexodb.debug.MysqlProcedure.compile(definition);char[] password=null;
        try{if(secretStore.has(target.profile().id()))password=secretStore.load(target.profile().id());else if(target.profile().authenticationMode()==com.nexodb.model.AuthenticationMode.WINDOWS)password=new char[0];else{JPasswordField field=new JPasswordField(24);if(JOptionPane.showConfirmDialog(this,new Object[]{tr("Contraseña para dos conexiones independientes de depuración:"),target.profile().name(),field},tr("Depurar procedimiento"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;password=field.getPassword();}addToolTab(tr("Depurar procedimiento")+" · "+item.name(),new StoredProcedureDebugPanel(target.profile(),password,item,definition,this::setStatus));}catch(Exception error){showError(tr("No se pudo iniciar el depurador"),error);}finally{if(password!=null)Arrays.fill(password,'\0');}
    });}

    private void addMenu(JPopupMenu menu, String text, IconFactory.Kind icon, ActionListener action) {
        JMenuItem entry = new JMenuItem(text, IconFactory.of(icon, 16));
        entry.addActionListener(action);
        menu.add(entry);
    }

    private void copyTable(JdbcSession source, SchemaItem table) {
        if (source == null || !source.isOpen()) return;
        new TableCopyDialog(this, source, table, new ArrayList<>(sessions.values()), () -> {
            for (JdbcSession open : new ArrayList<>(sessions.values())) if (open.isOpen()) refreshConnectionObjects(open);
        }).showDialog();
    }

    private void chooseTableToCopy(JdbcSession source, SchemaItem schema) {
        if (source == null || !source.isOpen()) return;
        setBusy(true);
        setStatus(new Object[]{UiLanguage.key("Cargando tablas de "), schema.name(), "…"});
        new SwingWorker<List<SchemaItem>, Void>() {
            @Override protected List<SchemaItem> doInBackground() throws Exception {
                return loadTables(source, schema, "TABLE", SchemaItem.Kind.TABLE);
            }
            @Override protected void done() {
                setBusy(false);
                try {
                    List<SchemaItem> tables = get();
                    if (tables.isEmpty()) {
                        JOptionPane.showMessageDialog(MainFrame.this, tr("La base no contiene tablas."), tr("Copiar tabla"), JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    SchemaItem selected = (SchemaItem) JOptionPane.showInputDialog(MainFrame.this,
                            tr("Selecciona la tabla que deseas copiar:"), tr("Copiar tabla a otra base"),
                            JOptionPane.PLAIN_MESSAGE, IconFactory.of(IconFactory.Kind.TABLE, 28), tables.toArray(), tables.get(0));
                    if (selected != null) copyTable(source, selected);
                } catch (Exception error) {
                    showError(tr("No se pudieron cargar las tablas"), rootCause(error));
                }
            }
        }.execute();
    }

    private void exportTable(JdbcSession target, SchemaItem table) {
        if (target == null || !target.isOpen()) return;
        JSpinner limit = new JSpinner(new SpinnerNumberModel(10000, 1, 1000000, 1000));
        JPanel options = new JPanel(new BorderLayout(8, 8));
        options.add(UiLanguage.text(new JLabel(tr("Máximo de filas que se exportarán:")), UiLanguage.key("Máximo de filas que se exportarán:")), BorderLayout.NORTH);
        options.add(limit, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, options, tr("Exportar ") + table.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        ExportChooser.Request request = ExportChooser.choose(this, table.name());
        if (request == null) return;
        int maxRows = ((Number) limit.getValue()).intValue();
        String qualified = qualifiedName(target, table);
        setBusy(true);
        setStatus(new Object[]{UiLanguage.key("Exportando "), table.name(), "…"});
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                QueryResult result = target.execute("SELECT * FROM " + qualified, maxRows);
                DataExporter.write(result, request.path(), request.format(), qualified);
                return result.rows().size();
            }
            @Override protected void done() {
                setBusy(false);
                try {
                    int rows = get();
                    setStatus(new Object[]{(request.format()), UiLanguage.key(" exportado · "), rows, UiLanguage.key(" filas · "), request.path().getFileName()});
                } catch (Exception error) {
                    showError(tr("No se pudo exportar la tabla"), rootCause(error));
                }
            }
        }.execute();
    }

    private TableWorkbenchPanel openTable(JdbcSession target, SchemaItem item, int section) {
        if (target == null || !target.isOpen()) return null;
        activateSession(target);
        for (int i = 1; i < workspaceTabs.getTabCount(); i++) {
            Component component = workspaceTabs.getComponentAt(i);
            if (component instanceof TableWorkbenchPanel panel && panel.session() == target && panel.item().equals(item)) {
                panel.selectSection(section);
                workspaceTabs.setSelectedIndex(i);
                return panel;
            }
        }
        TableWorkbenchPanel panel = new TableWorkbenchPanel(target, item, qualifiedName(target, item), this::setStatus);
        UiDesign.enhance(panel);
        panel.setStructureChanged(queryPanel::refreshCompletions);
        panel.selectSection(section);
        String tabTitle = target.profile().name() + " · " + item.name();
        workspaceTabs.addTab(tabTitle, IconFactory.of(item.kind() == SchemaItem.Kind.VIEW ? IconFactory.Kind.VIEW : IconFactory.Kind.TABLE, 16), panel);
        int index = workspaceTabs.indexOfComponent(panel);
        workspaceTabs.setTabComponentAt(index, closableTab(tabTitle, panel));
        workspaceTabs.setSelectedComponent(panel);
        return panel;
    }

    private Component closableTab(String title, Component component) {
        JPanel tab = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        tab.setOpaque(false);
        tab.add(new JLabel(title));
        JButton close = new JButton("×");
        close.setFocusable(false);
        close.setMargin(new Insets(0, 4, 0, 4));
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        UiLanguage.tooltip(close, UiLanguage.key("Cerrar pestaña"));
        close.addActionListener(e -> {if(component instanceof TableWorkbenchPanel table&&!table.canClose())return;if(component instanceof StoredObjectEditorPanel editor&&!editor.canClose())return;if(component instanceof StoredProcedureDebugPanel debug){if(!debug.canClose())return;debug.dispose();}if(component instanceof ExecutionPlanPanel plan&&!plan.canClose())return;if(component instanceof AiAssistantPanel ai)ai.dispose();if(component instanceof com.nexodb.reporting.ui.ReportDesignerPanel report){if(!report.canClose())return;report.dispose();}workspaceTabs.remove(component);});
        tab.add(close);
        return tab;
    }

    private void insertSelect(JdbcSession target, SchemaItem item) {
        insertTemplate(target, "SELECT *\nFROM " + qualifiedName(target, item) + ";\n");
    }

    private void transferSql(boolean importing,JdbcSession target,SchemaItem item){
        if(target==null||!target.isOpen()){JOptionPane.showMessageDialog(this,tr("Conecta primero una base de datos."));return;}
        ConnectionProfile profile=target.profile();
        if(!com.nexodb.db.SqlBackupService.supported(profile.type())){JOptionPane.showMessageDialog(this,tr("El respaldo y la restauración SQL completos están disponibles para MySQL y MariaDB."));return;}
        if(importing&&profile.readOnly()){JOptionPane.showMessageDialog(this,tr("Esta conexión es de solo lectura. Edita la conexión y desactiva Solo lectura antes de importar SQL."));return;}
        char[] password=null;
        try{
            if(secretStore.has(profile.id()))password=secretStore.load(profile.id());
            else if(profile.authenticationMode()==com.nexodb.model.AuthenticationMode.WINDOWS)password=new char[0];
            else{JPasswordField field=new JPasswordField(24);if(JOptionPane.showConfirmDialog(this,new Object[]{tr("Contraseña para abrir una conexión independiente:"),profile.name(),field},tr("Operación SQL"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;password=field.getPassword();}
            String selected=target==session?queryPanel.activeDatabase():java.util.Objects.toString(target.connection().getCatalog(),"");String table="";
            if(item!=null){if(item.kind()==SchemaItem.Kind.SCHEMA)selected=item.name();else if(item.catalog()!=null&&!item.catalog().isBlank())selected=item.catalog();if(item.kind()==SchemaItem.Kind.TABLE||item.kind()==SchemaItem.Kind.VIEW)table=item.name();}
            SqlTransferDialog dialog=new SqlTransferDialog(this,profile,password,importing,selected,table);
            dialog.setVisible(true);
            if(dialog.restored())refreshConnectionObjects(target);
        }catch(Exception error){showError(tr("Operación SQL"),error);}finally{if(password!=null)Arrays.fill(password,'\0');}
    }

    private void insertTemplate(JdbcSession target, String sql) {
        activateSession(target);
        insertTemplate(sql);
    }

    private void insertTemplate(String sql) {
        workspaceTabs.setSelectedIndex(0);
        queryPanel.insertSql(sql);
    }

    private void activateDatabase(JdbcSession target, SchemaItem item) {
        activateSession(target);
        String database = item.catalog() != null && !item.catalog().isBlank() ? item.catalog() : item.name();
        queryPanel.selectDatabase(database);
        workspaceTabs.setSelectedIndex(0);
        setStatus(new Object[]{UiLanguage.key("Base activa: "), database});
    }

    private void openDefinition(JdbcSession target, SchemaItem item) {
        if (target == null || !target.isOpen()) return;
        if(StoredObjectService.supported(target.profile().type())){editStoredObject(target,item);return;}
        activateSession(target);
        setStatus(new Object[]{UiLanguage.key("Leyendo definición de "), item.name(), "…"});
        setBusy(true);
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                String command = switch (item.kind()) {
                    case PROCEDURE -> "SHOW CREATE PROCEDURE ";
                    case FUNCTION -> "SHOW CREATE FUNCTION ";
                    case TRIGGER -> "SHOW CREATE TRIGGER ";
                    case EVENT -> "SHOW CREATE EVENT ";
                    default -> throw new IllegalArgumentException(tr("Este objeto no tiene una definición SQL disponible."));
                };
                QueryResult result = target.execute(command + qualifiedName(target, item), 1);
                if (result.rows().isEmpty()) return "-- No se encontró la definición de " + qualifiedName(target, item) + "\n";
                int createColumn = result.columns().size() - 1;
                for (int i = 0; i < result.columns().size(); i++) {
                    if (result.columns().get(i).toLowerCase().contains("create")) { createColumn = i; break; }
                }
                Object value = result.rows().get(0).get(createColumn);
                return value == null ? "-- Definición vacía\n" : value + ";\n";
            }

            @Override protected void done() {
                try {
                    insertTemplate(get());
                    setStatus(new Object[]{UiLanguage.key("Definición de "), item.name(), UiLanguage.key(" cargada")});
                } catch (Exception error) {
                    showError(tr("No se pudo leer la definición"), rootCause(error));
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void refreshNode(LazyNode node) {
        node.loaded = false;
        node.removeAllChildren();
        node.add(placeholder(node.nodeSession()));
        objectModel.reload(node);
        loadNode(node);
    }

    private String qualifiedName(JdbcSession target, SchemaItem item) {
        if(target!=null&&target.profile().type()==DatabaseType.SQLSERVER&&item.catalog()!=null&&!item.catalog().isBlank())
            return quoteIdentifier(target,item.catalog())+"."+(item.schema()==null||item.schema().isBlank()?"":quoteIdentifier(target,item.schema()))+"."+quoteIdentifier(target,item.name());
        String namespace;
        if (target != null && (target.profile().type() == DatabaseType.MYSQL || target.profile().type() == DatabaseType.MARIADB)) {
            namespace = item.catalog() != null && !item.catalog().isBlank() ? item.catalog() : item.schema();
        } else {
            namespace = item.schema() != null && !item.schema().isBlank() ? item.schema() : item.catalog();
        }
        return (namespace == null || namespace.isBlank() ? "" : quoteIdentifier(target, namespace) + ".") + quoteIdentifier(target, item.name());
    }

    private String qualifiedNamespace(JdbcSession target, SchemaItem item) {
        String namespace = item.catalog() != null && !item.catalog().isBlank() ? item.catalog() : item.schema();
        return namespace == null || namespace.isBlank() ? "" : quoteIdentifier(target, namespace) + ".";
    }

    private String quoteIdentifier(JdbcSession target, String name) {
        if (name == null || name.isBlank()) return "";
        try {
            String quote = target.connection().getMetaData().getIdentifierQuoteString();
            quote = quote == null ? "" : quote.trim();
            return quote.isBlank() ? name : quote + name.replace(quote, quote + quote) + quote;
        } catch (SQLException ignored) {
            return name;
        }
    }

    private static void copyText(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static boolean isProgrammableObject(SchemaItem.Kind kind) {
        return kind == SchemaItem.Kind.PROCEDURE || kind == SchemaItem.Kind.FUNCTION
                || kind == SchemaItem.Kind.TRIGGER || kind == SchemaItem.Kind.EVENT;
    }

    private static boolean isGroup(SchemaItem.Kind kind) {
        return kind == SchemaItem.Kind.TABLES || kind == SchemaItem.Kind.VIEWS
                || kind == SchemaItem.Kind.PROCEDURES || kind == SchemaItem.Kind.FUNCTIONS
                || kind == SchemaItem.Kind.TRIGGERS || kind == SchemaItem.Kind.EVENTS;
    }

    private static String groupLabel(SchemaItem.Kind kind) {
        return switch (kind) {
            case TABLES -> tr("Tablas");
            case VIEWS -> tr("Vistas");
            case PROCEDURES -> tr("Procedimientos almacenados");
            case FUNCTIONS -> tr("Funciones");
            case TRIGGERS -> "Triggers";
            case EVENTS -> tr("Eventos");
            default -> kind.name();
        };
    }

    private static String quote(String name, String quote, boolean addDot) {
        if (name == null || name.isBlank()) return "";
        String value = quote.isBlank() ? name : quote + name.replace(quote, quote + quote) + quote;
        return addDot ? value + "." : value;
    }

    private void resetObjectTree(String label) {
        objectRoot.removeAllChildren();
        objectRoot.setUserObject(new SchemaItem(SchemaItem.Kind.ROOT, "", "", label, ""));
        objectModel.reload();
    }

    private LazyNode placeholder(JdbcSession target) {
        return new LazyNode(new SchemaItem(SchemaItem.Kind.COLUMN, "", "", tr("Cargando…"), ""), true, target);
    }

    private void updateConnectionActions() {
        boolean connected = session != null && session.isOpen();
        ConnectionProfile selected = profileList.getSelectedValue();
        connectButton.setEnabled(selected != null && !sessions.containsKey(selected.id()));
        disconnectButton.setEnabled(connected);
        refreshButton.setEnabled(connected);
        compareButton.setEnabled(connected);
    }

    private void compareSchemas() {
        if (session == null || !session.isOpen()) {
            JOptionPane.showMessageDialog(this, tr("Conecta primero una base de datos."), tr("Comparar esquemas"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new SchemaCompareDialog(this, session, new ArrayList<>(sessions.values()), this::insertTemplate, schemaChoices).showDialog();
    }

    private void compareData() {
        if (session == null || !session.isOpen()) {
            JOptionPane.showMessageDialog(this, tr("Conecta primero una base de datos."), tr("Comparar datos"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new DataCompareDialog(this, session, new ArrayList<>(sessions.values()), this::insertTemplate, dataChoices).showDialog();
    }

    private void manageUsers() {
        if (session == null || !session.isOpen()) {
            JOptionPane.showMessageDialog(this, tr("Conecta primero una base de datos."), tr("Administrar usuarios"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new UserManagerDialog(this, session).showDialog();
    }

    private void setStatus(String text) { UiLanguage.text(status, UiLanguage.caption(text == null ? "" : text)); }
    private void setStatus(Object[] parts) { UiLanguage.text(status, parts); }

    private void setBusy(boolean busy) {
        Runnable update = () -> {
            busyDepth = Math.max(0, busyDepth + (busy ? 1 : -1));
            busyBar.setVisible(busyDepth > 0);
        };
        if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update);
    }

    private void exitApplication() {
        if(!canCloseStoredWorkspaces())return;
        for(Component component:workspaceTabs.getComponents())if(component instanceof TableWorkbenchPanel table&&!table.canClose())return;
        for(Component component:workspaceTabs.getComponents())if(component instanceof com.nexodb.reporting.ui.ReportDesignerPanel report&&!report.canClose())return;
        for(Component component:workspaceTabs.getComponents())if(component instanceof ExecutionPlanPanel plan&&!plan.canClose())return;
        if(!queryPanel.confirmDiscardResults())return;
        if(!queryPanel.saveDraftBeforeClose())return;
        closeAllSessions();
        for(Component component:workspaceTabs.getComponents())if(component instanceof StoredProcedureDebugPanel debug)debug.dispose();
        for(Component component:workspaceTabs.getComponents())if(component instanceof com.nexodb.reporting.ui.ReportDesignerPanel report)report.dispose();
        for(Component component:workspaceTabs.getComponents())if(component instanceof AiAssistantPanel ai)ai.dispose();
        dispose();
    }
    private boolean canCloseStoredWorkspaces(){for(Component component:workspaceTabs.getComponents()){if(component instanceof StoredObjectEditorPanel editor&&!editor.canClose())return false;if(component instanceof StoredProcedureDebugPanel debug&&!debug.canClose())return false;}return true;}

    private void showError(String title, Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.toString();
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private static final class LazyNode extends DefaultMutableTreeNode {
        private boolean loaded;
        private final JdbcSession nodeSession;
        LazyNode(SchemaItem item, boolean loaded) { this(item, loaded, null); }
        LazyNode(SchemaItem item, boolean loaded, JdbcSession nodeSession) {
            super(item);
            this.loaded = loaded;
            this.nodeSession = nodeSession;
        }
        SchemaItem item() { return (SchemaItem) getUserObject(); }
        JdbcSession nodeSession() { return nodeSession; }
    }

    private static final class ProfileRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            label.setBorder(new EmptyBorder(6, 9, 6, 9));
            label.setIcon(value instanceof ConnectionProfile profile
                    ? IconFactory.database(profile.type(), 28)
                    : IconFactory.of(IconFactory.Kind.DATABASE, 28));
            label.setIconTextGap(10);
            if (value instanceof ConnectionProfile profile) {
                String target = profile.host().isBlank() ? profile.database() : profile.host() + (profile.port() > 0 ? ":" + profile.port() : "");
                label.setText("<html><b>" + html(profile.name()) + "</b><br><span style='font-size:10px'>"
                        + html(profile.type().displayName()) + (target.isBlank() ? "" : " · " + html(target)) + "</span></html>");
            }
            return label;
        }

        private static String html(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static final class ObjectTreeRenderer extends DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                                 boolean leaf, int row, boolean focus) {
            JLabel label = (JLabel) super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            if (value instanceof LazyNode node) {
                if (isGroup(node.item().kind())) label.setText(groupLabel(node.item().kind()));
                if (node.item().kind() == SchemaItem.Kind.ROOT && node.nodeSession() == null) {
                    int count = node.getChildCount();
                    label.setText(tr("Conexiones activas") + (count == 0 ? "" : " (" + count + ")"));
                }
                if (node.item().kind() == SchemaItem.Kind.ROOT && node.nodeSession() != null) {
                    label.setIcon(IconFactory.database(node.nodeSession().profile().type(), 17));
                    label.setBorder(new EmptyBorder(2, 3, 2, 4));
                    return label;
                }
                IconFactory.Kind icon = switch (node.item().kind()) {
                    case ROOT -> IconFactory.Kind.DATABASE;
                    case SCHEMA -> IconFactory.Kind.SCHEMA;
                    case TABLES, TABLE -> IconFactory.Kind.TABLE;
                    case VIEWS, VIEW -> IconFactory.Kind.VIEW;
                    case PROCEDURES, PROCEDURE -> IconFactory.Kind.PROCEDURE;
                    case FUNCTIONS, FUNCTION -> IconFactory.Kind.FUNCTION;
                    case TRIGGERS, TRIGGER -> IconFactory.Kind.TRIGGER;
                    case EVENTS, EVENT -> IconFactory.Kind.EVENT;
                    case COLUMN -> IconFactory.Kind.COLUMN;
                };
                label.setIcon(IconFactory.of(icon, 17));
                label.setBorder(new EmptyBorder(2, 3, 2, 4));
            }
            return label;
        }
    }

    private static final class SearchResultRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            label.setBorder(new EmptyBorder(5, 7, 5, 7));
            if (value instanceof SchemaItem item) {
                IconFactory.Kind icon = switch (item.kind()) {
                    case VIEW -> IconFactory.Kind.VIEW;
                    case PROCEDURE -> IconFactory.Kind.PROCEDURE;
                    case FUNCTION -> IconFactory.Kind.FUNCTION;
                    case TRIGGER -> IconFactory.Kind.TRIGGER;
                    case EVENT -> IconFactory.Kind.EVENT;
                    default -> IconFactory.Kind.TABLE;
                };
                label.setIcon(IconFactory.of(icon, 18));
                String namespace = item.catalog() != null && !item.catalog().isBlank() ? item.catalog() : item.schema();
                label.setText("<html><b>" + html(item.name()) + "</b>"
                        + "<br><span>" + html(item.detail())
                        + (namespace == null || namespace.isBlank() ? "" : " · " + html(namespace)) + "</span></html>");
            }
            return label;
        }

        private static String html(String text) {
            return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}

