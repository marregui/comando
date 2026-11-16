package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.ColumnUse;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.PartitionReader;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableReader;

/**
 * ORDER BY with a small limit over a scan, filtered or not, read late: the scan reads only the columns the filter and
 * the sort keys need, plus each row's position ({@link ScanCursor#POSITION}); the sort keeps its first limit rows, and
 * only then are the other columns read, for those rows alone ({@link PartitionReader#fetch}). The sort compares the
 * keys alone and is stable in scan order, its frames merged in frame order, so the rows it keeps and their order, ties
 * included, are the eager plan's. The cursor owns the table reader its frames borrow and closes it after the sort.
 */
final class LateReadCursor implements Cursor {

    /**
     * The largest limit read late: the kept rows' key columns and positions are held at once, so the bound keeps that
     * small while covering the LIMITs a console asks for (set with the late read, not measured).
     */
    static final long MAX_LIMIT = 10_000;
    // a position is its partition's index in the snapshot above the row's 40 bits
    private static final int ROW_BITS = 40;
    private static final long ROW_MASK = (1L << ROW_BITS) - 1;

    private final List<Field> fields;
    private final int[] columns;
    // per output position: copied from the sort's page, or fetched here for the kept rows
    private final boolean[] early;
    private final boolean[] late;
    private final int positionColumn;
    private final long[] rows = new long[Page.CAPACITY];
    private final List<PartitionReader> partitions;
    private Cursor sorted;
    private TableReader reader;

    private LateReadCursor(
            final Plan.Scan scan,
            final boolean[] full,
            final boolean[] narrow,
            final Cursor sorted,
            final TableReader reader
    ) {
        fields = scan.fields();
        columns = scan.columns();
        early = narrow;
        late = new boolean[columns.length];
        for (int c = 0; c < columns.length; c++) {
            late[c] = (full == null || full[c]) && !narrow[c];
        }
        positionColumn = columns.length;
        partitions = reader.partitions();
        this.sorted = sorted;
        this.reader = reader;
    }

    /** True when {@code sort} is read late: a limit up to {@link #MAX_LIMIT} over a scan with a column to defer. */
    static boolean applies(final Plan.Sort sort, final Map<Plan.Scan, boolean[]> reads) {
        if (sort.limit() < 0 || sort.limit() > MAX_LIMIT || sort.keys().isEmpty()) {
            return false;
        }
        final Plan.Scan scan = scanOf(sort.child());
        if (scan == null) {
            return false;
        }
        final boolean[] full = reads.get(scan);
        final boolean[] narrow = narrowReads(sort, scan);
        for (int c = 0; c < narrow.length; c++) {
            if ((full == null || full[c]) && !narrow[c]) {
                return true;
            }
        }
        return false;
    }

    /** The cursor of {@code sort}, for which {@link #applies} holds, over a snapshot of its table in {@code store}. */
    static Cursor open(
            final Plan.Sort sort,
            final Store store,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads,
            final QueryContext ctx
    ) {
        return open(sort, store.reader(scanOf(sort.child()).table()), nowMicros, reads, ctx);
    }

    /**
     * The cursor of {@code sort}, for which {@link #applies} holds, over a snapshot of {@code reader}, which it owns
     * from here; {@code reads} are the eager plan's.
     */
    static Cursor open(
            final Plan.Sort sort,
            final TableReader reader,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads,
            final QueryContext ctx
    ) {
        try {
            final Plan.Scan scan = scanOf(sort.child());
            final boolean[] narrow = narrowReads(sort, scan);
            final int n = scan.columns().length;
            final int[] columns = Arrays.copyOf(scan.columns(), n + 1);
            columns[n] = ScanCursor.POSITION;
            final List<Field> fields = new ArrayList<>(scan.fields());
            fields.add(new Field("position", CdbType.LONG));
            final Plan.Scan narrowScan = new Plan.Scan(scan.table(), columns, scan.fromMicros(), scan.toMicros(),
                    scan.terms(), scan.newestFirst(), List.copyOf(fields));
            final Plan child = sort.child() instanceof Plan.Filter(final Plan _, final Bound predicate)
                    ? new Plan.Filter(narrowScan, predicate)
                    : narrowScan;
            final boolean[] withPosition = Arrays.copyOf(narrow, n + 1);
            withPosition[n] = true;
            final Map<Plan.Scan, boolean[]> narrowReads = new IdentityHashMap<>();
            narrowReads.put(narrowScan, withPosition);
            final FrameSource source = FrameSource.borrowing(child, reader, nowMicros, narrowReads, ctx);
            final Cursor sorted;
            try {
                sorted = SortCursor.parallel(source, sort.keys(), sort.limit());
            } catch (final RuntimeException | Error e) {
                source.close();
                throw e;
            }
            try {
                return new LateReadCursor(scan, reads.get(scan), narrow, sorted, reader);
            } catch (final RuntimeException | Error e) {
                try {
                    sorted.close();
                } catch (final RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        } catch (final RuntimeException | Error e) {
            try {
                reader.close();
            } catch (final RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /** The position of {@code row} of the snapshot's partition {@code partition}, as {@link ScanCursor} emits it. */
    static long position(final int partition, final long row) {
        return (long) partition << ROW_BITS | row;
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        if (sorted == null) {
            return null;
        }
        final Page in = sorted.next();
        if (in == null) {
            close();
            return null;
        }
        final Page out = Page.allocate(fields);
        for (int c = 0; c < columns.length; c++) {
            if (early[c]) {
                out.columns[c].copy(in.columns[c], 0, 0, in.size);
            }
        }
        final Vector positions = in.columns[positionColumn];
        int i = 0;
        while (i < in.size) {
            final long partition = positions.getLong(i) >>> ROW_BITS;
            int j = i;
            while (j < in.size) {
                final long position = positions.getLong(j);
                if (position >>> ROW_BITS != partition) {
                    break;
                }
                rows[j++] = position & ROW_MASK;
            }
            final PartitionReader p = partitions.get((int) partition);
            for (int c = 0; c < columns.length; c++) {
                if (late[c]) {
                    p.fetch(columns[c], rows, i, j - i, out.columns[c], i);
                }
            }
            i = j;
        }
        out.size = in.size;
        return out;
    }

    /** Closes the sort, then the reader; idempotent. */
    @Override
    public void close() {
        final Cursor s = sorted;
        final TableReader r = reader;
        sorted = null;
        reader = null;
        RuntimeException failure = null;
        if (s != null) {
            try {
                s.close();
            } catch (final RuntimeException e) {
                failure = e;
            }
        }
        if (r != null) {
            try {
                r.close();
            } catch (final RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** The scan under {@code plan} when it is a scan or a row-local filter over one, else null. */
    private static Plan.Scan scanOf(final Plan plan) {
        if (plan instanceof Plan.Scan s) {
            return s;
        }
        if (plan instanceof Plan.Filter(final Plan child, final Bound predicate) && child instanceof Plan.Scan s
                && Functions.rowLocal(predicate)) {
            return s;
        }
        return null;
    }

    /** The scan's positions the filter above it and the sort keys read. */
    private static boolean[] narrowReads(final Plan.Sort sort, final Plan.Scan scan) {
        final boolean[] filterReads = ColumnUse.of(new Plan.Project(sort.child(), List.of(), List.of())).get(scan);
        final boolean[] narrow = filterReads == null ? new boolean[scan.columns().length] : filterReads.clone();
        for (final Plan.SortKey key : sort.keys()) {
            narrow[key.column()] = true;
        }
        return narrow;
    }
}
