package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.Values;
import dev.marregui.comando.cdb.plan.Plan;

/**
 * LATEST ON … PARTITION BY: per distinct value of the partition columns, the row with the largest timestamp (the later
 * row on a tie), output in timestamp order, the earlier winner first on a tie. Reads the whole input on the first
 * {@link #next}.
 *
 * <p>When the plan says the input arrives newest first (descending timestamp, rows of one timestamp in the reverse of
 * their ascending order), the first row of a key is its answer: it is copied once, a later row of a seen key costs one
 * hash lookup, and the keys, numbered in first-seen order, are emitted last to first. Otherwise the best row of each
 * key in a page is tracked by its offset and copied once, when the page is done (2 M timestamp-ordered rows, 2 keys:
 * 131 ms before, 97 ms after, the client time of a whole query, best of 3; the newest-first path not measured).
 */
final class LatestOnCursor implements Cursor {

    /** The most values of a single text key found by a scan before the hash lookup takes over; a few cache lines. */
    private static final int FEW_KEYS = 16;

    private final Cursor child;
    private final List<Field> fields;
    private final boolean newestFirst;
    private final AggKeys partitions;
    private final int[] partitionColumns;
    private final Values[] rows;
    private final int timestampColumn;
    private long[] bestMicros = new long[16];
    // the offset in the current page of each key's best row, read for the keys in touched
    private int[] bestRow = new int[16];
    private boolean closed;
    private int emitted;
    private int[] order;
    private int pageCount;
    private long[] sequence = new long[16];
    // the touchedCount keys whose best row is in the current page; touchedIn holds the page that last listed each key
    private int[] touched = new int[16];
    private int touchedCount;
    private int[] touchedIn = new int[16];

    LatestOnCursor(final Cursor child, final Plan.LatestOn spec) {
        this.child = child;
        try {
            fields = child.fields();
            timestampColumn = spec.timestampColumn();
            partitionColumns = spec.partitionColumns().clone();
            newestFirst = spec.newestFirst();
            final List<CdbType> keyTypes = new ArrayList<>();
            for (final int column : partitionColumns) {
                keyTypes.add(fields.get(column).type());
            }
            partitions = new AggKeys(keyTypes);
            rows = new Values[fields.size()];
            for (int c = 0; c < rows.length; c++) {
                rows[c] = new Values(fields.get(c).type());
            }
        } catch (final RuntimeException e) {
            child.close();
            throw e;
        }
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        if (order == null) {
            if (newestFirst) {
                readNewestFirst();
            } else {
                readAll();
            }
        }
        if (emitted >= order.length) {
            return null;
        }
        final Page out = Page.allocate(fields);
        int row = 0;
        while (row < Page.CAPACITY && emitted < order.length) {
            final int group = order[emitted++];
            for (int c = 0; c < rows.length; c++) {
                rows[c].write(group, out.columns[c], row);
            }
            row++;
        }
        out.size = row;
        return out;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            child.close();
        }
    }

    private static boolean seen(final String[] values, final int count, final String key) {
        for (int j = 0; j < count; j++) {
            final String v = values[j];
            if (v == key || v != null && v.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private void readAll() {
        final Vector[] keys = new Vector[partitionColumns.length];
        long seen = 0;
        Page page;
        while ((page = child.next()) != null) {
            for (int k = 0; k < keys.length; k++) {
                keys[k] = page.columns[partitionColumns[k]];
            }
            final long[] micros = page.columns[timestampColumn].longs;
            final int stamp = ++pageCount;
            for (int i = 0; i < page.size; i++) {
                final int before = partitions.size();
                final int group = partitions.groupOf(keys, i);
                if (group == before) {
                    grow(group + 1);
                }
                if (group == before || micros[i] >= bestMicros[group]) {
                    bestMicros[group] = micros[i];
                    sequence[group] = seen;
                    bestRow[group] = i;
                    if (touchedIn[group] != stamp) {
                        touchedIn[group] = stamp;
                        touched[touchedCount++] = group;
                    }
                }
                seen++;
            }
            for (int t = 0; t < touchedCount; t++) {
                final int group = touched[t];
                final int best = bestRow[group];
                for (int c = 0; c < rows.length; c++) {
                    rows[c].set(group, page.columns[c], best);
                }
            }
            touchedCount = 0;
        }
        order = AggSort.sorted(partitions.size(), (a, b) -> {
            final int byTime = Long.compare(bestMicros[a], bestMicros[b]);
            return byTime != 0 ? byTime : Long.compare(sequence[a], sequence[b]);
        });
    }

    /**
     * The first row of a key is its latest, and the first-seen order of the keys descends by (timestamp, ascending
     * input order), so its reverse is the order {@link #readAll} sorts the same keys into.
     */
    private void readNewestFirst() {
        final Vector[] keys = new Vector[partitionColumns.length];
        // one text key with few values: a row of a key already seen is skipped by a scan of those values, where the
        // store hands out one String per value, so identity mostly answers; past FEW_KEYS values, the hash lookup
        final boolean oneText = keys.length == 1 && fields.get(partitionColumns[0]).type().isText();
        final String[] few = new String[FEW_KEYS];
        int fewCount = 0;
        Page page;
        while ((page = child.next()) != null) {
            for (int k = 0; k < keys.length; k++) {
                keys[k] = page.columns[partitionColumns[k]];
            }
            for (int i = 0; i < page.size; i++) {
                if (oneText && fewCount <= FEW_KEYS) {
                    final String key = keys[0].texts[i];
                    if (seen(few, fewCount, key)) {
                        continue;
                    }
                    if (fewCount < FEW_KEYS) {
                        few[fewCount] = key;
                    }
                    fewCount++;
                }
                final int group = partitions.size();
                if (partitions.groupOf(keys, i) == group) {
                    for (int c = 0; c < rows.length; c++) {
                        rows[c].ensure(group + 1);
                        rows[c].set(group, page.columns[c], i);
                    }
                }
            }
        }
        final int n = partitions.size();
        order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = n - 1 - i;
        }
    }

    // AggKeys refuses a key table past 2^30 slots, so groups stay below 2^29 and doubling cannot overflow
    private void grow(final int groups) {
        if (groups > bestMicros.length) {
            final int capacity = bestMicros.length * 2;
            bestMicros = Arrays.copyOf(bestMicros, capacity);
            sequence = Arrays.copyOf(sequence, capacity);
            bestRow = Arrays.copyOf(bestRow, capacity);
            touched = Arrays.copyOf(touched, capacity);
            touchedIn = Arrays.copyOf(touchedIn, capacity);
        }
        for (final Values column : rows) {
            column.ensure(groups);
        }
    }
}
