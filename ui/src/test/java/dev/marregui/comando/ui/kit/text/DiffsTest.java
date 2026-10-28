package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.format.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [Diffs] builds unified diffs of tool edits and classifies their lines.
/// - Only the first two lines are headers; a removed `--` or added `++` line is content.
/// - Each edit tool maps to the text it replaces and writes; other tools map to nothing.
class DiffsTest {

    /// In a diff of SQL whose removed line is a `--` comment and whose added line starts `++`, lines 0 and 1 are headers
    /// and the `---` and `+++` content lines are removed and added.
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

    /// [Diffs#edit] maps Edit to old and new strings, Write to empty and content, MultiEdit to its edits joined with
    /// `…` separators, and Bash to null.
    @Test
    void eachEditToolMapsToTheTextItReplacesAndWrites() {
        assertThat(Diffs.edit("Edit", Json.parse("{\"file_path\":\"/a\",\"old_string\":\"x\",\"new_string\":\"y\"}")))
                .isEqualTo(new Diffs.Edit("/a", "x", "y"));
        assertThat(Diffs.edit("Write", Json.parse("{\"file_path\":\"/a\",\"content\":\"z\"}")))
                .isEqualTo(new Diffs.Edit("/a", "", "z"));
        assertThat(Diffs.edit("MultiEdit", Json.parse("""
                {"file_path":"/a","edits":[{"old_string":"a1","new_string":"b1"},{"old_string":"a2","new_string":"b2"}]}""")))
                .isEqualTo(new Diffs.Edit("/a", "a1\n…\na2\n…\n", "b1\n…\nb2\n…\n"));
        assertThat(Diffs.edit("Bash", Json.parse("{\"command\":\"ls\"}"))).isNull();
    }
}
