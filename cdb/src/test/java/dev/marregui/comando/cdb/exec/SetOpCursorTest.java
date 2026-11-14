package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.exec.RowsFakes.SIZES;
import static dev.marregui.comando.cdb.exec.RowsFakes.drain;
import static dev.marregui.comando.cdb.exec.RowsFakes.field;
import static dev.marregui.comando.cdb.exec.RowsFakes.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [SetOpCursor] combines two inputs on whole rows, NULL equal to NULL as QuestDB compares them; all but `union all`
/// return distinct rows in first-seen order.
class SetOpCursorTest {

    private static final List<Field> XS = List.of(field("x", CdbType.LONG), field("s", CdbType.VARCHAR));

    /// Rows `(v / 2, "v" + v % 3)` for `v` in `[from, from + n)`: every row twice in a row, NULL text for `v % 5 == 0`.
    private static RowsFakes.ListCursor pairs(final int from, final int n) {
        return RowsFakes.cursor(XS, n, 700, r -> {
            final long v = from + r;
            return new Object[] {v / 2, v % 5 == 0 ? null : "v" + v / 2 % 3};
        });
    }

    /// The distinct rows of `inputs` in first-seen order, by list equality (null equal to null).
    private static List<List<Object>> distinct(final List<List<Object>> inputs) {
        final List<List<Object>> out = new ArrayList<>();
        for (final List<Object> row : inputs) {
            if (!out.contains(row)) {
                out.add(row);
            }
        }
        return out;
    }

    /// Each operation matches its definition over lists, at every size, with duplicates within and across inputs.
    @Test
    void matchesTheDefinitionsAtEverySize() {
        for (final int n : SIZES) {
            final List<List<Object>> l = drain(pairs(0, n)).rows();
            final List<List<Object>> r = drain(pairs(n / 2, n)).rows();
            final List<List<Object>> all = new ArrayList<>(l);
            all.addAll(r);
            final List<List<Object>> except = new ArrayList<>(distinct(l));
            except.removeIf(r::contains);
            final List<List<Object>> intersect = new ArrayList<>(distinct(l));
            intersect.removeIf(row -> !r.contains(row));
            check("union all", n, all);
            check("union", n, distinct(all));
            check("except", n, except);
            check("intersect", n, intersect);
        }
    }

    private static void check(final String op, final int n, final List<List<Object>> expected) {
        final RowsFakes.ListCursor left = pairs(0, n);
        final RowsFakes.ListCursor right = pairs(n / 2, n);
        final RowsFakes.Drained out = drain(new SetOpCursor(op, left, right));
        assertThat(out.rows()).as("%s n=%d", op, n).isEqualTo(expected);
        assertThat(left.closed() && right.closed()).as("%s n=%d", op, n).isTrue();
        if (!"union all".equals(op)) {
            assertThat(out.packed()).as("%s n=%d", op, n).isTrue();
        }
    }

    /// `union all` hands on the left's pages, then the right's, uncopied.
    @Test
    void unionAllPassesPagesThrough() {
        final RowsFakes.ListCursor left = pairs(0, 3000);
        final RowsFakes.ListCursor right = pairs(0, 1025);
        final List<Page> expected = new ArrayList<>(left.pages());
        expected.addAll(right.pages());
        assertThat(drain(new SetOpCursor("union all", left, right)).pages()).containsExactlyElementsOf(expected);
    }

    /// NULL equals NULL: one NULL row survives a union, and NULL rows match in except and intersect.
    @Test
    void comparesNullsAsEqual() {
        final List<Field> x = List.of(field("x", CdbType.DOUBLE));
        final Object[][] l = {{null}, {1.0}, {null}};
        final Object[][] r = {{null}, {2.0}};
        assertThat(drain(new SetOpCursor("union", RowsFakes.cursor(x, 3, 1, i -> l[i]),
                RowsFakes.cursor(x, 2, 1, i -> r[i]))).rows()).isEqualTo(rows(l[0], l[1], r[1]));
        assertThat(drain(new SetOpCursor("except", RowsFakes.cursor(x, 3, 1, i -> l[i]),
                RowsFakes.cursor(x, 2, 1, i -> r[i]))).rows()).isEqualTo(rows(l[1]));
        assertThat(drain(new SetOpCursor("intersect", RowsFakes.cursor(x, 3, 1, i -> l[i]),
                RowsFakes.cursor(x, 2, 1, i -> r[i]))).rows()).isEqualTo(rows(l[0]));
    }

    /// Inputs of different shapes, or an unknown operation, are refused and both inputs closed.
    @Test
    void refusesInputsThatDoNotLineUp() {
        final RowsFakes.ListCursor left = pairs(0, 3);
        final RowsFakes.ListCursor narrow = RowsFakes.longs(3, 3);
        assertThatThrownBy(() -> new SetOpCursor("union", left, narrow)).isInstanceOf(CdbException.class)
                .hasMessageContaining("left=2, right=1");
        assertThat(left.closed() && narrow.closed()).isTrue();
        final RowsFakes.ListCursor other = RowsFakes.cursor(List.of(field("x", CdbType.DOUBLE),
                field("s", CdbType.VARCHAR)), 1, 1, _ -> new Object[] {1.0, "a"});
        assertThatThrownBy(() -> new SetOpCursor("except", pairs(0, 3), other)).isInstanceOf(CdbException.class)
                .hasMessageContaining("column=x");
        assertThatThrownBy(() -> new SetOpCursor("minus", pairs(0, 3), pairs(0, 3)))
                .isInstanceOf(CdbException.class).hasMessageContaining("op=minus");
    }
}
