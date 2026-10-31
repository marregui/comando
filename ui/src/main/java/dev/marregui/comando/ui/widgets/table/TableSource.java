package dev.marregui.comando.ui.widgets.table;

import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.view.ViewSpec;

/** Rows the grid shows: a live SQL result or a table Claude placed. */
public interface TableSource {

    int rowCount();

    int columnCount();

    String columnName(int column);

    String typeName(int column);

    boolean isNumeric(int column);

    boolean isNull(int row, int column);

    /** Appends the cell's text to {@code sink}. */
    void format(int row, int column, StringBuilder sink);

    /** A sortable number for numeric columns, NaN otherwise. */
    double numeric(int row, int column);

    static TableSource of(ResultView r) {
        return new TableSource() {
            @Override
            public int rowCount() {
                return r.rowCount();
            }

            @Override
            public int columnCount() {
                return r.columnCount();
            }

            @Override
            public String columnName(int column) {
                return r.columnName(column);
            }

            @Override
            public String typeName(int column) {
                return r.typeName(column);
            }

            @Override
            public boolean isNumeric(int column) {
                return r.isNumeric(column);
            }

            @Override
            public boolean isNull(int row, int column) {
                return r.isNull(row, column);
            }

            @Override
            public void format(int row, int column, StringBuilder sink) {
                r.format(row, column, sink);
            }

            @Override
            public double numeric(int row, int column) {
                return r.numeric(row, column);
            }
        };
    }

    static TableSource of(ViewSpec.Table t) {
        int n = t.columns().size();
        boolean[] numeric = new boolean[n];
        for (int c = 0; c < n; c++) {
            boolean all = !t.rows().isEmpty();
            for (var row : t.rows()) {
                String v = row.get(c);
                if (!v.equals("null") && !isNumber(v)) {
                    all = false;
                    break;
                }
            }
            numeric[c] = all;
        }
        return new TableSource() {
            @Override
            public int rowCount() {
                return t.rows().size();
            }

            @Override
            public int columnCount() {
                return n;
            }

            @Override
            public String columnName(int column) {
                return t.columns().get(column);
            }

            @Override
            public String typeName(int column) {
                return numeric[column] ? "number" : "text";
            }

            @Override
            public boolean isNumeric(int column) {
                return numeric[column];
            }

            @Override
            public boolean isNull(int row, int column) {
                return t.rows().get(row).get(column).equals("null");
            }

            @Override
            public void format(int row, int column, StringBuilder sink) {
                sink.append(t.rows().get(row).get(column));
            }

            @Override
            public double numeric(int row, int column) {
                if (!numeric[column] || isNull(row, column)) {
                    return Double.NaN;
                }
                return Double.parseDouble(t.rows().get(row).get(column));
            }
        };
    }

    /**
     * Whether {@code s} is a decimal number: an optional sign, digits with an optional fraction, an optional exponent.
     * {@link Double#parseDouble} alone would also take {@code 1d}, {@code 2f}, hex, {@code NaN}, {@code Infinity} and
     * surrounding spaces, and class a column of codes as numbers.
     */
    private static boolean isNumber(final String s) {
        final int n = s.length();
        int i = skipSign(s, 0);
        final int whole = i;
        i = skipDigits(s, i);
        int digits = i - whole;
        if (i < n && s.charAt(i) == '.') {
            i++;
            final int fraction = i;
            i = skipDigits(s, i);
            digits += i - fraction;
        }
        if (digits == 0) {
            return false;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            final int exponent = skipSign(s, i + 1);
            i = skipDigits(s, exponent);
            if (i == exponent) {
                return false;
            }
        }
        return i == n;
    }

    private static int skipSign(final String s, final int at) {
        return at < s.length() && (s.charAt(at) == '+' || s.charAt(at) == '-') ? at + 1 : at;
    }

    /** Past the ASCII digits from {@code at}: {@link Character#isDigit} would take digits the parse rejects. */
    private static int skipDigits(final String s, final int at) {
        int i = at;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i;
    }
}
