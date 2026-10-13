package dev.marregui.comando.ui.widget.capability;

import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The one implementation of each capability's user side: the shell wires the same keys and menu items for
 * every widget, and these find the capability of the widget under the focus.
 */
public final class Capabilities {

    /** Client property a widget's root component carries to name its capabilities' owner. */
    public static final String OWNER = "comando.capabilities";

    private Capabilities() {
    }

    /** The nearest capability owner of type {@code type} at or above the focused component. */
    public static <T> T focused(Class<T> type) {
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        for (; c != null; c = c.getParent()) {
            if (c instanceof JComponent jc && type.isInstance(jc.getClientProperty(OWNER))) {
                return type.cast(jc.getClientProperty(OWNER));
            }
        }
        return null;
    }

    public static void mark(JComponent root, Object owner) {
        root.putClientProperty(OWNER, owner);
    }

    public static void copy(Copyable c) {
        String text = c.copyText();
        if (text != null && !text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        }
    }

    /** Asks for a file on the EDT, takes the widget's snapshot there, and writes it on a virtual thread. */
    public static void export(Component parent, Exportable e) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(System.getProperty("user.home"), e.exportName()));
        if (chooser.showSaveDialog(SwingUtilities.getWindowAncestor(parent)) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        Exportable.Export export = e.prepare();
        Thread.ofVirtual().name("export").start(() -> {
            try {
                export.writeTo(file);
            } catch (IOException | RuntimeException ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent, "Cannot save " + file + ": " + ex.getMessage(),
                        "Export", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    static String muted() {
        return Theme.hex(Theme.MUTED);
    }
}
