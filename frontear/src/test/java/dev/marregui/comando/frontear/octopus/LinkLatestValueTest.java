package dev.marregui.comando.frontear.octopus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.marregui.comando.frontear.gateway.Gateway;
import dev.marregui.comando.frontear.gateway.GatewayOptions;
import dev.marregui.comando.frontear.protocol.FakeGateway;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.store.LogOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Latest-value delivery of an instance's retained state over a link: of the presences and sessions' details a link's
/// log holds for its peer, only the newest of each topic is sent, and every inbox message is, once and in order; the
/// same across a restart at any cursor the log stands at, and when the inbox message was delivered before it.
class LinkLatestValueTest {

    private static final Duration DAY = Duration.ofHours(24);
    private static final String HOST = "127.0.0.1";
    private static final String ID = "bob";
    // the topics alice's link to bob carries, as the octopus network names them
    private static final String INBOX = "comando/v1/local/instance/bob/inbox";
    // the marks of the messages of backlog(), in its order
    private static final List<String> MARKS = List.of("p1", "m1", "p2", "s1", "p3");
    private static final LogOptions LOG = new LogOptions(64 * 1024, 64 * 1024 * 1024, Duration.ZERO);
    private static final String PRESENCE = "comando/v1/local/instance/alice/presence";
    private static final Duration QUIET = Duration.ofMillis(500);
    private static final String SESSIONS = "comando/v1/local/instance/alice/sessions";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    /// A link down while alice publishes presence p1, an inbox message m1, presence p2, sessions s1 and presence p3:
    /// once bob is back he gets m1, s1 and p3, in that order and retained as published, and neither p1 nor p2, which
    /// the link counts as coalesced, not sent and not dropped.
    @Test
    void aDownLinkDeliversTheLatestPresenceAndSessionsAndEveryInboxMessage() throws Exception {
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway();
                Links links = new Links(a, "alice", dir, LOG, options())) {
            peer.connectCode(Packet.NOT_AUTHORIZED);
            links.put(spec(peer));
            for (final Packet.Publish m : backlog()) {
                assertThat(publish(a, m)).as(m.topic()).isEqualTo(Packet.SUCCESS);
            }
            peer.connectCode(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> state(links).cursor() == 5);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 3);
            assertThat(payloads(peer)).containsExactly("m1", "s1", "p3");
            assertThat(peer.received(Packet.Publish.class)).extracting(Packet.Publish::retain)
                    .containsExactly(false, true, true);
            final LinkState s = state(links);
            assertThat(s.sent()).isEqualTo(3);
            assertThat(s.coalesced()).isEqualTo(2);
            assertThat(s.dropped()).isZero();
            assertThat(s.backlogBytes()).isZero();
        }
    }

    /// The node restarts after alice published the first `published` of the five while bob was down, the log's cursor
    /// at `cursor`: at 0 before anything is passed, at 1 between p1, passed as p2 superseded it, and m1. The second
    /// life publishes the rest; bob, once back, gets m1, s1 and p3, m1 once.
    @ParameterizedTest
    @CsvSource({"1, 0", "2, 0", "3, 1", "4, 1"})
    void aRestartAtAnyCursorDeliversTheSameLatestValues(final int published, final long cursor) throws Exception {
        final List<Packet.Publish> backlog = backlog();
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway()) {
            peer.connectCode(Packet.NOT_AUTHORIZED);
            try (Links links = new Links(a, "alice", dir, LOG, options())) {
                links.put(spec(peer));
                for (final Packet.Publish m : backlog.subList(0, published)) {
                    assertThat(publish(a, m)).as(m.topic()).isEqualTo(Packet.SUCCESS);
                }
                assertThat(state(links).cursor()).isEqualTo(cursor);
            }
            try (Links links = new Links(a, "alice", dir, LOG, options())) {
                await().atMost(WAIT).until(() -> state(links).queued() == published - cursor);
                for (final Packet.Publish m : backlog.subList(published, backlog.size())) {
                    assertThat(publish(a, m)).as(m.topic()).isEqualTo(Packet.SUCCESS);
                }
                peer.connectCode(Packet.SUCCESS);
                await().atMost(WAIT).until(() -> state(links).cursor() == 5);
                await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 3);
                assertThat(payloads(peer)).containsExactly("m1", "s1", "p3");
                assertThat(state(links).backlogBytes()).isZero();
            }
        }
    }

    /// bob takes p1 and m1 while the link is up, then is down while alice restarts and publishes p2, s1 and p3: after
    /// the restart he gets s1 and p3, not m1 again, which the cursor saved at the close had passed, and never p2.
    @Test
    void aRestartPastADeliveredInboxMessageNeitherRepeatsNorLosesIt() throws Exception {
        final List<Packet.Publish> backlog = backlog();
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway()) {
            try (Links links = new Links(a, "alice", dir, LOG, options())) {
                links.put(spec(peer));
                for (final Packet.Publish m : backlog.subList(0, 2)) {
                    assertThat(publish(a, m)).as(m.topic()).isEqualTo(Packet.SUCCESS);
                }
                await().atMost(WAIT).until(() -> state(links).cursor() == 2);
                assertThat(payloads(peer)).containsExactly("p1", "m1");
                peer.connectCode(Packet.NOT_AUTHORIZED);
            }
            try (Links links = new Links(a, "alice", dir, LOG, options())) {
                assertThat(state(links).cursor()).isEqualTo(2);
                for (final Packet.Publish m : backlog.subList(2, backlog.size())) {
                    assertThat(publish(a, m)).as(m.topic()).isEqualTo(Packet.SUCCESS);
                }
                peer.connectCode(Packet.SUCCESS);
                await().atMost(WAIT).until(() -> state(links).cursor() == 5);
                await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 4);
                assertThat(payloads(peer)).containsExactly("p1", "m1", "s1", "p3");
            }
        }
    }

    /// The five messages in the order alice publishes them: p1, m1, p2, s1, p3.
    private static List<Packet.Publish> backlog() {
        return List.of(presence("p1"), inbox("m1"), presence("p2"), sessions("s1"), presence("p3"));
    }

    /// A presence of schema 2 whose state is `mark`, retained.
    private static Packet.Publish presence(final String mark) {
        final String json = "{\"schema\":2,\"name\":\"alice\",\"slug\":\"alice\",\"version\":\"0.1.0\",\"role\":"
                + "\"main\",\"busy\":true,\"sessions\":[{\"name\":\"main\",\"busy\":true,\"status\":\"" + mark
                + "\"}]}";
        return new Packet.Publish(PRESENCE, json.getBytes(StandardCharsets.UTF_8), 1, true, false, 0, null, null);
    }

    /// The details of alice's sessions marked `mark`, retained.
    private static Packet.Publish sessions(final String mark) {
        final String json = "{\"schema\":2,\"sessions\":[{\"name\":\"main\",\"folder\":\"/w/comando\",\"model\":"
                + "\"opus\",\"status\":\"" + mark + "\"}]}";
        return new Packet.Publish(SESSIONS, json.getBytes(StandardCharsets.UTF_8), 1, true, false, 0, null, null);
    }

    /// A question to bob marked `mark`, not retained.
    private static Packet.Publish inbox(final String mark) {
        final String json = "{\"schema\":1,\"from\":\"alice\",\"question\":\"" + mark + "\"}";
        return new Packet.Publish(INBOX, json.getBytes(StandardCharsets.UTF_8), 1, false, false, 0, null, null);
    }

    /// The mark of each message bob received, in arrival order: the mark of the message of [#backlog] with its topic
    /// and payload, `unknown` for none.
    private static List<String> payloads(final FakeGateway peer) {
        final List<Packet.Publish> backlog = backlog();
        final List<String> marks = new ArrayList<>();
        for (final Packet.Publish p : peer.received(Packet.Publish.class)) {
            String mark = "unknown";
            for (int i = 0; i < backlog.size(); i++) {
                if (backlog.get(i).topic().equals(p.topic()) && Arrays.equals(backlog.get(i).payload(), p.payload())) {
                    mark = MARKS.get(i);
                }
            }
            marks.add(mark);
        }
        return marks;
    }

    private static LinkOptions options() {
        final LinkOptions d = LinkOptions.defaults();
        return new LinkOptions(d.queueMax(), d.windowMax(), d.maxBacklogBytes(), DAY);
    }

    private static LinkSpec spec(final FakeGateway peer) {
        return new LinkSpec(ID, HOST, peer.port(), Set.of(PRESENCE, SESSIONS, INBOX), Set.of(), 4);
    }

    private static Gateway gateway() throws IOException {
        return Gateway.start(GatewayOptions.defaults(new InetSocketAddress(HOST, 0)));
    }

    private static int publish(final Gateway gateway, final Packet.Publish m) throws Exception {
        return gateway.publish(m, "test").get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static LinkState state(final Links links) {
        for (final LinkState s : links.state()) {
            if (s.id().equals(ID)) {
                return s;
            }
        }
        throw new AssertionError("no such link [link=" + ID + ']');
    }
}
