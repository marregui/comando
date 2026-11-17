package dev.marregui.comando.cdb.catalog;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.QueryContext;
import dev.marregui.comando.cdb.exec.Vector;

/** {@code long_sequence(n)}: the column {@code x}, 1 to n, a page at a time; nothing is held beyond the counters. */
final class SequenceCursor implements Cursor {

    static final List<Field> FIELDS = List.of(new Field("x", CdbType.LONG));

    private final QueryContext ctx;
    private long remaining;
    private long value = 1;

    SequenceCursor(final long count, final QueryContext ctx) {
        this.remaining = count;
        this.ctx = ctx;
    }

    @Override
    public List<Field> fields() {
        return FIELDS;
    }

    @Override
    public Page next() {
        if (remaining <= 0) {
            return null;
        }
        ctx.check();
        final int n = (int) Math.min(Page.CAPACITY, remaining);
        final Page page = Page.allocate(FIELDS);
        final Vector x = page.columns[0];
        for (int i = 0; i < n; i++) {
            x.setLong(i, value++);
        }
        page.size = n;
        remaining -= n;
        return page;
    }

    @Override
    public void close() {
        remaining = 0;
    }
}
