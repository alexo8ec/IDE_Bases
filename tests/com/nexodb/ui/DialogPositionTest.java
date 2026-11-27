package com.nexodb.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.nexodb.util.I18n;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public final class DialogPositionTest {
    private record Opened(Window owner, Rectangle bounds) {}

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("DialogPositionTest: skipped (requires a graphical display)");
            return;
        }

        Path folder = Files.createTempDirectory("adisdb-dialog-position-");
        Path destination = folder.resolve("resultado.csv");
        try {
            SwingUtilities.invokeAndWait(() -> verifyDialogs(destination));
            check(!Files.exists(destination), "Declining an export must leave its destination untouched.");
        } finally {
            Files.deleteIfExists(folder);
        }
        System.out.println("DialogPositionTest: OK (export, cell editors, custom dialog, nested owner and screen fallback)");
    }

    private static void verifyDialogs(Path destination) {
        I18n.initialize("es");
        FlatLightLaf.setup();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        JFrame frame = new JFrame("AdisDB dialog position test");
        frame.setBounds(screen.x + screen.width / 10, screen.y + screen.height / 8,
                screen.width * 4 / 5, screen.height * 17 / 20);
        JButton gridAnchor = addOffCenterAnchor(frame.getContentPane(), frame.getBounds());
        frame.setVisible(true);
        try {
            List<Opened> export = observe(2, () -> check(ExportChooser.choose(gridAnchor, "resultado") == null,
                    "Declining export should return no request."), (dialog, index) -> {
                if (index == 0) {
                    JFileChooser chooser = find(dialog, JFileChooser.class);
                    chooser.setSelectedFile(destination.toFile());
                    chooser.approveSelection();
                } else {
                    JOptionPane confirmation = find(dialog, JOptionPane.class);
                    Object no = confirmation.getOptions()[1];
                    check(no.equals(confirmation.getInitialValue()), "Export confirmation must default to No.");
                    confirmation.setValue(no);
                }
            });
            assertCentered(export.get(0), frame, screen, "Export file chooser");
            assertCentered(export.get(1), frame, screen, "Export confirmation");

            assertCentered(cancelCellEditor(gridAnchor, Types.LONGVARCHAR, "Texto largo de prueba"),
                    frame, screen, "Text cell editor");

            Opened custom = observe(1, () -> {
                CenteredDialog dialog = new CenteredDialog(frame, "Diálogo personalizado", Dialog.ModalityType.APPLICATION_MODAL);
                dialog.setBounds(screen.x + 18, screen.y + 24, 400, 175);
                dialog.setVisible(true);
            }, (dialog, index) -> dialog.dispose()).get(0);
            assertCentered(custom, frame, screen, "Custom dialog");

            JDialog nested = new JDialog(frame, "Ventana secundaria", Dialog.ModalityType.MODELESS);
            nested.setBounds(screen.x + screen.width / 4, screen.y + screen.height / 5,
                    screen.width / 2, screen.height / 2);
            JButton nestedAnchor = addOffCenterAnchor(nested.getContentPane(), nested.getBounds());
            nested.setVisible(true);
            try {
                assertCentered(cancelCellEditor(nestedAnchor, Types.DATE, "2026-11-27"),
                        nested, screen, "Date cell editor");
                check(DialogPosition.owner(nestedAnchor) == nested,
                        "A nested prompt must use the closest visible window.");
                nested.setVisible(false);
                check(DialogPosition.owner(nestedAnchor) == frame,
                        "A hidden owner must forward prompts to its visible owner.");
            } finally {
                nested.dispose();
            }
        } finally {
            frame.dispose();
        }

        JPanel detached = new JPanel();
        check(DialogPosition.owner(detached) == null, "A detached component has no visible dialog owner.");
        Opened startup = observe(1, () -> JOptionPane.showMessageDialog(DialogPosition.owner(detached), "Prueba"),
                (dialog, index) -> find(dialog, JOptionPane.class).setValue(JOptionPane.OK_OPTION)).get(0);
        Point center = GraphicsEnvironment.getLocalGraphicsEnvironment().getCenterPoint();
        assertPosition(startup.bounds(), center.x - startup.bounds().width / 2,
                center.y - startup.bounds().height / 2, "Ownerless prompt");
    }

    private static JButton addOffCenterAnchor(Container content, Rectangle window) {
        content.setLayout(null);
        JButton anchor = new JButton("Acción");
        anchor.setBounds(24, window.height - 110, 120, 35);
        content.add(anchor);
        return anchor;
    }

    private static Opened cancelCellEditor(Component anchor, int jdbcType, String value) {
        return observe(1, () -> check(!ResultCellEditorDialog.show(anchor, "Campo", value, jdbcType, true).accepted(),
                "Canceling the cell editor must preserve its original value."),
                (dialog, index) -> find(dialog, JOptionPane.class).setValue(JOptionPane.CANCEL_OPTION)).get(0);
    }

    private static List<Opened> observe(int count, Runnable show, BiConsumer<JDialog, Integer> respond) {
        Set<Window> existing = Set.copyOf(Arrays.asList(Window.getWindows()));
        List<Opened> opened = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AWTEventListener observer = event -> {
            if (event.getID() != WindowEvent.WINDOW_OPENED || !(event.getSource() instanceof JDialog dialog)
                    || existing.contains(dialog)) return;
            int index = opened.size();
            opened.add(new Opened(dialog.getOwner(), dialog.getBounds()));
            SwingUtilities.invokeLater(() -> {
                try {
                    respond.accept(dialog, index);
                } catch (RuntimeException | AssertionError error) {
                    failure.set(error);
                    dialog.dispose();
                }
            });
        };
        Timer deadline = new Timer(8000, event -> {
            failure.compareAndSet(null, new AssertionError("Timed out waiting for a dialog."));
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog && !existing.contains(window)) window.dispose();
            }
        });
        deadline.setRepeats(false);
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        toolkit.addAWTEventListener(observer, AWTEvent.WINDOW_EVENT_MASK);
        deadline.start();
        try {
            show.run();
        } finally {
            deadline.stop();
            toolkit.removeAWTEventListener(observer);
        }
        if (failure.get() != null) throw new AssertionError("Could not inspect the dialog.", failure.get());
        check(opened.size() == count, "Expected " + count + " dialogs, opened " + opened.size());
        return opened;
    }

    private static void assertCentered(Opened dialog, Window owner, Rectangle screen, String name) {
        check(dialog.owner() == owner, name + " used the wrong owner.");
        Rectangle reference = owner.getBounds();
        Rectangle bounds = dialog.bounds();
        int x = reference.x + (reference.width - bounds.width) / 2;
        int y = reference.y + (reference.height - bounds.height) / 2;
        if (screen.contains(new Rectangle(x, y, bounds.width, bounds.height))) {
            assertPosition(bounds, x, y, name);
        } else {
            check(screen.intersects(bounds), name + " must remain visible if it cannot fit centered.");
        }
    }

    private static void assertPosition(Rectangle bounds, int x, int y, String name) {
        check(Math.abs(bounds.x - x) <= 2 && Math.abs(bounds.y - y) <= 2,
                name + " at " + bounds.getLocation() + " should be centered at " + new Point(x, y));
    }

    private static <T extends Component> T find(Component root, Class<T> type) {
        T result = findComponent(root, type);
        if (result == null) throw new AssertionError("Missing dialog component " + type.getSimpleName());
        return result;
    }

    private static <T extends Component> T findComponent(Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T component = findComponent(child, type);
                if (component != null) return component;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
