package dev.marregui.comando.cdb.exec;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;

/**
 * A batch of up to {@link #CAPACITY} rows, by column. A page {@link Cursor#next} returns belongs to the caller: the
 * cursor never writes it again, so a result can keep it without a copy. A page {@link Cursor#borrow} lends is the
 * cursor's own, {@link #clear}ed and refilled on the next call.
 */
public final class Page {

    /** The most rows a page holds; operators fill pages up to it, the last page of a cursor may be shorter. */
    public static final int CAPACITY = 1024;
    // -ea runs poison a lent page at its next loan (relend)
    private static final boolean POISONS = Page.class.desiredAssertionStatus();

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
            column.copy(column, count, 0, kept);
        }
        size = kept;
    }

    /** Empties the page for a refill, keeping its vectors and their grown arrays. */
    void clear() {
        size = 0;
        for (final Vector column : columns) {
            column.clear();
        }
    }

    /**
     * Overwrites the first {@code size} rows with values no read of a live page gives (a LONG near its minimum, a NaN
     * of a payload no arithmetic makes, a SYMBOL key past any dictionary), so an operator that kept a lent page past
     * its next refill reads garbage a test sees. For {@code -ea} runs only.
     */
    void poison() {
        for (final Vector column : columns) {
            column.poison(size);
        }
    }

    /**
     * The page a cursor lends next, after it lent {@code lent} (null before the first): {@code lent} {@link #clear}ed
     * for its refill; under {@code -ea}, {@code lent} {@link #poison}ed and a new page instead, so a page kept past its
     * loan reads poison.
     */
    static Page relend(final Page lent, final List<Field> fields) {
        if (lent == null) {
            return allocate(fields);
        }
        if (POISONS) {
            lent.poison();
            return allocate(fields);
        }
        lent.clear();
        return lent;
    }

    /** {@link #relend} for a single vector of {@link #CAPACITY} values of {@code type}. */
    static Vector relend(final Vector lent, final CdbType type) {
        if (lent == null) {
            return Vector.of(type, CAPACITY);
        }
        if (POISONS) {
            lent.poison(CAPACITY);
            return Vector.of(type, CAPACITY);
        }
        lent.clear();
        return lent;
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
