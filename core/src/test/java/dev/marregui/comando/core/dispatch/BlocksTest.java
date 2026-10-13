package dev.marregui.comando.core.dispatch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlocksTest {

    private static final String TEXT = """
            SELECT 1;

            -- second
            SELECT 2


            why is it slow""";

    @Test
    void splitsOnBlankLines() {
        List<Blocks.Block> blocks = Blocks.all(TEXT);
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).text(TEXT)).isEqualTo("SELECT 1;");
        assertThat(blocks.get(1).text(TEXT)).isEqualTo("-- second\nSELECT 2");
        assertThat(blocks.get(1).firstLine()).isEqualTo(2);
        assertThat(blocks.get(2).text(TEXT)).isEqualTo("why is it slow");
        assertThat(blocks.get(2).firstLine()).isEqualTo(6);
    }

    @Test
    void blockAtCaretOrNearestAbove() {
        int inSecond = TEXT.indexOf("SELECT 2") + 3;
        assertThat(Blocks.at(TEXT, inSecond).text(TEXT)).isEqualTo("-- second\nSELECT 2");
        int blankAfterSecond = TEXT.indexOf("SELECT 2") + "SELECT 2".length() + 1;
        assertThat(Blocks.at(TEXT, blankAfterSecond).text(TEXT)).isEqualTo("-- second\nSELECT 2");
        assertThat(Blocks.at("", 0)).isNull();
    }

    @Test
    void whitespaceOnlyLinesAreBlank() {
        assertThat(Blocks.all("a\n   \t\nb")).hasSize(2);
    }

    @Test
    void aStrayFenceJoinsNothing() {
        String text = "```\nSELECT 1\n\nnext\n\nResult:\n```\na\n```";
        assertThat(Blocks.all(text)).extracting(b -> b.text(text)).containsExactly("```\nSELECT 1", "next", "Result:\n```\na\n```");
    }
}
