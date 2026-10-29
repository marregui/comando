package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.Fixtures;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StreamMapperTest {

    private final RecordingSink sink = new RecordingSink();
    private final Transcript transcript = new Transcript();
    private final StreamMapper mapper = new StreamMapper("work", transcript, sink, new ObjectMapper());

    private List<Block> blocks() {
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < transcript.size(); i++) {
            out.add(transcript.get(i));
        }
        return out;
    }

    private <T extends Block> List<T> blocks(Class<T> type) {
        return blocks().stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void aCheckpointsClearShowsOnlyWhatItCarriedAndAClearByHandShowsItself() {
        String reset = "{\"type\":\"conversation_reset\",\"trigger\":\"clear\"}";
        String result = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"\",\"total_cost_usd\":0,"
                + "\"duration_ms\":3,\"num_turns\":0}";
        mapper.carried("## Reflected\nmemory written");
        mapper.clearQuietly();
        mapper.accept(reset);
        mapper.accept(result);
        assertThat(blocks()).hasSize(3);
        assertThat(blocks().get(0)).isInstanceOfSatisfying(Block.Note.class,
                n -> assertThat(n.text()).isEqualTo("Conversation cleared after the checkpoint; its summary below goes in front of the next prompt"));
        assertThat(blocks().get(1)).isInstanceOfSatisfying(Block.AssistantText.class,
                a -> assertThat(a.text()).isEqualTo("## Reflected\nmemory written"));
        assertThat(blocks().get(2)).isEqualTo(Block.TurnEnd.stored());
        assertThat(sink.of(Intent.SessionTurnEnded.class)).as("the clear's turn still ends").hasSize(1);

        mapper.accept(reset);
        mapper.accept(result);
        assertThat(blocks(Block.Note.class)).last().extracting(Block.Note::text).asString().startsWith("Conversation cleared (clear)");
        assertThat(blocks(Block.TurnEnd.class)).hasSize(2);
    }

    @Test
    void approvalsAndQuestionRecording() {
        mapper.prompt("Run the shell command: touch probe-file.txt", "one");
        Fixtures.stream("approvals-and-question").forEach(mapper::accept);

        Intent.SessionInit init = sink.of(Intent.SessionInit.class).getFirst();
        assertThat(init.info().claudeSessionId()).isNotEmpty();
        assertThat(init.info().model()).isEqualTo("claude-haiku-5-5");
        assertThat(init.info().capabilities()).contains("interrupt_receipt_v1");

        List<Block.ToolCall> calls = blocks(Block.ToolCall.class);
        assertThat(calls).extracting(Block.ToolCall::name).containsExactly("Bash", "AskUserQuestion");
        assertThat(calls.get(0).status()).isEqualTo(Block.ToolStatus.DONE);
        assertThat(calls.get(0).inputJson()).contains("touch probe-file.txt");
        assertThat(calls.get(0).resultText()).isEqualTo("(Bash completed with no output)");
        assertThat(calls.get(1).resultText()).contains("\"What is your favourite colour?\"=\"Red\"");

        assertThat(blocks(Block.AssistantText.class)).extracting(Block.AssistantText::text)
                .containsExactly("DONE", "You answered **red**.");
        assertThat(blocks(Block.AssistantText.class)).noneMatch(Block.AssistantText::streaming);

        List<Intent.SessionTurnEnded> ends = sink.of(Intent.SessionTurnEnded.class);
        assertThat(ends).hasSize(2).allSatisfy(e -> {
            assertThat(e.queued()).isZero();
            assertThat(e.error()).isFalse();
            assertThat(e.contextTokens()).isPositive();
            assertThat(e.contextWindow()).isEqualTo(1_000_000L);
        });
        assertThat(ends.get(1).costUsd()).isGreaterThan(0.0);
        // the context is the last API call's input, not result.usage, which sums every call of the turn
        assertThat(ends).extracting(Intent.SessionTurnEnded::contextTokens).containsExactly(34_699L, 35_134L);
        assertThat(blocks(Block.TurnEnd.class)).hasSize(2);
        assertThat(ends).as("each turn's last answer, which a checkpoint carries").extracting(Intent.SessionTurnEnded::result)
                .containsExactly("DONE", "You answered **red**.");
        assertThat(blocks(Block.Note.class)).filteredOn(n -> n.kind() == Block.NoteKind.INIT).hasSize(1);
    }

    /// The context follows each top-level model call of a live turn, not only its end; a stored conversation's calls send
    /// nothing as they replay, then one report when it is loaded: its last call's tokens with the window the metrics
    /// kept, or the metrics' own tokens when the replay had none (record 120).
    @Test
    void theContextFollowsEachModelCallAndAStoredConversationReportsItOnce() {
        mapper.prompt("Run the shell command: touch probe-file.txt", "one");
        Fixtures.stream("approvals-and-question").forEach(mapper::accept);
        List<Intent.SessionContext> live = sink.of(Intent.SessionContext.class);
        assertThat(live).as("one a model call, before each turn's end").hasSizeGreaterThan(sink.of(Intent.SessionTurnEnded.class).size());
        assertThat(live).allSatisfy(c -> assertThat(c.tokens()).isPositive());
        assertThat(live.getLast().tokens()).isEqualTo(35_134L);

        RecordingSink stored = new RecordingSink();
        StreamMapper replay = new StreamMapper("old", new Transcript(), stored, new ObjectMapper());
        replay.acceptHistory("{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"model\":\"m\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":10,\"cache_creation_input_tokens\":100,\"cache_read_input_tokens\":400000}}}");
        assertThat(stored.of(Intent.SessionContext.class)).as("nothing while replaying").isEmpty();
        replay.reportContext(Optional.of(new long[]{588_979L, 1_000_000L}));
        assertThat(stored.of(Intent.SessionContext.class)).containsExactly(new Intent.SessionContext("old", 400_110L, 1_000_000L));

        RecordingSink empty = new RecordingSink();
        StreamMapper none = new StreamMapper("new", new Transcript(), empty, new ObjectMapper());
        none.reportContext(Optional.of(new long[]{588_979L, 1_000_000L}));
        assertThat(empty.of(Intent.SessionContext.class)).as("the metrics' own when the replay had none")
                .containsExactly(new Intent.SessionContext("new", 588_979L, 1_000_000L));
        new StreamMapper("unknown", new Transcript(), empty, new ObjectMapper()).reportContext(Optional.empty());
        assertThat(empty.of(Intent.SessionContext.class)).as("nothing known, nothing sent").hasSize(1);
    }

    @Test
    void queuedPromptCompactionAndInterruptRecording() {
        Fixtures.stream("queued-compact-interrupt").forEach(mapper::accept);
        // after /compact the context is the compacted size until the next call reports one
        assertThat(sink.of(Intent.SessionTurnEnded.class)).extracting(Intent.SessionTurnEnded::contextTokens)
                .containsExactly(34_824L, 35_205L, 3_812L, 31_921L);

        // the prompt sent mid-turn was folded into the running turn: one answer
        assertThat(blocks(Block.AssistantText.class)).extracting(Block.AssistantText::text).contains("ALPHA\nBRAVO");
        assertThat(blocks(Block.Note.class)).extracting(Block.Note::kind)
                .contains(Block.NoteKind.COMPACT, Block.NoteKind.INTERRUPT, Block.NoteKind.COMMAND);

        List<Intent.SessionTurnEnded> ends = sink.of(Intent.SessionTurnEnded.class);
        assertThat(ends).hasSize(4);
        assertThat(ends.getLast().error()).isTrue();
        assertThat(ends.getLast().message()).isEqualTo("interrupted");

        Block.ToolCall sleep30 = blocks(Block.ToolCall.class).getLast();
        assertThat(sleep30.inputJson()).contains("sleep 30");
        assertThat(sleep30.status()).isEqualTo(Block.ToolStatus.ERROR);

        assertThat(sink.of(Intent.SessionActivity.class)).extracting(Intent.SessionActivity::activity)
                .contains("requesting", "compacting", "");
    }

    @Test
    void clearRecordingStartsANewConversationWithAnEmptyContext() {
        Fixtures.stream("clear").forEach(mapper::accept);
        List<String> conversations = sink.of(Intent.SessionInit.class).stream().map(i -> i.info().claudeSessionId()).distinct().toList();
        assertThat(conversations).as("the clear's init names a new conversation").hasSize(2);
        // one, then the clear's turn (no model call), then two in the new conversation
        List<Long> context = sink.of(Intent.SessionTurnEnded.class).stream().map(Intent.SessionTurnEnded::contextTokens).toList();
        assertThat(context).hasSize(3);
        assertThat(context.get(0)).isPositive();
        assertThat(context.get(1)).as("cleared").isZero();
        assertThat(context.get(2)).isPositive();
        assertThat(blocks(Block.Note.class)).extracting(Block.Note::text).contains("Conversation cleared (clear); a new one starts");
    }

    @Test
    void streamedTextGrowsInPlaceAndTheFinalMessageReplacesIt() {
        mapper.accept("""
                {"type":"stream_event","api_message_id":"m1","parent_tool_use_id":null,"event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}""");
        mapper.accept("""
                {"type":"stream_event","api_message_id":"m1","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}""");
        mapper.accept("""
                {"type":"stream_event","api_message_id":"m1","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}}""");
        assertThat(blocks()).singleElement().isEqualTo(new Block.AssistantText("m1", 0, "Hello", true, ""));

        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[{"type":"text","text":"Hello."}]}}""");
        assertThat(blocks()).singleElement().isEqualTo(new Block.AssistantText("m1", 0, "Hello.", false, ""));
    }

    @Test
    void subagentBlocksCarryTheirParent() {
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Agent","input":{"prompt":"look"}}]}}""");
        mapper.accept("""
                {"type":"user","parent_tool_use_id":"t1","message":{"content":"look"}}""");
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":"t1","message":{"id":"m2","content":[{"type":"text","text":"found it"}]}}""");
        mapper.accept("""
                {"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"report"}]}]}}""");

        assertThat(blocks()).hasSize(3);
        assertThat(blocks().get(1)).isEqualTo(new Block.UserPrompt("look", "subagent", "t1"));
        assertThat(blocks().get(2).parent()).isEqualTo("t1");
        assertThat(((Block.ToolCall) blocks().get(0)).resultText()).isEqualTo("report");
    }

    /** The event shapes Claude Code 2.1.302 sent for a foreground and a background sub-agent (read on 2026-10-18). */
    @Test
    void aSubagentEndsAtItsCallsResultOrForABackgroundOneAtItsTaskNotification() {
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[{"type":"tool_use","id":"fg","name":"Agent","input":{"prompt":"look"}}]}}""");
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m2","content":[{"type":"tool_use","id":"bg","name":"Agent","input":{"prompt":"wait","run_in_background":true}}]}}""");
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m3","content":[{"type":"tool_use","id":"sh","name":"Bash","input":{"command":"ls"}}]}}""");
        assertThat(mapper.agentEnded("fg")).isFalse();

        mapper.accept("""
                {"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"bg","content":[{"type":"text","text":"Async agent launched successfully. (This tool result is internal metadata.)\\nagentId: a8280b64a05ce6dc1 (internal ID)\\nThe agent is working in the background."}]}]}}""");
        mapper.accept("""
                {"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"sh","content":"out"}]}}""");
        assertThat(mapper.agentEnded("bg")).as("a launch is no end").isFalse();
        assertThat(mapper.agentEnded("sh")).as("not a sub-agent").isFalse();

        mapper.accept("""
                {"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"fg","content":[{"type":"text","text":"report"}]}]}}""");
        assertThat(mapper.agentEnded("fg")).isTrue();

        mapper.accept("""
                {"type":"system","subtype":"task_notification","task_id":"a8280b64a05ce6dc1","tool_use_id":"bg","status":"running","summary":"wait"}""");
        assertThat(mapper.agentEnded("bg")).isFalse();
        mapper.accept("""
                {"type":"system","subtype":"task_notification","task_id":"a8280b64a05ce6dc1","tool_use_id":"bg","status":"completed","output_file":"","summary":"wait"}""");
        assertThat(mapper.agentEnded("bg")).isTrue();
    }

    @Test
    void historyKeepsTypedPromptsAndSkipsMetaLines() {
        mapper.acceptHistory("""
                {"type":"user","message":{"role":"user","content":"why is it slow"},"cwd":"/w"}""");
        mapper.acceptHistory("""
                {"type":"user","isMeta":true,"message":{"role":"user","content":"<system-reminder>x</system-reminder>"}}""");
        mapper.acceptHistory("""
                {"type":"user","message":{"role":"user","content":"<command-name>/compact</command-name><command-args>focus</command-args>"}}""");
        mapper.acceptHistory("""
                {"type":"ai-title","aiTitle":"slow"}""");
        assertThat(blocks()).containsExactly(
                new Block.UserPrompt("why is it slow", "", ""),
                new Block.Note(Block.NoteKind.COMMAND, "/compact focus", ""));
    }

    @Test
    void aStoredTurnEndsAfterTheLastLineOfTheMessageThatStoppedAtItsEnd() {
        // shapes as version 2.1.302 stores them: one line per content block, each with its message's stop reason
        mapper.acceptHistory("""
                {"type":"user","message":{"role":"user","content":"why is it slow"}}""");
        mapper.acceptHistory("""
                {"type":"assistant","isSidechain":false,"message":{"id":"m1","role":"assistant","stop_reason":"tool_use","content":[{"type":"text","text":"Looking."}]}}""");
        mapper.acceptHistory("""
                {"type":"assistant","isSidechain":false,"message":{"id":"m1","role":"assistant","stop_reason":"tool_use","content":[{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}""");
        mapper.acceptHistory("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"x"}]}}""");
        mapper.acceptHistory("""
                {"type":"assistant","isSidechain":true,"message":{"id":"s1","role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"a subagent's end"}]}}""");
        mapper.acceptHistory("""
                {"type":"assistant","isSidechain":false,"message":{"id":"m2","role":"assistant","stop_reason":"end_turn","content":[{"type":"thinking","thinking":"so"}]}}""");
        mapper.acceptHistory("""
                {"type":"assistant","isSidechain":false,"message":{"id":"m2","role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"The cache misses."}]}}""");
        mapper.acceptHistory("""
                {"type":"system","subtype":"stop_hook_summary","isSidechain":false}""");
        mapper.acceptHistory("""
                {"type":"user","message":{"role":"user","content":"and now"}}""");
        mapper.acceptHistory("""
                {"type":"assistant","isSidechain":false,"message":{"id":"m3","role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"Fixed."}]}}""");
        mapper.endHistory();
        mapper.endHistory();

        List<String> shape = blocks().stream().map(b -> switch (b) {
            case Block.UserPrompt p -> "prompt " + p.text();
            case Block.AssistantText a -> "text " + a.text();
            case Block.TurnEnd t -> t.timed() ? "timed end" : "end";
            default -> b.getClass().getSimpleName();
        }).toList();
        assertThat(shape).as("an end after the message's last line, none for a subagent, one at the end of the file").containsExactly(
                "prompt why is it slow", "text Looking.", "ToolCall", "text a subagent's end", "Thinking", "text The cache misses.", "end",
                "prompt and now", "text Fixed.", "end");
    }

    @Test
    void aStoredConversationCutOffMidTurnHasNoEnd() {
        mapper.acceptHistory("""
                {"type":"user","message":{"role":"user","content":"go"}}""");
        mapper.acceptHistory("""
                {"type":"assistant","message":{"id":"m1","role":"assistant","stop_reason":"tool_use","content":[{"type":"text","text":"Checking."}]}}""");
        mapper.endHistory();
        assertThat(blocks(Block.TurnEnd.class)).isEmpty();
    }

    @Test
    void theBackgroundSetIsTheSessionsBackgroundWork() {
        // shapes as Claude Code 2.1.300 sends them for a background agent that runs a shell command of its own
        mapper.accept("""
                {"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"a1","run_id":"r1","task_type":"local_agent","subagent_type":"general-purpose","description":"Background sleep and pong"}]}""");
        mapper.accept("""
                {"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"t1","description":"Background sleep and pong","is_backgrounded":true,"task_type":"local_agent"}""");
        mapper.accept("""
                {"type":"system","subtype":"task_started","task_id":"b2","owned_by_subagent":true,"description":"Sleep for 5 seconds","is_backgrounded":false,"parent_task_id":"a1","task_type":"local_bash"}""");
        mapper.accept("""
                {"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"a1","task_type":"local_agent","description":"Background sleep and pong"},{"task_id":"d1","task_type":"dream","description":"dreaming","ambient":true}]}""");
        mapper.accept("""
                {"type":"system","subtype":"task_notification","task_id":"a1","tool_use_id":"t1","status":"completed","summary":"done"}""");
        mapper.accept("""
                {"type":"system","subtype":"background_tasks_changed","tasks":[]}""");

        BackgroundTask agent = new BackgroundTask("a1", "local_agent", "Background sleep and pong", 0L);
        assertThat(sink.of(Intent.SessionTasksChanged.class)).as("the agent's own command and a task Claude Code runs by itself are not in it")
                .extracting(Intent.SessionTasksChanged::open).containsExactly(List.of(agent), List.of(agent), List.of());
    }

    @Test
    void aCallOfTheMainConversationRunsATurnAndASubagentCallDoesNot() {
        mapper.accept("""
                {"type":"stream_event","parent_tool_use_id":"t1","event":{"type":"message_start","message":{"id":"s1"}}}""");
        assertThat(sink.of(Intent.SessionTurnRunning.class)).as("a background agent's call starts no turn").isEmpty();
        mapper.accept("""
                {"type":"stream_event","parent_tool_use_id":null,"event":{"type":"message_start","message":{"id":"m1"}}}""");
        assertThat(sink.of(Intent.SessionTurnRunning.class)).containsExactly(new Intent.SessionTurnRunning("work"));
    }

    @Test
    void garbageLinesAreIgnored() {
        assertThat(mapper.accept("not json")).isEmpty();
        assertThat(blocks()).isEmpty();
    }

    @Test
    void endingRunningToolsClosesOnlyTheCallsWithoutAResult() {
        mapper.accept("{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"Bash\",\"input\":{}},"
                + "{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"Read\",\"input\":{}}]}}");
        mapper.accept("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"b\",\"content\":\"ok\"}]}}");

        assertThat(mapper.endRunningTools()).isEqualTo(1);

        List<Block.ToolCall> calls = blocks(Block.ToolCall.class);
        assertThat(calls.get(0).status()).isEqualTo(Block.ToolStatus.ERROR);
        assertThat(calls.get(0).resultText()).isEqualTo(StreamMapper.NO_RESULT);
        assertThat(calls.get(1).status()).isEqualTo(Block.ToolStatus.DONE);
        assertThat(mapper.endRunningTools()).isZero();
    }

    /// Each top-level message of a turn sends what it ran at, as its transcript line gives it: the first with
    /// `firstOfTurn`, so the session's own setting shows; a message's later blocks ask no more; a sub-agent's message
    /// asks nothing; a message whose line is not yet written arms one read later (and is read again on the retry once its
    /// line is there), else for the last time at the turn's end, before the turn's end is sent, with the stream's model
    /// alone when the line never came.
    @Test
    void eachTopLevelMessageSendsTheModelAndEffortItsTranscriptLineGives() {
        List<String> asked = new ArrayList<>();
        Map<String, RunSettings.Ran> written = new HashMap<>();
        List<Runnable> retries = new ArrayList<>();
        mapper.ranFrom((conversation, message) -> {
            asked.add(conversation + "/" + message);
            return Optional.ofNullable(written.get(message));
        }, retries::add);
        mapper.accept("""
                {"type":"system","subtype":"init","session_id":"conv-1","model":"claude-opus-5-5","cwd":"/w","claude_code_version":"2.1.310","permissionMode":"default"}""");
        written.put("m1", new RunSettings.Ran("claude-opus-5-5", "medium"));
        written.put("m2", new RunSettings.Ran("claude-opus-5-5", "high"));
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","model":"claude-opus-5-5","content":[{"type":"tool_use","id":"t1","name":"Skill","input":{"skill":"deep"}}]}}""");
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","model":"claude-opus-5-5","content":[{"type":"text","text":"again"}]}}""");
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":"t9","message":{"id":"s1","model":"claude-haiku-5-5","content":[{"type":"text","text":"inside"}]}}""");
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m2","model":"claude-opus-5-5","content":[{"type":"text","text":"deep"}]}}""");
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m3","model":"claude-sonnet-5-5","content":[{"type":"text","text":"not written yet"}]}}""");
        assertThat(retries).as("m3 not written: one read later is armed").hasSize(1);
        mapper.accept("""
                {"type":"result","subtype":"success","is_error":false,"result":"","total_cost_usd":0,"duration_ms":3,"num_turns":3}""");
        retries.getFirst().run();

        assertThat(sink.of(Intent.SessionRanAt.class)).containsExactly(
                new Intent.SessionRanAt("work", "claude-opus-5-5", "medium", true),
                new Intent.SessionRanAt("work", "claude-opus-5-5", "high", false),
                new Intent.SessionRanAt("work", "claude-sonnet-5-5", "", false));
        assertThat(asked).as("one read per message, m3's last at the turn's end, none on the retry after it").containsExactly(
                "conv-1/m1", "conv-1/m2", "conv-1/m3", "conv-1/m3");
        List<Intent> all = sink.intents();
        assertThat(all.indexOf(new Intent.SessionRanAt("work", "claude-sonnet-5-5", "", false)))
                .isLessThan(all.indexOf(sink.of(Intent.SessionTurnEnded.class).getFirst()));

        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m4","model":"claude-opus-5-5","content":[{"type":"text","text":"next turn"}]}}""");
        assertThat(retries).as("m4 not written yet").hasSize(2);
        written.put("m4", new RunSettings.Ran("claude-opus-5-5", "low"));
        retries.getLast().run();
        assertThat(sink.of(Intent.SessionRanAt.class)).last().as("the next turn's first message, read on the retry").isEqualTo(
                new Intent.SessionRanAt("work", "claude-opus-5-5", "low", true));
    }
}
