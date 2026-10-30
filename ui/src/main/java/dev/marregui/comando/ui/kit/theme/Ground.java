package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.util.Arrays;
import java.util.Optional;

/**
 * The ground of every panel of the window (record 99): near-black, each a little toward a hue the tints sit well on,
 * the golds on the warm ones, silver and electric blue on the cool, tropical forest on the green. On each, the
 * transcript's ivory reads above 12:1, every tint's body above 7.5:1 and its shade above 4:1. {@link Theme#ground()}
 * is the one chosen. The six named here are the built-in themes' (record 126), read from their files; a theme file of
 * the user's brings its own.
 */
public final class Ground {

    public static final Ground GRAPHITE = builtIn("GRAPHITE", "graphite_gold");
    public static final Ground ESPRESSO = builtIn("ESPRESSO", "espresso_champagne");
    public static final Ground MIDNIGHT = builtIn("MIDNIGHT", "midnight_blue");
    public static final Ground EVERGREEN = builtIn("EVERGREEN", "evergreen_forest");
    public static final Ground AUBERGINE = builtIn("AUBERGINE", "aubergine_gold");
    public static final Ground SLATE = builtIn("SLATE", "slate_silver");
    private static final Ground[] BUILT_IN = {GRAPHITE, ESPRESSO, MIDNIGHT, EVERGREEN, AUBERGINE, SLATE};

    public final String label;
    public final Color color;
    private final String name;

    Ground(String name, String label, Color color) {
        this.name = name;
        this.label = label;
        this.color = color;
    }

    private static Ground builtIn(String name, String theme) {
        ThemeSpec s = BuiltInThemes.spec(theme);
        return new Ground(name, s.groundLabel(), s.ground());
    }

    /** The ground's name, kept in the preferences: {@code GRAPHITE}, or a theme file's id in capitals. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    /** The built-in grounds, in their fixed order. */
    public static Ground[] values() {
        return BUILT_IN.clone();
    }

    /** The ground named {@code name}: a built-in one, else one of the themes offered. */
    public static Optional<Ground> find(String name) {
        return Arrays.stream(BUILT_IN).filter(g -> g.name.equals(name)).findFirst()
                .or(() -> Arrays.stream(NamedTheme.values()).map(t -> t.ground).filter(g -> g.name.equals(name)).findFirst());
    }

    /** The ground named {@code name}; {@link #GRAPHITE} for a name no ground has. */
    public static Ground named(String name) {
        return find(name).orElse(GRAPHITE);
    }
}
