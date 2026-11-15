package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.Values;
import dev.marregui.comando.cdb.plan.Plan;

/// The LATEST ON and ORDER BY algorithms as they were before the top-k sort and the newest-first LATEST ON, kept as
/// the oracles the differential tests compare the cursors with (every row read, LATEST ON copying every row that
/// improves its key, ORDER BY sorting the whole input), and the random rows those tests feed both.
final class ReferenceCursors {

    /// `ts`, the designated timestamp, then one column of every type.
    static final List<Field> FIELDS = List.of(RowsFakes.field("ts", CdbType.TIMESTAMP),
            RowsFakes.field("sym", CdbType.SYMBOL), RowsFakes.field("n", CdbType.LONG),
            RowsFakes.field("d", CdbType.DOUBLE), RowsFakes.field("flag", CdbType.BOOLEAN),
            RowsFakes.field("s", CdbType.VARCHAR));

    private ReferenceCursors() {
    }

    /// A generator seeded from `-Dcdb.exec.seed`, else the clock; the seed is printed so a failure can be replayed.
    static Random random(final String test) {
        final long seed = Long.getLong("cdb.exec.seed", System.nanoTime());
        System.out.println(test + " seed=" + seed);
        return new Random(seed);
    }

    /// Mostly up to 300 rows, one case in ten up to 3000, so pages fill and spill.
    static int size(final Random random) {
        return random.nextInt(10) == 0 ? random.nextInt(3000) : random.nextInt(300);
    }

    /// A page size from 1 to [Page#CAPACITY], small ones as likely as the rest.
    static int pageSize(final Random random) {
        return random.nextBoolean() ? 1 + random.nextInt(8) : 1 + random.nextInt(Page.CAPACITY);
    }

    /// `n` rows of [#FIELDS]: values drawn from `cardinality` distinct ones, NULL in `nullPercent` percent of the
    /// nullable values; timestamps ascending with ties when `ascending`, else in any order with ties.
    static Object[][] rows(
            final Random random,
            final int n,
            final int cardinality,
            final int nullPercent,
            final boolean ascending
    ) {
        final Object[][] rows = new Object[n][];
        long micros = 1_000;
        for (int r = 0; r < n; r++) {
            micros = ascending ? micros + random.nextInt(3) : random.nextInt(n / 2 + 1);
            rows[r] = new Object[] {
                    micros,
                    nullable(random, nullPercent, "s" + random.nextInt(cardinality)),
                    nullable(random, nullPercent, (long) random.nextInt(cardinality)),
                    nullable(random, nullPercent, random.nextInt(cardinality) - cardinality / 2 + 0.5),
                    random.nextBoolean(),
                    nullable(random, nullPercent, "v" + random.nextInt(cardinality))
            };
        }
        return rows;
    }

    /// A cursor over `rows` in pages of `pageSize`, newest row first when `reversed`.
    static RowsFakes.ListCursor cursor(final Object[][] rows, final int pageSize, final boolean reversed) {
        final int n = rows.length;
        return RowsFakes.cursor(FIELDS, n, pageSize, r -> rows[reversed ? n - 1 - r : r]);
    }

    /// The rows of [#latestOn] or [#sort], as [RowsFakes#drain] reads them.
    static List<List<Object>> drain(final Cursor cursor) {
        return RowsFakes.drain(cursor).rows();
    }

    /// The latest row per key of an input in any order, the later row on a timestamp tie, in timestamp order.
    static Cursor latestOn(final Cursor child, final int timestampColumn, final int[] partitionColumns) {
        final List<Field> fields = child.fields();
        final List<CdbType> keyTypes = new ArrayList<>();
        for (final int column : partitionColumns) {
            keyTypes.add(fields.get(column).type());
        }
        final AggKeys partitions = new AggKeys(keyTypes);
        final Values[] rows = new Values[fields.size()];
        for (int c = 0; c < rows.length; c++) {
            rows[c] = new Values(fields.get(c).type());
        }
        long[] bestMicros = new long[16];
        long[] sequence = new long[16];
        final Vector[] keys = new Vector[partitionColumns.length];
        long seen = 0;
        Page page;
        while ((page = child.next()) != null) {
            for (int k = 0; k < keys.length; k++) {
                keys[k] = page.columns[partitionColumns[k]];
            }
            final long[] micros = page.columns[timestampColumn].longs;
            for (int i = 0; i < page.size; i++) {
                final int before = partitions.size();
                final int group = partitions.groupOf(keys, i);
                if (group == before) {
                    if (group + 1 > bestMicros.length) {
                        bestMicros = Arrays.copyOf(bestMicros, bestMicros.length * 2);
                        sequence = Arrays.copyOf(sequence, sequence.length * 2);
                    }
                    for (final Values column : rows) {
                        column.ensure(group + 1);
                    }
                }
                if (group == before || micros[i] >= bestMicros[group]) {
                    bestMicros[group] = micros[i];
                    sequence[group] = seen;
                    for (int c = 0; c < rows.length; c++) {
                        rows[c].set(group, page.columns[c], i);
                    }
                }
                seen++;
            }
        }
        child.close();
        final long[] micros = bestMicros;
        final long[] order = sequence;
        final int[] sorted = AggSort.sorted(partitions.size(), (a, b) -> {
            final int byTime = Long.compare(micros[a], micros[b]);
            return byTime != 0 ? byTime : Long.compare(order[a], order[b]);
        });
        final List<Page> out = new ArrayList<>();
        int emitted = 0;
        while (emitted < sorted.length) {
            final Page next = Page.allocate(fields);
            while (next.size < Page.CAPACITY && emitted < sorted.length) {
                for (int c = 0; c < rows.length; c++) {
                    rows[c].write(sorted[emitted], next.columns[c], next.size);
                }
                next.size++;
                emitted++;
            }
            out.add(next);
        }
        return new RowsFakes.ListCursor(fields, out);
    }

    /// The whole input sorted stably by `keys`, NULLs and text ordered as [SortCursor] documents.
    static Cursor sort(final Cursor child, final List<Plan.SortKey> keys) {
        final List<Field> fields = child.fields();
        final RowsBuffer buffer = new RowsBuffer(fields);
        Page page;
        while ((page = child.next()) != null) {
            buffer.addAll(page);
        }
        child.close();
        final int n = buffer.rowCount();
        final int[] sorted = AggSort.sorted(n, (x, y) -> compare(buffer, fields, keys, x, y));
        final List<Page> out = new ArrayList<>();
        int emitted = 0;
        while (emitted < n) {
            final Page next = Page.allocate(fields);
            while (next.size < Page.CAPACITY && emitted < n) {
                final int row = sorted[emitted++];
                for (int c = 0; c < fields.size(); c++) {
                    next.columns[c].copy(buffer.pageOf(row).columns[c], row & Rows.ROW_MASK, next.size);
                }
                next.size++;
            }
            out.add(next);
        }
        return new RowsFakes.ListCursor(fields, out);
    }

    private static Object nullable(final Random random, final int nullPercent, final Object value) {
        return random.nextInt(100) < nullPercent ? null : value;
    }

    private static int compare(
            final RowsBuffer buffer,
            final List<Field> fields,
            final List<Plan.SortKey> keys,
            final int x,
            final int y
    ) {
        final Page px = buffer.pageOf(x);
        final Page py = buffer.pageOf(y);
        final int rx = x & Rows.ROW_MASK;
        final int ry = y & Rows.ROW_MASK;
        for (final Plan.SortKey key : keys) {
            final int c = key.column();
            final Vector vx = px.columns[c];
            final Vector vy = py.columns[c];
            int r = Rows.compare(vx, rx, vy, ry);
            if (r != 0 && fields.get(c).type() == CdbType.DOUBLE && (vx.isNull(rx) || vy.isNull(ry))) {
                r = -r;
            }
            if (r != 0) {
                return key.descending() ? -r : r;
            }
        }
        return 0;
    }
}
