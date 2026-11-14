package dev.marregui.comando.db;

import dev.marregui.comando.cdb.CdbResult;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultView;

/**
 * A cdb result as the grid, plots and Claude read results: the result's own pages, no copy. Complete when it is
 * made, so every row below {@link #rowCount()} is there for any thread from the start.
 */
final class CdbResultView implements ResultView {

    private final ColumnKind[] kinds;
    private final long requestId;
    private final CdbResult result;

    CdbResultView(final long requestId, final CdbResult result) {
        this.requestId = requestId;
        this.result = result;
        kinds = new ColumnKind[result.columnCount()];
        for (int c = 0; c < kinds.length; c++) {
            kinds[c] = kindOf(result.type(c));
        }
    }

    @Override
    public long requestId() {
        return requestId;
    }

    @Override
    public int columnCount() {
        return kinds.length;
    }

    @Override
    public String columnName(final int column) {
        return result.columnName(column);
    }

    @Override
    public ColumnKind kind(final int column) {
        return kinds[column];
    }

    @Override
    public String typeName(final int column) {
        return result.type(column).sqlName();
    }

    @Override
    public int rowCount() {
        return result.rowCount();
    }

    @Override
    public boolean isNull(final int row, final int column) {
        checkRow(row);
        return result.isNull(row, column);
    }

    /** A LONG or TIMESTAMP column's value; a BOOLEAN column's as 1 or 0, as {@link ColumnarResult} keeps it. */
    @Override
    public long getLong(final int row, final int column) {
        checkRow(row);
        if (kinds[column] == ColumnKind.BOOLEAN) {
            return result.getBoolean(row, column) ? 1L : 0L;
        }
        return result.getLong(row, column);
    }

    /** A DOUBLE column's value; another column's {@link #getLong} widened, as {@link ColumnarResult} reads it. */
    @Override
    public double getDouble(final int row, final int column) {
        checkRow(row);
        if (kinds[column] != ColumnKind.DOUBLE) {
            return getLong(row, column);
        }
        return result.getDouble(row, column);
    }

    @Override
    public boolean getBoolean(final int row, final int column) {
        checkRow(row);
        return result.getBoolean(row, column);
    }

    /** A text column's value; any other column's display text, as {@link ColumnarResult} gives it. */
    @Override
    public String getText(final int row, final int column) {
        checkRow(row);
        if (kinds[column] != ColumnKind.TEXT) {
            final StringBuilder sb = new StringBuilder();
            format(row, column, sb);
            return sb.toString();
        }
        return result.getText(row, column);
    }

    /** The storage class a column of {@code type} is read as: SYMBOL and VARCHAR as text. */
    static ColumnKind kindOf(final CdbType type) {
        return switch (type) {
            case BOOLEAN -> ColumnKind.BOOLEAN;
            case LONG -> ColumnKind.LONG;
            case DOUBLE -> ColumnKind.DOUBLE;
            case TIMESTAMP -> ColumnKind.TIMESTAMP;
            case SYMBOL, VARCHAR -> ColumnKind.TEXT;
        };
    }

    private void checkRow(final int row) {
        if (row < 0 || row >= result.rowCount()) {
            throw new IndexOutOfBoundsException("row " + row + ", rows " + result.rowCount());
        }
    }
}
