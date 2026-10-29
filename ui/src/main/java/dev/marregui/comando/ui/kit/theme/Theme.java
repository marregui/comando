package dev.marregui.comando.ui.kit.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import dev.marregui.comando.ui.kit.desktop.Platform;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Design tokens and the look and feel (record 100). A theme is one set of tokens in four tiers: the palette (the ground
 * chosen, surfaces, ink, the tint chosen, status, Claude's orange), the roles named here ({@link #LINK} a link and
 * nothing else; the tint for structure, the selection, focus and the tab in front; {@link #ATTENTION} what needs the
 * user; {@link #DANGER} what is broken), the component map (each widget naming roles), and the content palettes derived
 * from them (syntax, terminal, diff, plots). Widgets take colours, fonts and spacing from here, never from literals:
 * {@code ColourRulesTest} fails any class outside this package that makes or names a {@code Color}.
 */
public final class Theme {

    // colours
    /** A link, and nothing else: the tint marks structure, the selection and focus (record 100). */
    public static final Color LINK = new Color(0x389FD6);
    /** Information inside content: a diff's hunk header, a shell command in a tool card. */
    public static final Color INFO = new Color(0x389FD6);
    public static final Color CLAUDE = new Color(0xD97757);
    public static final Color OK = new Color(0x59A869);
    public static final Color DANGER = new Color(0xDB5860);
    public static final Color MUTED = new Color(0x8C8C8C);
    /** Secondary text on list and card backgrounds, where MUTED falls short of 4.5:1 contrast. */
    public static final Color MUTED_STRONG = new Color(0xA8A8A8);
    public static final Color THINKING = new Color(0xB99BF8);
    /** A file's name as git sees it, against {@code HEAD}: changed, new, gone, untracked, in conflict. */
    public static final Color VCS_MODIFIED = new Color(0x6897BB);
    public static final Color VCS_ADDED = new Color(0x629755);
    public static final Color VCS_DELETED = new Color(0x8C8C8C);
    public static final Color VCS_CONFLICTED = new Color(0xE0525C);
    /**
     * The surfaces: a card, its border, the prompt's bubble, laid over the ground chosen and following it (record 103);
     * on Graphite #2D2D2D, #3C3C3C and #2E3A4A.
     */
    public static final Color CARD = new OnGround(Color.WHITE, 0.115f);
    public static final Color CARD_BORDER = new OnGround(Color.WHITE, 0.177f);
    public static final Color USER_BUBBLE = new OnGround(new Color(0x4A6282), 0.5f);
    /**
     * Every text of the transcript outside a bubble or a card: a warm ivory, low in saturation for long reading, so
     * that the tint of its headings and marks stands apart from it.
     */
    public static final Color TRANSCRIPT_TEXT = new Color(0xE2DCCD);
    /** Bold text in Claude's answers and rendered pages: brighter than {@link #TRANSCRIPT_TEXT}. */
    public static final Color ANSWER_STRONG = new Color(0xFAF7F0);
    /** Inline code elsewhere in the transcript (a bubble, a card), in the text's colour. */
    public static final Color INLINE_CODE_GROUND = new OnGround(Color.WHITE, 0.165f);
    /** Selected text in the transcript. */
    public static final Color SELECTION = new Color(0x214283);
    /**
     * A find match and the current one: violet, apart from the tint (structure), the selection's blue, the diff's green
     * and red, and the attention's gold; a match is data, not structure (record 96). Ivory reads at 7.8:1 and 4.9:1.
     */
    public static final Color FIND_MATCH = new Color(0x4A3560);
    public static final Color FIND_CURRENT = new Color(0x6F4C91);
    /** The rules of a table in the transcript. */
    public static final Color TABLE_RULE = new OnGround(Color.WHITE, 0.253f);
    /** Text where the look and feel names no label colour. */
    public static final Color TEXT_FALLBACK = new Color(0xDFE1E5);
    /**
     * A state that asks to be looked at (an approval waiting, a session opening, an edit not saved yet, the flash on
     * what a click brought into view, the SVGs' amber): Treasure gold's body in every tint, a status colour as
     * {@link #DANGER} is (record 96).
     */
    public static final Color ATTENTION = Tint.TREASURE.body;
    /** A window's ground that lets the desktop through: an undecorated card with the system's shadow. */
    public static final Color CLEAR = new Color(0, 0, 0, 0);
    /** A shadow's ink: an icon's shape filled with it casts the shadow under it. */
    public static final Color SHADOW = new Color(0, 0, 0);
    /** About's card: warm charcoal from top to bottom, its ink, its soft and muted text (record 60). */
    public static final Color ABOUT_TOP = new Color(0x2C2926);
    public static final Color ABOUT_BOTTOM = new Color(0x1B1917);
    public static final Color ABOUT_INK = new Color(0xF2EDE4);
    public static final Color ABOUT_SOFT = new Color(0xBFB6A8);
    public static final Color ABOUT_MUTED = new Color(0x8E867A);
    /** Laid over a window that is closing: it greys what is under it. */
    public static final Color VEIL = new Color(0, 0, 0, 110);

    // spacing: a 4-point grid (record 102); 1 to 3 pixels nudge a line or an icon into place and are not spacing
    /** Between an icon and its text, between lines of a group. */
    public static final int SPACE_XS = 4;
    /** Between the parts of a row or a form: buttons, fields, a header's controls. */
    public static final int SPACE_S = 8;
    /** Inside a card, a dialog, a bar: from its edge to its content. */
    public static final int SPACE_M = 12;
    /** Between groups; the margins of a page. */
    public static final int SPACE_L = 16;
    public static final int GAP = SPACE_S;
    public static final int PAD = SPACE_M;
    public static final int RADIUS = 8;

    /**
     * A row's height for text {@code textHeight} pixels high: the text plus {@link #SPACE_S}, rounded up to the grid.
     */
    public static int rowHeight(int textHeight) {
        int h = textHeight + SPACE_S;
        return (h + SPACE_XS - 1) / SPACE_XS * SPACE_XS;
    }

    /** The text size the type scale is built on: the look and feel's, 13 pt. */
    public static final float BASE_SIZE = 13f;

    /**
     * The type scale (record 102): every size of text in the window is one of these six steps, in points at the base
     * size. A text that zooms takes the same steps from its own base ({@link #size(float, TextSize)}).
     */
    public enum TextSize {
        /** A group's header in small capitals; a code block's notes. */
        CAPTION(11),
        /** Hints, chips, a fact's label, line numbers, code in the transcript. */
        SMALL(12),
        BODY(13),
        /** A card's second heading; a page's second-level heading. */
        SUBTITLE(15),
        /** A card's title; a page's first-level heading. */
        TITLE(18),
        DISPLAY(22);

        public final float pt;

        TextSize(float pt) {
            this.pt = pt;
        }
    }

    /**
     * Step {@code s} of the scale for a text whose body is {@code base} points: {@code base} itself at {@code BODY}. To
     * the half point, so that a base other than 13, as Linux's look and feel gives, lands on a size its fonts draw
     * (12 pt's small step is 11, not 11.08).
     */
    public static float size(float base, TextSize s) {
        return Math.round(base * s.pt / BASE_SIZE * 2) / 2f;
    }

    /** {@code f}, taken as its context's body text, at step {@code s} of the scale. */
    public static Font font(Font f, TextSize s) {
        return f.deriveFont(size(f.getSize2D(), s));
    }

    /**
     * The first three heading levels' sizes, as multiples of the text's, from the type scale: title, subtitle, body,
     * in the transcript and in rendered Markdown pages (records 96, 102). Levels 4 to 6 are the text's size, bold; the
     * third is told from them by the headings' serif.
     */
    public static final float[] HEADING_SCALE = {TextSize.TITLE.pt / BASE_SIZE, TextSize.SUBTITLE.pt / BASE_SIZE, 1f};

    private static Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    /**
     * Where macOS keeps New York, Apple's serif: it lists the face under no family name, as it does SF, so it is loaded
     * from its file.
     */
    static final Path NEW_YORK = Path.of("/System/Library/Fonts/NewYork.ttf");
    /** The headings' face where New York is not: serifs of Windows, macOS and Linux distributions, in that order. */
    static final String[] SERIFS = {"Georgia", "Cambria", "Noto Serif", "DejaVu Serif", "Liberation Serif"};
    private static volatile Tint tint = Tint.TREASURE;
    private static volatile Ground ground = Ground.GRAPHITE;
    /** What shows the tint and must lay itself out or recolour when another is chosen. */
    private static final List<Runnable> TINT_LISTENERS = new CopyOnWriteArrayList<>();

    private Theme() {
    }

    /** Installs the look and feel; call once on the EDT before any component exists. */
    public static void install() {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        UIManager.put("Component.arc", RADIUS);
        UIManager.put("Button.arc", RADIUS);
        UIManager.put("TextComponent.arc", RADIUS);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabsOverlapBorder", true);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
        UIManager.put("SplitPane.dividerSize", 5);
        setUpLook();
        FlatLaf.updateUI();
        mono = pickMono();
    }

    /**
     * The look and feel on the ground chosen, set here once for every panel (records 98, 99): its background, and the
     * background of the content components that draw their own; then the tooltips, as a report reads.
     */
    private static void setUpLook() {
        String g = hex(ground.color);
        FlatLaf.setGlobalExtraDefaults(Map.of("@background", g, "List.background", g, "Tree.background", g,
                "Table.background", g, "TextArea.background", g, "EditorPane.background", g,
                "TextPane.background", g, "Viewport.background", g));
        boolean ok = Platform.isMac() ? FlatMacDarkLaf.setup() : FlatDarkLaf.setup();
        if (!ok) {
            FlatDarkLaf.setup();
        }
        // tooltips read as a report: the panels' ground, a bar of the tint down the left edge, the transcript's ivory
        UIManager.put("ToolTip.background", new ColorUIResource(ground.color));
        UIManager.put("ToolTip.foreground", new ColorUIResource(TRANSCRIPT_TEXT));
        UIManager.put("ToolTip.border", new TintBarBorder());
    }

    /**
     * The ground of every panel of the window: the sidebar, the editors, the lower panel's tabs, the side pane, the
     * terminal, the transcript, tables, trees and lists (records 98, 99); {@link Ground#GRAPHITE} until one is chosen.
     */
    public static Color ground() {
        return ground.color;
    }

    /** The ground chosen. */
    public static Ground groundChosen() {
        return ground;
    }

    /**
     * Chooses the ground: the look and feel is set up again on it and every window takes it, then everything that
     * listens to the tint ({@link #onTint}) lays itself out or recolours, as its colours mix the tint with the ground.
     * Without the look and feel installed, as in a test, only the choice is kept. EDT only.
     */
    public static void ground(Ground chosen) {
        if (ground == chosen) {
            return;
        }
        ground = chosen;
        if (UIManager.getLookAndFeel() instanceof FlatLaf) {
            setUpLook();
            FlatLaf.updateUI();
        }
        for (Runnable r : TINT_LISTENERS) {
            r.run();
        }
    }

    /** The monospaced font: JetBrains Mono when installed, else SF Mono, Menlo, Consolas, DejaVu Sans Mono, Java's. */
    public static Font mono(float size) {
        return mono.deriveFont(size);
    }

    public static Font mono() {
        return mono;
    }

    /**
     * The face of the transcript's headings, bold at 13 pt: New York, else the first of {@link #SERIFS} installed, else
     * Java's logical serif, which every installation has. A glyph the face lacks is drawn in {@link #fallback}.
     */
    public static Font serif() {
        return SerifHolder.SERIF;
    }

    /** Picked on first use, so that tests and the headless tools need no {@link #install()}. */
    private static final class SerifHolder {
        static final Font SERIF = pickSerif(NEW_YORK, installedFamilies());
    }

    static Font pickSerif(Path newYork, Set<String> installed) {
        if (Files.isRegularFile(newYork)) {
            try {
                Font created = Font.createFont(Font.TRUETYPE_FONT, newYork.toFile());
                // registered, so that rendered Markdown's style sheet can name it
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(created);
                return created.deriveFont(Font.BOLD, 13f);
            } catch (FontFormatException | IOException e) {
                // not a font this Java reads: the next face
            }
        }
        for (String family : SERIFS) {
            if (installed.contains(family)) {
                return new Font(family, Font.BOLD, 13);
            }
        }
        return new Font(Font.SERIF, Font.BOLD, 13);
    }

    /**
     * The face for the glyphs {@code f} lacks: Java's logical sans serif in {@code f}'s style and size, which maps to
     * fonts covering most of Unicode on every desktop. A font loaded from a file, or a physical one such as Charter,
     * draws a glyph it lacks as a box or nothing.
     */
    public static Font fallback(Font f) {
        return new Font(Font.DIALOG, f.getStyle(), 1).deriveFont(f.getSize2D());
    }

    private static Set<String> installedFamilies() {
        return Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    }

    private static Font pickMono() {
        Set<String> installed = installedFamilies();
        for (String family : new String[]{"JetBrains Mono", "SF Mono", "Menlo", "Consolas", "DejaVu Sans Mono"}) {
            if (installed.contains(family)) {
                return new Font(family, Font.PLAIN, 13);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 13);
    }

    /**
     * The content palettes of the tint and ground chosen (record 101): syntax, the editor around it, diffs, the
     * terminal's colours, plot series and code in answers. Read it where it paints, or again on {@link #onTint}.
     */
    public static ContentPalette content() {
        return ContentPalette.of(tint, ground);
    }

    /** The tint chosen; {@link Tint#TREASURE} until one is. */
    public static Tint tint() {
        return tint;
    }

    /**
     * Chooses the tint: everything listening ({@link #onTint}) lays itself out or recolours in it at once; what reads
     * {@link #tint()} as it paints, the tooltips' bar among them, takes it at its next paint. EDT only.
     */
    public static void tint(Tint chosen) {
        if (tint == chosen) {
            return;
        }
        tint = chosen;
        for (Runnable r : TINT_LISTENERS) {
            r.run();
        }
    }

    /** Runs {@code listener} on the EDT each time another tint, or another ground, is chosen; returns what stops it. */
    public static Runnable onTint(Runnable listener) {
        TINT_LISTENERS.add(listener);
        return () -> TINT_LISTENERS.remove(listener);
    }

    /** {@code c} with {@code alpha} (0 to 255): a tint laid over text, such as the accent behind a statement run. */
    public static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /**
     * The spinner's bar {@code behindHead} bars behind its head: the tint's body at the head, fading into the ground
     * with each bar behind; something running is "where I am", which the tint marks (records 100, 103).
     */
    public static Color spinnerShade(int behindHead) {
        return Tint.over(ground(), tint.body, 1f - 0.11f * behindHead);
    }

    /** {@code c} as {@code #rrggbb}, for HTML and FlatLaf styles. */
    public static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
