package com.nexodb.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.*;
import static com.nexodb.util.I18n.tr;

/** Native checkbox cells support mouse, keyboard and filtered lists. Cancel never changes the selection. */
final class ComparisonObjectChooser {
    record Item(String key, String label) {}
    private ComparisonObjectChooser() {}
    static Optional<Set<String>> choose(Component owner, List<Item> objects, Set<String> selected, boolean configured) {
        DefaultTableModel model = new DefaultTableModel(new Object[]{tr("Comparar"), tr("Nombre")}, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        };
        for (Item object : objects) model.addRow(new Object[]{!configured || selected.contains(object.key()), object.label()});
        JTable table = new JTable(model);
        table.setRowHeight(27); table.getColumnModel().getColumn(0).setMaxWidth(85);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model); table.setRowSorter(sorter);
        JTextField search = new JTextField(); UiLanguage.placeholder(search, "Buscar tablas o vistas…");
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() { sorter.setRowFilter(search.getText().isBlank() ? null : RowFilter.regexFilter("(?iu)" + java.util.regex.Pattern.quote(search.getText()), 1)); }
            public void insertUpdate(DocumentEvent event) { filter(); } public void removeUpdate(DocumentEvent event) { filter(); } public void changedUpdate(DocumentEvent event) { filter(); }
        });
        JButton all = new JButton(tr("Todas")), none = new JButton(tr("Ninguna"));
        all.addActionListener(event -> { for (int i=0;i<model.getRowCount();i++) model.setValueAt(true,i,0); });
        none.addActionListener(event -> { for (int i=0;i<model.getRowCount();i++) model.setValueAt(false,i,0); });
        JLabel count = new JLabel();
        Runnable updateCount = () -> {
            int checked=0; for (int i=0;i<model.getRowCount();i++) if (Boolean.TRUE.equals(model.getValueAt(i,0))) checked++;
            count.setText(tr("Seleccionadas: %s de %s", checked, model.getRowCount()));
        };
        model.addTableModelListener(event -> updateCount.run()); updateCount.run();
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT)); buttons.add(all); buttons.add(none); buttons.add(count);
        JPanel heading = new JPanel(new BorderLayout(5,8)); heading.add(new JLabel(tr("Solo se compararán los elementos marcados.")), BorderLayout.NORTH); heading.add(search);
        JPanel content = new JPanel(new BorderLayout(5,8)); content.add(heading, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER); content.add(buttons, BorderLayout.SOUTH); content.setPreferredSize(new Dimension(620,480));
        while (JOptionPane.showConfirmDialog(owner, content, tr("Seleccionar tablas"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            Set<String> keys = new LinkedHashSet<>();
            for (int i=0;i<objects.size();i++) if (Boolean.TRUE.equals(model.getValueAt(i,0))) keys.add(objects.get(i).key());
            if (!keys.isEmpty()) return Optional.of(Set.copyOf(keys));
            JOptionPane.showMessageDialog(owner, tr("Selecciona al menos una tabla."), tr("Seleccionar tablas"), JOptionPane.INFORMATION_MESSAGE);
        }
        return Optional.empty();
    }
}
