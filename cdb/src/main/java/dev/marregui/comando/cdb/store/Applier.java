package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbException;

/**
 * The store's applier: one platform thread (its batches write and force files, which pins a virtual thread) that writes
 * the commits each table's log answered into its column files, many at once, and publishes them; then checkpoints the
 * table when its log holds enough. It serves the tables a commit queued, then the tables whose deadline (schedule,
 * retry, idle checkpoint) came, and parks until the earliest deadline or a signal: an idle store costs no pass over its
 * tables. A batch is one call of the commit's file code over the rows of contiguous commits in log order, so it keeps
 * the same rows as one commit at a time: dedup keeps the last row written, the sort is stable, and the TTL's newest
 * timestamp only grows. A failed batch is retried with the interval backed off; after
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
    // the longest the thread parks: a bound, not a poll, as every commit and reader signals it; also how soon a paused
    // applier looks again, the 1 s of the scan it replaced, the base interval of ApplySchedule
    private static final long IDLE_PARK_NANOS = TimeUnit.SECONDS.toNanos(1L);
    // a turn's answer when the table has nothing due: it keeps no deadline
    private static final long NOTHING_DUE = Long.MAX_VALUE;
    // the close waits this long for a batch in flight: as the store's wait for an open writer
    private static final long JOIN_MILLIS = 10_000L;

    private final Store store;
    private final Thread thread;
    // the heap estimate of every table's queued commits
    private final AtomicLong queuedBytes = new AtomicLong();
    // the tables a commit queued since their last turn, each once (inQueue): as many as the store's tables at most, so
    // never full. Offered by committing threads, polled by the thread
    private final Queue<TableState> queue = new ConcurrentLinkedQueue<>();
    private final Set<TableState> inQueue = ConcurrentHashMap.newKeySet();
    // the thread's alone: one entry per table whose apply, retry, idle checkpoint or pause is not yet due, the earliest
    // first; as many as the store's tables at most, so a removal's scan is short
    private final PriorityQueue<Deadline> deadlines = new PriorityQueue<>(
            (a, b) -> Long.compare(a.dueNanos() - b.dueNanos(), 0L));
    private final Map<TableState, Deadline> deadlineOf = new HashMap<>();
    // written by close, read by the thread; written before the unpark that wakes it
    private volatile boolean stopped;
    // written by commits and readers before their unpark, cleared by the thread before it serves
    private volatile boolean signalled;
    // written by wake (a reader waits, the queued commits press the budget) before its unpark, cleared by the thread
    // before it serves every table once
    private volatile boolean scanAll;
    // set by a commit that waits for the budget; cleared by whatever lowers the queued bytes under half the budget (a
    // batch applied by the applier, a direct commit or the close; a suspend; the waiting commit after its wait);
    // read by the status listing
    private volatile boolean waitedForBudget;
    // written by the thread alone, read by tests: its loop's passes and the table turns it served
    private volatile long passes;
    private volatile long turns;
    // the applier thread's own: when its pass next sweeps idle mappings, by System.nanoTime()
    private long sweepDueNanos;

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
     * Called by a commit once queued: queues its table for the thread, and when the store's queued commits are over
     * budget waits up to the read wait for the thread to bring them under half of it, so a writer faster than the
     * applier slows to it. Over half the budget every table with queued commits is due, so the thread serves them all.
     */
    void committed(final TableState state, final long bytes) {
        final long total = queuedBytes.addAndGet(bytes);
        if (total > store.queuedBudgetBytes / 2L) {
            wake();
        } else {
            enqueue(state);
        }
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
            // the batch that brought the bytes under half may have been applied between this commit's add and its
            // flag, so no later batch clears it: the commit clears it itself once under half
            clearPressureWhenUnderHalf();
        }
    }

    /**
     * Takes the commits queued in {@code state}, a dropped table, off the queued budget unless its suspend already
     * did, the caller holding its {@link TableState#applyLock}: they are freed with the table, never applied, as a
     * turn that takes the lock after the caller lets it go applies nothing.
     */
    void forget(final TableState state) {
        state.forget();
        queuedBytes.addAndGet(-state.takeBudgetBytes());
        clearPressureWhenUnderHalf();
    }

    /** Wakes the thread to serve every table once: a reader waits, the queued commits press the budget. */
    void wake() {
        scanAll = true;
        signalled = true;
        LockSupport.unpark(thread);
    }

    // the set before the queue, so a table is queued once; the thread takes it off the set before its turn, so a
    // commit during the turn queues it again
    private void enqueue(final TableState state) {
        if (inQueue.add(state)) {
            queue.offer(state);
        }
        signalled = true;
        LockSupport.unpark(thread);
    }

    // the check and the clear are two volatile accesses, not one: a commit that sets the flag between them is cleared
    // with the others and shows as 0 or 1 until its own wait ends, when it clears or keeps the flag by this same test
    private void clearPressureWhenUnderHalf() {
        if (queuedBytes.get() <= store.queuedBudgetBytes / 2L) {
            waitedForBudget = false;
        }
    }

    /** 0 below half the queued budget, 1 above it, 2 once a commit waited for the applier. */
    int memoryPressure() {
        if (waitedForBudget) {
            return 2;
        }
        return queuedBytes.get() > store.queuedBudgetBytes / 2L ? 1 : 0;
    }

    /** The heap estimate of every table's queued commits, as the budget counts it. */
    @TestOnly
    long queuedBytes() {
        return queuedBytes.get();
    }

    /** The passes of the thread's loop so far. */
    @TestOnly
    long passes() {
        return passes;
    }

    /** The table turns the thread served so far. */
    @TestOnly
    long turns() {
        return turns;
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

    // serves the queued tables, then the due deadlines, then parks until the earliest deadline or a signal; a wake
    // first serves every table once
    private void run() {
        sweepDueNanos = System.nanoTime() + Store.MAP_SWEEP_NANOS;
        while (!stopped) {
            passes++;
            signalled = false;
            if (scanAll) {
                scanAll = false;
                for (final TableState state : store.states()) {
                    if (stopped) {
                        break;
                    }
                    turn(state);
                }
            }
            TableState queued;
            while (!stopped && (queued = queue.poll()) != null) {
                inQueue.remove(queued);
                turn(queued);
            }
            // a turn's new deadline is at least 1 ns after the clock it read, so the due ones end
            final long nowNanos = System.nanoTime();
            while (!stopped && !deadlines.isEmpty() && deadlines.peek().dueNanos() - nowNanos <= 0L) {
                final Deadline due = deadlines.poll();
                deadlineOf.remove(due.state());
                turn(due.state());
            }
            // idle versions' mappings and an idle row-block epoch released here, on the applier's own pass: unmapping
            // a few files or closing one arena is short next to an apply, and the pass comes at least once a second
            if (!stopped && nowNanos - sweepDueNanos >= 0L) {
                store.sweepIdle(nowNanos);
                BlockArenas.shared().sweep();
                sweepDueNanos = System.nanoTime() + Store.MAP_SWEEP_NANOS;
            }
            if (!signalled && !stopped) {
                final Deadline next = deadlines.peek();
                final long parkNanos = next == null
                        ? IDLE_PARK_NANOS
                        : Math.min(IDLE_PARK_NANOS, Math.max(0L, next.dueNanos() - System.nanoTime()));
                LockSupport.parkNanos(this, parkNanos);
            }
        }
    }

    // one table's turn, then its deadline set to its next due time, or dropped when nothing is due
    private void turn(final TableState state) {
        turns++;
        long untilNanos;
        try {
            untilNanos = serve(state);
        } catch (final Throwable t) {
            // an Error (out of memory, a mapping limit) leaves the table's files half-written: it is suspended, as
            // after repeated failures, and the thread goes on serving the others
            state.suspend(String.valueOf(t));
            queuedBytes.addAndGet(-state.takeBudgetBytes());
            clearPressureWhenUnderHalf();
            LOG.log(System.Logger.Level.ERROR, "suspend table, its apply failed, log kept [table=" + state.key + ']',
                    t);
            untilNanos = NOTHING_DUE;
        }
        final Deadline next = untilNanos == NOTHING_DUE
                ? null
                : new Deadline(System.nanoTime() + Math.max(1L, untilNanos), state);
        final Deadline old = next == null ? deadlineOf.remove(state) : deadlineOf.put(state, next);
        if (old != null) {
            deadlines.remove(old);
        }
        if (next != null) {
            deadlines.add(next);
        }
    }

    // applies the table when due and checkpoints it when its log holds enough; the nanos until its next turn is due
    private long serve(final TableState state) {
        if (state.suspended()) {
            return NOTHING_DUE;
        }
        if (store.applierPaused) {
            return IDLE_PARK_NANOS;
        }
        final long nowNanos = System.nanoTime();
        if (state.failures > 0 && nowNanos - state.retryNanos < 0L) {
            // a failed batch is retried after its back-off, even when a reader waits or the budget presses
            return state.retryNanos - nowNanos;
        }
        final long oldestNanos = state.oldestQueuedNanos(Long.MIN_VALUE);
        if (oldestNanos != Long.MIN_VALUE) {
            final boolean pressed = queuedBytes.get() > store.queuedBudgetBytes / 2L;
            if (!state.schedule.due(nowNanos, oldestNanos, state.demanded(), pressed)) {
                return state.schedule.untilDueNanos(nowNanos, oldestNanos);
            }
            state.applyLock.lock();
            try {
                // dropped while this turn waited for the lock: its folder takes no more writes
                if (state.forgotten()) {
                    return NOTHING_DUE;
                }
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
                if (state.forgotten()) {
                    return NOTHING_DUE;
                }
                checkpointIfDue(state);
            } catch (final RuntimeException e) {
                failed(state, e);
            } finally {
                state.applyLock.unlock();
            }
        }
        return untilDueNanos(state);
    }

    // the nanos until the table's next turn: its retry, its queued commits' schedule or its idle checkpoint
    private long untilDueNanos(final TableState state) {
        if (state.suspended()) {
            return NOTHING_DUE;
        }
        final long nowNanos = System.nanoTime();
        if (state.failures > 0 && nowNanos - state.retryNanos < 0L) {
            return state.retryNanos - nowNanos;
        }
        final long oldestNanos = state.oldestQueuedNanos(Long.MIN_VALUE);
        if (oldestNanos != Long.MIN_VALUE) {
            return state.schedule.untilDueNanos(nowNanos, oldestNanos);
        }
        if (checkpointPending(state)) {
            return Math.max(0L, store.checkpointIdleNanos - (nowNanos - state.checkpointNanos()));
        }
        return NOTHING_DUE;
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
            queuedBytes.addAndGet(-state.takeBudgetBytes());
            clearPressureWhenUnderHalf();
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
        // published, so dequeued: the queue's blocks are closed by their owner, the batch that applied them
        for (final TableState.Queued q : batch) {
            q.rows().close();
        }
        queuedBytes.addAndGet(-state.takeReleasedBytes());
        clearPressureWhenUnderHalf();
        final long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos);
        state.countApply(batch.size(), rows, micros, demanded);
        LOG.log(System.Logger.Level.DEBUG, "applied logged commits [table=" + state.key + ", txns=" + batch.size()
                + ", rows=" + rows + ", txn=" + last + ", micros=" + micros + ']');
        return true;
    }

    // a commit published since the last checkpoint, on a table that may still checkpoint
    private static boolean checkpointPending(final TableState state) {
        final TableState.Snapshot named = state.checkpointed();
        final TableState.Snapshot current = state.current();
        return current != null && named != null && !state.poisoned() && current.txn() != named.txn();
    }

    private boolean dueForCheckpoint(final TableState state) {
        if (!checkpointPending(state)) {
            return false;
        }
        // a drop between the two reads, outside the apply lock, leaves the table without a snapshot
        final TableState.Snapshot current = state.current();
        if (current == null) {
            return false;
        }
        // commits since the last checkpoint, not the log's records: a cut kept for a queued commit leaves older ones
        return current.txn() - state.checkpointed().txn() >= store.checkpointRecords
                || state.wal.bytes() >= store.checkpointBytes
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
                state.poison("folder force failed in a checkpoint");
                LOG.log(System.Logger.Level.ERROR, "refuse further commits, a folder force failed in a checkpoint,"
                        + " log kept [table=" + state.key + ", txn=" + published.txn() + ']');
            }
        } catch (final IOException | RuntimeException e) {
            state.poison(e.toString());
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

    // the thread's next turn of one table
    private record Deadline(long dueNanos, TableState state) {
    }
}
