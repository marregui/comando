package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;

/** SELECT DISTINCT: each distinct row once, in first-seen order; streams, emitting the new rows of each input page. */
final class DistinctCursor implements Cursor {

    private final Cursor child;
    private final List<Field> fields;
    private final AggKeys seen;
    private boolean closed;

    DistinctCursor(final Cursor child) {
        this.child = child;
        try {
            fields = child.fields();
            final List<CdbType> types = new ArrayList<>();
            for (final Field field : fields) {
                types.add(field.type());
            }
            seen = new AggKeys(types);
        } catch (final RuntimeException e) {
            child.close();
            throw e;
        }
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        Page page;
        while ((page = child.next()) != null) {
            final Page out = Page.allocate(fields);
            for (int i = 0; i < page.size; i++) {
                final int before = seen.size();
                if (seen.groupOf(page.columns, i) == before) {
                    for (int c = 0; c < out.columns.length; c++) {
                        out.columns[c].copy(page.columns[c], i, out.size);
                    }
                    out.size++;
                }
            }
            if (out.size > 0) {
                return out;
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            child.close();
        }
    }
}
