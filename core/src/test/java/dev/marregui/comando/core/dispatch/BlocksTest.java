package dev.marregui.comando.core.dispatch;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Blocks] partitions editor text at blank lines and selects the caret block or its preceding block.
///
/// - non-blank line runs retain their offsets and first lines
/// - whitespace-only lines end a block
/// - fence text does not alter blank-line partitioning
class BlocksTest {

    private static final String TEXT = """
            SELECT 1;

            -- second
            SELECT 2


            why is it slow""";

    /// Three runs separated by one or more blank lines retain their source text and zero-based first-line positions.
    @Test
    void splitsOnBlankLines() {
        final List<Blocks.Block> blocks = Blocks.all(TEXT);
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).text(TEXT)).isEqualTo("SELECT 1;");
        assertThat(blocks.get(1).text(TEXT)).isEqualTo("-- second\nSELECT 2");
        assertThat(blocks.get(1).firstLine()).isEqualTo(2);
        assertThat(blocks.get(2).text(TEXT)).isEqualTo("why is it slow");
        assertThat(blocks.get(2).firstLine()).isEqualTo(6);
    }

    /// A caret in a block or in the blank line after it selects that block, while an empty source has no selection.
    @Test
    void blockAtCaretOrNearestAbove() {
        final int inSecond = TEXT.indexOf("SELECT 2") + 3;
        assertThat(Blocks.at(TEXT, inSecond).text(TEXT)).isEqualTo("-- second\nSELECT 2");
        final int blankAfterSecond = TEXT.indexOf("SELECT 2") + "SELECT 2".length() + 1;
        assertThat(Blocks.at(TEXT, blankAfterSecond).text(TEXT)).isEqualTo("-- second\nSELECT 2");
        assertThat(Blocks.at("", 0)).isNull();
    }

    /// A line containing spaces and a tab is blank, so it separates the surrounding non-blank lines into two blocks.
    @Test
    void whitespaceOnlyLinesAreBlank() {
        assertThat(Blocks.all("a\n   \t\nb")).hasSize(2);
    }

    /// Unmatched fence markers remain ordinary non-blank text and do not join runs across blank lines.
    @Test
    void aStrayFenceJoinsNothing() {
        final String text = "```\nSELECT 1\n\nnext\n\nResult:\n```\na\n```";
        assertThat(Blocks.all(text)).extracting(b -> b.text(text)).containsExactly("```\nSELECT 1", "next", "Result:\n```\na\n```");
    }
}
