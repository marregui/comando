package dev.marregui.comando.cdb.store;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;

/**
 * Rows of one partition held in memory by column while a commit merges them: the array of each column's type holds the
 * values ({@code longs} for LONG and TIMESTAMP, {@code doubles}, {@code booleans}, {@code texts} for SYMBOL and VARCHAR),
 * the others are null; NULL as in {@link CdbType}. {@link #size} rows are valid.
 */
final class RowBlock {

    final CdbType[] types;
    final long[][] longs;
    final double[][] doubles;
    final boolean[][] booleans;
    final String[][] texts;
    int size;

    RowBlock(final List<ColumnDef> columns, final int capacity) {
        final int n = columns.size();
        types = new CdbType[n];
        longs = new long[n][];
        doubles = new double[n][];
        booleans = new boolean[n][];
        texts = new String[n][];
        for (int c = 0; c < n; c++) {
            final CdbType t = columns.get(c).type();
            types[c] = t;
            switch (t) {
                case LONG, TIMESTAMP -> longs[c] = new long[capacity];
                case DOUBLE -> doubles[c] = new double[capacity];
                case BOOLEAN -> booleans[c] = new boolean[capacity];
                case SYMBOL, VARCHAR -> texts[c] = new String[capacity];
            }
        }
    }
}
