package dev.marregui.comando.cdb.exec;

import java.util.List;

/**
 * Copies rows from input pages into output pages of {@link Page#CAPACITY} rows, in the order they are added. A page
 * {@link #full()} or {@link #flush()} hands out is never written again. An operator copies until a page fills, hands it
 * out, and resumes where it stopped on its next call.
 */
final class RowsPacker {

    private final List<Field> fields;
    private Page current;

    RowsPacker(final List<Field> fields) {
        this.fields = fields;
    }

    /** True when no row is waiting in a page not yet handed out. */
    boolean isEmpty() {
        return current == null || current.size == 0;
    }

    /** True when the current page has no room left; {@link #full()} then hands it out. */
    boolean isFull() {
        return current != null && current.size == Page.CAPACITY;
    }

    /**
     * Copies up to {@code n} rows of {@code source} from {@code from}, as many as the current page has room for;
     * returns how many it copied, at least 1 for {@code n > 0}.
     */
    int append(final Page source, final int from, final int n) {
        final Page page = current();
        final int count = Math.min(n, Page.CAPACITY - page.size);
        final Vector[] dst = page.columns;
        final Vector[] src = source.columns;
        for (int c = 0; c < dst.length; c++) {
            Rows.copyRange(src[c], from, dst[c], page.size, count);
        }
        page.size += count;
        return count;
    }

    /**
     * Copies row {@code leftRow} of {@code left} then row {@code rightRow} of {@code right} as one row: the left's
     * columns first; {@code right} null pads the right's columns with NULL.
     */
    void appendJoined(final Page left, final int leftRow, final Page right, final int rightRow) {
        final Page page = current();
        final Vector[] dst = page.columns;
        final Vector[] lc = left.columns;
        final int width = lc.length;
        final int to = page.size;
        for (int c = 0; c < width; c++) {
            dst[c].copy(lc[c], leftRow, to);
        }
        if (right == null) {
            for (int c = width; c < dst.length; c++) {
                dst[c].setNull(to);
            }
        } else {
            final Vector[] rc = right.columns;
            for (int c = width; c < dst.length; c++) {
                dst[c].copy(rc[c - width], rightRow, to);
            }
        }
        page.size = to + 1;
    }

    /** The current page when it is full, handed out; else null. */
    Page full() {
        final Page page = current;
        if (page != null && page.size == Page.CAPACITY) {
            current = null;
            return page;
        }
        return null;
    }

    /** The current page when it holds a row, handed out; else null. */
    Page flush() {
        final Page page = current;
        current = null;
        return page != null && page.size > 0 ? page : null;
    }

    private Page current() {
        if (current == null) {
            current = Page.allocate(fields);
        }
        return current;
    }
}
