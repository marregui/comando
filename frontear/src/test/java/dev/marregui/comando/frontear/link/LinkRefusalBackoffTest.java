package dev.marregui.comando.frontear.link;

import java.io.IOException;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.frontear.broker.Broker;
import dev.marregui.comando.frontear.broker.BrokerOptions;
import dev.marregui.comando.frontear.broker.InternalSubscriber;
import dev.marregui.comando.frontear.mqtt.Packet;
import dev.marregui.comando.frontear.queue.LogOptions;

import static org.awaitility.Awaitility.await;

/// A link's backoff after a busy peer grows with the refusal rounds in a row, not with how many messages one window
/// held: a window of eight refused together, refused once more on its first resend, is taken after two rounds' waits
/// (1 s, then 2 s, less jitter), where counting each message took it to the 30 s cap (the simulation soak found it).
class LinkRefusalBackoffTest {

    /// A 50 ms force interval makes the eight appends one round, so they leave in one window, refused together.
    private static final LogOptions LOG = new LogOptions(64 * 1024, 64 * 1024 * 1024, Duration.ofMillis(50));
    /// Two rounds' backoff is at most 3 s; the bound leaves room for a loaded machine and stays far below the cap.
    private static final Duration TWO_ROUNDS_AND_SLACK = Duration.ofSeconds(12);

    /// Eight messages refused 0x97 together and once more on their resend all reach the peer within two rounds' waits.
    @Test
    void aWindowRefusedTogetherBacksOffByRoundsNotByMessages(@TempDir final Path dir) throws Exception {
        final int k = 8;
        try (Broker a = broker(); Broker b = broker(); Links links = new Links(a, "A", dir, LOG)) {
            final RefuseFirst gate = new RefuseFirst(k + 1);
            b.subscribe("gate", "t/#", 1, false, gate);
            links.put(new LinkSpec("ab", "127.0.0.1", b.port(), Set.of("t/#"), Set.of(), 4));
            await().atMost(Duration.ofSeconds(10)).until(() -> links.state().getFirst().connected());
            final List<CompletableFuture<Integer>> acks = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                acks.add(a.publish(new Packet.Publish("t/x", ("m" + i).getBytes(StandardCharsets.US_ASCII), 1, false,
                        false, 0, null, null), "test"));
            }
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            await().atMost(TWO_ROUNDS_AND_SLACK).until(() -> gate.accepted.get() == k);
        }
    }

    private static Broker broker() throws IOException {
        return Broker.start(BrokerOptions.defaults(new InetSocketAddress("127.0.0.1", 0)));
    }

    /// Refuses the first `k` messages with 0x97 and takes the rest.
    private static final class RefuseFirst implements InternalSubscriber {

        private final int k;
        private final AtomicInteger seen = new AtomicInteger();
        private final AtomicInteger accepted = new AtomicInteger();

        private RefuseFirst(final int k) {
            this.k = k;
        }

        @Override
        public CompletionStage<Integer> deliver(final Packet.Publish message, final String origin) {
            if (seen.getAndIncrement() < k) {
                return CompletableFuture.completedFuture(Packet.QUOTA_EXCEEDED);
            }
            accepted.incrementAndGet();
            return CompletableFuture.completedFuture(Packet.SUCCESS);
        }
    }
}
