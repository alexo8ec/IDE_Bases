package com.nexodb;

import com.nexodb.ui.MainFrame;
import com.nexodb.ui.ThemeManager;
import com.nexodb.util.AppSettings;
import com.nexodb.util.I18n;

import javax.swing.*;
import java.awt.*;

public final class App {
    private App() {}

    public static void main(String[] args) {
        // The simple UI does not need GPU acceleration. GDI avoids driver-level
        // hangs observed on some Windows laptops during Java2D initialization.
        System.setProperty("sun.java2d.d3d", "false");
        System.setProperty("sun.java2d.opengl", "false");
        System.setProperty("java.net.useSystemProxies", "true");
        if (System.getProperty("os.name", "").startsWith("Windows")) System.setProperty("sun.security.jgss.native", "true");
        I18n.initialize(AppSettings.get("language", "es"));
        ThemeManager.initialize();
        SwingUtilities.invokeLater(() -> {
            if(!com.nexodb.ui.LicenseGate.start())return;
            new MainFrame().setVisible(true);
        });
    }
}

