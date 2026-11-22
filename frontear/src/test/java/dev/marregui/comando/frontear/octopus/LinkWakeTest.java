package dev.marregui.comando.frontear.octopus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.frontear.gateway.Gateway;
import dev.marregui.comando.frontear.gateway.GatewayOptions;
import dev.marregui.comando.frontear.gateway.InternalSubscriber;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.store.LogOptions;
import dev.marregui.comando.frontear.watch.LinkStep;
import dev.marregui.comando.frontear.watch.Observation;
import dev.marregui.comando.frontear.watch.Watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// A link whose peer node comes back does not wait out a backoff grown while the peer was down: the peer's own link
/// connecting to this node wakes it, and a node that runs but holds its links (0x88) is tried again by the first
/// backoff. A live run showed the cost: the peer's link up at T, this node's link to it only at T + 11.8 s.
class LinkWakeTest {

    /// A 50 ms force interval: the queued messages are durable well within the waits below.
    private static final LogOptions LOG = new LogOptions(64 * 1024, 64 * 1024 * 1024, Duration.ofMillis(50));
    /// Ring slots for every step of a test: a few dozen connection changes and messages.
    private static final int WATCH_SLOTS = 1024;
    /// Messages queued on the link while its peer is down.
    private static final int QUEUED = 5;
    /// After the connection lost and five refused connects (1 + 1 + 2 + 4 + 8 s of backoff, less jitter), the next
    /// wait is 16 s less a fifth, at least 12.8 s, far past the 1 s the wake must beat.
    private static final int DOWN_STEPS = 6;
    /// The five backoffs above sum to at most 16 s; the bound leaves room for a loaded machine.
    private static final Duration BACKED_OFF_WITHIN = Duration.ofSeconds(40);
    /// What the wake must achieve: this node's link up within a second of the peer's link reaching this node.
    private static final long WAKE_WITHIN_NANOS = TimeUnit.SECONDS.toNanos(1);
    /// Four refusals 0x88 by the first backoff take at most 4 s; grown, they would take 1 + 1 + 2 + 4 = 8 s less
    /// jitter, at least 6.4 s, so the bound below the latter tells the two apart.
    private static final int HELD_REFUSALS = 4;
    private static final Duration HELD_REFUSALS_WITHIN = Duration.ofMillis(6_000);
    /// Once the held node lets links in, the next connect comes within the first backoff, 1 s, and its slack.
    private static final Duration LET_IN_WITHIN = Duration.ofMillis(2_500);
    private static final Duration WAIT = Duration.ofSeconds(10);

    /// Node b stops while a's link to it is up; a queues five messages for b and backs off through five refused
    /// connects; b comes back on the same port with its link to a: a's link connects within a second of b's link
    /// reaching a, and the queued messages reach b.
    @Test
    void aLinkBackedOffReconnectsWithinASecondOfItsPeersLinkReachingThisNode(@TempDir final Path dir)
            throws Exception {
        final Watch watchA = new Watch(WATCH_SLOTS);
        watchA.on(true);
        final AtomicLong peerLinkAtNanos = new AtomicLong();
        try (Gateway a = Gateway.start(options(0), watchA, "a");
                Links linksA = new Links(a, "a", dir.resolve("a"), LOG, watchA)) {
            a.addConnectListener(id -> {
                if ("b~a".equals(id)) {
                    peerLinkAtNanos.compareAndSet(0L, System.nanoTime());
                }
            });
            final int portB;
            try (Gateway b = Gateway.start(options(0), new Watch(WATCH_SLOTS), "b")) {
                portB = b.port();
                linksA.put(new LinkSpec("b", "127.0.0.1", portB, Set.of("t/#"), Set.of(), 4));
                await().atMost(WAIT).until(() -> connected(linksA, "b"));
            }
            await().atMost(BACKED_OFF_WITHIN).until(() -> downs(watchA, "b") >= DOWN_STEPS);
            final List<CompletableFuture<Integer>> queued = new ArrayList<>();
            for (int i = 0; i < QUEUED; i++) {
                queued.add(a.publish(new Packet.Publish("t/x", ("m" + i).getBytes(StandardCharsets.US_ASCII), 1,
                        false, false, 0, null, null), "test"));
            }
            assertThat(CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS))
                    .isNull();
            assertThat(connected(linksA, "b")).isFalse();
            final Counter arrived = new Counter();
            try (Gateway b = Gateway.start(options(portB), new Watch(WATCH_SLOTS), "b")) {
                b.subscribe("probe", "t/#", 1, false, arrived);
                try (Links linksB = new Links(b, "b", dir.resolve("b"), LOG)) {
                    linksB.put(new LinkSpec("a", "127.0.0.1", a.port(), Set.of(), Set.of(), 4));
                    await().atMost(WAIT).pollInterval(Duration.ofMillis(5)).until(() -> connected(linksA, "b"));
                    final long upAtNanos = System.nanoTime();
                    assertThat(peerLinkAtNanos.get()).isNotZero();
                    assertThat(upAtNanos - peerLinkAtNanos.get()).isLessThan(WAKE_WITHIN_NANOS);
                    await().atMost(WAIT).until(() -> arrived.count.get() >= QUEUED);
                }
            }
        }
    }

    /// A node that runs and holds its links refuses a's link 0x88 four times within 6 s, each tried again by the
    /// first backoff rather than one that grows; once it lets links in, a's link connects within the first backoff.
    @Test
    void aLinkHeldByARunningNodeIsTriedAgainByTheFirstBackoff(@TempDir final Path dir) throws Exception {
        final Watch watchA = new Watch(WATCH_SLOTS);
        watchA.on(true);
        try (Gateway b = Gateway.start(options(0), new Watch(WATCH_SLOTS), "b", true);
                Gateway a = Gateway.start(options(0), watchA, "a");
                Links linksA = new Links(a, "a", dir, LOG, watchA)) {
            linksA.put(new LinkSpec("b", "127.0.0.1", b.port(), Set.of("t/#"), Set.of(), 4));
            await().atMost(HELD_REFUSALS_WITHIN).until(() -> downs(watchA, "b") >= HELD_REFUSALS);
            assertThat(connected(linksA, "b")).isFalse();
            b.openLinks();
            await().atMost(LET_IN_WITHIN).pollInterval(Duration.ofMillis(5)).until(() -> connected(linksA, "b"));
        }
    }

    private static GatewayOptions options(final int port) {
        return GatewayOptions.defaults(new InetSocketAddress("127.0.0.1", port));
    }

    private static boolean connected(final Links links, final String id) {
        return links.state().stream().anyMatch(s -> s.id().equals(id) && s.connected());
    }

    /// The connection ends and refusals link `id` reported to `watch`.
    private static long downs(final Watch watch, final String id) {
        final List<Observation> events = watch.since(0, WATCH_SLOTS).events();
        return events.stream().filter(e -> e instanceof final LinkStep s && s.link().equals(id)
                && s.kind() == LinkStep.Kind.DISCONNECTED).count();
    }

    /// Takes every message and counts it.
    private static final class Counter implements InternalSubscriber {

        private final AtomicInteger count = new AtomicInteger();

        @Override
        public CompletionStage<Integer> deliver(final Packet.Publish message, final String origin) {
            count.incrementAndGet();
            return CompletableFuture.completedFuture(Packet.SUCCESS);
        }
    }
}
