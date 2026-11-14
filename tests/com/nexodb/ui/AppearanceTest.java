package com.nexodb.ui;

import com.formdev.flatlaf.*;
import com.nexodb.util.I18n;
import com.nexodb.util.AppSettings;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;

public final class AppearanceTest {
    public static void main(String[] args) throws Exception {
        Path preferences = Files.createTempDirectory("nexodb-settings-test-");
        System.setProperty("nexodb.settingsFile", preferences.resolve("settings.properties").toString());
        AppSettings.put("language", "en");
        AppSettings.put("theme", "dark");
        if (!AppSettings.get("language", "es").equals("en") || !AppSettings.get("theme", "light").equals("dark")) {
            throw new AssertionError("Preferences were not persisted");
        }
        if (!I18n.resolved("system", Locale.forLanguageTag("en-US")).equals("en")) throw new AssertionError();
        if (!I18n.resolved("system", Locale.forLanguageTag("es-EC")).equals("es")) throw new AssertionError();
        I18n.initialize("en");
        if (!I18n.tr("Guardar y conectar").equals("Save and connect")) throw new AssertionError("English resource missing");
        String initial = "-- AdisDB\n-- Selecciona una consulta o coloca el cursor dentro de ella y presiona Ctrl+Enter.\n\n";
        if (!I18n.tr(initial).contains("Select a query")) throw new AssertionError("Multiline translation failed");
        SwingUtilities.invokeAndWait(() -> {
            try {
                FlatLightLaf.setup();
                QueryPanel panel = new QueryPanel(text -> {}, busy -> {});
                JButton execute = field(panel, "execute", JButton.class);
                if (!execute.getText().equals("Execute")) throw new AssertionError("Toolbar not translated");
                SqlEditorPane editor = field(panel, "editor", SqlEditorPane.class);
                String sql = "SELECT 'Español — English' AS value;\n-- User SQL must stay unchanged\n";
                editor.setText(sql);
                snapshot(panel, "light-en");
                Style style = field(editor, "defaultStyle", Style.class);
                Color light = StyleConstants.getForeground(style);
                FlatDarkLaf.setup();
                SwingUtilities.updateComponentTreeUI(panel);
                Color dark = StyleConstants.getForeground(style);
                if (light.equals(dark) || dark.getRed() < 180) throw new AssertionError("SQL text did not adapt to dark theme");
                if (!sql.equals(editor.getText())) throw new AssertionError("Theme change modified SQL text");
                if (editor.getBackground().getRed() > 100) throw new AssertionError("SQL editor background stayed light");
                snapshot(panel, "dark-en");
                FlatLightLaf.setup();
                SwingUtilities.updateComponentTreeUI(panel);
                if (StyleConstants.getForeground(style).getRed() > 100) throw new AssertionError("Light theme not restored");
                I18n.initialize("es");
                if (!I18n.tr("Guardar y conectar").equals("Guardar y conectar")) throw new AssertionError("Spanish not restored");
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        System.out.println("AppearanceTest: OK");
        Files.delete(preferences.resolve("settings.properties"));
        Files.delete(preferences);
        System.clearProperty("nexodb.settingsFile");
    }
    private static void snapshot(JComponent panel, String name) throws Exception {
        SqlEditorPane editor = field(panel, "editor", SqlEditorPane.class);
        Method highlight = SqlEditorPane.class.getDeclaredMethod("highlight");
        highlight.setAccessible(true);
        highlight.invoke(editor);
        panel.setSize(1100, 650);
        layout(panel);
        BufferedImage image = new BufferedImage(1100, 650, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        panel.printAll(graphics);
        graphics.dispose();
        Path folder = Path.of("build/previews");
        Files.createDirectories(folder);
        ImageIO.write(image, "png", folder.resolve(name + ".png").toFile());
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component c : parent.getComponents()) if (c instanceof Container child) layout(child); }
    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target));
    }
}
