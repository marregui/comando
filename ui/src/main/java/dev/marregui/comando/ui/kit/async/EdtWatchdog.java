package dev.marregui.comando.ui.kit.async;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Predicate;

/**
 * Reports an event dispatch thread that does not answer for {@link #THRESHOLD_MILLIS}: every
 * {@link #PERIOD_MILLIS} a probe is posted to the EDT; a probe still waiting past the threshold reports the EDT's
 * stack once, and a JFR event records how long the EDT was blocked when it answers.
 * <p>
 * A stall spent in the JDK's drawing with no comando frame on the stack (Java2D waiting for its lock while macOS's
 * Metal pipeline flushes to the GPU) is not comando's: it is reported only when it ends, marked as drawing, unless it
 * lasts {@link #DRAWING_LIMIT_MILLIS}, when it is reported like any other, so a hang in drawing still shows.
 */
public final class EdtWatchdog implements AutoCloseable {

    public static final long PERIOD_MILLIS = 100;
    public static final long THRESHOLD_MILLIS = 200;
    /** A drawing stall this long is reported with its stack, as a hang would be. */
    public static final long DRAWING_LIMIT_MILLIS = 2_000;
    private static final System.Logger LOG = System.getLogger(EdtWatchdog.class.getName());

    /**
     * A stall as the watchdog saw it.
     *
     * @param millis  how long the EDT had not answered when the stall was reported
     * @param stack   the EDT's stack then
     * @param drawing true when every look found the EDT in the JDK's drawing, with no comando frame
     */
    public record Stall(long millis, String stack, boolean drawing) {
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

        @Label("EDT stack")
        String stack;

        @Label("Drawing")
        boolean drawing;
    }

    private final Thread thread;
    private final Consumer<Stall> report;
    /** Receives a stall that was reported, or a drawing stall, with its whole length once it ends. */
    private final ObjLongConsumer<Stall> ended;
    private final Predicate<StackTraceElement[]> drawing;
    private final long drawingLimitMillis;
    private volatile Thread edt;
    private volatile long posted;
    private volatile long answered;
    private volatile boolean running = true;
    private final CountDownLatch firstAnswer = new CountDownLatch(1);

    /** Starts watching; {@code report} receives each stall that is not the JDK's drawing, on the watchdog thread. */
    public EdtWatchdog(Consumer<Stall> report) {
        this(report, (stall, millis) -> { });
    }

    /**
     * Starts watching; {@code report} receives a stall at the threshold, and {@code ended} its whole length when it
     * ends; a drawing stall reaches only {@code ended}. Both run on the watchdog thread.
     */
    public EdtWatchdog(Consumer<Stall> report, ObjLongConsumer<Stall> ended) {
        this(report, ended, EdtWatchdog::drawingOnly, DRAWING_LIMIT_MILLIS);
    }

    EdtWatchdog(Consumer<Stall> report, ObjLongConsumer<Stall> ended, Predicate<StackTraceElement[]> drawing, long drawingLimitMillis) {
        this.report = report;
        this.ended = ended;
        this.drawing = drawing;
        this.drawingLimitMillis = drawingLimitMillis;
        this.thread = Thread.ofPlatform().daemon(true).name("edt-watchdog").start(this::watch);
    }

    /** Logs a warning with the EDT's stack. */
    public static EdtWatchdog logging() {
        return new EdtWatchdog(stall -> LOG.log(System.Logger.Level.WARNING,
                "the EDT has not answered for " + stall.millis() + " ms:\n" + stall.stack()));
    }

    /** Waits until the EDT has answered a probe, so the watchdog knows it; for tests. */
    boolean awaitFirstAnswer(long timeout, TimeUnit unit) throws InterruptedException {
        return firstAnswer.await(timeout, unit);
    }

    /** The EDT is in the JDK's drawing (Java2D) and nowhere in comando's code. */
    static boolean drawingOnly(StackTraceElement[] frames) {
        boolean java2d = false;
        for (StackTraceElement f : frames) {
            if (f.getClassName().startsWith("dev.marregui.")) {
                return false;
            }
            java2d |= f.getClassName().startsWith("sun.java2d.");
        }
        return java2d;
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
            Stall reported = null;
            Stall drawn = null;
            long blockedSince = probe;
            while (running && answered != probe) {
                sleep(PERIOD_MILLIS);
                if (answered == probe) {
                    break; // answered during the sleep: the wait ended below the threshold, or was already reported
                }
                long waited = (System.nanoTime() - blockedSince) / 1_000_000;
                if (reported == null && waited >= THRESHOLD_MILLIS) {
                    StackTraceElement[] frames = framesOf(edt);
                    String stack = format(frames);
                    if (frames.length > 0 && drawing.test(frames) && waited < drawingLimitMillis) {
                        drawn = new Stall(waited, stack, true); // the JDK's drawing: wait and see whether it ends
                    } else {
                        reported = new Stall(waited, stack, false);
                        report.accept(reported);
                    }
                }
            }
            Stall stall = reported != null ? reported : drawn;
            if (stall != null) {
                long total = (System.nanoTime() - blockedSince) / 1_000_000;
                ended.accept(stall, total);
                EdtBlockedEvent e = new EdtBlockedEvent();
                if (e.shouldCommit()) {
                    e.blockedMillis = total;
                    e.stack = stall.stack();
                    e.drawing = stall.drawing();
                    e.commit();
                }
            }
            sleep(PERIOD_MILLIS);
        }
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
