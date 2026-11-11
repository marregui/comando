package dev.marregui.comando.claude;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [MetricsLog] keeps live turn and sub-agent measurements, and none from replayed history, in QuestDB alone, here a
/// loopback server that answers as QuestDB does ([MetricsSinkTest.FakeQuestDb]).
///
/// - Sends a finished turn's and sub-agent's fields as their lines.
/// - Measures a background sub-agent up to its task notification.
/// - Counts each stored message once in a sub-agent's spend.
/// - Keeps nothing when metrics are disabled.
/// - Sends the queues' depths and peaks as `comando_queue` rows.
/// - Leaves out a queue whose reading repeats its last entry, idle or busy, and an event left with no queue.
/// - Loses an event QuestDB does not take, tells the user, and makes no file.
/// - Sends each event as a row of the instance it is built for.
/// - Takes a conversation's last context from QuestDB, and none when QuestDB has no row or is down.
class MetricsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    /** 2026-10-28T10:00:00Z, then as the test moves it. */
    private final AtomicLong now = new AtomicLong(1_793_181_600_000L);
    private final RecordingSink notices = new RecordingSink();

    /** A conversation id as Claude Code makes them. */
    private static final String CONVERSATION = "0e7a3f52-1c2d-4e5f-8a9b-0c1d2e3f4a5b";
    /** The start of the marker queue reading's line, which [#marked] writes. */
    private static final String MARK = "comando_queue,instance=/data,name=mark depth=0i,peak=0i ";
    private static final Duration WITHIN = Duration.ofSeconds(5);

    /// A live stream sends an `agent` row, then a `turn` row and its model's row with timing, model, usage, nested
    /// call count and error state; replaying the same history sends nothing.
    @Test
    void aTurnAndASubagentLeaveTheirRowsAndAReplayNone() throws IOException {
        try (final MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb();
                final MetricsLog log = log(server)) {
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

            await().atMost(WITHIN).until(() -> server.lines.size() == 3);
            assertThat(server.lines).extracting(l -> l.substring(0, l.indexOf(',')))
                    .containsExactly("comando_agent", "comando_turn", "comando_turn_model");
            assertThat(server.lines.get(0))
                    .startsWith("comando_agent,instance=/data,session=work,conversation=conv-1,type=reviewer,"
                            + "model_asked=sonnet,parent_model=claude-opus-5-5,status=done ")
                    .contains("tool_use_id=\"t1\"", "description=\"Review the diff\"", "background=f",
                            "duration_ms=90000i")
                    .as("the tool calls inside it at any depth").contains("tools=2i")
                    .endsWith(" 1793181690000000000");
            assertThat(server.lines.get(1))
                    .startsWith("comando_turn,instance=/data,session=work,conversation=conv-1,model=claude-opus-5-5,"
                            + "effort=high,")
                    .contains("duration_ms=120000i", "api_ms=80000i", "calls=4i", "cache_read_input_tokens=9000i",
                            "context_window=1000000i", "error=f");
            assertThat(server.lines.get(2))
                    .startsWith("comando_turn_model,instance=/data,conversation=conv-1,model=claude-opus-5-5 ")
                    .contains("output_tokens=500i");

            // a stored conversation read again says nothing that happens now
            final StreamMapper replay = new StreamMapper("work", new Transcript(), new RecordingSink(), JSON);
            replay.metrics(log, log::write);
            replay.acceptHistory("""
                    {"type":"assistant","message":{"id":"h1","content":[{"type":"tool_use","id":"h2","name":"Agent","input":{"description":"old"}}]}}""");
            replay.acceptHistory("""
                    {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"h2","content":"old report"}]}}""");
            marked(server, log);
            assertThat(server.lines).hasSize(4);
        }
    }

    /// Background sub-agent `b1` is not sent at its launch; its task notification sends it, done, with its 600 s
    /// duration.
    @Test
    void aBackgroundSubagentEndsAtItsTaskNotificationNotItsLaunch() throws IOException {
        try (final MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb();
                final MetricsLog log = log(server)) {
            final StreamMapper mapper = new StreamMapper("work", new Transcript(), new RecordingSink(), JSON);
            mapper.metrics(log, log::write);
            mapper.accept("""
                    {"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[{"type":"tool_use","id":"b1","name":"Agent","input":{"description":"Watch CI","run_in_background":true}}]}}""");
            mapper.accept("""
                    {"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"b1","content":[{"type":"text","text":"Async agent launched successfully. agentId: a58"}]}]}}""");
            marked(server, log);
            assertThat(server.lines).as("a launch is no end").singleElement().asString().startsWith(MARK);
            now.addAndGet(600_000);
            mapper.accept("""
                    {"type":"system","subtype":"task_notification","tool_use_id":"b1","status":"completed"}""");
            await().atMost(WITHIN).until(() -> server.lines.size() == 2);
            assertThat(server.lines.get(1)).startsWith("comando_agent,instance=/data,session=work,")
                    .contains(",status=done ", "background=t", "duration_ms=600000i");
        }
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

    /// [MetricsLog#NONE] takes a turn event, writes no file and knows no context.
    @Test
    void withoutQuestDbNothingIsKept() {
        MetricsLog.NONE.write(MetricsLog.NONE.event("turn"));
        assertThat(dir).isEmptyDirectory();
        assertThat(MetricsLog.NONE.lastContext(CONVERSATION)).isEmpty();
    }

    /// A `queues` event is a `comando_queue` row per queue read with its depth and its peak since the reading before,
    /// so a queue can be bounded later from how it was used.
    @Test
    void aQueuesEventIsARowPerQueueWithItsDepthAndPeak() throws Exception {
        try (final MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb();
                final MetricsLog log = log(server)) {
            log.queues(List.of(new Queues.Reading("intents", 0, 12), new Queues.Reading("claude-stdin-work", 1, 3)));
            await().atMost(WITHIN).until(() -> server.lines.size() == 2);
            assertThat(server.lines).containsExactly(
                    "comando_queue,instance=/data,name=intents depth=0i,peak=12i 1793181600000000000",
                    "comando_queue,instance=/data,name=claude-stdin-work depth=1i,peak=3i 1793181600000000000");
        }
    }

    /// A queue read idle (depth 0) with the peak last written for it is left out of the event, however often it is
    /// read; one never written is entered once, and an event left with no queue is not sent at all.
    @Test
    void anIdleQueueWithAWrittenPeakIsNotWritten() throws Exception {
        try (final MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb();
                final MetricsLog log = log(server)) {
            log.queues(List.of(new Queues.Reading("intents", 0, 1)));
            log.queues(List.of(new Queues.Reading("intents", 0, 1)));
            log.queues(List.of(new Queues.Reading("intents", 0, 1), new Queues.Reading("strand", 0, 0)));
            log.queues(List.of(new Queues.Reading("intents", 0, 1), new Queues.Reading("strand", 0, 0)));
            marked(server, log);
            assertThat(server.lines).containsExactly(
                    "comando_queue,instance=/data,name=intents depth=0i,peak=1i 1793181600000000000",
                    "comando_queue,instance=/data,name=strand depth=0i,peak=0i 1793181600000000000",
                    MARK + "1793181600000000000");
        }
    }

    /// A busy queue whose reading repeats (a timed strand holds its next run: depth 1 for ever) is written once, and
    /// again when its depth moves.
    @Test
    void aBusyQueueIsWrittenWhenItsReadingMoves() throws Exception {
        try (final MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb();
                final MetricsLog log = log(server)) {
            log.queues(List.of(new Queues.Reading("kit-check", 1, 1)));
            log.queues(List.of(new Queues.Reading("kit-check", 1, 1)));
            log.queues(List.of(new Queues.Reading("kit-check", 2, 2)));
            marked(server, log);
            assertThat(server.lines).filteredOn(l -> l.contains("name=kit-check")).containsExactly(
                    "comando_queue,instance=/data,name=kit-check depth=1i,peak=1i 1793181600000000000",
                    "comando_queue,instance=/data,name=kit-check depth=2i,peak=2i 1793181600000000000");
        }
    }

    /// The first idle reading of a queue that was busy in its last entry is written, then the reading whose peak falls
    /// to 0 (as `Queues` resets it), and the idle readings after them are not.
    @Test
    void aReturnToIdleIsWrittenThenItsPeakFallingToZero() throws Exception {
        try (final MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb();
                final MetricsLog log = log(server)) {
            log.queues(List.of(new Queues.Reading("intents", 3, 3)));
            log.queues(List.of(new Queues.Reading("intents", 0, 3)));
            log.queues(List.of(new Queues.Reading("intents", 0, 0)));
            log.queues(List.of(new Queues.Reading("intents", 0, 0)));
            marked(server, log);
            assertThat(server.lines).filteredOn(l -> l.contains("name=intents")).containsExactly(
                    "comando_queue,instance=/data,name=intents depth=3i,peak=3i 1793181600000000000",
                    "comando_queue,instance=/data,name=intents depth=0i,peak=3i 1793181600000000000",
                    "comando_queue,instance=/data,name=intents depth=0i,peak=0i 1793181600000000000");
        }
    }

    /// An idle queue whose peak differs from the one last written for it is written, in an event that leaves out the
    /// idle queues whose peak did not change.
    @Test
    void aPeakChangeIsWritten() throws Exception {
        try (final MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb();
                final MetricsLog log = log(server)) {
            log.queues(List.of(new Queues.Reading("intents", 0, 1), new Queues.Reading("strand", 0, 0)));
            log.queues(List.of(new Queues.Reading("intents", 0, 4), new Queues.Reading("strand", 0, 0)));
            marked(server, log);
            assertThat(server.lines).containsExactly(
                    "comando_queue,instance=/data,name=intents depth=0i,peak=1i 1793181600000000000",
                    "comando_queue,instance=/data,name=strand depth=0i,peak=0i 1793181600000000000",
                    "comando_queue,instance=/data,name=intents depth=0i,peak=4i 1793181600000000000",
                    MARK + "1793181600000000000");
        }
    }

    /// An endpoint that throws on every call takes no event: the event is lost, a notice says so, and nothing is
    /// written to disk in its stead.
    @Test
    void anEventQuestDbDoesNotTakeIsLostAndTold() throws Exception {
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
        try (final MetricsLog log = new MetricsLog(JSON, now::get, dir.toString(), failing, notices)) {
            log.queues(List.of(new Queues.Reading("intents", 0, 12)));
            final Intent.Notify told = notices.await(Intent.Notify.class, n -> true, WITHIN);
            assertThat(told.level()).isEqualTo(Notice.Level.WARN);
            assertThat(told.text()).isEqualTo("Cannot write metrics to QuestDB, the metrics rows are lost [lines=1,"
                    + " failedLines=1]");
        }
        try (final Stream<Path> made = Files.list(dir)) {
            assertThat(made).as("no file kept in the metrics' stead").isEmpty();
        }
    }

    /// An event reaches QuestDB as a row of the data root the log is built for.
    @Test
    void anEventReachesQuestDbAsARowOfItsInstance() throws Exception {
        final String instance = dir.resolve("data").toAbsolutePath().toString();
        final String expected = "comando_queue,instance=" + instance
                + ",name=intents depth=0i,peak=12i 1793181600000000000";
        try (final MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb();
                final MetricsLog log = new MetricsLog(
                        JSON, now::get, instance, MetricsSinkTest.Endpoint.at(server.uri()), notices)) {
            log.queues(List.of(new Queues.Reading("intents", 0, 12)));
            await().atMost(WITHIN).until(() -> server.lines.contains(expected));
            assertThat(server.lines).as("the live event alone").containsOnly(expected);
        }
    }

    /// [MetricsLog#lastContext] takes QuestDB's answer; with no row in QuestDB, with QuestDB down and with none running
    /// there is none. An id that is not a conversation id never reaches a query.
    @Test
    void theLastContextComesFromQuestDbAlone() throws Exception {
        try (final MetricsSinkTest.FakeQuestDb server = new MetricsSinkTest.FakeQuestDb()) {
            final MetricsSinkTest.Endpoint endpoint = MetricsSinkTest.Endpoint.at(server.uri());
            try (final MetricsLog log = new MetricsLog(JSON, now::get, "/data", endpoint, notices)) {
                server.selectAnswer.set("{\"query\":\"\",\"columns\":[{\"name\":\"context\",\"type\":\"LONG\"},"
                        + "{\"name\":\"context_window\",\"type\":\"LONG\"}],\"dataset\":[[588979,1000000]],"
                        + "\"count\":1}");
                assertThat(log.lastContext(CONVERSATION))
                        .hasValueSatisfying(c -> assertThat(c).containsExactly(588_979L, 1_000_000L));
                assertThat(server.queries).containsExactly("SELECT context, context_window FROM comando_turn"
                        + " WHERE conversation = '" + CONVERSATION + "' AND context > 0 ORDER BY ts DESC LIMIT 1");

                assertThat(log.lastContext("c1' OR '1'='1")).isEmpty();
                assertThat(log.lastContext(null)).isEmpty();
                assertThat(server.queries).as("not a conversation id").hasSize(1);

                server.selectAnswer.set("{\"query\":\"\",\"columns\":[],\"dataset\":[],\"count\":0}");
                assertThat(log.lastContext(CONVERSATION)).as("no row").isEmpty();

                server.down();
                assertThat(log.lastContext(CONVERSATION)).as("down").isEmpty();

                endpoint.live.set(null);
                assertThat(log.lastContext(CONVERSATION)).as("none running").isEmpty();
            }
        }
    }

    /// A log of instance `/data` that sends to `server` and tells its outages to [#notices].
    private MetricsLog log(final MetricsSinkTest.FakeQuestDb server) {
        return new MetricsLog(JSON, now::get, "/data", MetricsSinkTest.Endpoint.at(server.uri()), notices);
    }

    /// Writes a marker queue reading and waits until `server` has its line: every event written before it was sent.
    private static void marked(final MetricsSinkTest.FakeQuestDb server, final MetricsLog log) {
        log.queues(List.of(new Queues.Reading("mark", 0, 0)));
        await().atMost(WITHIN).until(() -> server.lines.stream().anyMatch(l -> l.startsWith(MARK)));
    }
}
