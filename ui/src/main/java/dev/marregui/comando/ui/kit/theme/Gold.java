package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.util.Arrays;

/**
 * The gold of the window: the transcript's headings, list marks and table heads, the bar beside a turn's report, new
 * files in the Files tab, the tab card's summary and About's accents. Three steps each, as polished metal reads: a pale
 * highlight, the body, a darker shade; large type and the report's bar run from one to the next, small text takes the
 * body alone. Light and low in saturation where copper is saturated and mid-toned, which made the gold before these
 * (#D9B44A, #F0BA4C) read as brass. {@link Theme#gold()} is the one chosen.
 */
public enum Gold {

    TREASURE("Treasure gold", 0xFFEDAA, 0xE7C55A, 0x9E8230),
    YELLOW("18k yellow gold", 0xF7E9BE, 0xDDC27C, 0xA08442),
    CHAMPAGNE("Champagne gold", 0xF1E6CA, 0xD2BE90, 0x8F7C55);

    public final String label;
    public final Color highlight;
    public final Color body;
    public final Color shade;

    Gold(String label, int highlight, int body, int shade) {
        this.label = label;
        this.highlight = new Color(highlight);
        this.body = new Color(body);
        this.shade = new Color(shade);
    }

    /** The gold named {@code name}, as {@link #name()} gives it; {@link #TREASURE} for a name no gold has. */
    public static Gold named(String name) {
        return Arrays.stream(values()).filter(g -> g.name().equals(name)).findFirst().orElse(TREASURE);
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

    /** {@code c} laid over {@code ground} at {@code amount} (0 to 1): a gold-tinted band or rule on a dark ground. */
    public static Color over(Color ground, Color c, float amount) {
        return mix(ground, c, amount);
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
