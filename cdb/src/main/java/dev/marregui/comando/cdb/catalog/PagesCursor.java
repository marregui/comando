package dev.marregui.comando.cdb.catalog;

import java.util.List;

import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.exec.Page;

/** A cursor over pages built in advance. */
final class PagesCursor implements Cursor {

    private final List<Field> fields;
    private final List<Page> pages;
    private int next;

    PagesCursor(final List<Field> fields, final List<Page> pages) {
        this.fields = fields;
        this.pages = pages;
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        return next < pages.size() ? pages.get(next++) : null;
    }

    @Override
    public void close() {
        next = pages.size();
    }
}
