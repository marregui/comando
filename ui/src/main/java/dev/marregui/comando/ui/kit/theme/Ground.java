package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.util.Arrays;

/**
 * The ground of every panel of the window (record 99): near-black, each a little toward a hue the tints sit well on,
 * the golds on the warm ones, silver and electric blue on the cool, tropical forest on the green. On each, the
 * transcript's ivory reads above 12:1, every tint's body above 7.5:1 and its shade above 4:1. {@link Theme#ground()}
 * is the one chosen.
 */
public enum Ground {

    GRAPHITE("Graphite", 0x121212),
    ONYX("Onyx", 0x0A0A0B),
    ESPRESSO("Espresso", 0x16120E),
    MIDNIGHT("Midnight ink", 0x0D1220),
    EVERGREEN("Evergreen", 0x0C1511),
    AUBERGINE("Aubergine", 0x150F19),
    SLATE("Slate", 0x1B1D21);

    public final String label;
    public final Color color;

    Ground(String label, int rgb) {
        this.label = label;
        this.color = new Color(rgb);
    }

    /** The ground named {@code name}, as {@link #name()} gives it; {@link #GRAPHITE} for a name no ground has. */
    public static Ground named(String name) {
        return Arrays.stream(values()).filter(g -> g.name().equals(name)).findFirst().orElse(GRAPHITE);
    }
}
