package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [FrontMatter#split] separates a Markdown file's YAML front matter from its body.
/// - Facts are read in order, nested keys by their dotted path, quotes dropped.
/// - The body keeps every line and offset of the file.
/// - Lists, block or flow, join their items.
/// - A rule, a setext heading, an unclosed or an empty block is not front matter.
class FrontMatterTest {

    /** A memory file as the CLI writes it. */
    static final String MEMORY = """
            ---
            name: reference-video-transcripts
            description: "How to get a transcript: the route that works"
            metadata:
              node_type: memory
              type: reference
              modified: 2026-10-23T13:27:57.642Z
            ---

            Proven 2026-10-22, see [[feedback-ui-requests]].
            """;

    /// A memory file's facts come in file order with `metadata.` keys, a quoted value loses its quotes and keeps its
    /// colon, and a timestamp is read whole.
    @Test
    void readsAMemoryFilesFactsWithNestedKeysByTheirPath() {
        FrontMatter.Split split = FrontMatter.split(MEMORY);
        assertThat(split.facts()).extracting(FrontMatter.Fact::key)
                .containsExactly("name", "description", "metadata.node_type", "metadata.type", "metadata.modified");
        assertThat(split.value("name")).isEqualTo("reference-video-transcripts");
        assertThat(split.value("description")).as("quotes dropped, the colon in it kept").isEqualTo("How to get a transcript: the route that works");
        assertThat(split.value("metadata.modified")).isEqualTo("2026-10-23T13:27:57.642Z");
    }

    /// The body has the file's length and line count, with the front matter blanked, so an offset in the body is the
    /// offset in the file.
    @Test
    void theBodyKeepsEveryLineAndOffset() {
        FrontMatter.Split split = FrontMatter.split(MEMORY);
        assertThat(split.body()).hasSameSizeAs(MEMORY);
        assertThat(split.body().lines().count()).isEqualTo(MEMORY.lines().count());
        assertThat(split.body().indexOf("Proven")).isEqualTo(MEMORY.indexOf("Proven"));
        assertThat(split.body().strip()).startsWith("Proven");
    }

    /// A block list and a flow list join their items with `, `, quotes dropped.
    @Test
    void listsJoinTheirItems() {
        FrontMatter.Split split = FrontMatter.split("---\ntags:\n  - a\n  - 'b c'\naliases: [x, \"y\"]\n---\nText");
        assertThat(split.value("tags")).isEqualTo("a, b c");
        assertThat(split.value("aliases")).isEqualTo("x, y");
    }

    /// A setext heading between rules, a block not at the top, an unclosed block and an empty block give no facts.
    @Test
    void aRuleAndAHeadingAreNotFrontMatter() {
        assertThat(FrontMatter.split("---\nA heading of words\n---\ntext").any()).as("a setext heading under a rule").isFalse();
        assertThat(FrontMatter.split("# Title\n---\nname: x\n---").any()).as("not at the top").isFalse();
        assertThat(FrontMatter.split("---\nname: x\n").any()).as("never closed").isFalse();
        assertThat(FrontMatter.split("---\n---\n").any()).as("empty").isFalse();
    }
}
