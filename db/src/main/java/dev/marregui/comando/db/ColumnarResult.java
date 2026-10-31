package dev.marregui.comando.db;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultView;

/**
 * Rows of one run stored by column in primitive chunks: no boxed value and no row object per cell.
 *
 * <p>One writer (the connection's lane thread) appends; readers (the EDT, plots) read rows below
 * {@link #rowCount()}. The writer fills a row's cells, then publishes the new count through a volatile
 * write, so a reader that read the count sees every cell and chunk below it. A column's chunk table is replaced
 * when it grows, and a reader can meet a table grown for a row past the count it read: the writer stores a grown
 * table with a release store and readers load it with an acquire load, so the reader sees the table's copied
 * chunks.</p>
 */
public final class ColumnarResult implements ResultView {

    static final int CHUNK_BITS = 14;
    static final int CHUNK = 1 << CHUNK_BITS;
    private static final int MASK = CHUNK - 1;
    private static final VarHandle LONG_TABLE = MethodHandles.arrayElementVarHandle(long[][][].class);
    private static final VarHandle DOUBLE_TABLE = MethodHandles.arrayElementVarHandle(double[][][].class);
    private static final VarHandle TEXT_TABLE = MethodHandles.arrayElementVarHandle(String[][][].class);

    private final long requestId;
    private final String[] names;
    private final String[] typeNames;
    private final ColumnKind[] kinds;
    private final int[] sqlTypes;
    private final long[][][] longs;      // [column][chunk][row]; LONG, BOOLEAN, TIMESTAMP
    private final double[][][] doubles;  // [column][chunk][row]; DOUBLE
    private final String[][][] texts;    // [column][chunk][row]; TEXT, null for SQL NULL
    private final long[][][] nulls;      // [column][chunk][row / 64]; bit set for SQL NULL
    private volatile int rowCount;

    private ColumnarResult(final long requestId, final String[] names, final String[] typeNames, final ColumnKind[] kinds, final int[] sqlTypes) {
        this.requestId = requestId;
        this.names = names;
        this.typeNames = typeNames;
        this.kinds = kinds;
        this.sqlTypes = sqlTypes;
        final int n = names.length;
        longs = new long[n][][];
        doubles = new double[n][][];
        texts = new String[n][][];
        nulls = new long[n][][];
        for (int c = 0; c < n; c++) {
            switch (kinds[c]) {
                case LONG, BOOLEAN, TIMESTAMP -> longs[c] = new long[4][];
                case DOUBLE -> doubles[c] = new double[4][];
                case TEXT -> texts[c] = new String[4][];
            }
            nulls[c] = new long[4][];
        }
    }

    public static ColumnarResult of(final long requestId, final ResultSetMetaData md) throws SQLException {
        final int n = md.getColumnCount();
        final String[] names = new String[n];
        final String[] typeNames = new String[n];
        final ColumnKind[] kinds = new ColumnKind[n];
        final int[] sqlTypes = new int[n];
        for (int c = 0; c < n; c++) {
            names[c] = md.getColumnLabel(c + 1);
            typeNames[c] = md.getColumnTypeName(c + 1);
            sqlTypes[c] = md.getColumnType(c + 1);
            kinds[c] = kindOf(sqlTypes[c]);
        }
        return new ColumnarResult(requestId, names, typeNames, kinds, sqlTypes);
    }

    static ColumnKind kindOf(final int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> ColumnKind.LONG;
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> ColumnKind.DOUBLE;
            case Types.BOOLEAN, Types.BIT -> ColumnKind.BOOLEAN;
            case Types.DATE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> ColumnKind.TIMESTAMP;
            default -> ColumnKind.TEXT;
        };
    }

    /** Appends the result set's current row. Writer thread only. */
    void append(final ResultSet rs) throws SQLException {
        final int row = rowCount;
        final int chunk = row >>> CHUNK_BITS;
        final int at = row & MASK;
        for (int c = 0; c < names.length; c++) {
            final int col = c + 1;
            boolean isNull;
            switch (kinds[c]) {
                case LONG -> {
                    final long v = rs.getLong(col);
                    isNull = rs.wasNull();
                    longChunk(c, chunk)[at] = v;
                }
                case DOUBLE -> {
                    final double v = rs.getDouble(col);
                    isNull = rs.wasNull();
                    doubleChunk(c, chunk)[at] = v;
                }
                case BOOLEAN -> {
                    final boolean v = rs.getBoolean(col);
                    isNull = rs.wasNull();
                    longChunk(c, chunk)[at] = v ? 1L : 0L;
                }
                case TIMESTAMP -> {
                    final long v = readMicros(rs, col, sqlTypes[c]);
                    isNull = rs.wasNull();
                    longChunk(c, chunk)[at] = v;
                }
                default -> {
                    final String v = rs.getString(col);
                    isNull = v == null;
                    textChunk(c, chunk)[at] = v;
                }
            }
            final long[] bits = nullChunk(c, chunk);
            if (isNull) {
                bits[at >>> 6] |= 1L << (at & 63);
            }
        }
        rowCount = row + 1;
    }

    private static long readMicros(final ResultSet rs, final int col, final int sqlType) throws SQLException {
        switch (sqlType) {
            case Types.TIMESTAMP_WITH_TIMEZONE -> {
                final OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
                return t == null ? 0L : t.toEpochSecond() * 1_000_000L + t.getNano() / 1_000;
            }
            case Types.DATE -> {
                final LocalDate d = rs.getObject(col, LocalDate.class);
                return d == null ? 0L : d.toEpochDay() * 86_400_000_000L;
            }
            default -> {
                final LocalDateTime t = rs.getObject(col, LocalDateTime.class);
                return t == null ? 0L : t.toEpochSecond(ZoneOffset.UTC) * 1_000_000L + t.getNano() / 1_000;
            }
        }
    }

    private long[] longChunk(final int c, final int chunk) {
        long[][] table = longs[c];
        if (chunk == table.length) {
            // no overflow: a row is an int, so a table never needs more than 2^(31 - CHUNK_BITS) = 2^17 slots
            table = Arrays.copyOf(table, table.length * 2);
            LONG_TABLE.setRelease(longs, c, table);
        }
        long[] a = table[chunk];
        if (a == null) {
            table[chunk] = a = new long[CHUNK];
        }
        return a;
    }

    private double[] doubleChunk(final int c, final int chunk) {
        double[][] table = doubles[c];
        if (chunk == table.length) {
            // no overflow: a row is an int, so a table never needs more than 2^(31 - CHUNK_BITS) = 2^17 slots
            table = Arrays.copyOf(table, table.length * 2);
            DOUBLE_TABLE.setRelease(doubles, c, table);
        }
        double[] a = table[chunk];
        if (a == null) {
            table[chunk] = a = new double[CHUNK];
        }
        return a;
    }

    private String[] textChunk(final int c, final int chunk) {
        String[][] table = texts[c];
        if (chunk == table.length) {
            // no overflow: a row is an int, so a table never needs more than 2^(31 - CHUNK_BITS) = 2^17 slots
            table = Arrays.copyOf(table, table.length * 2);
            TEXT_TABLE.setRelease(texts, c, table);
        }
        String[] a = table[chunk];
        if (a == null) {
            table[chunk] = a = new String[CHUNK];
        }
        return a;
    }

    private long[] nullChunk(final int c, final int chunk) {
        long[][] table = nulls[c];
        if (chunk == table.length) {
            // no overflow: a row is an int, so a table never needs more than 2^(31 - CHUNK_BITS) = 2^17 slots
            table = Arrays.copyOf(table, table.length * 2);
            LONG_TABLE.setRelease(nulls, c, table);
        }
        long[] a = table[chunk];
        if (a == null) {
            table[chunk] = a = new long[CHUNK >>> 6];
        }
        return a;
    }

    // ---- ResultView

    @Override
    public long requestId() {
        return requestId;
    }

    @Override
    public int columnCount() {
        return names.length;
    }

    @Override
    public String columnName(final int column) {
        return names[column];
    }

    @Override
    public ColumnKind kind(final int column) {
        return kinds[column];
    }

    @Override
    public String typeName(final int column) {
        return typeNames[column];
    }

    @Override
    public int rowCount() {
        return rowCount;
    }

    @Override
    public boolean isNull(final int row, final int column) {
        checkRow(row);
        final int at = row & MASK;
        return (longTable(nulls, column)[row >>> CHUNK_BITS][at >>> 6] & (1L << (at & 63))) != 0;
    }

    @Override
    public long getLong(final int row, final int column) {
        checkRow(row);
        return longTable(longs, column)[row >>> CHUNK_BITS][row & MASK];
    }

    @Override
    public double getDouble(final int row, final int column) {
        checkRow(row);
        if (kinds[column] != ColumnKind.DOUBLE) {
            return getLong(row, column);
        }
        return ((double[][]) DOUBLE_TABLE.getAcquire(doubles, column))[row >>> CHUNK_BITS][row & MASK];
    }

    @Override
    public boolean getBoolean(final int row, final int column) {
        return getLong(row, column) != 0L;
    }

    @Override
    public String getText(final int row, final int column) {
        checkRow(row);
        if (kinds[column] != ColumnKind.TEXT) {
            final StringBuilder sb = new StringBuilder();
            format(row, column, sb);
            return sb.toString();
        }
        return ((String[][]) TEXT_TABLE.getAcquire(texts, column))[row >>> CHUNK_BITS][row & MASK];
    }

    /** A column's chunk table as a reader loads it: after the count, with an acquire load. */
    private static long[][] longTable(final long[][][] tables, final int column) {
        return (long[][]) LONG_TABLE.getAcquire(tables, column);
    }

    private void checkRow(final int row) {
        if (row < 0 || row >= rowCount) {
            throw new IndexOutOfBoundsException("row " + row + ", rows " + rowCount);
        }
    }
}
