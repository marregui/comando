package dev.marregui.comando.core.result;

/**
 * Read access to the rows of one SQL run. One writer appends rows; readers see a growing prefix whose
 * length is {@link #rowCount()}. Every read of a row below {@code rowCount()} is valid.
 */
public interface ResultView {

    long requestId();

    int columnCount();

    String columnName(int column);

    ColumnKind kind(int column);

    /** The database type name, for headers. */
    String typeName(int column);

    int rowCount();

    boolean isNull(int row, int column);

    long getLong(int row, int column);

    double getDouble(int row, int column);

    boolean getBoolean(int row, int column);

    /** Epoch microseconds UTC of a TIMESTAMP column. */
    default long getMicros(final int row, final int column) {
        return getLong(row, column);
    }

    String getText(int row, int column);

    /** A plottable value: numbers as they are, timestamps as epoch milliseconds, NaN otherwise. */
    default double numeric(final int row, final int column) {
        if (isNull(row, column)) {
            return Double.NaN;
        }
        return switch (kind(column)) {
            case LONG -> getLong(row, column);
            case DOUBLE -> getDouble(row, column);
            case BOOLEAN -> getBoolean(row, column) ? 1.0 : 0.0;
            case TIMESTAMP -> getMicros(row, column) / 1000.0;
            case TEXT -> Double.NaN;
        };
    }

    /** Appends the cell's display text to {@code sink}, which the caller reuses across cells. */
    default void format(final int row, final int column, final StringBuilder sink) {
        if (isNull(row, column)) {
            sink.append("null");
            return;
        }
        switch (kind(column)) {
            case LONG -> sink.append(getLong(row, column));
            case DOUBLE -> sink.append(getDouble(row, column));
            case BOOLEAN -> sink.append(getBoolean(row, column));
            case TIMESTAMP -> Formats.appendMicrosUtc(sink, getMicros(row, column));
            case TEXT -> sink.append(getText(row, column));
        }
    }

    default boolean isNumeric(final int column) {
        final ColumnKind k = kind(column);
        return k == ColumnKind.LONG || k == ColumnKind.DOUBLE || k == ColumnKind.TIMESTAMP;
    }
}
