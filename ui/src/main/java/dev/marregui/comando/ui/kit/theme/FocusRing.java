package dev.marregui.comando.ui.kit.theme;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.AbstractBorder;

import com.formdev.flatlaf.ui.FlatScrollPaneUI;

/**
 * The edge of a content view's scroll pane: a hairline in the tint while the view holds the keyboard, nothing
 * otherwise, so the user sees where typing goes (record 100: the tint marks focus). Not a look-and-feel resource, so a
 * theme change, which installs the look and feel again, keeps it; the tint is read at each paint.
 */
public final class FocusRing extends AbstractBorder {

    private static final FocusRing RING = new FocusRing();
    private static boolean listening;

    private FocusRing() {
    }

    /** Gives {@code scroll} the ring; the first call starts the one listener that repaints a ring as focus moves. EDT only. */
    public static void install(final JScrollPane scroll) {
        scroll.setBorder(RING);
        if (!listening) {
            listening = true;
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", e -> {
                repaintRingAround(e.getOldValue());
                repaintRingAround(e.getNewValue());
            });
        }
    }

    /** Whether {@code scroll}'s ring shows: its view, or an editor inside its table or tree, holds the keyboard. */
    public static boolean shows(final JScrollPane scroll) {
        return FlatScrollPaneUI.isPermanentFocusOwner(scroll);
    }

    private static void repaintRingAround(final Object focused) {
        if (focused instanceof Component c) {
            final JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, c);
            if (scroll != null && scroll.getBorder() == RING) {
                scroll.repaint();
            }
        }
    }

    @Override
    public void paintBorder(final Component c, final Graphics g, final int x, final int y, final int width, final int height) {
        if (c instanceof JScrollPane scroll && shows(scroll)) {
            g.setColor(Theme.tint().body);
            g.drawRect(x, y, width - 1, height - 1);
        }
    }

    @Override
    public Insets getBorderInsets(final Component c, final Insets insets) {
        insets.set(1, 1, 1, 1);
        return insets;
    }
}
