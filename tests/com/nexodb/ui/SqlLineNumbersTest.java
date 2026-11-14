package com.nexodb.ui;

import com.nexodb.db.SchemaItem;
import com.nexodb.db.StoredObjectService;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Set;
import javax.swing.*;

/** Regression coverage for row headers that stayed at line 1 while SQL scrolled. */
public final class SqlLineNumbersTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                SqlEditorPane editor = new SqlEditorPane();
                editor.restoreText(("SELECT 1;\n\n-- comment\n").repeat(80));
                SqlLineNumbers numbers = new SqlLineNumbers(editor);
                JScrollPane scroll = new JScrollPane(editor);
                scroll.setRowHeaderView(numbers);
                verifyScrolling(scroll, editor, numbers);
                editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 20));
                equal(editor.getFont(), numbers.getFont(), "Gutter font must follow editor");
                editor.append("SELECT 2;\n".repeat(100));
                verifyScrolling(scroll, editor, numbers);
                editor.restoreText("SELECT 1;");
                verifyScrolling(scroll, editor, numbers);

                String sql = "CREATE PROCEDURE sample()\nBEGIN\n"
                        + "    SELECT 1;\n".repeat(140) + "END";
                var item = new SchemaItem(SchemaItem.Kind.PROCEDURE, "test", null, "sample", "");
                var definition = new StoredObjectService.Definition(sql, "", "", "");
                var panel = new StoredProcedureDebugPanel(null, new char[0], item, definition, s -> {});
                SqlEditorPane source = field(panel, "source");
                JComponent gutter = field(panel, "gutter");
                JScrollPane code = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, source);
                verifyScrolling(code, source, gutter);
                layout(code, 700, 230);
                var root = source.getDocument().getDefaultRootElement();
                var rect = source.modelToView2D(root.getElement(90).getStartOffset());
                code.getViewport().setViewPosition(new Point(0, (int) rect.getY() - 43));
                equal(code.getViewport().getViewPosition().y, code.getRowHeader().getViewPosition().y,
                        "Debugger numbers must follow partial-line scrolling");
                // Click the visible marker through viewport coordinates, as a real mouse does.
                Point visible = new Point(10, (int) rect.getCenterY() - code.getRowHeader().getViewPosition().y);
                Point target = SwingUtilities.convertPoint(code.getRowHeader(), visible, gutter);
                gutter.dispatchEvent(new MouseEvent(gutter, MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(), 0, target.x, target.y, 1, false));
                Set<Integer> breakpoints = field(panel, "breakpoints");
                equal(Set.of(91), breakpoints, "Click must toggle the displayed SQL line");
                BufferedImage image = new BufferedImage(code.getRowHeader().getWidth(),
                        code.getRowHeader().getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try { code.getRowHeader().paint(graphics); } finally { graphics.dispose(); }
                equal(new Color(0xD3303E).getRGB(), image.getRGB(10, visible.y),
                        "Breakpoint marker must paint beside the clicked line after scrolling");
                panel.dispose();
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        System.out.println("SQL line numbers: scrolling, resizing, edits and debugger markers OK");
    }

    private static void verifyScrolling(JScrollPane scroll, SqlEditorPane editor, JComponent gutter) {
        for (Dimension size : new Dimension[]{new Dimension(700, 230), new Dimension(520, 340)}) {
            layout(scroll, size.width, size.height);
            equal(editor.getPreferredSize().height, gutter.getPreferredSize().height,
                    "Gutter must cover the full SQL document");
            int maximum = scroll.getViewport().getViewSize().height - scroll.getViewport().getExtentSize().height;
            for (int y : new int[]{0, Math.max(0, maximum / 2 + 7), Math.max(0, maximum)}) {
                scroll.getVerticalScrollBar().setValue(y);
                equal(scroll.getViewport().getViewPosition().y, scroll.getRowHeader().getViewPosition().y,
                        "Line numbers must scroll with SQL at y=" + y);
            }
        }
    }

    private static void layout(JScrollPane scroll, int width, int height) {
        scroll.setSize(width, height);
        for (int pass = 0; pass < 3; pass++) {
            scroll.doLayout();
            scroll.getViewport().doLayout();
            scroll.getRowHeader().doLayout();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
