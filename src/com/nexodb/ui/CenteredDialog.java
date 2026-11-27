package com.nexodb.ui;

import javax.swing.JDialog;
import java.awt.Frame;
import java.awt.Window;

/** Positions custom dialogs after their layout and size have been set. */
class CenteredDialog extends JDialog {
    CenteredDialog(Window owner, String title, ModalityType modality) {
        super(owner, title, modality);
    }

    CenteredDialog(Frame owner, String title, boolean modal) {
        super(owner, title, modal);
    }

    CenteredDialog(Frame owner, boolean modal) {
        super(owner, modal);
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible && !isVisible()) {
            setLocationRelativeTo(DialogPosition.owner(getOwner()));
        }
        super.setVisible(visible);
    }
}
