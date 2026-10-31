package dev.marregui.comando.ui.widgets.table;

import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;

/**
 * The grid's frame-time gate: a {@link GridTable} over 100 000 rows of 12 columns (longs, doubles and text, with nulls)
 * in a 1600 x 900 viewport, each frame moving the view and painting it, first a fling through the whole result, then a
 * slow scroll by a few rows. Prints percentiles and {@code HOT} when p99 is above {@value #HOT_MILLIS} ms, the line
 * above which a paint path is worth optimising. It measures: the exit code is 0 either way, and a p99 above the
 * 16 ms frame budget is printed as {@code OVER BUDGET}. It is 3 when the grid was not laid out, 2 when the JVM is still
 * alive {@value #EXIT_GRACE_MILLIS} ms after the measurement (AWT ends the JVM by itself once nothing posts to it, so a
 * displayable component left behind would otherwise hang the gate).
 */
public final class GridGate {

    /** The transcript gate's exit watch. */
    private static final long EXIT_GRACE_MILLIS = 10_000;
    /** Miguel's line for calling a paint path hot: p99 above 4 ms. */
    private static final double HOT_MILLIS = 4.0;
    /** One 60 Hz frame. */
    private static final double BUDGET_MILLIS = 16.0;
    /** The result size the gate stands for: 100 000 rows by 12 columns. */
    private static final int ROWS = 100_000;
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;
    /** Enough frames for the JIT to settle; the measured frames are 900, above the 500 the gate asks for. */
    private static final int WARM_UP_FRAMES = 300;
    private static final int FLING_FRAMES = 600;
    private static final int SLOW_FRAMES = 300;

    private GridGate() {
    }

    public static void main(final String[] args) throws Exception {
        // on the EDT, as the widget runs
        SwingUtilities.invokeAndWait(GridGate::measure);
        exitWatch();
    }

    /** A daemon thread, so it never holds the JVM: it only speaks when something else does. */
    private static void exitWatch() {
        final Thread watch = new Thread(() -> {
            try {
                Thread.sleep(EXIT_GRACE_MILLIS);
            } catch (InterruptedException e) {
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
        final GridTable grid = new GridTable();
        grid.setSource(new Rows(ROWS), "gate.csv");
        final JScrollPane scroll = (JScrollPane) grid.component();
        scroll.setSize(WIDTH, HEIGHT);
        layout(scroll);
        final JViewport viewport = scroll.getViewport();
        final BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB_PRE);

        if (viewport.getWidth() == 0 || grid.table().getHeight() <= viewport.getHeight()) {
            System.out.println("GATE FAILED: the grid was not laid out, viewport " + viewport.getSize() + ", table " + grid.table().getSize());
            System.exit(3);
        }
        final int maxTop = Math.max(0, grid.table().getHeight() - viewport.getHeight());
        // class loading and JIT, then the measured passes
        frames(scroll, viewport, img, WARM_UP_FRAMES, maxTop / WARM_UP_FRAMES);
        final long[] fling = frames(scroll, viewport, img, FLING_FRAMES, maxTop / FLING_FRAMES);
        final long[] slow = frames(scroll, viewport, img, SLOW_FRAMES, 3 * grid.table().getRowHeight());

        final long[] all = new long[fling.length + slow.length];
        System.arraycopy(fling, 0, all, 0, fling.length);
        System.arraycopy(slow, 0, all, fling.length, slow.length);
        System.out.printf(Locale.ROOT, "grid %d rows x 12 columns, viewport %d x %d, row height %d px, headless %s%n", ROWS, viewport.getWidth(),
                viewport.getHeight(), grid.table().getRowHeight(), System.getProperty("java.awt.headless"));
        report("fling through the result", fling);
        report("slow scroll, 3 rows a frame", slow);
        final double p99 = report("all frames", all);
        System.out.println(p99 > HOT_MILLIS ? "HOT" : "NOT HOT");
        if (p99 > BUDGET_MILLIS) {
            System.out.println("OVER BUDGET: p99 above " + BUDGET_MILLIS + " ms");
        }
    }

    /** Lays out {@code c} and everything under it, parents first, as a displayed container would be; no peer is needed. */
    private static void layout(final Container c) {
        c.doLayout();
        for (final Component child : c.getComponents()) {
            if (child instanceof Container inner) {
                layout(inner);
            }
        }
    }

    /** Moves the view down by {@code stride} pixels a frame, wrapping at the end, and paints it; the time of each frame. */
    private static long[] frames(
            final JScrollPane scroll,
            final JViewport viewport,
            final BufferedImage img,
            final int count,
            final int stride
    ) {
        final int maxTop = Math.max(1, viewport.getView().getHeight() - viewport.getHeight());
        final long[] frames = new long[count];
        int top = 0;
        for (int i = 0; i < count; i++) {
            final long t = System.nanoTime();
            viewport.setViewPosition(new Point(0, top));
            final Graphics2D g = img.createGraphics();
            scroll.paint(g);
            g.dispose();
            frames[i] = System.nanoTime() - t;
            top = (top + Math.max(1, stride)) % maxTop;
        }
        return frames;
    }

    private static double report(final String what, final long[] frames) {
        final long[] sorted = frames.clone();
        Arrays.sort(sorted);
        final double p50 = ms(sorted[sorted.length / 2]);
        final double p90 = ms(sorted[(int) (sorted.length * 0.90)]);
        final double p99 = ms(sorted[(int) (sorted.length * 0.99)]);
        final double max = ms(sorted[sorted.length - 1]);
        System.out.printf(Locale.ROOT, "%-28s %4d frames: p50 %.2f ms, p90 %.2f ms, p99 %.2f ms, max %.2f ms%n", what, sorted.length, p50, p90,
                p99, max);
        return p99;
    }

    private static double ms(final long nanos) {
        return nanos / 1e6;
    }

    /** Rows of three longs, three doubles, four texts and two mixed columns, one in ten cells null in the nullable ones. */
    private static final class Rows implements TableSource {

        private static final int COLUMNS = 12;
        private final int rows;
        private final long[][] longs = new long[3][];
        private final double[][] doubles = new double[3][];
        private final String[][] texts = new String[6][];

        Rows(final int rows) {
            this.rows = rows;
            final Random random = new Random(42);
            for (int c = 0; c < longs.length; c++) {
                longs[c] = new long[rows];
                for (int r = 0; r < rows; r++) {
                    longs[c][r] = random.nextLong() >> (c * 8);
                }
            }
            for (int c = 0; c < doubles.length; c++) {
                doubles[c] = new double[rows];
                for (int r = 0; r < rows; r++) {
                    doubles[c][r] = random.nextGaussian() * 1000;
                }
            }
            for (int c = 0; c < texts.length; c++) {
                texts[c] = new String[rows];
                for (int r = 0; r < rows; r++) {
                    // one in ten null; lengths from 4 to 40, as names and messages are
                    texts[c][r] = random.nextInt(10) == 0 ? null : "value-" + Long.toString(random.nextLong() >>> 1, 36).repeat(1 + random.nextInt(3));
                }
            }
        }

        @Override
        public int rowCount() {
            return rows;
        }

        @Override
        public int columnCount() {
            return COLUMNS;
        }

        @Override
        public String columnName(final int column) {
            return "col" + column;
        }

        @Override
        public String typeName(final int column) {
            return column < 3 ? "long" : column < 6 ? "double" : "text";
        }

        @Override
        public boolean isNumeric(final int column) {
            return column < 6;
        }

        @Override
        public boolean isNull(final int row, final int column) {
            // the double columns carry the nulls among the numbers, the text columns hold them as missing strings
            return column >= 3 && column < 6 ? row % 10 == column : column >= 6 && texts[column - 6][row] == null;
        }

        @Override
        public void format(final int row, final int column, final StringBuilder sink) {
            if (isNull(row, column)) {
                sink.append("null");
            } else if (column < 3) {
                sink.append(longs[column][row]);
            } else if (column < 6) {
                sink.append(doubles[column - 3][row]);
            } else {
                sink.append(texts[column - 6][row]);
            }
        }

        @Override
        public double numeric(final int row, final int column) {
            if (column >= 6 || isNull(row, column)) {
                return Double.NaN;
            }
            return column < 3 ? longs[column][row] : doubles[column - 3][row];
        }
    }
}
