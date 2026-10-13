package dev.marregui.comando.ui.kit.async;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

import javax.swing.SwingUtilities;
import java.util.function.BiConsumer;

/**
 * Reports an event dispatch thread that does not answer for {@link #THRESHOLD_MILLIS}: every
 * {@link #PERIOD_MILLIS} a probe is posted to the EDT; a probe still waiting past the threshold logs the
 * EDT's stack once, and a JFR event records how long the EDT was blocked when it answers.
 */
public final class EdtWatchdog implements AutoCloseable {

    public static final long PERIOD_MILLIS = 100;
    public static final long THRESHOLD_MILLIS = 200;
    private static final System.Logger LOG = System.getLogger(EdtWatchdog.class.getName());

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
    }

    private final Thread thread;
    private final BiConsumer<Long, String> report;
    private volatile Thread edt;
    private volatile long posted;
    private volatile long answered;
    private volatile boolean running = true;

    /** Starts watching; {@code report} receives the blocked time and the EDT's stack, on the watchdog thread. */
    public EdtWatchdog(BiConsumer<Long, String> report) {
        this.report = report;
        this.thread = Thread.ofPlatform().daemon(true).name("edt-watchdog").start(this::watch);
    }

    /** Logs a warning with the EDT's stack. */
    public static EdtWatchdog logging() {
        return new EdtWatchdog((millis, stack) -> LOG.log(System.Logger.Level.WARNING,
                "the EDT has not answered for " + millis + " ms:\n" + stack));
    }

    private void watch() {
        while (running) {
            long probe = System.nanoTime();
            posted = probe;
            SwingUtilities.invokeLater(() -> {
                edt = Thread.currentThread();
                answered = probe;
            });
            String stack = null;
            long blockedSince = probe;
            while (running && answered != probe) {
                sleep(PERIOD_MILLIS);
                long waited = (System.nanoTime() - blockedSince) / 1_000_000;
                if (stack == null && waited >= THRESHOLD_MILLIS) {
                    stack = stackOf(edt);
                    report.accept(waited, stack);
                }
            }
            if (stack != null) {
                EdtBlockedEvent e = new EdtBlockedEvent();
                if (e.shouldCommit()) {
                    e.blockedMillis = (System.nanoTime() - blockedSince) / 1_000_000;
                    e.stack = stack;
                    e.commit();
                }
            }
            sleep(PERIOD_MILLIS);
        }
    }

    private static String stackOf(Thread t) {
        if (t == null) {
            return "(the EDT has not run a probe yet)";
        }
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] frames = t.getStackTrace();
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
