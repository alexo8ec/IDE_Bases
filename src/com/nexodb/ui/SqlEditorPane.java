package com.nexodb.ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import javax.swing.undo.*;
import java.awt.event.*;
import java.awt.*;
import com.nexodb.util.SqlVocabulary;
import java.util.Locale;
import java.util.Set;

/** Lightweight SQL editor with keyword casing and syntax colors. */
public final class SqlEditorPane extends JTextPane {
    private final java.util.Map<Integer,String> errors=new java.util.TreeMap<>();private final java.util.List<Object> errorHighlights=new java.util.ArrayList<>();
    public java.util.Map<Integer,String> errorLines(){return java.util.Map.copyOf(errors);}
    public void clearErrors(){for(Object tag:errorHighlights)getHighlighter().removeHighlight(tag);errorHighlights.clear();errors.clear();firePropertyChange("sqlErrors",false,true);repaint();}
    public void markError(int line,int column,String message){var root=getDocument().getDefaultRootElement();line=Math.max(1,Math.min(line,root.getElementCount()));var element=root.getElement(line-1);try{errorHighlights.add(getHighlighter().addHighlight(element.getStartOffset(),Math.min(getDocument().getLength(),element.getEndOffset()),new DefaultHighlighter.DefaultHighlightPainter(ThemeManager.color(0xFFE0E0,0x66343D))));}catch(BadLocationException ignored){}errors.put(line,message);firePropertyChange("sqlErrors",false,true);repaint();}
    public void goToError(int line,int column){var root=getDocument().getDefaultRootElement();var element=root.getElement(Math.max(0,Math.min(root.getElementCount()-1,line-1)));setCaretPosition(Math.max(element.getStartOffset(),Math.min(getDocument().getLength(),Math.min(element.getEndOffset()-1,element.getStartOffset()+Math.max(0,column-1)))));requestFocusInWindow();try{var rect=modelToView2D(getCaretPosition());if(rect!=null)scrollRectToVisible(rect.getBounds());}catch(BadLocationException ignored){}}
    @Override public String getToolTipText(MouseEvent event){int at=viewToModel2D(event.getPoint());String error=errors.get(getDocument().getDefaultRootElement().getElementIndex(at)+1);return error==null?super.getToolTipText(event):error;}
    private Set<String> keywords = SqlVocabulary.base();

    public void setKeywords(Set<String> words) { keywords=Set.copyOf(words);highlightTimer.restart(); }

    private final Style defaultStyle;
    private final Style keywordStyle;
    private final Style stringStyle;
    private final Style commentStyle;
    private final Style numberStyle;
    private final Style identifierStyle;
    private final Timer highlightTimer;
    private boolean highlighting;
    private boolean restoring, replaying;
    private int editDepth;
    private CompoundEdit compound;
    private boolean compoundChanged;
    private final UndoManager undo = new UndoManager();
    private Runnable undoStateChanged=()->{};

    public SqlEditorPane() {
        StyledDocument document = getStyledDocument();
        ToolTipManager.sharedInstance().registerComponent(this);document.addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){clearErrors();}public void removeUpdate(DocumentEvent e){clearErrors();}public void changedUpdate(DocumentEvent e){}});
        defaultStyle = addStyle("default", null);
        StyleConstants.setFontFamily(defaultStyle,Font.MONOSPACED);
        StyleConstants.setFontSize(defaultStyle,14);
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
        undo.setLimit(1000);
        document.addUndoableEditListener(event->{
            if(highlighting||restoring||replaying||event.getEdit() instanceof DocumentEvent change&&change.getType()==DocumentEvent.EventType.CHANGE)return;
            if(compound!=null){compound.addEdit(event.getEdit());compoundChanged=true;}else{undo.addEdit(event.getEdit());undoStateChanged.run();}
        });
        bindUndo(KeyEvent.CTRL_DOWN_MASK);
        if(!GraphicsEnvironment.isHeadless())bindUndo(Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        updatePalette();
    }
    private void bindUndo(int mask){
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,mask),"sql-undo");
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y,mask),"sql-redo");
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,mask|KeyEvent.SHIFT_DOWN_MASK),"sql-redo");
        getActionMap().put("sql-undo",new AbstractAction(){public void actionPerformed(ActionEvent e){undoEdit();}});
        getActionMap().put("sql-redo",new AbstractAction(){public void actionPerformed(ActionEvent e){redoEdit();}});
    }
    public void onUndoStateChanged(Runnable listener){undoStateChanged=listener==null?()->{}:listener;}
    public boolean canUndoEdit(){return undo.canUndo();}
    public boolean canRedoEdit(){return undo.canRedo();}
    public void undoEdit(){if(undo.canUndo()){replaying=true;try{undo.undo();}finally{replaying=false;highlightTimer.restart();undoStateChanged.run();}}}
    public void redoEdit(){if(undo.canRedo()){replaying=true;try{undo.redo();}finally{replaying=false;highlightTimer.restart();undoStateChanged.run();}}}
    public void editAtomically(Runnable action){beginEdit();try{action.run();}finally{endEdit();}}
    private void beginEdit(){if(editDepth++==0){compound=new CompoundEdit();compoundChanged=false;}}
    private void endEdit(){if(--editDepth==0){compound.end();if(compoundChanged&&!restoring&&!replaying)undo.addEdit(compound);compound=null;undoStateChanged.run();}}

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
                if (c == '`' || c == '[') {
                    int end = quotedEnd(text, i, c=='['?']':c);
                    doc.setCharacterAttributes(i, end - i, identifierStyle, true); i = end; continue;
                }
                if (Character.isDigit(c)) {
                    int end = i + 1; while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) end++;
                    doc.setCharacterAttributes(i, end - i, numberStyle, true); i = end; continue;
                }
                if (isWord(c)) {
                    int end = i + 1; while (end < text.length() && isWord(text.charAt(end))) end++;
                    if (keywords.contains(text.substring(i, end).toUpperCase(Locale.ROOT))) doc.setCharacterAttributes(i, end - i, keywordStyle, true);
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
        restoring=true;try{document.setDocumentFilter(null);setText(text);}finally{document.setDocumentFilter(filter);restoring=false;undo.discardAllEdits();undoStateChanged.run();}
    }

    private final class KeywordFilter extends DocumentFilter {
        @Override public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            beginEdit();try{fb.insertString(offset, string, attr);normalize(fb, offset + (string == null ? 0 : string.length()), string);}finally{endEdit();}
        }
        @Override public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            beginEdit();try{fb.replace(offset, length, text, attrs);normalize(fb, offset + (text == null ? 0 : text.length()), text);}finally{endEdit();}
        }
        @Override public void remove(FilterBypass fb,int offset,int length)throws BadLocationException{beginEdit();try{fb.remove(offset,length);}finally{endEdit();}}
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
            if (keywords.contains(upper) && !word.equals(upper)) fb.replace(start, end - start, upper, null);
        }
    }

    private static boolean isCode(String text, int position) {
        boolean single = false, doub = false, backtick = false, bracket = false, line = false, block = false;
        for (int i = 0; i < position; i++) {
            char c = text.charAt(i), next = i + 1 < position ? text.charAt(i + 1) : '\0';
            if (line) { if (c == '\n') line = false; continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (single) { if (c == '\\') i++; else if (c == '\'') single = false; continue; }
            if (doub) { if (c == '\\') i++; else if (c == '"') doub = false; continue; }
            if (backtick) { if (c == '`') backtick = false; continue; }
            if (bracket) { if (c == ']') { if(next==']')i++;else bracket=false; } continue; }
            if (c == '-' && next == '-') { line = true; i++; }
            else if (c == '#') line = true;
            else if (c == '/' && next == '*') { block = true; i++; }
            else if (c == '\'') single = true;
            else if (c == '"') doub = true;
            else if (c == '`') backtick = true;
            else if (c == '[') bracket = true;
        }
        return !single && !doub && !backtick && !bracket && !line && !block;
    }
}
