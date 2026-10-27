package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.util.Arrays;
import java.util.Locale;

/**
 * The tint of the window: the transcript's headings, list marks and table heads, the bar beside a turn's report and down
 * a tooltip's edge, new files in the Files tab, the tab card's title and summary heading and About's accents. Three
 * steps each, as polished metal reads: a pale highlight, the body, a darker shade; large type and the report's bar run
 * from one to the next, small text takes the body alone. Each is light on the transcript's ground, its body above 8:1 and
 * its shade above 4.5:1 there. {@link Theme#tint()} is the one chosen. The golds came first
 * (record 83); silver, tropical forest and electric blue were added with the choice in Actions (record 96).
 */
public enum Tint {

    TREASURE("Treasure gold", 0xFFEDAA, 0xE7C55A, 0x9E8230),
    YELLOW("18k yellow gold", 0xF7E9BE, 0xDDC27C, 0xA08442),
    CHAMPAGNE("Champagne gold", 0xF1E6CA, 0xD2BE90, 0x8F7C55),
    SILVER("Silver", 0xF4F6F9, 0xC3CAD3, 0x7B8591),
    TROPICAL_FOREST("Tropical forest", 0xCFF5DC, 0x62CC94, 0x339966),
    ELECTRIC_BLUE("Electric blue", 0xD6ECFF, 0x63B5FF, 0x3A80E0);

    public final String label;
    public final Color highlight;
    public final Color body;
    public final Color shade;

    Tint(String label, int highlight, int body, int shade) {
        this.label = label;
        this.highlight = new Color(highlight);
        this.body = new Color(body);
        this.shade = new Color(shade);
    }

    /** The tint named {@code name}, as {@link #name()} gives it; {@link #TREASURE} for a name no tint has. */
    public static Tint named(String name) {
        return Arrays.stream(values()).filter(t -> t.name().equals(name)).findFirst().orElse(TREASURE);
    }

    /** The command that chooses this tint: {@code tint.treasure}, {@code tint.tropical_forest}. */
    public String commandId() {
        return "tint." + name().toLowerCase(Locale.ROOT);
    }

    /** The ramp at {@code t}, 0 the highlight's end, 1 the shade's: highlight to body in the first 45 %, then to the shade. */
    public Color at(float t) {
        float u = Math.clamp(t, 0f, 1f);
        return u < 0.45f ? mix(highlight, body, u / 0.45f) : mix(body, shade, (u - 0.45f) / 0.55f);
    }

    /**
     * The paint of polished metal from {@code top} to {@code bottom} (y, in the space it fills): highlight, then body at
     * 45 %, then the shade, or for letters the ramp at 85 %, whose bottom would read too dark at small sizes.
     */
    public LinearGradientPaint ramp(float top, float bottom, boolean letters) {
        return new LinearGradientPaint(0, top, 0, Math.max(top + 1, bottom), new float[]{0f, 0.45f, 1f},
                new Color[]{highlight, body, letters ? at(0.85f) : shade});
    }

    /** The line under a first- or second-level heading: the shade at 70 % over the transcript's ground. */
    public Color headingRule() {
        return over(Theme.TRANSCRIPT, shade, 0.7f);
    }

    /** The band behind a table's head: the shade at 22 % over the transcript's ground. */
    public Color tableHeadGround() {
        return over(Theme.TRANSCRIPT, shade, 0.22f);
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
