package dev.marregui.comando.ui.kit.layout;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.Set;

/**
 * A row of components, left to right, each at its preferred width while they fit. When they do not, the components
 * named {@code shrinkable} (labelled chips) give up width from the right: the last one gives way down to
 * {@code minimum} before the one before it gives any, so a change in a later chip's text moves nothing to its left.
 * A chip that gives way ends its text in an ellipsis; the others (icon buttons) keep their size. Components are
 * centred vertically. What still does not fit is clipped at the right edge.
 */
public final class ShrinkingRow implements LayoutManager {

    private final int gap;
    private final int minimum;
    private final Set<Component> shrinkable;

    public ShrinkingRow(final int gap, final int minimum, final Set<Component> shrinkable) {
        this.gap = gap;
        this.minimum = minimum;
        this.shrinkable = Set.copyOf(shrinkable);
    }

    @Override
    public void layoutContainer(final Container parent) {
        final Insets in = parent.getInsets();
        final int available = parent.getWidth() - in.left - in.right;
        final int height = parent.getHeight() - in.top - in.bottom;
        final Component[] cs = parent.getComponents();
        final int[] widths = new int[cs.length];
        int total = 0;
        int visible = 0;
        for (int i = 0; i < cs.length; i++) {
            if (cs[i].isVisible()) {
                widths[i] = cs[i].getPreferredSize().width;
                total += widths[i];
                visible++;
            }
        }
        total += Math.max(0, visible - 1) * gap;
        int excess = Math.max(0, total - available);
        for (int i = cs.length - 1; i >= 0 && excess > 0; i--) {
            if (cs[i].isVisible() && shrinkable.contains(cs[i])) {
                final int given = Math.min(excess, Math.max(0, widths[i] - minimum));
                widths[i] -= given;
                excess -= given;
            }
        }
        int x = in.left;
        for (int i = 0; i < cs.length; i++) {
            if (!cs[i].isVisible()) {
                continue;
            }
            final int h = Math.min(cs[i].getPreferredSize().height, height);
            cs[i].setBounds(x, in.top + (height - h) / 2, widths[i], h);
            x += widths[i] + gap;
        }
    }

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
        return size(parent, false);
    }

    @Override
    public Dimension minimumLayoutSize(final Container parent) {
        return size(parent, true);
    }

    private Dimension size(final Container parent, final boolean least) {
        final Insets in = parent.getInsets();
        int w = 0;
        int h = 0;
        int visible = 0;
        for (final Component c : parent.getComponents()) {
            if (c.isVisible()) {
                final Dimension d = c.getPreferredSize();
                w += least && shrinkable.contains(c) ? Math.min(d.width, minimum) : d.width;
                h = Math.max(h, d.height);
                visible++;
            }
        }
        w += Math.max(0, visible - 1) * gap;
        return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
    }

    @Override
    public void addLayoutComponent(final String name, final Component comp) {
    }

    @Override
    public void removeLayoutComponent(final Component comp) {
    }
}
