package dev.marregui.comando.frontear.octopus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;

/// What a link writes on a message it carries: the hop count read and stamped, the queue time kept in the log only,
/// the expiry decremented by the time queued or the message skipped, and the reconnect backoff.
class ForwardingTest {

    private static final Packet.UserProperty APP = new Packet.UserProperty("app", "kept");

    /// A message with no count has made no hop; one decimal count is read; a repeated, empty, signed, non-decimal or
    /// overlong count is refused, so a peer cannot reset or overflow it.
    @Test
    void theHopCountIsReadOrRefused() {
        assertThat(Forwarding.hops(message(List.of(APP), -1))).isZero();
        assertThat(Forwarding.hops(message(List.of(hops("3")), -1))).isEqualTo(3);
        assertThat(Forwarding.hops(message(List.of(hops("999999999")), -1))).isEqualTo(999_999_999);
        for (final String bad : List.of("", "-1", "+1", "1a", " 1", "1000000000")) {
            assertThat(Forwarding.hops(message(List.of(hops(bad)), -1))).as(bad).isEqualTo(-1);
        }
        assertThat(Forwarding.hops(message(List.of(hops("1"), hops("1")), -1))).isEqualTo(-1);
    }

    /// The log keeps a message at QoS 1, not a duplicate, with one hop count and its queue time in place of any it
    /// carried, its own properties kept in order.
    @Test
    void aQueuedMessageCarriesOneHopCountAndItsQueueTime() {
        final Packet.UserProperty stale = new Packet.UserProperty(Forwarding.QUEUED, "5");
        final Packet.Publish in = new Packet.Publish("a/b", bytes("x"), 1, true, true, 9, null, null,
                new Packet.Meta(-1, 60, null, List.of(hops("1"), APP, stale)));
        final Packet.Publish queued = Forwarding.queued(in, 2, 1_000);
        assertThat(queued.qos()).isEqualTo(1);
        assertThat(queued.dup()).isFalse();
        assertThat(queued.retain()).isTrue();
        assertThat(queued.meta().messageExpirySeconds()).isEqualTo(60);
        assertThat(queued.meta().userProperties()).containsExactly(APP, hops("2"),
                new Packet.UserProperty(Forwarding.QUEUED, "1000"));
        final Packet.Publish atMostOnce = new Packet.Publish("a/b", bytes("x"), 0, false, false, 0, null, null);
        assertThat(Forwarding.queued(atMostOnce, 1, 1_000).qos()).isEqualTo(1);
        final Packet.Publish arrived = Forwarding.arrived(in, 4);
        assertThat(arrived.qos()).isEqualTo(1);
        assertThat(arrived.dup()).isFalse();
        assertThat(arrived.packetId()).isZero();
        assertThat(arrived.meta().userProperties()).containsExactly(APP, hops("4"));
    }

    /// A message sent from the log loses its queue time and has its expiry decremented by the whole seconds it waited
    /// (§3.3.2.3.3); one that waited its expiry or longer is skipped; one with no expiry is sent as it was.
    @Test
    void theExpiryIsDecrementedByTheTimeQueuedOrTheMessageSkipped() {
        final Packet.Publish queued = Forwarding.queued(message(List.of(APP), 10), 1, 1_000);
        final Packet.Publish soon = Forwarding.outgoing(queued, 1_000 + 3_999);
        assertThat(soon.meta().messageExpirySeconds()).isEqualTo(7);
        assertThat(soon.meta().userProperties()).containsExactly(APP, hops("1"));
        assertThat(Forwarding.outgoing(queued, 1_000 + 9_999).meta().messageExpirySeconds()).isEqualTo(1);
        assertThat(Forwarding.outgoing(queued, 1_000 + 10_000)).isNull();
        assertThat(Forwarding.outgoing(queued, 0).meta().messageExpirySeconds()).isEqualTo(10);
        final Packet.Publish forever = Forwarding.queued(message(List.of(), -1), 1, 1_000);
        assertThat(Forwarding.outgoing(forever, Long.MAX_VALUE).meta().messageExpirySeconds()).isEqualTo(-1);
    }

    /// The backoff starts at a second, doubles per failed attempt to thirty, and its jitter only shortens it, by at
    /// most a fifth.
    @Test
    void theBackoffDoublesFromOneSecondToThirtyWithJitterBelow() {
        assertThat(Forwarding.backoff(0, 0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(Forwarding.backoff(1, 0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(Forwarding.backoff(2, 0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(Forwarding.backoff(5, 0)).isEqualTo(Duration.ofSeconds(16));
        assertThat(Forwarding.backoff(6, 0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(Forwarding.backoff(Integer.MAX_VALUE, 0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(Forwarding.backoff(6, 0.999_999)).isGreaterThan(Duration.ofSeconds(24));
        assertThat(Forwarding.backoff(1, 0.5)).isEqualTo(Duration.ofMillis(900));
    }

    private static Packet.Publish message(final List<Packet.UserProperty> properties, final long expirySeconds) {
        return new Packet.Publish("a/b", bytes("x"), 1, false, false, 1, null, null,
                new Packet.Meta(-1, expirySeconds, null, properties));
    }

    private static Packet.UserProperty hops(final String value) {
        return new Packet.UserProperty(Forwarding.HOPS, value);
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
