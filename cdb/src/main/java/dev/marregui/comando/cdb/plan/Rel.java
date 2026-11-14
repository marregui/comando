package dev.marregui.comando.cdb.plan;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;

/**
 * A plan with what name resolution needs: each output column's qualifier (the table name or alias it came from, null
 * when it has none) and name, and the index of the designated timestamp among them, -1 when there is none.
 */
record Rel(Plan plan, List<Col> cols, int timestamp) {

    Rel {
        cols = List.copyOf(cols);
    }

    /** The plan's fields, all qualified by {@code qualifier}. */
    static Rel of(final Plan plan, final String qualifier, final int timestamp) {
        final List<Col> cols = new ArrayList<>();
        for (final Field f : plan.fields()) {
            cols.add(new Col(qualifier, f.name(), f.type()));
        }
        return new Rel(plan, cols, timestamp);
    }

    /** The same columns under {@code qualifier}, when it is not null. */
    Rel qualified(final String qualifier) {
        if (qualifier == null) {
            return this;
        }
        final List<Col> out = new ArrayList<>();
        for (final Col c : cols) {
            out.add(new Col(qualifier, c.name(), c.type()));
        }
        return new Rel(plan, out, timestamp);
    }

    int width() {
        return cols.size();
    }

    /**
     * The index of the column {@code parts} names ({@code a} or {@code t.a}), matched without regard to case; -1 when
     * none does; an error when an unqualified name matches columns of two qualifiers.
     */
    int find(final List<String> parts, final int pos) {
        final String name = parts.getLast();
        final String qualifier = parts.size() > 1 ? parts.get(parts.size() - 2) : null;
        int found = -1;
        for (int i = 0; i < cols.size(); i++) {
            final Col c = cols.get(i);
            final boolean match = c.name().equalsIgnoreCase(name)
                    && (qualifier == null || qualifier.equalsIgnoreCase(c.qualifier()));
            if (!match) {
                continue;
            }
            if (found >= 0) {
                throw new CdbException("cannot resolve ambiguous column [column=" + String.join(".", parts) + ']', pos);
            }
            found = i;
        }
        return found;
    }

    /** {@link #find}, an error when no column matches. */
    int resolve(final List<String> parts, final int pos) {
        final int i = find(parts, pos);
        if (i < 0) {
            throw new CdbException("cannot find column [column=" + String.join(".", parts) + ']', pos);
        }
        return i;
    }

    /** One output column of a {@link Rel}. */
    record Col(String qualifier, String name, CdbType type) {
    }
}
