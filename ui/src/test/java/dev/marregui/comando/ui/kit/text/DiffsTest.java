package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffsTest {

    @Test
    void removedSqlCommentsAreContentNotHeaders() {
        List<String> lines = Diffs.unified("q.sql", "-- old comment\nSELECT 1", "++ added\nSELECT 1");
        assertThat(lines.get(0)).startsWith("---");
        assertThat(Diffs.isHeader(0, lines.get(0))).isTrue();
        assertThat(Diffs.isHeader(1, lines.get(1))).isTrue();
        int removed = lines.indexOf("--- old comment");
        int added = lines.indexOf("+++ added");
        assertThat(removed).isGreaterThan(1);
        assertThat(Diffs.isRemoved(removed, lines.get(removed))).isTrue();
        assertThat(Diffs.isAdded(added, lines.get(added))).isTrue();
    }
}
