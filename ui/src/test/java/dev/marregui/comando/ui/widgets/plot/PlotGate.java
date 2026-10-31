package dev.marregui.comando.ui.widgets.plot;

import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javax.swing.SwingUtilities;

import dev.marregui.comando.core.view.ViewSpec;

/**
 * The plot's frame-time gate: a {@link PlotCanvas} over 4 series of 200 000 points in a 1600 x 900 viewport, each frame
 * either a crosshair move (200 per pass) or a zoom step (50 per pass: 25 in, then 25 out) followed by a paint, the pass
 * run twice after a warm-up. Prints percentiles and {@code HOT} when p99 is above {@value #HOT_MILLIS} ms, the line above
 * which a paint path is worth optimising. It measures: the exit code is 0 either way, and a p99 above the 16 ms frame
 * budget is printed as {@code OVER BUDGET}. It is 2 when the JVM is still alive {@value #EXIT_GRACE_MILLIS} ms after the
 * measurement (AWT ends the JVM by itself once nothing posts to it, so a displayable component left behind would
 * otherwise hang the gate).
 */
public final class PlotGate {

    /** The transcript gate's exit watch. */
    private static final long EXIT_GRACE_MILLIS = 10_000;
    /** Miguel's line for calling a paint path hot: p99 above 4 ms. */
    private static final double HOT_MILLIS = 4.0;
    /** One 60 Hz frame. */
    private static final double BUDGET_MILLIS = 16.0;
    /** The plot the gate stands for: 4 series of 200 000 points. */
    private static final int SERIES = 4;
    private static final int POINTS = 200_000;
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;
    /** Per pass: 200 crosshair moves and 50 zoom steps; two measured passes give 500 frames, the 500 the gate asks for. */
    private static final int MOVES = 200;
    private static final int ZOOMS = 50;
    private static final int WARM_UP_PASSES = 3;
    private static final int MEASURED_PASSES = 2;

    private PlotGate() {
    }

    public static void main(final String[] args) throws Exception {
        // on the EDT, as the widget runs
        SwingUtilities.invokeAndWait(PlotGate::measure);
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

    private static void measure() {
        final PlotCanvas canvas = new PlotCanvas();
        canvas.setSize(WIDTH, HEIGHT);
        canvas.setData(PlotCanvas.prepare(plot()));
        final BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB_PRE);

        for (int i = 0; i < WARM_UP_PASSES; i++) {
            pass(canvas, img, null, null);
        }
        final long[] moves = new long[MEASURED_PASSES * MOVES];
        final long[] zooms = new long[MEASURED_PASSES * ZOOMS];
        for (int i = 0; i < MEASURED_PASSES; i++) {
            pass(canvas, img, slice(moves, i * MOVES), slice(zooms, i * ZOOMS));
        }
        final long[] all = new long[moves.length + zooms.length];
        System.arraycopy(moves, 0, all, 0, moves.length);
        System.arraycopy(zooms, 0, all, moves.length, zooms.length);
        System.out.printf(Locale.ROOT, "plot %d series x %d points, viewport %d x %d, headless %s%n", SERIES, POINTS, WIDTH, HEIGHT,
                System.getProperty("java.awt.headless"));
        report("crosshair moves", moves);
        report("zoom steps", zooms);
        final double p99 = report("all frames", all);
        System.out.println(p99 > HOT_MILLIS ? "HOT" : "NOT HOT");
        if (p99 > BUDGET_MILLIS) {
            System.out.println("OVER BUDGET: p99 above " + BUDGET_MILLIS + " ms");
        }
    }

    /** Frame times that a pass fills at an offset of the full arrays. */
    private record Slice(long[] frames, int from) {
    }

    private static Slice slice(final long[] frames, final int from) {
        return new Slice(frames, from);
    }

    /** Resets the view, moves the crosshair across the plot, then zooms in and out around the middle; times when asked. */
    private static void pass(final PlotCanvas canvas, final BufferedImage img, final Slice moves, final Slice zooms) {
        canvas.reset();
        for (int i = 0; i < MOVES; i++) {
            final long t = System.nanoTime();
            final int x = 70 + (int) ((long) i * (WIDTH - 90) / MOVES);
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, HEIGHT / 2, 0, false));
            paint(canvas, img);
            if (moves != null) {
                moves.frames()[moves.from() + i] = System.nanoTime() - t;
            }
        }
        for (int i = 0; i < ZOOMS; i++) {
            final long t = System.nanoTime();
            canvas.zoom(i < ZOOMS / 2 ? 1 : -1);
            paint(canvas, img);
            if (zooms != null) {
                zooms.frames()[zooms.from() + i] = System.nanoTime() - t;
            }
        }
    }

    private static void paint(final PlotCanvas canvas, final BufferedImage img) {
        final Graphics2D g = img.createGraphics();
        canvas.paint(g);
        g.dispose();
    }

    /** Four noisy walks over one shared, ascending x, as a time series of 200 000 samples each. */
    private static ViewSpec.Plot plot() {
        final Random random = new Random(42);
        final double[] x = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            x[i] = 1_700_000_000_000.0 + i * 1000.0;
        }
        final List<ViewSpec.Series> series = new ArrayList<>();
        for (int s = 0; s < SERIES; s++) {
            final double[] y = new double[POINTS];
            double v = s * 100;
            for (int i = 0; i < POINTS; i++) {
                v += random.nextGaussian();
                y[i] = v;
            }
            series.add(new ViewSpec.Series("series-" + s, x, y));
        }
        return new ViewSpec.Plot("time", "value", true, series);
    }

    private static double report(final String what, final long[] frames) {
        final long[] sorted = frames.clone();
        Arrays.sort(sorted);
        final double p50 = ms(sorted[sorted.length / 2]);
        final double p90 = ms(sorted[(int) (sorted.length * 0.90)]);
        final double p99 = ms(sorted[(int) (sorted.length * 0.99)]);
        final double max = ms(sorted[sorted.length - 1]);
        System.out.printf(Locale.ROOT, "%-18s %4d frames: p50 %.2f ms, p90 %.2f ms, p99 %.2f ms, max %.2f ms%n", what, sorted.length, p50, p90,
                p99, max);
        return p99;
    }

    private static double ms(final long nanos) {
        return nanos / 1e6;
    }
}
