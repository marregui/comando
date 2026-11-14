package dev.marregui.comando.cdb.exec;

import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;

/**
 * Rows held in memory as packed pages, every page full but the last, so row {@code i} is row
 * {@code i & Rows.ROW_MASK} of page {@code i >>> Rows.PAGE_SHIFT}. Its pages stay its own: an operator reads them but
 * never hands them out.
 */
final class RowsBuffer {

    private final List<Field> fields;
    private Page[] pages = new Page[4];
    private int pageCount;
    private int rowCount;

    RowsBuffer(final List<Field> fields) {
        this.fields = fields;
    }

    /** Adds every row of {@code source}. */
    void addAll(final Page source) {
        int from = 0;
        while (from < source.size) {
            final Page page = room();
            final int count = Math.min(source.size - from, Page.CAPACITY - page.size);
            final Vector[] dst = page.columns;
            final Vector[] src = source.columns;
            for (int c = 0; c < dst.length; c++) {
                Rows.copyRange(src[c], from, dst[c], page.size, count);
            }
            page.size += count;
            rowCount += count;
            from += count;
        }
    }

    /** Adds row {@code row} of {@code source}; returns its index. */
    int add(final Page source, final int row) {
        final Page page = room();
        final Vector[] dst = page.columns;
        final Vector[] src = source.columns;
        final int to = page.size;
        for (int c = 0; c < dst.length; c++) {
            dst[c].copy(src[c], row, to);
        }
        page.size = to + 1;
        return rowCount++;
    }

    int rowCount() {
        return rowCount;
    }

    int pageCount() {
        return pageCount;
    }

    /** Page {@code index} of the buffer, not of a row. */
    Page page(final int index) {
        return pages[index];
    }

    /** The page holding row {@code row}. */
    Page pageOf(final int row) {
        return pages[row >>> Rows.PAGE_SHIFT];
    }

    private Page room() {
        if (pageCount > 0 && pages[pageCount - 1].size < Page.CAPACITY) {
            return pages[pageCount - 1];
        }
        if (rowCount > Integer.MAX_VALUE - Page.CAPACITY) {
            throw new CdbException("cannot hold more rows in memory [rows=" + rowCount + "]", -1);
        }
        if (pageCount == pages.length) {
            pages = Arrays.copyOf(pages, pages.length * 2);
        }
        final Page page = Page.allocate(fields);
        pages[pageCount++] = page;
        return page;
    }
}
