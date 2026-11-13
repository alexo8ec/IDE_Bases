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
