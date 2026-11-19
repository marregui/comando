package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;

/**
 * SAMPLE BY: the bucket's start, the keys, then the aggregates, in ascending bucket order, the keys of a bucket in
 * first-seen order. With a FILL, every bucket from the first to the last with data holds every key, in first-seen
 * order over the whole input, an empty one filled per aggregate: {@code NULL}, {@code PREV} (the key's last value),
 * {@code LINEAR} (QuestDB's: along the key's neighbours by bucket start time, beyond its first or last along the
 * nearest two; in double, then truncated for a whole-number type) or a number.
 * Rows with a NULL timestamp are dropped. Reads the whole input on the first {@link #next}.
 */
final class SampleByCursor implements Cursor {

    // a FILL with a stride far finer than the data's span emits one row per bucket and key; this bounds the memory of
    // a mistyped stride, at about 16 bytes of bookkeeping per output row
    private static final long MAX_FILL_ROWS = 10_000_000L;

    // the input of the serial operator, null over frames
    private final Cursor child;
    private final long count;
    private final List<Field> fields;
    // the fill per aggregate; null when the query has no FILL (or FILL(NONE))
    private final Fill[] fill;
    private final double[] fillValues;
    private final AggKeys keyTable;
    private final long nowMicros;
    // the serial operator's; over frames each frame has its own
    private final Accumulation serial;
    // the input over frames, null for the serial operator
    private final FrameSource source;
    private final Plan.SampleBy spec;
    // the bucket length of a fixed stride, 0 for a calendar one
    private final long strideMicros;
    private final AggTable table;
    // the bucket's start, then the keys
    private final List<CdbType> tableTypes;
    private final int timestampColumn;
    private final String unit;
    private long[] bucketStarts;
    private int[] cells;
    private boolean closed;
    private int emitted;
    private int keys;
    // per LINEAR aggregate, the one row a neighbour's value is read into; null for any other fill
    private Vector[] linearValue;
    private int[] nextCell;
    private int[] order;
    private int[] prevCell;
    private int rows = -1;

    SampleByCursor(final Cursor child, final Plan.SampleBy spec, final long nowMicros) {
        this(child, null, spec, nowMicros);
    }

    // exactly one of child and source is not null; a failure closes it
    private SampleByCursor(
            final Cursor child,
            final FrameSource source,
            final Plan.SampleBy spec,
            final long nowMicros
    ) {
        this.child = child;
        this.source = source;
        this.spec = spec;
        this.nowMicros = nowMicros;
        try {
            fields = spec.fields();
            count = spec.count();
            unit = spec.unit();
            timestampColumn = spec.timestampColumn();
            strideMicros = AggBuckets.fixedStride(count, unit);
            final List<CdbType> keyTypes = new ArrayList<>();
            tableTypes = new ArrayList<>();
            tableTypes.add(CdbType.TIMESTAMP);
            for (final Bound key : spec.keys()) {
                keyTypes.add(key.type());
                tableTypes.add(key.type());
            }
            keyTable = new AggKeys(keyTypes);
            table = new AggTable(tableTypes, spec.aggs(), nowMicros);
            serial = new Accumulation(table);
            fillValues = new double[spec.aggs().size()];
            fill = parseFill(spec.fill(), fillValues);
        } catch (final RuntimeException e) {
            closeInput();
            throw e;
        }
    }

    /**
     * SAMPLE BY over {@code source}'s frames: a partial bucket and key table per frame, merged in frame order, then
     * FILL. The answer is the serial operator's, the same bits whatever the pool's size. Owns and closes the source.
     */
    static Cursor parallel(final FrameSource source, final Plan.SampleBy spec, final long nowMicros) {
        return new SampleByCursor(null, source, spec, nowMicros);
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        if (rows < 0) {
            readAll();
        }
        if (emitted >= rows) {
            return null;
        }
        final Page out = Page.allocate(fields);
        int row = 0;
        while (row < Page.CAPACITY && emitted < rows) {
            if (fill == null) {
                writeGroup(order[emitted], out, row);
            } else {
                writeCell(emitted, out, row);
            }
            row++;
            emitted++;
        }
        out.size = row;
        return out;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeInput();
        }
    }

    private static Fill[] parseFill(final List<String> written, final double[] values) {
        if (written.isEmpty() || written.size() == 1 && "NONE".equalsIgnoreCase(written.getFirst())) {
            return null;
        }
        if (written.size() != 1 && written.size() != values.length) {
            throw new CdbException("cannot match FILL values to aggregates [fills=" + written.size() + ", aggregates="
                    + values.length + ']', -1);
        }
        final Fill[] fill = new Fill[values.length];
        for (int a = 0; a < fill.length; a++) {
            final String text = written.size() == 1 ? written.getFirst() : written.get(a);
            fill[a] = switch (text.toUpperCase(Locale.ROOT)) {
                case "NULL" -> Fill.NULL;
                case "PREV" -> Fill.PREV;
                case "LINEAR" -> Fill.LINEAR;
                case "NONE" -> throw new CdbException("cannot mix FILL(NONE) with other fills [fill=" + written + ']',
                        -1);
                default -> {
                    try {
                        values[a] = Double.parseDouble(text);
                    } catch (final NumberFormatException e) {
                        throw new CdbException("cannot fill with this value [fill=" + text + ']', e);
                    }
                    yield Fill.VALUE;
                }
            };
        }
        return fill;
    }

    private static void writeNumber(final double value, final Vector out, final int row) {
        switch (out.type) {
            case LONG, TIMESTAMP -> out.setLong(row, (long) value);
            case DOUBLE -> out.setDouble(row, value);
            default -> out.setNull(row);
        }
    }

    private void closeInput() {
        if (source != null) {
            source.close();
        } else {
            child.close();
        }
    }

    // a frame's partial, on a pool worker or the consuming thread: its accumulation and table are its own
    private AggTable partial(final int frame) {
        final Accumulation partial = new Accumulation(new AggTable(tableTypes, spec.aggs(), nowMicros));
        try (Cursor rows = source.open(frame)) {
            Page page;
            while ((page = rows.borrow()) != null) {
                source.context().check();
                partial.add(page);
            }
        }
        return partial.table;
    }

    private void readAll() {
        if (source == null) {
            Page page;
            while ((page = child.borrow()) != null) {
                serial.add(page);
            }
        } else {
            // frame order, never completion order: the groups' order and every sum's rounding follow the frames
            try (final Job<AggTable> job = source.submit(this::partial)) {
                for (int frame = 0; frame < job.frames(); frame++) {
                    final AggTable partial = job.await(frame);
                    if (partial.groups() > 0) {
                        table.merge(partial, source.context());
                    }
                }
            }
        }
        final int groups = table.groups();
        final long[] groupBucket = new long[groups];
        final Vector one = Vector.of(CdbType.TIMESTAMP, 1);
        int withBucket = 0;
        for (int g = 0; g < groups; g++) {
            table.writeKey(g, 0, one, 0);
            groupBucket[g] = one.getLong(0);
            if (groupBucket[g] != Vector.NULL_LONG) {
                withBucket++;
            }
        }
        if (fill == null) {
            final int[] sorted = AggSort.sorted(groups, (a, b) -> Long.compare(groupBucket[a], groupBucket[b]));
            // NULL_LONG sorts first: the groups of NULL timestamps lead and are skipped
            order = Arrays.copyOfRange(sorted, groups - withBucket, groups);
            rows = order.length;
        } else {
            layOutFill(groups, groupBucket, withBucket);
        }
    }

    private void layOutFill(final int groups, final long[] groupBucket, final int withBucket) {
        // the keys numbered in first-seen order over the rows with a bucket: a key's first row opens its first group,
        // so walking the groups in order numbers the keys as the rows would, from the table alone
        final int[] keyOfGroup = new int[groups];
        final Vector[] keyRow = new Vector[tableTypes.size() - 1];
        for (int k = 0; k < keyRow.length; k++) {
            keyRow[k] = Vector.of(tableTypes.get(k + 1), 1);
        }
        for (int g = 0; g < groups; g++) {
            if (groupBucket[g] != Vector.NULL_LONG) {
                for (int k = 0; k < keyRow.length; k++) {
                    table.writeKey(g, k + 1, keyRow[k], 0);
                }
                keyOfGroup[g] = keyTable.groupOf(keyRow, 0);
            }
        }
        keys = keyTable.size();
        if (withBucket == 0) {
            rows = 0;
            return;
        }
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (int g = 0; g < groups; g++) {
            if (groupBucket[g] != Vector.NULL_LONG) {
                first = Math.min(first, groupBucket[g]);
                last = Math.max(last, groupBucket[g]);
            }
        }
        int bucketCount = 0;
        for (long b = first; b <= last; b = AggBuckets.next(b, count, unit)) {
            bucketCount++;
            if ((long) bucketCount * keys > MAX_FILL_ROWS) {
                throw new CdbException("cannot fill this many buckets [limit=" + MAX_FILL_ROWS + ", count=" + count
                        + ", unit=" + unit + ']', -1);
            }
        }
        bucketStarts = new long[bucketCount];
        long start = first;
        for (int j = 0; j < bucketCount; j++) {
            bucketStarts[j] = start;
            start = AggBuckets.next(start, count, unit);
        }
        rows = bucketStarts.length * keys;
        cells = new int[rows];
        Arrays.fill(cells, -1);
        for (int g = 0; g < groups; g++) {
            if (groupBucket[g] != Vector.NULL_LONG) {
                final int j = Arrays.binarySearch(bucketStarts, groupBucket[g]);
                cells[j * keys + keyOfGroup[g]] = g;
            }
        }
        prevCell = new int[rows];
        nextCell = new int[rows];
        for (int k = 0; k < keys; k++) {
            int prev = -1;
            for (int j = 0; j < bucketStarts.length; j++) {
                final int cell = j * keys + k;
                prevCell[cell] = prev;
                if (cells[cell] >= 0) {
                    prev = cell;
                }
            }
            int next = -1;
            for (int j = bucketStarts.length - 1; j >= 0; j--) {
                final int cell = j * keys + k;
                nextCell[cell] = next;
                if (cells[cell] >= 0) {
                    next = cell;
                }
            }
        }
        linearValue = new Vector[fill.length];
        for (int a = 0; a < fill.length; a++) {
            if (fill[a] == Fill.LINEAR) {
                linearValue[a] = Vector.of(fields.get(tableTypes.size() + a).type(), 1);
            }
        }
    }

    private void writeCell(final int cell, final Page out, final int row) {
        final int bucket = cell / keys;
        final int key = cell % keys;
        out.columns[0].setLong(row, bucketStarts[bucket]);
        final int keyColumns = tableTypes.size() - 1;
        for (int k = 0; k < keyColumns; k++) {
            keyTable.write(key, k, out.columns[k + 1], row);
        }
        final int group = cells[cell];
        final int offset = keyColumns + 1;
        for (int a = 0; a < fill.length; a++) {
            final Vector column = out.columns[offset + a];
            if (group >= 0) {
                table.writeAggregate(group, a, column, row);
                continue;
            }
            switch (fill[a]) {
                case NULL -> column.setNull(row);
                case VALUE -> writeNumber(fillValues[a], column, row);
                case PREV -> {
                    if (prevCell[cell] < 0) {
                        column.setNull(row);
                    } else {
                        table.writeAggregate(cells[prevCell[cell]], a, column, row);
                    }
                }
                case LINEAR -> writeLinear(cell, a, column, row);
            }
        }
    }

    private void writeGroup(final int group, final Page out, final int row) {
        final int columns = tableTypes.size();
        for (int k = 0; k < columns; k++) {
            table.writeKey(group, k, out.columns[k], row);
        }
        final int offset = columns;
        for (int a = 0; a < table.aggregates(); a++) {
            table.writeAggregate(group, a, out.columns[offset + a], row);
        }
    }

    private void writeLinear(final int cell, final int aggregate, final Vector column, final int row) {
        if (column.type.isText() || column.type == CdbType.BOOLEAN) {
            column.setNull(row);
            return;
        }
        final double value = linear(cell, aggregate, linearValue[aggregate]);
        if (Double.isNaN(value)) {
            column.setNull(row);
        } else if (column.type == CdbType.DOUBLE) {
            column.setDouble(row, value);
        } else {
            column.setLong(row, (long) value);
        }
    }

    /**
     * The value FILL(LINEAR) gives empty cell {@code cell}, NaN for NULL, as QuestDB fills it: between the key's
     * neighbours with data; before its first, along its first two; past its last, along its last and the bucket before
     * that, filled first when empty, so truncated there for a whole-number type, as QuestDB stores it. One cell with
     * data, or a neighbour whose value is NULL, gives NULL.
     */
    private double linear(final int cell, final int aggregate, final Vector y) {
        final int before = prevCell[cell];
        final int after = nextCell[cell];
        if (before >= 0 && after >= 0) {
            return line(cell, before, valueOf(before, aggregate, y), after, valueOf(after, aggregate, y));
        }
        if (after >= 0) {
            final int second = nextCell[after];
            return second < 0
                    ? Double.NaN
                    : line(cell, after, valueOf(after, aggregate, y), second, valueOf(second, aggregate, y));
        }
        if (before < keys) {
            return Double.NaN;
        }
        final int earlier = before - keys;
        double y0;
        if (cells[earlier] >= 0) {
            y0 = valueOf(earlier, aggregate, y);
        } else if (prevCell[earlier] < 0) {
            y0 = Double.NaN;
        } else {
            y0 = linear(earlier, aggregate, y);
            if (y.type != CdbType.DOUBLE && !Double.isNaN(y0)) {
                y0 = (long) y0;
            }
        }
        return line(cell, earlier, y0, before, valueOf(before, aggregate, y));
    }

    // QuestDB's interpolation, in double over the buckets' start times: a whole-number gap neither wraps nor rounds
    // toward its first value, and the cast of a value past a long's range saturates
    private double line(final int cell, final int c0, final double y0, final int c1, final double y1) {
        if (Double.isNaN(y0) || Double.isNaN(y1)) {
            return Double.NaN;
        }
        final long x = bucketStarts[cell / keys];
        final long x0 = bucketStarts[c0 / keys];
        final long x1 = bucketStarts[c1 / keys];
        return (y0 * (x1 - x) + y1 * (x - x0)) / (x1 - x0);
    }

    // the aggregate of cell c, which has data, as a double; NaN for NULL
    private double valueOf(final int c, final int aggregate, final Vector y) {
        table.writeAggregate(cells[c], aggregate, y, 0);
        if (y.isNull(0)) {
            return Double.NaN;
        }
        return y.type == CdbType.DOUBLE ? y.getDouble(0) : y.getLong(0);
    }

    /**
     * One input's rows into one table: its own key functions and bucket vector, as a compiled function reuses its
     * output vector, so frames on several threads share nothing but the cursor's final fields.
     */
    private final class Accumulation {

        private final VectorFn[] keyFns;
        // the bucket, then the keys' vectors of the page being added
        private final Vector[] keysWithBucket;
        private final AggTable table;
        // the bucket last computed, [bucketStart, bucketEnd): time-ordered rows reuse it with two comparisons in
        // place of a division per row (the gain not measured)
        private long bucketEnd;
        private long bucketStart;

        Accumulation(final AggTable table) {
            this.table = table;
            final List<Bound> keys = spec.keys();
            keyFns = new VectorFn[keys.size()];
            for (int k = 0; k < keyFns.length; k++) {
                keyFns[k] = Functions.compile(keys.get(k), nowMicros);
            }
            keysWithBucket = new Vector[keyFns.length + 1];
            keysWithBucket[0] = Vector.of(CdbType.TIMESTAMP, Page.CAPACITY);
        }

        void add(final Page page) {
            final Vector buckets = keysWithBucket[0];
            final Vector micros = page.columns[timestampColumn];
            if (strideMicros > 0) {
                for (int i = 0; i < page.size; i++) {
                    final long ts = micros.getLong(i);
                    if (ts == Vector.NULL_LONG) {
                        buckets.setLong(i, Vector.NULL_LONG);
                    } else {
                        if (ts < bucketStart || ts >= bucketEnd) {
                            bucketStart = Math.floorDiv(ts, strideMicros) * strideMicros;
                            // a floor below Long.MIN_VALUE wraps above ts, and an end past Long.MAX_VALUE wraps
                            // below the start: either leaves an empty range, so those rows floor again
                            bucketEnd = bucketStart > ts ? bucketStart : bucketStart + strideMicros;
                        }
                        buckets.setLong(i, bucketStart);
                    }
                }
            } else {
                for (int i = 0; i < page.size; i++) {
                    final long ts = micros.getLong(i);
                    buckets.setLong(i, ts == Vector.NULL_LONG
                            ? Vector.NULL_LONG
                            : AggBuckets.floor(ts, count, unit));
                }
            }
            for (int k = 0; k < keyFns.length; k++) {
                keysWithBucket[k + 1] = keyFns[k].eval(page);
            }
            table.add(page, keysWithBucket);
        }
    }

    /** How an empty bucket's aggregate is filled. */
    private enum Fill {
        NULL,
        PREV,
        LINEAR,
        VALUE
    }
}
