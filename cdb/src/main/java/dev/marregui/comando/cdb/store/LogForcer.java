package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.jetbrains.annotations.TestOnly;

/**
 * The timed force of a store opened with {@link StoreOptions#isTimed()}: one platform daemon thread,
 * {@code cdb-log-force-<root>} (a force blocks in the file system, which would pin a virtual thread), that forces each
 * table log at most {@link StoreOptions#logForceMillis()} after its first unforced record, and at once for a commit a
 * durable request waits on. Started by {@link Store#open} and stopped by {@link Store#close}, after a last force of
 * every log.
 *
 * <p>Per log it keeps a deadline: the first {@link #dirty} since the last force sets it to that moment plus the
 * interval, {@link #urgent} brings it to now. The thread parks until the earliest deadline or a signal, then forces
 * every log that is due, one after another, outside every lock. A failed force poisons the table, as a failed force of
 * a commit does: its commits are refused until the store reopens, and the log is never forced again, since a retry can
 * succeed over pages the failure dropped. Each log's monitor is a leaf: nothing is called while it is held.</p>
 */
final class LogForcer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(LogForcer.class.getName());
    // the longest the thread parks: a bound, not a poll, as every first dirty record and every durable waiter signals
    // it; as the applier's
    private static final long IDLE_PARK_NANOS = TimeUnit.SECONDS.toNanos(1L);
    // the close waits this long for a force in flight and the last round: as the store's wait for an open writer
    private static final long JOIN_MILLIS = 10_000L;
    // forceDue's answer when no log is armed
    private static final long NOTHING_DUE = -1L;

    /** Forces one table's log: {@link Wal#force()}, replaced by a test to count or fail the forces. */
    // written by a test before its commits, read by the thread and by a force after close
    @TestOnly
    volatile LogForce forceLog = Wal::force;

    private final long intervalMillis;
    private final long intervalNanos;
    private final Thread thread;
    // one per table that had a dirty record; a table's entry is removed when the table is dropped (forget)
    private final Map<TableState, Log> logs = new ConcurrentHashMap<>();
    // written by close, read by the thread and by dirty and urgent under a log's monitor; written before the unpark
    // that wakes the thread, and read under each log's monitor by the last round, so a dirty either arms a log the
    // last round then forces, or sees the flag and forces on its own thread
    private volatile boolean stopped;
    // written by dirty and urgent after they armed or hurried a log and before their unpark, cleared by the thread
    // before it reads the logs: a signal set after the clear makes the thread read them again
    private volatile boolean signalled;
    // the closing thread's: start ran
    private boolean started;

    /** {@code name} is the store's root's, for the thread's name. */
    LogForcer(final String name, final long intervalMillis) {
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("refuse log force interval that is not positive [intervalMillis="
                    + intervalMillis + ']');
        }
        this.intervalMillis = intervalMillis;
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
        this.thread = Thread.ofPlatform().daemon().name("cdb-log-force-" + name).unstarted(this::run);
    }

    void start() {
        started = true;
        thread.start();
    }

    /** The interval between a log's first unforced record and its force; read by the tests. */
    @TestOnly
    long intervalMillis() {
        return intervalMillis;
    }

    /**
     * Notes that {@code state}'s log has records written since its last force; called after the record is written
     * and before its commit is answered. After {@link #close} the log is forced at once on the calling thread.
     */
    void dirty(final TableState state) {
        final Log log = log(state);
        synchronized (log) {
            if (log.failed) {
                return;
            }
            if (!stopped) {
                if (!log.armed) {
                    final long nowNanos = System.nanoTime();
                    log.armed = true;
                    log.deadlineNanos = nowNanos + intervalNanos;
                    log.dueNanos = log.deadlineNanos;
                    signal();
                }
                return;
            }
        }
        // closed: the thread made its last round, so the commit is forced here, as under the force per commit; an
        // interrupt would close the log's channel mid-force, so it is cleared for the force and restored
        final boolean interrupted = Thread.interrupted();
        try {
            force(log, System.nanoTime());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Stops forcing the log of {@code state}, a dropped table: a force in flight may still fail on the closed log,
     * and is not reported.
     */
    void forget(final TableState state) {
        final Log log = logs.remove(state);
        if (log == null) {
            return;
        }
        synchronized (log) {
            log.failed = true;
            log.armed = false;
        }
    }

    /** Asks for {@code state}'s log to be forced now, for a durable waiter. */
    void urgent(final TableState state) {
        final Log log = logs.get(state);
        if (log == null) {
            return;
        }
        synchronized (log) {
            // a log not armed has no record a force in flight or done does not cover: its disarm came before that
            // force read the log's last record
            if (!log.armed || log.failed || stopped) {
                return;
            }
            log.dueNanos = System.nanoTime();
            signal();
        }
    }

    /**
     * The timed forces of {@code state}'s log so far: the last transaction a force covered (0 before any), how many
     * held, and the most one held past its deadline. Read without waiting for a force in flight; read by the tests,
     * not yet listed by {@code wal_tables()}.
     */
    @TestOnly
    Status status(final TableState state) {
        final Log log = logs.get(state);
        if (log == null) {
            return new Status(0L, 0L, 0L);
        }
        return new Status(log.forcedTxn, log.forces, TimeUnit.NANOSECONDS.toMillis(log.lateMaxNanos));
    }

    /** Forces every dirty log once more and ends the thread; idempotent. */
    @Override
    public void close() {
        if (stopped) {
            return;
        }
        stopped = true;
        LockSupport.unpark(thread);
        if (!started) {
            // never started: no thread to make the last round
            forceAll();
            return;
        }
        if (thread.isAlive() && Thread.currentThread() != thread) {
            try {
                thread.join(JOIN_MILLIS);
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                LOG.log(System.Logger.Level.WARNING, "cannot end log forcer within its wait, a force is in flight"
                        + " [thread=" + thread.getName() + ", waitMillis=" + JOIN_MILLIS + ']');
            }
        }
    }

    // called holding the log's monitor: the thread reads the logs again
    private void signal() {
        // the log's state is written before the flag, the flag before the unpark: the thread clears the flag before it
        // reads the logs, so it either sees this log or the flag again
        signalled = true;
        LockSupport.unpark(thread);
    }

    private Log log(final TableState state) {
        final Log log = logs.get(state);
        return log != null ? log : logs.computeIfAbsent(state, Log::new);
    }

    // forces the due logs, parks until the earliest deadline or a signal, and makes a last round once stopped
    private void run() {
        boolean armed = false;
        long dueNanos = 0L;
        while (!stopped) {
            if (signalled || armed && dueNanos - System.nanoTime() <= 0L) {
                signalled = false;
                final long untilNanos = forceDue();
                armed = untilNanos != NOTHING_DUE;
                dueNanos = System.nanoTime() + untilNanos;
            }
            if (!signalled && !stopped) {
                final long parkNanos = armed
                        ? Math.min(IDLE_PARK_NANOS, Math.max(1L, dueNanos - System.nanoTime()))
                        : IDLE_PARK_NANOS;
                LockSupport.parkNanos(this, parkNanos);
            }
        }
        forceAll();
    }

    // forces every armed log whose deadline came; the nanos until the next deadline, NOTHING_DUE when none is armed
    private long forceDue() {
        long untilNanos = NOTHING_DUE;
        for (final Log log : logs.values()) {
            if (stopped) {
                break;
            }
            final long deadlineNanos;
            synchronized (log) {
                if (!log.armed) {
                    continue;
                }
                final long leftNanos = log.dueNanos - System.nanoTime();
                if (leftNanos > 0L) {
                    untilNanos = untilNanos == NOTHING_DUE ? leftNanos : Math.min(untilNanos, leftNanos);
                    continue;
                }
                // disarmed before the force reads the log's last record: a record dirtied from here arms it again
                log.armed = false;
                deadlineNanos = log.deadlineNanos;
            }
            force(log, deadlineNanos);
        }
        return untilNanos;
    }

    // the last round: every armed log forced, due or not
    private void forceAll() {
        for (final Log log : logs.values()) {
            final long deadlineNanos;
            synchronized (log) {
                if (!log.armed) {
                    continue;
                }
                log.armed = false;
                deadlineNanos = log.deadlineNanos;
            }
            force(log, deadlineNanos);
        }
    }

    // forces one log outside its monitor; a failure poisons the table for good
    private void force(final Log log, final long deadlineNanos) {
        final TableState state = log.state;
        // read before the force: the force covers at least every commit answered by now
        final long upToTxn = state.loggedTxn();
        try {
            forceLog.force(state.wal);
        } catch (final Throwable t) {
            // an Error is taken as a failed force too: the page cache cannot be trusted after either
            failed(log, t);
            return;
        }
        // a force that beat its deadline, for a durable waiter or the last round, is not late
        final long lateNanos = Math.max(0L, System.nanoTime() - deadlineNanos);
        synchronized (log) {
            log.forces++;
            log.forcedTxn = Math.max(log.forcedTxn, upToTxn);
            log.lateMaxNanos = Math.max(log.lateMaxNanos, lateNanos);
        }
    }

    private void failed(final Log log, final Throwable cause) {
        final long forcedTxn;
        synchronized (log) {
            if (log.failed) {
                return;
            }
            log.failed = true;
            log.armed = false;
            forcedTxn = log.forcedTxn;
        }
        final TableState state = log.state;
        state.poison("timed log force failed [cause=" + cause + ']');
        // the commits since the last force that held, or the checkpoint, whichever is later, up to the last answered
        final TableState.Snapshot named = state.checkpointed();
        final long fromTxn = Math.max(forcedTxn, named == null ? 0L : named.txn()) + 1L;
        LOG.log(System.Logger.Level.ERROR, "refuse further commits, a timed log force failed, commits in doubt [table="
                + state.key + ", fromTxn=" + fromTxn + ", toTxn=" + state.loggedTxn() + ']', cause);
    }

    /** The forcing step of one log. */
    @FunctionalInterface
    interface LogForce {

        /** Forces {@code wal}; a throw is a failed force. */
        void force(Wal wal) throws IOException;
    }

    /**
     * One table's timed forces, as {@link #status} reads them.
     *
     * @param forcedTxn          the last transaction a timed force covered, 0 before any
     * @param forces             the timed forces that held
     * @param forceLateMaxMillis the most a force held past its deadline, the first unforced record plus the interval;
     *                           0 when none was late
     */
    record Status(long forcedTxn, long forces, long forceLateMaxMillis) {
    }

    // one table's log: its deadline and its forces' figures, guarded by its monitor; the figures volatile for the
    // status listing, which reads them without it
    private static final class Log {

        final TableState state;
        // a record was dirtied since the last force began
        boolean armed;
        // the first unforced record's moment plus the interval: what a force's lateness is measured against
        long deadlineNanos;
        // when the thread forces the log: the deadline, or earlier for a durable waiter
        long dueNanos;
        // a force failed: never forced again
        boolean failed;
        // written under the monitor by the forcing thread, read by the status listing without it
        volatile long forcedTxn;
        volatile long forces;
        volatile long lateMaxNanos;

        Log(final TableState state) {
            this.state = state;
        }
    }
}
