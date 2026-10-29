package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// [MetricsLog] persists live turn and delegated-task measurements while ignoring replayed history.
///
/// - Writes completed turn and delegated-task fields to JSON lines.
/// - Measures background work through its completion notification.
/// - Counts each stored message once when deriving delegated-task usage.
/// - Leaves storage untouched when metrics are disabled.
class MetricsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    /** 2026-10-28T10:00:00Z, then as the test moves it. */
    private final AtomicLong now = new AtomicLong(1_793_181_600_000L);

    private List<JsonNode> lines() throws IOException {
        List<JsonNode> out = new ArrayList<>();
        Path file = dir.resolve("metrics-2026-10-28.jsonl");
        if (!Files.exists(file)) {
            return out;
        }
        for (String l : Files.readAllLines(file)) {
            out.add(JSON.readTree(l));
        }
        return out;
    }

    /// A live stream writes a delegated-task event and then a turn event with timing, model, usage, nested call count,
    /// and error state; replaying equivalent history adds no current-time measurements.
    @Test
    void aTurnAndASubagentLeaveTheirEventsAndAReplayNone() throws IOException {
        MetricsLog log = new MetricsLog(dir, JSON, now::get);
        List<ObjectNode> ended = new ArrayList<>();
        StreamMapper mapper = new StreamMapper("work", new Transcript(), new RecordingSink(), JSON);
        mapper.metrics(log, e -> {
            ended.add(e);
            log.write(e);
        });
        mapper.effortFrom(model -> "high");

        mapper.accept("""
                {"type":"system","subtype":"init","session_id":"conv-1","model":"claude-opus-5-5","cwd":"/w","claude_code_version":"2.1.400","permissionMode":"default"}""");
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Agent","input":{"description":"Review the diff","subagent_type":"reviewer","model":"sonnet","prompt":"look"}}]}}""");
        now.addAndGet(90_000);
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":"t1","message":{"id":"m2","content":[{"type":"tool_use","id":"r1","name":"Read","input":{"file_path":"/w/a"}}]}}""");
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":"r1","message":{"id":"m3","content":[{"type":"tool_use","id":"g1","name":"Grep","input":{"pattern":"x"}}]}}""");
        mapper.accept("""
                {"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"2 findings"}]}]}}""");
        mapper.accept("""
                {"type":"result","subtype":"success","is_error":false,"result":"done","duration_ms":120000,"duration_api_ms":80000,"num_turns":4,"total_cost_usd":1.25,"queued_turn_count":0,"usage":{"input_tokens":10,"output_tokens":500,"cache_read_input_tokens":9000,"cache_creation_input_tokens":300},"modelUsage":{"claude-opus-5-5":{"inputTokens":10,"outputTokens":500,"contextWindow":1000000}}}""");

        List<JsonNode> events = lines();
        assertThat(events).extracting(e -> e.path("event").asText()).containsExactly("agent", "turn");
        JsonNode agent = events.get(0);
        assertThat(agent.path("ts").asText()).isEqualTo("2026-10-28T10:01:30Z");
        assertThat(agent.path("session").asText()).isEqualTo("work");
        assertThat(agent.path("conversation").asText()).isEqualTo("conv-1");
        assertThat(agent.path("tool_use_id").asText()).isEqualTo("t1");
        assertThat(agent.path("type").asText()).isEqualTo("reviewer");
        assertThat(agent.path("description").asText()).isEqualTo("Review the diff");
        assertThat(agent.path("model_asked").asText()).isEqualTo("sonnet");
        assertThat(agent.path("parent_model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(agent.path("status").asText()).isEqualTo("done");
        assertThat(agent.path("background").asBoolean()).isFalse();
        assertThat(agent.path("duration_ms").asLong()).isEqualTo(90_000);
        assertThat(agent.path("tools").asInt()).as("the tool calls inside it at any depth").isEqualTo(2);

        JsonNode turn = events.get(1);
        assertThat(turn.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(turn.path("effort").asText()).isEqualTo("high");
        assertThat(turn.path("duration_ms").asLong()).isEqualTo(120_000);
        assertThat(turn.path("api_ms").asLong()).isEqualTo(80_000);
        assertThat(turn.path("calls").asInt()).isEqualTo(4);
        assertThat(turn.path("usage").path("cache_read_input_tokens").asLong()).isEqualTo(9000);
        assertThat(turn.path("models").path("claude-opus-5-5").path("outputTokens").asLong()).isEqualTo(500);
        assertThat(turn.path("window").asLong()).isEqualTo(1_000_000);
        assertThat(turn.path("error").asBoolean()).isFalse();

        // a stored conversation read again says nothing that happens now
        StreamMapper replay = new StreamMapper("work", new Transcript(), new RecordingSink(), JSON);
        replay.metrics(log, log::write);
        replay.acceptHistory("""
                {"type":"assistant","message":{"id":"h1","content":[{"type":"tool_use","id":"h2","name":"Agent","input":{"description":"old"}}]}}""");
        replay.acceptHistory("""
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"h2","content":"old report"}]}}""");
        assertThat(lines()).hasSize(2);
    }

    /// Background task `b1` remains unwritten at launch and receives its 600-second duration only at the later
    /// completion notification, which marks it done.
    @Test
    void aBackgroundSubagentEndsAtItsTaskNotificationNotItsLaunch() throws IOException {
        MetricsLog log = new MetricsLog(dir, JSON, now::get);
        StreamMapper mapper = new StreamMapper("work", new Transcript(), new RecordingSink(), JSON);
        mapper.metrics(log, log::write);
        mapper.accept("""
                {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[{"type":"tool_use","id":"b1","name":"Agent","input":{"description":"Watch CI","run_in_background":true}}]}}""");
        mapper.accept("""
                {"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"b1","content":[{"type":"text","text":"Async agent launched successfully. agentId: a58"}]}]}}""");
        assertThat(lines()).as("a launch is no end").isEmpty();
        now.addAndGet(600_000);
        mapper.accept("""
                {"type":"system","subtype":"task_notification","tool_use_id":"b1","status":"completed"}""");
        assertThat(lines()).singleElement().satisfies(e -> {
            assertThat(e.path("event").asText()).isEqualTo("agent");
            assertThat(e.path("background").asBoolean()).isTrue();
            assertThat(e.path("duration_ms").asLong()).isEqualTo(600_000);
            assertThat(e.path("status").asText()).isEqualTo("done");
        });
    }

    /// Repeated stored message `m1` contributes its usage once, while distinct `m2` adds its own tokens and model to
    /// the derived spending summary despite a malformed trailing line.
    @Test
    void aSubagentsSpendIsReadFromItsFileEachMessageOnce() throws IOException {
        Path file = dir.resolve("agent-x.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"user\",\"message\":{\"content\":\"task\"}}",
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"model\":\"claude-sonnet-5-5\",\"usage\":{\"input_tokens\":5,\"output_tokens\":40,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":7}}}",
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"model\":\"claude-sonnet-5-5\",\"usage\":{\"input_tokens\":5,\"output_tokens\":40,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":7}}}",
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m2\",\"model\":\"claude-sonnet-5-5\",\"usage\":{\"input_tokens\":1,\"output_tokens\":10,\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":0}}}",
                "not json") + "\n");
        JsonNode spend = SubagentFiles.spend(file, JSON);
        assertThat(spend.path("calls").asLong()).as("a message stored a block a line counts once").isEqualTo(2);
        assertThat(spend.path("output").asLong()).isEqualTo(50);
        assertThat(spend.path("cache_read").asLong()).isEqualTo(300);
        assertThat(spend.path("models")).extracting(JsonNode::asText).containsExactly("claude-sonnet-5-5");
    }

    /// The disabled metrics sink accepts a turn event without creating any file in the supplied temporary directory.
    @Test
    void withoutADataRootNothingIsWritten() {
        MetricsLog.NONE.write(MetricsLog.NONE.event("turn"));
        assertThat(dir).isEmptyDirectory();
    }

    /// [MetricsLog#lastContext] finds a conversation's last turn, newest file and line first, across days, and nothing for
    /// an unknown conversation or a log with no folder.
    @Test
    void theLastContextOfAConversationIsItsNewestTurns() throws Exception {
        Path days = Files.createDirectories(dir.resolve("days"));
        Files.writeString(days.resolve("metrics-2026-10-28.jsonl"), String.join("\n",
                "{\"ts\":\"2026-10-28T20:00:00Z\",\"event\":\"turn\",\"conversation\":\"c1\",\"context\":100,\"window\":200000}",
                "{\"ts\":\"2026-10-28T21:00:00Z\",\"event\":\"turn\",\"conversation\":\"c1\",\"context\":588979,\"window\":1000000}",
                "{\"ts\":\"2026-10-28T21:01:00Z\",\"event\":\"agent\",\"conversation\":\"c1\",\"context\":7}", ""));
        Files.writeString(days.resolve("metrics-2026-10-29.jsonl"), "{\"ts\":\"2026-10-29T06:00:00Z\",\"event\":\"usage\"}\n");
        MetricsLog log = new MetricsLog(days, JSON, () -> Instant.parse("2026-10-29T08:00:00Z").toEpochMilli());
        assertThat(log.lastContext("c1")).hasValueSatisfying(c -> assertThat(c).containsExactly(588_979L, 1_000_000L));
        assertThat(log.lastContext("c2")).isEmpty();
        assertThat(MetricsLog.NONE.lastContext("c1")).isEmpty();
    }
}
