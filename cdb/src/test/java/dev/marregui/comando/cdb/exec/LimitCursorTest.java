package dev.marregui.comando.cdb.exec;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.marregui.comando.cdb.CdbException;

import static dev.marregui.comando.cdb.exec.RowsFakes.SIZES;
import static dev.marregui.comando.cdb.exec.RowsFakes.drain;
import static dev.marregui.comando.cdb.exec.RowsFakes.longs;
import static dev.marregui.comando.cdb.exec.RowsFakes.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [LimitCursor] keeps rows `[lo, hi)`, negative bounds from the end, as QuestDB's `LIMIT` does on
/// `long_sequence(10)` (values 1 to 10, here 0 to 9).
class LimitCursorTest {

    /// Each row is a `LIMIT` QuestDB 9.x answered on `long_sequence(10)`: its bounds and its first and last value,
    /// less one, `-1, -1` for none. `LIMIT -n` is `(-n, 0)`; `LIMIT a, b` with `a > b` swaps them.
    @ParameterizedTest(name = "lo={0} hi={1}")
    @CsvSource({
        "0, 3, 0, 2",
        "-3, 0, 7, 9",
        "2, 4, 2, 3",
        "-4, -1, 6, 8",
        "3, -2, 3, 7",
        "4, 2, 2, 3",
        "0, 0, -1, -1",
        "-20, 0, 0, 9",
        "-2, -5, 5, 7",
        "3, -20, -1, -1",
        "8, 20, 8, 9",
        "0, -3, 0, 6",
        "-20, -8, 0, 1",
        "5, 0, 0, 4",
        "-3, -3, -1, -1",
    })
    void matchesQuestDb(final long lo, final long hi, final long first, final long last) {
        final List<Long> out = drain(new LimitCursor(longs(10, 3), lo, hi)).longs();
        assertThat(out).isEqualTo(first < 0 ? List.of() : range(first, last + 1));
    }

    /// `LIMIT -n, m` with `m > 0` is refused, as QuestDB refuses it, and the child is closed.
    @Test
    void refusesANegativeLowAndPositiveHigh() {
        final RowsFakes.ListCursor child = longs(10, 3);
        assertThatThrownBy(() -> new LimitCursor(child, -2, 3)).isInstanceOf(CdbException.class)
                .hasMessageContaining("[lo=-2, hi=3]");
        assertThat(child.closed()).isTrue();
    }

    /// Windows across page ends, from the start and from the end, come out whole and packed at every size.
    @Test
    void slicesAcrossPagesAtEverySize() {
        final long[][] bounds = {{0, 1025}, {1, 1024}, {1023, 2049}, {-1025, 0}, {-1, 0}, {0, -1}, {-2000, -1000}};
        for (final int n : SIZES) {
            for (final int pageSize : new int[] {Page.CAPACITY, 700}) {
                for (final long[] b : bounds) {
                    final long lo = b[0] < 0 ? Math.max(0, n + b[0]) : Math.min(n, b[0]);
                    final long hi = b[1] < 0 ? Math.max(0, n + b[1]) : b[1] == 0 ? n : Math.min(n, b[1]);
                    final RowsFakes.Drained out = drain(new LimitCursor(longs(n, pageSize), b[0], b[1]));
                    assertThat(out.longs()).as("n=%d page=%d lo=%d hi=%d", n, pageSize, b[0], b[1])
                            .isEqualTo(range(lo, Math.max(lo, hi)));
                    assertThat(out.packed()).as("n=%d page=%d lo=%d hi=%d", n, pageSize, b[0], b[1]).isTrue();
                }
            }
        }
    }

    /// With non-negative bounds the child is pulled only until the window is full, then closed.
    @Test
    void stopsPullingOnceSatisfied() {
        final RowsFakes.ListCursor child = longs(3000, Page.CAPACITY);
        final LimitCursor cursor = new LimitCursor(child, 0, 10);
        final Page first = cursor.next();
        assertThat(first.size).isEqualTo(10);
        assertThat(cursor.next()).isNull();
        assertThat(child.pulled()).isEqualTo(1);
        assertThat(child.closed()).isTrue();
        cursor.close();
    }

    /// A full input page wholly inside the window is handed on as it is.
    @Test
    void passesAWholePageThrough() {
        final RowsFakes.ListCursor child = longs(3000, Page.CAPACITY);
        final RowsFakes.Drained out = drain(new LimitCursor(child, 1024, 2048));
        assertThat(out.pages()).containsExactly(child.pages().get(1));
    }
}
