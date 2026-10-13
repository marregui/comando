package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.testkit.SyntheticSession;

import javax.swing.JViewport;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The renderer's frame-time gate (plan 4a.4): replays the synthetic session one stream delta per frame,
 * each frame applying the change, laying out and painting a 1200 x 800 viewport at the end, as the widget
 * does while following a live turn. Prints percentiles; exits 1 when p99 exceeds 16 ms.
 */
public final class TranscriptGate {

    private TranscriptGate() {
    }

    public static void main(String[] args) {
        int width = 1200;
        int height = 800;
        int delta = args.length > 0 ? Integer.parseInt(args[0]) : 16;
        List<SyntheticSession.Step> steps = SyntheticSession.steps(delta);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);

        // warm-up pass: class loading and JIT, then the measured pass on a fresh canvas
        run(steps, width, height, img, null);
        long[] frames = new long[steps.size()];
        TranscriptCanvas canvas = run(steps, width, height, img, frames);

        // a width change with the view at the end: what is visible is laid out at once, the rest when idle
        JViewport viewport = new JViewport();
        viewport.setView(canvas);
        viewport.setSize(width, height);
        viewport.setViewSize(new Dimension(width, canvas.contentHeight()));
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - height)));
        long t0 = System.nanoTime();
        viewport.setSize(width - 300, height);
        canvas.ensureLayout(width - 300);
        viewport.setViewSize(new Dimension(width - 300, canvas.contentHeight()));
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - height)));
        paintTop(canvas, width - 300, height, img, Math.max(0, canvas.contentHeight() - height));
        long resize = System.nanoTime() - t0;
        long t2 = System.nanoTime();
        canvas.refineAll();
        long idle = System.nanoTime() - t2;
        long t3 = System.nanoTime();
        canvas.layoutFully(width - 600);
        long full = System.nanoTime() - t3;
        long t1 = System.nanoTime();
        paintTop(canvas, width - 600, height, img, canvas.contentHeight() / 2);
        long scrollPaint = System.nanoTime() - t1;

        long[] sorted = frames.clone();
        Arrays.sort(sorted);
        double p50 = ms(sorted[sorted.length / 2]);
        double p99 = ms(sorted[(int) (sorted.length * 0.99)]);
        double max = ms(sorted[sorted.length - 1]);
        long over = Arrays.stream(frames).filter(f -> f > 16_000_000L).count();
        System.out.printf(Locale.ROOT, "frames %d (delta %d chars), content %d px%n", frames.length, delta, canvas.contentHeight());
        System.out.printf(Locale.ROOT, "frame p50 %.2f ms, p99 %.2f ms, max %.2f ms, over 16 ms: %d%n", p50, p99, max, over);
        System.out.printf(Locale.ROOT, "width change: visible part laid out and painted %.2f ms, idle pass %.2f ms in 8 ms slices%n",
                ms(resize), ms(idle));
        System.out.printf(Locale.ROOT, "full exact relayout %.2f ms, paint after a scroll %.2f ms%n", ms(full), ms(scrollPaint));
        if (p99 > 16.0) {
            System.out.println("GATE FAILED: p99 above 16 ms");
            System.exit(1);
        }
        System.out.println("GATE PASSED");
    }

    private static TranscriptCanvas run(List<SyntheticSession.Step> steps, int width, int height, BufferedImage img, long[] frames) {
        TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
        canvas.setSize(width, height);
        int n = 0;
        for (int i = 0; i < steps.size(); i++) {
            long t = System.nanoTime();
            SyntheticSession.Step s = steps.get(i);
            if (s.index() < 0) {
                canvas.append(n++, s.block());
            } else {
                canvas.update(s.index(), s.block());
            }
            canvas.ensureLayout(width);
            paintTop(canvas, width, height, img, Math.max(0, canvas.contentHeight() - height));
            if (frames != null) {
                frames[i] = System.nanoTime() - t;
            }
        }
        return canvas;
    }

    private static void paintTop(TranscriptCanvas canvas, int width, int height, BufferedImage img, int top) {
        if (!(canvas.getParent() instanceof JViewport)) {
            canvas.setSize(width, canvas.contentHeight());
        }
        Graphics2D g = img.createGraphics();
        g.translate(0, -top);
        g.setClip(0, top, width, height);
        canvas.paint(g);
        g.dispose();
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }
}
