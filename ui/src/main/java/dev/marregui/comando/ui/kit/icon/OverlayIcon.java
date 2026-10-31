package dev.marregui.comando.ui.kit.icon;

import java.awt.Component;
import java.awt.Graphics;
import javax.swing.Icon;

/** One icon drawn over another of the same size: a badge, such as git's, on a folder. */
public final class OverlayIcon implements Icon {

    private final Icon base;
    private final Icon badge;

    public OverlayIcon(final Icon base, final Icon badge) {
        this.base = base;
        this.badge = badge;
    }

    @Override
    public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
        base.paintIcon(c, g, x, y);
        badge.paintIcon(c, g, x, y);
    }

    @Override
    public int getIconWidth() {
        return base.getIconWidth();
    }

    @Override
    public int getIconHeight() {
        return base.getIconHeight();
    }
}
