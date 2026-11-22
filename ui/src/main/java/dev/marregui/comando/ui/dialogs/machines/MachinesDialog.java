package dev.marregui.comando.ui.dialogs.machines;

import java.awt.Dialog;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import javax.swing.JButton;
import javax.swing.JDialog;

import dev.marregui.comando.core.port.MachinesPort;
import dev.marregui.comando.ui.dialogs.FormRows;
import dev.marregui.comando.ui.kit.resource.ResourceActions;

/**
 * This machine and the machines paired with it in a window over comando's (record 189): the {@link MachinesEditor},
 * Close and Pair. Each change goes to the port as the user makes it, so Close, Esc and the window's close drop
 * nothing but a token typed and not paired. EDT only.
 */
public final class MachinesDialog {

    public static final String TITLE = "Machines";

    private final JDialog dialog;
    private final MachinesEditor editor;

    private MachinesDialog(final Window owner, final MachinesPort port) {
        editor = new MachinesEditor(port, ResourceActions.Questions.MODAL, MachinesDialog::copy);
        dialog = new JDialog(owner, TITLE, Dialog.ModalityType.APPLICATION_MODAL);
        final JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        FormRows.frame(dialog, owner, editor.component(), FormRows.south(close, editor.pairButton()), dialog::dispose);
    }

    /** Opens the window over {@code owner}, paired machine {@code select} selected when named; returns once closed. */
    public static void show(final Window owner, final MachinesPort port, final String select) {
        final MachinesDialog d = new MachinesDialog(owner, port);
        if (select != null && !select.isEmpty()) {
            d.editor.select(select);
        }
        d.dialog.setVisible(true);
    }

    private static void copy(final String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }
}
