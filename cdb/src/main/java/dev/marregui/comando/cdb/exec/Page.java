package dev.marregui.comando.cdb.exec;

import java.util.List;

/**
 * A batch of up to {@link #CAPACITY} rows, by column. A page a {@link Cursor} returns belongs to the caller: the cursor
 * never writes it again, so a result can keep it without a copy.
 */
public final class Page {

    /** The most rows a page holds; operators fill pages up to it, the last page of a cursor may be shorter. */
    public static final int CAPACITY = 1024;

    public final Vector[] columns;
    public int size;

    /** A page with the given columns and {@code size} rows. */
    public Page(final Vector[] columns, final int size) {
        this.columns = columns;
        this.size = size;
    }

    /** Drops the first {@code count} rows, moving the rest to the front of each column; {@code count <= size}. */
    public void dropFirst(final int count) {
        final int kept = size - count;
        for (final Vector column : columns) {
            final Object values = switch (column.type) {
                case LONG, TIMESTAMP -> column.longs;
                case DOUBLE -> column.doubles;
                case BOOLEAN -> column.booleans;
                case SYMBOL, VARCHAR -> column.texts;
            };
            System.arraycopy(values, count, values, 0, kept);
        }
        size = kept;
    }

    /** An empty page with one vector of {@link #CAPACITY} values per field. */
    public static Page allocate(final List<Field> fields) {
        final Vector[] columns = new Vector[fields.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = Vector.of(fields.get(i).type(), CAPACITY);
        }
        return new Page(columns, 0);
    }
}
