package com.nexodb;

import com.formdev.flatlaf.FlatLightLaf;
import com.nexodb.ui.ConnectionDialog;
import com.nexodb.ui.IconFactory;
import com.nexodb.ui.MainFrame;
import com.nexodb.model.DatabaseType;
import com.nexodb.model.AuthenticationMode;
import com.nexodb.model.ConnectionProfile;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

public final class UiSnapshot {
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.d3d", "false");
        FlatLightLaf.setup();
        UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 9);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("TabbedPane.showTabSeparators", true);

        Path output = args.length == 0 ? Path.of("build") : Path.of(args[0]);
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                MainFrame frame = new MainFrame();
                frame.setSize(1280, 780);
                frame.addNotify();
                frame.validate();
                render(frame.getRootPane(), output.resolve("ui-main.png"));

                ConnectionDialog dialog = new ConnectionDialog(frame, null);
                dialog.pack();
                dialog.addNotify();
                dialog.validate();
                render(dialog.getRootPane(), output.resolve("ui-connection.png"));
                dialog.dispose();

                ConnectionProfile windowsProfile = new ConnectionProfile("", "MariaDB con Windows",
                        DatabaseType.MARIADB, "localhost", 3306, "", "root",
                        DatabaseType.MARIADB.buildUrl("localhost", 3306, ""),
                        DatabaseType.MARIADB.driverClass(), "", AuthenticationMode.WINDOWS, true);
                ConnectionDialog windowsDialog = new ConnectionDialog(frame, windowsProfile);
                windowsDialog.pack();
                windowsDialog.addNotify();
                windowsDialog.validate();
                render(windowsDialog.getRootPane(), output.resolve("ui-connection-windows.png"));
                windowsDialog.dispose();

                JPanel engines = new JPanel(new GridLayout(2, 4, 10, 10));
                engines.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
                for (DatabaseType engine : DatabaseType.values()) {
                    JLabel label = new JLabel(engine.displayName(), IconFactory.database(engine, 42), SwingConstants.CENTER);
                    label.setHorizontalTextPosition(SwingConstants.CENTER);
                    label.setVerticalTextPosition(SwingConstants.BOTTOM);
                    label.setIconTextGap(5);
                    engines.add(label);
                }
                engines.setSize(720, 190);
                engines.doLayout();
                render(engines, output.resolve("ui-engines.png"));
                frame.dispose();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        System.exit(0);
    }

    private static void render(Container component, Path destination) throws Exception {
        int width = Math.max(1, component.getWidth());
        int height = Math.max(1, component.getHeight());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(component.getBackground());
            graphics.fillRect(0, 0, width, height);
            component.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", destination.toFile());
    }
}
