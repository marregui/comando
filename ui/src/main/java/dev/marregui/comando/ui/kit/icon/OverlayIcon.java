package dev.marregui.comando.ui.kit.icon;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;

/** One icon drawn over another of the same size: a badge, such as git's, on a folder. */
public final class OverlayIcon implements Icon {

    private final Icon base;
    private final Icon badge;

    public OverlayIcon(Icon base, Icon badge) {
        this.base = base;
        this.badge = badge;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
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
