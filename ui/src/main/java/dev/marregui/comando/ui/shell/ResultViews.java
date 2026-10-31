package dev.marregui.comando.ui.shell;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.view.ViewSpec;

/** Views made from a result: a plot of its numeric columns, a table of its rows. */
final class ResultViews {

    static final int MAX_PLOT_POINTS = 2_000_000;
    static final int MAX_TABLE_ROWS = 100_000;

    private ResultViews() {
    }

    /**
     * The first time column, else the first numeric one, as x; up to eight other numeric columns as series.
     *
     * @throws IllegalArgumentException with the reason when the result cannot be plotted
     */
    static ViewSpec.Plot plot(final ResultView r) {
        if (r == null || r.rowCount() == 0) {
            throw new IllegalArgumentException("The panel has no rows to plot");
        }
        int x = -1;
        for (int c = 0; c < r.columnCount() && x < 0; c++) {
            if (r.kind(c) == ColumnKind.TIMESTAMP) {
                x = c;
            }
        }
        for (int c = 0; c < r.columnCount() && x < 0; c++) {
            if (r.isNumeric(c)) {
                x = c;
            }
        }
        if (x < 0) {
            throw new IllegalArgumentException("The result has no numeric or time column");
        }
        final int n = Math.min(r.rowCount(), MAX_PLOT_POINTS);
        final double[] xs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = r.numeric(i, x);
        }
        final List<ViewSpec.Series> series = new ArrayList<>();
        for (int c = 0; c < r.columnCount() && series.size() < 8; c++) {
            if (c != x && r.isNumeric(c) && r.kind(c) != ColumnKind.TIMESTAMP) {
                final double[] ys = new double[n];
                for (int i = 0; i < n; i++) {
                    ys[i] = r.numeric(i, c);
                }
                series.add(new ViewSpec.Series(r.columnName(c), xs, ys));
            }
        }
        if (series.isEmpty()) {
            throw new IllegalArgumentException("The result has no numeric column besides x");
        }
        return new ViewSpec.Plot(r.columnName(x), "", r.kind(x) == ColumnKind.TIMESTAMP, series);
    }

    /** The first {@link #MAX_TABLE_ROWS} rows as text. */
    static ViewSpec.Table table(final ResultView r) {
        final List<String> columns = new ArrayList<>(r.columnCount());
        for (int c = 0; c < r.columnCount(); c++) {
            columns.add(r.columnName(c));
        }
        final int n = Math.min(r.rowCount(), MAX_TABLE_ROWS);
        final List<List<String>> rows = new ArrayList<>(n);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            final List<String> row = new ArrayList<>(columns.size());
            for (int c = 0; c < columns.size(); c++) {
                sb.setLength(0);
                r.format(i, c, sb);
                row.add(sb.toString());
            }
            rows.add(row);
        }
        return new ViewSpec.Table(columns, rows);
    }
}
