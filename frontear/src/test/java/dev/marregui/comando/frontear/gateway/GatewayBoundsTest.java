package dev.marregui.comando.frontear.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.protocol.Codec;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// What one client can make the gateway hold, each bound set small and reached: a connection's outbound backlog in
/// bytes, the retained store in count and bytes, an offline session's queue in bytes, the filters of a SUBSCRIBE and
/// of a session, and the depth of a topic or a filter; each refusal with its reason code and counted in the stats.
/// And a connection whose reader fails with an `Error` ends, so no client is left on a reader that stopped.
class GatewayBoundsTest {

    // large enough that no test reaches it unless it means to
    private static final long LOOSE_BYTES = 64L * 1024 * 1024;
    private static final int LOOSE_COUNT = 1_000;
    private static final int LOOSE_LEVELS = 128;

    /// A subscriber that grants Receive Maximum 65,535 and stops reading: once what waits on its writer would pass the
    /// backlog bound, its connection ends with DISCONNECT 0x97, counted, while the publisher stays connected.
    @Test
    void aSilentSubscribersBacklogEndsItsConnectionWith0x97() throws IOException {
        final long backlog = 64 * 1024;
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(LOOSE_COUNT, LOOSE_BYTES, backlog, LOOSE_COUNT,
                LOOSE_BYTES, LOOSE_LEVELS));
                RawClient silent = RawClient.connected(test.port(), new Packet.Connect("silent", true, 0, 0, 65_535,
                        Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null));
                RawClient p = RawClient.connected(test.port(), "p")) {
            assertThat(silent.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
            final byte[] payload = new byte[8 * 1024];
            // 32 MiB: past what the loopback socket's buffers take before the writer waits on them
            final int limit = 4_096;
            int published = 0;
            while (test.gateway.stats().backlogDisconnects() == 0 && published < limit) {
                published++;
                assertThat(p.publish(new Packet.Publish("t", payload, 1, false, false, 1, null, null)))
                        .isEqualTo(Packet.SUCCESS);
            }
            assertThat(test.gateway.stats().backlogDisconnects()).isEqualTo(1);
            final List<Packet> got = silent.untilClosed();
            assertThat(got).last().isEqualTo(new Packet.Disconnect(Packet.QUOTA_EXCEEDED, null));
            assertThat(got.size() - 1).isLessThan(published);
            assertThat(p.ping()).isEmpty();
        }
    }

    /// Past the retained store's count, a retained PUBLISH is acknowledged 0x97, counted and kept nowhere; replacing
    /// or clearing a retained topic stays possible, and a clear makes room for a new one. The client's own presence
    /// holds one of the four places.
    @Test
    void theRetainedStoreIsBoundedInCount() throws IOException {
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(LOOSE_COUNT, LOOSE_BYTES, LOOSE_BYTES, 4,
                LOOSE_BYTES, LOOSE_LEVELS));
                RawClient p = RawClient.connected(test.port(), "p")) {
            awaitPresence(test, "p");
            for (int i = 1; i <= 3; i++) {
                assertThat(p.publish(retained("r/" + i, 1))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            }
            assertThat(p.publish(retained("r/4", 1))).isEqualTo(Packet.QUOTA_EXCEEDED);
            assertThat(p.publish(retained("r/1", 2))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            assertThat(p.publish(retained("r/2", 0))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            assertThat(p.publish(retained("r/4", 1))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            final Gateway.Stats stats = test.gateway.stats();
            assertThat(stats.retained()).isEqualTo(4);
            assertThat(stats.refusedRetained()).isEqualTo(1);
            assertThat(test.gateway.retained("r/4", 1)).hasSize(1);
        }
    }

    /// Past the retained store's bytes, a retained PUBLISH is acknowledged 0x97 and counted; a replacement is
    /// measured against what it replaces. The client's own presence holds some of the bytes.
    @Test
    void theRetainedStoreIsBoundedInBytes() throws IOException {
        final long bound = 2_500;
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(LOOSE_COUNT, LOOSE_BYTES, LOOSE_BYTES,
                LOOSE_COUNT, bound, LOOSE_LEVELS));
                RawClient p = RawClient.connected(test.port(), "p")) {
            awaitPresence(test, "p");
            final long presence = test.gateway.stats().retainedBytes();
            final long small = retained("r/a", 1_000).weight();
            final long large = retained("r/a", 1_500).weight();
            // the sizes below land on the side of the bound each line expects
            assertThat(presence + large).isLessThanOrEqualTo(bound);
            assertThat(presence + 2 * small).isGreaterThan(bound);
            assertThat(p.publish(retained("r/a", 1_000))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            assertThat(p.publish(retained("r/b", 1_000))).isEqualTo(Packet.QUOTA_EXCEEDED);
            assertThat(p.publish(retained("r/a", 1_500))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            final Gateway.Stats stats = test.gateway.stats();
            assertThat(stats.retained()).isEqualTo(2);
            assertThat(stats.retainedBytes()).isEqualTo(presence + large);
            assertThat(stats.refusedRetained()).isEqualTo(1);
            assertThat(test.gateway.retained("r/b", 1)).isEmpty();
        }
    }

    /// An offline session's queue is bounded in bytes beside its count: what would pass the bytes is dropped and
    /// counted, and the session gets what fitted when it returns.
    @Test
    void anOfflineSessionsQueueIsBoundedInBytes() throws IOException {
        final Packet.Publish message = new Packet.Publish("t", new byte[300], 1, false, false, 1, null, null);
        // three of the ten fit
        final long bound = 3 * message.weight();
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(100, bound, LOOSE_BYTES, LOOSE_COUNT,
                LOOSE_BYTES, LOOSE_LEVELS))) {
            try (RawClient q = RawClient.connected(test.port(), RawClient.connect("q", true, 600))) {
                assertThat(q.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
                q.send(new Packet.Disconnect(Packet.SUCCESS, null));
                q.untilClosed();
            }
            try (RawClient p = RawClient.connected(test.port(), "p")) {
                for (int i = 0; i < 10; i++) {
                    assertThat(p.publish(message)).isEqualTo(Packet.SUCCESS);
                }
            }
            final Gateway.Stats stats = test.gateway.stats();
            assertThat(stats.droppedFull()).isEqualTo(7);
            assertThat(stats.largestSessionBytes()).isEqualTo(bound);
            try (RawClient q = RawClient.open(test.port(), RawClient.connect("q", false, 600))) {
                assertThat(q.read(Packet.ConnAck.class).sessionPresent()).isTrue();
                assertThat(q.ping()).hasSize(3);
            }
        }
    }

    /// A retained message is measured by what holding it costs, its user properties included: one of 5,000 empty
    /// properties, 25,000 bytes on the wire and about 400,000 on the heap, passes no 64 KiB bound, and messages whose
    /// property values fill the store are refused once their weight would pass it.
    @Test
    void theRetainedStoreCountsUserProperties() throws IOException {
        final long bound = 64 * 1024;
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(LOOSE_COUNT, LOOSE_BYTES, LOOSE_BYTES,
                LOOSE_COUNT, bound, LOOSE_LEVELS));
                RawClient p = RawClient.connected(test.port(), "p")) {
            awaitPresence(test, "p");
            final long presence = test.gateway.stats().retainedBytes();
            assertThat(p.publish(withProperties(retained("r/empty", 1), 5_000, ""))).isEqualTo(Packet.QUOTA_EXCEEDED);
            final String value = "v".repeat(8_000);
            int accepted = 0;
            for (int i = 0; i < 20; i++) {
                if (p.publish(withProperties(retained("r/%02d".formatted(i), 1), 4, value))
                        == Packet.NO_MATCHING_SUBSCRIBERS) {
                    accepted++;
                }
            }
            final long weight = withProperties(retained("r/00", 1), 4, value).weight();
            assertThat(accepted).isPositive().isEqualTo((int) ((bound - presence) / weight));
            final Gateway.Stats stats = test.gateway.stats();
            assertThat(stats.retainedBytes()).isEqualTo(presence + accepted * weight).isLessThanOrEqualTo(bound);
            assertThat(stats.refusedRetained()).isEqualTo(1 + 20 - accepted);
        }
    }

    /// An offline session's queue is measured by what holding a message costs, its user properties included:
    /// messages of 400 empty properties, 2,000 bytes on the wire each, fill a 64 KiB queue in two.
    @Test
    void anOfflineSessionsQueueCountsUserProperties() throws IOException {
        final long bound = 64 * 1024;
        final Packet.Publish message = withProperties(new Packet.Publish("t", new byte[1], 1, false, false, 1, null,
                null), 400, "");
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(100, bound, LOOSE_BYTES, LOOSE_COUNT,
                LOOSE_BYTES, LOOSE_LEVELS))) {
            try (RawClient q = RawClient.connected(test.port(), RawClient.connect("q", true, 600))) {
                assertThat(q.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
                q.send(new Packet.Disconnect(Packet.SUCCESS, null));
                q.untilClosed();
            }
            try (RawClient p = RawClient.connected(test.port(), "p")) {
                for (int i = 0; i < 10; i++) {
                    assertThat(p.publish(message)).isEqualTo(Packet.SUCCESS);
                }
            }
            final int fit = (int) (bound / message.weight());
            final Gateway.Stats stats = test.gateway.stats();
            assertThat(fit).isPositive().isLessThan(10);
            assertThat(stats.droppedFull()).isEqualTo(10 - fit);
            assertThat(stats.largestSessionBytes()).isEqualTo(fit * message.weight());
            try (RawClient q = RawClient.open(test.port(), RawClient.connect("q", false, 600))) {
                assertThat(q.read(Packet.ConnAck.class).sessionPresent()).isTrue();
                assertThat(q.ping()).hasSize(fit);
            }
        }
    }

    /// A client that floods PINGREQ and never reads is ended with 0x97 once its unanswered PINGRESPs pass the
    /// backlog bound, each counted with what it holds on the writer's queue: it sends fewer PINGREQ bytes than the
    /// bound, so counting the 2 bytes of each PINGRESP alone would never reach it.
    @Test
    void aPingFloodThatNeverReadsEndsItsConnectionWith0x97() throws IOException {
        final long backlog = 4L * 1024 * 1024;
        final byte[] pings = new byte[64 * 1024];
        for (int i = 0; i < pings.length; i += 2) {
            pings[i] = (byte) 0xC0;
        }
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(LOOSE_COUNT, LOOSE_BYTES, backlog, LOOSE_COUNT,
                LOOSE_BYTES, LOOSE_LEVELS));
                Socket flood = new Socket()) {
            // a small window, so the gateway's PINGRESPs wait on its writer rather than in this socket's buffer
            flood.setReceiveBufferSize(1024);
            flood.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), test.port()));
            flood.setSoTimeout((int) TestGateway.WAIT.toMillis());
            final OutputStream out = flood.getOutputStream();
            out.write(Codec.encode(RawClient.connect("flood", true, 0)));
            final InputStream in = flood.getInputStream();
            assertThat(Codec.read(in, in.read(), Packet.PROTOCOL_MAXIMUM_PACKET_SIZE)).isInstanceOfSatisfying(
                    Packet.ConnAck.class, a -> assertThat(a.reasonCode()).isEqualTo(Packet.SUCCESS));
            // fewer bytes than the bound: each PINGREQ is answered by a PINGRESP of the same 2 bytes
            final long chunks = backlog / pings.length - 1;
            try {
                for (long c = 0; c < chunks && test.gateway.stats().backlogDisconnects() == 0; c++) {
                    out.write(pings);
                }
            } catch (final IOException _) {
                // the gateway ended the connection while the flood was still writing
            }
            await().atMost(TestGateway.WAIT).until(() -> test.gateway.stats().backlogDisconnects() == 1);
        }
    }

    /// Past the filters one SUBSCRIBE may carry, and past the subscriptions one session may hold, a filter is refused
    /// with 0x97 and counted, while the others in the same SUBSCRIBE are granted; a filter the session already holds
    /// is granted again at its cap, and the session receives on what was granted only.
    @Test
    void filtersPastTheSubscriptionCapsAreRefusedWith0x97() throws IOException {
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(LOOSE_COUNT, LOOSE_BYTES, LOOSE_BYTES,
                LOOSE_COUNT, LOOSE_BYTES, LOOSE_LEVELS, 4, 6));
                RawClient c = RawClient.connected(test.port(), "c");
                RawClient p = RawClient.connected(test.port(), "p")) {
            final int refused = Packet.QUOTA_EXCEEDED;
            assertThat(c.subscribe(filters("a", 6))).containsExactly(0, 0, 0, 0, refused, refused);
            assertThat(c.subscribe(filters("b", 3))).containsExactly(0, 0, refused);
            assertThat(c.subscribe(new Packet.Subscription("a/0", 1))).containsExactly(1);
            assertThat(test.gateway.stats().refusedSubscriptions()).isEqualTo(3);
            assertThat(p.publish(RawClient.qos1("a/4", "refused"))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            assertThat(p.publish(RawClient.qos1("b/2", "refused"))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            assertThat(p.publish(RawClient.qos1("b/1", "granted"))).isEqualTo(Packet.SUCCESS);
            assertThat(c.receive().topic()).isEqualTo("b/1");
            assertThat(c.ping()).isEmpty();
        }
    }

    /// The stats show, beside the session cap, the most subscriptions any session holds now: a filter granted again
    /// adds none, a refused one adds none, and a session that ends no longer counts.
    @Test
    void theStatsShowTheMostSubscriptionsASessionHolds() throws IOException {
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(LOOSE_COUNT, LOOSE_BYTES, LOOSE_BYTES,
                LOOSE_COUNT, LOOSE_BYTES, LOOSE_LEVELS, 10, 5));
                RawClient small = RawClient.connected(test.port(), "small")) {
            assertThat(test.gateway.stats().largestSessionSubscriptions()).isZero();
            assertThat(small.subscribe(filters("s", 2))).containsExactly(0, 0);
            try (RawClient large = RawClient.connected(test.port(), "large")) {
                assertThat(large.subscribe(filters("l", 4))).containsExactly(0, 0, 0, 0);
                assertThat(large.subscribe(new Packet.Subscription("l/0", 1))).containsExactly(1);
                assertThat(test.gateway.stats().largestSessionSubscriptions()).isEqualTo(4);
                assertThat(large.subscribe(filters("m", 2))).containsExactly(0, Packet.QUOTA_EXCEEDED);
                assertThat(test.gateway.stats().largestSessionSubscriptions()).isEqualTo(5);
            }
            await().atMost(TestGateway.WAIT).until(() -> test.gateway.stats().sessions() == 1);
            assertThat(test.gateway.stats().largestSessionSubscriptions()).isEqualTo(2);
        }
    }

    /// A topic or a filter deeper than the gateway's bound is refused before the tree sees it: SUBACK 0x8F for the
    /// filter, PUBACK 0x90 for the topic, CONNACK 0x90 for a will's topic, each counted; the connection stays up.
    @Test
    void aTopicOrFilterDeeperThanTheBoundIsRefused() throws IOException {
        try (TestGateway test = TestGateway.start(new TestGateway.Bounds(LOOSE_COUNT, LOOSE_BYTES, LOOSE_BYTES,
                LOOSE_COUNT, LOOSE_BYTES, 4));
                RawClient c = RawClient.connected(test.port(), "c");
                RawClient s = RawClient.connected(test.port(), "s")) {
            assertThat(c.subscribe(new Packet.Subscription("a/b/c/d/e", 1)))
                    .containsExactly(Packet.TOPIC_FILTER_INVALID);
            assertThat(s.subscribe(new Packet.Subscription("a/b/c/+", 1))).containsExactly(1);
            assertThat(c.publish(RawClient.qos1("a/b/c/d/e", "deep"))).isEqualTo(Packet.TOPIC_NAME_INVALID);
            assertThat(c.publish(RawClient.qos1("a/b/c/d", "at the bound"))).isEqualTo(Packet.SUCCESS);
            assertThat(s.receive().topic()).isEqualTo("a/b/c/d");
            final Packet.Publish will = new Packet.Publish("w/1/2/3/4", new byte[] {1}, 0, false, false, 0, null,
                    null);
            try (RawClient w = RawClient.open(test.port(), new Packet.Connect("w", true, 0, 0,
                    Packet.DEFAULT_RECEIVE_MAXIMUM, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, will))) {
                assertThat(w.read(Packet.ConnAck.class).reasonCode()).isEqualTo(Packet.TOPIC_NAME_INVALID);
            }
            assertThat(test.gateway.stats().refusedTopics()).isEqualTo(3);
            assertThat(c.ping()).isEmpty();
        }
    }

    /// A filter and a topic of 65,000 empty levels, both valid (§4.7.1), are refused at the default bound and
    /// answered, where an unbounded tree walk would overflow the reader's stack.
    @Test
    void aFilterAndTopicOf65000LevelsAreAnswered() throws IOException {
        final String deep = "/".repeat(65_000);
        try (TestGateway test = TestGateway.start();
                RawClient c = RawClient.connected(test.port(), "c")) {
            assertThat(c.subscribe(new Packet.Subscription(deep, 0))).containsExactly(Packet.TOPIC_FILTER_INVALID);
            assertThat(c.publish(new Packet.Publish(deep, new byte[1], 1, false, false, 1, null, null)))
                    .isEqualTo(Packet.TOPIC_NAME_INVALID);
            assertThat(c.ping()).isEmpty();
        }
    }

    /// A reader that fails with an `Error` ends its connection: the client is told 0x80 and the socket closes, rather
    /// than keeping a connection whose reader is gone.
    @Test
    void aReaderThatFailsWithAnErrorEndsItsConnection() throws IOException {
        try (TestGateway test = TestGateway.start();
                RawClient c = RawClient.connected(test.port(), "c")) {
            test.gateway.gate(new Gate() {
                @Override
                public boolean mayPublish(final String clientId, final String topic) {
                    return true;
                }

                @Override
                public boolean maySubscribe(final String clientId, final String filter) {
                    throw new StackOverflowError("a gate that fails as a deep walk would");
                }
            });
            c.send(new Packet.Subscribe(1, List.of(new Packet.Subscription("t", 0))));
            assertThat(c.untilClosed()).containsExactly(new Packet.Disconnect(Packet.UNSPECIFIED, null));
        }
    }

    private static void awaitPresence(final TestGateway test, final String clientId) {
        await().atMost(TestGateway.WAIT).until(() -> !test.gateway.retained(Names.PRESENCE + clientId, 1).isEmpty());
    }

    private static Packet.Publish retained(final String topic, final int bytes) {
        return new Packet.Publish(topic, new byte[bytes], 1, true, false, 1, null, null);
    }

    /// `message` with `count` user properties of an empty name and `value`.
    private static Packet.Publish withProperties(final Packet.Publish message, final int count, final String value) {
        final List<Packet.UserProperty> properties = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            properties.add(new Packet.UserProperty("", value));
        }
        return new Packet.Publish(message.topic(), message.payload(), message.qos(), message.retain(), message.dup(),
                message.packetId(), message.responseTopic(), message.correlation(), new Packet.Meta(-1, -1, null,
                        properties));
    }

    /// `count` filters at QoS 0 under `prefix`: `prefix/0` and on.
    private static Packet.Subscription[] filters(final String prefix, final int count) {
        final Packet.Subscription[] filters = new Packet.Subscription[count];
        for (int i = 0; i < count; i++) {
            filters[i] = new Packet.Subscription(prefix + '/' + i, 0);
        }
        return filters;
    }
}
