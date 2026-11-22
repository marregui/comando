package dev.marregui.comando.frontear.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;

/// The MQTT 5 clauses on a session that outlives its connection: the Session Expiry Interval a DISCONNECT carries, the
/// resend of unacknowledged messages within the new Receive Maximum and with their expiry lowered by the wait, expired
/// messages that hold no room in an offline queue; and a client's PUBLISH that carries a Subscription Identifier.
class GatewayProtocolTest {

    private TestGateway test;

    @BeforeEach
    void start() throws IOException {
        test = TestGateway.start();
    }

    @AfterEach
    void stop() {
        test.close();
    }

    /// §3.14.2.2.2: a DISCONNECT's Session Expiry Interval replaces the CONNECT's, and 0 ends the session with the
    /// connection.
    @Test
    void aDisconnectSessionExpiryOfZeroEndsTheSession() throws IOException {
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect("a", true, 60))) {
            assertThat(a.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
            // DISCONNECT 0x00, properties length 5, Session Expiry Interval 0
            a.sendHex("E0 07 00 05 11 00 00 00 00");
            a.untilClosed();
        }
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", false, 60))) {
            assertThat(a.read(Packet.ConnAck.class).sessionPresent()).isFalse();
        }
    }

    /// §3.14.2.2.2: a DISCONNECT's Session Expiry Interval of 600 s keeps the session past the CONNECT's 5 s.
    @Test
    void aDisconnectSessionExpiryExtendsTheSession() throws IOException {
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect("a", true, 5))) {
            assertThat(a.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
            // DISCONNECT 0x00, properties length 5, Session Expiry Interval 600
            a.sendHex("E0 07 00 05 11 00 00 02 58");
            a.untilClosed();
        }
        test.clock.advance(Duration.ofSeconds(10));
        test.sweepsDone();
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", false, 600))) {
            assertThat(a.read(Packet.ConnAck.class).sessionPresent()).isTrue();
        }
    }

    /// §3.14.2.2.2: a non-zero Session Expiry Interval in a DISCONNECT after a CONNECT with 0 is a Protocol Error.
    @Test
    void aDisconnectSessionExpiryAfterZeroIsAProtocolError() throws IOException {
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect("a", true, 0))) {
            // DISCONNECT 0x00, properties length 5, Session Expiry Interval 60
            a.sendHex("E0 07 00 05 11 00 00 00 3C");
            assertThat(a.untilClosed()).containsExactly(new Packet.Disconnect(Packet.PROTOCOL_ERROR, null));
        }
    }

    /// [MQTT-3.3.4-9], [MQTT-4.4.0-1]: unacknowledged messages are resent on a reconnect within the new Receive
    /// Maximum, each with its original packet id and DUP set; an acknowledgement frees a slot for the next.
    @Test
    void theResendOnAReconnectStaysWithinTheNewReceiveMaximum() throws IOException {
        final List<Integer> ids;
        try (RawClient first = RawClient.connected(test.port(), new Packet.Connect("a", true, 0, 60, 8,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null))) {
            assertThat(first.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
            try (RawClient p = RawClient.connected(test.port(), "p")) {
                for (int i = 0; i < 4; i++) {
                    assertThat(p.publish(RawClient.qos1("t", Integer.toString(i)))).isEqualTo(Packet.SUCCESS);
                }
            }
            ids = first.ping().stream().map(m -> ((Packet.Publish) m).packetId()).toList();
            assertThat(ids).hasSize(4);
        }
        try (RawClient a = RawClient.open(test.port(), new Packet.Connect("a", false, 0, 60, 2,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null))) {
            assertThat(a.read(Packet.ConnAck.class).sessionPresent()).isTrue();
            final List<Packet> resent = a.ping();
            assertThat(resent).hasSize(2);
            final Packet.Publish one = (Packet.Publish) resent.getFirst();
            assertThat(one.dup()).isTrue();
            assertThat(ids).contains(one.packetId());
            a.send(new Packet.PubAck(one.packetId(), Packet.SUCCESS, null));
            final List<Packet> next = a.ping();
            assertThat(next).hasSize(1);
            assertThat(((Packet.Publish) next.getFirst()).dup()).isTrue();
        }
    }

    /// [MQTT-3.3.2-6]: a message resent after time offline carries its expiry lowered by all the time it waited.
    @Test
    void aResentMessageCarriesItsExpiryLoweredByTheWait() throws IOException {
        try (RawClient first = RawClient.connected(test.port(), RawClient.connect("a", true, 600))) {
            assertThat(first.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
            try (RawClient p = RawClient.connected(test.port(), "p")) {
                assertThat(p.publish(new Packet.Publish("t", new byte[] {1}, 1, false, false, 1, null, null,
                        new Packet.Meta(-1, 100, null, List.of())))).isEqualTo(Packet.SUCCESS);
            }
            assertThat(first.read(Packet.Publish.class).meta().messageExpirySeconds()).isEqualTo(100);
        }
        test.clock.advance(Duration.ofSeconds(30));
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", false, 600))) {
            a.read(Packet.ConnAck.class);
            final Packet.Publish resent = a.read(Packet.Publish.class);
            assertThat(resent.dup()).isTrue();
            assertThat(resent.meta().messageExpirySeconds()).isEqualTo(70);
        }
    }

    /// [MQTT-3.3.2-5]: expired copies are deleted, so they hold no room in an offline queue against a live one.
    @Test
    void expiredQueuedMessagesDoNotCrowdOutALiveOne() throws IOException {
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect("a", true, 600))) {
            assertThat(a.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
            a.send(new Packet.Disconnect(Packet.SUCCESS, null));
            a.untilClosed();
        }
        try (RawClient p = RawClient.connected(test.port(), "p")) {
            for (int i = 0; i < TestGateway.MAX_QUEUED; i++) {
                assertThat(p.publish(new Packet.Publish("t", new byte[] {1}, 1, false, false, 1, null, null,
                        new Packet.Meta(-1, 1, null, List.of())))).isEqualTo(Packet.SUCCESS);
            }
            test.clock.advance(Duration.ofSeconds(10));
            assertThat(p.publish(RawClient.qos1("t", "live"))).isEqualTo(Packet.SUCCESS);
        }
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", false, 600))) {
            a.read(Packet.ConnAck.class);
            final List<Packet> queued = a.ping();
            assertThat(queued).hasSize(1);
            assertThat(new String(((Packet.Publish) queued.getFirst()).payload(), StandardCharsets.UTF_8))
                    .isEqualTo("live");
        }
        assertThat(test.gateway.stats().droppedFull()).isZero();
    }

    /// [MQTT-3.3.4-6]: a Subscription Identifier in a client's PUBLISH is a Protocol Error.
    @Test
    void aSubscriptionIdentifierInAClientPublishIsAProtocolError() throws IOException {
        try (RawClient p = RawClient.connected(test.port(), "p")) {
            // PUBLISH QoS 1 to t, packet id 1, properties length 2: Subscription Identifier 1; payload "m"
            p.sendHex("32 09 00 01 74 00 01 02 0B 01 6D");
            assertThat(p.untilClosed()).containsExactly(new Packet.Disconnect(Packet.PROTOCOL_ERROR, null));
        }
    }
}
