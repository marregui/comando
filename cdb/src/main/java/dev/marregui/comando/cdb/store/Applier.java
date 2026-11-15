package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import dev.marregui.comando.cdb.CdbException;

/**
 * The store's applier: one platform thread (its batches write and force files, which pins a virtual thread) that writes
 * the commits each table's log answered into its column files, many at once, and publishes them; then checkpoints the
 * table when its log holds enough. A batch is one call of the commit's file code over the rows of contiguous commits in
 * log order, so it keeps the same rows as one commit at a time: dedup keeps the last row written, the sort is stable,
 * and the TTL's newest timestamp only grows. A failed batch is retried with the interval backed off; after
 * {@link #MAX_FAILURES} the table is suspended until the store reopens and replays its log.
 */
final class Applier {

    private static final System.Logger LOG = System.getLogger(Applier.class.getName());
    /** A batch takes at most this many rows: as {@code CommitQueue}'s largest group, so its heap stays bounded. */
    static final int MAX_BATCH_ROWS = 100_000;
    // failures before a table is suspended: three tries, the retries 1 s and 2 s after a failure; not measured, set so
    // a transient failure (a file descriptor or mapping limit freed meanwhile) gets two more chances in 3 s
    private static final int MAX_FAILURES = 3;
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(1L);
    // the longest the thread parks with nothing due: bounds a missed wake-up at the base interval of ApplySchedule
    private static final long IDLE_PARK_NANOS = TimeUnit.SECONDS.toNanos(1L);
    // the close waits this long for a batch in flight: as the store's wait for an open writer
    private static final long JOIN_MILLIS = 10_000L;

    private final Store store;
    private final Thread thread;
    // the heap estimate of every table's queued commits
    private final AtomicLong queuedBytes = new AtomicLong();
    // written by close, read by the thread; written before the unpark that wakes it
    private volatile boolean stopped;
    // written by commits and readers before their unpark, cleared by the thread before it scans the tables
    private volatile boolean signalled;
    // written by a commit that waited for the budget and by whoever applies a batch (the applier, a direct commit, the
    // close); read by the status listing
    private volatile boolean waitedForBudget;

    Applier(final Store store, final String name) {
        this.store = store;
        this.thread = Thread.ofPlatform().daemon().name("cdb-applier-" + name).unstarted(this::run);
    }

    void start() {
        thread.start();
    }

    /** Stops the thread after its batch in flight; idempotent. */
    void stop() {
        stopped = true;
        LockSupport.unpark(thread);
        if (thread.isAlive() && Thread.currentThread() != thread) {
            try {
                thread.join(JOIN_MILLIS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Called by a commit once queued: wakes the thread, and when the store's queued commits are over budget waits up
     * to the read wait for the thread to bring them under half of it, so a writer faster than the applier slows to it.
     */
    void committed(final long bytes) {
        final long total = queuedBytes.addAndGet(bytes);
        wake();
        if (total > store.queuedBudgetBytes) {
            waitedForBudget = true;
            final long deadlineNanos = System.nanoTime() + store.readWaitNanos;
            // an interrupted thread's park returns at once: the commit is answered, its wait given up
            while (queuedBytes.get() > store.queuedBudgetBytes / 2L && !stopped
                    && !Thread.currentThread().isInterrupted()) {
                final long leftNanos = deadlineNanos - System.nanoTime();
                if (leftNanos <= 0L) {
                    break;
                }
                wake();
                LockSupport.parkNanos(Math.min(leftNanos, TimeUnit.MILLISECONDS.toNanos(1L)));
            }
        }
    }

    /** Wakes the thread: a commit was queued or a reader waits. */
    void wake() {
        signalled = true;
        LockSupport.unpark(thread);
    }

    /** 0 below half the queued budget, 1 above it, 2 once a commit waited for the applier. */
    int memoryPressure() {
        if (waitedForBudget) {
            return 2;
        }
        return queuedBytes.get() > store.queuedBudgetBytes / 2L ? 1 : 0;
    }

    /**
     * Applies every queued commit of {@code state}, in batches, the caller holding its {@link TableState#applyLock}:
     * a direct commit, the store's checkpoint and close. Throws when a batch fails.
     */
    void applyAllHeld(final TableState state, final boolean demanded) {
        while (applyBatchHeld(state, demanded)) {
            // until the queue is empty
        }
    }

    private void run() {
        while (!stopped) {
            signalled = false;
            long parkNanos = IDLE_PARK_NANOS;
            for (final TableState state : store.states()) {
                if (stopped) {
                    break;
                }
                try {
                    parkNanos = Math.min(parkNanos, serve(state));
                } catch (final Throwable t) {
                    // an Error (out of memory, a mapping limit) leaves the table's files half-written: it is
                    // suspended, as after repeated failures, and the thread goes on serving the others
                    state.suspend(String.valueOf(t));
                    queuedBytes.addAndGet(-state.queuedBytes());
                    LOG.log(System.Logger.Level.ERROR, "suspend table, its apply failed, log kept [table=" + state.key
                            + ']', t);
                }
            }
            if (!signalled && !stopped) {
                LockSupport.parkNanos(this, parkNanos);
            }
        }
    }

    // one table's turn: applies it when due and checkpoints it when its log holds enough; the nanos until it is due
    private long serve(final TableState state) {
        final long nowNanos = System.nanoTime();
        final long oldestNanos = state.oldestQueuedNanos(Long.MIN_VALUE);
        if (state.suspended() || store.applierPaused) {
            return IDLE_PARK_NANOS;
        }
        if (state.failures > 0 && nowNanos - state.retryNanos < 0L) {
            // a failed batch is retried after its back-off, even when a reader waits or the budget presses
            return state.retryNanos - nowNanos;
        }
        if (oldestNanos != Long.MIN_VALUE) {
            final boolean pressed = queuedBytes.get() > store.queuedBudgetBytes / 2L;
            if (!state.schedule.due(nowNanos, oldestNanos, state.demanded(), pressed)) {
                return state.schedule.untilDueNanos(nowNanos, oldestNanos);
            }
            state.applyLock.lock();
            try {
                final boolean demanded = state.takeDemand();
                final long startNanos = System.nanoTime();
                applyAllHeld(state, demanded);
                state.schedule.applied(System.nanoTime() - startNanos, demanded);
                state.failures = 0;
                checkpointIfDue(state);
            } catch (final RuntimeException e) {
                failed(state, e);
            } finally {
                state.applyLock.unlock();
            }
        } else if (dueForCheckpoint(state)) {
            state.applyLock.lock();
            try {
                checkpointIfDue(state);
            } catch (final RuntimeException e) {
                failed(state, e);
            } finally {
                state.applyLock.unlock();
            }
        }
        return state.oldestQueuedNanos(Long.MIN_VALUE) == Long.MIN_VALUE
                ? IDLE_PARK_NANOS
                : state.schedule.untilDueNanos(System.nanoTime(), state.oldestQueuedNanos(System.nanoTime()));
    }

    private void failed(final TableState state, final RuntimeException e) {
        state.schedule.failed();
        state.failures++;
        final long backoffNanos = RETRY_NANOS << (state.failures - 1);
        state.retryNanos = System.nanoTime() + backoffNanos;
        final String error = e.getMessage();
        if (state.failures >= MAX_FAILURES) {
            state.suspend(error);
            // its commits stay queued until the reopen replays them: they no longer press the other tables
            queuedBytes.addAndGet(-state.queuedBytes());
            LOG.log(System.Logger.Level.ERROR, "suspend table, cannot apply its logged commits, log kept [table="
                    + state.key + ", failures=" + state.failures + ']', e);
        } else {
            state.failed(error);
            LOG.log(System.Logger.Level.WARNING, "cannot apply logged commits, retrying [table=" + state.key
                    + ", failures=" + state.failures + ", retryMillis=" + TimeUnit.NANOSECONDS.toMillis(backoffNanos)
                    + ']', e);
        }
    }

    // called with applyLock held: the oldest queued commits, as one commit of the file code; false when none
    private boolean applyBatchHeld(final TableState state, final boolean demanded) {
        final List<TableState.Queued> batch = state.peekQueued(MAX_BATCH_ROWS);
        if (batch.isEmpty()) {
            return false;
        }
        final long startNanos = System.nanoTime();
        final long last = batch.getLast().txn();
        long rows = 0L;
        try (final DefaultWriter writer = new DefaultWriter(store, state, true)) {
            for (final TableState.Queued q : batch) {
                writer.addRows(q.rows());
                rows += q.rows().size;
            }
            writer.commitAs(last);
        }
        queuedBytes.addAndGet(-state.takeReleasedBytes());
        if (queuedBytes.get() <= store.queuedBudgetBytes / 2L) {
            waitedForBudget = false;
        }
        final long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos);
        state.countApply(batch.size(), rows, micros, demanded);
        LOG.log(System.Logger.Level.DEBUG, "applied logged commits [table=" + state.key + ", txns=" + batch.size()
                + ", rows=" + rows + ", txn=" + last + ", micros=" + micros + ']');
        return true;
    }

    private boolean dueForCheckpoint(final TableState state) {
        final TableState.Snapshot named = state.checkpointed();
        final TableState.Snapshot current = state.current();
        if (current == null || named == null || state.poisoned() || current.txn() == named.txn()) {
            return false;
        }
        // commits since the last checkpoint, not the log's records: a cut kept for a queued commit leaves older ones
        return current.txn() - named.txn() >= store.checkpointRecords || state.wal.bytes() >= store.checkpointBytes
                || System.nanoTime() - state.checkpointNanos() >= store.checkpointIdleNanos;
    }

    /**
     * Called with applyLock held: checkpoints the published snapshot when its log holds enough. A log that a stream of
     * commits never lets empty past its bound is drained under the writer lease, so its cut can follow.
     */
    void checkpointIfDue(final TableState state) {
        if (!dueForCheckpoint(state)) {
            return;
        }
        // 4 checkpoints' worth of log, 64 MiB: a stream of commits kept the cut from running that long; not measured.
        // The drain holds the log's monitor, so appends wait for it (one apply and one checkpoint) and the cut follows
        if (state.wal.bytes() >= 4L * store.checkpointBytes) {
            state.wal.holdingAppends(() -> {
                applyAllHeld(state, false);
                checkpoint(state);
            });
        } else {
            checkpoint(state);
        }
    }

    /**
     * Called with applyLock held: a failure refuses the table's further commits until the store reopens, as a failed
     * checkpoint after a logged commit did: the page cache cannot be trusted after a failed force.
     */
    void checkpoint(final TableState state) {
        final TableState.Snapshot published = state.current();
        if (published == null) {
            return;
        }
        try {
            if (!Checkpoint.run(store, state, published)) {
                state.poison();
                state.failed("folder force failed in a checkpoint");
                LOG.log(System.Logger.Level.ERROR, "refuse further commits, a folder force failed in a checkpoint,"
                        + " log kept [table=" + state.key + ", txn=" + published.txn() + ']');
            }
        } catch (final IOException | RuntimeException e) {
            state.poison();
            state.failed(e.getMessage());
            LOG.log(System.Logger.Level.ERROR, "refuse further commits, cannot checkpoint table, log kept [table="
                    + state.key + ", txn=" + published.txn() + ']', e);
        }
    }

    /** Throws a read's failure to see the commits answered before it: the applier gave up or the wait ran out. */
    static CdbException notApplied(final TableState state, final long txn) {
        return new CdbException("cannot read table, its logged commits are not applied [table=" + state.key
                + ", appliedTxn=" + state.appliedTxn() + ", loggedTxn=" + txn + ", suspended=" + state.suspended()
                + ']', -1);
    }
}
