package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.util.Arrays;

/**
 * The tint of the window: the transcript's headings, list marks and table heads, the bar beside a turn's report and
 * down a tooltip's edge, new files in the Files tab, the tab card's title and summary heading and About's accents.
 * Three steps each, as polished metal reads: a pale highlight, the body, a darker shade; large type and the report's
 * bar run from one to the next, small text takes the body alone. Each is light on the transcript's ground, its body
 * above 8:1 and its shade above 4.5:1 there. {@link Theme#tint()} is the one chosen (records 83, 96). The six named
 * here are the built-in themes' (record 126), read from their files; a theme file of the user's brings its own.
 */
public final class Tint {

    public static final Tint TREASURE = builtIn("TREASURE", "graphite_gold");
    public static final Tint YELLOW = builtIn("YELLOW", "aubergine_gold");
    public static final Tint CHAMPAGNE = builtIn("CHAMPAGNE", "espresso_champagne");
    public static final Tint SILVER = builtIn("SILVER", "slate_silver");
    public static final Tint TROPICAL_FOREST = builtIn("TROPICAL_FOREST", "evergreen_forest");
    public static final Tint ELECTRIC_BLUE = builtIn("ELECTRIC_BLUE", "midnight_blue");
    public static final Tint OCHRE = builtIn("OCHRE", "paper_ochre");
    private static final Tint[] BUILT_IN = {TREASURE, YELLOW, CHAMPAGNE, SILVER, TROPICAL_FOREST, ELECTRIC_BLUE, OCHRE};

    public final String label;
    public final Color highlight;
    public final Color body;
    public final Color shade;
    /** Whether the tint is made for a light ground: deep steps on a pale one (record 127). */
    public final boolean light;
    private final String name;

    Tint(String name, String label, Color highlight, Color body, Color shade, boolean light) {
        this.name = name;
        this.light = light;
        this.label = label;
        this.highlight = highlight;
        this.body = body;
        this.shade = shade;
    }

    private static Tint builtIn(String name, String theme) {
        ThemeSpec s = BuiltInThemes.spec(theme);
        return new Tint(name, s.tintLabel(), s.highlight(), s.body(), s.shade(), s.light());
    }

    /** The tint's name, kept in the preferences: {@code TREASURE}, or a theme file's id in capitals. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    /** The built-in tints, in their fixed order. */
    public static Tint[] values() {
        return BUILT_IN.clone();
    }

    /** The tint named {@code name}: a built-in one, else one of the themes offered; {@link #TREASURE} for no tint's. */
    public static Tint named(String name) {
        return Arrays.stream(BUILT_IN).filter(t -> t.name.equals(name)).findFirst()
                .or(() -> Arrays.stream(NamedTheme.values()).map(t -> t.tint).filter(t -> t.name.equals(name)).findFirst())
                .orElse(TREASURE);
    }

    /** The ramp at {@code t}, 0 the highlight, 1 the shade: highlight to body in the first 45 %, then to the shade. */
    public Color at(float t) {
        float u = Math.clamp(t, 0f, 1f);
        return u < 0.45f ? mix(highlight, body, u / 0.45f) : mix(body, shade, (u - 0.45f) / 0.55f);
    }

    /**
     * The paint of polished metal from {@code top} to {@code bottom} (y, in the space it fills): highlight, then body
     * at 45 %, then the shade, or for letters the ramp at 85 %, whose bottom would read too dark at small sizes.
     */
    public LinearGradientPaint ramp(float top, float bottom, boolean letters) {
        return new LinearGradientPaint(0, top, 0, Math.max(top + 1, bottom), new float[]{0f, 0.45f, 1f},
                new Color[]{highlight, body, letters ? at(0.85f) : shade});
    }

    /** The line under a first- or second-level heading: the shade at 70 % over the panels' ground. */
    public Color headingRule() {
        return over(Theme.ground(), shade, 0.7f);
    }

    /** The band behind a table's head: the shade at 22 % over the panels' ground. */
    public Color tableHeadGround() {
        return over(Theme.ground(), shade, 0.22f);
    }

    /** {@code c} laid over {@code ground} at {@code amount} (0 to 1): a tinted band or rule on a dark ground. */
    public static Color over(Color ground, Color c, float amount) {
        return mix(ground, c, amount);
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
