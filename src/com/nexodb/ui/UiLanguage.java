package com.nexodb.ui;

import com.nexodb.util.I18n;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.*;

/** Explicit caption bindings. Database names, SQL, cell values and editable fields are never translated. */
public final class UiLanguage {
    private static final String TEXT = "nexodb.language.text";
    private static final String TOOLTIP = "nexodb.language.tooltip";
    private static final String PLACEHOLDER = "nexodb.language.placeholder";
    private static final String TABS = "nexodb.language.tabs";
    private static final String HEADERS = "nexodb.language.headers";
    private static final String TITLE = "nexodb.language.title";
    public record Key(String source) { @Override public String toString() { return I18n.tr(source); } }
    private static final class Binding {
        final Object[] parts;
        String last;
        Binding(Object[] parts) { this.parts = parts.clone(); }
        String render() {
            StringBuilder value = new StringBuilder();
            for (Object part : parts) value.append(part);
            return value.toString();
        }
    }
    private UiLanguage() {}
    public static Key key(String source) { return new Key(source); }
    public static Key caption(String displayed) { return key(I18n.source(displayed)); }
    public static JLabel label(String source){return text(new JLabel(),key(source));}
    public static JButton button(String source){return text(new JButton(),key(source));}

    public static <T extends JComponent> T text(T component, Object... parts) {
        if (!(component instanceof JLabel) && !(component instanceof AbstractButton)) {
            throw new IllegalArgumentException("Only label/button captions can be bound");
        }
        Binding binding = new Binding(parts);
        component.putClientProperty(TEXT, binding);
        setText(component, binding.last = binding.render());
        return component;
    }
    private static String textOf(JComponent component) {
        return component instanceof JLabel label ? label.getText() : ((AbstractButton) component).getText();
    }
    private static void setText(JComponent component, String text) {
        if (component instanceof JLabel label) label.setText(text); else ((AbstractButton) component).setText(text);
    }
    public static void tooltip(JComponent component, Object... parts) {
        Binding binding = new Binding(parts);
        component.putClientProperty(TOOLTIP, binding);
        component.setToolTipText(binding.last = binding.render());
    }
    public static void placeholder(JComponent component, String source) {
        component.putClientProperty(PLACEHOLDER, source);
        component.putClientProperty("JTextField.placeholderText", I18n.tr(source));
    }
    public static void title(Window window, Object... parts) {
        if (window instanceof RootPaneContainer pane) {
            Binding binding = new Binding(parts);
            pane.getRootPane().putClientProperty(TITLE, binding);
            setTitle(window, binding.last = binding.render());
        }
    }
    private static void setTitle(Window window, String title) {
        if (window instanceof Frame frame) frame.setTitle(title);
        if (window instanceof Dialog dialog) dialog.setTitle(title);
    }
    public static void addTab(JTabbedPane tabs, String source, Icon icon, Component content) {
        addTab(tabs, new Object[]{key(source)}, icon, content);
    }
    public static void addTab(JTabbedPane tabs, Object[] parts, Icon icon, Component content) {
        Binding binding = new Binding(parts);
        tabs.addTab(binding.last = binding.render(), icon, content);
        @SuppressWarnings("unchecked") Map<Component, Binding> bindings = (Map<Component, Binding>) tabs.getClientProperty(TABS);
        if (bindings == null) { bindings = new WeakHashMap<>(); tabs.putClientProperty(TABS, bindings); }
        bindings.put(content, binding);
    }
    public static TitledBorder border(String source) {
        return new TitledBorder(source) { @Override public String getTitle() { return I18n.tr(source); } };
    }
    public static JTable headers(JTable table) {
        String[] sources = new String[table.getColumnModel().getColumnCount()];
        for (int i = 0; i < sources.length; i++) {
            var column = table.getColumnModel().getColumn(i);
            sources[column.getModelIndex()] = I18n.source(String.valueOf(column.getHeaderValue()));
        }
        table.putClientProperty(HEADERS, sources);
        return table;
    }
    /** The model remains stable, so repainting a choice never fires a database change. */
    public static void choices(JComboBox<String> combo) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.setText(I18n.tr(String.valueOf(value)));
                return label;
            }
        });
    }

    public static void change(String preference, Component... additionalRoots) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Language changes require the Swing event thread");
        I18n.initialize(preference);
        Set<Component> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable()) continue;
            refresh(window, visited);
            SwingUtilities.updateComponentTreeUI(window);
            window.revalidate(); window.repaint();
        }
        for (Component root : additionalRoots) {
            refresh(root, visited);
            if (root instanceof JComponent component) { component.revalidate(); component.repaint(); }
        }
    }
    private static void refresh(Component component, Set<Component> visited) {
        if (component == null || !visited.add(component)) return;
        component.setLocale(I18n.locale());
        if (component instanceof RootPaneContainer pane && component instanceof Window window) {
            if (pane.getRootPane().getClientProperty(TITLE) instanceof Binding binding) setTitle(window, binding.last = binding.render());
        }
        if (component instanceof JComponent target) {
            if (target.getClientProperty(TEXT) instanceof Binding binding) {
                // A later application update owns its text unless it explicitly installs a new binding.
                if (Objects.equals(textOf(target), binding.last)) setText(target, binding.last = binding.render());
                else target.putClientProperty(TEXT, null);
            }
            if (target.getClientProperty(TOOLTIP) instanceof Binding binding && Objects.equals(target.getToolTipText(), binding.last)) {
                target.setToolTipText(binding.last = binding.render());
            }
            if (target.getClientProperty(PLACEHOLDER) instanceof String source) target.putClientProperty("JTextField.placeholderText", I18n.tr(source));
            if (target instanceof JTable table && target.getClientProperty(HEADERS) instanceof String[] headers) {
                for (int i = 0; i < table.getColumnCount(); i++) {
                    var column = table.getColumnModel().getColumn(i);
                    if (column.getModelIndex() < headers.length) column.setHeaderValue(I18n.tr(headers[column.getModelIndex()]));
                }
                table.getTableHeader().repaint();
            }
            if (target instanceof JTabbedPane tabs && target.getClientProperty(TABS) instanceof Map<?, ?> bindings) {
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    if (bindings.get(tabs.getComponentAt(i)) instanceof Binding binding && Objects.equals(tabs.getTitleAt(i), binding.last)) {
                        tabs.setTitleAt(i, binding.last = binding.render());
                    }
                }
            }
            if (target instanceof JMenu menu) refresh(menu.getPopupMenu(), visited);
            if (target.getComponentPopupMenu() != null) refresh(target.getComponentPopupMenu(), visited);
        }
        if (component instanceof Container container) for (Component child : container.getComponents()) refresh(child, visited);
    }
}
