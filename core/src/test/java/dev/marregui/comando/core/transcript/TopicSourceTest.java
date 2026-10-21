package dev.marregui.comando.core.transcript;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopicSourceTest {

    @Test
    void theEndOfTheMainConversationIsReadOldestFirstWithoutThinkingNotesOrSubAgents() {
        Transcript t = new Transcript();
        t.append(new Block.UserPrompt("fix the flaky\n  key test", "p1", ""));
        t.append(new Block.Thinking("m1", 0, "secret reasoning", 10L, false, ""));
        t.append(new Block.ToolCall("t1", "Bash", "{\"command\":\"gradle test\"}", "ok", Block.ToolStatus.DONE, ""));
        t.append(new Block.AssistantText("m2", 0, "a sub-agent's words", false, "t9"));
        t.append(new Block.Note(Block.NoteKind.HOOK, "hook said", ""));
        t.append(new Block.AssistantText("m3", 0, "The test reads the focus owner.", false, ""));
        t.append(new Block.TurnEnd(0.1, 1_000L, 1, false, "done"));

        String source = TopicSource.ofSession("main", "keys", t, TopicSource.MAX_CHARS);

        assertThat(source).startsWith("A coding session named \"main\", in a panel titled \"keys\".")
                .contains("User: fix the flaky key test\nTool Bash: {\"command\":\"gradle test\"}\nAssistant: The test reads the focus owner.\n")
                .doesNotContain("secret reasoning", "a sub-agent's words", "hook said", "done");
    }

    @Test
    void theLatestPromptIsKeptWhenTheConversationAfterItFillsTheBudget() {
        Transcript t = new Transcript();
        t.append(new Block.UserPrompt("an older prompt", "p1", ""));
        t.append(new Block.UserPrompt("make the tab card sticky", "p1", ""));
        for (int i = 0; i < 40; i++) {
            t.append(new Block.AssistantText("m" + i, 0, "step " + i + " " + "x".repeat(200), false, ""));
        }

        String source = TopicSource.ofSession("s", "p", t, 2_000);

        assertThat(source).contains("User: make the tab card sticky").contains("step 39").doesNotContain("an older prompt");
        assertThat(source.indexOf("make the tab card sticky")).isLessThan(source.indexOf("step 39"));
        assertThat(source.length()).isLessThan(2_000 + 300);
    }

    @Test
    void aHugeBlockIsReadOnlyFromItsStart() {
        Transcript t = new Transcript();
        t.append(new Block.UserPrompt("write the report", "p1", ""));
        t.append(new Block.ToolCall("t1", "Write", "{\"content\":\"" + "word ".repeat(200_000) + "\"}", "", Block.ToolStatus.DONE, ""));
        t.append(new Block.AssistantText("m1", 0, "\uD83D\uDE00".repeat(1_000), false, ""));

        String source = TopicSource.ofSession("s", "p", t, TopicSource.MAX_CHARS);

        assertThat(source.length()).isLessThan(TopicSource.MAX_TOOL_INPUT + TopicSource.MAX_TEXT + 300);
        assertThat(source).contains("Tool Write: {\"content\":\"word word").contains("User: write the report");
        assertThat(Character.isHighSurrogate(source.charAt(source.length() - 3))).as("no pair split before the ellipsis").isFalse();
    }

    @Test
    void aConversationOrTextWithNothingToReadHasNoSource() {
        Transcript t = new Transcript();
        t.append(new Block.Thinking("m1", 0, "", 10L, false, ""));
        assertThat(TopicSource.ofSession("s", "p", new Transcript(), 100)).isEmpty();
        assertThat(TopicSource.ofSession("s", "p", t, 100)).isEmpty();
        assertThat(TopicSource.ofText("p", "  \n ", 100)).isEmpty();
        assertThat(TopicSource.ofText("notes.md", "# Plan\nship it", 100)).isEqualTo("A document in a panel titled \"notes.md\". Its text begins:\n# Plan\nship it");
        assertThat(TopicSource.ofText("big.log", "y".repeat(500), 100)).endsWith("y…");
    }
}
