package com.nexodb.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.nexodb.db.JdbcSession;
import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import com.nexodb.util.AppSettings;
import com.nexodb.util.I18n;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class LanguageSwitchTest {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("nexodb-live-language-");
        System.setProperty("nexodb.settingsFile", folder.resolve("settings.properties").toString());
        try {
            I18n.initialize("es");
            FlatLightLaf.setup();
            SwingUtilities.invokeAndWait(() -> {
                try { verify(); } catch (Exception error) { throw new RuntimeException(error); }
            });
        } finally {
            Files.deleteIfExists(folder.resolve("settings.properties")); Files.delete(folder);
            System.clearProperty("nexodb.settingsFile"); I18n.initialize("es");
        }
        System.out.println("LanguageSwitchTest: OK (live switching, state and data preserved)");
    }

    private static void verify() throws Exception {
        QueryPanel query = new QueryPanel(value -> {}, busy -> {});
        SqlEditorPane editor = field(query, "editor", SqlEditorPane.class);
        String sql = "SELECT 'Guardar', 'Usuario', 'Datos';\n-- English / Español\n";
        editor.setText(sql);
        editor.select(7, 16);
        Object document = editor.getDocument();
        int start = editor.getSelectionStart(), end = editor.getSelectionEnd();
        AtomicInteger databaseCalls = new AtomicInteger();
        Connection connection = (Connection) Proxy.newProxyInstance(LanguageSwitchTest.class.getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> {
            databaseCalls.incrementAndGet(); throw new AssertionError("Language change called the database: " + m.getName());
        });
        ConnectionProfile profile = new ConnectionProfile("test", "Guardar", DatabaseType.MYSQL,
                "localhost", 3306, "Datos", "Usuario", "jdbc:mysql://localhost:3306/Datos", "", "", true);
        Constructor<JdbcSession> constructor = JdbcSession.class.getDeclaredConstructor(ConnectionProfile.class, Connection.class, URLClassLoader.class);
        constructor.setAccessible(true);
        JdbcSession session = constructor.newInstance(profile, connection, null);
        setField(query, "session", session);
        @SuppressWarnings("unchecked") JComboBox<String> database = field(query, "database", JComboBox.class);
        database.addItem("Seleccione una base…"); database.addItem("Datos"); database.setSelectedIndex(1);
        AtomicInteger selectionEvents = new AtomicInteger();
        database.addActionListener(event -> selectionEvents.incrementAndGet());
        JButton execute = field(query, "execute", JButton.class);
        AtomicInteger clicks = new AtomicInteger(); execute.addActionListener(event -> clicks.incrementAndGet());
        JPanel root = new JPanel(new BorderLayout()); root.add(query, BorderLayout.CENTER);
        JMenuBar menuBar = new JMenuBar();
        JMenu menu = UiLanguage.text(new JMenu(), UiLanguage.key("Archivo"));
        JMenuItem item = UiLanguage.text(new JMenuItem(), UiLanguage.key("Nueva conexión…"));
        menu.add(item); menuBar.add(menu); root.add(menuBar, BorderLayout.NORTH);
        JPanel data = new JPanel();
        JLabel name = new JLabel("Guardar"); data.add(name);
        JLabel status = UiLanguage.text(new JLabel(), UiLanguage.key("Conexión activa: "), "Guardar"); data.add(status);
        JTextField userInput = new JTextField("Usuario"); data.add(userInput);
        JTable results = new JTable(new DefaultTableModel(new Object[][]{{"Guardar", "Usuario"}}, new Object[]{"Nombre", "Datos"})); data.add(results);
        JTable metadata = UiLanguage.headers(new JTable(new DefaultTableModel(new Object[][]{{"Datos", "VARCHAR"}}, new Object[]{"Columna", "Tipo"})));
        metadata.moveColumn(0, 1); data.add(metadata);
        root.add(data, BorderLayout.SOUTH);
        JTabbedPane tabs = field(query, "outputTabs", JTabbedPane.class);
        JPanel userTable = new JPanel(); tabs.addTab("Datos", userTable);
        int selectedTab = tabs.getSelectedIndex();
        Class<?> tls = Class.forName("com.nexodb.ui.ConnectionDialog$TlsMode");
        Object automatic = tls.getEnumConstants()[0];
        equal("Automático (recomendado)", automatic.toString());

        // Use the actual preferences save path, not just translator initialization.
        PreferencesDialog.apply("en", "light", root);
        equal("Execute", execute.getText()); equal("File", menu.getText()); equal("New connection…", item.getText());
        equal("Messages", tabs.getTitleAt(0)); equal("Datos", tabs.getTitleAt(tabs.indexOfComponent(userTable)));
        equal("Active connection: Guardar", status.getText()); equal("Guardar", name.getText()); equal("Usuario", userInput.getText());
        equal("Nombre", results.getColumnModel().getColumn(0).getHeaderValue()); equal("Guardar", results.getValueAt(0,0));
        equal("Type", metadata.getColumnModel().getColumn(0).getHeaderValue()); equal("Column", metadata.getColumnModel().getColumn(1).getHeaderValue());
        equal("Automatic (recommended)", automatic.toString()); equal("en", AppSettings.get("language", ""));
        equal(sql, editor.getText()); same(document, editor.getDocument()); same(session, field(query, "session", JdbcSession.class));
        equal(start, editor.getSelectionStart()); equal(end, editor.getSelectionEnd()); equal("Datos", query.activeDatabase());
        equal(selectedTab, tabs.getSelectedIndex()); equal(0, clicks.get()); equal(0, selectionEvents.get()); equal(0, databaseCalls.get());

        PreferencesDialog.apply("es", "light", root);
        equal("Ejecutar", execute.getText()); equal("Archivo", menu.getText()); equal("Mensajes", tabs.getTitleAt(0));
        equal("Automático (recomendado)", automatic.toString()); equal("Conexión activa: Guardar", status.getText());
        equal(sql, editor.getText()); equal("Datos", query.activeDatabase()); equal(0, databaseCalls.get());
        PreferencesDialog.apply("system", "light", root);
        equal(I18n.resolved("system", Locale.getDefault()), I18n.locale().getLanguage());
        equal("system", AppSettings.get("language", "")); equal(sql, editor.getText()); equal(0, selectionEvents.get());
    }
    private static void equal(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError("Expected ["+a+"] got ["+b+"]"); }
    private static void same(Object a, Object b) { if (a != b) throw new AssertionError("An open document/session was replaced"); }
    private static <T> T field(Object target, String name, Class<T> type) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
    private static void setField(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value); }
}
