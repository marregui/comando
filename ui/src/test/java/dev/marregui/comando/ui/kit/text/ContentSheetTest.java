package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.ContentPalette;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [ContentPalette], painted to `build/snapshots/content.png` (record 101): a Java snippet in the editors' colours, the
/// terminal's sixteen colours, an added and a removed line, the plot series; every tint on the default ground, then every
/// ground in the default tint. The sheet to look at when the generator changes.
/// - Every tint and every ground yields a content palette that paints without failing.
class ContentSheetTest {

    private static final String CODE = """
            /** Drops {@code pending} retries; see @link Store. */
            @Override
            public int drop(String key, long limit) {
                // the writer holds the lock
                var re = Pattern.compile("^r[0-9]+$");
                if (key == null || limit > 0x40) {
                    return -1;
                }
                return store.remove(key, 3.5f) ? 1 : 0;
            }""";
    private static final int WIDTH = 470;
    private static final int CODE_HEIGHT = 190;
    private static final int HEIGHT = CODE_HEIGHT + 96;
    private static final int LABEL = 22;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void defaults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.tint(Tint.TREASURE);
            Theme.ground(Ground.GRAPHITE);
        });
    }

    /// Painting one panel per tint on graphite and one per ground in treasure, through [Highlighter#runs] and
    /// [ContentPalette#ansi], completes and writes a non-empty PNG.
    @Test
    void everyTintAndEveryGroundPaintsItsOwnContentPalette() throws Exception {
        Tint[] tints = Tint.values();
        Ground[] grounds = Ground.values();
        int panels = tints.length + grounds.length;
        int cols = 3;
        int rows = (panels + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * WIDTH, rows * (HEIGHT + LABEL), BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D g = sheet.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int i = 0; i < panels; i++) {
                Tint t = i < tints.length ? tints[i] : Tint.TREASURE;
                Ground gr = i < tints.length ? Ground.GRAPHITE : grounds[i - tints.length];
                Theme.tint(t);
                Theme.ground(gr);
                int x = i % cols * WIDTH;
                int y = i / cols * (HEIGHT + LABEL);
                g.setColor(Theme.CARD);
                g.fillRect(x, y, WIDTH, LABEL);
                g.setColor(Theme.TRANSCRIPT_TEXT);
                g.setFont(Theme.mono(12f));
                g.drawString(t.label + " on " + gr.label, x + 10, y + 15);
                Graphics2D one = (Graphics2D) g.create(x, y + LABEL, WIDTH, HEIGHT);
                panel(one);
                one.dispose();
            }
            g.dispose();
        });
        Path out = Path.of("build", "snapshots", "content.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(sheet, "png", out.toFile());
        assertThat(out).isNotEmptyFile();
    }

    /**
     * The snippet as the editors colour it ({@link Highlighter} reads their scheme), with line numbers and a selection:
     * a text area paints only on a display.
     */
    private static void code(Graphics2D g, ContentPalette p) {
        g.setFont(Theme.mono(12f));
        FontMetrics fm = g.getFontMetrics();
        int line = fm.getHeight();
        String[] lines = CODE.split("\n", -1);
        int[] starts = new int[lines.length];
        for (int i = 1; i < lines.length; i++) {
            starts[i] = starts[i - 1] + lines[i - 1].length() + 1;
        }
        int sel = CODE.indexOf("store.remove");
        List<Highlighter.Run> runs = Highlighter.runs(CODE, "java");
        for (int i = 0; i < lines.length; i++) {
            int base = 8 + (i + 1) * line;
            g.setColor(p.lineNumber);
            g.drawString(String.valueOf(i + 1), 8, base);
            int x = 34;
            for (int c = 0; c < lines[i].length(); c++) {
                int at = starts[i] + c;
                String ch = String.valueOf(lines[i].charAt(c));
                int w = fm.stringWidth(ch);
                if (at >= sel && at < sel + 12) {
                    g.setColor(p.selection);
                    g.fillRect(x, base - fm.getAscent(), w, line);
                }
                g.setColor(colourAt(runs, at, p));
                g.drawString(ch, x, base);
                x += w;
            }
        }
    }

    private static Color colourAt(List<Highlighter.Run> runs, int at, ContentPalette p) {
        for (Highlighter.Run r : runs) {
            if (at >= r.start() && at < r.end()) {
                return r.color();
            }
        }
        return p.ink;
    }

    private static void panel(Graphics2D g) {
        ContentPalette p = Theme.content();
        g.setColor(Theme.ground());
        g.fillRect(0, 0, WIDTH, HEIGHT);
        code(g, p);

        int y = CODE_HEIGHT + 6;
        for (int i = 0; i < 16; i++) {
            g.setColor(p.ansi(i));
            g.fillRect(10 + (i % 8) * 28, y + (i / 8) * 14, 24, 11);
        }
        for (int i = 0; i < p.seriesCount(); i++) {
            g.setColor(p.series(i));
            g.fillRect(250 + i * 26, y, 22, 25);
        }
        g.setFont(Theme.mono(12f));
        y += 34;
        g.setColor(p.added);
        g.fillRect(0, y, WIDTH, 18);
        g.setColor(p.addedWord);
        g.fillRect(72, y + 1, 54, 16);
        g.setColor(p.removed);
        g.fillRect(0, y + 18, WIDTH, 18);
        g.setColor(p.removedWord);
        g.fillRect(72, y + 19, 54, 16);
        g.setColor(p.ink);
        g.drawString("+ int limit = 64;", 10, y + 13);
        g.drawString("- int limit = 32;", 10, y + 31);
        g.setColor(p.codeInlineGround);
        g.fillRect(10, y + 40, 120, 18);
        g.setColor(p.code);
        g.drawString("store.drop()", 14, y + 53);
    }
}
