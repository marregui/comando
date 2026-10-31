package dev.marregui.comando.ui.panels;

import java.awt.Color;
import java.awt.Graphics;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.UIManager;
import javax.swing.plaf.LayerUI;

import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * Greys a view of a session whose checkpoint runs (record 130), with the veil that greys a closing window: the views of
 * one session dim together, as one disabled group. It only paints: the view under it still scrolls and its text can
 * be selected and copied; what may not act is disabled by its owner. EDT only.
 */
final class LockVeil {

    private final JLayer<JComponent> layer;
    private boolean shown;

    LockVeil(final JComponent view) {
        layer = new JLayer<>(view, new LayerUI<>() {
            @Override
            public void paint(final Graphics g, final JComponent c) {
                super.paint(g, c);
                if (shown) {
                    g.setColor(Theme.VEIL);
                    g.fillRect(0, 0, c.getWidth(), c.getHeight());
                }
            }
        });
    }

    /** The colour of a locked view's title, a tab's: the look and feel's text of a disabled control. */
    static Color dimmed() {
        return UIManager.getColor("Label.disabledForeground");
    }

    /** The view under the veil, to lay out in the view's place. */
    JComponent component() {
        return layer;
    }

    /** Lays the veil over the view, or takes it off; repaints only on a change. */
    void show(final boolean locked) {
        if (locked != shown) {
            shown = locked;
            layer.repaint();
        }
    }

    boolean shown() {
        return shown;
    }
}
