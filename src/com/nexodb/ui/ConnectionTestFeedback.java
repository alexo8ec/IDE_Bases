package com.nexodb.ui;

import com.nexodb.model.ConnectionProfile;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import static com.nexodb.util.I18n.tr;

/** Prominent, theme-aware test feedback without displaying passwords or JDBC URLs. */
final class ConnectionTestFeedback {
    private ConnectionTestFeedback() {}
    static JPanel success(ConnectionProfile profile, String server, String database, long elapsedMillis) {
        JPanel panel = base(true, "¡Conexión exitosa!", "El servidor aceptó las credenciales.");
        JPanel details = new JPanel(new GridLayout(0,2,14,8)); details.setOpaque(false);
        detail(details, tr("Conexión"), profile.name());
        detail(details, tr("Servidor"), profile.host() + (profile.port() > 0 ? ":" + profile.port() : ""));
        detail(details, tr("Motor"), server);
        detail(details, tr("Usuario"), profile.username());
        detail(details, tr("Base de prueba"), database == null || database.isBlank() ? tr("Sin base seleccionada") : database);
        detail(details, tr("Tiempo total"), tr("%s ms", elapsedMillis));
        panel.add(details, BorderLayout.CENTER);
        return panel;
    }
    static JPanel failure(String message) {
        JPanel panel = base(false, "No se pudo conectar", "Revisa los datos de conexión y vuelve a probar.");
        JTextArea details = new JTextArea(message == null ? tr("Error desconocido") : message, 4, 48);
        details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true); details.setOpaque(false);
        panel.add(details, BorderLayout.CENTER); return panel;
    }
    private static JPanel base(boolean success, String heading, String description) {
        JPanel panel = new JPanel(new BorderLayout(14,18));
        Color accent = success ? ThemeManager.color(0x15803D,0x86EFAC) : ThemeManager.color(0xB91C1C,0xFCA5A5);
        panel.setBackground(success ? ThemeManager.color(0xF0FDF4,0x193729) : ThemeManager.color(0xFEF2F2,0x42252A));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent,2,true),new EmptyBorder(20,22,20,22)));
        JLabel title = UiLanguage.text(new JLabel(), UiLanguage.key(heading)); title.setFont(title.getFont().deriveFont(Font.BOLD,20f)); title.setForeground(accent);
        title.setIcon(IconFactory.of(success ? IconFactory.Kind.TEST : IconFactory.Kind.STOP,36)); title.setIconTextGap(14);
        JPanel top = new JPanel(new BorderLayout(8,9)); top.setOpaque(false); top.add(title,BorderLayout.NORTH);
        top.add(UiLanguage.text(new JLabel(), UiLanguage.key(description)),BorderLayout.SOUTH); panel.add(top,BorderLayout.NORTH);
        return panel;
    }
    private static void detail(JPanel panel, String caption, String value) {
        JLabel label = new JLabel(caption); label.setFont(label.getFont().deriveFont(Font.BOLD)); panel.add(label);
        JTextField text = new JTextField(value); text.setEditable(false); text.setBorder(null); text.setOpaque(false); panel.add(text);
    }
}
