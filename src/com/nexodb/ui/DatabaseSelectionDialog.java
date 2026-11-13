package com.nexodb.ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;

final class DatabaseSelectionDialog {
    private DatabaseSelectionDialog() {}

    static String choose(Component owner, String username, List<String> databases) {
        JComboBox<String> choices = new JComboBox<>(databases.toArray(String[]::new));
        choices.setSelectedIndex(-1);
        JPanel content = new JPanel(new BorderLayout(8, 12));
        content.add(new JLabel("Bases disponibles para el usuario “" + username + "”. Selecciona una:"), BorderLayout.NORTH);
        content.add(choices, BorderLayout.CENTER);
        while (JOptionPane.showConfirmDialog(owner, content, "Seleccionar base de datos PostgreSQL",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            if (choices.getSelectedItem() != null) return choices.getSelectedItem().toString();
        }
        return null;
    }
}
