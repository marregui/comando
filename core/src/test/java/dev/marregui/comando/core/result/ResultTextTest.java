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
class ResultTextTest {

    private static FakeResult result(final List<List<Object>> rows) {
        return new FakeResult(1, List.of("id", "name"), List.of(ColumnKind.LONG, ColumnKind.TEXT), rows);
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
}
