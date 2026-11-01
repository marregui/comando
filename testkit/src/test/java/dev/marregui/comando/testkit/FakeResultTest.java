package dev.marregui.comando.testkit;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.result.ColumnKind;

import static org.assertj.core.api.Assertions.assertThat;

/// [FakeResult] answers as the database adapter's result does, so a test built on it sees what production shows.
class FakeResultTest {

    /// `getText` gives a TEXT cell's value and null for its SQL NULL, and any other cell's display text: a timestamp
    /// formatted in UTC, a number as written, a SQL NULL as `null`.
    @Test
    void getTextAnswersAsTheRealResultDoes() {
        final FakeResult r = new FakeResult(1L, List.of("label", "ts", "n"), List.of(ColumnKind.TEXT, ColumnKind.TIMESTAMP, ColumnKind.LONG),
                List.of(List.of("a", 1_000_000L, 7L), Arrays.asList(null, null, null)));
        assertThat(r.getText(0, 0)).isEqualTo("a");
        assertThat(r.getText(1, 0)).as("a TEXT cell's SQL NULL").isNull();
        assertThat(r.getText(0, 1)).as("a timestamp, formatted").startsWith("1970-01-01").doesNotContain("1000000");
        assertThat(r.getText(0, 2)).isEqualTo("7");
        assertThat(r.getText(1, 1)).as("another cell's SQL NULL").isEqualTo("null");
    }
}
