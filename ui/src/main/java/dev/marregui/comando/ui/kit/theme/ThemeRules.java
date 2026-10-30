package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The floors a theme must read above to be offered (record 126): the ones the built-in themes are tested on, checked
 * when a theme file is read, so that a file of the user's cannot make text unreadable. Contrast is WCAG's ratio; the
 * syntax and terminal colours are those {@link ContentPalette} derives from the theme.
 */
public final class ThemeRules {

    private ThemeRules() {
    }

    /** What in {@code tint} on {@code ground} reads below its floor, one line each; empty when the theme reads. */
    public static List<String> problems(Ground ground, Tint tint) {
        List<String> out = new ArrayList<>();
        Color g = ground.color;
        floor(out, "the transcript's text on the ground", Theme.TRANSCRIPT_TEXT, g, 12);
        floor(out, "a card on the ground", Tint.over(g, Color.WHITE, 0.115f), g, 1.2);
        floor(out, "the tint's body on the ground", tint.body, g, 7.5);
        floor(out, "the tint's shade on the ground", tint.shade, g, 4);
        floor(out, "the tint's highlight on a table's head", tint.highlight, Tint.over(g, tint.shade, 0.22f), 7);
        ContentPalette p = ContentPalette.of(tint, ground);
        floor(out, "code on the ground", p.ink, g, 12);
        floor(out, "keywords on the ground", p.keyword, g, 7.5);
        floor(out, "comments on the ground", p.comment, g, 4.5);
        Color[] cats = p.categories();
        for (int i = 0; i < cats.length; i++) {
            floor(out, "syntax colour " + (i + 1) + " on the ground", cats[i], g, 6.5);
        }
        for (int i = 1; i <= 6; i++) {
            floor(out, "terminal colour " + i + " on the ground", p.ansi(i), g, 5);
            floor(out, "bright terminal colour " + i + " on the ground", p.ansi(8 + i), g, 8);
        }
        return out;
    }

    /** WCAG's contrast ratio of {@code a} over {@code b}, 1 to 21. */
    public static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static void floor(List<String> out, String what, Color c, Color on, double min) {
        double r = contrast(c, on);
        if (r <= min) {
            out.add(String.format("%s reads at %.1f:1, below %s:1", what, r, min % 1 == 0 ? String.valueOf((int) min) : String.valueOf(min)));
        }
    }

    private static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(int channel) {
        double v = channel / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
