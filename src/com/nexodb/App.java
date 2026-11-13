package com.nexodb;

import com.formdev.flatlaf.FlatLightLaf;
import com.nexodb.ui.MainFrame;

import javax.swing.*;
import java.awt.*;

public final class App {
    private App() {}

    public static void main(String[] args) {
        // The simple UI does not need GPU acceleration. GDI avoids driver-level
        // hangs observed on some Windows laptops during Java2D initialization.
        System.setProperty("sun.java2d.d3d", "false");
        System.setProperty("sun.java2d.opengl", "false");
        System.setProperty("sun.security.jgss.native", "true");
        SwingUtilities.invokeLater(() -> {
            try {
                FlatLightLaf.setup();
                Font interfaceFont = new Font("Segoe UI", Font.PLAIN, 13);
                UIManager.put("defaultFont", interfaceFont);
                UIManager.put("ToolTip.font", interfaceFont.deriveFont(12f));
                UIManager.put("Component.arc", 10);
                UIManager.put("Button.arc", 10);
                UIManager.put("TextComponent.arc", 9);
                UIManager.put("Component.focusWidth", 1);
                UIManager.put("ScrollBar.thumbArc", 999);
                UIManager.put("TabbedPane.showTabSeparators", true);
                UIManager.put("Table.showHorizontalLines", true);
                UIManager.put("Table.showVerticalLines", false);
                UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
            } catch (Exception ignored) {
            }
            new MainFrame().setVisible(true);
        });
    }
}

