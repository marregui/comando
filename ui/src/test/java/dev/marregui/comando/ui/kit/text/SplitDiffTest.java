package dev.marregui.comando.ui.kit.text;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// [SplitDiff] compares two texts side by side, mapping lines between them and marking changed words.
/// - Chunks are the differences in order; a line maps across unchanged and changed stretches by its share.
/// - An empty text has no lines.
/// - Changed words are marked, not whole lines, and a stretch rewritten whole has no word marks.
/// - A rewrite too large to match in the time budget is compared as a whole; an over-long line is not compared.
/// - Word marks are offsets into the whole text.
/// - Next and previous step through differences one by one; showing two texts colours lines and words.
@ExtendWith(OnEdt.class)
class SplitDiffTest {

    private static final List<String> BEFORE = List.of("a", "b", "c", "d", "e", "f");
    // b changed, a line added after d, f gone
    private static final List<String> AFTER = List.of("a", "B", "c", "d", "d2", "e");

    /// A changed line, an inserted line and a deleted line give three chunks as half-open line ranges on each side.
    @Test
    void chunksAreTheDifferencesInOrder() {
        assertThat(SplitDiff.chunks(BEFORE, AFTER)).containsExactly(
                new SplitDiff.Chunk(1, 2, 1, 2),
                new SplitDiff.Chunk(4, 4, 4, 5),
                new SplitDiff.Chunk(5, 6, 6, 6));
    }

    /// Unchanged lines keep their distance from a chunk, a fractional line inside an insertion or deletion maps to the
    /// point it went to, and lines past every chunk shift by the net change.
    @Test
    void aLineMapsAcrossUnchangedAndChangedStretches() {
        final List<SplitDiff.Chunk> chunks = SplitDiff.chunks(BEFORE, AFTER);
        assertThat(SplitDiff.map(chunks, 0, true)).isEqualTo(0);
        assertThat(SplitDiff.map(chunks, 3, true)).as("unchanged: the same distance").isEqualTo(3);
        assertThat(SplitDiff.map(chunks, 4, true)).as("after the insertion on the right").isEqualTo(5);
        assertThat(SplitDiff.map(chunks, 4.5, false)).as("inside the inserted line: the point it was inserted at").isEqualTo(4);
        assertThat(SplitDiff.map(chunks, 5, false)).isEqualTo(4);
        assertThat(SplitDiff.map(chunks, 5.5, true)).as("inside the deleted line: where it went").isEqualTo(6);
        assertThat(SplitDiff.map(chunks, 7, true)).as("past every difference").isEqualTo(7);
    }

    /// Inside a chunk of 2 lines against 4, a line maps in proportion both ways, and past it shifts by the difference.
    @Test
    void aLongerChangeMapsByItsShare() {
        final List<SplitDiff.Chunk> chunks = List.of(new SplitDiff.Chunk(2, 4, 2, 6));
        assertThat(SplitDiff.map(chunks, 3, true)).isCloseTo(4, within(1e-9));
        assertThat(SplitDiff.map(chunks, 5, false)).isCloseTo(3.5, within(1e-9));
        assertThat(SplitDiff.map(chunks, 10, false)).isEqualTo(8);
    }

    /// [SplitDiff#lines] of the empty string is empty, so a one-line text against it is one insertion.
    @Test
    void anEmptyTextHasNoLines() {
        assertThat(SplitDiff.lines("")).isEmpty();
        assertThat(SplitDiff.chunks(SplitDiff.lines(""), SplitDiff.lines("x\n"))).containsExactly(new SplitDiff.Chunk(0, 0, 0, 2));
    }

    /// Two lines differing in one token mark only that token on each side.
    @Test
    void theWordsThatChangedAreMarkedNotTheWholeLine() {
        final List<int[]> a = new ArrayList<>();
        final List<int[]> b = new ArrayList<>();
        assertThat(SplitDiff.wordChanges("int count = 1;", "int count = 2;", a, b)).isTrue();
        assertThat(a).hasSize(1);
        assertThat("int count = 1;".substring(a.getFirst()[0], a.getFirst()[1])).isEqualTo("1");
        assertThat("int count = 2;".substring(b.getFirst()[0], b.getFirst()[1])).isEqualTo("2");
    }

    /// Lines sharing no words return false and mark nothing.
    @Test
    void aStretchRewrittenWholeHasNoWordMarks() {
        final List<int[]> a = new ArrayList<>();
        final List<int[]> b = new ArrayList<>();
        assertThat(SplitDiff.wordChanges("alpha beta gamma", "one two three", a, b)).isFalse();
        assertThat(a).isEmpty();
        assertThat(b).isEmpty();
    }

    /// Two 20 000-line texts with nothing in common under a 100 ms budget finish within 2 s as one whole chunk with a
    /// note saying so.
    @Test
    void aRewriteTooLargeToMatchInTimeIsComparedAsAWholeWithinTheBudget() {
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            a.append("left ").append(i).append('\n');
            b.append("right ").append(i * 7).append('\n');
        }
        final long start = System.nanoTime();
        final SplitDiff.Model m = SplitDiff.compute(a.toString(), b.toString(), 100);
        final long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(millis).as("bounded by the budget, not by the size").isLessThan(2_000);
        assertThat(m.compared()).isTrue();
        assertThat(m.note()).contains("as a whole");
        assertThat(m.chunks()).containsExactly(new SplitDiff.Chunk(0, 20_001, 0, 20_001));
    }

    /// A line one past [SplitDiff#MAX_LINE_CHARS] makes the texts uncompared with a note about minified text; a line at
    /// the bound is compared.
    @Test
    void aLineLongerThanTheBoundIsNotCompared() {
        final SplitDiff.Model m = SplitDiff.compute("short\n", "x".repeat(SplitDiff.MAX_LINE_CHARS + 1), 1_000);
        assertThat(m.compared()).isFalse();
        assertThat(m.note()).contains("minified");
        assertThat(SplitDiff.compute("a\n", "x".repeat(SplitDiff.MAX_LINE_CHARS) + "\n", 1_000).compared()).isTrue();
    }

    /// Word marks of a model count from the start of the text, `\r\n` included, matching [SplitDiff#lineStarts].
    @Test
    void wordMarksAreOffsetsIntoTheWholeText() {
        final String before = "a\r\nint count = 1;\nz\n";
        final String after = "a\r\nint count = 2;\nz\n";
        final SplitDiff.Model m = SplitDiff.compute(before, after, 1_000);
        assertThat(m.leftWords()).hasSize(1);
        assertThat(before.substring(m.leftWords().getFirst()[0], m.leftWords().getFirst()[1])).isEqualTo("1");
        assertThat(after.substring(m.rightWords().getFirst()[0], m.rightWords().getFirst()[1])).isEqualTo("2");
        assertThat(SplitDiff.lineStarts(before)).containsExactly(0, 3, 18, 20);
    }

    /// With three differences, next visits 0, 1, 2 then returns false; previous goes back to 1 and 0, not the same one
    /// again, then returns false.
    @Test
    void nextAndPreviousStepThroughTheDifferencesOneByOne() {
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            a.append("line ").append(i).append('\n');
            b.append(i == 10 || i == 50 || i == 90 ? "changed " : "line ").append(i).append('\n');
        }
        final SplitDiff d = new SplitDiff();
        d.component().setSize(800, 300);
        d.component().doLayout();
        d.show("x.txt", "HEAD", a.toString(), "Working tree", b.toString(), false);
        assertThat(d.chunks()).hasSize(3);
        assertThat(d.next()).isTrue();
        assertThat(d.current()).isZero();
        assertThat(d.next()).isTrue();
        assertThat(d.current()).isEqualTo(1);
        assertThat(d.next()).isTrue();
        assertThat(d.next()).as("past the last").isFalse();
        assertThat(d.previous()).isTrue();
        assertThat(d.current()).isEqualTo(1);
        assertThat(d.previous()).isTrue();
        assertThat(d.current()).as("back, not the same one again").isZero();
        assertThat(d.previous()).isFalse();
    }

    /// [SplitDiff#show] fills both editors in the file's syntax style, finds the one chunk and highlights the changed
    /// word.
    @Test
    void showingTwoTextsColoursTheirLinesAndWords() {
        final SplitDiff d = new SplitDiff();
        d.show("A.java", "HEAD", "class A {\n    int x = 1;\n}\n", "Working tree", "class A {\n    int x = 2;\n    int y;\n}\n", false);
        assertThat(d.chunks()).containsExactly(new SplitDiff.Chunk(1, 2, 1, 3));
        assertThat(d.left().getText()).contains("x = 1");
        assertThat(d.right().getText()).contains("x = 2");
        assertThat(d.left().getSyntaxEditingStyle()).isEqualTo(Editors.styleFor("java"));
        assertThat(d.left().getHighlighter().getHighlights()).as("the 1 that became 2").hasSize(1);
        assertThat(d.right().getHighlighter().getHighlights()).hasSizeGreaterThanOrEqualTo(1);
    }
}
