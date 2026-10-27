package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrontMatterTest {

    /** A memory file as Claude Code writes it. */
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

    @Test
    void readsAMemoryFilesFactsWithNestedKeysByTheirPath() {
        FrontMatter.Split split = FrontMatter.split(MEMORY);
        assertThat(split.facts()).extracting(FrontMatter.Fact::key)
                .containsExactly("name", "description", "metadata.node_type", "metadata.type", "metadata.modified");
        assertThat(split.value("name")).isEqualTo("reference-video-transcripts");
        assertThat(split.value("description")).as("quotes dropped, the colon in it kept").isEqualTo("How to get a transcript: the route that works");
        assertThat(split.value("metadata.modified")).isEqualTo("2026-10-23T13:27:57.642Z");
    }

    @Test
    void theBodyKeepsEveryLineAndOffset() {
        FrontMatter.Split split = FrontMatter.split(MEMORY);
        assertThat(split.body()).hasSameSizeAs(MEMORY);
        assertThat(split.body().lines().count()).isEqualTo(MEMORY.lines().count());
        assertThat(split.body().indexOf("Proven")).isEqualTo(MEMORY.indexOf("Proven"));
        assertThat(split.body().strip()).startsWith("Proven");
    }

    @Test
    void listsJoinTheirItems() {
        FrontMatter.Split split = FrontMatter.split("---\ntags:\n  - a\n  - 'b c'\naliases: [x, \"y\"]\n---\nText");
        assertThat(split.value("tags")).isEqualTo("a, b c");
        assertThat(split.value("aliases")).isEqualTo("x, y");
    }

    @Test
    void aRuleAndAHeadingAreNotFrontMatter() {
        assertThat(FrontMatter.split("---\nA heading of words\n---\ntext").any()).as("a setext heading under a rule").isFalse();
        assertThat(FrontMatter.split("# Title\n---\nname: x\n---").any()).as("not at the top").isFalse();
        assertThat(FrontMatter.split("---\nname: x\n").any()).as("never closed").isFalse();
        assertThat(FrontMatter.split("---\n---\n").any()).as("empty").isFalse();
    }
}
