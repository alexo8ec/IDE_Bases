package com.nexodb.ui;

import com.nexodb.util.AppSettings;
import com.nexodb.util.I18n;
import javax.swing.*;
import java.awt.*;
import static com.nexodb.util.I18n.tr;

final class PreferencesDialog {
    private record Option(String id, String label) { public String toString() { return tr(label); } }
    private PreferencesDialog() {}
    static void show(Window owner) {
        JComboBox<Option> language = new JComboBox<>(new Option[]{new Option("es", "Español"),
                new Option("en", "English"), new Option("system", "Según el sistema")});
        JComboBox<Option> theme = new JComboBox<>(new Option[]{new Option("system", "Según el sistema"),
                new Option("light", "Claro"), new Option("dark", "Oscuro")});
        select(language, AppSettings.get("language", "es"));
        select(theme, ThemeManager.preference());
        JPanel fields = new JPanel(new GridLayout(0, 2, 12, 12));
        fields.add(UiLanguage.text(new JLabel(tr("Idioma")), UiLanguage.key("Idioma"))); fields.add(language);
        fields.add(UiLanguage.text(new JLabel(tr("Apariencia")), UiLanguage.key("Apariencia"))); fields.add(theme);
        JPanel content = new JPanel(new BorderLayout(12, 18));
        content.add(fields, BorderLayout.CENTER);
        content.add(UiLanguage.text(new JLabel(tr("El idioma y el tema se aplican al guardar, sin cerrar tus consultas.")), UiLanguage.key("El idioma y el tema se aplican al guardar, sin cerrar tus consultas.")), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(owner, content, tr("Preferencias"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                apply(((Option) language.getSelectedItem()).id(), ((Option) theme.getSelectedItem()).id());
            } catch (IllegalStateException error) {
                JOptionPane.showMessageDialog(owner, error.getMessage(), tr("Preferencias"), JOptionPane.WARNING_MESSAGE);
            }
        }
    }
    static void apply(String language, String theme, Component... roots) {
        AppSettings.put("language", language);
        ThemeManager.select(theme);
        UiLanguage.change(language, roots);
    }
    private static void select(JComboBox<Option> box, String id) {
        for (int i = 0; i < box.getItemCount(); i++) if (box.getItemAt(i).id().equals(id)) box.setSelectedIndex(i);
    }
}
