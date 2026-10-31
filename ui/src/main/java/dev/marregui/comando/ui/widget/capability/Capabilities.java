package dev.marregui.comando.ui.widget.capability;

import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import javax.swing.JComponent;

import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;

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
    public static <T> T focused(final Class<T> type) {
        return at(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(), type);
    }

    /** The nearest capability owner of type {@code type} at or above {@code c}; null when there is none. */
    public static <T> T at(Component c, final Class<T> type) {
        for (; c != null; c = c.getParent()) {
            if (c instanceof JComponent jc && type.isInstance(jc.getClientProperty(OWNER))) {
                return type.cast(jc.getClientProperty(OWNER));
            }
        }
        return null;
    }

    /**
     * The first capability owner of type {@code type} at or below {@code root}, breadth first, among the
     * showing components; null when there is none. Lets a key act on the view in front when the focus is elsewhere.
     */
    public static <T> T within(final Component root, final Class<T> type) {
        final ArrayDeque<Component> queue = new ArrayDeque<>();
        if (root != null) {
            queue.add(root);
        }
        while (!queue.isEmpty()) {
            final Component c = queue.poll();
            if (!c.isShowing()) {
                continue;
            }
            if (c instanceof JComponent jc && type.isInstance(jc.getClientProperty(OWNER))) {
                return type.cast(jc.getClientProperty(OWNER));
            }
            if (c instanceof Container k) {
                queue.addAll(Arrays.asList(k.getComponents()));
            }
        }
        return null;
    }

    /** The root each owner marked, weakly, so the find bar can tell when the view it searches is gone. */
    private static final Map<Object, WeakReference<JComponent>> ROOTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Makes {@code owner} the capability owner of {@code root} and everything under it. */
    public static void mark(final JComponent root, final Object owner) {
        root.putClientProperty(OWNER, owner);
        ROOTS.put(owner, new WeakReference<>(root));
    }

    /** The component {@code owner} marked, or null. */
    public static JComponent rootOf(final Object owner) {
        final WeakReference<JComponent> r = ROOTS.get(owner);
        return r == null ? null : r.get();
    }

    /** Copies the owner's text; false when there was none, so nothing was copied. */
    public static boolean copy(final Copyable c) {
        final String text = c.copyText();
        if (text != null && !text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            return true;
        }
        return false;
    }

    /**
     * Asks for a file, starting in {@code dir}, takes the widget's snapshot on the EDT, and writes it on the window's
     * {@code export} errand; {@code outcome} then gets the file and null, or the file and the error, on the EDT.
     * Nothing happens when the user cancels.
     */
    public static void export(final Component parent, final Exportable e, final Path dir, final BiConsumer<Path, String> outcome) {
        final Optional<Path> chosen = FileChoosers.save(parent, "Export", dir, e.exportName());
        if (chosen.isEmpty()) {
            return;
        }
        final Path file = chosen.get();
        final Exportable.Export export = e.prepare();
        UiErrands.start("export", () -> {
            String error = null;
            try {
                export.writeTo(file);
            } catch (IOException | RuntimeException ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            final String failed = error;
            Edt.later(() -> outcome.accept(file, failed));
        });
    }
}
