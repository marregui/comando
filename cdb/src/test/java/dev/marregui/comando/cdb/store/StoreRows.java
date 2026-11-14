package dev.marregui.comando.cdb.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/// Reads a snapshot's rows back as text, for the store's tests: one line per row, the columns' values joined by `|`,
/// a NULL as `null`.
final class StoreRows {

    private StoreRows() {
    }

    /// The microseconds since the epoch of an ISO-8601 instant.
    static long micros(final String iso) {
        return Instant.parse(iso).toEpochMilli() * 1_000L;
    }

    /// Every row of `reader`, partition after partition.
    static List<String> rows(final TableReader reader) {
        final List<String> rows = new ArrayList<>();
        for (final PartitionReader partition : reader.partitions()) {
            rows.addAll(rows(reader.def(), partition));
        }
        return rows;
    }

    /// Every row of `partition`, read with the columns of `def`.
    static List<String> rows(final TableDef def, final PartitionReader partition) {
        final int n = (int) partition.rowCount();
        final int columns = def.columns().size();
        final String[][] values = new String[columns][];
        for (int c = 0; c < columns; c++) {
            values[c] = column(def.columns().get(c), c, partition, n);
        }
        final List<String> rows = new ArrayList<>(n);
        for (int r = 0; r < n; r++) {
            final StringBuilder row = new StringBuilder();
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    row.append('|');
                }
                row.append(values[c][r]);
            }
            rows.add(row.toString());
        }
        return rows;
    }

    /// The designated timestamps of every row of `reader`, in read order.
    static List<Long> timestamps(final TableReader reader) {
        final List<Long> out = new ArrayList<>();
        final int ts = reader.def().timestampIndex();
        for (final PartitionReader partition : reader.partitions()) {
            final int n = (int) partition.rowCount();
            final long[] longs = new long[n];
            partition.longs(ts, 0L, n, longs);
            for (final long t : longs) {
                out.add(t);
            }
        }
        return out;
    }

    private static String[] column(final ColumnDef column, final int c, final PartitionReader partition, final int n) {
        final String[] out = new String[n];
        switch (column.type()) {
            case LONG, TIMESTAMP -> {
                final long[] longs = new long[n];
                partition.longs(c, 0L, n, longs);
                for (int r = 0; r < n; r++) {
                    out[r] = longs[r] == Long.MIN_VALUE ? "null" : Long.toString(longs[r]);
                }
            }
            case DOUBLE -> {
                final double[] doubles = new double[n];
                partition.doubles(c, 0L, n, doubles);
                for (int r = 0; r < n; r++) {
                    out[r] = Double.isNaN(doubles[r]) ? "null" : Double.toString(doubles[r]);
                }
            }
            case BOOLEAN -> {
                final boolean[] booleans = new boolean[n];
                partition.booleans(c, 0L, n, booleans);
                for (int r = 0; r < n; r++) {
                    out[r] = Boolean.toString(booleans[r]);
                }
            }
            case SYMBOL, VARCHAR -> {
                final String[] texts = new String[n];
                partition.texts(c, 0L, n, texts);
                for (int r = 0; r < n; r++) {
                    out[r] = texts[r] == null ? "null" : '"' + texts[r] + '"';
                }
            }
        }
        return out;
    }
}
