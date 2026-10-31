package dev.marregui.comando.ui.kit.async;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

import dev.marregui.comando.core.loop.Watchdog;

/**
 * Watches the event dispatch thread: every {@link #PERIOD_MILLIS} a probe is posted to it, and a probe still waiting
 * past {@link #THRESHOLD_MILLIS} is a stall. The EDT is looked at once then, to tell what kind of stall it is and
 * where; the stall is told once it ends, with its whole length.
 * <p>
 * A stall that reaches its limit is a hang: it is reported with the EDT's stack, taken again at the limit, while the
 * EDT is still held. The limit is {@link #HANG_LIMIT_MILLIS}, and {@link #DRAWING_LIMIT_MILLIS} for a stall in the
 * JDK's drawing (Java2D waiting for its lock while macOS's Metal pipeline flushes to the GPU), which is not comando's.
 */
public final class EdtWatchdog implements AutoCloseable {

    public static final long PERIOD_MILLIS = Watchdog.PERIOD_MILLIS;
    public static final long THRESHOLD_MILLIS = 200;
    /** A stall this long is reported with its stack. */
    public static final long HANG_LIMIT_MILLIS = 1_000;
    /** A drawing stall this long is reported with its stack, as a hang would be. */
    public static final long DRAWING_LIMIT_MILLIS = 2_000;

    /** What held the EDT, from one look at its stack. */
    public enum Kind {
        /** the EDT had not run a probe yet: the window is being built */
        STARTUP,
        /** the JDK's drawing, waiting on Java2D's render queue or with no comando frame on the stack */
        DRAWING,
        /** comando's code is on the stack */
        COMANDO,
        /** only the JDK's code is on the stack, outside Java2D */
        JDK;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A stall as the watchdog saw it.
     *
     * @param millis   how long the EDT had not answered when it was looked at
     * @param kind     what held it
     * @param where    one line for grouping stalls: {@code in=} the first frame outside {@code java.base} and
     *                 {@code comando=} the first comando frame with its line, {@code -} when there is none
     * @param stack    the EDT's stack then
     * @param reported true when the stall reached its limit and was reported with its stack
     */
    public record Stall(long millis, Kind kind, String where, String stack, boolean reported) {

        public boolean drawing() {
            return kind == Kind.DRAWING;
        }
    }

    /** The look at the EDT on the shared {@link Watchdog}, from the construction to the close. */
    private final AutoCloseable watching;
    private final Consumer<Stall> report;
    /** Receives every stall with its whole length once it ends. */
    private final ObjLongConsumer<Stall> ended;
    private final Predicate<StackTraceElement[]> drawing;
    private final long hangLimitMillis;
    private final long drawingLimitMillis;
    private volatile Thread edt;
    private volatile long posted;
    private volatile long answered;
    private volatile boolean running = true;
    private final CountDownLatch firstAnswer = new CountDownLatch(1);
    // the watchdog thread's own: the probe waiting for its answer (0 when none), the stall it has made, since when
    private long probe;
    private Stall stall;
    private long blockedSince;

    /** Starts watching; {@code report} receives each hang, on the shared watchdog's thread. */
    public EdtWatchdog(final Consumer<Stall> report) {
        this(report, (stall, millis) -> { });
    }

    /**
     * Starts watching; {@code report} receives a hang at its limit, and {@code ended} every stall with its whole
     * length when it ends. Both run on the shared watchdog's thread.
     */
    public EdtWatchdog(final Consumer<Stall> report, final ObjLongConsumer<Stall> ended) {
        this(report, ended, EdtWatchdog::drawingOnly, HANG_LIMIT_MILLIS, DRAWING_LIMIT_MILLIS);
    }

    EdtWatchdog(final Consumer<Stall> report, final ObjLongConsumer<Stall> ended, final Predicate<StackTraceElement[]> drawing,
                final long hangLimitMillis, final long drawingLimitMillis) {
        this.report = report;
        this.ended = ended;
        this.drawing = drawing;
        this.hangLimitMillis = hangLimitMillis;
        this.drawingLimitMillis = drawingLimitMillis;
        // the first probe now, as the window's build may still hold the EDT; the watchdog's looks follow it
        post(System.nanoTime());
        this.watching = Watchdog.shared().watch(this::look);
    }

    /** How long the latest probe has waited for the EDT, 0 once answered; for tests, which hold the EDT until seen. */
    long probeWaitMillis() {
        final long p = posted;
        return p == 0 || answered == p ? 0 : (System.nanoTime() - p) / 1_000_000;
    }

    /** Waits until the EDT has answered a probe, so the watchdog knows it; for tests. */
    boolean awaitFirstAnswer(final long timeout, final TimeUnit unit) throws InterruptedException {
        return firstAnswer.await(timeout, unit);
    }

    /**
     * The EDT is in the JDK's drawing: waiting on Java2D's render queue (its lock, or a flush to finish) before any
     * comando frame, whoever called the drawing; or in Java2D with no comando frame on the stack at all.
     */
    static boolean drawingOnly(final StackTraceElement[] frames) {
        boolean java2d = false;
        boolean comando = false;
        for (final StackTraceElement f : frames) {
            final String c = f.getClassName();
            if (c.startsWith("dev.marregui.")) {
                comando = true;
            } else if (!comando && renderQueueWait(f)) {
                return true;
            }
            java2d |= c.startsWith("sun.java2d.");
        }
        return java2d && !comando;
    }

    private static boolean renderQueueWait(final StackTraceElement f) {
        final String c = f.getClassName();
        return (c.equals("sun.java2d.pipe.RenderQueue") && f.getMethodName().equals("lock"))
                || (c.endsWith("RenderQueue$QueueFlusher") && f.getMethodName().equals("flushNow"));
    }

    /** {@code in=} the first frame outside {@code java.base}; {@code comando=} the first comando frame and its line. */
    static String where(final StackTraceElement[] frames) {
        String in = "-";
        String comando = "-";
        for (final StackTraceElement f : frames) {
            if (in.equals("-") && !"java.base".equals(f.getModuleName())) {
                in = shortName(f);
            }
            if (f.getClassName().startsWith("dev.marregui.")) {
                comando = shortName(f) + ":" + f.getLineNumber();
                break;
            }
        }
        return "in=" + in + " comando=" + comando;
    }

    private static String shortName(final StackTraceElement f) {
        final String c = f.getClassName();
        return c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName();
    }

    /**
     * One look, every period on the shared watchdog's thread. A probe answered ends its stall, if it made one, with the
     * whole wait, and the next probe is posted; a probe still waiting past the threshold is a stall, looked at once to
     * tell its kind, and again with its stack when it reaches its limit, a hang.
     */
    private void look() {
        if (!running) {
            return;
        }
        final long now = System.nanoTime();
        if (probe == 0 || answered == probe) {
            if (stall != null) {
                ended.accept(stall, (now - blockedSince) / 1_000_000);
                stall = null;
            }
            post(now);
            return;
        }
        final long waited = (now - blockedSince) / 1_000_000;
        if (stall == null && waited >= THRESHOLD_MILLIS) {
            stall = look(waited, false);
        }
        if (stall != null && !stall.reported() && waited >= limitOf(stall)) {
            stall = look(waited, true); // a hang: the stack where the EDT is held now
            report.accept(stall);
        }
    }

    private void post(final long now) {
        final long p = now;
        probe = p;
        blockedSince = p;
        posted = p;
        SwingUtilities.invokeLater(() -> {
            edt = Thread.currentThread();
            answered = p;
            firstAnswer.countDown();
        });
    }

    private Stall look(final long waited, final boolean reported) {
        final StackTraceElement[] frames = framesOf(edt);
        Kind kind;
        if (frames.length == 0) {
            kind = Kind.STARTUP;
        } else if (drawing.test(frames)) {
            kind = Kind.DRAWING;
        } else {
            kind = comandoFrame(frames) ? Kind.COMANDO : Kind.JDK;
        }
        return new Stall(waited, kind, frames.length == 0 ? "" : where(frames), format(frames), reported);
    }

    private long limitOf(final Stall stall) {
        return stall.drawing() ? drawingLimitMillis : hangLimitMillis;
    }

    private static boolean comandoFrame(final StackTraceElement[] frames) {
        for (final StackTraceElement f : frames) {
            if (f.getClassName().startsWith("dev.marregui.")) {
                return true;
            }
        }
        return false;
    }

    private static StackTraceElement[] framesOf(final Thread t) {
        return t == null ? new StackTraceElement[0] : t.getStackTrace();
    }

    private static String format(final StackTraceElement[] frames) {
        if (frames.length == 0) {
            return "(the EDT has not run a probe yet)";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(frames.length, 40); i++) {
            sb.append("\tat ").append(frames[i]).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void close() {
        running = false;
        try {
            watching.close();
        } catch (final Exception e) {
            // the look is a list entry; removing it does not fail
        }
    }
}
