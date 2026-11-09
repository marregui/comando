package dev.marregui.comando.core.result;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.testkit.FakeResult;

import static org.assertj.core.api.Assertions.assertThat;

/// [ResultText] turns a result into column names and rows of display text.
///
/// - SQL NULL is a null cell, apart from a text cell holding `null`.
/// - Rows stop at the cap; a result shorter than the cap is kept whole.
/// - An empty result keeps its column names and has no rows.
/// - A page is a window of rows over a chosen set of columns, clamped to the rows the caller read.
class ResultTextTest {

    private static FakeResult result(final List<List<Object>> rows) {
        return new FakeResult(1, List.of("id", "name"), List.of(ColumnKind.LONG, ColumnKind.TEXT), rows);
    }

    private static FakeResult fiveRows() {
        return new FakeResult(
                1,
                List.of("id", "name", "score"),
                List.of(ColumnKind.LONG, ColumnKind.TEXT, ColumnKind.DOUBLE),
                List.of(
                        Arrays.asList(0L, "a", 0.5),
                        Arrays.asList(1L, "b", 1.5),
                        Arrays.asList(2L, null, 2.5),
                        Arrays.asList(3L, "d", 3.5),
                        Arrays.asList(4L, "e", 4.5)));
    }

    /// A NULL cell becomes a null cell, the text `null` stays the string `"null"`, and other cells take their
    /// [ResultView#format] text.
    @Test
    void nullCellIsNullAndTheTextNullIsText() {
        final FakeResult r = result(List.of(Arrays.asList(7L, null), Arrays.asList(null, "null")));
        assertThat(ResultText.columns(r)).containsExactly("id", "name");
        assertThat(ResultText.rows(r, 10)).containsExactly(Arrays.asList("7", null), Arrays.asList(null, "null"));
    }

    /// A cap below the row count keeps the first rows; a cap above it keeps them all.
    @Test
    void rowsStopAtTheCap() {
        final FakeResult r = result(List.of(Arrays.asList(1L, "a"), Arrays.asList(2L, "b"), Arrays.asList(3L, "c")));
        assertThat(ResultText.rows(r, 2)).containsExactly(List.of("1", "a"), List.of("2", "b"));
        assertThat(ResultText.rows(r, Integer.MAX_VALUE)).hasSize(3);
    }

    /// A result with no rows keeps its column names and yields an empty row list.
    @Test
    void anEmptyResultHasColumnsAndNoRows() {
        final FakeResult r = result(List.of());
        assertThat(ResultText.columns(r)).containsExactly("id", "name");
        assertThat(ResultText.rows(r, 10)).isEmpty();
    }

    /// A page starts at its offset and holds at most its count of rows; a NULL cell in it is a null cell.
    @Test
    void aPageStartsAtItsOffsetAndStopsAtItsCount() {
        final FakeResult r = fiveRows();
        assertThat(ResultText.page(r, r.rowCount(), 1, 2, new int[] {0, 1}))
                .containsExactly(List.of("1", "b"), Arrays.asList("2", null));
    }

    /// A page holds only the asked columns, in the order asked, whatever their order in the result.
    @Test
    void aPageKeepsTheAskedColumnsInTheirOrder() {
        final FakeResult r = fiveRows();
        assertThat(ResultText.page(r, r.rowCount(), 3, 2, new int[] {2, 0}))
                .containsExactly(List.of("3.5", "3"), List.of("4.5", "4"));
    }

    /// An offset at or past the rows read gives an empty page, and the page never reads beyond those rows.
    @Test
    void aPageStopsAtTheRowsRead() {
        final FakeResult r = fiveRows();
        assertThat(ResultText.page(r, r.rowCount(), 5, 3, new int[] {0})).isEmpty();
        assertThat(ResultText.page(r, r.rowCount(), 9, 3, new int[] {0})).isEmpty();
        assertThat(ResultText.page(r, 2, 1, 10, new int[] {0})).containsExactly(List.of("1"));
    }

    /// A count so large that offset plus count overflows an int still runs to the last row read.
    @Test
    void aPageWithAHugeCountRunsToTheEnd() {
        final FakeResult r = fiveRows();
        assertThat(ResultText.page(r, r.rowCount(), 3, Integer.MAX_VALUE, new int[] {0}))
                .containsExactly(List.of("3"), List.of("4"));
    }
}
