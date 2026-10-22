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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Design tokens and the look and feel. Widgets take colours, fonts and spacing from here, never from
 * literals, so the whole UI changes together.
 */
public final class Theme {

    // colours
    public static final Color ACCENT = new Color(0x389FD6);
    public static final Color CLAUDE = new Color(0xD97757);
    public static final Color OK = new Color(0x59A869);
    public static final Color DANGER = new Color(0xDB5860);
    public static final Color MUTED = new Color(0x8C8C8C);
    /** Secondary text on list and card backgrounds, where MUTED falls short of 4.5:1 contrast. */
    public static final Color MUTED_STRONG = new Color(0xA8A8A8);
    public static final Color THINKING = new Color(0xB99BF8);
    public static final Color DIFF_ADDED = new Color(0x2C4A33);
    public static final Color DIFF_REMOVED = new Color(0x5A2E2E);
    /** The words that changed inside a changed line, over {@link #DIFF_ADDED} and {@link #DIFF_REMOVED}. */
    public static final Color DIFF_ADDED_WORD = new Color(0x3F7350);
    public static final Color DIFF_REMOVED_WORD = new Color(0x8A4242);
    /** The same four on the light editor theme's paper. */
    public static final Color DIFF_ADDED_LIGHT = new Color(0xD6ECD2);
    public static final Color DIFF_REMOVED_LIGHT = new Color(0xF4D8D3);
    public static final Color DIFF_ADDED_WORD_LIGHT = new Color(0xA9D9A0);
    public static final Color DIFF_REMOVED_WORD_LIGHT = new Color(0xEBADA4);
    /** A file's name as git sees it, against {@code HEAD}: changed, new, gone, untracked, in conflict. */
    public static final Color VCS_MODIFIED = new Color(0x6897BB);
    public static final Color VCS_ADDED = new Color(0x629755);
    public static final Color VCS_DELETED = new Color(0x8C8C8C);
    public static final Color VCS_CONFLICTED = new Color(0xE0525C);
    public static final Color CARD = new Color(0x2B2D30);
    public static final Color CARD_BORDER = new Color(0x3C3F41);
    public static final Color USER_BUBBLE = new Color(0x2E3A4A);
    /**
     * The ground of the session's transcript: near-black, so its text and cards stand out more than on the window's
     * grey; not pure black, on which bright text blurs at its edges.
     */
    public static final Color TRANSCRIPT = new Color(0x121212);
    /**
     * Every text of the transcript outside a bubble or a card: a warm ivory, low in saturation for long reading, so
     * that the gold of its headings and marks stands apart from it.
     */
    public static final Color TRANSCRIPT_TEXT = new Color(0xE2DCCD);
    /** Laid over a window that is closing: it greys what is under it. */
    public static final Color VEIL = new Color(0, 0, 0, 110);
    /** Series colours, chosen to stay apart on a dark background. */
    public static final Color[] SERIES = {
            new Color(0x389FD6), new Color(0xD97757), new Color(0x59A869), new Color(0xB99BF8),
            new Color(0xEDA200), new Color(0x4FC1C9), new Color(0xDB5860), new Color(0xC9C9C9)};

    // spacing
    public static final int GAP = 6;
    public static final int PAD = 10;
    public static final int RADIUS = 8;

    private static Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    /**
     * Where macOS keeps New York, Apple's serif: it lists the face under no family name, as it does SF, so it is loaded
     * from its file.
     */
    static final Path NEW_YORK = Path.of("/System/Library/Fonts/NewYork.ttf");
    /** The headings' face where New York is not: serifs of Windows, macOS and the Linux distributions, in that order. */
    static final String[] SERIFS = {"Georgia", "Cambria", "Noto Serif", "DejaVu Serif", "Liberation Serif"};
    private static volatile Gold gold = Gold.TREASURE;
    /** What shows the gold and must lay itself out or recolour when another is chosen. */
    private static final List<Runnable> GOLD_LISTENERS = new CopyOnWriteArrayList<>();

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
        boolean ok = Platform.isMac() ? FlatMacDarkLaf.setup() : FlatDarkLaf.setup();
        if (!ok) {
            FlatDarkLaf.setup();
        }
        // tooltips read as a report: its ground, a bar of the gold down the left edge, the transcript's ivory
        UIManager.put("ToolTip.background", new ColorUIResource(tooltipGround()));
        UIManager.put("ToolTip.foreground", new ColorUIResource(TRANSCRIPT_TEXT));
        UIManager.put("ToolTip.border", new GoldBarBorder());
        FlatLaf.updateUI();
        mono = pickMono();
    }

    /** The monospaced font: JetBrains Mono when installed, then the platform's. */
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
     * A state that asks to be looked at (an approval waiting, a session opening, an edit not saved yet, the flash on what
     * a click brought into view): the gold chosen, as every other gold of the window. It was an amber of its own,
     * {@code #EDA200}, which read as a second, coppery gold beside it.
     */
    public static Color warn() {
        return gold().body;
    }

    /** The gold chosen; {@link Gold#TREASURE} until one is. */
    public static Gold gold() {
        return gold;
    }

    /**
     * Chooses the gold: the tooltips take it, then everything listening ({@link #onGold}) lays itself out or recolours
     * in it at once; what reads {@link #gold()} as it paints takes it at its next paint. EDT only.
     */
    public static void gold(Gold chosen) {
        if (gold == chosen) {
            return;
        }
        gold = chosen;
        UIManager.put("ToolTip.background", new ColorUIResource(tooltipGround()));
        for (Runnable r : GOLD_LISTENERS) {
            r.run();
        }
    }

    /** Runs {@code listener} on the EDT each time another gold is chosen; returns what stops it. */
    public static Runnable onGold(Runnable listener) {
        GOLD_LISTENERS.add(listener);
        return () -> GOLD_LISTENERS.remove(listener);
    }

    /** The ground of a tooltip and a tab card: the transcript's, lifted a little towards the gold, as a report's. */
    public static Color tooltipGround() {
        return Gold.over(TRANSCRIPT, gold.highlight, 0.03f);
    }

    /** {@code c} with {@code alpha} (0 to 255): a tint laid over text, such as the accent behind a statement that ran. */
    public static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** The spinner's bar {@code behindHead} bars behind its head: deep blue at the head, lighter with each bar behind. */
    public static Color spinnerShade(int behindHead) {
        int g = 0xFF - 0x7F / (behindHead + 1);
        return new Color(g >>> 2, g, 0xFF);
    }

    public static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
