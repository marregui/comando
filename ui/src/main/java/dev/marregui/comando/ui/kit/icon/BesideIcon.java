package dev.marregui.comando.ui.kit.icon;

import java.awt.Component;
import java.awt.Graphics;
import javax.swing.Icon;

/** Two icons side by side, the first at the left, each centred in the taller one's height: a cue before a mark. */
public final class BesideIcon implements Icon {

    private final Icon left;
    private final Icon right;

    public BesideIcon(final Icon left, final Icon right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
        final int h = getIconHeight();
        left.paintIcon(c, g, x, y + (h - left.getIconHeight()) / 2);
        right.paintIcon(c, g, x + left.getIconWidth(), y + (h - right.getIconHeight()) / 2);
    }

    @Override
    public int getIconWidth() {
        return left.getIconWidth() + right.getIconWidth();
    }

    @Override
    public int getIconHeight() {
        return Math.max(left.getIconHeight(), right.getIconHeight());
    }
}
