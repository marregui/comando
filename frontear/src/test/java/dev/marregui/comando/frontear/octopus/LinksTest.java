package dev.marregui.comando.frontear.octopus;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.gateway.Gateway;
import dev.marregui.comando.frontear.gateway.GatewayOptions;
import dev.marregui.comando.frontear.gateway.InternalSubscriber;
import dev.marregui.comando.frontear.protocol.FakeGateway;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.store.LogOptions;
import dev.marregui.comando.frontear.tls.MachineKey;
import dev.marregui.comando.frontear.tls.Pins;
import dev.marregui.comando.frontear.tls.Terminator;
import dev.marregui.comando.frontear.tls.Tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// Links between real gateways in one JVM, and against [FakeGateway] where the test must answer for the peer: one copy
/// per message whatever the filters overlap, no echo over a two-way link, order and no loss across a peer's and the
/// link's restart with a backlog, filter changes that miss nothing covered and lose nothing queued, the hop limit on a
/// loop of three, the PUBACK classes, a refused message sent again alone and counted when a later one overtook
/// it, the bound on what waits for a link's strand, a close that answers what waits and interrupts nothing,
/// expiry while queued, the IN side's PUBACK after the local publish, a message in outside the IN filters dropped,
/// the checks on what a link is given, a link's spec kept across a restart, and a TLS link that waits for the octopus's
/// TLS context.
class LinksTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Duration QUIET = Duration.ofMillis(500);
    private static final LogOptions LOG = new LogOptions(64 * 1024, 64 * 1024 * 1024, Duration.ZERO);
    private static final String HOST = "127.0.0.1";
    private static final int QUEUE_MAX = LinkOptions.defaults().queueMax();

    private static ExecutorService callbacks;

    @TempDir
    Path dir;

    @BeforeAll
    static void startCallbacks() {
        callbacks = Threads.strand("links-test-callbacks");
    }

    @AfterAll
    static void stopCallbacks() {
        if (callbacks != null) {
            ExecutorStop.now("links-test", callbacks);
        }
    }

    /// A message published on A for a filter of link A to B is queued, acknowledged to its publisher once durable,
    /// and arrives on B once, with one hop counted; the log's cursor then reaches its tail.
    @Test
    void aMessageForAnOutFilterArrivesOnThePeerOnce() throws Exception {
        try (Gateway a = gateway(0); Gateway b = gateway(0); Links links = new Links(a, "A", dir, LOG);
                MqttClient publisher = client(a, "publisher")) {
            final Probe onB = Probe.on(b, "t/#");
            links.put(new LinkSpec("ab", HOST, b.port(), Set.of("t/#"), Set.of(), 4));
            awaitConnected(links, "ab");
            assertThat(publish(publisher, "t/x", 0)).isEqualTo(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> onB.messages.size() == 1);
            await().during(QUIET).atMost(WAIT).until(() -> onB.messages.size() == 1);
            assertThat(Forwarding.hops(onB.messages.getFirst())).isEqualTo(1);
            await().atMost(WAIT).until(() -> state(links, "ab").cursor() == 1);
            assertThat(state(links, "ab")).isEqualTo(new LinkState("ab", true, 0, 1, 1, 1, 0));
        }
    }

    /// Every OUT filter of a link is one internal subscriber: a message several of them match is queued and sent
    /// once.
    @Test
    void overlappingFiltersGiveOneCopy() throws Exception {
        try (Gateway a = gateway(0); Gateway b = gateway(0); Links links = new Links(a, "A", dir, LOG);
                MqttClient publisher = client(a, "publisher")) {
            final Probe onB = Probe.on(b, "#");
            links.put(new LinkSpec("ab", HOST, b.port(), Set.of("#", "t/#", "t/+", "t/x"), Set.of(), 4));
            awaitConnected(links, "ab");
            publish(publisher, "t/x", 0);
            publish(publisher, "t/y/z", 1);
            await().atMost(WAIT).until(() -> onB.messages.size() == 2);
            await().during(QUIET).atMost(WAIT).until(() -> onB.messages.size() == 2);
            assertThat(onB.payloads()).containsExactly(0, 1);
            assertThat(state(links, "ab").tail()).isEqualTo(2);
        }
    }

    /// One link both ways: what A sends B does not come back, because the link's IN subscription on B is No Local
    /// under the link's client id; what B sends A does not go back, because the link publishes it on A as its own OUT
    /// subscriber, which No Local leaves out. Each side sees each message once.
    @Test
    void noEchoOverATwoWayLink() throws Exception {
        try (Gateway a = gateway(0); Gateway b = gateway(0); Links links = new Links(a, "A", dir, LOG);
                MqttClient onA = client(a, "publisher-a"); MqttClient onB = client(b, "publisher-b")) {
            final Probe seenOnA = Probe.on(a, "t/#");
            final Probe seenOnB = Probe.on(b, "t/#");
            links.put(new LinkSpec("ab", HOST, b.port(), Set.of("t/#"), Set.of("t/#"), 4));
            awaitConnected(links, "ab");
            // the IN subscription is in place once a message published on B reaches A
            await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
                b.publish(message("t/warm", -1), "warm-up");
                return seenOnA.count("t/warm") > 0;
            });
            publish(onA, "t/x", 1);
            publish(onB, "t/x", 2);
            await().atMost(WAIT).until(() -> seenOnA.count("t/x") == 2 && seenOnB.count("t/x") == 2);
            await().during(QUIET).atMost(WAIT).until(() -> seenOnA.count("t/x") == 2 && seenOnB.count("t/x") == 2);
            assertThat(seenOnA.payloads("t/x")).containsExactlyInAnyOrder(1, 2);
            assertThat(seenOnB.payloads("t/x")).containsExactlyInAnyOrder(1, 2);
        }
    }

    /// Order and no loss across restarts with a backlog: every message acknowledged 0x00 to its publisher reaches
    /// the peer, in the order published, after the peer went down with messages still to come and the link itself
    /// was closed and opened again on its log; the reopened link sends from its cursor, nothing it had sent before.
    @Test
    void orderIsKeptAcrossAPeerAndALinkRestartWithABacklog() throws Exception {
        final LinkSpec spec;
        final Probe before;
        try (Gateway a = gateway(0); MqttClient publisher = client(a, "publisher")) {
            try (Links links = new Links(a, "A", dir, LOG)) {
                try (Gateway b = gateway(0)) {
                    before = Probe.on(b, "t/#");
                    spec = new LinkSpec("ab", HOST, b.port(), Set.of("t/#"), Set.of(), 4);
                    links.put(spec);
                    assertThat(publishAll(publisher, "t/x", 0, 100)).containsOnly(Packet.SUCCESS);
                    await().atMost(WAIT).until(() -> state(links, "ab").cursor() == 100);
                }
                await().atMost(WAIT).until(() -> !state(links, "ab").connected());
                assertThat(publishAll(publisher, "t/x", 100, 200)).containsOnly(Packet.SUCCESS);
                assertThat(state(links, "ab").queued()).isEqualTo(200);
            }
            try (Gateway b = gateway(spec.port()); Links links = new Links(a, "A", dir, LOG)) {
                final Probe after = Probe.on(b, "t/#");
                links.put(spec);
                await().atMost(WAIT).until(() -> after.messages.size() == 200);
                await().during(QUIET).atMost(WAIT).until(() -> after.messages.size() == 200);
                assertThat(before.payloads()).containsExactlyElementsOf(range(0, 100));
                assertThat(after.payloads()).containsExactlyElementsOf(range(100, 300));
                await().atMost(WAIT).until(() -> state(links, "ab").cursor() == 300);
                assertThat(state(links, "ab").queued()).isZero();
            }
        }
    }

    /// Replacing the OUT filters while messages flow adds the new ones before it removes the old, so no message
    /// covered before and after the change finds no subscriber (0x10) or goes missing, and the order holds.
    @Test
    void aFilterChangeUnderLoadMissesNothingItCovers() throws Exception {
        try (Gateway a = gateway(0); Gateway b = gateway(0); Links links = new Links(a, "A", dir, LOG);
                MqttClient publisher = client(a, "publisher")) {
            final Probe onB = Probe.on(b, "t/#");
            links.put(new LinkSpec("ab", HOST, b.port(), Set.of("t/#"), Set.of(), 4));
            awaitConnected(links, "ab");
            final List<CompletableFuture<Integer>> acks = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                acks.add(publisher.publishAcknowledged(message("t/x", i)));
                if (i % 50 == 25) {
                    links.filters("ab", Direction.OUT, i % 100 == 25 ? Set.of("t/+", "u/#") : Set.of("t/#"));
                }
            }
            for (final CompletableFuture<Integer> ack : acks) {
                assertThat(ack.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            }
            await().atMost(WAIT).until(() -> onB.messages.size() == 500);
            assertThat(onB.payloads()).containsExactlyElementsOf(range(0, 500));
        }
    }

    /// A filter change that drops what queued messages matched loses none of them: they are in the log and go to the
    /// peer when it is back; a message the new filters do not cover finds no subscriber.
    @Test
    void aFilterChangeLosesNothingAlreadyQueued() throws Exception {
        try (Gateway a = gateway(0); Links links = new Links(a, "A", dir, LOG);
                MqttClient publisher = client(a, "publisher")) {
            final int port;
            try (Gateway b = gateway(0)) {
                port = b.port();
                links.put(new LinkSpec("ab", HOST, port, Set.of("t/#"), Set.of(), 4));
                awaitConnected(links, "ab");
            }
            await().atMost(WAIT).until(() -> !state(links, "ab").connected());
            assertThat(publishAll(publisher, "t/x", 0, 50)).containsOnly(Packet.SUCCESS);
            links.filters("ab", Direction.OUT, Set.of("u/#"));
            assertThat(publish(publisher, "t/x", 50)).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            try (Gateway b = gateway(port)) {
                final Probe onB = Probe.on(b, "t/#");
                await().atMost(WAIT).until(() -> onB.messages.size() == 50);
                await().during(QUIET).atMost(WAIT).until(() -> onB.messages.size() == 50);
                assertThat(onB.payloads()).containsExactlyElementsOf(range(0, 50));
            }
        }
    }

    /// Three gateways linked in a ring, A to B to C to A, with a hop limit of 3: a message published on A goes round
    /// once, comes back to A with three hops and stops there, counted as dropped by the link that refused it.
    @Test
    void theHopLimitStopsAThreeGatewayLoop() throws Exception {
        try (Gateway a = gateway(0); Gateway b = gateway(0); Gateway c = gateway(0);
                Links fromA = new Links(a, "A", dir.resolve("a"), LOG);
                Links fromB = new Links(b, "B", dir.resolve("b"), LOG);
                Links fromC = new Links(c, "C", dir.resolve("c"), LOG);
                MqttClient publisher = client(a, "publisher")) {
            final Probe onA = Probe.on(a, "t/#");
            final Probe onB = Probe.on(b, "t/#");
            final Probe onC = Probe.on(c, "t/#");
            fromA.put(new LinkSpec("ab", HOST, b.port(), Set.of("t/#"), Set.of(), 3));
            fromB.put(new LinkSpec("bc", HOST, c.port(), Set.of("t/#"), Set.of(), 3));
            fromC.put(new LinkSpec("ca", HOST, a.port(), Set.of("t/#"), Set.of(), 3));
            awaitConnected(fromA, "ab");
            awaitConnected(fromB, "bc");
            awaitConnected(fromC, "ca");
            publish(publisher, "t/x", 0);
            await().atMost(WAIT).until(() -> state(fromA, "ab").dropped() == 1);
            await().during(QUIET).atMost(WAIT).until(() -> onA.messages.size() == 2 && onB.messages.size() == 1
                    && onC.messages.size() == 1);
            assertThat(onA.messages).extracting(Forwarding::hops).containsExactly(0, 3);
            assertThat(Forwarding.hops(onB.messages.getFirst())).isEqualTo(1);
            assertThat(Forwarding.hops(onC.messages.getFirst())).isEqualTo(2);
            assertThat(state(fromA, "ab").sent()).isEqualTo(1);
        }
    }

    /// The peer's PUBACK decides: a code below 0x80 is sent; 0x97 and 0x89 hold that message and send it again alone
    /// after a backoff, nothing else with it, until the peer takes it; another refusal drops the message and counts
    /// it; the cursor passes what is decided.
    @Test
    void pubackCodesAreClassified() throws Exception {
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway(0); Links links = new Links(a, "A", dir, LOG);
                MqttClient publisher = client(a, "publisher")) {
            peer.ackPublishes(false);
            links.put(new LinkSpec("ab", HOST, peer.port(), Set.of("t/#"), Set.of(), 4));
            awaitConnected(links, "ab");
            assertThat(publishAll(publisher, "t/x", 0, 3)).containsOnly(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 3);
            peer.send(new Packet.PubAck(sentTo(peer, 0).packetId(), Packet.NOT_AUTHORIZED, null));
            peer.send(new Packet.PubAck(sentTo(peer, 1).packetId(), Packet.QUOTA_EXCEEDED, null));
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 4);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 4);
            assertThat(state(links, "ab").cursor()).isEqualTo(1);
            assertThat(state(links, "ab").dropped()).isEqualTo(1);
            peer.send(new Packet.PubAck(sentTo(peer, 3).packetId(), Link.SERVER_BUSY, null));
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 5);
            peer.send(new Packet.PubAck(sentTo(peer, 4).packetId(), Packet.SUCCESS, null));
            await().atMost(WAIT).until(() -> state(links, "ab").cursor() == 2);
            peer.send(new Packet.PubAck(sentTo(peer, 2).packetId(), Packet.NO_MATCHING_SUBSCRIBERS, null));
            await().atMost(WAIT).until(() -> state(links, "ab").cursor() == 3);
            assertThat(payloads(peer)).containsExactly(0, 1, 2, 1, 1);
            assertThat(state(links, "ab")).isEqualTo(new LinkState("ab", true, 0, 3, 3, 2, 1, 0));
        }
    }

    /// A message the peer refuses for now after it took a later one of the same window: only the refused one goes
    /// again, the window is one until the peer takes it (a message appended meanwhile waits), it is counted as
    /// reordered once, and the window then opens again.
    @Test
    void aRefusedMessageGoesAgainAloneAndCountsTheOvertaking() throws Exception {
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway(0); Links links = new Links(a, "A", dir, LOG);
                MqttClient publisher = client(a, "publisher")) {
            peer.ackPublishes(false);
            links.put(new LinkSpec("ab", HOST, peer.port(), Set.of("t/#"), Set.of(), 4));
            awaitConnected(links, "ab");
            assertThat(publishAll(publisher, "t/x", 0, 3)).containsOnly(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 3);
            peer.send(new Packet.PubAck(sentTo(peer, 1).packetId(), Packet.SUCCESS, null));
            peer.send(new Packet.PubAck(sentTo(peer, 0).packetId(), Packet.QUOTA_EXCEEDED, null));
            await().atMost(WAIT).until(() -> state(links, "ab").reordered() == 1);
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 4);
            assertThat(payload(sentTo(peer, 3))).isZero();
            assertThat(publish(publisher, "t/x", 3)).isEqualTo(Packet.SUCCESS);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 4);
            peer.send(new Packet.PubAck(sentTo(peer, 3).packetId(), Packet.SUCCESS, null));
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 5);
            assertThat(payload(sentTo(peer, 4))).isEqualTo(3);
            await().atMost(WAIT).until(() -> state(links, "ab").cursor() == 2);
            peer.send(new Packet.PubAck(sentTo(peer, 2).packetId(), Packet.SUCCESS, null));
            peer.send(new Packet.PubAck(sentTo(peer, 4).packetId(), Packet.SUCCESS, null));
            await().atMost(WAIT).until(() -> state(links, "ab").cursor() == 4);
            assertThat(payloads(peer)).containsExactly(0, 1, 2, 0, 3);
            assertThat(state(links, "ab")).isEqualTo(new LinkState("ab", true, 0, 4, 4, 4, 0, 1));
        }
    }

    /// Between real gateways: a peer whose own queue refuses the first of three messages (0x97) and takes the next
    /// two gets the first once more, alone, and none of the two again; the link counts the one overtaking.
    @Test
    void aRefusalBetweenGatewaysResendsOnlyTheRefusedMessage() throws Exception {
        try (Gateway a = gateway(0); Links links = new Links(a, "A", dir, LOG);
                MqttClient publisher = client(a, "publisher")) {
            final int port;
            try (Gateway b = gateway(0)) {
                port = b.port();
                links.put(new LinkSpec("ab", HOST, port, Set.of("t/#"), Set.of(), 4));
                awaitConnected(links, "ab");
            }
            await().atMost(WAIT).until(() -> !state(links, "ab").connected());
            assertThat(publishAll(publisher, "t/x", 0, 3)).containsOnly(Packet.SUCCESS);
            try (Gateway b = gateway(port)) {
                final RefuseFirst gate = new RefuseFirst();
                b.subscribe("gate", "t/#", 1, false, gate);
                await().atMost(WAIT).until(() -> gate.accepted.size() == 3);
                await().during(QUIET).atMost(WAIT).until(() -> gate.accepted.size() == 3);
                assertThat(gate.accepted).containsExactly(1, 2, 0);
                await().atMost(WAIT).until(() -> state(links, "ab").cursor() == 3);
                assertThat(state(links, "ab").reordered()).isEqualTo(1);
                assertThat(state(links, "ab").sent()).isEqualTo(3);
            }
        }
    }

    /// QoS 0 publishers while the link's strand is busy (a slow disk write stands in): at most the link's queue bound
    /// waits for the strand, the rest is dropped and counted, the queue's reading shows it full, and the strand's own
    /// queue holds one append for all of them; once free, the strand appends what waited.
    @Test
    void qosZeroMessagesOverTheLinksQueueAreDroppedAndCounted() throws Exception {
        try (Gateway a = gateway(0); Links links = new Links(a, "A", dir, LOG);
                MqttClient publisher = client(a, "publisher")) {
            links.put(new LinkSpec("ab", HOST, 1, Set.of("t/#"), Set.of(), 4));
            final ScheduledThreadPoolExecutor strand = strandOf(links, "ab");
            final CountDownLatch release = new CountDownLatch(1);
            strand.execute(() -> hold(release, new AtomicBoolean()));
            final int count = 3 * QUEUE_MAX;
            final byte[] payload = new byte[1024];
            final List<CompletableFuture<Void>> written = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                written.add(publisher.publish(new Packet.Publish("t/x", payload, 0, false, false, 0, null, null)));
            }
            for (final CompletableFuture<Void> w : written) {
                w.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
            await().atMost(WAIT).until(() -> state(links, "ab").dropped() == count - QUEUE_MAX);
            assertThat(waiting("ab")).isEqualTo(QUEUE_MAX);
            assertThat(strand.getQueue().size()).as("tasks waiting on the link strand").isLessThan(100);
            release.countDown();
            await().atMost(WAIT).until(() -> state(links, "ab").tail() == QUEUE_MAX);
            assertThat(state(links, "ab").dropped()).isEqualTo(count - QUEUE_MAX);
        }
    }

    /// QoS 1 messages while the link's strand is busy: the link's queue bound of them wait, the next is answered 0x97
    /// at once, and a QoS 0 one over the bound is dropped and counted; once free, the strand appends every message that
    /// waited and answers each 0x00.
    @Test
    void aQosOneMessageOverTheLinksQueueIsAnsweredQuotaExceeded() throws Exception {
        try (Gateway a = gateway(0); Links links = new Links(a, "A", dir, LOG)) {
            links.put(new LinkSpec("ab", HOST, 1, Set.of("t/#"), Set.of(), 4));
            final ScheduledThreadPoolExecutor strand = strandOf(links, "ab");
            final CountDownLatch release = new CountDownLatch(1);
            strand.execute(() -> hold(release, new AtomicBoolean()));
            final List<CompletableFuture<Integer>> waiting = new ArrayList<>();
            for (int i = 0; i < QUEUE_MAX; i++) {
                waiting.add(a.publish(message("t/x", i), "test"));
            }
            assertThat(a.publish(message("t/x", QUEUE_MAX), "test")).succeedsWithin(Duration.ofSeconds(5))
                    .isEqualTo(Packet.QUOTA_EXCEEDED);
            assertThat(a.publish(new Packet.Publish("t/x", bytes(0), 0, false, false, 0, null, null), "test"))
                    .succeedsWithin(Duration.ofSeconds(5)).isEqualTo(Packet.QUOTA_EXCEEDED);
            assertThat(state(links, "ab").dropped()).isEqualTo(1);
            assertThat(waiting).noneMatch(CompletableFuture::isDone);
            release.countDown();
            for (final CompletableFuture<Integer> w : waiting) {
                assertThat(w.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            }
            assertThat(state(links, "ab").tail()).isEqualTo(QUEUE_MAX);
        }
    }

    /// The link's queue bound is the one its options give: with the strand busy, that many messages wait, the next is
    /// answered 0x97, and the link's state shows the messages waiting beside the bound; once the strand is free they
    /// are appended and none waits.
    @Test
    void theLinksQueueBoundComesFromItsOptionsAndItsStateShowsItsUsage() throws Exception {
        final int queueMax = 10;
        try (Gateway a = gateway(0); Links links = new Links(a, "A", dir, LOG, new LinkOptions(queueMax, QUEUE_MAX))) {
            links.put(new LinkSpec("ab", HOST, 1, Set.of("t/#"), Set.of(), 4));
            final ScheduledThreadPoolExecutor strand = strandOf(links, "ab");
            final CountDownLatch release = new CountDownLatch(1);
            strand.execute(() -> hold(release, new AtomicBoolean()));
            final List<CompletableFuture<Integer>> waiting = new ArrayList<>();
            for (int i = 0; i < queueMax; i++) {
                waiting.add(a.publish(message("t/x", i), "test"));
            }
            assertThat(a.publish(message("t/x", queueMax), "test")).succeedsWithin(Duration.ofSeconds(5))
                    .isEqualTo(Packet.QUOTA_EXCEEDED);
            assertThat(state(links, "ab").queuedDeliveries()).isEqualTo(queueMax);
            assertThat(state(links, "ab").queueMax()).isEqualTo(queueMax);
            release.countDown();
            for (final CompletableFuture<Integer> w : waiting) {
                assertThat(w.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            }
            assertThat(state(links, "ab").tail()).isEqualTo(queueMax);
            assertThat(state(links, "ab").queuedDeliveries()).isZero();
        }
    }

    /// The peer's QoS 0 messages wait for the link's strand within the link's queue bound: with the strand busy, that
    /// many wait, the rest are dropped and counted in the link's `dropped`, and once the strand is free the ones that
    /// waited are published locally, in order, and no others.
    @Test
    void thePeersQos0MessagesWaitWithinTheLinksQueueBoundAndTheRestAreDroppedAndCounted() throws Exception {
        final int queueMax = 10;
        final int count = 10 * queueMax;
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway(0);
                Links links = new Links(a, "A", dir, LOG, new LinkOptions(queueMax, QUEUE_MAX))) {
            final Probe onA = Probe.on(a, "in/#");
            links.put(new LinkSpec("ab", HOST, peer.port(), Set.of(), Set.of("in/#"), 4));
            await().atMost(WAIT).until(() -> peer.received(Packet.Subscribe.class).size() == 1);
            final ScheduledThreadPoolExecutor strand = strandOf(links, "ab");
            final CountDownLatch holding = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            try {
                strand.execute(() -> {
                    holding.countDown();
                    hold(release, new AtomicBoolean());
                });
                assertThat(holding.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
                final int before = strand.getQueue().size();
                for (int i = 0; i < count; i++) {
                    peer.send(new Packet.Publish("in/x", bytes(i), 0, false, false, 0, null, null));
                }
                await().atMost(WAIT).until(() -> state(links, "ab").dropped() == count - queueMax);
                assertThat(strand.getQueue().size() - before).isLessThanOrEqualTo(queueMax);
            } finally {
                release.countDown();
            }
            await().atMost(WAIT).until(() -> onA.messages.size() == queueMax);
            await().during(QUIET).atMost(WAIT).until(() -> onA.messages.size() == queueMax);
            assertThat(onA.messages).extracting(LinksTest::payload).containsExactlyElementsOf(range(0, queueMax));
            assertThat(state(links, "ab").dropped()).isEqualTo(count - queueMax);
        }
    }

    /// The link's window is capped by its options below the peer's Receive Maximum: with the peer answering nothing,
    /// only the cap's worth is in flight, and each answer lets one more go.
    @Test
    void theLinksWindowIsCappedByItsOptionsBelowThePeersReceiveMaximum() throws Exception {
        final int windowMax = 2;
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway(0);
                Links links = new Links(a, "A", dir, LOG, new LinkOptions(QUEUE_MAX, windowMax));
                MqttClient publisher = client(a, "publisher")) {
            peer.ackPublishes(false);
            links.put(new LinkSpec("ab", HOST, peer.port(), Set.of("t/#"), Set.of(), 4));
            awaitConnected(links, "ab");
            assertThat(publishAll(publisher, "t/x", 0, 5)).containsOnly(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == windowMax);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == windowMax);
            peer.send(new Packet.PubAck(sentTo(peer, 0).packetId(), Packet.SUCCESS, null));
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == windowMax + 1);
            await().during(QUIET).atMost(WAIT)
                    .until(() -> peer.received(Packet.Publish.class).size() == windowMax + 1);
            assertThat(payloads(peer)).containsExactly(0, 1, 2);
        }
    }

    /// A link that is caught up sends each message as it becomes durable, whatever its window holds: with a window of
    /// four and a peer that answers nothing, four messages published one at a time are all in flight.
    @Test
    void aCaughtUpLinkSendsEachMessageWithoutWaitingForAnswers() throws Exception {
        final int windowMax = 4;
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway(0);
                Links links = new Links(a, "A", dir, LOG, new LinkOptions(QUEUE_MAX, windowMax));
                MqttClient publisher = client(a, "publisher")) {
            peer.ackPublishes(false);
            links.put(new LinkSpec("ab", HOST, peer.port(), Set.of("t/#"), Set.of(), 4));
            awaitConnected(links, "ab");
            for (int i = 0; i < windowMax; i++) {
                final int sent = i + 1;
                assertThat(publishAll(publisher, "t/x", i, 1)).containsOnly(Packet.SUCCESS);
                await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == sent);
            }
            assertThat(payloads(peer)).containsExactly(0, 1, 2, 3);
        }
    }

    /// The link refills its window once half of it is free, not per answer: with a window of four and the peer
    /// answering nothing, one answer sends nothing more, the second sends two, in order.
    @Test
    void theLinkRefillsItsWindowOnceHalfOfItIsFree() throws Exception {
        final int windowMax = 4;
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway(0);
                Links links = new Links(a, "A", dir, LOG, new LinkOptions(QUEUE_MAX, windowMax));
                MqttClient publisher = client(a, "publisher")) {
            peer.ackPublishes(false);
            links.put(new LinkSpec("ab", HOST, peer.port(), Set.of("t/#"), Set.of(), 4));
            awaitConnected(links, "ab");
            assertThat(publishAll(publisher, "t/x", 0, 8)).containsOnly(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == windowMax);
            peer.send(new Packet.PubAck(sentTo(peer, 0).packetId(), Packet.SUCCESS, null));
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == windowMax);
            peer.send(new Packet.PubAck(sentTo(peer, 1).packetId(), Packet.SUCCESS, null));
            await().atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == windowMax + 2);
            await().during(QUIET).atMost(WAIT)
                    .until(() -> peer.received(Packet.Publish.class).size() == windowMax + 2);
            assertThat(payloads(peer)).containsExactly(0, 1, 2, 3, 4, 5);
        }
    }

    /// Link options out of range are refused: an empty queue, an empty window, and a window larger than the client's
    /// publish queue, whose go-back sends would not fit in it.
    @Test
    void linkOptionsOutOfRangeAreRefused() {
        assertThatThrownBy(() -> new LinkOptions(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkOptions(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkOptions(1, MqttClient.QUEUE_MAX + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LinkOptions(1, MqttClient.QUEUE_MAX).windowMax()).isEqualTo(MqttClient.QUEUE_MAX);
    }

    /// A link removed while its strand is busy past the close's wait (a slow disk write stands in): a QoS 1 message
    /// still waiting for the strand is answered 0x8B at once, the busy task is not interrupted, and the strand's last
    /// task closes the log once the busy one returns.
    @Test
    void aCloseAnswersWhatWaitsAndInterruptsNothing() throws Exception {
        try (Gateway a = gateway(0); Links links = new Links(a, "A", dir, LOG)) {
            links.put(new LinkSpec("ab", HOST, 1, Set.of("t/#"), Set.of(), 4));
            final ScheduledThreadPoolExecutor strand = strandOf(links, "ab");
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicBoolean interrupted = new AtomicBoolean();
            strand.execute(() -> hold(release, interrupted));
            final int before = strand.getQueue().size();
            final CompletableFuture<Integer> ack = a.publish(message("t/x", 0), "test");
            await().atMost(WAIT).until(() -> strand.getQueue().size() > before);
            links.remove("ab");
            assertThat(ack).as("the publisher's answer after the link's close").succeedsWithin(Duration.ofSeconds(5))
                    .isEqualTo(Packet.SERVER_SHUTTING_DOWN);
            assertThat(interrupted).as("the busy task was interrupted").isFalse();
            assertThat(strand.isTerminated()).as("the strand ended under its busy task").isFalse();
            release.countDown();
            await().atMost(WAIT).until(strand::isTerminated);
            assertThat(interrupted).as("the busy task was interrupted").isFalse();
        }
    }

    /// A message whose expiry passes while it waits in the log is skipped and counted; one that outlives the wait is
    /// sent with its expiry less the whole seconds it waited, and without the queue time the log kept.
    @Test
    void anExpiredMessageIsSkippedAndALiveOneDecremented() throws Exception {
        final ManualClock clock = new ManualClock(1_700_000_000_000L);
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway(0);
                Links links = new Links(a, "A", dir, LOG, LinkOptions.defaults(), clock);
                MqttClient publisher = client(a, "publisher")) {
            peer.connectCode(Packet.NOT_AUTHORIZED);
            links.put(new LinkSpec("ab", HOST, peer.port(), Set.of("t/#"), Set.of(), 4));
            await().atMost(WAIT).until(() -> !peer.received(Packet.Connect.class).isEmpty());
            assertThat(publisher.publishAcknowledged(expiring("t/short", 10)).get(WAIT.toMillis(),
                    TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            assertThat(publisher.publishAcknowledged(expiring("t/long", 100)).get(WAIT.toMillis(),
                    TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            clock.advance(Duration.ofSeconds(20));
            peer.connectCode(Packet.SUCCESS);
            await().atMost(WAIT).until(() -> state(links, "ab").cursor() == 2);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.Publish.class).size() == 1);
            final Packet.Publish sent = peer.received(Packet.Publish.class).getFirst();
            assertThat(sent.topic()).isEqualTo("t/long");
            assertThat(sent.meta().messageExpirySeconds()).isEqualTo(80);
            assertThat(sent.meta().userProperties()).extracting(Packet.UserProperty::name)
                    .containsExactly(Forwarding.HOPS);
            assertThat(state(links, "ab").dropped()).isEqualTo(1);
        }
    }

    /// The IN side subscribes on the peer with No Local and Retain As Published, publishes what arrives on the
    /// local gateway with one more hop, and answers the peer only when that publish completes, with its code; a
    /// message past the hop limit is answered 0x00 and not published; replacing the IN filters subscribes to the new
    /// one before it unsubscribes from the old.
    @Test
    void theInSideAnswersThePeerAfterTheLocalPublish() throws Exception {
        try (FakeGateway peer = new FakeGateway(); Gateway a = gateway(0); Links links = new Links(a, "A", dir, LOG)) {
            final Probe onA = Probe.holding(a, "in/#");
            links.put(new LinkSpec("ab", HOST, peer.port(), Set.of(), Set.of("in/#"), 2));
            await().atMost(WAIT).until(() -> peer.received(Packet.Subscribe.class).size() == 1);
            assertThat(peer.received(Packet.Subscribe.class).getFirst().subscriptions())
                    .containsExactly(new Packet.Subscription("in/#", 1, true, true, 0));
            peer.send(new Packet.Publish("in/x", bytes(0), 1, false, false, 7, null, null));
            await().atMost(WAIT).until(() -> onA.messages.size() == 1);
            assertThat(Forwarding.hops(onA.messages.getFirst())).isEqualTo(1);
            await().during(QUIET).atMost(WAIT).until(() -> peer.received(Packet.PubAck.class).isEmpty());
            onA.answers.getFirst().complete(Packet.QUOTA_EXCEEDED);
            await().atMost(WAIT).until(() -> !peer.received(Packet.PubAck.class).isEmpty());
            assertThat(peer.received(Packet.PubAck.class)).containsExactly(new Packet.PubAck(7, Packet.QUOTA_EXCEEDED,
                    null));
            peer.send(new Packet.Publish("in/y", bytes(1), 1, false, false, 8, null, null, new Packet.Meta(-1, -1,
                    null, List.of(new Packet.UserProperty(Forwarding.HOPS, "2")))));
            await().atMost(WAIT).until(() -> peer.received(Packet.PubAck.class).size() == 2);
            assertThat(peer.received(Packet.PubAck.class).get(1)).isEqualTo(new Packet.PubAck(8, Packet.SUCCESS,
                    null));
            assertThat(onA.messages).hasSize(1);
            assertThat(state(links, "ab").dropped()).isEqualTo(1);
            links.filters("ab", Direction.IN, Set.of("in2/#"));
            await().atMost(WAIT).until(() -> peer.received(Packet.Unsubscribe.class).size() == 1);
            assertThat(peer.received(Packet.Subscribe.class)).hasSize(2);
            assertThat(peer.received(Packet.Subscribe.class).get(1).subscriptions())
                    .containsExactly(new Packet.Subscription("in2/#", 1, true, true, 0));
            assertThat(peer.received(Packet.Unsubscribe.class).getFirst().filters()).containsExactly("in/#");
        }
    }

    /// A peer that pushes a message the link never subscribed to (another machine's server, for one) is answered
    /// 0x00 and nothing reaches the local gateway, on a link with no IN filters and on one whose IN filters do not
    /// cover the topic; each such message is counted as dropped, and one the filters cover still arrives.
    @Test
    void aMessageInOutsideTheInFiltersIsDroppedAndCounted() throws Exception {
        try (FakeGateway peer = new FakeGateway(); FakeGateway other = new FakeGateway(); Gateway a = gateway(0);
                Links links = new Links(a, "A", dir, LOG)) {
            final Probe onA = Probe.on(a, "#");
            links.put(new LinkSpec("main@b", HOST, peer.port(), Set.of("comando/v1/b/instance/+/inbox"), Set.of(),
                    2));
            links.put(new LinkSpec("ac", HOST, other.port(), Set.of(), Set.of("in/#"), 2));
            awaitConnected(links, "main@b");
            awaitConnected(links, "ac");
            peer.send(new Packet.Publish("comando/v1/local/instance/x/inbox", bytes(0), 1, false, false, 7,
                    "comando/v1/local/instance/main/reply", null));
            other.send(new Packet.Publish("out/x", bytes(1), 1, false, false, 8, null, null));
            await().atMost(WAIT).until(() -> peer.received(Packet.PubAck.class).size() == 1
                    && other.received(Packet.PubAck.class).size() == 1);
            assertThat(peer.received(Packet.PubAck.class)).containsExactly(new Packet.PubAck(7, Packet.SUCCESS,
                    null));
            assertThat(other.received(Packet.PubAck.class)).containsExactly(new Packet.PubAck(8, Packet.SUCCESS,
                    null));
            assertThat(state(links, "main@b").dropped()).isEqualTo(1);
            assertThat(state(links, "ac").dropped()).isEqualTo(1);
            other.send(new Packet.Publish("in/x", bytes(2), 1, false, false, 9, null, null));
            await().atMost(WAIT).until(() -> onA.messages.size() == 1);
            assertThat(onA.payloads()).containsExactly(2);
        }
    }

    /// The octopus refuses what it cannot run (an id that is not a folder name, an empty host, a port or hop limit out
    /// of range, a filter that is not one) and a change to an unknown link; it replaces a link put again, forgets a
    /// removed one, and lists the links by id.
    @Test
    void theOctopusChecksWhatItIsGivenAndListsById() throws Exception {
        try (Gateway a = gateway(0); Gateway b = gateway(0); Links links = new Links(a, "A", dir, LOG)) {
            for (final LinkSpec bad : List.of(
                    new LinkSpec("..", HOST, b.port(), Set.of(), Set.of(), 1),
                    new LinkSpec("a/b", HOST, b.port(), Set.of(), Set.of(), 1),
                    new LinkSpec("ab", " ", b.port(), Set.of(), Set.of(), 1),
                    new LinkSpec("ab", HOST, 0, Set.of(), Set.of(), 1),
                    new LinkSpec("ab", HOST, 65_536, Set.of(), Set.of(), 1),
                    new LinkSpec("ab", HOST, b.port(), Set.of(), Set.of(), 0),
                    new LinkSpec("ab", HOST, b.port(), Set.of("t/#/x"), Set.of(), 1),
                    new LinkSpec("ab", HOST, b.port(), Set.of(), Set.of("t+"), 1))) {
                assertThatThrownBy(() -> links.put(bad)).as(bad.toString())
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> links.filters("none", Direction.OUT, Set.of("t/#")))
                    .isInstanceOf(IllegalArgumentException.class);
            links.put(new LinkSpec("zz", HOST, b.port(), Set.of("t/#"), Set.of(), 2));
            links.put(new LinkSpec("ab", HOST, b.port(), Set.of("t/#"), Set.of(), 2));
            links.put(new LinkSpec("ab", HOST, b.port(), Set.of("u/#"), Set.of(), 2));
            assertThat(links.state()).extracting(LinkState::id).containsExactly("ab", "zz");
            assertThat(a.publish(message("u/x", 0), "test").get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(Packet.SUCCESS);
            links.remove("zz");
            links.remove("never");
            assertThat(links.state()).extracting(LinkState::id).containsExactly("ab");
            assertThat(a.publish(message("t/x", 1), "test").get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
        }
    }

    /// A link to another machine's node is named `<node>@<machine>`: the octopus takes the id, keeps the link in a
    /// folder of that name and brings it back from there after a restart; a `/` after it is still refused.
    @Test
    void aLinkToAnotherMachineIsNamedNodeAtMachine() throws Exception {
        try (Gateway a = gateway(0); Gateway b = gateway(0)) {
            final LinkSpec remote = new LinkSpec("main@b", HOST, b.port(), Set.of("t/#"), Set.of(), 2);
            try (Links links = new Links(a, "A", dir, LOG)) {
                links.put(remote);
                assertThat(links.state()).extracting(LinkState::id).containsExactly("main@b");
                assertThatThrownBy(() -> links.put(new LinkSpec("main@b/x", HOST, b.port(), Set.of(), Set.of(), 1)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(dir.resolve("main@b").resolve(LinkFile.NAME)).isRegularFile();
            try (Links links = new Links(a, "A", dir, LOG)) {
                assertThat(links.state()).extracting(LinkState::id).containsExactly("main@b");
            }
        }
    }

    /// A link comes back when links are made again on its folder, with no put: its spec and its last filter change
    /// were kept, so the backlog queued while the peer was down reaches the peer once it is up, and a message for the
    /// changed filter is carried after the restart while one for the replaced filter is not.
    @Test
    void aLinkComesBackAfterARestartWithItsFiltersAndDrainsItsBacklog() throws Exception {
        final int port;
        try (Gateway b = gateway(0)) {
            port = b.port();
        }
        try (Gateway a = gateway(0); MqttClient publisher = client(a, "publisher")) {
            try (Links links = new Links(a, "A", dir, LOG)) {
                links.put(new LinkSpec("ab", HOST, port, Set.of("t/#"), Set.of(), 4));
                links.filters("ab", Direction.OUT, Set.of("u/#"));
                links.filters("ab", Direction.IN, Set.of("r/#"));
                assertThat(publishAll(publisher, "u/x", 0, 50)).containsOnly(Packet.SUCCESS);
                assertThat(state(links, "ab").queued()).isEqualTo(50);
            }
            try (Links links = new Links(a, "A", dir, LOG)) {
                assertThat(links.state()).extracting(LinkState::id).containsExactly("ab");
                assertThat(state(links, "ab").queued()).isEqualTo(50);
                assertThat(LinkFile.read(Files.readAllBytes(dir.resolve("ab").resolve(LinkFile.NAME))))
                        .isEqualTo(new LinkSpec("ab", HOST, port, Set.of("u/#"), Set.of("r/#"), 4));
                try (Gateway b = gateway(port)) {
                    final Probe onB = Probe.on(b, "#");
                    await().atMost(WAIT).until(() -> onB.messages.size() == 50);
                    assertThat(publish(publisher, "t/x", 50)).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
                    assertThat(publish(publisher, "u/x", 51)).isEqualTo(Packet.SUCCESS);
                    await().atMost(WAIT).until(() -> onB.messages.size() == 51);
                    await().during(QUIET).atMost(WAIT).until(() -> onB.messages.size() == 51);
                    final List<Integer> expected = new ArrayList<>(range(0, 50));
                    expected.add(51);
                    assertThat(onB.payloads()).containsExactlyElementsOf(expected);
                }
            }
        }
    }

    /// A removed link does not come back, and a spec that cannot be read, or that names another link than its
    /// folder, keeps its link down without stopping the others; a put of that id replaces the spec and starts the
    /// link; a remove refuses an id that is not a folder name.
    @Test
    void aRemovedLinkAndAnUnreadableSpecDoNotComeBack() throws Exception {
        try (Gateway a = gateway(0)) {
            try (Links links = new Links(a, "A", dir, LOG)) {
                links.put(new LinkSpec("ab", HOST, 1, Set.of("t/#"), Set.of(), 2));
                links.put(new LinkSpec("ac", HOST, 1, Set.of("t/#"), Set.of(), 2));
                links.put(new LinkSpec("ad", HOST, 1, Set.of("t/#"), Set.of(), 2));
                links.remove("ab");
            }
            Files.writeString(dir.resolve("ac").resolve(LinkFile.NAME), "{\"version\":2}");
            Files.createDirectories(dir.resolve("ae"));
            Files.write(dir.resolve("ae").resolve(LinkFile.NAME),
                    LinkFile.write(new LinkSpec("zz", HOST, 1, Set.of("t/#"), Set.of(), 2)));
            try (Links links = new Links(a, "A", dir, LOG)) {
                assertThat(links.state()).extracting(LinkState::id).containsExactly("ad");
                assertThatThrownBy(() -> links.remove("../ad")).isInstanceOf(IllegalArgumentException.class);
                links.put(new LinkSpec("ac", HOST, 1, Set.of("t/#"), Set.of(), 2));
            }
            try (Links links = new Links(a, "A", dir, LOG)) {
                assertThat(links.state()).extracting(LinkState::id).containsExactly("ac", "ad");
            }
        }
    }

    /// A TLS link put before the octopus has a TLS context stays down, its state saying why, and opens no connection;
    /// once the context is set it connects over TLS with this machine's key and its reason is cleared. Its spec, a
    /// filter change included, keeps `tls` across a restart, where the link waits for the context again.
    @Test
    void tlsLinkWaitsForTheContextAndKeepsTlsAcrossARestart(@TempDir final Path keys) throws Exception {
        final MachineKey local = MachineKey.loadOrCreate(keys.resolve("a"), "a");
        final MachineKey remote = MachineKey.loadOrCreate(keys.resolve("b"), "b");
        final SSLContext tls = Tls.context(local, pins(remote));
        try (Gateway a = gateway(0); FakeGateway peer = new FakeGateway();
                Terminator b = new Terminator(Tls.context(remote, pins(local)), peer.port())) {
            try (Links links = new Links(a, "A", dir, LOG)) {
                links.put(new LinkSpec("ab", HOST, b.port(), Set.of("t/#"), Set.of(), 4, true));
                await().atMost(WAIT).until(() -> state(links, "ab").reason().equals(Link.NO_TLS));
                await().during(QUIET).atMost(WAIT).until(() -> !state(links, "ab").connected());
                assertThat(b.protocols()).isEmpty();
                assertThat(b.failures()).isEmpty();
                links.tls(_ -> tls);
                awaitConnected(links, "ab");
                assertThat(state(links, "ab").reason()).isEmpty();
                assertThat(b.peers()).containsExactly(local.fingerprint());
                links.filters("ab", Direction.OUT, Set.of("u/#"));
            }
            assertThat(LinkFile.read(Files.readAllBytes(dir.resolve("ab").resolve(LinkFile.NAME))))
                    .isEqualTo(new LinkSpec("ab", HOST, b.port(), Set.of("u/#"), Set.of(), 4, true));
            try (Links links = new Links(a, "A", dir, LOG)) {
                await().atMost(WAIT).until(() -> state(links, "ab").reason().equals(Link.NO_TLS));
                links.tls(_ -> tls);
                awaitConnected(links, "ab");
                assertThat(b.peers()).hasSize(2);
            }
        }
    }

    /// A plain link stays plain once the octopus has a TLS context, a TLS link whose handshake fails shows the failure
    /// as its reason, and a closed octopus refuses a context.
    @Test
    void tlsContextLeavesPlainLinksPlainAndAFailedHandshakeIsTheReason(@TempDir final Path keys) throws Exception {
        final MachineKey local = MachineKey.loadOrCreate(keys.resolve("a"), "a");
        final MachineKey remote = MachineKey.loadOrCreate(keys.resolve("b"), "b");
        final Links closed;
        try (Gateway a = gateway(0); Gateway c = gateway(0); FakeGateway peer = new FakeGateway();
                Terminator b = new Terminator(Tls.context(remote, pins(local)), peer.port());
                Links links = new Links(a, "A", dir, LOG)) {
            closed = links;
            links.tls(_ -> Tls.context(local, new Pins()));
            links.put(new LinkSpec("ab", HOST, b.port(), Set.of("t/#"), Set.of(), 4, true));
            links.put(new LinkSpec("ac", HOST, c.port(), Set.of("t/#"), Set.of(), 4));
            awaitConnected(links, "ac");
            await().atMost(WAIT).until(() -> !b.failures().isEmpty());
            await().atMost(WAIT).until(() -> !state(links, "ab").reason().isEmpty()
                    && !state(links, "ab").reason().equals(Link.NO_TLS));
            assertThat(state(links, "ab").connected()).isFalse();
            assertThat(b.protocols()).isEmpty();
        }
        assertThatThrownBy(() -> closed.tls(_ -> Tls.context(local, new Pins())))
                .isInstanceOf(IllegalStateException.class);
    }

    /// Each TLS link dials with the context the octopus gives its id: a link to `b` whose context pins `b` alone fails
    /// the handshake with a peer that presents `c`'s certificate, `c` pinned in another link's context, which connects
    /// to that same peer; a link the octopus gives no context stays down with [Link#NO_TLS].
    @Test
    void eachTlsLinkTrustsOnlyTheKeyOfItsOwnContext(@TempDir final Path keys) throws Exception {
        final MachineKey local = MachineKey.loadOrCreate(keys.resolve("a"), "a");
        final MachineKey b = MachineKey.loadOrCreate(keys.resolve("b"), "b");
        final MachineKey c = MachineKey.loadOrCreate(keys.resolve("c"), "c");
        final Map<String, SSLContext> contexts = Map.of("ab", Tls.context(local, pins(b)),
                "ac", Tls.context(local, pins(c)));
        try (Gateway a = gateway(0); FakeGateway peer = new FakeGateway();
                Terminator asC = new Terminator(Tls.context(c, pins(local)), peer.port());
                Links links = new Links(a, "A", dir, LOG)) {
            links.tls(contexts::get);
            links.put(new LinkSpec("ab", HOST, asC.port(), Set.of("t/#"), Set.of(), 4, true));
            links.put(new LinkSpec("ac", HOST, asC.port(), Set.of("t/#"), Set.of(), 4, true));
            links.put(new LinkSpec("ax", HOST, asC.port(), Set.of("t/#"), Set.of(), 4, true));
            awaitConnected(links, "ac");
            await().atMost(WAIT).until(() -> !state(links, "ab").reason().isEmpty()
                    && !state(links, "ab").reason().equals(Link.NO_TLS));
            await().atMost(WAIT).until(() -> state(links, "ax").reason().equals(Link.NO_TLS));
            await().during(QUIET).atMost(WAIT).until(() -> !state(links, "ab").connected()
                    && !state(links, "ax").connected());
            assertThat(asC.peers()).containsOnly(local.fingerprint());
        }
    }

    /// The strand of link `id`, for a test that holds it busy.
    private static ScheduledThreadPoolExecutor strandOf(final Links links, final String id) throws Exception {
        final Field linksField = Links.class.getDeclaredField("links");
        linksField.setAccessible(true);
        final Link link = (Link) ((Map<?, ?>) linksField.get(links)).get(id);
        final Field strandField = Link.class.getDeclaredField("strand");
        strandField.setAccessible(true);
        return (ScheduledThreadPoolExecutor) strandField.get(link);
    }

    /// Holds the running thread until `release` opens or the wait runs out; records an interrupt.
    private static void hold(final CountDownLatch release, final AtomicBoolean interrupted) {
        try {
            release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException _) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
        }
    }

    /// How many messages wait for link `id`'s strand, as the queue readings report it.
    private static int waiting(final String id) {
        for (final Queues.Reading r : Queues.read()) {
            if (r.name().equals("link-" + id + "-deliveries")) {
                return r.depth();
            }
        }
        throw new AssertionError("no queue reading [link=" + id + ']');
    }

    private static List<Integer> payloads(final FakeGateway peer) {
        final List<Integer> payloads = new ArrayList<>();
        for (final Packet.Publish p : peer.received(Packet.Publish.class)) {
            payloads.add(payload(p));
        }
        return payloads;
    }

    private static Pins pins(final MachineKey peer) {
        final Pins pins = new Pins();
        pins.set(Map.of(peer.fingerprint(), peer.machine()));
        return pins;
    }

    private static Gateway gateway(final int port) throws IOException {
        return Gateway.start(GatewayOptions.defaults(new InetSocketAddress(HOST, port)));
    }

    private static MqttClient client(final Gateway gateway, final String clientId) throws Exception {
        final MqttClient client = new MqttClient(new MqttClient.Options(HOST, gateway.port(), clientId, 60,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), new Quiet(), callbacks);
        try {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final Exception e) {
            client.close();
            throw e;
        }
        return client;
    }

    private static int publish(final MqttClient client, final String topic, final int n) throws Exception {
        return client.publishAcknowledged(message(topic, n)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /// Publishes `count` messages numbered from `first` without waiting between them; their PUBACK codes in order.
    private static List<Integer> publishAll(final MqttClient client, final String topic, final int first,
            final int count) throws Exception {
        final List<CompletableFuture<Integer>> acks = new ArrayList<>();
        for (int i = first; i < first + count; i++) {
            acks.add(client.publishAcknowledged(message(topic, i)));
        }
        final List<Integer> codes = new ArrayList<>();
        for (final CompletableFuture<Integer> ack : acks) {
            codes.add(ack.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        }
        return codes;
    }

    private static Packet.Publish message(final String topic, final int n) {
        return new Packet.Publish(topic, bytes(n), 1, false, false, 0, null, null);
    }

    private static Packet.Publish expiring(final String topic, final long expirySeconds) {
        return new Packet.Publish(topic, bytes(0), 1, false, false, 0, null, null,
                new Packet.Meta(-1, expirySeconds, null, List.of()));
    }

    private static Packet.Publish sentTo(final FakeGateway peer, final int index) {
        return peer.received(Packet.Publish.class).get(index);
    }

    private static void awaitConnected(final Links links, final String id) {
        await().atMost(WAIT).until(() -> state(links, id).connected());
    }

    private static LinkState state(final Links links, final String id) {
        for (final LinkState s : links.state()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("no such link [link=" + id + ']');
    }

    private static List<Integer> range(final int from, final int to) {
        return IntStream.range(from, to).boxed().toList();
    }

    private static byte[] bytes(final int n) {
        return Integer.toString(n).getBytes(StandardCharsets.UTF_8);
    }

    private static int payload(final Packet.Publish m) {
        return Integer.parseInt(new String(m.payload(), StandardCharsets.UTF_8));
    }

    /// An internal subscriber on a gateway that records what reaches it and answers 0x00 at once, or, holding, leaves
    /// each answer to the test.
    private static final class Probe implements InternalSubscriber {

        private final List<Packet.Publish> messages = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<Integer>> answers = new CopyOnWriteArrayList<>();
        private final boolean holding;

        private Probe(final boolean holding) {
            this.holding = holding;
        }

        static Probe on(final Gateway gateway, final String filter) {
            final Probe probe = new Probe(false);
            gateway.subscribe("probe", filter, 1, false, probe);
            return probe;
        }

        static Probe holding(final Gateway gateway, final String filter) {
            final Probe probe = new Probe(true);
            gateway.subscribe("probe", filter, 1, false, probe);
            return probe;
        }

        @Override
        public CompletionStage<Integer> deliver(final Packet.Publish message, final String origin) {
            messages.add(message);
            if (!holding) {
                return CompletableFuture.completedFuture(Packet.SUCCESS);
            }
            final CompletableFuture<Integer> answer = new CompletableFuture<>();
            answers.add(answer);
            return answer;
        }

        List<Integer> payloads() {
            final List<Integer> payloads = new ArrayList<>();
            for (final Packet.Publish m : messages) {
                payloads.add(payload(m));
            }
            return payloads;
        }

        List<Integer> payloads(final String topic) {
            final List<Integer> payloads = new ArrayList<>();
            for (final Packet.Publish m : messages) {
                if (m.topic().equals(topic)) {
                    payloads.add(payload(m));
                }
            }
            return payloads;
        }

        long count(final String topic) {
            return payloads(topic).size();
        }
    }

    /// The peer's own queue, full for the first copy of message 0 (0x97) and taking every other at once.
    private static final class RefuseFirst implements InternalSubscriber {

        private final List<Integer> accepted = new CopyOnWriteArrayList<>();
        private final AtomicBoolean refused = new AtomicBoolean();

        @Override
        public CompletionStage<Integer> deliver(final Packet.Publish message, final String origin) {
            final int n = payload(message);
            if (n == 0 && refused.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(Packet.QUOTA_EXCEEDED);
            }
            accepted.add(n);
            return CompletableFuture.completedFuture(Packet.SUCCESS);
        }
    }

    /// A client handler that ignores what reaches it and does not reconnect.
    private static final class Quiet implements MqttClient.Handler {

        @Override
        public void connected() {
        }

        @Override
        public void message(final Packet.Publish message) {
        }

        @Override
        public Duration disconnected(final int attempts, final Throwable cause) {
            return null;
        }
    }

    /// A clock the test moves by hand, read by the links from their strands.
    private static final class ManualClock implements InstantSource {

        private final AtomicLong nowMillis;

        ManualClock(final long startMillis) {
            this.nowMillis = new AtomicLong(startMillis);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(nowMillis.get());
        }

        @Override
        public long millis() {
            return nowMillis.get();
        }

        void advance(final Duration by) {
            nowMillis.addAndGet(by.toMillis());
        }
    }
}
