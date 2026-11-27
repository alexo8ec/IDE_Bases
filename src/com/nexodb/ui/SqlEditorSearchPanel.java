package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/** Literal search and selection highlights scoped to one SQL document. */
final class SqlEditorSearchPanel extends JPanel {
    private static final int MAX_HIGHLIGHTS = 500;
    private final SqlEditorPane editor;
    private final JPanel findBar = new JPanel(new BorderLayout(8, 0));
    private final JTextField term = new JTextField();
    private final JLabel counter = new JLabel();
    private final JLabel selectionCounter = new JLabel();
    private final JButton previous = new JButton("↑");
    private final JButton next = new JButton("↓");
    private final List<Object> highlights = new ArrayList<>();
    private final Timer debounce = new Timer(110, event -> scan());
    private SwingWorker<Matches, Void> worker;
    private int count;
    private int active;

    private record Matches(int count, int activeOffset, List<Integer> visibleOffsets) {}

    SqlEditorSearchPanel(SqlEditorPane editor) {
        super(new BorderLayout());
        this.editor = editor;
        JScrollPane scroll = new JScrollPane(editor);
        scroll.setRowHeaderView(new SqlLineNumbers(editor));
        add(scroll, BorderLayout.CENTER);
        add(findBar, BorderLayout.NORTH);
        add(selectionCounter, BorderLayout.SOUTH);
        selectionCounter.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        selectionCounter.setVisible(false);
        findBar.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
        findBar.setVisible(false);
        term.setName("sql-search-term");
        term.putClientProperty("JTextField.leadingIcon", IconFactory.of(IconFactory.Kind.SEARCH, 16));
        UiLanguage.placeholder(term, "Buscar palabra o frase en SQL");
        term.getAccessibleContext().setAccessibleName(tr("Buscar palabra o frase en SQL"));
        findBar.add(term, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton close = new JButton("×");
        configureButton(previous, "Coincidencia anterior (Mayús+Intro)", () -> move(-1));
        configureButton(next, "Siguiente coincidencia (Intro)", () -> move(1));
        configureButton(close, "Cerrar búsqueda (Esc)", () -> closeSearch(true));
        controls.add(counter);
        controls.add(previous);
        controls.add(next);
        controls.add(close);
        findBar.add(controls, BorderLayout.EAST);

        debounce.setRepeats(false);
        term.getDocument().addDocumentListener(onTextChange(this::requestSearch));
        editor.getDocument().addDocumentListener(onTextChange(this::requestSearch));
        editor.addCaretListener(event -> {
            if (!findBar.isVisible()) requestSearch();
        });
        bindFind(KeyEvent.CTRL_DOWN_MASK);
        if (!GraphicsEnvironment.isHeadless()) {
            bindFind(Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        }
        term.addActionListener(event -> move(1));
        bind(term, WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                "sql-find-previous", () -> move(-1));
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        bind(findBar, WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, escape, "sql-close-find", () -> closeSearch(true));
        Object originalEscape = editor.getInputMap(WHEN_FOCUSED).get(escape);
        bind(editor, WHEN_FOCUSED, escape, "sql-close-find", () -> {
            if (findBar.isVisible()) {
                closeSearch(true);
            } else if (originalEscape != null && editor.getActionMap().get(originalEscape) != null) {
                editor.getActionMap().get(originalEscape).actionPerformed(
                        new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, originalEscape.toString()));
            }
        });
    }

    private static void configureButton(JButton button, String description, Runnable action) {
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        UiLanguage.tooltip(button, UiLanguage.key(description));
        button.getAccessibleContext().setAccessibleName(tr(description));
        button.addActionListener(event -> action.run());
    }

    private static DocumentListener onTextChange(Runnable action) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { action.run(); }
            @Override public void removeUpdate(DocumentEvent event) { action.run(); }
            @Override public void changedUpdate(DocumentEvent event) {}
        };
    }

    private void bindFind(int mask) {
        KeyStroke find = KeyStroke.getKeyStroke(KeyEvent.VK_F, mask);
        bind(editor, WHEN_FOCUSED, find, "sql-open-find", this::openSearch);
        bind(findBar, WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, find, "sql-open-find", this::openSearch);
    }

    private static void bind(JComponent target, int condition, KeyStroke key, String id, Runnable run) {
        target.getInputMap(condition).put(key, id);
        target.getActionMap().put(id, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { run.run(); }
        });
    }

    void openSearch() {
        if (!findBar.isVisible()) {
            findBar.setVisible(true);
            selectionCounter.setVisible(false);
            String selected = editor.getSelectedText();
            if (selected != null && !selected.isBlank() && selected.indexOf('\n') < 0 && selected.indexOf('\r') < 0) {
                term.setText(selected);
            }
            requestSearch();
        }
        term.requestFocusInWindow();
        term.selectAll();
    }

    private void closeSearch(boolean restoreFocus) {
        findBar.setVisible(false);
        term.setText("");
        if (restoreFocus) {
            requestSearch();
            editor.requestFocusInWindow();
        }
    }

    @Override public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (!visible && debounce != null) {
            closeSearch(false);
            resetMatches();
        }
    }

    private String searchText() {
        return findBar.isVisible() ? term.getText() : editor.getSelectedText();
    }

    private void requestSearch() {
        resetMatches();
        boolean hasSelection = editor.getSelectionEnd() > editor.getSelectionStart();
        if (isVisible() && (findBar.isVisible() ? term.getDocument().getLength() > 0 : hasSelection)) {
            debounce.restart();
        }
    }

    private void resetMatches() {
        debounce.stop();
        cancelScan();
        removeHighlights();
        selectionCounter.setVisible(false);
        UiLanguage.text(counter, "");
        previous.setEnabled(false);
        next.setEnabled(false);
        count = 0;
        active = 0;
    }

    private void cancelScan() {
        if (worker != null) {
            worker.cancel(true);
            worker = null;
        }
    }

    private void removeHighlights() {
        for (Object tag : highlights) editor.getHighlighter().removeHighlight(tag);
        highlights.clear();
    }

    private void move(int direction) {
        if (count == 0) return;
        active = Math.floorMod(active + direction, count);
        scan();
    }

    private void scan() {
        cancelScan();
        String query = searchText();
        if (!isVisible() || query == null || query.isBlank()) return;
        boolean manual = findBar.isVisible();
        int chosen = active;
        String source;
        try {
            var document = editor.getDocument();
            source = document.getText(0, document.getLength());
        } catch (BadLocationException ignored) {
            return;
        }
        boolean wholeWord = !manual && query.codePoints().allMatch(SqlEditorSearchPanel::wordPart)
                && isolatedWord(source, editor.getSelectionStart(), editor.getSelectionEnd());
        worker = new SwingWorker<>() {
            @Override protected Matches doInBackground() {
                int flags = Pattern.LITERAL | (manual ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
                var matcher = Pattern.compile(query, flags).matcher(source);
                List<Integer> visible = new ArrayList<>();
                int total = 0;
                int offset = -1;
                int highlightFrom = Math.max(0, chosen - MAX_HIGHLIGHTS / 2);
                while (!isCancelled() && matcher.find()) {
                    if (wholeWord && !isolatedWord(source, matcher.start(), matcher.end())) continue;
                    if (total == chosen) offset = matcher.start();
                    if (total >= highlightFrom && visible.size() < MAX_HIGHLIGHTS) visible.add(matcher.start());
                    total++;
                }
                return new Matches(total, offset, visible);
            }

            @Override protected void done() {
                if (worker != this) return;
                worker = null;
                try {
                    showMatches(get(), query.length(), manual);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException error) {
                    resetMatches();
                }
            }
        };
        worker.execute();
    }

    private static boolean isolatedWord(String source, int start, int end) {
        return (start == 0 || !wordPart(source.codePointBefore(start)))
                && (end == source.length() || !wordPart(source.codePointAt(end)));
    }

    private static boolean wordPart(int character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '$'
                || Character.getType(character) == Character.NON_SPACING_MARK;
    }

    private void showMatches(Matches matches, int length, boolean manual) {
        removeHighlights();
        count = matches.count();
        var normal = new DefaultHighlighter.DefaultHighlightPainter(ThemeManager.color(0xFFEDB0, 0x62501B));
        var current = new DefaultHighlighter.DefaultHighlightPainter(ThemeManager.color(0xF9B94E, 0x935B23));
        try {
            for (int offset : matches.visibleOffsets()) {
                highlights.add(editor.getHighlighter().addHighlight(offset, offset + length,
                        manual && offset == matches.activeOffset() ? current : normal));
            }
            if (manual && matches.activeOffset() >= 0) {
                var rectangle = editor.modelToView2D(matches.activeOffset());
                if (rectangle != null) editor.scrollRectToVisible(rectangle.getBounds());
            }
        } catch (BadLocationException ignored) {
            resetMatches();
            return;
        }
        if (manual) {
            if (count == 0) UiLanguage.text(counter, UiLanguage.key("Sin coincidencias"));
            else UiLanguage.text(counter, active + 1, " ", UiLanguage.key("de"), " ", count);
            next.setEnabled(count > 1);
            previous.setEnabled(count > 1);
        } else {
            UiLanguage.text(selectionCounter, count, " ", UiLanguage.key(count == 1 ? "coincidencia" : "coincidencias"));
            selectionCounter.setVisible(count > 0);
        }
    }
}
