package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;
import com.nexodb.util.PasswordGenerator;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.Arrays;

/** Shared create/change password controls. Secrets never enter preferences or logs. */
final class PasswordEditorPanel extends JPanel implements AutoCloseable {
    final JPasswordField password = new JPasswordField(26);
    final JPasswordField confirmation = new JPasswordField(26);
    final JSpinner length = new JSpinner(new SpinnerNumberModel(20, 12, 64, 1));
    final JCheckBox symbols = new JCheckBox(tr("Incluir símbolos"), true);
    final JCheckBox show = new JCheckBox(tr("Mostrar contraseña"));
    final JButton generate = new JButton(tr("Generar contraseña"), IconFactory.of(IconFactory.Kind.KEY, 16));
    final JButton copy = new JButton(tr("Copiar"), IconFactory.of(IconFactory.Kind.COPY, 16));
    final JLabel feedback = new JLabel(" ");
    private final char echo;
    PasswordEditorPanel() {
        super(new GridBagLayout()); echo = password.getEchoChar();
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(5, 0, 5, 8); c.fill = GridBagConstraints.HORIZONTAL;
        row(c, 0, tr("Contraseña:"), password); row(c, 1, tr("Confirmar contraseña:"), confirmation);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        options.add(new JLabel(tr("Longitud:"))); options.add(length); options.add(symbols);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2; add(options, c);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        actions.add(generate); actions.add(copy); actions.add(show); c.gridy = 3; add(actions, c);
        c.gridy = 4; add(feedback, c);
        show.addActionListener(e -> { password.setEchoChar(show.isSelected() ? 0 : echo); confirmation.setEchoChar(show.isSelected() ? 0 : echo); });
        generate.addActionListener(e -> {
            char[] secret = PasswordGenerator.generate((Integer) length.getValue(), symbols.isSelected());
            try { password.setText(new String(secret)); confirmation.setText(new String(secret)); }
            finally { Arrays.fill(secret, '\0'); }
        });
        copy.addActionListener(e -> {
            char[] secret = password.getPassword();
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(new String(secret)), null); feedback.setText(tr("Contraseña copiada. Guárdala en un lugar seguro.")); }
            catch (IllegalStateException | HeadlessException error) { feedback.setText(tr("No se pudo acceder al portapapeles.")); }
            finally { Arrays.fill(secret, '\0'); }
        });
        DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update(); } public void removeUpdate(DocumentEvent e) { update(); } public void changedUpdate(DocumentEvent e) { update(); }
        };
        password.getDocument().addDocumentListener(listener); confirmation.getDocument().addDocumentListener(listener); update();
    }
    private void row(GridBagConstraints c, int row, String caption, JComponent field) {
        c.gridy = row; c.gridx = 0; c.weightx = 0; add(new JLabel(caption), c);
        c.gridx = 1; c.weightx = 1; add(field, c);
    }
    String validation() {
        char[] a = password.getPassword(), b = confirmation.getPassword();
        try {
            if (a.length == 0) return tr("Escribe o genera una contraseña.");
            if (!Arrays.equals(a, b)) return tr("Las contraseñas no coinciden.");
            if (new String(a).chars().anyMatch(Character::isISOControl)) return tr("La contraseña no puede contener caracteres de control.");
            return "";
        } finally { Arrays.fill(a, '\0'); Arrays.fill(b, '\0'); }
    }
    private void update() {
        char[] secret = password.getPassword(); try { copy.setEnabled(secret.length > 0); } finally { Arrays.fill(secret, '\0'); }
        String error = validation(); feedback.setText(error.isEmpty() ? tr("Contraseñas coincidentes. El servidor comprobará su política de seguridad.") : error);
    }
    @Override public void close() { password.setText(""); confirmation.setText(""); show.setSelected(false); password.setEchoChar(echo); confirmation.setEchoChar(echo); }
}
