package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.dispatch.Blocks;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.testkit.FakeResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentsTest {

    @Test
    void attachedRowsAndTheQuestionUnderThemStayOneBlock() {
        FakeResult r = new FakeResult(1L, List.of("a", "b"), List.of(ColumnKind.TEXT, ColumnKind.TEXT),
                List.of(Arrays.asList("x", "line1\nline2"), Arrays.asList("", "")));
        String text = Attachments.rows("SELECT a,\n b FROM t", r, new int[0]) + "why is row 2 empty?";
        assertThat(Blocks.all(text)).hasSize(1);
        assertThat(text).contains("x\tline1⏎line2", "(empty row)", "Result of `SELECT a,  b FROM t` (first 2 of 2 rows)");
    }

    @Test
    void anErrorWithBlankLinesStaysOneBlock() {
        String text = Attachments.error("SELECT 1", "ERROR: boom\n\n  Hint: none\n") + "what went wrong?";
        assertThat(Blocks.all(text)).hasSize(1);
        assertThat(text).contains("ERROR: boom\n  Hint: none");
    }
}
