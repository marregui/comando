package dev.marregui.comando.ui.kit.icon;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import javax.swing.Icon;

import com.formdev.flatlaf.FlatLaf;

import dev.marregui.comando.ui.kit.theme.Tint;

/**
 * An SVG drawn a share of the way from its own colours to another colour, read at each paint, so that a colour that
 * follows the theme ({@code Theme.THINKING}) is followed here: the Reflect button's bookmark, grey at no context and
 * the checkpoint's purple at the threshold. Disabled, it greys as every icon does. Changed and painted on the EDT.
 */
public final class BlendIcon implements Icon, FlatLaf.DisabledIconProvider {

    private final SvgIcon svg;
    private final Color to;
    private double share;

    /** {@code name}'s SVG, 16 pixels, at its own colours until {@link #share} moves it towards {@code to}. */
    public BlendIcon(final String name, final Color to) {
        this.to = to;
        svg = new SvgIcon("icons/" + name + ".svg", 16, 16, this::blend);
    }

    /** How far from the SVG's colours to the other: 0 its own, 1 the other's, clamped to that; the caller repaints. */
    public void share(final double fraction) {
        share = Math.clamp(fraction, 0.0, 1.0);
    }

    @Override
    public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
        svg.paintIcon(c, g, x, y);
    }

    @Override
    public int getIconWidth() {
        return svg.getIconWidth();
    }

    @Override
    public int getIconHeight() {
        return svg.getIconHeight();
    }

    @Override
    public Icon getDisabledIcon() {
        return svg.getDisabledIcon();
    }

    /**
     * Opaque, as the SVGs' fills are; the theme's own mix, since only the theme makes a colour. At no share, the SVG's
     * colours as every icon of the set paints them ({@link Icons#gilded}).
     */
    private Color blend(final Color from) {
        return share <= 0.0 ? Icons.gilded(from) : Tint.over(from, to, (float) share);
    }
}
