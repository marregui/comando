package dev.marregui.comando.claude.codex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// `CodexMapper` against a real app-server turn (Codex 0.172.0) and against single items, each case naming what a
/// Claude session would show for it.
class CodexMapperTest {

    private static final String THREAD = "01a21bbd-9374-7ac2-9020-4d684daee844";

    private final List<ObjectNode> events = new ArrayList<>();
    private final RecordingSink sink = new RecordingSink();
    private final Transcript transcript = new Transcript();
    private final CodexMapper mapper = new CodexMapper("work", transcript, sink);

    /// The recorded turn shows the prompt once, two answers, two `Bash` calls ending by their exit codes (0 and 6) and
    /// one turn end; the session runs, follows its context and ends its turn with the final answer, and the turn's
    /// metric sums the turn's two model calls in Claude's split of cached and fresh input.
    @Test
    void aRecordedTurnShowsAsAClaudeTurn() {
        mapper.metrics(events::add);
        mapper.prompt("Run each shell command separately", "", false);
        mapper.threadStarted(THREAD, "gpt-6.2-sol", "high");
        replay();

        assertThat(blocks(Block.UserPrompt.class)).singleElement().extracting(Block.UserPrompt::delivered)
                .isEqualTo(false);
        final List<Block.AssistantText> texts = blocks(Block.AssistantText.class);
        assertThat(texts).hasSize(2).noneMatch(Block.AssistantText::streaming);
        assertThat(texts.getFirst().text()).isEqualTo("I’ll run each command separately and record its exit status.");
        assertThat(texts.getLast().text()).startsWith("1. Scratchpad `touch` → exit `0`")
                .endsWith("MCP tools available — none.");
        final List<Block.ToolCall> calls = blocks(Block.ToolCall.class);
        assertThat(calls).extracting(Block.ToolCall::name).containsExactly("Bash", "Bash");
        assertThat(calls).extracting(Block.ToolCall::status)
                .containsExactly(Block.ToolStatus.DONE, Block.ToolStatus.ERROR);
        assertThat(Json.readTree(calls.getLast().inputJson()).path("command").asString("")).contains("curl");
        assertThat(Json.readTree(calls.getLast().inputJson()).path("cwd").asString("")).isEqualTo("/tmp/t258p");
        assertThat(calls.getLast().resultText()).isEqualTo("curl: (6) Could not resolve host: example.com\n");
        assertThat(blocks(Block.TurnEnd.class)).singleElement().satisfies(end -> {
            assertThat(end.error()).isFalse();
            assertThat(end.durationMillis()).isEqualTo(10_040L);
            assertThat(end.numTurns()).as("one per model call's token report").isEqualTo(2);
        });

        assertThat(sink.of(Intent.SessionInit.class)).as("the adapter dispatches it").isEmpty();
        assertThat(sink.of(Intent.SessionRanAt.class)).containsExactly(
                new Intent.SessionRanAt("work", "gpt-6.2-sol", "high", true));
        assertThat(sink.of(Intent.SessionTurnRunning.class)).hasSize(1);
        assertThat(sink.of(Intent.SessionContext.class)).containsExactly(
                new Intent.SessionContext("work", 15_366L, 475_000L),
                new Intent.SessionContext("work", 15_776L, 475_000L));
        assertThat(sink.of(Intent.SessionTurnEnded.class)).singleElement().satisfies(ended -> {
            assertThat(ended.error()).isFalse();
            assertThat(ended.contextTokens()).isEqualTo(15_776L);
            assertThat(ended.contextWindow()).isEqualTo(475_000L);
            assertThat(ended.result()).isEqualTo(texts.getLast().text());
        });

        assertThat(events).singleElement().satisfies(m -> {
            assertThat(m.path("conversation").asString("")).isEqualTo(THREAD);
            assertThat(m.path("model").asString("")).isEqualTo("gpt-6.2-sol");
            assertThat(m.path("duration_ms").asLong(0)).isEqualTo(10_040L);
            assertThat(m.path("context").asLong(0)).isEqualTo(15_776L);
            assertThat(m.path("usage").path("input_tokens").asLong(0)).isEqualTo(15_366L - 13_696L + 15_776L - 15_232L);
            assertThat(m.path("usage").path("cache_read_input_tokens").asLong(0)).isEqualTo(13_696L + 15_232L);
            assertThat(m.path("usage").path("output_tokens").asLong(0)).isEqualTo(254L);
            assertThat(m.path("cost_usd").asDouble(-1)).isZero();
        });
    }

    /// Deltas grow one block per item while it streams; the completed item's text replaces what they built.
    @Test
    void deltasStreamIntoOneBlockAndTheCompletedTextWins() {
        mapper.notification("turn/started", params("{}"));
        mapper.notification("item/started",
                params("{\"item\":{\"type\":\"agentMessage\",\"id\":\"m1\",\"text\":\"\"}}"));
        mapper.notification("item/agentMessage/delta", params("{\"itemId\":\"m1\",\"delta\":\"a\"}"));
        mapper.notification("item/agentMessage/delta", params("{\"itemId\":\"m1\",\"delta\":\"b\"}"));

        assertThat(blocks(Block.AssistantText.class)).singleElement().satisfies(t -> {
            assertThat(t.text()).isEqualTo("ab");
            assertThat(t.streaming()).isTrue();
        });

        mapper.notification("item/completed",
                params("{\"item\":{\"type\":\"agentMessage\",\"id\":\"m1\",\"text\":\"abc\"}}"));

        assertThat(blocks(Block.AssistantText.class)).singleElement().satisfies(t -> {
            assertThat(t.text()).isEqualTo("abc");
            assertThat(t.streaming()).isFalse();
        });
    }

    /// A reasoning item shows as thinking, its summary the text.
    @Test
    void reasoningShowsAsThinking() {
        mapper.notification("item/started", params("{\"item\":{\"type\":\"reasoning\",\"id\":\"r1\",\"summary\":[]}}"));
        mapper.notification("item/reasoning/summaryTextDelta", params("{\"itemId\":\"r1\",\"delta\":\"Plan\"}"));
        mapper.notification("item/completed",
                params("{\"item\":{\"type\":\"reasoning\",\"id\":\"r1\","
                        + "\"summary\":[\"Plan\",\"Act\"],\"content\":[]}}"));

        assertThat(blocks(Block.Thinking.class)).singleElement().satisfies(t -> {
            assertThat(t.text()).isEqualTo("Plan\n\nAct");
            assertThat(t.streaming()).isFalse();
        });
    }

    /// A failed turn ends in error with Codex's message; an interrupted one reads as an interrupt, as a Claude turn's.
    @Test
    void aFailedTurnEndsWithItsErrorAndAnInterruptedOneAsInterrupted() {
        mapper.notification("turn/started", params("{}"));
        mapper.notification("turn/completed", params("{\"turn\":{\"status\":\"failed\",\"durationMs\":12,"
                + "\"error\":{\"message\":\"usage limit reached\"}}}"));
        mapper.notification("turn/started", params("{}"));
        mapper.notification("turn/completed", params("{\"turn\":{\"status\":\"interrupted\",\"durationMs\":5}}"));

        assertThat(blocks(Block.TurnEnd.class)).extracting(Block.TurnEnd::error, Block.TurnEnd::text)
                .containsExactly(tuple(true, "usage limit reached"), tuple(true, "interrupted"));
        assertThat(sink.of(Intent.SessionTurnEnded.class)).extracting(Intent.SessionTurnEnded::message)
                .containsExactly("usage limit reached", "interrupted");
        assertThat(sink.of(Intent.SessionTurnEnded.class)).allMatch(Intent.SessionTurnEnded::error);
        assertThat(blocks(Block.TurnEnd.class)).extracting(Block.TurnEnd::numTurns).containsExactly(0, 0);
    }

    /// A completed turn with no token report counts one model call; the next turn counts its own calls only.
    @Test
    void aCompletedTurnWithoutATokenReportCountsOneModelCall() {
        mapper.notification("turn/started", params("{}"));
        mapper.notification("thread/tokenUsage/updated", params("{\"tokenUsage\":{\"last\":{\"inputTokens\":5}}}"));
        mapper.notification("thread/tokenUsage/updated", params("{\"tokenUsage\":{\"last\":{\"inputTokens\":7}}}"));
        mapper.notification("turn/completed", params("{\"turn\":{\"status\":\"completed\",\"durationMs\":3}}"));
        mapper.notification("turn/started", params("{}"));
        mapper.notification("turn/completed", params("{\"turn\":{\"status\":\"completed\",\"durationMs\":4}}"));

        assertThat(blocks(Block.TurnEnd.class)).extracting(Block.TurnEnd::numTurns).containsExactly(2, 1);
    }

    /// A sub-agent shows as an `Agent` call under the spawn call's id, running until its completed activity.
    @Test
    void aSubAgentShowsAsAnAgentCall() {
        final String started = "{\"item\":{\"type\":\"subAgentActivity\",\"id\":\"call-1\",\"kind\":\"started\","
                + "\"agentThreadId\":\"thr-c\",\"agentPath\":\"/root/pong\",\"model\":\"gpt-6.2-sol\"}}";
        mapper.notification("turn/started", params("{}"));
        mapper.notification("item/started", params(started));
        mapper.notification("item/completed", params(started));

        assertThat(blocks(Block.ToolCall.class)).singleElement().satisfies(call -> {
            assertThat(call.toolUseId()).isEqualTo("call-1");
            assertThat(call.isSubagent()).isTrue();
            assertThat(call.status()).isEqualTo(Block.ToolStatus.RUNNING);
            assertThat(call.inputJson()).contains("\"description\":\"pong\"");
        });

        mapper.notification("item/completed", params("{\"item\":{\"type\":\"subAgentActivity\",\"id\":\"done-1\","
                + "\"kind\":\"completed\",\"agentThreadId\":\"thr-c\",\"agentPath\":\"/root/pong\"}}"));
        assertThat(blocks(Block.ToolCall.class)).singleElement().satisfies(call -> {
            assertThat(call.status()).isNotEqualTo(Block.ToolStatus.RUNNING);
            assertThat(call.resultText()).isEqualTo("Sub-agent pong completed");
        });
    }

    /// A transcript emptied and read again shows its sub-agent again, and the end marks the new block.
    @Test
    void aSubAgentReadAgainShowsAgain() {
        final String started = "{\"item\":{\"type\":\"subAgentActivity\",\"id\":\"call-1\",\"kind\":\"started\","
                + "\"agentThreadId\":\"thr-c\",\"agentPath\":\"/root/pong\"}}";
        mapper.notification("item/completed", params(started));
        transcript.clear();
        mapper.note("before");
        mapper.notification("item/completed", params(started));
        mapper.notification("item/completed", params("{\"item\":{\"type\":\"subAgentActivity\",\"id\":\"done-1\","
                + "\"kind\":\"completed\",\"agentThreadId\":\"thr-c\",\"agentPath\":\"/root/pong\"}}"));

        assertThat(transcript.get(0)).isInstanceOf(Block.Note.class);
        assertThat(blocks(Block.ToolCall.class)).singleElement()
                .extracting(Block.ToolCall::resultText).isEqualTo("Sub-agent pong completed");
    }

    /// A new thread (`/clear`) starts with an empty context: the old thread's context size is not reported.
    @Test
    void aNewThreadStartsWithAnEmptyContext() {
        mapper.threadStarted(THREAD, "gpt-6.2-sol", "medium");
        mapper.notification("turn/started", params("{}"));
        mapper.notification("thread/tokenUsage/updated", params("{\"tokenUsage\":{\"last\":{\"inputTokens\":900}}}"));
        mapper.notification("turn/completed", params("{\"turn\":{\"status\":\"completed\",\"durationMs\":3}}"));
        mapper.threadStarted("thr-new", "gpt-6.2-sol", "medium");
        mapper.notification("turn/started", params("{}"));
        mapper.exited("codex exited with code 1");

        assertThat(sink.of(Intent.SessionTurnEnded.class)).extracting(Intent.SessionTurnEnded::contextTokens)
                .containsExactly(900L, 0L);
    }

    /// A file change shows as an `Edit` call with its paths and diffs, done when Codex applied it.
    @Test
    void aFileChangeShowsAsAnEdit() {
        final String item = "{\"type\":\"fileChange\",\"id\":\"f1\",\"status\":\"%s\",\"changes\":["
                + "{\"path\":\"/w/a.txt\",\"kind\":{\"type\":\"update\"},\"diff\":\"-x\\n+y\\n\"},"
                + "{\"path\":\"/w/b.txt\",\"kind\":{\"type\":\"add\"},\"diff\":\"+z\\n\"}]}";
        mapper.notification("item/started", params("{\"item\":" + item.formatted("inProgress") + '}'));

        assertThat(blocks(Block.ToolCall.class)).singleElement().extracting(Block.ToolCall::status)
                .isEqualTo(Block.ToolStatus.RUNNING);

        mapper.notification("item/completed", params("{\"item\":" + item.formatted("completed") + '}'));

        final Block.ToolCall call = blocks(Block.ToolCall.class).getFirst();
        assertThat(blocks(Block.ToolCall.class)).hasSize(1);
        assertThat(call.name()).isEqualTo("Edit");
        assertThat(call.status()).isEqualTo(Block.ToolStatus.DONE);
        final JsonNode input = Json.readTree(call.inputJson());
        assertThat(input.path("file_path").asString("")).isEqualTo("/w/a.txt");
        assertThat(input.path("changes").path(1).path("path").asString("")).isEqualTo("/w/b.txt");
        assertThat(input.path("changes").path(0).path("diff").asString("")).isEqualTo("-x\n+y\n");
        assertThat(input.path("changes").path(1).path("kind").asString("")).isEqualTo("add");
    }

    /// An MCP tool call shows as `mcp__<server>__<tool>` with its arguments and its result's text, or its error.
    @Test
    void anMcpToolCallShowsUnderClaudesName() {
        mapper.notification("item/completed", params("{\"item\":{\"type\":\"mcpToolCall\",\"id\":\"c1\","
                + "\"server\":\"comando\",\"tool\":\"ui_notify\",\"arguments\":{\"text\":\"hi\"},"
                + "\"status\":\"completed\","
                + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"shown\"}]},\"error\":null}}"));
        mapper.notification("item/completed", params("{\"item\":{\"type\":\"mcpToolCall\",\"id\":\"c2\","
                + "\"server\":\"comando\",\"tool\":\"db_run_sql\",\"arguments\":{},\"status\":\"failed\","
                + "\"result\":null,\"error\":{\"message\":\"no connection\"}}}"));

        final List<Block.ToolCall> calls = blocks(Block.ToolCall.class);
        assertThat(calls).extracting(Block.ToolCall::name)
                .containsExactly("mcp__comando__ui_notify", "mcp__comando__db_run_sql");
        assertThat(calls).extracting(Block.ToolCall::status)
                .containsExactly(Block.ToolStatus.DONE, Block.ToolStatus.ERROR);
        assertThat(calls).extracting(Block.ToolCall::resultText).containsExactly("shown", "no connection");
        assertThat(Json.readTree(calls.getFirst().inputJson()).path("text").asString("")).isEqualTo("hi");
    }

    /// The process ending mid-turn ends its running tool with no result and the turn with the error.
    @Test
    void anExitEndsRunningToolsAndTheTurn() {
        mapper.notification("turn/started", params("{}"));
        mapper.notification("item/started", params("{\"item\":{\"type\":\"commandExecution\",\"id\":\"e1\","
                + "\"command\":\"sleep 9\",\"cwd\":\"/w\",\"status\":\"inProgress\"}}"));
        mapper.exited("codex exited with code 1");

        assertThat(blocks(Block.ToolCall.class)).singleElement().satisfies(call -> {
            assertThat(call.status()).isEqualTo(Block.ToolStatus.ERROR);
            assertThat(call.resultText()).startsWith("no result");
        });
        assertThat(blocks(Block.TurnEnd.class)).singleElement().satisfies(end -> {
            assertThat(end.error()).isTrue();
            assertThat(end.text()).isEqualTo("codex exited with code 1");
        });
        assertThat(sink.of(Intent.SessionTurnEnded.class)).singleElement()
                .extracting(Intent.SessionTurnEnded::message).isEqualTo("codex exited with code 1");
    }

    /// A clean exit between turns ends nothing.
    @Test
    void aCleanExitBetweenTurnsEndsNoTurn() {
        mapper.exited("");

        assertThat(blocks(Block.TurnEnd.class)).isEmpty();
        assertThat(sink.of(Intent.SessionTurnEnded.class)).isEmpty();
    }

    /// An error Codex gives up on shows as a note, one it retries does not; comando's own notes show too.
    @Test
    void aFinalErrorAndANoteShowAsNotes() {
        mapper.notification("error", params("{\"error\":{\"message\":\"stream lost\"},\"willRetry\":true}"));
        mapper.notification("error", params("{\"error\":{\"message\":\"usage limit\"},\"willRetry\":false}"));
        mapper.note("/compact is a Claude Code command; Codex sessions do not have it");

        assertThat(blocks(Block.Note.class)).extracting(Block.Note::kind, Block.Note::text).containsExactly(
                tuple(Block.NoteKind.ERROR, "Error: usage limit"),
                tuple(Block.NoteKind.INFO, "/compact is a Claude Code command; Codex sessions do not have it"));
    }

    /// Account, MCP server, thread status and remote-control notifications, and the prompt's own item, show nothing.
    @Test
    void otherNotificationsAndThePromptItemShowNothing() {
        mapper.notification("account/rateLimits/updated", params("{}"));
        mapper.notification("mcpServer/startupStatus/updated", params("{}"));
        mapper.notification("thread/status/changed", params("{}"));
        mapper.notification("remoteControl/status/changed", params("{}"));
        mapper.notification("item/completed", params("{\"item\":{\"type\":\"userMessage\",\"id\":\"u1\"}}"));
        mapper.notification("item/completed", params("{\"item\":{\"type\":\"webSearch\",\"id\":\"w1\"}}"));

        assertThat(transcript.size()).isZero();
        assertThat(sink.intents()).isEmpty();
    }

    /// A completed `contextCompaction` item shows as the compaction note a Claude session shows, once; its start and
    /// `thread/compacted` add nothing.
    @Test
    void aCompactionShowsAsTheCompactionNote() {
        final String item = "{\"item\":{\"type\":\"contextCompaction\",\"id\":\"cmp-1\"}}";
        mapper.notification("item/started", params(item));
        mapper.notification("item/completed", params(item));
        mapper.notification("thread/compacted", params("{\"threadId\":\"t\",\"turnId\":\"cmp\"}"));

        assertThat(blocks()).singleElement().isInstanceOfSatisfying(Block.Note.class, note -> {
            assertThat(note.kind()).isEqualTo(Block.NoteKind.COMPACT);
            assertThat(note.text()).isEqualTo("Conversation compacted");
        });
        assertThat(sink.intents()).isEmpty();
    }

    /// A stored turn's end shows its turn end with its length and status, failed when the turn failed, and sends no
    /// intent and no metric: the turn ended long ago.
    @Test
    void aStoredTurnEndsWithoutAnIntentOrAMetric() {
        mapper.metrics(events::add);
        mapper.notification(CodexMapper.HISTORY_TURN_ENDED,
                params("{\"turn\":{\"status\":\"completed\",\"durationMs\":1200,\"completedAt\":1795761749}}"));
        mapper.notification(CodexMapper.HISTORY_TURN_ENDED,
                params("{\"turn\":{\"status\":\"failed\",\"error\":{\"message\":\"quota\"}}}"));

        assertThat(blocks(Block.TurnEnd.class)).extracting(Block.TurnEnd::durationMillis, Block.TurnEnd::error,
                Block.TurnEnd::text).containsExactly(tuple(1_200L, false, "completed"), tuple(0L, true, "quota"));
        assertThat(sink.intents()).isEmpty();
        assertThat(events).isEmpty();
    }

    private static ObjectNode params(final String json) {
        return (ObjectNode) Json.readTree(json);
    }

    private static List<String> recordedTurn() {
        try (final InputStream in = CodexMapperTest.class.getResourceAsStream("/codex/turn.jsonl")) {
            assertThat(in).as("the recorded turn").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read the recorded turn", e);
        }
    }

    private List<Block> blocks() {
        final List<Block> out = new ArrayList<>();
        for (int i = 0; i < transcript.size(); i++) {
            out.add(transcript.get(i));
        }
        return out;
    }

    private <T extends Block> List<T> blocks(final Class<T> type) {
        return blocks().stream().filter(type::isInstance).map(type::cast).toList();
    }

    /// The server's notifications, in order: not what the client sent, nor the responses to its requests.
    private void replay() {
        for (final String line : recordedTurn()) {
            if (line.startsWith(">> ")) {
                continue;
            }
            final JsonNode message = Json.readTree(line);
            if (message.has("id")) {
                continue;
            }
            mapper.notification(message.path("method").asString(""), (ObjectNode) message.path("params"));
        }
    }
}
