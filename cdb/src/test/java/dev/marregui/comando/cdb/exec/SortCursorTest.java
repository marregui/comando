package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;

import static dev.marregui.comando.cdb.exec.RowsFakes.SIZES;
import static dev.marregui.comando.cdb.exec.RowsFakes.drain;
import static dev.marregui.comando.cdb.exec.RowsFakes.field;
import static dev.marregui.comando.cdb.exec.RowsFakes.range;
import static dev.marregui.comando.cdb.exec.RowsFakes.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// [SortCursor] sorts stably by its keys, NULLs first ascending and last descending, text by `String.compareTo`.
class SortCursorTest {

    private static final List<Field> KEY_AND_INDEX = List.of(field("k", CdbType.LONG), field("i", CdbType.LONG));

    /// Rows `(n - 1 - i)` come out ascending, packed, and the child is closed once the input is read.
    @Test
    void sortsAtEverySize() {
        for (final int n : SIZES) {
            final RowsFakes.ListCursor child = RowsFakes.cursor(List.of(field("x", CdbType.LONG)), n, 700,
                    r -> new Object[] {(long) (n - 1 - r)});
            final SortCursor cursor = new SortCursor(child, List.of(new Plan.SortKey(0, false)));
            final RowsFakes.Drained out = drain(cursor);
            assertThat(out.longs()).as("n=%d", n).isEqualTo(range(0, n));
            assertThat(out.packed()).as("n=%d", n).isTrue();
            assertThat(child.closed()).isTrue();
        }
    }

    /// Rows with equal keys keep their input order, ascending and descending, over 3000 rows of 7 keys.
    @Test
    void isStable() {
        for (final boolean descending : new boolean[] {false, true}) {
            final RowsFakes.ListCursor child = RowsFakes.cursor(KEY_AND_INDEX, 3000, Page.CAPACITY,
                    r -> new Object[] {(long) (r * 5 % 7), (long) r});
            final List<List<Object>> out = drain(new SortCursor(child, List.of(new Plan.SortKey(0, descending))))
                    .rows();
            final List<List<Object>> expected = new ArrayList<>(drain(RowsFakes.cursor(KEY_AND_INDEX, 3000,
                    Page.CAPACITY, r -> new Object[] {(long) (r * 5 % 7), (long) r})).rows());
            final Comparator<List<Object>> byKey = Comparator.comparingLong(row -> (Long) row.getFirst());
            expected.sort(descending ? byKey.reversed() : byKey);
            assertThat(out).isEqualTo(expected);
        }
    }

    /// NULL sorts first ascending and last descending, for LONG, DOUBLE and text keys.
    @Test
    void placesNullsFirstAscendingAndLastDescending() {
        final List<Field> fields = List.of(field("l", CdbType.LONG), field("d", CdbType.DOUBLE),
                field("s", CdbType.VARCHAR));
        final Object[][] input = {{2L, 2.0, "b"}, {null, null, null}, {1L, -1.0, "a"}};
        for (int column = 0; column < 3; column++) {
            final int c = column;
            final List<List<Object>> up = drain(new SortCursor(RowsFakes.cursor(fields, 3, Page.CAPACITY,
                    r -> input[r]), List.of(new Plan.SortKey(c, false)))).rows();
            final List<List<Object>> down = drain(new SortCursor(RowsFakes.cursor(fields, 3, Page.CAPACITY,
                    r -> input[r]), List.of(new Plan.SortKey(c, true)))).rows();
            assertThat(up).as("column %d", c).isEqualTo(rows(input[1], input[2], input[0]));
            assertThat(down).as("column %d", c).isEqualTo(rows(input[0], input[2], input[1]));
        }
    }

    /// Text sorts by `String.compareTo`: upper case before lower case; false sorts before true.
    @Test
    void sortsTextByCompareToAndFalseBeforeTrue() {
        final List<Field> fields = List.of(field("s", CdbType.SYMBOL), field("b", CdbType.BOOLEAN));
        final Object[][] input = {{"b", true}, {"B", false}, {"a", true}, {"A", false}};
        final List<List<Object>> byText = drain(new SortCursor(RowsFakes.cursor(fields, 4, Page.CAPACITY,
                r -> input[r]), List.of(new Plan.SortKey(0, false)))).rows();
        assertThat(byText).isEqualTo(rows(input[3], input[1], input[2], input[0]));
        final List<List<Object>> byFlag = drain(new SortCursor(RowsFakes.cursor(fields, 4, Page.CAPACITY,
                r -> input[r]), List.of(new Plan.SortKey(1, false)))).rows();
        assertThat(byFlag).isEqualTo(rows(input[1], input[3], input[0], input[2]));
    }

    /// A second key orders the rows the first leaves equal, each in its own direction.
    @Test
    void ordersByEveryKeyInTurn() {
        final Object[][] input = {{1L, 1L}, {0L, 1L}, {1L, 2L}, {0L, 2L}};
        final List<List<Object>> out = drain(new SortCursor(RowsFakes.cursor(KEY_AND_INDEX, 4, Page.CAPACITY,
                r -> input[r]), List.of(new Plan.SortKey(0, false), new Plan.SortKey(1, true)))).rows();
        assertThat(out).isEqualTo(rows(input[3], input[1], input[2], input[0]));
    }
}
