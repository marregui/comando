package dev.marregui.comando.cdb;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;

/**
 * The rows of one statement, by column, in the pages the query produced: no copy and no row object. Immutable once
 * returned; read from any thread. A statement that changes the store has no columns and its {@link #updateCount()}.
 */
public final class CdbResult {

    private final List<Field> fields;
    private final List<Page> pages;
    private final int[] starts;
    private final int rowCount;
    private final long updateCount;
    private final boolean truncated;

    private CdbResult(final List<Field> fields, final List<Page> pages, final long updateCount, final boolean truncated) {
        this.fields = List.copyOf(fields);
        this.pages = List.copyOf(pages);
        this.updateCount = updateCount;
        this.truncated = truncated;
        starts = new int[pages.size() + 1];
        for (int i = 0; i < pages.size(); i++) {
            starts[i + 1] = starts[i] + pages.get(i).size;
        }
        rowCount = starts[pages.size()];
    }

    /** Drains {@code cursor} up to {@code maxRows} rows (no bound when not positive), then closes it. */
    static CdbResult drain(final Cursor cursor, final int maxRows) {
        try (cursor) {
            final List<Page> pages = new ArrayList<>();
            int rows = 0;
            boolean truncated = false;
            Page page;
            while ((page = cursor.next()) != null) {
                if (maxRows > 0 && rows + page.size > maxRows) {
                    page.size = maxRows - rows;
                    truncated = true;
                }
                if (page.size > 0) {
                    pages.add(page);
                    rows += page.size;
                }
                if (truncated) {
                    break;
                }
            }
            return new CdbResult(cursor.fields(), pages, -1, truncated);
        }
    }

    /** The result of a statement that changed {@code count} rows. */
    static CdbResult updated(final long count) {
        return new CdbResult(List.of(), List.of(), count, false);
    }

    public int columnCount() {
        return fields.size();
    }

    public String columnName(final int column) {
        return fields.get(column).name();
    }

    public CdbType type(final int column) {
        return fields.get(column).type();
    }

    public int rowCount() {
        return rowCount;
    }

    /** The rows changed by a statement that changes the store; -1 for a query. */
    public long updateCount() {
        return updateCount;
    }

    /** True when the query had more rows than the {@code maxRows} it was run with. */
    public boolean truncated() {
        return truncated;
    }

    public boolean isNull(final int row, final int column) {
        final int p = pageOf(row);
        return pages.get(p).columns[column].isNull(row - starts[p]);
    }

    /** LONG and TIMESTAMP (microseconds); {@link Vector#NULL_LONG} for NULL. */
    public long getLong(final int row, final int column) {
        final int p = pageOf(row);
        return pages.get(p).columns[column].longs[row - starts[p]];
    }

    /** DOUBLE; NaN for NULL. */
    public double getDouble(final int row, final int column) {
        final int p = pageOf(row);
        return pages.get(p).columns[column].doubles[row - starts[p]];
    }

    public boolean getBoolean(final int row, final int column) {
        final int p = pageOf(row);
        return pages.get(p).columns[column].booleans[row - starts[p]];
    }

    /** SYMBOL and VARCHAR; null for NULL. */
    public String getText(final int row, final int column) {
        final int p = pageOf(row);
        return pages.get(p).columns[column].texts[row - starts[p]];
    }

    // pages are full but the last, as operators fill them to capacity, so the division is right but for short pages
    private int pageOf(final int row) {
        final int guess = Math.min(row / Page.CAPACITY, pages.size() - 1);
        if (guess >= 0 && starts[guess] <= row && row < starts[guess + 1]) {
            return guess;
        }
        int lo = 0;
        int hi = pages.size() - 1;
        while (lo < hi) {
            final int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= row) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
