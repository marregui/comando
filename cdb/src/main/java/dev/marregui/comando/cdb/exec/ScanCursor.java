package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.PartitionReader;
import dev.marregui.comando.cdb.store.TableReader;

/**
 * The rows of a table scan: the snapshot's rows with a designated timestamp in {@code [fromMicros, toMicros)}, a page
 * at a time, in time order, or in reverse with {@code newestFirst} (the newest partition first, each from its last row
 * to its first): exactly the time-order scan's rows in reverse, rows with equal timestamps included, terms or not.
 * Each partition's rows in range are found by binary search on the timestamp. With pre-filter terms,
 * each chunk of up to {@link Page#CAPACITY} rows is selected term by term and only the kept rows are read; a row a
 * numeric term keeps for its NULL or NaN may still fail it, so the filter above decides (text terms are exact). Pages are full across partition ends, the last may be shorter.
 * Only the positions {@code reads} marks are read from the store (all when it is null); the others' values in a page are
 * left unset, as nothing above reads them ({@code plan.ColumnUse}).
 * The cursor owns the reader and closes it after the last page, or on {@link #close()}.
 */
final class ScanCursor implements Cursor {

    private final List<Field> fields;
    private final int[] columns;
    private final CdbType[] types;
    /** The positions read from the store, ascending. */
    private final int[] live;
    private final List<PartitionReader> partitions;
    private final long[] starts;
    private final long[] ends;
    private final Plan.ScanTerm[] terms;
    private final boolean newestFirst;
    private final int[] sel;
    private final int[] other;
    private TableReader reader;
    private Page scratch;
    private int partition;
    private long lo;
    private long hi;

    ScanCursor(final Plan.Scan scan, final TableReader reader) {
        this(scan, reader, null);
    }

    ScanCursor(final Plan.Scan scan, final TableReader reader, final boolean[] reads) {
        this.reader = reader;
        try {
            fields = scan.fields();
            columns = scan.columns().clone();
            types = new CdbType[columns.length];
            int liveCount = 0;
            for (int i = 0; i < columns.length; i++) {
                types[i] = fields.get(i).type();
                liveCount += reads == null || reads[i] ? 1 : 0;
            }
            live = new int[liveCount];
            for (int i = 0, k = 0; i < columns.length; i++) {
                if (reads == null || reads[i]) {
                    live[k++] = i;
                }
            }
            terms = scan.terms().toArray(new Plan.ScanTerm[0]);
            newestFirst = scan.newestFirst();
            sel = terms.length == 0 ? null : new int[Page.CAPACITY];
            other = terms.length < 2 ? null : new int[Page.CAPACITY];
            final int ts = reader.def().timestampIndex();
            final long from = scan.fromMicros();
            final long to = scan.toMicros();
            final List<PartitionReader> kept = new ArrayList<>();
            final List<long[]> bounds = new ArrayList<>();
            // Long.MAX_VALUE is the plan's "no upper bound", so a row stamped with it is in range
            final boolean bounded = to != Long.MAX_VALUE;
            for (final PartitionReader p : reader.partitions()) {
                if (p.rowCount() <= 0 || p.maxTimestamp() < from || bounded && p.minTimestamp() >= to) {
                    continue;
                }
                final long start = ts < 0 || p.minTimestamp() >= from ? 0 : p.rowAtOrAfter(ts, from);
                final long end = ts < 0 || !bounded || p.maxTimestamp() < to ? p.rowCount() : p.rowAtOrAfter(ts, to);
                if (start < end) {
                    kept.add(p);
                    bounds.add(new long[] {start, end});
                }
            }
            partitions = newestFirst ? kept.reversed() : kept;
            final List<long[]> ordered = newestFirst ? bounds.reversed() : bounds;
            starts = new long[ordered.size()];
            ends = new long[ordered.size()];
            for (int i = 0; i < starts.length; i++) {
                starts[i] = ordered.get(i)[0];
                ends[i] = ordered.get(i)[1];
            }
            enter();
        } catch (final RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        if (reader == null) {
            return null;
        }
        Page out = null;
        while (partition < starts.length) {
            if (lo >= hi) {
                partition++;
                enter();
                continue;
            }
            if (out == null) {
                out = Page.allocate(fields);
            }
            final PartitionReader p = partitions.get(partition);
            final Page dst = out.size == 0 ? out : scratch();
            final int room = Page.CAPACITY - out.size;
            final int n = terms.length == 0 ? readAll(p, room, dst) : readSelected(p, room, dst);
            if (dst != out) {
                for (final int c : live) {
                    Rows.copyRange(dst.columns[c], 0, out.columns[c], out.size, n);
                }
            }
            if (newestFirst) {
                for (final int c : live) {
                    reverse(out.columns[c], out.size, n);
                }
            }
            out.size += n;
            if (out.size == Page.CAPACITY) {
                return out;
            }
        }
        close();
        return out == null || out.size == 0 ? null : out;
    }

    @Override
    public void close() {
        final TableReader r = reader;
        reader = null;
        scratch = null;
        if (r != null) {
            r.close();
        }
    }

    /** The row bounds of the current partition, when there is one. */
    private void enter() {
        if (partition < starts.length) {
            lo = starts[partition];
            hi = ends[partition];
        }
    }

    private Page scratch() {
        if (scratch == null) {
            scratch = Page.allocate(fields);
        }
        return scratch;
    }

    /** Up to {@code room} rows from the end the scan reads from, every one of them, into {@code dst} from 0. */
    private int readAll(final PartitionReader p, final int room, final Page dst) {
        final int n = (int) Math.min(room, hi - lo);
        final long from = newestFirst ? hi - n : lo;
        read(p, from, n, dst);
        if (newestFirst) {
            hi -= n;
        } else {
            lo += n;
        }
        return n;
    }

    /**
     * The rows of the next chunk that every term may keep, at most {@code room} of them, into {@code dst} from 0; the
     * rows of the chunk past the last one taken stay for the next call.
     */
    private int readSelected(final PartitionReader p, final int room, final Page dst) {
        final int n = (int) Math.min(Page.CAPACITY, hi - lo);
        final long from = newestFirst ? hi - n : lo;
        final int kept = select(p, from, n);
        final int taken = Math.min(kept, room);
        if (kept == taken) {
            if (newestFirst) {
                hi = from;
            } else {
                lo = from + n;
            }
        } else if (newestFirst) {
            final int skip = kept - taken;
            hi = from + sel[skip - 1] + 1;
            System.arraycopy(sel, skip, sel, 0, taken);
        } else {
            lo = from + sel[taken - 1] + 1;
        }
        if (taken == n) {
            read(p, from, n, dst);
        } else if (taken > 0) {
            gather(p, from, taken, dst);
        }
        return taken;
    }

    /** The offsets from {@code from} that every term keeps, ascending, in {@link #sel}; returns their count. */
    private int select(final PartitionReader p, final long from, final int n) {
        int count = selectTerm(p, terms[0], from, n, sel);
        for (int t = 1; t < terms.length && count > 0; t++) {
            final int more = selectTerm(p, terms[t], from, n, other);
            count = intersect(sel, count, other, more);
        }
        return count;
    }

    private int selectTerm(
            final PartitionReader p,
            final Plan.ScanTerm term,
            final long from,
            final int n,
            final int[] dst
    ) {
        final int column = columns[term.column()];
        return switch (types[term.column()]) {
            case LONG, TIMESTAMP -> p.selectLong(column, from, n, term.op(), term.longValue(), dst);
            case DOUBLE -> p.selectDouble(column, from, n, term.op(), term.doubleValue(), dst);
            case SYMBOL, VARCHAR -> p.selectText(column, from, n, term.op(), term.textValue(), dst);
            case BOOLEAN -> {
                for (int i = 0; i < n; i++) {
                    dst[i] = i;
                }
                yield n;
            }
        };
    }

    /** The offsets in both ascending lists, written over {@code a}; returns their count. */
    private static int intersect(final int[] a, final int aCount, final int[] b, final int bCount) {
        int i = 0;
        int j = 0;
        int out = 0;
        while (i < aCount && j < bCount) {
            if (a[i] < b[j]) {
                i++;
            } else if (a[i] > b[j]) {
                j++;
            } else {
                a[out++] = a[i];
                i++;
                j++;
            }
        }
        return out;
    }

    private void read(final PartitionReader p, final long from, final int n, final Page dst) {
        for (final int c : live) {
            final Vector v = dst.columns[c];
            switch (types[c]) {
                case LONG, TIMESTAMP -> p.longs(columns[c], from, n, v.longs);
                case DOUBLE -> p.doubles(columns[c], from, n, v.doubles);
                case BOOLEAN -> p.booleans(columns[c], from, n, v.booleans);
                case SYMBOL, VARCHAR -> p.texts(columns[c], from, n, v.texts);
            }
        }
    }

    private void gather(final PartitionReader p, final long from, final int count, final Page dst) {
        for (final int c : live) {
            final Vector v = dst.columns[c];
            switch (types[c]) {
                case LONG, TIMESTAMP -> p.longsAt(columns[c], from, sel, count, v.longs);
                case DOUBLE -> p.doublesAt(columns[c], from, sel, count, v.doubles);
                case BOOLEAN -> p.booleansAt(columns[c], from, sel, count, v.booleans);
                case SYMBOL, VARCHAR -> p.textsAt(columns[c], from, sel, count, v.texts);
            }
        }
    }

    /** Values {@code [at, at + n)} of {@code v} in reverse order. */
    private static void reverse(final Vector v, final int at, final int n) {
        for (int i = at, j = at + n - 1; i < j; i++, j--) {
            switch (v.type) {
                case LONG, TIMESTAMP -> {
                    final long t = v.longs[i];
                    v.longs[i] = v.longs[j];
                    v.longs[j] = t;
                }
                case DOUBLE -> {
                    final double t = v.doubles[i];
                    v.doubles[i] = v.doubles[j];
                    v.doubles[j] = t;
                }
                case BOOLEAN -> {
                    final boolean t = v.booleans[i];
                    v.booleans[i] = v.booleans[j];
                    v.booleans[j] = t;
                }
                case SYMBOL, VARCHAR -> {
                    final String t = v.texts[i];
                    v.texts[i] = v.texts[j];
                    v.texts[j] = t;
                }
            }
        }
    }
}
