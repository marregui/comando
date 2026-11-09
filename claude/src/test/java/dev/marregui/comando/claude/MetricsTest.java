package dev.marregui.comando.claude;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [MetricsLog] keeps live turn and sub-agent measurements and none from replayed history.
///
/// - Writes a finished turn's and sub-agent's fields as JSON lines.
/// - Measures a background sub-agent up to its task notification.
/// - Counts each stored message once in a sub-agent's spend.
/// - Leaves storage untouched when metrics are disabled.
/// - Writes the queues' depths and peaks as a `queues` event.
/// - Makes its folder owner-only when it writes into one that is gone.
/// - Writes the file as before when the QuestDB sink fails, and sends each event to QuestDB as well.
/// - Takes a conversation's last context from QuestDB, and from the files when QuestDB has no row or is down.
class MetricsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    /** 2026-10-28T10:00:00Z, then as the test moves it. */
    private final AtomicLong now = new AtomicLong(1_793_181_600_000L);

    /** A conversation id as Claude Code makes them. */
    private static final String CONVERSATION = "0e7a3f52-1c2d-4e5f-8a9b-0c1d2e3f4a5b";

    private List<JsonNode> lines() throws IOException {
        final List<JsonNode> out = new ArrayList<>();
        final Path file = dir.resolve("metrics-2026-10-28.jsonl");
        if (!Files.exists(file)) {
            return out;
        }
        for (final String l : Files.readAllLines(file)) {
            out.add(JSON.readTree(l));
        }
        return out;
    }

    /// A live stream writes an `agent` event, then a `turn` event with timing, model, usage, nested call count and
    /// error state; replaying the same history writes nothing.
    @Test
    void aTurnAndASubagentLeaveTheirEventsAndAReplayNone() throws IOException {
        final MetricsLog log = new MetricsLog(dir, JSON, now::get);
        final List<ObjectNode> ended = new ArrayList<>();
        final StreamMapper mapper = new StreamMapper("work", new Transcript(), new RecordingSink(), JSON);
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

        final List<JsonNode> events = lines();
        assertThat(events).extracting(e -> e.path("event").asString()).containsExactly("agent", "turn");
        final JsonNode agent = events.get(0);
        assertThat(agent.path("ts").asString()).isEqualTo("2026-10-28T10:01:30Z");
        assertThat(agent.path("session").asString()).isEqualTo("work");
        assertThat(agent.path("conversation").asString()).isEqualTo("conv-1");
        assertThat(agent.path("tool_use_id").asString()).isEqualTo("t1");
        assertThat(agent.path("type").asString()).isEqualTo("reviewer");
        assertThat(agent.path("description").asString()).isEqualTo("Review the diff");
        assertThat(agent.path("model_asked").asString()).isEqualTo("sonnet");
        assertThat(agent.path("parent_model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(agent.path("status").asString()).isEqualTo("done");
        assertThat(agent.path("background").asBoolean()).isFalse();
        assertThat(agent.path("duration_ms").asLong()).isEqualTo(90_000);
        assertThat(agent.path("tools").asInt()).as("the tool calls inside it at any depth").isEqualTo(2);

        final JsonNode turn = events.get(1);
        assertThat(turn.path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(turn.path("effort").asString()).isEqualTo("high");
        assertThat(turn.path("duration_ms").asLong()).isEqualTo(120_000);
        assertThat(turn.path("api_ms").asLong()).isEqualTo(80_000);
        assertThat(turn.path("calls").asInt()).isEqualTo(4);
        assertThat(turn.path("usage").path("cache_read_input_tokens").asLong()).isEqualTo(9000);
        assertThat(turn.path("models").path("claude-opus-5-5").path("outputTokens").asLong()).isEqualTo(500);
        assertThat(turn.path("window").asLong()).isEqualTo(1_000_000);
        assertThat(turn.path("error").asBoolean()).isFalse();

        // a stored conversation read again says nothing that happens now
        final StreamMapper replay = new StreamMapper("work", new Transcript(), new RecordingSink(), JSON);
        replay.metrics(log, log::write);
        replay.acceptHistory("""
                {"type":"assistant","message":{"id":"h1","content":[{"type":"tool_use","id":"h2","name":"Agent","input":{"description":"old"}}]}}""");
        replay.acceptHistory("""
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"h2","content":"old report"}]}}""");
        assertThat(lines()).hasSize(2);
    }

    /// Background sub-agent `b1` is not written at its launch; its task notification writes it, done, with its 600 s
    /// duration.
    @Test
    void aBackgroundSubagentEndsAtItsTaskNotificationNotItsLaunch() throws IOException {
        final MetricsLog log = new MetricsLog(dir, JSON, now::get);
        final StreamMapper mapper = new StreamMapper("work", new Transcript(), new RecordingSink(), JSON);
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
            assertThat(e.path("event").asString()).isEqualTo("agent");
            assertThat(e.path("background").asBoolean()).isTrue();
            assertThat(e.path("duration_ms").asLong()).isEqualTo(600_000);
            assertThat(e.path("status").asString()).isEqualTo("done");
        });
    }

    /// A message stored on two lines (`m1`) counts once and `m2` adds its own tokens; a malformed last line is
    /// skipped.
    @Test
    void aSubagentsSpendIsReadFromItsFileEachMessageOnce() throws IOException {
        final Path file = dir.resolve("agent-x.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"user\",\"message\":{\"content\":\"task\"}}",
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"model\":\"claude-sonnet-5-5\",\"usage\":{\"input_tokens\":5,\"output_tokens\":40,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":7}}}",
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"model\":\"claude-sonnet-5-5\",\"usage\":{\"input_tokens\":5,\"output_tokens\":40,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":7}}}",
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m2\",\"model\":\"claude-sonnet-5-5\",\"usage\":{\"input_tokens\":1,\"output_tokens\":10,\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":0}}}",
                "not json") + "\n");
        final JsonNode spend = SubagentFiles.spend(file, JSON);
        assertThat(spend.path("calls").asLong()).as("a message stored a block a line counts once").isEqualTo(2);
        assertThat(spend.path("output").asLong()).isEqualTo(50);
        assertThat(spend.path("cache_read").asLong()).isEqualTo(300);
        assertThat(spend.path("models")).extracting(JsonNode::asString).containsExactly("claude-sonnet-5-5");
    }

    /// [MetricsLog#NONE] takes a turn event and writes no file.
    @Test
    void withoutADataRootNothingIsWritten() {
        MetricsLog.NONE.write(MetricsLog.NONE.event("turn"));
        assertThat(dir).isEmptyDirectory();
    }

    /// [MetricsLog#lastContext] finds a conversation's last turn, newest file and line first, across days, and nothing
    /// for an unknown conversation or a log with no folder.
    @Test
    void theLastContextOfAConversationIsItsNewestTurns() throws Exception {
        final Path days = Files.createDirectories(dir.resolve("days"));
        Files.writeString(days.resolve("metrics-2026-10-28.jsonl"), String.join("\n",
                "{\"ts\":\"2026-10-28T20:00:00Z\",\"event\":\"turn\",\"conversation\":\"c1\",\"context\":100,\"window\":200000}",
                "{\"ts\":\"2026-10-28T21:00:00Z\",\"event\":\"turn\",\"conversation\":\"c1\",\"context\":588979,\"window\":1000000}",
                "{\"ts\":\"2026-10-28T21:01:00Z\",\"event\":\"agent\",\"conversation\":\"c1\",\"context\":7}", ""));
        Files.writeString(days.resolve("metrics-2026-10-29.jsonl"), "{\"ts\":\"2026-10-29T06:00:00Z\",\"event\":\"usage\"}\n");
        final MetricsLog log = new MetricsLog(days, JSON, () -> Instant.parse("2026-10-29T08:00:00Z").toEpochMilli());
        assertThat(log.lastContext("c1")).hasValueSatisfying(c -> assertThat(c).containsExactly(588_979L, 1_000_000L));
        assertThat(log.lastContext("c2")).isEmpty();
        assertThat(MetricsLog.NONE.lastContext("c1")).isEmpty();
    }

    /// The event's own fields decide, not their text: an `agent` event whose nested detail reads `"event":"turn"` and a
    /// turn of another conversation whose nested object names `c1` are not `c1`'s last turn.
    @Test
    void onlyATurnEventOfTheConversationItselfIsItsLastContext() throws Exception {
        final Path days = Files.createDirectories(dir.resolve("fields"));
        Files.writeString(days.resolve("metrics-2026-10-29.jsonl"), String.join("\n",
                "{\"ts\":\"2026-10-29T06:00:00Z\",\"event\":\"turn\",\"conversation\":\"c1\",\"context\":100,\"window\":200000}",
                "{\"ts\":\"2026-10-29T07:00:00Z\",\"event\":\"agent\",\"conversation\":\"c1\",\"detail\":{\"event\":\"turn\"},\"context\":7}",
                "{\"ts\":\"2026-10-29T07:30:00Z\",\"event\":\"turn\",\"conversation\":\"c2\",\"models\":{\"conversation\":\"c1\"},\"context\":9}", ""));
        final MetricsLog log = new MetricsLog(days, JSON, () -> Instant.parse("2026-10-29T08:00:00Z").toEpochMilli());
        assertThat(log.lastContext("c1")).hasValueSatisfying(c -> assertThat(c).containsExactly(100L, 200_000L));
    }

    /// A `queues` event lists each queue read with its depth and its peak since the reading before, so a queue can be
    /// bounded later from how it was used.
    @Test
    void aQueuesEventListsEachQueuesDepthAndPeak() throws Exception {
        final MetricsLog log = new MetricsLog(dir, JSON, now::get);
        log.queues(List.of(new Queues.Reading("intents", 0, 12), new Queues.Reading("claude-stdin-work", 1, 3)));
        final JsonNode e = lines().getLast();
        assertThat(e.path("event").asString()).isEqualTo("queues");
        assertThat(e.path("queues").findValuesAsString("name")).containsExactly("intents", "claude-stdin-work");
        assertThat(e.path("queues").get(0).path("peak").asInt()).isEqualTo(12);
        assertThat(e.path("queues").get(1).path("depth").asInt()).isEqualTo(1);
    }

    /// The metrics folder deleted while comando runs is made again owner-only (rule 15) by the next write, whatever the
    /// umask, as the start made it.
    @Test
    void aMetricsFolderDeletedWhileRunningComesBackOwnerOnly() throws Exception {
        final Path metrics = dir.resolve("data/metrics");
        final MetricsLog log = new MetricsLog(metrics, JSON, now::get);
        log.queues(List.of(new Queues.Reading("intents", 0, 1)));
        assertThat(Files.getPosixFilePermissions(metrics)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.isRegularFile(metrics.resolve("metrics-2026-10-28.jsonl"))).isTrue();
    }

    /// A sink whose endpoint throws on every call leaves the day's file byte for byte as a log without one writes it.
    @Test
    void aFailingSinkLeavesTheFileAsBefore() throws Exception {
        final Path plain = Files.createDirectories(dir.resolve("plain"));
        final Path sunk = Files.createDirectories(dir.resolve("sunk"));
        final QuestDbEndpoint failing = new QuestDbEndpoint() {
            @Override
            public Optional<URI> ensureUp() {
                throw new IllegalStateException("no server");
            }

            @Override
            public Optional<URI> live() {
                throw new IllegalStateException("no server");
            }
        };
        final List<Queues.Reading> readings = List.of(new Queues.Reading("intents", 0, 12));
        final MetricsLog alone = new MetricsLog(plain, JSON, now::get);
        alone.queues(readings);
        alone.write(alone.event("turn").put("conversation", CONVERSATION).put("context", 5));
        try (MetricsLog log = new MetricsLog(sunk, JSON, now::get, failing)) {
            log.queues(readings);
            log.write(log.event("turn").put("conversation", CONVERSATION).put("context", 5));
        }
        final String day = "metrics-2026-10-28.jsonl";
        assertThat(Files.readString(sunk.resolve(day))).isEqualTo(Files.readString(plain.resolve(day)));
    }

    /// An event written also reaches QuestDB, as a row of the data root the metrics folder sits in.
    @Test
    void anEventWrittenAlsoReachesQuestDb() throws Exception {
        final Path metrics = dir.resolve("data/metrics");
        final String expected = "comando_queue,instance=" + dir.resolve("data").toAbsolutePath()
                + ",name=intents depth=0i,peak=12i 1793181600000000000";
        try (MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb();
                MetricsLog log = new MetricsLog(metrics, JSON, now::get, MetricsSinkTest.Endpoint.at(server.uri()))) {
            log.queues(List.of(new Queues.Reading("intents", 0, 12)));
            await().atMost(Duration.ofSeconds(5)).until(() -> server.lines.contains(expected));
            assertThat(server.lines).as("the live event, and today's file imported at the first connection")
                    .containsOnly(expected);
        }
    }

    /// [MetricsLog#lastContext] takes QuestDB's answer over the files'; with no row in QuestDB, with QuestDB down
    /// and with none running, the files answer. An id that is not a conversation id never reaches a query.
    @Test
    void theLastContextComesFromQuestDbThenTheFiles() throws Exception {
        final Path days = Files.createDirectories(dir.resolve("days"));
        Files.writeString(days.resolve("metrics-2026-10-29.jsonl"), "{\"ts\":\"2026-10-29T06:00:00Z\",\"event\":\"turn\","
                + "\"conversation\":\"" + CONVERSATION + "\",\"context\":100,\"window\":200000}\n");
        try (MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb()) {
            final MetricsSinkTest.Endpoint endpoint = MetricsSinkTest.Endpoint.at(server.uri());
            try (MetricsLog log = new MetricsLog(days, JSON, () -> Instant.parse("2026-10-29T08:00:00Z").toEpochMilli(),
                    endpoint)) {
                server.selectAnswer.set("{\"query\":\"\",\"columns\":[{\"name\":\"context\",\"type\":\"LONG\"},"
                        + "{\"name\":\"context_window\",\"type\":\"LONG\"}],\"dataset\":[[588979,1000000]],"
                        + "\"count\":1}");
                assertThat(log.lastContext(CONVERSATION))
                        .hasValueSatisfying(c -> assertThat(c).containsExactly(588_979L, 1_000_000L));
                assertThat(server.queries).containsExactly("SELECT context, context_window FROM comando_turn"
                        + " WHERE conversation = '" + CONVERSATION + "' AND context > 0 ORDER BY ts DESC LIMIT 1");

                assertThat(log.lastContext("c1' OR '1'='1")).isEmpty();
                assertThat(server.queries).as("not a conversation id").hasSize(1);

                server.selectAnswer.set("{\"query\":\"\",\"columns\":[],\"dataset\":[],\"count\":0}");
                assertThat(log.lastContext(CONVERSATION)).as("no row")
                        .hasValueSatisfying(c -> assertThat(c).containsExactly(100L, 200_000L));

                server.down();
                assertThat(log.lastContext(CONVERSATION)).as("down")
                        .hasValueSatisfying(c -> assertThat(c).containsExactly(100L, 200_000L));

                endpoint.live.set(null);
                assertThat(log.lastContext(CONVERSATION)).as("none running")
                        .hasValueSatisfying(c -> assertThat(c).containsExactly(100L, 200_000L));
            }
        }
    }
}
