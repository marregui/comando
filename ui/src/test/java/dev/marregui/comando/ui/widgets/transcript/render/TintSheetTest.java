package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// One answer painted by [TranscriptCanvas] in every [Tint], side by side, to `build/snapshots/tints.png`: the sheet to
/// look at when a tint is added or changed.
/// - each tint paints its own highlight and no other tint's
@ExtendWith(OnEdt.class)
class TintSheetTest {

    private static final String ANSWER = """
            # Result

            The store drops **pending retries** when the queue is `full`.

            ## Why

            - the writer holds the lock
            - the reader times out

            > a quote beside its bar

            | Field | Value |
            |---|---|
            | retries | 3 |

            ### Next
            """;
    private static final int WIDTH = 520;
    private static final int HEIGHT = 420;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void defaultTint() {
        Theme.tint(Tint.TREASURE);
    }

    /** The look and feel's text colour, which no look and feel sets here: else the prompt's text paints near black. */
    @BeforeEach
    void labelText() {
        labelWas = UIManager.getColor("Label.foreground");
        UIManager.put("Label.foreground", Theme.TEXT_FALLBACK);
    }

    @AfterEach
    void labelTextBack() {
        UIManager.put("Label.foreground", labelWas);
    }

    private Color labelWas;

    /// For every tint, an answer with headings, a list, a quote and a table contains pixels of that tint's highlight
    /// and none of any other tint's; the sheet is written for review.
    @Test
    void everyTintPaintsTheSameAnswerInItsOwnColoursOnly() throws IOException {
        final Tint[] tints = Tint.values();
        final int cols = 3;
        final int rows = (tints.length + cols - 1) / cols;
        final BufferedImage sheet = new BufferedImage(cols * WIDTH, rows * (HEIGHT + 24), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (int i = 0; i < tints.length; i++) {
            final Tint t = tints[i];
            Theme.tint(t);
            final BufferedImage panel = answer();
            for (final Tint other : tints) {
                final int n = count(panel, other.highlight);
                if (other == t) {
                    assertThat(n).as(t + "'s bar").isPositive();
                } else {
                    assertThat(n).as(other + "'s highlight in " + t).isZero();
                }
            }
            final int x = (i % cols) * WIDTH;
            final int y = (i / cols) * (HEIGHT + 24);
            g.setColor(Theme.CARD);
            g.fillRect(x, y, WIDTH, 24);
            g.setColor(Theme.TRANSCRIPT_TEXT);
            g.setFont(Theme.mono(12f));
            g.drawString(t.label, x + 10, y + 16);
            g.drawImage(panel, x, y + 24, null);
        }
        g.dispose();
        final Path out = Path.of("build", "snapshots", "tints.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(sheet, "png", out.toFile());
    }

    private static BufferedImage answer() {
        final TranscriptCanvas canvas = new TranscriptCanvas(url -> { });
        canvas.append(0, new Block.UserPrompt("why does the store drop retries?", "p", ""));
        canvas.append(1, new Block.AssistantText("m", 0, ANSWER, false, ""));
        canvas.append(2, new Block.TurnEnd(0.1, 1_000, 2, false, ""));
        canvas.setSize(WIDTH, HEIGHT);
        canvas.ensureLayout(WIDTH);
        canvas.setSize(WIDTH, Math.max(HEIGHT, canvas.contentHeight()));
        final BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        g.setColor(Theme.ground());
        g.fillRect(0, 0, WIDTH, HEIGHT);
        canvas.paint(g);
        g.dispose();
        return img;
    }

    private static int count(final BufferedImage img, final Color c) {
        final int rgb = c.getRGB() & 0xFFFFFF;
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    n++;
                }
            }
        }
        return n;
    }
}
