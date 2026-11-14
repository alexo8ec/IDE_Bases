package com.nexodb.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.nexodb.util.I18n;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.Objects;

/** Exercises the real application window and an already-created connection dialog. */
public final class LanguageWindowTest {
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("LanguageWindowTest: skipped (requires a graphical desktop)");
            return;
        }
        Path preferences = Files.createTempDirectory("nexodb-window-language-");
        System.setProperty("nexodb.settingsFile", preferences.resolve("settings.properties").toString());
        try {
            I18n.initialize("es"); FlatLightLaf.setup();
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = new MainFrame();
                equal(com.nexodb.util.ProductBrand.TITLE,frame.getTitle());
                if(frame.getIconImages().isEmpty())throw new AssertionError("AdisDB application icon missing");
                ConnectionDialog dialog = new ConnectionDialog(frame, null);
                try {
                    frame.setSize(1280, 780); frame.addNotify(); frame.validate();
                    dialog.addNotify(); dialog.validate();
                    QueryPanel query = field(frame, "queryPanel", QueryPanel.class);
                    SqlEditorPane editor = field(query, "editor", SqlEditorPane.class);
                    String sql = "SELECT 'Guardar', 'Usuario', 'Datos';\n-- This query stays open\n";
                    editor.setText(sql);
                    Object document = editor.getDocument();
                    JMenuBar menu = frame.getJMenuBar();
                    JButton connect = field(frame, "connectButton", JButton.class);
                    int listeners = connect.getActionListeners().length;
                    JTextField user = field(dialog, "username", JTextField.class); user.setText("Usuario");
                    equal("Archivo", menu.getMenu(0).getText());
                    JMenu preferencesMenu=menu(menu,"Preferencias"),powerMenu=menu(menu,"Herramientas avanzadas");
                    PreferencesDialog.apply("en", "light");
                    equal("File", menu.getMenu(0).getText()); equal("Preferences",preferencesMenu.getText());equal("Power tools",powerMenu.getText());
                    equal("Connect", connect.getText()); equal("New connection", dialog.getTitle()); equal("Usuario", user.getText());
                    JMenu help=menu(menu,"Help");boolean about=false;for(int i=0;i<help.getItemCount();i++){var entry=help.getItem(i);if(entry!=null&&entry.getText().equals("About AdisDB"))about=true;}if(!about)throw new AssertionError("AdisDB branding was not translated");
                    equal("Execute", field(query, "execute", JButton.class).getText());
                    equal(sql, editor.getText());
                    if (frame.getJMenuBar() != menu || field(frame, "queryPanel", QueryPanel.class) != query || editor.getDocument() != document) {
                        throw new AssertionError("Language switch rebuilt the open workspace");
                    }
                    equal(listeners, connect.getActionListeners().length);
                    render(frame, "language-en.png");
                    PreferencesDialog.apply("es", "light");
                    equal("Archivo", menu.getMenu(0).getText()); equal("Conectar", connect.getText()); equal("Nueva conexión", dialog.getTitle());
                    equal(sql, editor.getText()); equal(listeners, connect.getActionListeners().length);
                    render(frame, "language-es.png");
                } catch (Exception error) { throw new RuntimeException(error); }
                finally { dialog.dispose(); frame.dispose(); }
            });
        } finally {
            Files.deleteIfExists(preferences.resolve("settings.properties")); Files.delete(preferences);
            System.clearProperty("nexodb.settingsFile");
        }
        System.out.println("LanguageWindowTest: OK (real window and connection dialog translated in place)");
        System.exit(0);
    }
    private static void render(JFrame frame, String file) throws Exception {
        frame.validate();
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); frame.getRootPane().printAll(g); g.dispose();
        Files.createDirectories(Path.of("build/previews")); ImageIO.write(image, "png", Path.of("build/previews", file).toFile());
    }
    private static void equal(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError("Expected ["+a+"] got ["+b+"]"); }
    private static JMenu menu(JMenuBar bar,String title){for(int i=0;i<bar.getMenuCount();i++)if(bar.getMenu(i).getText().equals(title))return bar.getMenu(i);throw new AssertionError("Missing menu "+title);}
    private static <T> T field(Object target, String name, Class<T> type) throws Exception { Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
}
