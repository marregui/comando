package dev.marregui.comando.frontear.queue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The timed force of one link log: one platform daemon thread, {@code frontear-log-force-<folder>} (a force blocks
 * in the file system, which would pin a virtual thread's carrier), that runs the log's round at most the interval
 * after it was armed. Mirrored from cdb's store {@code LogForcer}: started by the log's open, stopped by its close
 * through a flag written before the unpark that wakes the thread, never by an interrupt, which would close a
 * segment's channel mid-write or mid-force; once stopped the thread runs the log's last round and ends.
 */
final class LogForcer {

    private static final System.Logger LOG = System.getLogger(LogForcer.class.getName());
    // the longest the thread parks: a bound, not a poll, as every arm and the close unpark it; as cdb's log forcer
    private static final long IDLE_PARK_NANOS = TimeUnit.SECONDS.toNanos(1L);
    // the close waits this long for a force in flight and the last round: as cdb's log forcer waits for its last round
    private static final long JOIN_MILLIS = 10_000L;

    private final Runnable round;
    private final Runnable last;
    private final long intervalNanos;
    private final Thread thread;
    // guards armed and dueNanos: taken by arm and by the thread, a leaf, nothing is called while it is held
    private final Object lock = new Object();
    // a round is due at dueNanos and has not started
    private boolean armed;
    private long dueNanos;
    // written by close before the unpark that wakes the thread, read by the thread and by close: the thread then
    // runs the last round and ends
    private volatile boolean stopped;
    // written by arm after it armed and before its unpark, cleared by the thread before it reads armed: an arm after
    // the clear makes the thread read it again
    private volatile boolean signalled;
    // the opening thread's, then the closing thread's: start ran
    private boolean started;

    /**
     * {@code name} is the log folder's, for the thread's name; {@code round} runs on the thread once per arm, at most
     * {@code intervalNanos} after it; {@code last} runs once after the close, on the thread, or on the closer when
     * the thread never started.
     */
    LogForcer(final String name, final long intervalNanos, final Runnable round, final Runnable last) {
        this.round = round;
        this.last = last;
        this.intervalNanos = intervalNanos;
        this.thread = Thread.ofPlatform().daemon().name("frontear-log-force-" + name).unstarted(this::run);
    }

    void start() {
        started = true;
        thread.start();
    }

    /** Asks for a round within the interval; an arm while one is due changes nothing. */
    void arm() {
        synchronized (lock) {
            if (armed) {
                return;
            }
            armed = true;
            // a sum past Long.MAX_VALUE wraps, and the subtraction the thread compares by still holds
            dueNanos = System.nanoTime() + intervalNanos;
        }
        // armed is written before the flag, the flag before the unpark: the thread clears the flag before it reads
        // armed, so it either sees this arm or the flag again
        signalled = true;
        LockSupport.unpark(thread);
    }

    /**
     * Stops the thread, which runs the last round, and waits a bounded time for it to end; idempotent. When the wait
     * runs out the thread is left to end on its own, the last round still to run there: true when it ended, or never
     * started.
     */
    boolean stop() {
        if (stopped) {
            return !thread.isAlive();
        }
        stopped = true;
        LockSupport.unpark(thread);
        if (!started) {
            // never started: no thread to run the last round
            last.run();
            return true;
        }
        if (thread.isAlive() && Thread.currentThread() != thread) {
            try {
                thread.join(JOIN_MILLIS);
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                LOG.log(System.Logger.Level.WARNING, "cannot end link log forcer within its wait, a force is in"
                        + " flight [thread=" + thread.getName() + ", waitMillis=" + JOIN_MILLIS + ']');
                return false;
            }
        }
        return true;
    }

    // runs the armed rounds as they come due, parks until the next or a signal, and runs the last round once stopped
    private void run() {
        while (!stopped) {
            signalled = false;
            final boolean due;
            final long waitNanos;
            synchronized (lock) {
                final long leftNanos = armed ? dueNanos - System.nanoTime() : IDLE_PARK_NANOS;
                due = armed && leftNanos <= 0L;
                if (due) {
                    // disarmed before the round reads the log: an arm from here asks for the next round
                    armed = false;
                }
                waitNanos = Math.min(IDLE_PARK_NANOS, Math.max(1L, leftNanos));
            }
            if (due) {
                round.run();
            } else if (!signalled && !stopped) {
                LockSupport.parkNanos(this, waitNanos);
            }
        }
        last.run();
    }
}
