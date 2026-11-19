package dev.marregui.comando.cdb.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The store's line-protocol appender. A row's fields are buffered until {@link #at}, so a row is either written whole
 * or refused whole; the appender keeps each touched table's writer until {@link #close}. A table whose writer the store
 * did not hand over refuses the appender's later rows of it at once, with the same reason, rather than waiting again;
 * a table dropped before its writer is taken is not refused but created again, as for a line to a missing table.
 * {@link #flush} commits each touched table in turn, not atomically across tables: a failed commit leaves the tables
 * before it committed and the ones from it on rolled back. An {@link Error} in a flush closes the appender, its writers
 * released. {@link #awaitDurable} waits until every commit flushed since the last wait that held is durable, those of
 * the flush in {@link #close} included, so it may be called after the close.
 */
final class IlpAppender implements CdbAppender {

    // the designated timestamp of a table the appender creates, and its partitioning, as QuestDB's ILP creates one
    private static final String TIMESTAMP = "timestamp";
    // how long the appender waits for a table's writer held by another
    private static final long WRITER_WAIT_MILLIS = 30_000L;
    // how many drops a line outlasts between creating its table and taking the writer: each must land in that short
    // window, so a few cover racing drops, and more means a loop of drops the line gives way to
    private static final int WRITER_ATTEMPTS = 8;
    private static final int INITIAL_FIELDS = 16;
    // 10000-01-01T00:00:00Z in microseconds since the epoch: QuestDB's ILP refuses a designated timestamp from it on,
    // and one before the epoch (LineUtils.fromDesignatedTimestamp, MicrosTimestampDriver.validateBounds)
    private static final long YEAR_10000_MICROS = 253_402_300_800_000_000L;

    private final Store store;
    private final long writerWaitMillis;
    private final int writerAttempts;
    private final Map<String, TableWriter> writers = new LinkedHashMap<>();
    // a table's writer the store refused, by table key: a group commit's later rows of it would each wait as long
    private final Map<String, CdbException> unavailable = new HashMap<>();
    private final List<TableWriter> touched = new ArrayList<>();
    // by table key, the durability of the last commit not done at its answer of each table flushed since the last wait
    // that held: a later commit's force covers the earlier ones of its log
    private final Map<String, ForceTicket> unforced = new LinkedHashMap<>();
    private String table;
    private int fields;
    private String[] names = new String[INITIAL_FIELDS];
    private CdbType[] types = new CdbType[INITIAL_FIELDS];
    private long[] longs = new long[INITIAL_FIELDS];
    private double[] doubles = new double[INITIAL_FIELDS];
    private String[] texts = new String[INITIAL_FIELDS];
    private boolean closed;

    IlpAppender(final Store store) {
        this(store, WRITER_WAIT_MILLIS);
    }

    /** As above, waiting at most {@code writerWaitMillis} for a table's writer held by another. */
    IlpAppender(final Store store, final long writerWaitMillis) {
        this(store, writerWaitMillis, WRITER_ATTEMPTS);
    }

    /** As above, refusing a line once its table was dropped before each of {@code writerAttempts} writers. */
    @TestOnly
    IlpAppender(final Store store, final long writerWaitMillis, final int writerAttempts) {
        this.store = store;
        this.writerWaitMillis = writerWaitMillis;
        this.writerAttempts = writerAttempts;
    }

    @Override
    public CdbAppender table(final String name) {
        checkOpen();
        TableFiles.checkName(name, "table");
        table = name;
        fields = 0;
        return this;
    }

    @Override
    public CdbAppender symbol(final String column, final String value) {
        add(column, CdbType.SYMBOL);
        texts[fields - 1] = value;
        return this;
    }

    @Override
    public CdbAppender stringColumn(final String column, final String value) {
        add(column, CdbType.VARCHAR);
        texts[fields - 1] = value;
        return this;
    }

    @Override
    public CdbAppender longColumn(final String column, final long value) {
        add(column, CdbType.LONG);
        longs[fields - 1] = value;
        return this;
    }

    @Override
    public CdbAppender doubleColumn(final String column, final double value) {
        add(column, CdbType.DOUBLE);
        doubles[fields - 1] = value;
        return this;
    }

    @Override
    public CdbAppender booleanColumn(final String column, final boolean value) {
        add(column, CdbType.BOOLEAN);
        longs[fields - 1] = value ? 1L : 0L;
        return this;
    }

    @Override
    public CdbAppender timestampColumn(final String column, final long micros) {
        add(column, CdbType.TIMESTAMP);
        longs[fields - 1] = micros;
        return this;
    }

    @Override
    public void at(final long micros) {
        checkOpen();
        if (table == null) {
            throw new CdbException("refuse row without table", -1);
        }
        final String name = table;
        table = null;
        if (micros < 0L || micros >= YEAR_10000_MICROS) {
            fields = 0;
            throw new CdbException("refuse designated timestamp out of range [table=" + name + ", micros=" + micros
                    + ", min=0, max=" + (YEAR_10000_MICROS - 1L) + ']', -1);
        }
        final TableWriter writer = writer(name);
        final int[] columns = new int[fields];
        for (int f = 0; f < fields; f++) {
            columns[f] = column(writer, f);
        }
        writer.newRow(micros);
        for (int f = 0; f < fields; f++) {
            final int c = columns[f];
            switch (writer.def().columns().get(c).type()) {
                case LONG, TIMESTAMP -> writer.putLong(c, longs[f]);
                case DOUBLE -> writer.putDouble(c, types[f] == CdbType.DOUBLE ? doubles[f] : (double) longs[f]);
                case BOOLEAN -> writer.putBoolean(c, longs[f] != 0L);
                case SYMBOL, VARCHAR -> writer.putText(c, texts[f]);
            }
        }
        writer.endRow();
        fields = 0;
        if (!touched.contains(writer)) {
            touched.add(writer);
        }
    }

    @Override
    public void flush() {
        checkOpen();
        int committed = 0;
        try {
            for (final TableWriter writer : touched) {
                writer.commit();
                committed++;
                final ForceTicket ticket = writer.ticket();
                // a done ticket says nothing of the table's earlier commits: their ticket stays until a wait sees a
                // force or a cut of the log cover it; a later ticket replaces it, its record after theirs in one log
                if (ticket != ForceTicket.DONE) {
                    unforced.put(TableState.key(writer.def().name()), ticket);
                }
            }
        } catch (final RuntimeException e) {
            // the failed table's rows and those of the tables after it would otherwise ride on a later flush
            rollBack(touched.subList(committed, touched.size()), e);
            throw e;
        } catch (final Error e) {
            release(e);
            throw e;
        } finally {
            touched.clear();
        }
    }

    /** A ticket that held is dropped; on a timeout or a failure the rest are kept for the next wait. */
    @Override
    public boolean awaitDurable(final long timeoutMillis) throws InterruptedException {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        final Iterator<ForceTicket> tickets = unforced.values().iterator();
        while (tickets.hasNext()) {
            final long startNanos = System.nanoTime();
            if (!tickets.next().await(remainingNanos)) {
                return false;
            }
            tickets.remove();
            remainingNanos = Math.max(0L, remainingNanos - (System.nanoTime() - startNanos));
        }
        return true;
    }

    /** Rolls back every writer the appender holds and keeps them: a column only a refused row added goes too. */
    @Override
    public void discard() {
        checkOpen();
        table = null;
        fields = 0;
        touched.clear();
        final RuntimeException failure = rollBack(writers.values(), null);
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        Throwable failure = null;
        try {
            flush();
        } catch (final RuntimeException | Error e) {
            failure = e;
        }
        failure = release(failure);
        if (failure instanceof final Error e) {
            throw e;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }

    // rolls back each of writers; returns primary, or a failure of its own when null, with each rollback failure added
    private static RuntimeException rollBack(final Collection<TableWriter> writers, final RuntimeException primary) {
        RuntimeException failure = primary;
        for (final TableWriter writer : writers) {
            try {
                writer.rollback();
            } catch (final RuntimeException e) {
                if (failure == null) {
                    failure = new CdbException("cannot roll back appender's writers", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
    }

    // closes the appender and every writer it holds; returns primary, or a failure of its own when null, with each
    // close failure added
    private Throwable release(final Throwable primary) {
        closed = true;
        touched.clear();
        final List<TableWriter> open = new ArrayList<>(writers.values());
        writers.clear();
        Throwable failure = primary;
        for (final TableWriter writer : open) {
            try {
                writer.close();
            } catch (final RuntimeException e) {
                if (failure == null) {
                    failure = new CdbException("cannot close appender's writers", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
    }

    private void add(final String column, final CdbType type) {
        checkOpen();
        if (table == null) {
            throw new CdbException("refuse field before table [column=" + column + ']', -1);
        }
        TableFiles.checkName(column, "column");
        if (fields == names.length) {
            final int grown = fields * 2;
            names = Arrays.copyOf(names, grown);
            types = Arrays.copyOf(types, grown);
            longs = Arrays.copyOf(longs, grown);
            doubles = Arrays.copyOf(doubles, grown);
            texts = Arrays.copyOf(texts, grown);
        }
        names[fields] = column;
        types[fields] = type;
        fields++;
    }

    private TableWriter writer(final String name) {
        final String key = TableState.key(name);
        final TableWriter open = writers.get(key);
        if (open != null) {
            return open;
        }
        final CdbException refused = unavailable.get(key);
        if (refused != null) {
            throw new CdbException(refused.getMessage(), refused);
        }
        // a drop between the create and the writer leaves nothing to write to: the line creates the table again
        for (int attempt = 1; ; attempt++) {
            if (store.def(name) == null) {
                create(name);
            }
            final TableWriter writer;
            try {
                writer = store.writerUnlessDropped(name, writerWaitMillis);
            } catch (final CdbException e) {
                unavailable.put(key, e);
                throw e;
            }
            if (writer != null) {
                writers.put(key, writer);
                return writer;
            }
            if (attempt == writerAttempts) {
                throw new CdbException("refuse table dropped before each writer [table=" + name + ", attempts="
                        + attempt + ']', -1);
            }
        }
    }

    // the table as QuestDB's ILP creates one: the line's fields, then the designated timestamp
    private void create(final String name) {
        final List<ColumnDef> columns = new ArrayList<>(fields + 1);
        for (int f = 0; f < fields; f++) {
            if (indexOf(columns, names[f]) < 0) {
                columns.add(new ColumnDef(names[f], types[f]));
            }
        }
        if (indexOf(columns, TIMESTAMP) < 0) {
            columns.add(new ColumnDef(TIMESTAMP, CdbType.TIMESTAMP));
        }
        final int ts = indexOf(columns, TIMESTAMP);
        if (columns.get(ts).type() != CdbType.TIMESTAMP) {
            throw new CdbException("refuse field named as the designated timestamp [table=" + name + ", column="
                    + TIMESTAMP + ", type=" + columns.get(ts).type() + ']', -1);
        }
        store.create(new TableDef(name, columns, ts, PartitionBy.DAY, List.of(), 0L), true);
    }

    /** The column of field {@code f}, added to the table when missing; a field of a type it cannot take fails. */
    private int column(final TableWriter writer, final int f) {
        final int c = writer.def().columnIndex(names[f]);
        if (c < 0) {
            return writer.addColumn(new ColumnDef(names[f], types[f]));
        }
        final CdbType column = writer.def().columns().get(c).type();
        final CdbType field = types[f];
        // QuestDB's LineWalAppender casts an integer into TIMESTAMP as its microseconds and a boolean into LONG or
        // DOUBLE as 1 or 0, the designated timestamp aside; a boolean field holds 1 or 0 in longs already
        final boolean fits = column == field
                || column == CdbType.DOUBLE && (field == CdbType.LONG || field == CdbType.BOOLEAN)
                || column == CdbType.LONG && field == CdbType.BOOLEAN
                || column == CdbType.TIMESTAMP && field == CdbType.LONG && c != writer.def().timestampIndex()
                || column == CdbType.VARCHAR && field == CdbType.SYMBOL
                || column == CdbType.SYMBOL && field == CdbType.VARCHAR;
        if (!fits) {
            throw new CdbException("refuse field of another type than its column [table=" + writer.def().name()
                    + ", column=" + names[f] + ", columnType=" + column + ", fieldType=" + field + ']', -1);
        }
        return c;
    }

    private static int indexOf(final List<ColumnDef> columns, final String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private void checkOpen() {
        if (closed) {
            throw new CdbException("refuse use of closed appender", -1);
        }
    }
}
