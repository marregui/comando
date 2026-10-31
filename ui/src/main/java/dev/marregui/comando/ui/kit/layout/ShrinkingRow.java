package dev.marregui.comando.ui.kit.layout;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.Set;

/**
 * A row of components, left to right, each at its preferred width while they fit. When they do not, the components
 * named {@code shrinkable} (labelled chips) give up width in proportion to what they can spare, down to
 * {@code minimum} each, and their text ends in an ellipsis; the others (icon buttons) keep their size. Components are
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
        int total = 0;
        int spare = 0;
        int visible = 0;
        for (final Component c : cs) {
            if (c.isVisible()) {
                final int w = c.getPreferredSize().width;
                total += w;
                visible++;
                if (shrinkable.contains(c)) {
                    spare += Math.max(0, w - minimum);
                }
            }
        }
        total += Math.max(0, visible - 1) * gap;
        final int excess = Math.max(0, total - available);
        final double share = spare == 0 ? 0.0 : Math.min(1.0, (double) excess / spare);
        int x = in.left;
        for (final Component c : cs) {
            if (!c.isVisible()) {
                continue;
            }
            final Dimension d = c.getPreferredSize();
            int w = d.width;
            if (shrinkable.contains(c)) {
                w -= (int) Math.ceil(Math.max(0, w - minimum) * share);
            }
            final int h = Math.min(d.height, height);
            c.setBounds(x, in.top + (height - h) / 2, w, h);
            x += w + gap;
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
