package dev.marregui.comando.frontear.octopus;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.frontear.gateway.Gateway;
import dev.marregui.comando.frontear.gateway.GatewayOptions;
import dev.marregui.comando.frontear.protocol.FakeGateway;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.store.LogOptions;
import dev.marregui.comando.frontear.watch.LinkStep;
import dev.marregui.comando.frontear.watch.Observation;
import dev.marregui.comando.frontear.watch.Watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// A link's backlog between its cursor and its tail: while the peer is down the byte bound holds, what it drops is
/// counted and the newest records reach the peer in order once it is back; retained messages coalesce to the newest of
/// each topic while the link is down, as it sends, and across a restart; the age bound drops what waited too long; a
/// link that is up answers 0x97 past its byte bound; the warning for a backlog that grows while the link is down; and
/// the bounds the options refuse.
class LinkBacklogTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Duration QUIET = Duration.ofMillis(500);
    private static final LogOptions LOG = new LogOptions(64 * 1024, 64 * 1024 * 1024, Duration.ZERO);
    private static final String HOST = "127.0.0.1";
    private static final String ID = "ab";
    private static final int PAYLOAD_BYTES = 16 * 1024;
    private static final long QUOTA = 1L << 20;
    private static final Duration DAY = Duration.ofHours(24);

    @TempDir
    Path dir;

    /// A down link with a 1 MiB bound takes 2 MiB of messages, each answered 0x00: its backlog stays within the
    /// bound, what it passed is counted in `dropped`, and its log on disk shrinks to the bound and a segment; once the
    /// peer is back the newest messages reach it in the order published, and those plus the dropped are all of them.
    @Test
    void aDownLinksBacklogStaysWithinItsByteBoundAndCountsWhatItDrops() throws Exception {
        final int count = 128;
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway();
                Links links = new Links(a, "A", dir, LOG, options(QUOTA, DAY))) {
            peer.connectCode(Packet.NOT_AUTHORIZED);
            links.put(spec(peer));
            for (int i = 0; i < count; i++) {
                assertThat(publish(a, big("t/" + i))).as("message %d", i).isEqualTo(Packet.SUCCESS);
            }
            final LinkState down = state(links);
            assertThat(down.backlogBytes()).isPositive().isLessThanOrEqualTo(QUOTA);
            assertThat(down.maxBacklogBytes()).isEqualTo(QUOTA);
            assertThat(down.dropped()).isPositive();
            assertThat(down.dropped() + down.queued()).isEqualTo(count);
            // the head segment holds records already passed until the whole of it is
            await().atMost(WAIT).until(() -> links.logs().getFirst().bytes() <= QUOTA + 2 * LOG.segmentBytes());
            peer.connectCode(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> state(links).cursor() == count);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size()
                    == count - down.dropped());
            final List<String> topics = topics(peer);
            final List<String> newest = new ArrayList<>();
            for (int i = count - topics.size(); i < count; i++) {
                newest.add("t/" + i);
            }
            assertThat(topics).containsExactlyElementsOf(newest);
            assertThat(state(links).dropped()).isEqualTo(down.dropped());
        }
    }

    /// Retained messages queued while the link is down: each one a newer one on its topic supersedes is passed while
    /// the link waits, counted in `coalesced` and not in `dropped`, and the watch says why; the peer, once back, gets
    /// the newest of each topic and the message that was not retained, in order, retained as they were.
    @Test
    void retainedMessagesCoalesceWhileTheLinkIsDown() throws Exception {
        final Watch watch = new Watch(1_024);
        watch.on(true);
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway();
                Links links = new Links(a, "A", dir, LOG, options(QUOTA, DAY), InstantSource.system(), watch)) {
            peer.connectCode(Packet.NOT_AUTHORIZED);
            links.put(spec(peer));
            assertThat(publish(a, retained("r/1", 0))).isEqualTo(Packet.SUCCESS);
            assertThat(publish(a, retained("r/2", 1))).isEqualTo(Packet.SUCCESS);
            assertThat(publish(a, retained("r/1", 2))).isEqualTo(Packet.SUCCESS);
            assertThat(publish(a, retained("r/2", 3))).isEqualTo(Packet.SUCCESS);
            assertThat(publish(a, plain("t/x", 4))).isEqualTo(Packet.SUCCESS);
            final LinkState down = state(links);
            assertThat(down.queued()).isEqualTo(3);
            assertThat(down.coalesced()).isEqualTo(2);
            assertThat(down.dropped()).isZero();
            assertThat(superseded(watch)).containsExactly(0L, 1L);
            peer.connectCode(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> state(links).cursor() == 5);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 3);
            assertThat(topics(peer)).containsExactly("r/1", "r/2", "t/x");
            assertThat(payloads(peer)).containsExactly(2, 3, 4);
            assertThat(peer.received(Packet.Publish.class)).extracting(Packet.Publish::retain)
                    .containsExactly(true, true, false);
            assertThat(state(links).coalesced()).isEqualTo(2);
        }
    }

    /// Retained messages behind one that is not: the down link cannot pass them, its cursor stops at the first, so
    /// they wait; as the link sends, each one a newer one on its topic supersedes is coalesced, not sent, and the peer
    /// gets the first message and the newest retained one.
    @Test
    void aSupersededRetainedMessageIsNotSent() throws Exception {
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway();
                Links links = new Links(a, "A", dir, LOG, options(QUOTA, DAY))) {
            peer.connectCode(Packet.NOT_AUTHORIZED);
            links.put(spec(peer));
            assertThat(publish(a, plain("t/x", 0))).isEqualTo(Packet.SUCCESS);
            for (int i = 1; i <= 3; i++) {
                assertThat(publish(a, retained("r/1", i))).isEqualTo(Packet.SUCCESS);
            }
            assertThat(state(links).queued()).isEqualTo(4);
            assertThat(state(links).coalesced()).isZero();
            peer.connectCode(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> state(links).cursor() == 4);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 2);
            assertThat(topics(peer)).containsExactly("t/x", "r/1");
            assertThat(payloads(peer)).containsExactly(0, 3);
            final LinkState s = state(links);
            assertThat(s.coalesced()).isEqualTo(2);
            assertThat(s.sent()).isEqualTo(2);
            assertThat(s.dropped()).isZero();
            assertThat(s.backlogBytes()).isZero();
            // read after the cursor, which the strand writes after it lets go of the topics
            assertThat((Map<?, ?>) field(links, "latest")).as("retained topics still waiting").isEmpty();
        }
    }

    /// A link learns the backlog a restart left: its bytes as before, and the retained topic a record waits on, so a
    /// newer retained message on that topic after the restart supersedes it.
    @Test
    void aRestartLearnsTheBacklogItLeft() throws Exception {
        final long bytes;
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway()) {
            peer.connectCode(Packet.NOT_AUTHORIZED);
            try (Links links = new Links(a, "A", dir, LOG, options(QUOTA, DAY))) {
                links.put(spec(peer));
                assertThat(publish(a, retained("r/1", 0))).isEqualTo(Packet.SUCCESS);
                assertThat(publish(a, plain("t/x", 1))).isEqualTo(Packet.SUCCESS);
                bytes = state(links).backlogBytes();
                assertThat(bytes).isPositive();
            }
            try (Links links = new Links(a, "A", dir, LOG, options(QUOTA, DAY))) {
                await().atMost(WAIT).until(() -> state(links).backlogBytes() == bytes);
                assertThat(publish(a, retained("r/1", 2))).isEqualTo(Packet.SUCCESS);
                final LinkState s = state(links);
                assertThat(s.coalesced()).isEqualTo(1);
                assertThat(s.queued()).isEqualTo(2);
                peer.connectCode(Packet.SUCCESS);
                await().atMost(WAIT).until(() -> state(links).cursor() == 3);
                await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 2);
                assertThat(payloads(peer)).containsExactly(1, 2);
            }
        }
    }

    /// The age bound: records older than it are passed and counted in `dropped` by the next message queued while the
    /// link is down, and the rest when the peer comes back, before anything is sent.
    @Test
    void recordsOlderThanTheAgeBoundAreDropped() throws Exception {
        final LinksTest.ManualClock clock = new LinksTest.ManualClock(1_700_000_000_000L);
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway();
                Links links = new Links(a, "A", dir, LOG, options(QUOTA, Duration.ofHours(1)), clock)) {
            peer.connectCode(Packet.NOT_AUTHORIZED);
            links.put(spec(peer));
            assertThat(publish(a, plain("t/x", 0))).isEqualTo(Packet.SUCCESS);
            assertThat(publish(a, plain("t/x", 1))).isEqualTo(Packet.SUCCESS);
            clock.advance(Duration.ofMinutes(61));
            assertThat(publish(a, plain("t/x", 2))).isEqualTo(Packet.SUCCESS);
            assertThat(state(links).dropped()).isEqualTo(2);
            assertThat(state(links).queued()).isEqualTo(1);
            assertThat(publish(a, plain("t/x", 3))).isEqualTo(Packet.SUCCESS);
            clock.advance(Duration.ofMinutes(30));
            assertThat(publish(a, plain("t/x", 4))).isEqualTo(Packet.SUCCESS);
            clock.advance(Duration.ofMinutes(31));
            peer.connectCode(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> state(links).cursor() == 5);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 1);
            assertThat(payloads(peer)).containsExactly(4);
            assertThat(state(links).dropped()).isEqualTo(4);
        }
    }

    /// A link that is up holds what the peer has not answered in its backlog: past the byte bound it answers a QoS 1
    /// message 0x97, counted in `refused`, and drops a QoS 0 one, counted in `dropped`; it passes nothing itself.
    @Test
    void anUpLinkPastItsByteBoundAnswersQuotaExceeded() throws Exception {
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway();
                Links links = new Links(a, "A", dir, LOG, options(64 * 1024, DAY))) {
            peer.ackPublishes(false);
            links.put(spec(peer));
            await().atMost(WAIT).until(() -> state(links).connected());
            final List<Integer> codes = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                codes.add(publish(a, big("t/" + i)));
            }
            // a record is the 16 KiB payload and some 60 bytes of topic and properties: three fit in 64 KiB, not four
            assertThat(codes).containsExactly(Packet.SUCCESS, Packet.SUCCESS, Packet.SUCCESS, Packet.QUOTA_EXCEEDED,
                    Packet.QUOTA_EXCEEDED);
            assertThat(publish(a, new Packet.Publish("t/0", new byte[PAYLOAD_BYTES], 0, false, false, 0, null, null)))
                    .isEqualTo(Packet.QUOTA_EXCEEDED);
            final LinkState s = state(links);
            assertThat(s.refused()).isEqualTo(2);
            assertThat(s.dropped()).isEqualTo(1);
            assertThat(s.queued()).isEqualTo(3);
            assertThat(s.backlogBytes()).isLessThanOrEqualTo(64 * 1024);
        }
    }

    /// The warning: a link down past five minutes whose queue grew warns, one down for less or whose queue did not
    /// grow does not; a link's state carries it from its own clock of when it went down, and a link back up clears it.
    @Test
    void aBacklogThatGrowsWhileTheLinkIsDownWarns() throws Exception {
        assertThat(Link.growing(5, 1, Link.BACKLOG_WARN_NANOS)).isTrue();
        assertThat(Link.growing(5, 1, Link.BACKLOG_WARN_NANOS - 1)).isFalse();
        assertThat(Link.growing(1, 1, 10 * Link.BACKLOG_WARN_NANOS)).isFalse();
        assertThat(Link.growing(0, 3, 10 * Link.BACKLOG_WARN_NANOS)).isFalse();
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway();
                Links links = new Links(a, "A", dir, LOG, options(QUOTA, DAY))) {
            peer.connectCode(Packet.NOT_AUTHORIZED);
            links.put(spec(peer));
            assertThat(publish(a, plain("t/x", 0))).isEqualTo(Packet.SUCCESS);
            assertThat(state(links).backlogGrowing()).as("down for less than five minutes").isFalse();
            downSince(links, System.nanoTime() - Link.BACKLOG_WARN_NANOS - 1);
            assertThat(state(links).backlogGrowing()).as("down past five minutes, one more queued").isTrue();
            peer.connectCode(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> state(links).connected());
            assertThat(state(links).backlogGrowing()).as("up again").isFalse();
        }
    }

    /// The backlog and queue bounds the options refuse: none, and an age or wait that is not positive; the backlog's
    /// defaults are 64 MiB and a day, the queue's 16,384 messages, 8 MiB and 50 ms, and the shorter constructors take
    /// them.
    @Test
    void backlogBoundsOutOfRangeAreRefused() {
        assertThatThrownBy(() -> new LinkOptions(1, 1, 0, DAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkOptions(1, 1, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkOptions(1, 1, 1, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkOptions(1, 1, 1, null)).isInstanceOf(NullPointerException.class);
        assertThat(LinkOptions.defaults().maxBacklogBytes()).isEqualTo(64L << 20);
        assertThat(LinkOptions.defaults().maxBacklogAge()).isEqualTo(DAY);
        assertThat(LinkOptions.defaults().queueMax()).isEqualTo(16_384);
        assertThat(LinkOptions.defaults().maxQueueBytes()).isEqualTo(8L << 20);
        assertThat(LinkOptions.defaults().maxQueueWait()).isEqualTo(Duration.ofMillis(50));
        assertThatThrownBy(() -> new LinkOptions(1, 1, 1, DAY, 0, DAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkOptions(1, 1, 1, DAY, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkOptions(1, 1, 1, DAY, 1, null)).isInstanceOf(NullPointerException.class);
        assertThat(new LinkOptions(3, 2)).isEqualTo(new LinkOptions(3, 2, 64L << 20, DAY));
    }

    private static LinkOptions options(final long maxBacklogBytes, final Duration maxBacklogAge) {
        final LinkOptions d = LinkOptions.defaults();
        return new LinkOptions(d.queueMax(), d.windowMax(), maxBacklogBytes, maxBacklogAge);
    }

    private static LinkSpec spec(final FakeGateway peer) {
        return new LinkSpec(ID, HOST, peer.port(), Set.of("#"), Set.of(), 4);
    }

    private static Gateway gateway() throws IOException {
        return Gateway.start(GatewayOptions.defaults(new InetSocketAddress(HOST, 0)));
    }

    private static int publish(final Gateway gateway, final Packet.Publish m) throws Exception {
        return gateway.publish(m, "test").get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Packet.Publish big(final String topic) {
        final byte[] payload = new byte[PAYLOAD_BYTES];
        Arrays.fill(payload, (byte) 'x');
        return new Packet.Publish(topic, payload, 1, false, false, 0, null, null);
    }

    private static Packet.Publish plain(final String topic, final int n) {
        return new Packet.Publish(topic, bytes(n), 1, false, false, 0, null, null);
    }

    private static Packet.Publish retained(final String topic, final int n) {
        return new Packet.Publish(topic, bytes(n), 1, true, false, 0, null, null);
    }

    private static byte[] bytes(final int n) {
        return Integer.toString(n).getBytes(StandardCharsets.UTF_8);
    }

    private static LinkState state(final Links links) {
        for (final LinkState s : links.state()) {
            if (s.id().equals(ID)) {
                return s;
            }
        }
        throw new AssertionError("no such link [link=" + ID + ']');
    }

    private static List<String> topics(final FakeGateway peer) {
        final List<String> topics = new ArrayList<>();
        for (final Packet.Publish p : peer.received(Packet.Publish.class)) {
            topics.add(p.topic());
        }
        return topics;
    }

    private static List<Integer> payloads(final FakeGateway peer) {
        final List<Integer> payloads = new ArrayList<>();
        for (final Packet.Publish p : peer.received(Packet.Publish.class)) {
            payloads.add(Integer.parseInt(new String(p.payload(), StandardCharsets.UTF_8)));
        }
        return payloads;
    }

    /// The offsets the watch saw dropped as superseded, in order.
    private static List<Long> superseded(final Watch watch) {
        final List<Long> offsets = new ArrayList<>();
        for (final Observation o : watch.since(0, 1_024).events()) {
            if (o instanceof final LinkStep s && s.kind() == LinkStep.Kind.DROPPED
                    && Link.SUPERSEDED.equals(s.detail())) {
                offsets.add(s.offset());
            }
        }
        return offsets;
    }

    /// Moves when the link went down to `nanos`, as if it had been down since then.
    private static void downSince(final Links links, final long nanos) throws Exception {
        final Field downAt = Link.class.getDeclaredField("downAtNanos");
        downAt.setAccessible(true);
        downAt.setLong(link(links), nanos);
    }

    /// Field `name` of the link.
    private static Object field(final Links links, final String name) throws Exception {
        final Field f = Link.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(link(links));
    }

    private static Link link(final Links links) throws Exception {
        final Field linksField = Links.class.getDeclaredField("links");
        linksField.setAccessible(true);
        return (Link) ((Map<?, ?>) linksField.get(links)).get(ID);
    }
}
