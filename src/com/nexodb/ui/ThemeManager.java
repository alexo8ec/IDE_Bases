package com.nexodb.ui;

import com.formdev.flatlaf.*;
import com.nexodb.util.AppSettings;
import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;

public final class ThemeManager {
    private static volatile String preference = AppSettings.get("theme", "system");
    private static volatile boolean systemDark;
    private static final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "nexodb-system-theme"); thread.setDaemon(true); return thread;
    });
    private ThemeManager() {}

    public static void initialize() {
        systemDark = detectSystemDark();
        apply();
        watcher.scheduleWithFixedDelay(() -> {
            if (!"system".equals(preference)) return;
            boolean detected = detectSystemDark();
            if (detected != systemDark) {
                systemDark = detected;
                SwingUtilities.invokeLater(ThemeManager::apply);
            }
        }, 15, 15, TimeUnit.SECONDS);
    }
    public static String preference() { return preference; }
    public static void select(String value) {
        if (!java.util.Set.of("light", "dark", "system").contains(value)) throw new IllegalArgumentException(value);
        preference = value;
        AppSettings.put("theme", value);
        apply();
    }
    public static Color color(int light, int dark) { return new Color(FlatLaf.isLafDark() ? dark : light); }
    private static void apply() {
        boolean dark = "dark".equals(preference) || ("system".equals(preference) && systemDark);
        if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 9);
        UIManager.put("Component.focusWidth", 2);
        UIManager.put("Component.accentColor",new Color(0x167567));
        UIManager.put("Button.margin",new Insets(6,11,6,11));
        UIManager.put("Button.iconTextGap",7);
        UIManager.put("MenuItem.margin",new Insets(6,10,6,10));
        UIManager.put("MenuItem.minimumIconSize",new Dimension(18,18));
        UIManager.put("TabbedPane.tabHeight",35);
        UIManager.put("Tree.rowHeight",27);
        Color accent=new Color(0x167567);
        UIManager.put("Component.focusColor",accent);
        UIManager.put("ProgressBar.foreground",accent);
        UIManager.put("Panel.background",new Color(dark?0x1B2632:0xF4F7F9));
        UIManager.put("MenuBar.background",new Color(dark?0x18232E:0xFFFFFF));
        UIManager.put("ToolBar.background",new Color(dark?0x202D39:0xFFFFFF));
        UIManager.put("Table.background",new Color(dark?0x202D39:0xFFFFFF));
        UIManager.put("Table.alternateRowColor",new Color(dark?0x243341:0xF7FAFC));
        UIManager.put("Table.selectionBackground",new Color(dark?0x244E4B:0xDDF1EB));
        UIManager.put("Table.selectionForeground",new Color(dark?0xEDF4F8:0x134337));
        UIManager.put("List.selectionBackground",new Color(dark?0x244E4B:0xDDF1EB));
        UIManager.put("List.selectionForeground",new Color(dark?0xEDF4F8:0x134337));
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
        FlatLaf.updateUI();
        for(Window window:Window.getWindows())if(window instanceof MainFrame)UiDesign.enhance(window);
    }
    static boolean detectSystemDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return command("reg.exe", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\s*.*");
        }
        if (os.contains("mac")) return command("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle").toLowerCase(Locale.ROOT).contains("dark");
        // The desktop portal works for GNOME, KDE and other portal-enabled Linux desktops.
        String portal = command("gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
                "--object-path", "/org/freedesktop/portal/desktop", "--method", "org.freedesktop.portal.Settings.Read",
                "org.freedesktop.appearance", "color-scheme");
        if (portal.contains("uint32 1")) return true;
        if (portal.contains("uint32 2")) return false;
        String gnome = command("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
        if (gnome.contains("prefer-dark")) return true;
        String gtk = System.getenv().getOrDefault("GTK_THEME", "");
        if (gtk.toLowerCase(Locale.ROOT).contains("dark")) return true;
        return command("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme").toLowerCase(Locale.ROOT).contains("dark");
    }
    private static String command(String... arguments) {
        Process process = null;
        try {
            process = new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) return "";
            return process.exitValue() == 0 ? new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8).trim() : "";
        } catch (Exception ignored) { return ""; }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
