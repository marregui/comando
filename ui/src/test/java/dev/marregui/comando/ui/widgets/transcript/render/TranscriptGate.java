package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.SyntheticSession;

/**
 * The renderer's frame-time gate (plan 4a.4): replays the synthetic session one stream delta per frame, each frame
 * applying the change, laying out and painting a 1200 x 800 viewport at the end, as the widget does while following a
 * live turn. Then streams a long thinking block, which stays open while it streams. Prints percentiles; exits 1 when
 * either p99 exceeds 16 ms, 2 when the JVM is still alive {@value #EXIT_GRACE_MILLIS} ms after the measurement: AWT
 * ends the JVM by itself once nothing posts to it, so a Swing timer or a displayable component left behind would
 * otherwise hang the gate for as long as its caller waits.
 */
public final class TranscriptGate {

    private static final long EXIT_GRACE_MILLIS = 10_000;

    private TranscriptGate() {
    }

    public static void main(final String[] args) throws Exception {
        final int delta = args.length > 0 ? Integer.parseInt(args[0]) : 16;
        // on the EDT, as the widget runs: the canvas's idle pass is a Swing timer, which would otherwise race the gate
        SwingUtilities.invokeAndWait(() -> measure(delta));
        exitWatch();
    }

    /** A daemon thread, so it never holds the JVM: it only speaks when something else does. */
    private static void exitWatch() {
        final Thread watch = new Thread(() -> {
            try {
                Thread.sleep(EXIT_GRACE_MILLIS);
            } catch (final InterruptedException e) {
                return;
            }
            System.out.println("GATE FAILED: the JVM is still alive " + EXIT_GRACE_MILLIS + " ms after the measurement; "
                    + "something keeps AWT open (a Swing timer, a displayable component). Non-daemon threads:");
            Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> !t.isDaemon() && t != Thread.currentThread())
                    .sorted(Comparator.comparing(Thread::getName))
                    .forEach(t -> System.out.println("  " + t.getName() + " " + t.getState()));
            Runtime.getRuntime().halt(2);
        }, "gate-exit-watch");
        watch.setDaemon(true);
        watch.start();
    }

    private static void measure(final int delta) {
        final int width = 1200;
        final int height = 800;
        final List<SyntheticSession.Step> steps = SyntheticSession.steps(delta);
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);

        // warm-up pass: class loading and JIT, then the measured pass on a fresh canvas
        run(steps, width, height, img, null);
        final long[] frames = new long[steps.size()];
        final TranscriptCanvas canvas = run(steps, width, height, img, frames);

        // a width change with the view at the end: what is visible is laid out at once, the rest when idle
        final JViewport viewport = new JViewport();
        viewport.setView(canvas);
        viewport.setSize(width, height);
        viewport.setViewSize(new Dimension(width, canvas.contentHeight()));
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - height)));
        final long t0 = System.nanoTime();
        viewport.setSize(width - 300, height);
        canvas.ensureLayout(width - 300);
        viewport.setViewSize(new Dimension(width - 300, canvas.contentHeight()));
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - height)));
        paintTop(canvas, width - 300, height, img, Math.max(0, canvas.contentHeight() - height));
        final long resize = System.nanoTime() - t0;
        final long t2 = System.nanoTime();
        canvas.refineAll();
        final long idle = System.nanoTime() - t2;
        final long t3 = System.nanoTime();
        canvas.layoutFully(width - 600);
        final long full = System.nanoTime() - t3;
        final long t1 = System.nanoTime();
        paintTop(canvas, width - 600, height, img, canvas.contentHeight() / 2);
        final long scrollPaint = System.nanoTime() - t1;

        thinking(delta, width, height, img);
        final long[] thinkingFrames = thinking(delta, width, height, img);
        codeFence(delta, width, height, img);
        final long[] codeFrames = codeFence(delta, width, height, img);
        findInLongTranscript(width, height, img);
        final long[] find = findInLongTranscript(width, height, img);

        final long[] sorted = frames.clone();
        Arrays.sort(sorted);
        final double p50 = ms(sorted[sorted.length / 2]);
        final double p99 = ms(sorted[(int) (sorted.length * 0.99)]);
        final double max = ms(sorted[sorted.length - 1]);
        final long over = Arrays.stream(frames).filter(f -> f > 16_000_000L).count();
        System.out.printf(Locale.ROOT, "frames %d (delta %d chars), content %d px%n", frames.length, delta, canvas.contentHeight());
        System.out.printf(Locale.ROOT, "frame p50 %.2f ms, p99 %.2f ms, max %.2f ms, over 16 ms: %d%n", p50, p99, max, over);
        System.out.printf(Locale.ROOT, "width change: visible part laid out and painted %.2f ms, idle pass %.2f ms in 8 ms slices%n",
                ms(resize), ms(idle));
        System.out.printf(Locale.ROOT, "full exact relayout %.2f ms, paint after a scroll %.2f ms%n", ms(full), ms(scrollPaint));
        final long[] thinkingSorted = thinkingFrames.clone();
        Arrays.sort(thinkingSorted);
        final double thinkingP99 = ms(thinkingSorted[(int) (thinkingSorted.length * 0.99)]);
        System.out.printf(Locale.ROOT, "open thinking streamed (%d frames): p50 %.2f ms, p99 %.2f ms, max %.2f ms%n", thinkingSorted.length,
                ms(thinkingSorted[thinkingSorted.length / 2]), thinkingP99, ms(thinkingSorted[thinkingSorted.length - 1]));
        final long[] codeSorted = codeFrames.clone();
        Arrays.sort(codeSorted);
        System.out.printf(Locale.ROOT, "1 500-line code fence, its last 100 lines streamed (%d frames): p50 %.2f ms, p99 %.2f ms, max %.2f ms%n",
                codeSorted.length, ms(codeSorted[codeSorted.length / 2]), ms(codeSorted[(int) (codeSorted.length * 0.99)]),
                ms(codeSorted[codeSorted.length - 1]));
        System.out.printf(Locale.ROOT, "find in %d loaded blocks, a word in most: first search %.2f ms, the next match %.2f ms; "
                + "a word in a few, from the end backwards: %.2f ms%n", find[2], ms(find[0]), ms(find[1]), ms(find[3]));
        if (p99 > 16.0 || thinkingP99 > 16.0) {
            System.out.println("GATE FAILED: p99 above 16 ms");
            System.exit(1);
        }
        System.out.println("GATE PASSED");
    }

    private static TranscriptCanvas run(final List<SyntheticSession.Step> steps, final int width, final int height, final BufferedImage img, final long[] frames) {
        final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
        canvas.setSize(width, height);
        int n = 0;
        for (int i = 0; i < steps.size(); i++) {
            final long t = System.nanoTime();
            final SyntheticSession.Step s = steps.get(i);
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

    /** Streams about 9 000 characters of thinking, 40 words a line, one delta per frame, painting the end. */
    private static long[] thinking(final int delta, final int width, final int height, final BufferedImage img) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            text.append("word").append(i % 7).append(i % 40 == 39 ? '\n' : ' ');
        }
        final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
        canvas.setSize(width, height);
        canvas.append(0, new Block.Thinking("t", 0, text.substring(0, delta), 1, true, ""));
        final long[] frames = new long[text.length() / delta];
        for (int i = 0; i < frames.length; i++) {
            final long t = System.nanoTime();
            canvas.update(0, new Block.Thinking("t", 0, text.substring(0, (i + 1) * delta), i, true, ""));
            canvas.ensureLayout(width);
            paintTop(canvas, width, height, img, Math.max(0, canvas.contentHeight() - height));
            frames[i] = System.nanoTime() - t;
        }
        return frames;
    }

    /**
     * An answer holding one 1 500-line Java fence: the first 1 400 lines are there, the last 100 stream one delta per
     * frame, painting the end, as a long code block arrives.
     */
    private static long[] codeFence(final int delta, final int width, final int height, final BufferedImage img) {
        final StringBuilder code = new StringBuilder("Here is the class:\n\n```java\n");
        for (int i = 0; i < 1500; i++) {
            code.append("    private static final int FIELD_").append(i).append(" = compute(").append(i).append(", \"x\"); // note\n");
        }
        final String head = code.substring(0, nthLine(code, 1403));
        final String full = code.append("```\n").toString();
        final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
        canvas.setSize(width, height);
        canvas.append(0, new Block.AssistantText("m", 0, head, true, ""));
        canvas.ensureLayout(width);
        final long[] frames = new long[(full.length() - head.length()) / delta];
        for (int i = 0; i < frames.length; i++) {
            final long t = System.nanoTime();
            canvas.update(0, new Block.AssistantText("m", 0, full.substring(0, head.length() + (i + 1) * delta), true, ""));
            canvas.ensureLayout(width);
            paintTop(canvas, width, height, img, Math.max(0, canvas.contentHeight() - height));
            frames[i] = System.nanoTime() - t;
        }
        return frames;
    }

    private static int nthLine(final CharSequence s, final int n) {
        int at = 0;
        for (int i = 0; i < n; i++) {
            at = s.toString().indexOf('\n', at) + 1;
        }
        return at;
    }

    /**
     * The synthetic session's final blocks repeated to 2 000, as a stored conversation loads, the end laid out and
     * painted; then one find of a word every tool result holds, and the next match. Returns both times and the count.
     */
    private static long[] findInLongTranscript(final int width, final int height, final BufferedImage img) {
        final Transcript session = SyntheticSession.transcript();
        final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
        canvas.setSize(width, height);
        int n = 0;
        while (n < 2000) {
            for (int i = 0; i < session.size() && n < 2000; i++) {
                canvas.append(n++, session.get(i));
            }
        }
        canvas.ensureLayout(width);
        paintTop(canvas, width, height, img, Math.max(0, canvas.contentHeight() - height));
        final long t0 = System.nanoTime();
        canvas.find("timeout", true);
        final long first = System.nanoTime() - t0;
        final long t1 = System.nanoTime();
        canvas.find("timeout", true);
        final long next = System.nanoTime() - t1;
        canvas.clearFind();
        final TranscriptCanvas fresh = new TranscriptCanvas(href -> { });
        fresh.setSize(width, height);
        for (int i = 0; i < n; i++) {
            fresh.append(i, session.get(i % session.size()));
        }
        fresh.ensureLayout(width);
        paintTop(fresh, width, height, img, Math.max(0, fresh.contentHeight() - height));
        final long t2 = System.nanoTime();
        fresh.find("Reducer.java:42", false);
        final long rare = System.nanoTime() - t2;
        return new long[]{first, next, n, rare};
    }

    private static void paintTop(final TranscriptCanvas canvas, final int width, final int height, final BufferedImage img, final int top) {
        if (!(canvas.getParent() instanceof JViewport)) {
            canvas.setSize(width, canvas.contentHeight());
        }
        final Graphics2D g = img.createGraphics();
        g.translate(0, -top);
        g.setClip(0, top, width, height);
        canvas.paint(g);
        g.dispose();
    }

    private static double ms(final long nanos) {
        return nanos / 1e6;
    }
}
