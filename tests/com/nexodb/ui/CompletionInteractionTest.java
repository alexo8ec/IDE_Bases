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
                editor.undoEdit();if(!editor.getText().equals("SELECT * FROM ct_"))throw new AssertionError("Alias completion must undo atomically");
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

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
