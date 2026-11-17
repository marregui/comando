package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;

import static org.assertj.core.api.Assertions.assertThat;

/// `x IN (constants)` over LONG and TIMESTAMP searches the sorted constants instead of comparing each row with each
/// value; these tests hold it to the per-value path, which a list of columns holding the same constants on every row
/// takes, over seeded random pages with NULL rows, NULL constants, duplicates and the ends of the `long` range.
class LongInListTest {

    private static final long[] EDGES = {Long.MIN_VALUE + 1, -1, 0, 1, Long.MAX_VALUE};
    private static final long NOW_MICROS = 1_794_000_000_000_000L;
    private static final int ROUNDS = 300;

    /// For random pages and lists, `in` and `not_in` with constants give, row by row, the booleans the per-value path
    /// gives with the same values in columns; one compiled function evaluates two pages, so the values read on the
    /// first page serve the second.
    @Test
    void constantsMatchThePerValuePath() {
        final TwoSeedRandom rnd = TwoSeedRandom.of("cdb.inlist", "constantsMatchThePerValuePath");
        for (int round = 0; round < ROUNDS; round++) {
            final CdbType type = rnd.percent(50) ? CdbType.LONG : CdbType.TIMESTAMP;
            final boolean negated = rnd.percent(50);
            final int listed = rnd.between(1, 40);
            final Long[] list = new Long[listed];
            for (int j = 0; j < listed; j++) {
                list[j] = rnd.percent(5) ? null : value(rnd);
            }
            final List<Bound> constants = new ArrayList<>();
            final List<Bound> columns = new ArrayList<>();
            constants.add(new Bound.Column(0, type));
            columns.add(new Bound.Column(0, type));
            for (int j = 0; j < listed; j++) {
                constants.add(new Bound.Constant(list[j], type));
                columns.add(new Bound.Column(1 + j, type));
            }
            final String function = negated ? "not_in" : "in";
            final VectorFn fast = Functions.compile(new Bound.Call(function, constants, CdbType.BOOLEAN), NOW_MICROS);
            final VectorFn slow = Functions.compile(new Bound.Call(function, columns, CdbType.BOOLEAN), NOW_MICROS);
            for (int p = 0; p < 2; p++) {
                final Page page = page(rnd, type, list);
                assertThat(rows(fast, page)).as("round %d page %d %s %s", round, p, type, function)
                        .containsExactlyElementsOf(rows(slow, page));
            }
        }
    }

    /// A NULL row is in a list only when the list holds a NULL; `not_in` is its complement on every row.
    @Test
    void nullRowsFollowTheNullConstant() {
        final Long[] withNull = {7L, null};
        final Page page = new Page(new Vector[] {longs(CdbType.LONG, new Long[] {7L, null, 8L})}, 3);
        assertThat(rows(compile("in", CdbType.LONG, withNull), page)).containsExactly(true, true, false);
        assertThat(rows(compile("not_in", CdbType.LONG, withNull), page)).containsExactly(false, false, true);
        assertThat(rows(compile("in", CdbType.LONG, new Long[] {7L}), page)).containsExactly(true, false, false);
        assertThat(rows(compile("not_in", CdbType.LONG, new Long[] {7L}), page)).containsExactly(false, true, true);
    }

    private static VectorFn compile(final String function, final CdbType type, final Long[] list) {
        final List<Bound> args = new ArrayList<>();
        args.add(new Bound.Column(0, type));
        for (final Long v : list) {
            args.add(new Bound.Constant(v, type));
        }
        return Functions.compile(new Bound.Call(function, args, CdbType.BOOLEAN), NOW_MICROS);
    }

    private static long value(final TwoSeedRandom rnd) {
        return rnd.percent(10) ? EDGES[rnd.nextInt(EDGES.length)] : rnd.between(-20, 20);
    }

    private static Page page(final TwoSeedRandom rnd, final CdbType type, final Long[] list) {
        final int size = rnd.between(1, Page.CAPACITY);
        final Vector[] columns = new Vector[1 + list.length];
        final Long[] xs = new Long[size];
        for (int i = 0; i < size; i++) {
            xs[i] = rnd.percent(10) ? null : value(rnd);
        }
        columns[0] = longs(type, xs);
        for (int j = 0; j < list.length; j++) {
            final Long[] same = new Long[size];
            Arrays.fill(same, list[j]);
            columns[1 + j] = longs(type, same);
        }
        return new Page(columns, size);
    }

    private static Vector longs(final CdbType type, final Long[] values) {
        final Vector v = Vector.of(type, Page.CAPACITY);
        for (int i = 0; i < values.length; i++) {
            v.setLong(i, values[i] == null ? Vector.NULL_LONG : values[i]);
        }
        return v;
    }

    private static List<Boolean> rows(final VectorFn fn, final Page page) {
        final Vector out = fn.eval(page);
        final List<Boolean> rows = new ArrayList<>();
        for (int i = 0; i < page.size; i++) {
            rows.add(out.getBoolean(i));
        }
        return rows;
    }
}
