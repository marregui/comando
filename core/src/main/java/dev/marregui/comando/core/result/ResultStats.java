package dev.marregui.comando.core.result;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.marregui.comando.core.view.Clip;

/** Per-column summary of a result's first {@code rowCount} rows, as far as the scan got before its deadline. */
public record ResultStats(int rowCount, int scanned, List<Column> columns) {

    /**
     * Distinct values counted exactly up to this many per column; beyond it the count is reported as at least this.
     * It bounds one column's set at about 0.5 MB (a hash-set entry and its boxed key, about 50 bytes each); the entry
     * size is estimated, not measured on a real result.
     */
    public static final int DISTINCT_CAP = 10_000;

    /**
     * The clock is read once per this many rows, which keeps the read off the per-row path. Not measured; its cost is
     * that a scan may run this many rows, times the columns asked, past its deadline. A timed scan of 65,536 rows of a
     * wide text result would settle whether the overshoot matters.
     */
    private static final int ROWS_PER_CLOCK_READ = 65_536;

    /** Min and max texts are cut to this many characters, so one cell cannot fill an agent's 64 KB answer. */
    private static final int TEXT_CLIP_CHARS = 200;

    /**
     * Scans rows 0 to {@code rows} of the given columns. Checks {@code System.nanoTime()} against
     * {@code deadlineNanos} (compared by subtraction) every 65,536 rows and stops there when it has passed, so the
     * first 65,536 rows are scanned whatever the deadline.
     */
    public static ResultStats of(final ResultView r, final int rows, final int[] columns, final long deadlineNanos) {
        final Tally[] tallies = new Tally[columns.length];
        for (int k = 0; k < tallies.length; k++) {
            tallies[k] = new Tally();
        }
        int scanned = 0;
        while (scanned < rows) {
            if (scanned > 0 && System.nanoTime() - deadlineNanos >= 0) {
                break;
            }
            final int end = scanned + Math.min(rows - scanned, ROWS_PER_CLOCK_READ);
            for (int k = 0; k < columns.length; k++) {
                tally(r, columns[k], scanned, end, tallies[k]);
            }
            scanned = end;
        }
        final List<Column> stats = new ArrayList<>(columns.length);
        final StringBuilder sb = new StringBuilder();
        for (int k = 0; k < columns.length; k++) {
            final int c = columns[k];
            final Tally t = tallies[k];
            final boolean any = t.min != null;
            stats.add(new Column(
                    r.columnName(c),
                    r.typeName(c),
                    t.nulls,
                    any ? display(r, t.minRow, c, sb) : null,
                    any ? display(r, t.maxRow, c, sb) : null,
                    t.distinct.size()));
        }
        return new ResultStats(rows, scanned, stats);
    }

    public boolean partial() {
        return scanned < rowCount;
    }

    private static void tally(final ResultView r, final int column, final int from, final int end, final Tally t) {
        final ColumnKind kind = r.kind(column);
        for (int row = from; row < end; row++) {
            if (r.isNull(row, column)) {
                t.nulls++;
                continue;
            }
            final Object v = value(r, kind, row, column);
            if (t.min == null || compare(kind, v, t.min) < 0) {
                t.min = v;
                t.minRow = row;
            }
            if (t.max == null || compare(kind, v, t.max) > 0) {
                t.max = v;
                t.maxRow = row;
            }
            if (t.distinct.size() < DISTINCT_CAP) {
                t.distinct.add(v);
            }
        }
    }

    private static Object value(final ResultView r, final ColumnKind kind, final int row, final int column) {
        return switch (kind) {
            case LONG -> r.getLong(row, column);
            case TIMESTAMP -> r.getMicros(row, column);
            case DOUBLE -> r.getDouble(row, column);
            case BOOLEAN -> r.getBoolean(row, column);
            case TEXT -> r.getText(row, column);
        };
    }

    /** Orders two non-null values of one kind; {@code Double.compare} puts NaN above every other double. */
    private static int compare(final ColumnKind kind, final Object a, final Object b) {
        return switch (kind) {
            case LONG, TIMESTAMP -> Long.compare((Long) a, (Long) b);
            case DOUBLE -> Double.compare((Double) a, (Double) b);
            case BOOLEAN -> Boolean.compare((Boolean) a, (Boolean) b);
            case TEXT -> ((String) a).compareTo((String) b);
        };
    }

    private static String display(final ResultView r, final int row, final int column, final StringBuilder sb) {
        sb.setLength(0);
        r.format(row, column, sb);
        // the ellipsis tells a cut value from an exact one
        return Clip.keepStart(sb.toString(), TEXT_CLIP_CHARS);
    }

    /**
     * @param nulls    SQL NULLs among the scanned rows
     * @param min      the smallest non-null value as display text ({@link ResultView#format}), null when every scanned
     *                 cell is NULL
     * @param max      the largest, likewise
     * @param distinct distinct non-null values, exact when below {@link #DISTINCT_CAP}, else {@link #DISTINCT_CAP}
     */
    public record Column(String name, String type, int nulls, String min, String max, int distinct) {

        public boolean distinctCapped() {
            return distinct >= DISTINCT_CAP;
        }
    }

    /** One column's running counts; {@code min} and {@code max} stay null until the first non-null cell. */
    private static final class Tally {
        private final Set<Object> distinct = new HashSet<>();
        private Object max;
        private int maxRow;
        private Object min;
        private int minRow;
        private int nulls;
    }
}
