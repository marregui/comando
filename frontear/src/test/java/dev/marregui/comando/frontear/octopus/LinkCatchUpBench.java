package dev.marregui.comando.frontear.octopus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.frontear.gateway.Gateway;
import dev.marregui.comando.frontear.gateway.GatewayOptions;
import dev.marregui.comando.frontear.gateway.InternalSubscriber;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.protocol.PublishRecord;
import dev.marregui.comando.frontear.store.LinkLog;
import dev.marregui.comando.frontear.store.LogOptions;

import static org.assertj.core.api.Assertions.assertThat;

/// How fast a link that fell behind catches up: a link's log is preloaded with N durable records while the link is
/// down, then the link to a second gateway starts and drains the backlog to a subscriber there that answers at once.
/// The rate is the messages after the first over the time from the first arrival to the last, so the connect is not
/// counted. Small segments put most of the backlog in sealed segments; Frontear's 64 MiB ones keep it in the active
/// segment. Runs only with `-Dfrontear.bench=true`.
@EnabledIfSystemProperty(named = "frontear.bench", matches = "true")
class LinkCatchUpBench {

    // how long one preload append may wait for its force
    private static final long APPEND_SECONDS = 30;
    // Frontear's own log options
    private static final LogOptions FRONTEAR_SEGMENTS = new LogOptions(64L << 20, 1L << 30, Duration.ZERO);
    private static final String HOST = "127.0.0.1";
    // appends in flight while preloading, so the forcer covers many with one force
    private static final int PRELOAD_BATCH = 256;
    private static final LogOptions SMALL_SEGMENTS = new LogOptions(1L << 20, 1L << 30, Duration.ZERO);
    private static final String TOPIC = "t/x";
    // how long the drain of one backlog may take: far above the slowest rate seen, 13k msg/s
    private static final long WAIT_SECONDS = 120;

    @TempDir
    Path dir;

    /// The catch-up rate of a 128-byte and a 4 KiB backlog, in 1 MiB and in 64 MiB segments.
    @Test
    void catchUpDrainRate() throws Exception {
        drain("small-128", SMALL_SEGMENTS, 128, 100_000);
        drain("small-4096", SMALL_SEGMENTS, 4096, 25_000);
        drain("frontear-128", FRONTEAR_SEGMENTS, 128, 100_000);
        drain("frontear-4096", FRONTEAR_SEGMENTS, 4096, 25_000);
    }

    private static Gateway gateway() throws IOException {
        return Gateway.start(GatewayOptions.defaults(new InetSocketAddress(HOST, 0)));
    }

    private void drain(final String name, final LogOptions options, final int payloadBytes, final int count)
            throws Exception {
        final Path dataDir = dir.resolve(name);
        final long preloadStart = System.nanoTime();
        try (LinkLog log = LinkLog.open(dataDir.resolve("ab"), options)) {
            final byte[] record = PublishRecord.encode(Forwarding.queued(
                    new Packet.Publish(TOPIC, new byte[payloadBytes], 1, false, false, 0, null, null), 1,
                    System.currentTimeMillis()));
            for (int done = 0; done < count; done += PRELOAD_BATCH) {
                CompletableFuture<Long> last = null;
                for (int i = 0; i < PRELOAD_BATCH && done + i < count; i++) {
                    last = log.append(record);
                }
                last.get(APPEND_SECONDS, TimeUnit.SECONDS);
            }
            assertThat(log.tail()).isEqualTo(count);
        }
        final long preloadNanos = System.nanoTime() - preloadStart;
        try (Gateway a = gateway(); Gateway b = gateway(); Links links = new Links(a, "A", dataDir, options)) {
            final Counter counter = new Counter(count);
            b.subscribe("bench", TOPIC, 1, false, counter);
            links.put(new LinkSpec("ab", HOST, b.port(), Set.of("t/#"), Set.of(), 4));
            assertThat(counter.all.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final long drainNanos = counter.lastNanos - counter.firstNanos;
            System.out.println(String.format(Locale.ROOT, "link catch-up: case=%s payload=%dB n=%d msg/s=%.0f"
                    + " drain=%.2f s preload=%.2f s", name, payloadBytes, count, (count - 1) * 1e9 / drainNanos,
                    drainNanos / 1e9, preloadNanos / 1e9));
        }
    }

    /// An internal subscriber that answers 0x00 at once and records when the first and the last expected message
    /// arrived.
    private static final class Counter implements InternalSubscriber {

        private final CountDownLatch all = new CountDownLatch(1);
        private final int expected;
        private final AtomicInteger seen = new AtomicInteger();
        // written by the delivering thread before the latch opens, read by the test after it
        private volatile long firstNanos;
        private volatile long lastNanos;

        private Counter(final int expected) {
            this.expected = expected;
        }

        @Override
        public CompletionStage<Integer> deliver(final Packet.Publish message, final String origin) {
            final int n = seen.incrementAndGet();
            if (n == 1) {
                firstNanos = System.nanoTime();
            }
            if (n == expected) {
                lastNanos = System.nanoTime();
                all.countDown();
            }
            return CompletableFuture.completedFuture(Packet.SUCCESS);
        }
    }
}
