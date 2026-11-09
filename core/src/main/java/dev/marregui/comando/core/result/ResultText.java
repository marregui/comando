package dev.marregui.comando.core.result;

import java.util.ArrayList;
import java.util.List;

/** A result as text: its column names, and its rows as display cells with SQL NULL as a null cell. */
public final class ResultText {

    private ResultText() {
    }

    /** The column names, in order. */
    public static List<String> columns(final ResultView r) {
        final List<String> columns = new ArrayList<>(r.columnCount());
        for (int c = 0; c < r.columnCount(); c++) {
            columns.add(r.columnName(c));
        }
        return columns;
    }

    /**
     * The first {@code maxRows} rows, each cell in its {@link ResultView#format} text; SQL NULL is a null cell, apart
     * from the text {@code null}.
     */
    public static List<List<String>> rows(final ResultView r, final int maxRows) {
        final int n = Math.min(r.rowCount(), maxRows);
        final int width = r.columnCount();
        final List<List<String>> rows = new ArrayList<>(n);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            final List<String> row = new ArrayList<>(width);
            for (int c = 0; c < width; c++) {
                if (r.isNull(i, c)) {
                    row.add(null);
                    continue;
                }
                sb.setLength(0);
                r.format(i, c, sb);
                row.add(sb.toString());
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Rows {@code from} up to {@code from + count} of the first {@code rows} rows, only the given columns in that
     * order, as display cells with SQL NULL as a null cell. {@code rows} is the caller's one read of
     * {@code rowCount()}.
     */
    public static List<List<String>> page(
            final ResultView r,
            final int rows,
            final int from,
            final int count,
            final int[] columns
    ) {
        // in long, so a count near Integer.MAX_VALUE cannot wrap the end below from
        final int end = (int) Math.min(rows, (long) from + count);
        final List<List<String>> page = new ArrayList<>(Math.max(0, end - from));
        final StringBuilder sb = new StringBuilder();
        for (int i = from; i < end; i++) {
            final List<String> row = new ArrayList<>(columns.length);
            for (final int c : columns) {
                if (r.isNull(i, c)) {
                    row.add(null);
                    continue;
                }
                sb.setLength(0);
                r.format(i, c, sb);
                row.add(sb.toString());
            }
            page.add(row);
        }
        return page;
    }
}
