package com.nexodb.ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import java.awt.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Lightweight SQL editor with keyword casing and syntax colors. */
public final class SqlEditorPane extends JTextPane {
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList((
            "SELECT FROM WHERE INSERT INTO VALUES UPDATE SET DELETE CREATE ALTER DROP TABLE DATABASE SCHEMA VIEW INDEX "
            + "PRIMARY KEY FOREIGN REFERENCES UNIQUE NOT NULL DEFAULT AUTO_INCREMENT AS DISTINCT ALL AND OR NOT IN IS LIKE BETWEEN "
            + "JOIN INNER LEFT RIGHT FULL OUTER CROSS ON UNION INTERSECT EXCEPT GROUP BY ORDER HAVING LIMIT OFFSET ASC DESC "
            + "CASE WHEN THEN ELSE END EXISTS ANY SOME WITH RECURSIVE USE SHOW DESCRIBE EXPLAIN CALL PROCEDURE FUNCTION TRIGGER EVENT "
            + "BEGIN COMMIT ROLLBACK TRANSACTION GRANT REVOKE IF NULL TRUE FALSE CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP "
            + "COUNT SUM AVG MIN MAX CAST CONVERT OVER PARTITION ROWS RANGE FETCH FIRST ONLY RETURNING MERGE MATCHED"
    ).split("\\s+")));

    private final Style defaultStyle;
    private final Style keywordStyle;
    private final Style stringStyle;
    private final Style commentStyle;
    private final Style numberStyle;
    private final Style identifierStyle;
    private final Timer highlightTimer;
    private boolean highlighting;

    public SqlEditorPane() {
        StyledDocument document = getStyledDocument();
        defaultStyle = addStyle("default", null);
        StyleConstants.setForeground(defaultStyle, new Color(0x172033));
        keywordStyle = addStyle("keyword", defaultStyle);
        StyleConstants.setForeground(keywordStyle, new Color(0x155EEF));
        StyleConstants.setBold(keywordStyle, true);
        stringStyle = addStyle("string", defaultStyle);
        StyleConstants.setForeground(stringStyle, new Color(0x16803A));
        commentStyle = addStyle("comment", defaultStyle);
        StyleConstants.setForeground(commentStyle, new Color(0x7A8699));
        StyleConstants.setItalic(commentStyle, true);
        numberStyle = addStyle("number", defaultStyle);
        StyleConstants.setForeground(numberStyle, new Color(0x8B3FD1));
        identifierStyle = addStyle("identifier", defaultStyle);
        StyleConstants.setForeground(identifierStyle, new Color(0x087E8B));
        ((AbstractDocument) document).setDocumentFilter(new KeywordFilter());
        highlightTimer = new Timer(90, event -> highlight());
        highlightTimer.setRepeats(false);
        document.addDocumentListener(new DocumentListener() {
            private void changed() { if (!highlighting) highlightTimer.restart(); }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });
        updatePalette();
    }

    @Override public void updateUI() {
        super.updateUI();
        if (defaultStyle != null) updatePalette();
    }

    private void updatePalette() {
        StyleConstants.setForeground(defaultStyle, ThemeManager.color(0x172033, 0xE2E8F0));
        StyleConstants.setForeground(keywordStyle, ThemeManager.color(0x155EEF, 0x82AAFF));
        StyleConstants.setForeground(stringStyle, ThemeManager.color(0x16803A, 0xA3D98B));
        StyleConstants.setForeground(commentStyle, ThemeManager.color(0x7A8699, 0x9BA8BB));
        StyleConstants.setForeground(numberStyle, ThemeManager.color(0x8B3FD1, 0xC4A7E7));
        StyleConstants.setForeground(identifierStyle, ThemeManager.color(0x087E8B, 0x7DD3D8));
        setCaretColor(UIManager.getColor("TextPane.caretForeground"));
        if (highlightTimer != null) highlightTimer.restart();
    }

    public void append(String text) {
        try { getDocument().insertString(getDocument().getLength(), text, null); }
        catch (BadLocationException ignored) {}
    }

    public void setTabSize(int columns) {
        int width = Math.max(1, columns) * getFontMetrics(getFont()).charWidth(' ');
        TabStop[] stops = new TabStop[40];
        for (int i = 0; i < stops.length; i++) stops[i] = new TabStop((i + 1) * width);
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setTabSet(attributes, new TabSet(stops));
        getStyledDocument().setParagraphAttributes(0, getDocument().getLength(), attributes, false);
    }

    public void setLineWrap(boolean ignored) { }

    @Override public boolean getScrollableTracksViewportWidth() {
        Container parent = getParent();
        return parent == null || getUI().getPreferredSize(this).width <= parent.getSize().width;
    }

    @Override public void setSize(Dimension size) {
        if (getParent() != null && size.width < getParent().getSize().width) size.width = getParent().getSize().width;
        super.setSize(size);
    }

    private void highlight() {
        String text = getText();
        StyledDocument doc = getStyledDocument();
        highlighting = true;
        try {
            doc.setCharacterAttributes(0, text.length(), defaultStyle, true);
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if ((c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') || c == '#') {
                    int end = text.indexOf('\n', i); if (end < 0) end = text.length();
                    doc.setCharacterAttributes(i, end - i, commentStyle, true); i = end; continue;
                }
                if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    int end = text.indexOf("*/", i + 2); end = end < 0 ? text.length() : end + 2;
                    doc.setCharacterAttributes(i, end - i, commentStyle, true); i = end; continue;
                }
                if (c == '\'' || c == '"') {
                    int end = quotedEnd(text, i, c);
                    doc.setCharacterAttributes(i, end - i, stringStyle, true); i = end; continue;
                }
                if (c == '`') {
                    int end = quotedEnd(text, i, c);
                    doc.setCharacterAttributes(i, end - i, identifierStyle, true); i = end; continue;
                }
                if (Character.isDigit(c)) {
                    int end = i + 1; while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) end++;
                    doc.setCharacterAttributes(i, end - i, numberStyle, true); i = end; continue;
                }
                if (isWord(c)) {
                    int end = i + 1; while (end < text.length() && isWord(text.charAt(end))) end++;
                    if (KEYWORDS.contains(text.substring(i, end).toUpperCase(Locale.ROOT))) doc.setCharacterAttributes(i, end - i, keywordStyle, true);
                    i = end; continue;
                }
                i++;
            }
        } finally {
            highlighting = false;
        }
    }

    private static int quotedEnd(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == '\\') { i += 2; continue; }
            if (text.charAt(i) == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) { i += 2; continue; }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    private static boolean isWord(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }

    public void restoreText(String text){
        AbstractDocument document=(AbstractDocument)getDocument();DocumentFilter filter=document.getDocumentFilter();
        try{document.setDocumentFilter(null);setText(text);}finally{document.setDocumentFilter(filter);}
    }

    private final class KeywordFilter extends DocumentFilter {
        @Override public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            fb.insertString(offset, string, attr);
            normalize(fb, offset + (string == null ? 0 : string.length()), string);
        }
        @Override public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            fb.replace(offset, length, text, attrs);
            normalize(fb, offset + (text == null ? 0 : text.length()), text);
        }
        private void normalize(FilterBypass fb, int caret, String inserted) throws BadLocationException {
            if (inserted == null || inserted.isEmpty() || isWord(inserted.charAt(inserted.length() - 1))) return;
            Document doc = fb.getDocument();
            String value = doc.getText(0, doc.getLength());
            int end = Math.min(caret, value.length());
            while (end > 0 && !isWord(value.charAt(end - 1))) end--;
            int start = end; while (start > 0 && isWord(value.charAt(start - 1))) start--;
            if (start == end || !isCode(value, start)) return;
            String word = value.substring(start, end);
            String upper = word.toUpperCase(Locale.ROOT);
            if (KEYWORDS.contains(upper) && !word.equals(upper)) fb.replace(start, end - start, upper, null);
        }
    }

    private static boolean isCode(String text, int position) {
        boolean single = false, doub = false, backtick = false, line = false, block = false;
        for (int i = 0; i < position; i++) {
            char c = text.charAt(i), next = i + 1 < position ? text.charAt(i + 1) : '\0';
            if (line) { if (c == '\n') line = false; continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (single) { if (c == '\\') i++; else if (c == '\'') single = false; continue; }
            if (doub) { if (c == '\\') i++; else if (c == '"') doub = false; continue; }
            if (backtick) { if (c == '`') backtick = false; continue; }
            if (c == '-' && next == '-') { line = true; i++; }
            else if (c == '#') line = true;
            else if (c == '/' && next == '*') { block = true; i++; }
            else if (c == '\'') single = true;
            else if (c == '"') doub = true;
            else if (c == '`') backtick = true;
        }
        return !single && !doub && !backtick && !line && !block;
    }
}
