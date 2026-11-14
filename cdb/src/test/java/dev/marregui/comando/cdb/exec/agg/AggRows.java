package dev.marregui.comando.cdb.exec.agg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;

/// Rows in and out of the grouping cursors: a source cursor over boxed rows, and a drain of a cursor back to rows.
/// A value is a `Long` (LONG, TIMESTAMP), `Double`, `Boolean`, `String`, or `null` for NULL.
public final class AggRows {

    private AggRows() {
    }

    /// One row of values, NULLs allowed.
    public static List<Object> row(final Object... values) {
        return Arrays.asList(values);
    }

    /// A field of `name` and `type`.
    public static Field field(final String name, final CdbType type) {
        return new Field(name, type);
    }

    /// A cursor over `rows`, `pageSize` rows a page; [Source#closed] says whether it was closed.
    public static Source source(final List<Field> fields, final int pageSize, final List<List<Object>> rows) {
        return new Source(fields, pageSize, rows);
    }

    /// Every row of `cursor`, then closes it.
    public static List<List<Object>> drain(final Cursor cursor) {
        final List<List<Object>> rows = new ArrayList<>();
        try (cursor) {
            Page page;
            while ((page = cursor.next()) != null) {
                for (int i = 0; i < page.size; i++) {
                    final List<Object> row = new ArrayList<>();
                    for (final Vector column : page.columns) {
                        row.add(value(column, i));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static Object value(final Vector v, final int i) {
        if (v.isNull(i) && v.type != CdbType.BOOLEAN) {
            return null;
        }
        return switch (v.type) {
            case LONG, TIMESTAMP -> v.longs[i];
            case DOUBLE -> v.doubles[i];
            case BOOLEAN -> v.booleans[i];
            case SYMBOL, VARCHAR -> v.texts[i];
        };
    }

    /// A cursor over fixed rows.
    public static final class Source implements Cursor {

        private final List<Field> fields;
        private final int pageSize;
        private final List<List<Object>> rows;
        private boolean closed;
        private int next;

        private Source(final List<Field> fields, final int pageSize, final List<List<Object>> rows) {
            this.fields = fields;
            this.pageSize = pageSize;
            this.rows = rows;
        }

        /// True once closed.
        public boolean closed() {
            return closed;
        }

        @Override
        public List<Field> fields() {
            return fields;
        }

        @Override
        public Page next() {
            if (next >= rows.size()) {
                return null;
            }
            final Page page = Page.allocate(fields);
            while (page.size < pageSize && next < rows.size()) {
                final List<Object> row = rows.get(next++);
                for (int c = 0; c < fields.size(); c++) {
                    set(page.columns[c], page.size, row.get(c));
                }
                page.size++;
            }
            return page;
        }

        @Override
        public void close() {
            closed = true;
        }

        private static void set(final Vector v, final int i, final Object value) {
            switch (value) {
                case null -> v.setNull(i);
                case Long l when v.type == CdbType.DOUBLE -> v.doubles[i] = l;
                case Long l -> v.longs[i] = l;
                case Double d -> v.doubles[i] = d;
                case Boolean b -> v.booleans[i] = b;
                case String s -> v.texts[i] = s;
                default -> throw new IllegalArgumentException("cannot hold this value [value=" + value + ']');
            }
        }
    }
}
