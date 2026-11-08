package dev.marregui.comando.ui.kit.text;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [HitMarks]: a search's hits found again on their lines (record 141).
/// - a match is found on its line in either ASCII case, occurrences not overlapping
/// - the occurrence the snippet's text before it points at wins; else the one nearest its column past the indent
/// - the current index is one of the hits, or none; the hits are a copy
class HitMarksTest {

    /// ASCII letters match in either case; other characters only as they are; occurrences do not overlap.
    @Test
    void aMatchIsFoundInEitherCaseWithoutOverlap() {
        assertThat(new HitMarks.Hit(1, "foo", -1, "").startsIn("FOO foo fOo")).containsExactly(0, 4, 8);
        assertThat(new HitMarks.Hit(1, "aa", -1, "").startsIn("aaaa")).containsExactly(0, 2);
        assertThat(new HitMarks.Hit(1, "é", -1, "").startsIn("É é")).containsExactly(2);
        assertThat(new HitMarks.Hit(1, "", -1, "").startsIn("anything")).isEmpty();
    }

    /// The text before the match decides where it sits, even past the indent a snippet drops or a control char it
    /// shows as a space; without it, the column past the indent decides.
    @Test
    void theNearestOccurrenceIsTheOneTheSnippetPointsAt() {
        final String line = "\tcall(x);\u0007call(y);";
        final HitMarks.Hit hit = new HitMarks.Hit(1, "call", 0, "call(x); ");
        assertThat(hit.nearestIn(line, hit.startsIn(line))).isEqualTo(10);

        final HitMarks.Hit byColumn = new HitMarks.Hit(1, "a", 12, "");
        final String spaced = "  x = a; y = a; z = a;";
        assertThat(byColumn.nearestIn(spaced, byColumn.startsIn(spaced))).isEqualTo(13);

        final HitMarks.Hit unknown = new HitMarks.Hit(1, "a", -1, "");
        assertThat(unknown.nearestIn(spaced, unknown.startsIn(spaced))).isEqualTo(6);
    }

    /// The current index is a hit or -1; the hits given are copied; a line alone is one hit with nothing to match.
    @Test
    void theCurrentIsAHitAndTheHitsAreACopy() {
        final List<HitMarks.Hit> hits = new ArrayList<>(List.of(new HitMarks.Hit(2, "x", 0, "")));
        final HitMarks marks = new HitMarks(hits, 0);
        hits.clear();

        assertThat(marks.hits()).hasSize(1);
        assertThat(HitMarks.NONE.current()).isEqualTo(-1);
        assertThat(HitMarks.line(7).hits()).containsExactly(new HitMarks.Hit(7, "", -1, ""));
        assertThatThrownBy(() -> new HitMarks(List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HitMarks(List.of(), -2)).isInstanceOf(IllegalArgumentException.class);
    }
}
