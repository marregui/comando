package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The theme's fourth tier (records 100, 101): the colours of content, derived from the tint and the ground instead of
 * chosen, so that the editors, the terminal, diffs, plots and code in answers change with them. {@link Theme#content()}
 * is the one in use.
 *
 * <p>Syntax: keywords and markup tags take the tint's body; identifiers, functions and operators the ink; comments the
 * muted grey, documentation in italic; six categories (annotation, number, string, regex, type, variable) one hue each
 * at one lightness and chroma, so that none outweighs another. Their hues sit 60° apart on a wheel turned so that the
 * tint's hue falls midway between two of them: every category stays within 30° of its customary hue (annotations rose,
 * numbers orange, strings green, regular expressions teal, types blue, variables violet) and at least 30° from the
 * tint. The golds lie near a midpoint of the customary wheel, so it hardly turns for them and they share one palette in
 * all but the keywords. A tint with too little chroma to have a hue, silver, leaves the wheel where custom puts it.
 *
 * <p>The terminal's sixteen colours keep their names' hues, since programs mean red by red; diffs lay green and red
 * over the ground; plot series start at the tint and go round the wheel.
 *
 * <p>On a light ground (record 127) the same hues are drawn dark enough to read on it: the ink is the transcript's
 * dark text, the categories, the terminal's colours, plot series and inline code take a low lightness, and the
 * terminal's black and white swap ends, black the near-ink and white the near-ground.
 */
public final class ContentPalette {

    /** The lightness and chroma of the syntax categories; on a light ground, the ones that read and stay apart there. */
    private static final double SYNTAX_L = 0.76;
    private static final double SYNTAX_L_LIGHT = 0.4;
    private static final double SYNTAX_C_LIGHT = 0.14;
    private static final double SYNTAX_C = 0.11;
    private static final double SYNTAX_STEP_LIGHT = 0.03;
    /** Below this chroma a tint's hue is noise. */
    private static final double HUELESS = 0.04;
    /** The categories' customary hues: annotation, number, string, regex, type, variable. */
    private static final double[] CUSTOM = {0, 60, 120, 180, 240, 300};
    /** One palette per tint and ground, which compare by identity. */
    private static final Map<Pair, ContentPalette> CACHE = new ConcurrentHashMap<>();

    private record Pair(Tint tint, Ground ground) {
    }

    // syntax
    public final Color ink;
    public final Color keyword;
    public final Color number;
    public final Color annotation;
    public final Color string;
    public final Color regex;
    public final Color type;
    public final Color variable;
    public final Color comment;
    /** Tags inside documentation comments ({@code @param}). */
    public final Color commentTag;
    public final Color error;

    // the editor around the text
    public final Color caret;
    public final Color selection;
    public final Color currentLine;
    public final Color guide;
    public final Color lineNumber;
    public final Color currentLineNumber;
    public final Color bracket;
    public final Color occurrence;
    public final Color foldArmed;
    /** Backgrounds of embedded languages, as HTML's scripts and styles: faint blue, green and red. */
    public final Color[] embedded;

    // diffs: line backgrounds, and the words that changed inside a changed line
    public final Color added;
    public final Color removed;
    public final Color addedWord;
    public final Color removedWord;

    /** The terminal's sixteen colours: black, red, green, yellow, blue, magenta, cyan, white, then the bright ones. */
    private final Color[] ansi;
    /** Plot series, the tint's body first. */
    private final Color[] series;
    /** Inline code in answers and rendered pages, and its ground. */
    public final Color code;
    public final Color codeInlineGround;
    /** A code block's ground: a step above the panels'. */
    public final Color codeGround;

    private ContentPalette(Tint tint, Ground ground) {
        Color g = ground.color;
        boolean light = ground.light;
        double syntaxL = light ? SYNTAX_L_LIGHT : SYNTAX_L;
        double syntaxC = light ? SYNTAX_C_LIGHT : SYNTAX_C;
        double[] t = Oklch.lch(tint.body);
        double turn = t[1] < HUELESS ? 0 : turn(t[2]);
        double[] hue = new double[CUSTOM.length];
        for (int i = 0; i < CUSTOM.length; i++) {
            hue[i] = (CUSTOM[i] + turn + 360) % 360;
        }

        ink = Theme.fixed(Theme.TRANSCRIPT_TEXT, light);
        // keywords a step apart from the categories in lightness, as on a dark ground: the deepest step on a light one
        keyword = light ? tint.highlight : tint.body;
        // on a light ground the dark end of the gamut holds less chroma, so neighbouring hues alternate a step in
        // lightness to stay apart
        double step = light ? SYNTAX_STEP_LIGHT : 0;
        annotation = Oklch.of(syntaxL - step, syntaxC, hue[0]);
        number = Oklch.of(syntaxL + step, syntaxC, hue[1]);
        string = Oklch.of(syntaxL - step, syntaxC, hue[2]);
        regex = Oklch.of(syntaxL + step, syntaxC, hue[3]);
        type = Oklch.of(syntaxL - step, syntaxC, hue[4]);
        variable = Oklch.of(syntaxL + step, syntaxC, hue[5]);
        Color muted = Theme.fixed(Theme.MUTED, light);
        comment = muted;
        commentTag = Tint.over(muted, tint.body, 0.45f);
        error = Theme.fixed(Theme.DANGER, light);

        caret = ink;
        selection = Tint.over(g, tint.shade, 0.45f);
        currentLine = Tint.over(g, ink, 0.05f);
        guide = Tint.over(g, ink, 0.12f);
        lineNumber = Tint.over(g, ink, light ? 0.40f : 0.32f);
        currentLineNumber = Tint.over(g, ink, 0.72f);
        bracket = Tint.over(g, tint.shade, 0.32f);
        occurrence = Tint.over(g, ink, 0.12f);
        foldArmed = Tint.over(g, ink, 0.10f);
        embedded = new Color[]{
                Tint.over(g, Oklch.of(0.6, 0.12, 265), 0.12f),
                Tint.over(g, Oklch.of(0.6, 0.12, 150), 0.12f),
                Tint.over(g, Oklch.of(0.6, 0.12, 25), 0.12f)};

        Color green = Oklch.of(0.62, 0.13, 150);
        Color red = Oklch.of(0.62, 0.13, 25);
        added = Tint.over(g, green, 0.30f);
        removed = Tint.over(g, red, 0.30f);
        addedWord = Tint.over(g, green, 0.48f);
        removedWord = Tint.over(g, red, 0.48f);

        double[] names = {25, 145, 95, 255, 330, 200};
        ansi = new Color[16];
        // black and white at the ends of the ground-to-ink line: black near the ground on a dark one, near the ink on a
        // light one, as a terminal's black is the colour of its ground
        ansi[0] = Tint.over(g, ink, light ? 0.92f : 0.25f);
        ansi[8] = Tint.over(g, ink, light ? 0.56f : 0.50f);
        ansi[7] = Tint.over(g, ink, light ? 0.25f : 0.80f);
        ansi[15] = light ? Tint.over(g, ink, 0.12f) : Theme.fixed(Theme.ANSWER_STRONG, false);
        for (int i = 0; i < names.length; i++) {
            ansi[1 + i] = Oklch.of(light ? 0.47 : 0.70, 0.14, names[i]);
            ansi[9 + i] = Oklch.of(light ? 0.38 : 0.82, 0.11, names[i]);
        }

        series = new Color[8];
        series[0] = tint.body;
        for (int i = 1; i < series.length; i++) {
            series[i] = Oklch.of(light ? 0.50 : 0.72, 0.13, (t[2] + 45.0 * i) % 360);
        }

        code = Oklch.of(light ? 0.38 : 0.82, 0.06, hue[3]);
        codeInlineGround = Tint.over(g, ink, 0.08f);
        codeGround = Tint.over(g, ink, 0.05f);
    }

    /** The palette of {@code tint} on {@code ground}, made once per pair. */
    public static ContentPalette of(Tint tint, Ground ground) {
        return CACHE.computeIfAbsent(new Pair(tint, ground), k -> new ContentPalette(tint, ground));
    }

    /**
     * How far the wheel turns, −30° to 30°, so that {@code tintHue} lies midway between two of its hues: the
     * nearest midpoint of the customary wheel (30°, 90°, … 330°) is moved onto the tint.
     */
    static double turn(double tintHue) {
        double off = (((tintHue - 30) % 60) + 60) % 60;
        return off <= 30 ? off : off - 60;
    }

    /** The terminal's colour {@code i}, 0 to 15. */
    public Color ansi(int i) {
        return ansi[i];
    }

    /** Plot series {@code i}, any index: the eight repeat. */
    public Color series(int i) {
        return series[Math.floorMod(i, series.length)];
    }

    /** How many series differ before they repeat. */
    public int seriesCount() {
        return series.length;
    }

    /** The six syntax categories' colours, in the order of their customary hues. */
    Color[] categories() {
        return new Color[]{annotation, number, string, regex, type, variable};
    }
}
