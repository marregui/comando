package dev.marregui.comando.ui.kit.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Set;

/**
 * Design tokens and the look and feel. Widgets take colours, fonts and spacing from here, never from
 * literals, so the whole UI changes together.
 */
public final class Theme {

    // colours
    public static final Color ACCENT = new Color(0x389FD6);
    public static final Color CLAUDE = new Color(0xD97757);
    public static final Color OK = new Color(0x59A869);
    public static final Color WARN = new Color(0xEDA200);
    public static final Color DANGER = new Color(0xDB5860);
    public static final Color MUTED = new Color(0x8C8C8C);
    /** Secondary text on list and card backgrounds, where MUTED falls short of 4.5:1 contrast. */
    public static final Color MUTED_STRONG = new Color(0xA8A8A8);
    public static final Color THINKING = new Color(0xB99BF8);
    /** The text of a turn's conclusion, the answer that ends it. */
    public static final Color GOLD = new Color(0xD9B44A);
    public static final Color DIFF_ADDED = new Color(0x2C4A33);
    public static final Color DIFF_REMOVED = new Color(0x5A2E2E);
    public static final Color CARD = new Color(0x2B2D30);
    public static final Color CARD_BORDER = new Color(0x3C3F41);
    public static final Color USER_BUBBLE = new Color(0x2E3A4A);
    /** Series colours, chosen to stay apart on a dark background. */
    public static final Color[] SERIES = {
            new Color(0x389FD6), new Color(0xD97757), new Color(0x59A869), new Color(0xB99BF8),
            new Color(0xEDA200), new Color(0x4FC1C9), new Color(0xDB5860), new Color(0xC9C9C9)};

    // spacing
    public static final int GAP = 6;
    public static final int PAD = 10;
    public static final int RADIUS = 8;

    private static Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);

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
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        boolean ok = mac ? FlatMacDarkLaf.setup() : FlatDarkLaf.setup();
        if (!ok) {
            FlatDarkLaf.setup();
        }
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

    private static Font pickMono() {
        Set<String> installed = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : new String[]{"JetBrains Mono", "SF Mono", "Menlo", "Consolas", "DejaVu Sans Mono"}) {
            if (installed.contains(family)) {
                return new Font(family, Font.PLAIN, 13);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 13);
    }

    public static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
