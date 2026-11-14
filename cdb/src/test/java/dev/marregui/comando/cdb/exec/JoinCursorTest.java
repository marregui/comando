package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;

import static dev.marregui.comando.cdb.exec.RowsFakes.SIZES;
import static dev.marregui.comando.cdb.exec.RowsFakes.drain;
import static dev.marregui.comando.cdb.exec.RowsFakes.field;
import static dev.marregui.comando.cdb.exec.RowsFakes.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [JoinCursor] joins the left's rows to the right's: by hash for one `=` between a column of each side, else by a
/// nested loop over the condition; a left join pads NULLs, and NULL keys match as in QuestDB.
class JoinCursorTest {

    private static final List<Field> LEFT = List.of(field("k", CdbType.LONG), field("i", CdbType.LONG));
    private static final List<Field> RIGHT = List.of(field("k", CdbType.LONG), field("s", CdbType.VARCHAR));
    private static final List<Field> BOTH = List.of(LEFT.get(0), LEFT.get(1), field("k1", CdbType.LONG),
            RIGHT.get(1));

    /// `n` left rows `(i % 50, i)`, NULL key for `i % 17 == 0`.
    private static RowsFakes.ListCursor left(final int n) {
        return RowsFakes.cursor(LEFT, n, 700, r -> new Object[] {r % 17 == 0 ? null : (long) (r % 50), (long) r});
    }

    /// `n` right rows `(j % 40, "r" + j)`, NULL key for `j % 13 == 0`.
    private static RowsFakes.ListCursor right(final int n) {
        return RowsFakes.cursor(RIGHT, n, 900, r -> new Object[] {r % 13 == 0 ? null : (long) (r % 40), "r" + r});
    }

    /// The join by definition: left order, then right order, NULL keys equal, a left join padding NULLs.
    private static List<List<Object>> expected(final int n, final int m, final boolean leftJoin) {
        final List<List<Object>> l = drain(left(n)).rows();
        final List<List<Object>> r = drain(right(m)).rows();
        final List<List<Object>> out = new ArrayList<>();
        for (final List<Object> a : l) {
            boolean matched = false;
            for (final List<Object> b : r) {
                if (Objects.equals(a.get(0), b.get(0))) {
                    final List<Object> row = new ArrayList<>(a);
                    row.addAll(b);
                    out.add(row);
                    matched = true;
                }
            }
            if (!matched && leftJoin) {
                final List<Object> row = new ArrayList<>(a);
                row.add(null);
                row.add(null);
                out.add(row);
            }
        }
        return out;
    }

    /// `left.k = right.k` as a nested-loop condition, NULL equal to NULL, in one vector reused on every call.
    private static VectorFn keysEqual() {
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return page -> {
            for (int i = 0; i < page.size; i++) {
                out.booleans[i] = page.columns[0].longs[i] == page.columns[2].longs[i];
            }
            return out;
        };
    }

    private static Bound equal(final int a, final int b) {
        return new Bound.Call("=", List.of(new Bound.Column(a, CdbType.LONG), new Bound.Column(b, CdbType.LONG)),
                CdbType.BOOLEAN);
    }

    /// The hash join, either way round, and the nested loop give the join's definition, inner and left, at every
    /// size of either side, in packed pages, both inputs closed.
    @Test
    void joinsByHashAndByLoopAtEverySize() {
        for (final int n : SIZES) {
            for (final int m : new int[] {0, 1, 1025}) {
                for (final String kind : List.of("inner", "left")) {
                    final List<List<Object>> expected = expected(n, m, "left".equals(kind));
                    final String at = kind + " n=" + n + " m=" + m;
                    check(JoinCursor.open(left(n), right(m), kind, equal(0, 2), 0, BOTH), expected, at + " hash");
                    check(JoinCursor.open(left(n), right(m), kind, equal(2, 0), 0, BOTH), expected, at + " swapped");
                    check(new JoinCursor(left(n), right(m), kind, keysEqual(), BOTH), expected, at + " loop");
                }
            }
        }
    }

    private static void check(final JoinCursor cursor, final List<List<Object>> expected, final String at) {
        final RowsFakes.Drained out = drain(cursor);
        assertThat(out.rows()).as(at).isEqualTo(expected);
        assertThat(out.packed()).as(at).isTrue();
    }

    /// A cross join gives every pair, left order then right order, over page ends; an empty side gives nothing.
    @Test
    void crossJoinsEveryPair() {
        for (final int[] sizes : new int[][] {{1025, 3}, {3, 1025}, {0, 5}, {5, 0}}) {
            final RowsFakes.ListCursor l = left(sizes[0]);
            final RowsFakes.ListCursor r = right(sizes[1]);
            final List<List<Object>> out = drain(new JoinCursor(l, r, "cross", null, BOTH)).rows();
            assertThat(out).hasSize(sizes[0] * sizes[1]);
            if (!out.isEmpty()) {
                assertThat(out.get(sizes[1] + 1)).isEqualTo(rows(new Object[] {1L, 1L, 1L, "r1"}).getFirst());
            }
            assertThat(l.closed() && r.closed()).isTrue();
        }
    }

    /// A left join pads the right's columns with NULL for every type.
    @Test
    void padsNullsInALeftJoin() {
        final List<Field> right = List.of(field("k", CdbType.LONG), field("d", CdbType.DOUBLE),
                field("s", CdbType.SYMBOL), field("t", CdbType.TIMESTAMP));
        final List<Field> fields = List.of(LEFT.get(0), LEFT.get(1), field("k1", CdbType.LONG),
                field("d", CdbType.DOUBLE), field("s", CdbType.SYMBOL), field("t", CdbType.TIMESTAMP));
        final RowsFakes.ListCursor l = RowsFakes.cursor(LEFT, 2, 2, r -> new Object[] {(long) r, (long) r});
        final RowsFakes.ListCursor r = RowsFakes.cursor(right, 1, 1, _ -> new Object[] {1L, 0.5, "a", 7L});
        assertThat(drain(JoinCursor.open(l, r, "left", equal(0, 2), 0, fields)).rows()).isEqualTo(rows(
                new Object[] {0L, 0L, null, null, null, null}, new Object[] {1L, 1L, 1L, 0.5, "a", 7L}));
    }

    /// An inner join with an empty right reads no left page.
    @Test
    void anEmptyRightEndsAnInnerJoinAtOnce() {
        final RowsFakes.ListCursor l = left(3000);
        assertThat(drain(JoinCursor.open(l, right(0), "inner", equal(0, 2), 0, BOTH)).pages()).isEmpty();
        assertThat(l.pulled()).isZero();
        assertThat(l.closed()).isTrue();
    }

    /// An unknown kind, or fields that are not the left's then the right's, are refused and both inputs closed.
    @Test
    void refusesWhatDoesNotLineUp() {
        final RowsFakes.ListCursor l = left(3);
        final RowsFakes.ListCursor r = right(3);
        assertThatThrownBy(() -> new JoinCursor(l, r, "full", null, BOTH)).isInstanceOf(CdbException.class)
                .hasMessageContaining("kind=full");
        assertThat(l.closed() && r.closed()).isTrue();
        assertThatThrownBy(() -> new JoinCursor(left(3), right(3), "inner", null, LEFT))
                .isInstanceOf(CdbException.class).hasMessageContaining("fields=2");
    }
}
