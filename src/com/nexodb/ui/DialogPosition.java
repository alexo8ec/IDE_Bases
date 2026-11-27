package com.nexodb.ui;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Window;

public final class DialogPosition {
    private DialogPosition() {}

    /**
     * Use the visible window as Swing's placement reference. A detached component
     * or a dialog with no visible owner uses Swing's screen-centered fallback.
     */
    public static Window owner(Component invoker) {
        if (invoker == null) return null;
        Window window = invoker instanceof Window owner ? owner : SwingUtilities.getWindowAncestor(invoker);
        while (window != null && !window.isShowing()) {
            window = window.getOwner();
        }
        return window;
    }
}
