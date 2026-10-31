package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.theme.Theme;

/** Paints the synthetic session at a scroll offset to a PNG: {@code TranscriptShot <file> <offset> [width] [height]} */
public final class TranscriptShot {

    private TranscriptShot() {
    }

    public static void main(final String[] args) throws Exception {
        final Path file = Path.of(args[0]);
        final int offset = Integer.parseInt(args[1]);
        final int width = args.length > 2 ? Integer.parseInt(args[2]) : 1100;
        final int height = args.length > 3 ? Integer.parseInt(args[3]) : 900;
        SwingUtilities.invokeAndWait(Theme::install);
        final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
        int n = 0;
        for (final SyntheticSession.Step s : SyntheticSession.steps(10_000)) {
            if (s.index() < 0) {
                canvas.append(n++, s.block());
            } else {
                canvas.update(s.index(), s.block());
            }
        }
        canvas.layoutFully(width);
        canvas.setSize(width, canvas.contentHeight());
        canvas.find(args.length > 4 ? args[4] : "cancelSignalTimeout", true);
        canvas.clearFind();
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        g.setColor(UIManager.getColor("Panel.background"));
        g.fillRect(0, 0, width, height);
        g.translate(0, -offset);
        g.setClip(0, offset, width, height);
        canvas.paint(g);
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
        System.out.println("content " + canvas.contentHeight() + " px, wrote " + file + " " + List.of(width, height, offset));
    }
}
