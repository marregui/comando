package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

/// Test helper: a query's answer as its exact bits, a double by `Double.doubleToRawLongBits`, and the first cell two
/// answers differ in.
final class ParallelRows {

    /// The bound on the relative difference of a double sum or average between a parallel answer and the serial one:
    /// the frames' partial sums are added in another order than one pass over the rows, which moves the last digits.
    static final double SUM_TOLERANCE = 1e-12;

    private ParallelRows() {
    }

    /// The plan of the query `sql` over `store`'s tables, at `nowMicros`.
    static Plan plan(final Store store, final String sql, final long nowMicros) {
        return Planner.planned(Parser.parse(sql), store::def, nowMicros).plan();
    }

    /// Every row of `cursor`, which this closes.
    static Answer drain(final Cursor cursor) {
        try (cursor) {
            final List<Field> fields = cursor.fields();
            final List<Page> pages = new ArrayList<>();
            int rows = 0;
            for (Page page = cursor.next(); page != null; page = cursor.next()) {
                pages.add(page);
                rows += page.size;
            }
            final long[][] bits = new long[fields.size()][];
            final String[][] texts = new String[fields.size()][];
            for (int c = 0; c < fields.size(); c++) {
                if (fields.get(c).type().isText()) {
                    texts[c] = new String[rows];
                } else {
                    bits[c] = new long[rows];
                }
            }
            int at = 0;
            for (final Page page : pages) {
                for (int c = 0; c < fields.size(); c++) {
                    final Vector v = page.columns[c];
                    for (int i = 0; i < page.size; i++) {
                        switch (v.type) {
                            case LONG, TIMESTAMP -> bits[c][at + i] = v.getLong(i);
                            case DOUBLE -> bits[c][at + i] = Double.doubleToRawLongBits(v.getDouble(i));
                            case BOOLEAN -> bits[c][at + i] = v.getBoolean(i) ? 1L : 0L;
                            case SYMBOL, VARCHAR -> texts[c][at + i] = v.textAt(i);
                        }
                    }
                }
                at += page.size;
            }
            return new Answer(List.copyOf(fields), rows, bits, texts);
        }
    }

    /// A query's rows by column: `bits[c]` for a number, a boolean (1 or 0) or a timestamp, `texts[c]` for text.
    record Answer(List<Field> fields, int rows, long[][] bits, String[][] texts) {

        /// The first difference from `expected`, null when none. A DOUBLE column whose name starts with `sum_` or
        /// `avg_` may differ by [#SUM_TOLERANCE] relative when `tolerant`; every other cell must hold the same bits.
        String differenceFrom(final Answer expected, final boolean tolerant) {
            if (!fields.equals(expected.fields)) {
                return "columns differ [expected=" + expected.fields + ", actual=" + fields + ']';
            }
            if (rows != expected.rows) {
                return "row counts differ [expected=" + expected.rows + ", actual=" + rows + ']';
            }
            for (int c = 0; c < fields.size(); c++) {
                final Field field = fields.get(c);
                final boolean sum = tolerant && field.type() == CdbType.DOUBLE
                        && (field.name().startsWith("sum_") || field.name().startsWith("avg_"));
                for (int r = 0; r < rows; r++) {
                    final boolean same = field.type().isText()
                            ? Objects.equals(texts[c][r], expected.texts[c][r])
                            : bits[c][r] == expected.bits[c][r] || sum && close(bits[c][r], expected.bits[c][r]);
                    if (!same) {
                        return "cells differ [row=" + r + ", column=" + field.name() + ", expected="
                                + cell(expected, c, r) + ", actual=" + cell(this, c, r) + ']';
                    }
                }
            }
            return null;
        }

        private static boolean close(final long actualBits, final long expectedBits) {
            final double a = Double.longBitsToDouble(actualBits);
            final double e = Double.longBitsToDouble(expectedBits);
            if (Double.isNaN(a) || Double.isNaN(e)) {
                return Double.isNaN(a) && Double.isNaN(e);
            }
            return Math.abs(a - e) <= SUM_TOLERANCE * Math.max(Math.abs(a), Math.abs(e));
        }

        private static String cell(final Answer answer, final int c, final int r) {
            return switch (answer.fields.get(c).type()) {
                case DOUBLE -> Double.longBitsToDouble(answer.bits[c][r]) + " (bits "
                        + Long.toHexString(answer.bits[c][r]) + ')';
                case SYMBOL, VARCHAR -> String.valueOf(answer.texts[c][r]);
                case LONG, TIMESTAMP, BOOLEAN -> String.valueOf(answer.bits[c][r]);
            };
        }
    }
}
