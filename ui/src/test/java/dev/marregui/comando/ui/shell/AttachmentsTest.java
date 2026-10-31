package dev.marregui.comando.ui.shell;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.dispatch.Blocks;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.testkit.FakeResult;

import static org.assertj.core.api.Assertions.assertThat;

/// [Attachments] turns a result or an error into prompt text that, with the question under it, stays one block.
/// - Attached rows and the question under them stay one block, newlines in cells shown as ⏎ and empty rows named.
/// - An error with blank lines, or with no text, stays one block.
class AttachmentsTest {

    /// [Attachments#rows] flattens the query onto one line, shows a cell's newline as ⏎ and an all-empty row as
    /// `(empty row)`, and with the question appended [Blocks#all] sees one block.
    @Test
    void attachedRowsAndTheQuestionUnderThemStayOneBlock() {
        final FakeResult r = new FakeResult(1L, List.of("a", "b"), List.of(ColumnKind.TEXT, ColumnKind.TEXT),
                List.of(Arrays.asList("x", "line1\nline2"), Arrays.asList("", "")));
        final String text = Attachments.rows("SELECT a,\n b FROM t", r, new int[0]) + "why is row 2 empty?";
        assertThat(Blocks.all(text)).hasSize(1);
        assertThat(text).contains("x\tline1⏎line2", "(empty row)", "Result of `SELECT a,  b FROM t` (first 2 of 2 rows)");
    }

    /// [Attachments#error] drops the blank line inside the message, so the error and the question stay one block.
    @Test
    void anErrorWithBlankLinesStaysOneBlock() {
        final String text = Attachments.error("SELECT 1", "ERROR: boom\n\n  Hint: none\n") + "what went wrong?";
        assertThat(Blocks.all(text)).hasSize(1);
        assertThat(text).contains("ERROR: boom\n  Hint: none");
    }

    /// A blank error message becomes `(no message)` in its fence, still one block with the question.
    @Test
    void anErrorWithoutTextStaysOneBlock() {
        final String text = Attachments.error("SELECT 1", "  \n") + "what went wrong?";
        assertThat(Blocks.all(text)).hasSize(1);
        assertThat(text).contains("```\n(no message)\n```");
    }
}
