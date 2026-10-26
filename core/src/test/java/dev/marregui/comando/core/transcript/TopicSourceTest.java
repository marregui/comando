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

        String source = TopicSource.ofSession("main", "keys", "/w/comando", "main", t, TopicSource.MAX_CHARS);

        assertThat(source).startsWith("A coding session named \"main\", working in /w/comando (git branch main), in a panel titled \"keys\".")
                .contains("User: fix the flaky key test\nTool Bash: {\"command\":\"gradle test\"}\nAssistant: The test reads the focus owner.\n")
                .doesNotContain("secret reasoning", "a sub-agent's words", "hook said", "done");
        assertThat(TopicSource.ofSession("main", "", "/w/comando", "", t, TopicSource.MAX_CHARS))
                .as("a headless session names no panel").startsWith("A coding session named \"main\", working in /w/comando. The end of its conversation");
    }

    @Test
    void theLatestPromptIsKeptWhenTheConversationAfterItFillsTheBudget() {
        Transcript t = new Transcript();
        t.append(new Block.UserPrompt("an older prompt", "p1", ""));
        t.append(new Block.UserPrompt("make the tab card sticky", "p1", ""));
        for (int i = 0; i < 40; i++) {
            t.append(new Block.AssistantText("m" + i, 0, "step " + i + " " + "x".repeat(200), false, ""));
        }

        String source = TopicSource.ofSession("s", "p", "", "", t, 2_000);

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

        String source = TopicSource.ofSession("s", "p", "", "", t, TopicSource.MAX_CHARS);

        assertThat(source.length()).isLessThan(TopicSource.MAX_TOOL_INPUT + TopicSource.MAX_TEXT + 300);
        assertThat(source).contains("Tool Write: {\"content\":\"word word").contains("User: write the report");
        assertThat(Character.isHighSurrogate(source.charAt(source.length() - 3))).as("no pair split before the ellipsis").isFalse();
    }

    @Test
    void aConversationOrTextWithNothingToReadHasNoSource() {
        Transcript t = new Transcript();
        t.append(new Block.Thinking("m1", 0, "", 10L, false, ""));
        assertThat(TopicSource.ofSession("s", "p", "/w", "", new Transcript(), 100)).isEmpty();
        assertThat(TopicSource.ofSession("s", "p", "/w", "", t, 100)).isEmpty();
        assertThat(TopicSource.ofText("p", "", "  \n ", 100)).isEmpty();
        assertThat(TopicSource.ofText("notes.md", "/w/notes.md", "# Plan\nship it", 100)).isEqualTo("A document /w/notes.md in a panel titled \"notes.md\". Its text begins:\n# Plan\nship it");
        assertThat(TopicSource.ofText("big.log", "", "y".repeat(500), 100)).endsWith("y…");
    }

    @Test
    void aSessionPanelsTopicIsAboutItsLastParagraphWithTheRestForContext() {
        String text = "# notes\nold idea about the cache\n\nfix ENG-1125: the browse regression\n \nnow port the fix to v1\nand open the PR";
        String source = TopicSource.ofPanel("work", "edge", "/Users/m/edge", "ENG-1125", text, "", TopicSource.MAX_CHARS);
        assertThat(source).startsWith("The panel \"work\", where the user writes the prompts of a coding session named \"edge\", working in /Users/m/edge (git branch ENG-1125).");
        assertThat(source).contains("which the summary is about:\nnow port the fix to v1\nand open the PR\n\nThe text before it, for context, oldest first:\n# notes");
        assertThat(source).endsWith("fix ENG-1125: the browse regression");
        assertThat(TopicSource.ofPanel("work", "edge", "", "", "  \n ", "a response", TopicSource.MAX_CHARS)).isEmpty();
        assertThat(TopicSource.ofPanel("work", "edge", "", "", "one paragraph only", "", TopicSource.MAX_CHARS))
                .endsWith("which the summary is about:\none paragraph only").doesNotContain("for context");

        String longBefore = "x".repeat(10_000) + "\n\nthe ask";
        String bounded = TopicSource.ofPanel("work", "edge", "", "", longBefore, "", 200);
        assertThat(bounded).as("the context keeps its end, within the budget").contains("the ask\n\nThe text before it");
        assertThat(bounded.length()).isLessThan(200 + 300);
    }

    @Test
    void theSessionsLastResponseIsReadWithTheLastParagraphBeforeTheContext() {
        Transcript t = new Transcript();
        t.append(new Block.UserPrompt("port it", "p", ""));
        t.append(new Block.AssistantText("m1", 0, "Looking.", false, ""));
        t.append(new Block.AssistantText("m2", 0, "Ported to v1; PR #1790 is up and CI is green.", false, ""));
        t.append(new Block.AssistantText("m3", 0, "a subagent's words", false, "toolu_1"));
        t.append(new Block.TurnEnd(0.1, 1_000, 2, false, ""));
        assertThat(TopicSource.lastResponse(t)).as("the main conversation's latest").isEqualTo("Ported to v1; PR #1790 is up and CI is green.");
        assertThat(TopicSource.lastResponse(new Transcript())).isEmpty();

        String source = TopicSource.ofPanel("work", "edge", "", "", "earlier notes\n\nport the fix to v1", TopicSource.lastResponse(t),
                TopicSource.MAX_CHARS);
        assertThat(source).contains("which the summary is about:\nport the fix to v1\n\nThe session's last response, to read with that command:\n"
                + "Ported to v1; PR #1790 is up and CI is green.\n\nThe text before it, for context, oldest first:\nearlier notes");

        String longAnswer = "y".repeat(5_000);
        String bounded = TopicSource.ofPanel("work", "edge", "", "", "the ask", longAnswer, TopicSource.MAX_CHARS);
        assertThat(bounded).as("a long response keeps its start, within its share").contains("y".repeat(TopicSource.MAX_RESPONSE - 1) + "…")
                .doesNotContain("y".repeat(TopicSource.MAX_RESPONSE + 1));
    }
}
