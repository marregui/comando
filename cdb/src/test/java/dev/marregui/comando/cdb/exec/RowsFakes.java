package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionReader;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableReader;
import dev.marregui.comando.cdb.store.TableWriter;

import static org.assertj.core.api.Assertions.assertThat;

/// Fakes and builders the operator tests share: cursors over given pages, a table reader over given partitions, a
/// writer that records what it is given, and a drain that checks every page a cursor returns.
final class RowsFakes {

    /// The row counts every operator is tested at: empty, one, around one page, and several pages.
    static final int[] SIZES = {0, 1, 1023, 1024, 1025, 3000};

    private RowsFakes() {
    }

    static Field field(final String name, final CdbType type) {
        return new Field(name, type);
    }

    /// One LONG column `x` holding `0..n-1`, in pages of `pageSize` rows.
    static ListCursor longs(final int n, final int pageSize) {
        return cursor(List.of(field("x", CdbType.LONG)), n, pageSize, r -> new Object[] {(long) r});
    }

    /// A cursor over `n` rows built by `row`, in pages of `pageSize` rows; a null value is NULL.
    static ListCursor cursor(
            final List<Field> fields,
            final int n,
            final int pageSize,
            final IntFunction<Object[]> row
    ) {
        final List<Page> pages = new ArrayList<>();
        int done = 0;
        while (done < n) {
            final int size = Math.min(pageSize, n - done);
            final Page page = Page.allocate(fields);
            for (int i = 0; i < size; i++) {
                final Object[] values = row.apply(done + i);
                for (int c = 0; c < fields.size(); c++) {
                    set(page.columns[c], i, values[c]);
                }
            }
            page.size = size;
            pages.add(page);
            done += size;
        }
        return new ListCursor(fields, pages);
    }

    /// Rows as lists of boxed values, NULL as null.
    static List<List<Object>> rows(final Object[]... rows) {
        final List<List<Object>> out = new ArrayList<>();
        for (final Object[] row : rows) {
            out.add(Arrays.asList(row));
        }
        return out;
    }

    static void set(final Vector v, final int i, final Object value) {
        if (value == null) {
            v.setNull(i);
            return;
        }
        switch (v.type) {
            case LONG, TIMESTAMP -> v.longs[i] = (Long) value;
            case DOUBLE -> v.doubles[i] = (Double) value;
            case BOOLEAN -> v.booleans[i] = (Boolean) value;
            case SYMBOL, VARCHAR -> v.texts[i] = (String) value;
        }
    }

    static Object value(final Vector v, final int i) {
        if (v.isNull(i)) {
            return null;
        }
        return switch (v.type) {
            case LONG, TIMESTAMP -> v.longs[i];
            case DOUBLE -> v.doubles[i];
            case BOOLEAN -> v.booleans[i];
            case SYMBOL, VARCHAR -> v.texts[i];
        };
    }

    /// Every page of `cursor` until its end, then closes it; fails on an empty page or one over capacity.
    static Drained drain(final Cursor cursor) {
        final List<Page> pages = new ArrayList<>();
        try (cursor) {
            Page page;
            while ((page = cursor.next()) != null) {
                assertThat(page.size).as("a page is never empty").isBetween(1, Page.CAPACITY);
                pages.add(page);
            }
            assertThat(cursor.next()).as("the end stays the end").isNull();
        }
        return new Drained(pages);
    }

    /// The pages a cursor returned, read after the cursor went on: a page is the caller's.
    record Drained(List<Page> pages) {

        List<List<Object>> rows() {
            final List<List<Object>> out = new ArrayList<>();
            for (final Page page : pages) {
                for (int r = 0; r < page.size; r++) {
                    final List<Object> row = new ArrayList<>();
                    for (final Vector v : page.columns) {
                        row.add(value(v, r));
                    }
                    out.add(row);
                }
            }
            return out;
        }

        /// The first column of every row as a long.
        List<Long> longs() {
            final List<Long> out = new ArrayList<>();
            for (final List<Object> row : rows()) {
                out.add((Long) row.getFirst());
            }
            return out;
        }

        List<Integer> sizes() {
            final List<Integer> out = new ArrayList<>();
            for (final Page page : pages) {
                out.add(page.size);
            }
            return out;
        }

        /// True when every page but the last is full.
        boolean packed() {
            for (int p = 0; p < pages.size() - 1; p++) {
                if (pages.get(p).size != Page.CAPACITY) {
                    return false;
                }
            }
            return true;
        }
    }

    /// The values `from..to-1` as longs.
    static List<Long> range(final long from, final long to) {
        final List<Long> out = new ArrayList<>();
        for (long v = from; v < to; v++) {
            out.add(v);
        }
        return out;
    }

    /// A cursor over given pages that counts the pages pulled and the closes.
    static final class ListCursor implements Cursor {

        private final List<Field> fields;
        private final List<Page> pages;
        private int next;
        private int closes;

        ListCursor(final List<Field> fields, final List<Page> pages) {
            this.fields = fields;
            this.pages = pages;
        }

        @Override
        public List<Field> fields() {
            return fields;
        }

        @Override
        public Page next() {
            assertThat(closes).as("no page is pulled from a closed cursor").isZero();
            return next < pages.size() ? pages.get(next++) : null;
        }

        @Override
        public void close() {
            closes++;
        }

        int pulled() {
            return next;
        }

        boolean closed() {
            return closes > 0;
        }

        List<Page> pages() {
            return pages;
        }
    }

    /// A partition over column arrays (`long[]`, `double[]`, `boolean[]` or `String[]`, one per table column).
    record FakePartition(long minTimestamp, long maxTimestamp, int rows, Object[] columns) implements PartitionReader {

        @Override
        public long lowerBound() {
            return minTimestamp;
        }

        @Override
        public long rowCount() {
            return rows;
        }

        @Override
        public void longs(final int column, final long from, final int n, final long[] dst) {
            System.arraycopy(columns[column], (int) from, dst, 0, n);
        }

        @Override
        public void doubles(final int column, final long from, final int n, final double[] dst) {
            System.arraycopy(columns[column], (int) from, dst, 0, n);
        }

        @Override
        public void booleans(final int column, final long from, final int n, final boolean[] dst) {
            System.arraycopy(columns[column], (int) from, dst, 0, n);
        }

        @Override
        public void texts(final int column, final long from, final int n, final String[] dst) {
            System.arraycopy(columns[column], (int) from, dst, 0, n);
        }
    }

    /// A partition of `n` rows of the table `ts TIMESTAMP, x LONG, d DOUBLE, s SYMBOL`: ts and x from `first`, d as
    /// `x / 2`, s as `"s" + x % 10`; every value of x divisible by 7 is NULL in d and s.
    static FakePartition partition(final long first, final int n) {
        final long[] ts = new long[n];
        final long[] x = new long[n];
        final double[] d = new double[n];
        final String[] s = new String[n];
        for (int i = 0; i < n; i++) {
            final long v = first + i;
            ts[i] = v;
            x[i] = v;
            d[i] = v % 7 == 0 ? Double.NaN : v / 2.0;
            s[i] = v % 7 == 0 ? null : "s" + v % 10;
        }
        return new FakePartition(first, first + n - 1, n, new Object[] {ts, x, d, s});
    }

    static TableDef table() {
        return new TableDef("t", List.of(new ColumnDef("ts", CdbType.TIMESTAMP), new ColumnDef("x", CdbType.LONG),
                new ColumnDef("d", CdbType.DOUBLE), new ColumnDef("s", CdbType.SYMBOL)), 0, null, List.of(), 0);
    }

    /// A reader over given partitions that counts its closes.
    static final class FakeReader implements TableReader {

        private final TableDef def;
        private final List<PartitionReader> partitions;
        private int closes;

        FakeReader(final TableDef def, final List<PartitionReader> partitions) {
            this.def = def;
            this.partitions = partitions;
        }

        @Override
        public TableDef def() {
            return def;
        }

        @Override
        public List<PartitionReader> partitions() {
            return partitions;
        }

        @Override
        public long rowCount() {
            long n = 0;
            for (final PartitionReader p : partitions) {
                n += p.rowCount();
            }
            return n;
        }

        @Override
        public void close() {
            closes++;
        }

        int closes() {
            return closes;
        }
    }

    /// A writer that records the rows appended after its last truncate, and whether it committed or rolled back.
    static final class FakeWriter implements TableWriter {

        private final TableDef def;
        private final List<List<Object>> rows = new ArrayList<>();
        private final List<Long> timestamps = new ArrayList<>();
        private Object[] row;
        private boolean truncated;
        private int commits;
        private int rollbacks;

        FakeWriter(final TableDef def) {
            this.def = def;
        }

        @Override
        public TableDef def() {
            return def;
        }

        @Override
        public int addColumn(final ColumnDef column) {
            throw new UnsupportedOperationException("no column is added by an update");
        }

        @Override
        public void newRow(final long micros) {
            assertThat(row).as("a row is ended before the next starts").isNull();
            row = new Object[def.columns().size()];
            if (def.timestampIndex() >= 0) {
                row[def.timestampIndex()] = micros;
            }
            timestamps.add(micros);
        }

        @Override
        public void putLong(final int column, final long value) {
            row[column] = value;
        }

        @Override
        public void putDouble(final int column, final double value) {
            row[column] = value;
        }

        @Override
        public void putBoolean(final int column, final boolean value) {
            row[column] = value;
        }

        @Override
        public void putText(final int column, final String value) {
            row[column] = value;
        }

        @Override
        public void endRow() {
            rows.add(Arrays.asList(row));
            row = null;
        }

        @Override
        public void truncate() {
            truncated = true;
            rows.clear();
        }

        @Override
        public void commit() {
            commits++;
        }

        @Override
        public void rollback() {
            rollbacks++;
        }

        @Override
        public void close() {
        }

        List<List<Object>> rows() {
            return rows;
        }

        List<Long> timestamps() {
            return timestamps;
        }

        boolean truncated() {
            return truncated;
        }

        int commits() {
            return commits;
        }

        int rollbacks() {
            return rollbacks;
        }
    }
}
