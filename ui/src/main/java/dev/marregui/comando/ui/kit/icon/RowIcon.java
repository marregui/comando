package dev.marregui.comando.ui.kit.icon;

import java.awt.Component;
import java.awt.Graphics;
import java.util.List;
import javax.swing.Icon;

/** Icons in a row, left to right, {@link #GAP} apart and centred on one line: two marks where a cell has one icon. */
public final class RowIcon implements Icon {

    static final int GAP = 2;

    private final List<Icon> icons;

    public RowIcon(final Icon... icons) {
        this.icons = List.of(icons);
    }

    @Override
    public void paintIcon(final Component c, final Graphics g, int x, final int y) {
        final int h = getIconHeight();
        for (final Icon i : icons) {
            i.paintIcon(c, g, x, y + (h - i.getIconHeight()) / 2);
            x += i.getIconWidth() + GAP;
        }
    }

    @Override
    public int getIconWidth() {
        return icons.stream().mapToInt(Icon::getIconWidth).sum() + Math.max(0, icons.size() - 1) * GAP;
    }

    @Override
    public int getIconHeight() {
        return icons.stream().mapToInt(Icon::getIconHeight).max().orElse(0);
    }
}
