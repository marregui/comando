package dev.marregui.comando.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.port.LabPort;
import dev.marregui.comando.core.port.LabPort.Counts;
import dev.marregui.comando.core.port.LabPort.Frame;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Link;
import dev.marregui.comando.core.port.LabPort.Log;
import dev.marregui.comando.core.port.LabPort.Node;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.core.port.LabPort.Totals;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// `frontear_read` (record 187) over a fake [LabPort]: each view answers its documented JSON, `traffic` pages by
/// `since` and caps `max` at 2000, the first call opens the watch, and an unknown view is an error.
/// - before the application sets a port, the tool answers a node that is not up
class LabToolsTest {

    private FakeLab lab;
    private McpTools tools;

    @BeforeEach
    void start() {
        lab = new FakeLab(5_000);
        tools = new McpTools(_ -> { }, States::twoPanels, new TranscriptRegistry(), null, AuditLog.none(),
                new FakeClock(), Duration.ofMinutes(1));
        tools.lab(lab);
    }

    /// The tool is listed with the read-only tools, and before a port is set it answers a node that is not up.
    @Test
    void itIsListedAndAnswersNoNodeBeforeThePortIsSet() {
        assertThat(tools.builtIn()).contains("frontear_read");
        final McpTools bare = new McpTools(_ -> { }, States::twoPanels, new TranscriptRegistry(), null,
                AuditLog.none(), new FakeClock(), Duration.ofMinutes(1));
        final JsonNode out = read(bare, "{}");
        assertThat(out.path("view").asString()).isEqualTo("overview");
        assertThat(out.path("node").path("up").asBoolean()).isFalse();
        assertThat(out.path("links").size()).isZero();
    }

    /// overview: the node, the broker's totals, the counts of what was lost, and per link its state and rates; the
    /// first call opens the watch and asks for no packets.
    @Test
    void overviewAnswersTheNodeTotalsCountsAndRates() {
        final JsonNode out = read(tools, "{\"view\":\"overview\"}");
        assertThat(lab.opened).isEqualTo(1);
        assertThat(lab.askedMax).isZero();
        assertThat(out.path("node").path("name").asString()).isEqualTo("alpha");
        assertThat(out.path("node").path("port").asInt()).isEqualTo(1883);
        assertThat(out.path("node").path("up").asBoolean()).isTrue();
        assertThat(out.path("totals").path("sessions").asInt()).isEqualTo(2);
        assertThat(out.path("totals").path("droppedFull").asLong()).isEqualTo(1L);
        assertThat(out.path("counts").path("sinkDroppedFull").asLong()).isEqualTo(6L);
        final JsonNode beta = out.path("links").path(0);
        assertThat(beta.path("id").asString()).isEqualTo("beta");
        assertThat(beta.path("connected").asBoolean()).isTrue();
        assertThat(beta.path("messagesPerSecond").asDouble()).isEqualTo(2.0);
        assertThat(beta.path("bytesPerSecond").asDouble()).isEqualTo(14.0);
        assertThat(out.path("links").path(1).path("connected").asBoolean()).isFalse();
        assertThat(out.has("packets")).isFalse();
    }

    /// traffic: the packets after `since`, 200 without `max`, every field of a packet, `missed` and `last`, which is
    /// the next call's `since`.
    @Test
    void trafficPagesBySinceWithEveryFieldOfAPacket() {
        final JsonNode first = read(tools, "{\"view\":\"traffic\"}");
        assertThat(first.path("packets").size()).isEqualTo(200);
        assertThat(first.path("since").asLong()).isZero();
        assertThat(first.path("missed").asLong()).isEqualTo(3L);
        assertThat(first.path("last").asLong()).isEqualTo(200L);
        final JsonNode p = first.path("packets").path(0);
        assertThat(p.path("seq").asLong()).isEqualTo(1L);
        assertThat(p.path("kind").asString()).isEqualTo("PUBLISHED");
        assertThat(p.path("origin").asString()).isEqualTo("alpha");
        assertThat(p.path("topic").asString()).isEqualTo("work/1");
        assertThat(p.path("qos").asInt()).isEqualTo(1);
        assertThat(p.path("retain").asBoolean()).isTrue();
        assertThat(p.path("bytes").asInt()).isEqualTo(7);
        assertThat(p.path("hops").asInt()).isZero();
        assertThat(p.path("offset").asLong()).isEqualTo(-1L);
        assertThat(p.path("correlation").asString()).isEqualTo("0a0b");
        assertThat(p.path("head").asString()).isEqualTo("{}");
        assertThat(p.path("detail").asString()).isEmpty();

        final JsonNode next = read(tools, "{\"view\":\"traffic\",\"since\":200,\"max\":10}");
        assertThat(next.path("packets").path(0).path("seq").asLong()).isEqualTo(201L);
        assertThat(next.path("packets").size()).isEqualTo(10);
        assertThat(next.path("missed").asLong()).isZero();
        assertThat(next.path("last").asLong()).isEqualTo(210L);
    }

    /// A `max` over 2000 reads 2000, one under 1 reads 1.
    @Test
    void trafficCapsMax() {
        assertThat(read(tools, "{\"view\":\"traffic\",\"max\":100000}").path("packets").size()).isEqualTo(2000);
        assertThat(lab.askedMax).isEqualTo(LabTools.MAX);
        assertThat(read(tools, "{\"view\":\"traffic\",\"max\":0}").path("packets").size()).isEqualTo(1);
    }

    /// octopus: each link's queue, cursor, tail, counters and last connect and disconnect; store: each log's
    /// cursor, tail, size and force rounds.
    @Test
    void octopusAndStoreAnswerEachLinkAndLog() {
        final JsonNode octopus = read(tools, "{\"view\":\"octopus\"}");
        final JsonNode gamma = octopus.path("links").path(1);
        assertThat(gamma.path("id").asString()).isEqualTo("gamma");
        assertThat(gamma.path("queued").asLong()).isEqualTo(5L);
        assertThat(gamma.path("cursor").asLong()).isZero();
        assertThat(gamma.path("tail").asLong()).isEqualTo(5L);
        assertThat(gamma.path("queuedDeliveries").asInt()).isZero();
        assertThat(gamma.path("queueMax").asInt()).isEqualTo(64);
        assertThat(gamma.path("lastDownMillis").asLong()).isEqualTo(900L);
        assertThat(gamma.path("lastDetail").asString()).isEqualTo("refused");
        assertThat(octopus.path("links").path(0).path("sent").asLong()).isEqualTo(8L);

        final JsonNode store = read(tools, "{\"view\":\"store\"}");
        final JsonNode beta = store.path("logs").path(0);
        assertThat(beta.path("link").asString()).isEqualTo("beta");
        assertThat(beta.path("cursor").asLong()).isEqualTo(40L);
        assertThat(beta.path("tail").asLong()).isEqualTo(50L);
        assertThat(beta.path("bytes").asLong()).isEqualTo(4_096L);
        assertThat(beta.path("segments").asInt()).isEqualTo(1);
        assertThat(beta.path("roundsPerSecond").asDouble()).isEqualTo(4.0);
        assertThat(beta.path("lastRecords").asInt()).isEqualTo(2);
        assertThat(beta.path("p50Millis").asDouble()).isEqualTo(0.4);
        assertThat(beta.path("p99Millis").asDouble()).isEqualTo(0.9);
        assertThat(lab.opened).as("opened on every call; the port keeps it idempotent").isEqualTo(2);
    }

    /// A view the tool does not know is an error naming the views it knows.
    @Test
    void anUnknownViewIsAnError() {
        final McpTools.Outcome out = tools.call("s", "frontear_read", Json.readTree("{\"view\":\"queues\"}"));
        assertThat(out.error()).isTrue();
        assertThat(out.text()).contains("view=queues").contains("overview|traffic|octopus|store");
    }

    private static JsonNode read(final McpTools tools, final String args) {
        final McpTools.Outcome out = tools.call("s", "frontear_read", Json.readTree(args));
        assertThat(out.error()).as(out.text()).isFalse();
        return Json.readTree(out.text());
    }

    /// A node `alpha` with links `beta` (up) and `gamma` (down), and `count` publishes numbered from 1.
    private static final class FakeLab implements LabPort {

        private final List<Packet> packets = new ArrayList<>();
        private int opened;
        private int askedMax = -1;

        FakeLab(final int count) {
            for (int i = 1; i <= count; i++) {
                packets.add(new Packet(i, i, Kind.PUBLISHED, "alpha", "", "work/" + i, 1, true, 7, 0, -1L, "0a0b",
                        "{}", ""));
            }
        }

        @Override
        public void open() {
            opened++;
        }

        @Override
        public void listen(final Runnable changed) {
        }

        @Override
        public Frame read(final long after, final int max) {
            askedMax = max;
            final List<Packet> out = new ArrayList<>();
            for (final Packet p : packets) {
                if (p.seq() > after && out.size() < max) {
                    out.add(p);
                }
            }
            final Node node = new Node("alpha", 1883, 10_000L, true,
                    new Totals(2, 2, 3, 300L, 1L, 0L, 0L, 0L, 0L),
                    List.of(new Link("beta", true, 0L, 50L, 50L, 8L, 0L, 0L, 3, 64, 2.0, 14.0, 1_000L, 0L, ""),
                            new Link("gamma", false, 5L, 0L, 5L, 0L, 0L, 0L, 0, 64, 0.0, 0.0, 0L, 900L, "refused")),
                    List.of(new Log("beta", 40L, 50L, 4_096L, 1, 4.0, 2, 120L, 0.5, 0.4, 0.9),
                            new Log("gamma", 0L, 5L, 1_024L, 1, 0.0, 0, 0L, 0.0, 0.0, 0.0)),
                    new Counts(0L, 1L, 10L, 6L, 0L));
            final long missed = after == 0L && max > 0 ? 3L : 0L;
            return new Frame(node, out, missed, out.isEmpty() ? after : out.getLast().seq());
        }
    }
}
