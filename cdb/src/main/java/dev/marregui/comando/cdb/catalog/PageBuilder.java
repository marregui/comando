package dev.marregui.comando.cdb.catalog;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.exec.Page;

/**
 * Collects rows value by value into pages of {@link Page#CAPACITY}: the caller sets every column of a row, then ends it.
 * The listings are small and held whole, so the pages are built eagerly.
 */
final class PageBuilder {

    private final List<Field> fields;
    private final List<Page> pages = new ArrayList<>();
    private Page current;

    PageBuilder(final List<Field> fields) {
        this.fields = fields;
        this.current = Page.allocate(fields);
    }

    void text(final int column, final String value) {
        current.columns[column].texts[current.size] = value;
    }

    void integer(final int column, final long value) {
        current.columns[column].longs[current.size] = value;
    }

    void real(final int column, final double value) {
        current.columns[column].doubles[current.size] = value;
    }

    void bool(final int column, final boolean value) {
        current.columns[column].booleans[current.size] = value;
    }

    /** Completes the row being written; a full page is set aside and a new one started. */
    void endRow() {
        current.size++;
        if (current.size == Page.CAPACITY) {
            pages.add(current);
            current = Page.allocate(fields);
        }
    }

    /** The pages written; the last holds the remainder and an empty one is never returned. */
    List<Page> finish() {
        if (current.size > 0) {
            pages.add(current);
            current = Page.allocate(fields);
        }
        return pages;
    }
}
