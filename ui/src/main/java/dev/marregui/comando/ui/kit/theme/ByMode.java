package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;

/**
 * A colour with one value on a dark ground and another on a light one (record 127): its value is the one for the
 * ground chosen, read each time it is asked, so that a foreground or a border set once with it follows a light theme
 * chosen later with no listener, as {@link OnGround} follows the ground.
 */
final class ByMode extends Color {

    private final int dark;
    private final int light;

    ByMode(final int dark, final int light) {
        super(dark);
        this.dark = 0xFF000000 | dark;
        this.light = 0xFF000000 | light;
    }

    @Override
    public int getRGB() {
        return Theme.groundChosen().light ? light : dark;
    }

    /** The value on a light ground, or on a dark one, whichever is chosen: a plain colour that never changes. */
    Color in(final boolean onLight) {
        return new Color(onLight ? light : dark);
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof Color c && c.getRGB() == getRGB();
    }

    @Override
    public int hashCode() {
        return getRGB();
    }
}
