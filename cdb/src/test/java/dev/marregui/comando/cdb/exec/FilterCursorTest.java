package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.VectorFn;

import static dev.marregui.comando.cdb.exec.RowsFakes.SIZES;
import static dev.marregui.comando.cdb.exec.RowsFakes.drain;
import static dev.marregui.comando.cdb.exec.RowsFakes.longs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [FilterCursor] keeps the rows its predicate holds for, packed into full pages, never an empty one.
class FilterCursorTest {

    /// A predicate over column 0 that reuses one vector on every call, as a compiled function may.
    private static VectorFn where(final LongPredicate test) {
        final Vector mask = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return page -> {
            for (int i = 0; i < page.size; i++) {
                mask.booleans[i] = test.test(page.columns[0].longs[i]);
            }
            return mask;
        };
    }

    private static List<Long> expected(final int n, final LongPredicate test) {
        final List<Long> out = new ArrayList<>();
        for (long v = 0; v < n; v++) {
            if (test.test(v)) {
                out.add(v);
            }
        }
        return out;
    }

    /// Every third row, and every row but one, come out in order and packed, from input pages of 1024 and of 700.
    @Test
    void keepsMatchingRowsInFullPagesAtEverySize() {
        final List<LongPredicate> tests = List.of(v -> v % 3 == 0, v -> v != 500);
        for (final int n : SIZES) {
            for (final int pageSize : new int[] {Page.CAPACITY, 700}) {
                for (final LongPredicate test : tests) {
                    final RowsFakes.Drained out = drain(new FilterCursor(longs(n, pageSize), where(test)));
                    assertThat(out.longs()).as("n=%d page=%d", n, pageSize).isEqualTo(expected(n, test));
                    assertThat(out.packed()).as("n=%d page=%d", n, pageSize).isTrue();
                }
            }
        }
    }

    /// A predicate that holds for no row gives no page at all, not an empty one.
    @Test
    void keepsNothingWithoutAnEmptyPage() {
        assertThat(drain(new FilterCursor(longs(3000, Page.CAPACITY), where(_ -> false))).pages()).isEmpty();
    }

    /// A full input page whose every row is kept is handed on as it is, the same page.
    @Test
    void passesAWhollyKeptFullPageThrough() {
        final RowsFakes.ListCursor child = longs(2048, Page.CAPACITY);
        final RowsFakes.Drained out = drain(new FilterCursor(child, where(_ -> true)));
        assertThat(out.pages()).containsExactlyElementsOf(child.pages());
        assertThat(child.closed()).isTrue();
    }

    /// A NULL value fails a comparison, so a predicate that is false for NULL drops its row.
    @Test
    void dropsTheRowsWherePredicateIsFalseForNull() {
        final RowsFakes.ListCursor child = RowsFakes.cursor(List.of(RowsFakes.field("x", CdbType.LONG)), 1025,
                Page.CAPACITY, r -> new Object[] {r % 2 == 0 ? null : (long) r});
        final RowsFakes.Drained out = drain(new FilterCursor(child, where(v -> v != Vector.NULL_LONG)));
        assertThat(out.longs()).hasSize(512).allMatch(v -> v % 2 == 1);
    }

    /// A predicate that is not BOOLEAN is refused.
    @Test
    void refusesAPredicateThatIsNotBoolean() {
        final FilterCursor cursor = new FilterCursor(longs(10, Page.CAPACITY), page -> page.columns[0]);
        assertThatThrownBy(cursor::next).isInstanceOf(CdbException.class).hasMessageContaining("type=LONG");
        cursor.close();
    }
}
