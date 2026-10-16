package dev.marregui.comando.ui.kit.async;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

import javax.swing.SwingUtilities;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Predicate;

/**
 * Watches the event dispatch thread: every {@link #PERIOD_MILLIS} a probe is posted to it, and a probe still waiting
 * past {@link #THRESHOLD_MILLIS} is a stall. The EDT is looked at once then, to tell what kind of stall it is and
 * where; the stall is told once it ends, with its whole length, and a JFR event records it.
 * <p>
 * A stall that reaches its limit is a hang: it is reported with the EDT's stack, taken again at the limit, while the
 * EDT is still held. The limit is {@link #HANG_LIMIT_MILLIS}, and {@link #DRAWING_LIMIT_MILLIS} for a stall in the
 * JDK's drawing (Java2D waiting for its lock while macOS's Metal pipeline flushes to the GPU), which is not comando's.
 */
public final class EdtWatchdog implements AutoCloseable {

    public static final long PERIOD_MILLIS = 100;
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

    @Name("comando.EdtBlocked")
    @Label("EDT blocked")
    @Category("comando")
    @Description("The event dispatch thread did not run a posted probe within the threshold")
    @StackTrace(false)
    static final class EdtBlockedEvent extends Event {
        @Label("Blocked")
        @Timespan(Timespan.MILLISECONDS)
        long blockedMillis;

        @Label("Kind")
        String kind;

        @Label("Where")
        String where;

        @Label("EDT stack")
        String stack;
    }

    private final Thread thread;
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

    /** Starts watching; {@code report} receives each hang, on the watchdog thread. */
    public EdtWatchdog(Consumer<Stall> report) {
        this(report, (stall, millis) -> { });
    }

    /**
     * Starts watching; {@code report} receives a hang at its limit, and {@code ended} every stall with its whole
     * length when it ends. Both run on the watchdog thread.
     */
    public EdtWatchdog(Consumer<Stall> report, ObjLongConsumer<Stall> ended) {
        this(report, ended, EdtWatchdog::drawingOnly, HANG_LIMIT_MILLIS, DRAWING_LIMIT_MILLIS);
    }

    EdtWatchdog(Consumer<Stall> report, ObjLongConsumer<Stall> ended, Predicate<StackTraceElement[]> drawing,
                long hangLimitMillis, long drawingLimitMillis) {
        this.report = report;
        this.ended = ended;
        this.drawing = drawing;
        this.hangLimitMillis = hangLimitMillis;
        this.drawingLimitMillis = drawingLimitMillis;
        this.thread = Thread.ofPlatform().daemon(true).name("edt-watchdog").start(this::watch);
    }

    /** How long the latest probe has waited for the EDT, 0 once answered; for tests, which hold the EDT until it is seen. */
    long probeWaitMillis() {
        long p = posted;
        return p == 0 || answered == p ? 0 : (System.nanoTime() - p) / 1_000_000;
    }

    /** Waits until the EDT has answered a probe, so the watchdog knows it; for tests. */
    boolean awaitFirstAnswer(long timeout, TimeUnit unit) throws InterruptedException {
        return firstAnswer.await(timeout, unit);
    }

    /**
     * The EDT is in the JDK's drawing: waiting on Java2D's render queue (its lock, or a flush to finish) before any
     * comando frame, whoever called the drawing; or in Java2D with no comando frame on the stack at all.
     */
    static boolean drawingOnly(StackTraceElement[] frames) {
        boolean java2d = false;
        boolean comando = false;
        for (StackTraceElement f : frames) {
            String c = f.getClassName();
            if (c.startsWith("dev.marregui.")) {
                comando = true;
            } else if (!comando && renderQueueWait(f)) {
                return true;
            }
            java2d |= c.startsWith("sun.java2d.");
        }
        return java2d && !comando;
    }

    private static boolean renderQueueWait(StackTraceElement f) {
        String c = f.getClassName();
        return (c.equals("sun.java2d.pipe.RenderQueue") && f.getMethodName().equals("lock"))
                || (c.endsWith("RenderQueue$QueueFlusher") && f.getMethodName().equals("flushNow"));
    }

    /** {@code in=} the first frame outside {@code java.base}, {@code comando=} the first comando frame with its line. */
    static String where(StackTraceElement[] frames) {
        String in = "-";
        String comando = "-";
        for (StackTraceElement f : frames) {
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

    private static String shortName(StackTraceElement f) {
        String c = f.getClassName();
        return c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName();
    }

    private void watch() {
        while (running) {
            long probe = System.nanoTime();
            posted = probe;
            SwingUtilities.invokeLater(() -> {
                edt = Thread.currentThread();
                answered = probe;
                firstAnswer.countDown();
            });
            Stall stall = null;
            long blockedSince = probe;
            while (running && answered != probe) {
                sleep(PERIOD_MILLIS);
                if (answered == probe) {
                    break; // answered during the sleep: the wait ended below the threshold, or was already looked at
                }
                long waited = (System.nanoTime() - blockedSince) / 1_000_000;
                if (stall == null && waited >= THRESHOLD_MILLIS) {
                    stall = look(waited, false);
                }
                if (stall != null && !stall.reported() && waited >= limitOf(stall)) {
                    stall = look(waited, true); // a hang: the stack where the EDT is held now
                    report.accept(stall);
                }
            }
            if (stall != null) {
                long total = (System.nanoTime() - blockedSince) / 1_000_000;
                ended.accept(stall, total);
                EdtBlockedEvent e = new EdtBlockedEvent();
                if (e.shouldCommit()) {
                    e.blockedMillis = total;
                    e.kind = stall.kind().toString();
                    e.where = stall.where();
                    e.stack = stall.stack();
                    e.commit();
                }
            }
            sleep(PERIOD_MILLIS);
        }
    }

    private Stall look(long waited, boolean reported) {
        StackTraceElement[] frames = framesOf(edt);
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

    private long limitOf(Stall stall) {
        return stall.drawing() ? drawingLimitMillis : hangLimitMillis;
    }

    private static boolean comandoFrame(StackTraceElement[] frames) {
        for (StackTraceElement f : frames) {
            if (f.getClassName().startsWith("dev.marregui.")) {
                return true;
            }
        }
        return false;
    }

    private static StackTraceElement[] framesOf(Thread t) {
        return t == null ? new StackTraceElement[0] : t.getStackTrace();
    }

    private static String format(StackTraceElement[] frames) {
        if (frames.length == 0) {
            return "(the EDT has not run a probe yet)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(frames.length, 40); i++) {
            sb.append("\tat ").append(frames[i]).append('\n');
        }
        return sb.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
