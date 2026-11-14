package com.nexodb.ui;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class CompletionInteractionTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                QueryPanel panel = new QueryPanel(text -> {}, busy -> {});
                SqlEditorPane editor = field(panel, "editor", SqlEditorPane.class);
                JPopupMenu popup = field(panel, "completionPopup", JPopupMenu.class);
                @SuppressWarnings("unchecked") JList<String> list = field(panel, "completionList", JList.class);
                @SuppressWarnings("unchecked") DefaultListModel<String> model = field(panel, "completionModel", DefaultListModel.class);
                if (popup.isFocusable() || list.isFocusable()) throw new AssertionError("Completion popup must not steal focus");
                editor.setText("sele");
                editor.setCaretPosition(editor.getDocument().getLength());
                model.addElement("SELECT");
                list.setSelectedIndex(0);
                Method accept = QueryPanel.class.getDeclaredMethod("acceptCompletion");
                accept.setAccessible(true);
                accept.invoke(panel);
                if (!"SELECT".equals(editor.getText())) throw new AssertionError("Completion was not inserted: " + editor.getText());
                editor.undoEdit();if(!"sele".equals(editor.getText()))throw new AssertionError("Completion must undo in one step: "+editor.getText());editor.redoEdit();
                @SuppressWarnings("unchecked") java.util.Set<String> tables = field(panel,"tableWords",java.util.Set.class);
                tables.add("ct_ventas");model.clear();model.addElement("ct_ventas");list.setSelectedIndex(0);
                editor.setText("SELECT * FROM ct_");editor.setCaretPosition(editor.getDocument().getLength());accept.invoke(panel);
                if(!editor.getText().equals("SELECT * FROM \"ct_ventas\" AS v"))throw new AssertionError("Table alias missing: "+editor.getText());
                if(editor.getCaretPosition()!=editor.getDocument().getLength())throw new AssertionError("Caret must follow the completed alias");
                if(editor.getFocusTraversalKeysEnabled())throw new AssertionError("Tab must reach the completion action");
                editor.undoEdit();if(!editor.getText().equals("SELECT * FROM ct_"))throw new AssertionError("Alias completion must undo atomically");
                editor.restoreText("SELECT * FROM ct_ WHERE 1=1");editor.setCaretPosition("SELECT * FROM ct_".length());
                popup.setVisible(true);
                editor.getActionMap().get("completion-tab").actionPerformed(new java.awt.event.ActionEvent(editor,0,"tab"));
                if(!editor.getText().equals("SELECT * FROM \"ct_ventas\" AS v WHERE 1=1"))throw new AssertionError("Tab completion lost SQL suffix: "+editor.getText());
                if(editor.getCaretPosition()!= "SELECT * FROM \"ct_ventas\" AS v".length())throw new AssertionError("Tab caret must follow insertion");
                if(popup.isVisible())throw new AssertionError("Completion must close after Tab");
                editor.getActionMap().get("completion-tab").actionPerformed(new java.awt.event.ActionEvent(editor,0,"tab"));
                if(!editor.getText().contains("AS v\t WHERE"))throw new AssertionError("Tab without completion must indent");
                checkColumnAcceptance(panel, editor, popup, list, model);
                checkEscape(panel, editor, popup);
                editor.setText("");
                editor.getDocument().insertString(0, "select ", null);
                if (!"SELECT ".equals(editor.getText())) throw new AssertionError("Keyword was not uppercased: " + editor.getText());
                editor.setText("");
                editor.getDocument().insertString(0, "'from '", null);
                if (!"'from '".equals(editor.getText())) throw new AssertionError("String literal was modified: " + editor.getText());
                System.out.println("CompletionInteractionTest: OK");
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
    }

    private static void checkColumnAcceptance(QueryPanel panel, SqlEditorPane editor, JPopupMenu popup,
            JList<String> list, DefaultListModel<String> model) throws Exception {
        @SuppressWarnings("unchecked") java.util.Set<String> columns = field(panel, "columnWords", java.util.Set.class);
        columns.add("identificacion");
        String prefix = "SELECT * FROM `ct_ventas` AS v\nJOIN `ct_clientes` AS c ON v.id_cliente=c.";
        for (String newline : new String[]{"\n", "\r\n"}) {
            for (String typed : new String[]{"i", "ide", "identificacion"}) {
                for (int key : new int[]{java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.VK_TAB, 0}) {
                    String before = prefix + typed + " AND c.activo=1";
                    editor.restoreText(before.replace("\n", newline));
                    editor.getDocument().putProperty(javax.swing.text.DefaultEditorKit.EndOfLineStringProperty, newline);
                    editor.setCaretPosition(prefix.length() + typed.length());
                    Method token = QueryPanel.class.getDeclaredMethod("completionToken");token.setAccessible(true);
                    if (!("c." + typed).equals(token.invoke(panel))) throw new AssertionError("Completion filtering must use document offsets");
                    model.clear();model.addElement("identificacion");list.setSelectedIndex(0);
                    popup.setVisible(true);
                    if (key == 0) {
                        list.setSize(330, 24);
                        var event = new java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_RELEASED,
                                System.currentTimeMillis(), 0, 10, 10, 1, false, java.awt.event.MouseEvent.BUTTON1);
                        for (var listener : list.getMouseListeners()) {
                            if (listener.getClass().getEnclosingClass() == QueryPanel.class) listener.mouseReleased(event);
                        }
                    } else press(editor, key);
                    String expected = prefix + "identificacion AND c.activo=1";
                    String actual = editor.getDocument().getText(0, editor.getDocument().getLength());
                    if (!expected.equals(actual)) throw new AssertionError("Completion duplicated its prefix (key=" + key + "): " + actual);
                    if (editor.getCaretPosition() != prefix.length() + "identificacion".length()) throw new AssertionError("Column caret must follow insertion");
                    if (popup.isVisible()) throw new AssertionError("Completion must close after acceptance");
                    editor.undoEdit();
                    if (!before.equals(editor.getDocument().getText(0, editor.getDocument().getLength()))) throw new AssertionError("Column completion must undo in one step");
                    editor.redoEdit();
                    if (!expected.equals(editor.getDocument().getText(0, editor.getDocument().getLength()))) throw new AssertionError("Column completion must redo in one step");
                }
            }
        }
    }

    private static void checkEscape(QueryPanel panel, SqlEditorPane editor, JPopupMenu popup) throws Exception {
        javax.swing.Timer timer = field(panel, "completionTimer", javax.swing.Timer.class);
        long generation = field(panel, "completionGeneration", Long.class);
        String before = editor.getText();
        timer.restart();popup.setVisible(true);
        press(editor, java.awt.event.KeyEvent.VK_ESCAPE);
        if (popup.isVisible()) throw new AssertionError("Escape must hide completion");
        if (timer.isRunning()) throw new AssertionError("Escape must stop queued completion");
        if (field(panel, "completionGeneration", Long.class) <= generation) throw new AssertionError("Escape must discard pending completion results");
        if (!before.equals(editor.getText())) throw new AssertionError("Escape must preserve SQL");
        timer.restart();press(editor, java.awt.event.KeyEvent.VK_ESCAPE);
        if (timer.isRunning()) throw new AssertionError("Escape must cancel completion before it appears");
        SwingWorker<java.util.List<String>, Void> pending = new SwingWorker<>() {
            @Override protected java.util.List<String> doInBackground() { return java.util.List.of("identificacion"); }
        };
        Field worker = QueryPanel.class.getDeclaredField("columnWorker");worker.setAccessible(true);worker.set(panel, pending);
        press(editor, java.awt.event.KeyEvent.VK_ESCAPE);
        if (!pending.isCancelled()) throw new AssertionError("Escape must cancel pending column loading");
        timer.restart();popup.setVisible(true);
        for (var listener : popup.getPopupMenuListeners()) listener.popupMenuCanceled(new javax.swing.event.PopupMenuEvent(popup));
        if (popup.isVisible() || timer.isRunning()) throw new AssertionError("Swing popup cancellation must dismiss pending completion");
    }

    private static void press(SqlEditorPane editor, int key) {
        Object name = editor.getInputMap().get(KeyStroke.getKeyStroke(key, 0));
        editor.getActionMap().get(name).actionPerformed(new java.awt.event.ActionEvent(editor, 0, name.toString()));
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
