package dev.marregui.comando.cdb.serve;

import java.io.Serial;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.InsertRunner;

/**
 * Group commit of {@code /write} bodies, one queue per table. A request queues its body's rows of each table as a
 * {@link Batch}; one committer at a time per table, the request thread that finds none running, takes every queued
 * batch of that table, replays them into one appender in arrival order and flushes it once (one table write, one
 * fsync), then completes each request. Different tables commit in parallel. A row the store refuses fails only its own
 * request, and none of that request's rows of the table are written: the store's appender drops its batch, the batches
 * of the group before it are replayed, and the batches after it are written. A body that touches several tables is written table by table, each table in its own
 * commit, not atomically across tables; it answers when every table is done.
 *
 * <p>A committer holds one table's writer lease and no lock of this class while it writes, and never waits for another
 * queue, so committers of different tables, the PG wire's statements and {@code Cdb.run} (each holding one table's
 * lease at a time) cannot wait on one another in a cycle.
 *
 * <p>Bounded by the server's admission: at most {@link CdbServer#MAX_REQUESTS} requests in flight, each with at most
 * one batch per table. A batch waits at most {@link #WAIT_MILLIS} for a committer to take it, then its request is
 * refused. After {@link #close()} a new batch is refused; those already queued are still committed by their requests.
 *
 * <p>A durable body is answered only once its rows are durable. The committer closes its appender, marks the group done
 * and lets the table go as for any group, then hands the closed appender to the group's durable batches: each of their
 * requests waits on its own thread, holding no lock of this class, at most {@link InsertRunner#DURABLE_WAIT_MILLIS} in
 * all for the store's force ({@link CdbAppender#awaitDurable}, which answers after the close for the rows the appender
 * flushed). A batch without the flag and the table's next group never wait for that force. Under the force per commit
 * the wait returns at once. A wait that runs out, or a force that failed, answers the request with a
 * {@link NotDurableException}: its rows are written either way.
 */
final class CommitQueue implements AutoCloseable {

    /** As long as the store's appender waits for a table's writer held by another: 30 seconds. */
    static final long WAIT_MILLIS = 30_000L;
    /**
     * Not measured: two of the bench's 50,000-line bodies. A commit holds its rows twice (the batches and the writer's
     * pending rows), so the bound keeps one commit to a few tens of megabytes; a batch over it commits alone.
     */
    static final int MAX_GROUP_ROWS = 100_000;
    private static final System.Logger LOG = System.getLogger(CommitQueue.class.getName());
    // how often a request whose batch a committer took re-checks it; the committer's wake-up normally ends the wait
    private static final long TAKEN_POLL_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private final Supplier<CdbAppender> appenders;
    private final Map<String, Table> tables = new ConcurrentHashMap<>();
    // written by the closing thread, read by request threads as they queue a batch
    private volatile boolean closed;

    CommitQueue(final Supplier<CdbAppender> appenders) {
        this.appenders = appenders;
    }

    /** As {@link #write(Body, boolean)}, not durable. */
    @TestOnly
    Refusal write(final Body body) {
        return write(body, false);
    }

    /**
     * Writes every row of {@code body}, waiting until each of its tables committed, and when {@code durable} until
     * those commits are durable. Returns null when every row was written, else the refusal of the body's first refused
     * row; throws the failure of a commit that wrote the rows of none of its refusals, then a
     * {@link NotDurableException} when every row was written but not every one is known durable.
     */
    Refusal write(final Body body, final boolean durable) {
        final List<Batch> batches = body.batches();
        final List<Table> held = new ArrayList<>(batches.size());
        try {
            for (final Batch batch : batches) {
                final Table table = acquire(batch.key);
                held.add(table);
                table.enqueue(batch, durable);
            }
            for (int i = 0; i < batches.size(); i++) {
                held.get(i).await(batches.get(i));
            }
        } finally {
            for (int i = 0; i < held.size(); i++) {
                release(batches.get(i).key, held.get(i));
            }
        }
        Refusal first = null;
        RuntimeException failure = null;
        for (final Batch batch : batches) {
            if (batch.refusal != null && (first == null || batch.refusal.row() < first.row())) {
                first = batch.refusal;
            }
            if (batch.failure != null && failure == null) {
                failure = batch.failure;
            }
        }
        if (first != null) {
            return first;
        }
        if (failure == null) {
            final NotDurableException notDurable = durable ? awaitDurable(batches) : null;
            if (notDurable != null) {
                throw notDurable;
            }
            return null;
        }
        if (failure instanceof final CdbException cdb) {
            throw cdb;
        }
        throw new IllegalStateException("cannot write rows [tables=" + batches.size() + ']', failure);
    }

    // on the request thread, its tables let go: waits for each table's force, at most DURABLE_WAIT_MILLIS in all; null
    // once every one held. A force that failed outweighs a wait that ran out: those rows may be lost, the others are
    // on their way
    private static NotDurableException awaitDurable(final List<Batch> batches) {
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(InsertRunner.DURABLE_WAIT_MILLIS);
        NotDurableException outcome = null;
        for (final Batch batch : batches) {
            if (batch.durability == null) {
                continue;
            }
            final NotDurableException next = batch.durability.await(deadlineNanos);
            if (next != null && (outcome == null || next.isInDoubt() && !outcome.isInDoubt())) {
                outcome = next;
            }
        }
        return outcome;
    }

    /** Stops queueing: a batch queued after it is refused; the batches already queued are still committed. */
    @Override
    public void close() {
        closed = true;
    }

    /** The batches of {@code table} waiting for a committer. */
    @TestOnly
    int queued(final String table) {
        final Table queue = tables.get(key(table));
        return queue == null ? 0 : queue.queued();
    }

    // the store matches table names without regard to case; a key that split one table in two would only split its
    // group, as the table's writer lease still lets one commit run at a time
    private static String key(final String table) {
        return table.toLowerCase(Locale.ROOT);
    }

    // a table's queue lives while a request holds it, so the map keeps no queue of a table nobody is writing
    private Table acquire(final String key) {
        return tables.compute(key, (_, table) -> {
            final Table held = table == null ? new Table(key) : table;
            held.users++;
            return held;
        });
    }

    private void release(final String key, final Table table) {
        tables.computeIfPresent(key, (_, current) -> current != table || --table.users > 0 ? current : null);
    }

    /** A refused row: its row number in the body, counted from 0, and why the store refused it. */
    record Refusal(int row, CdbException cause) {
    }

    /**
     * A durable write whose rows are written and visible but not known durable: {@link #isInDoubt()} when the store's
     * force failed, so the rows may be lost; else the wait for the force ran out.
     */
    static final class NotDurableException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final boolean inDoubt;

        NotDurableException(final String message, final boolean inDoubt, final Throwable cause) {
            super(message, cause);
            this.inDoubt = inDoubt;
        }

        boolean isInDoubt() {
            return inDoubt;
        }
    }

    /**
     * The durability of one group's commit of a table: the store's appender after its close. The group's durable
     * requests wait on it one at a time, as an appender is not safe for concurrent use; a wait that held settles it for
     * the ones after, as does a force that failed, while a wait that ran out leaves the next its own try.
     */
    private static final class Durability {

        private final String table;
        private final CdbAppender appender;
        // a leaf: held only across the appender's wait, which takes no lock of this class and calls nothing back
        private final ReentrantLock lock = new ReentrantLock();
        // the two below: guarded by lock
        private boolean held;
        private NotDurableException inDoubt;

        Durability(final String table, final CdbAppender appender) {
            this.table = table;
            this.appender = appender;
        }

        // waits until deadlineNanos (System.nanoTime) at most; null once the rows are durable, else why they are not
        NotDurableException await(final long deadlineNanos) {
            boolean locked = false;
            try {
                locked = lock.tryLock(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (!locked) {
                    return notYet();
                }
                if (held || inDoubt != null) {
                    return inDoubt;
                }
                if (appender.awaitDurable(
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0L, deadlineNanos - System.nanoTime())))) {
                    held = true;
                    return null;
                }
                return notYet();
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
                return new NotDurableException("wrote rows, not yet durable, wait interrupted [table=" + table + ']',
                        false, null);
            } catch (final RuntimeException e) {
                // only the appender's wait throws, with the lock held
                LOG.log(Level.WARNING, "commit in doubt [table=" + table + ']', e);
                inDoubt = new NotDurableException("commit in doubt [table=" + table + ", error=" + e.getMessage() + ']',
                        true, e);
                return inDoubt;
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }

        // the wait ran out: the rows are written and on their way to the device
        private NotDurableException notYet() {
            final long waitMillis = InsertRunner.DURABLE_WAIT_MILLIS;
            LOG.log(Level.WARNING, "wrote rows, not yet durable [table=" + table + ", waitMillis=" + waitMillis + ']');
            return new NotDurableException("wrote rows, not yet durable [table=" + table + ", waitMillis=" + waitMillis
                    + ']', false, null);
        }
    }

    /**
     * A body's rows by table, as the parser hands them over: an appender that only records, for the queue to replay
     * into the store's appender.
     */
    static final class Body implements CdbAppender {

        private final Map<String, Batch> batches = new LinkedHashMap<>();
        private Batch current;
        private int rows;

        @Override
        public CdbAppender table(final String table) {
            current = batches.computeIfAbsent(key(table), Batch::new);
            current.startRow(table, rows++);
            return this;
        }

        @Override
        public CdbAppender symbol(final String column, final String value) {
            current.field(column, CdbType.SYMBOL, 0L, 0d, value);
            return this;
        }

        @Override
        public CdbAppender stringColumn(final String column, final String value) {
            current.field(column, CdbType.VARCHAR, 0L, 0d, value);
            return this;
        }

        @Override
        public CdbAppender longColumn(final String column, final long value) {
            current.field(column, CdbType.LONG, value, 0d, null);
            return this;
        }

        @Override
        public CdbAppender doubleColumn(final String column, final double value) {
            current.field(column, CdbType.DOUBLE, 0L, value, null);
            return this;
        }

        @Override
        public CdbAppender booleanColumn(final String column, final boolean value) {
            current.field(column, CdbType.BOOLEAN, value ? 1L : 0L, 0d, null);
            return this;
        }

        @Override
        public CdbAppender timestampColumn(final String column, final long micros) {
            current.field(column, CdbType.TIMESTAMP, micros, 0d, null);
            return this;
        }

        @Override
        public void at(final long micros) {
            current.endRow(micros);
        }

        @Override
        public void discard() {
            batches.clear();
            current = null;
            rows = 0;
        }

        @Override
        public void flush() {
            // the queue writes the rows
        }

        @Override
        public void close() {
            // nothing is held
        }

        List<Batch> batches() {
            return List.copyOf(batches.values());
        }
    }

    /**
     * One request's rows of one table: per row its table name as written, its row number in the body and its
     * timestamp; per field its name, type and value. The committer writes the outcome before it marks the batch done
     * under its table's lock; the request reads it after it saw the batch done under the same lock.
     */
    static final class Batch {

        private static final int INITIAL_ROWS = 16;
        private static final int INITIAL_FIELDS = 64;

        final String key;
        private int rows;
        private String[] rowTables = new String[INITIAL_ROWS];
        private int[] rowNumbers = new int[INITIAL_ROWS];
        private long[] rowMicros = new long[INITIAL_ROWS];
        // the first field of each row; rowFields[rows] is the end of the last row's fields
        private int[] rowFields = new int[INITIAL_ROWS + 1];
        private int fields;
        private String[] names = new String[INITIAL_FIELDS];
        private CdbType[] types = new CdbType[INITIAL_FIELDS];
        private long[] longs = new long[INITIAL_FIELDS];
        private double[] doubles = new double[INITIAL_FIELDS];
        private String[] texts = new String[INITIAL_FIELDS];
        // the six below: guarded by the lock of the batch's table
        private boolean durable;
        private boolean taken;
        private boolean done;
        private Refusal refusal;
        private RuntimeException failure;
        // set for a durable batch the commit wrote; its request waits on it after the batch is done
        private Durability durability;

        Batch(final String key) {
            this.key = key;
        }

        int rows() {
            return rows;
        }

        private void startRow(final String table, final int number) {
            if (rows == rowTables.length) {
                final int grown = rows * 2;
                rowTables = Arrays.copyOf(rowTables, grown);
                rowNumbers = Arrays.copyOf(rowNumbers, grown);
                rowMicros = Arrays.copyOf(rowMicros, grown);
                rowFields = Arrays.copyOf(rowFields, grown + 1);
            }
            rowTables[rows] = table;
            rowNumbers[rows] = number;
            rowFields[rows] = fields;
        }

        private void field(final String name, final CdbType type, final long longValue, final double doubleValue,
                final String text) {
            if (fields == names.length) {
                final int grown = fields * 2;
                names = Arrays.copyOf(names, grown);
                types = Arrays.copyOf(types, grown);
                longs = Arrays.copyOf(longs, grown);
                doubles = Arrays.copyOf(doubles, grown);
                texts = Arrays.copyOf(texts, grown);
            }
            // rows of one table mostly repeat their fields: the previous row's name is kept, the parser's copy let go
            final int position = fields - rowFields[rows];
            String kept = name;
            if (rows > 0 && position < rowFields[rows] - rowFields[rows - 1]) {
                final String previous = names[rowFields[rows - 1] + position];
                kept = previous.equals(name) ? previous : name;
            }
            names[fields] = kept;
            types[fields] = type;
            longs[fields] = longValue;
            doubles[fields] = doubleValue;
            texts[fields] = text;
            fields++;
        }

        private void endRow(final long micros) {
            rowMicros[rows] = micros;
            rows++;
            rowFields[rows] = fields;
        }

        // replays the rows into sink; the first row it refuses ends the replay and is the batch's refusal
        private void replay(final CdbAppender sink) {
            for (int r = 0; r < rows; r++) {
                try {
                    sink.table(rowTables[r]);
                    for (int f = rowFields[r]; f < rowFields[r + 1]; f++) {
                        switch (types[f]) {
                            case SYMBOL -> sink.symbol(names[f], texts[f]);
                            case VARCHAR -> sink.stringColumn(names[f], texts[f]);
                            case LONG -> sink.longColumn(names[f], longs[f]);
                            case DOUBLE -> sink.doubleColumn(names[f], doubles[f]);
                            case BOOLEAN -> sink.booleanColumn(names[f], longs[f] != 0L);
                            case TIMESTAMP -> sink.timestampColumn(names[f], longs[f]);
                        }
                    }
                    sink.at(rowMicros[r]);
                } catch (final CdbException e) {
                    refusal = new Refusal(rowNumbers[r], e);
                    return;
                }
            }
        }
    }

    /** One table's queue, and whether a committer runs for it. */
    private final class Table {

        private final String key;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        // the two below: guarded by lock
        private final ArrayDeque<Batch> queue = new ArrayDeque<>();
        private boolean committing;
        // the requests holding this queue: read and written only inside the map's compute for its key
        private int users;

        Table(final String key) {
            this.key = key;
        }

        int queued() {
            lock.lock();
            try {
                return queue.size();
            } finally {
                lock.unlock();
            }
        }

        // a batch queued after close is refused at once, as a closed store refuses its appender
        void enqueue(final Batch batch, final boolean durable) {
            lock.lock();
            try {
                batch.durable = durable;
                if (closed) {
                    batch.failure = new CdbException("refuse write after close [table=" + batch.rowTables[0] + ']', -1);
                    batch.done = true;
                    return;
                }
                queue.addLast(batch);
            } finally {
                lock.unlock();
            }
        }

        /** Waits until {@code batch} is done, committing it and what else is queued when no committer runs. */
        void await(final Batch batch) {
            boolean interrupted = false;
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            lock.lock();
            try {
                while (!batch.done) {
                    if (!committing) {
                        commitGroup();
                        continue;
                    }
                    final long remaining = batch.taken ? TAKEN_POLL_NANOS : deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        refuse(batch, "refuse write after waiting for its commit [table=" + batch.rowTables[0]
                                + ", waitMillis=" + WAIT_MILLIS + ']');
                        continue;
                    }
                    try {
                        changed.awaitNanos(remaining);
                    } catch (final InterruptedException _) {
                        interrupted = true;
                        if (!batch.taken) {
                            refuse(batch, "refuse write interrupted before its commit [table=" + batch.rowTables[0]
                                    + ']');
                        }
                    }
                }
            } finally {
                lock.unlock();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // called holding lock with no committer running; returns holding it, the group done and its requests woken
        private void commitGroup() {
            final List<Batch> group = new ArrayList<>(queue.size());
            int rows = 0;
            while (!queue.isEmpty() && (group.isEmpty() || rows + queue.peekFirst().rows() <= MAX_GROUP_ROWS)) {
                final Batch next = queue.pollFirst();
                next.taken = true;
                rows += next.rows();
                group.add(next);
            }
            committing = true;
            lock.unlock();
            final long start = System.nanoTime();
            try {
                commit(group);
            } finally {
                lock.lock();
                for (final Batch batch : group) {
                    batch.done = true;
                }
                committing = false;
                changed.signalAll();
            }
            final int groupRows = rows;
            final long tookMicros = (System.nanoTime() - start) / 1_000L;
            LOG.log(Level.DEBUG, () -> "committed rows [table=" + key + ", batches=" + group.size() + ", rows="
                    + groupRows + ", tookMicros=" + tookMicros + ']');
        }

        // runs on the committer without lock: the group's rows into one appender, then one flush; a failed commit
        // fails every batch of the group it did not refuse. The closed appender then goes to the durable batches the
        // commit wrote, whose requests wait for its force once the group is done and the table's writer let go
        private void commit(final List<Batch> group) {
            final CdbAppender flushed;
            try (CdbAppender appender = appenders.get()) {
                replayAll(appender, group);
                appender.flush();
                flushed = appender;
            } catch (final RuntimeException e) {
                LOG.log(Level.WARNING, "could not commit rows [table=" + key + ", batches=" + group.size() + ']', e);
                fail(group, e);
                return;
            } catch (final Error e) {
                fail(group, new IllegalStateException("could not commit rows [table=" + key + ']', e));
                throw e;
            }
            Durability durability = null;
            for (final Batch batch : group) {
                if (batch.durable && batch.refusal == null) {
                    if (durability == null) {
                        durability = new Durability(key, flushed);
                    }
                    batch.durability = durability;
                }
            }
        }

        // the appender can drop only everything written since its last flush, so a refused batch drops the group's
        // rows and the batches not refused are written again, in arrival order, without it
        private static void replayAll(final CdbAppender appender, final List<Batch> group) {
            final List<Batch> live = new ArrayList<>(group);
            int next = 0;
            while (next < live.size()) {
                final Batch batch = live.get(next);
                batch.replay(appender);
                if (batch.refusal == null) {
                    next++;
                    continue;
                }
                appender.discard();
                live.remove(next);
                next = 0;
            }
        }

        private static void fail(final List<Batch> group, final RuntimeException failure) {
            for (final Batch batch : group) {
                if (batch.refusal == null) {
                    batch.failure = failure;
                }
            }
        }

        // called holding lock: the batch leaves the queue unwritten and its request is refused
        private void refuse(final Batch batch, final String message) {
            queue.remove(batch);
            batch.failure = new CdbException(message, -1);
            batch.done = true;
        }
    }
}
