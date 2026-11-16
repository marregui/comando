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
 * {@code LINEAR} (between the key's neighbours, by bucket ordinal; truncated for a whole-number type) or a number.
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
            while ((page = rows.next()) != null) {
                source.context().check();
                partial.add(page);
            }
        }
        return partial.table;
    }

    private void readAll() {
        if (source == null) {
            Page page;
            while ((page = child.next()) != null) {
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
        final int before = prevCell[cell];
        final int after = nextCell[cell];
        if (before < 0 || after < 0 || column.type.isText() || column.type == CdbType.BOOLEAN) {
            column.setNull(row);
            return;
        }
        final Vector y = Vector.of(column.type, 2);
        table.writeAggregate(cells[before], aggregate, y, 0);
        table.writeAggregate(cells[after], aggregate, y, 1);
        if (y.isNull(0) || y.isNull(1)) {
            column.setNull(row);
            return;
        }
        final long x0 = before / keys;
        final long dx = after / keys - x0;
        final long x = cell / keys - x0;
        if (column.type == CdbType.DOUBLE) {
            column.setDouble(row, y.getDouble(0) + (y.getDouble(1) - y.getDouble(0)) * x / dx);
        } else {
            column.setLong(row, y.getLong(0) + (y.getLong(1) - y.getLong(0)) * x / dx);
        }
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
