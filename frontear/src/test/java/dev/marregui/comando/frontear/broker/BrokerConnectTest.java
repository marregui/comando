package dev.marregui.comando.frontear.broker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.mqtt.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// CONNECT and the session over TCP: what CONNACK states and declines, the client ids and connects the broker
/// refuses with their reason codes, keep alive, ping, takeover, Clean Start, and sessions that outlive their
/// connection with a bounded queue of QoS 1 messages until their expiry.
class BrokerConnectTest {

    private TestBroker test;

    @BeforeEach
    void start() throws IOException {
        test = TestBroker.start();
    }

    @AfterEach
    void stop() {
        test.close();
    }

    /// CONNACK states the broker's Receive Maximum and Maximum Packet Size, no topic alias, and the capabilities it
    /// declines: Maximum QoS 1, no subscription identifiers, no shared subscriptions.
    @Test
    void connAckStatesTheLimitsAndTheDeclinedCapabilities() throws IOException {
        try (RawClient c = RawClient.open(test.port(), RawClient.connect("a", true, 0))) {
            final Packet.ConnAck a = c.read(Packet.ConnAck.class);
            assertThat(a.reasonCode()).isEqualTo(Packet.SUCCESS);
            assertThat(a.sessionPresent()).isFalse();
            assertThat(a.receiveMaximum()).isEqualTo(TestBroker.RECEIVE_MAXIMUM);
            assertThat(a.maximumPacketSize()).isEqualTo(TestBroker.MAXIMUM_PACKET_SIZE);
            assertThat(a.topicAliasMaximum()).isZero();
            assertThat(a.assignedClientId()).isNull();
            assertThat(a.capabilities()).isEqualTo(new Packet.Capabilities(1, true, true, false, false));
        }
    }

    /// An empty client id gets one assigned, stated in CONNACK (§3.1.3.1).
    @Test
    void anEmptyClientIdIsAssignedOne() throws IOException {
        try (RawClient c = RawClient.open(test.port(), RawClient.connect("", true, 0))) {
            final Packet.ConnAck a = c.read(Packet.ConnAck.class);
            assertThat(a.reasonCode()).isEqualTo(Packet.SUCCESS);
            assertThat(a.assignedClientId()).startsWith("frontear-");
        }
    }

    /// A client id that starts with `$`, or that cannot end a presence topic (a wildcard in it), is refused with
    /// 0x85 and the connection closed.
    @Test
    void aClientIdTheBrokerCannotNameIsRefused() throws IOException {
        for (final String id : List.of("$link/x", "a/+", "a/#")) {
            try (RawClient c = RawClient.open(test.port(), RawClient.connect(id, true, 0))) {
                assertThat(c.read(Packet.ConnAck.class).reasonCode()).as(id).isEqualTo(Packet.CLIENT_ID_NOT_VALID);
                assertThat(c.untilClosed()).isEmpty();
            }
        }
    }

    /// Each feature of CONNECT the broker declines gets its CONNACK reason code, then the connection closes: a user
    /// name or password 0x86, an authentication method 0x8C, a protocol level other than 5 0x84.
    @Test
    void declinedConnectFeaturesAreRefusedWithTheirCodes() throws IOException {
        // flags 0xC2: user name, password, clean start; refused on the flags, before the payload is read
        refusedWith("10 12 00 04 4D 51 54 54 05 C2 00 0A 05 11 00 00 00 0A 00 00",
                Packet.BAD_USER_NAME_OR_PASSWORD);
        // property 0x15 Authentication Method "SCRAM"
        refusedWith("10 15 00 04 4D 51 54 54 05 02 00 0A 08 15 00 05 53 43 52 41 4D 00 00",
                Packet.BAD_AUTHENTICATION_METHOD);
        // protocol level 4, MQTT 3.1.1
        refusedWith("10 0D 00 04 4D 51 54 54 04 02 00 0A 00 00 00", Packet.UNSUPPORTED_PROTOCOL_VERSION);
    }

    /// A will at QoS 2 is refused with 0x9B, as the broker's Maximum QoS is 1 (§3.2.2.3.4).
    @Test
    void aWillAtQos2IsRefused() throws IOException {
        final Packet.Publish will = new Packet.Publish("w", new byte[] {1}, 2, false, false, 0, null, null);
        try (RawClient c = RawClient.open(test.port(), new Packet.Connect("a", true, 0, 0, 10, 1_000, will))) {
            assertThat(c.read(Packet.ConnAck.class).reasonCode()).isEqualTo(Packet.QOS_NOT_SUPPORTED);
            assertThat(c.untilClosed()).isEmpty();
        }
    }

    /// A will on a topic the gate refuses is refused with 0x87 before the session is made.
    @Test
    void aWillOnAReservedTopicIsRefused() throws IOException {
        final Packet.Publish will = new Packet.Publish("$frontear/presence/a", new byte[0], 0, true, false, 0, null,
                null);
        try (RawClient c = RawClient.open(test.port(), new Packet.Connect("a", true, 0, 0, 10, 1_000, will))) {
            assertThat(c.read(Packet.ConnAck.class).reasonCode()).isEqualTo(Packet.NOT_AUTHORIZED);
        }
        assertThat(test.broker.stats().sessions()).isZero();
    }

    /// Past its session limit the broker refuses a new client with 0x97 Quota Exceeded; a client whose session it
    /// holds still connects.
    @Test
    void sessionsPastTheLimitAreRefused() throws IOException {
        test.close();
        test = TestBroker.start(2);
        try (RawClient a = RawClient.connected(test.port(), "a");
                RawClient b = RawClient.connected(test.port(), "b");
                RawClient c = RawClient.open(test.port(), RawClient.connect("c", true, 0))) {
            assertThat(c.read(Packet.ConnAck.class).reasonCode()).isEqualTo(Packet.QUOTA_EXCEEDED);
            assertThat(c.untilClosed()).isEmpty();
            try (RawClient again = RawClient.open(test.port(), RawClient.connect("a", false, 0))) {
                assertThat(again.read(Packet.ConnAck.class).reasonCode()).isEqualTo(Packet.SUCCESS);
            }
            assertThat(b.ping()).isEmpty();
            assertThat(a.untilClosed()).containsExactly(new Packet.Disconnect(Packet.SESSION_TAKEN_OVER, null));
        }
    }

    /// A first packet other than CONNECT closes the connection unanswered (§3.1.0).
    @Test
    void aFirstPacketOtherThanConnectClosesTheConnection() throws IOException {
        try (RawClient c = RawClient.open(test.port())) {
            c.send(new Packet.PingReq());
            assertThat(c.untilClosed()).isEmpty();
        }
    }

    /// A second CONNECT on a connection is a protocol error: DISCONNECT 0x82, then the connection closes.
    @Test
    void aSecondConnectIsAProtocolError() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            c.send(RawClient.connect("a", true, 0));
            assertThat(c.untilClosed()).containsExactly(new Packet.Disconnect(Packet.PROTOCOL_ERROR, null));
        }
    }

    /// PINGREQ is answered with PINGRESP, and a client that pings within its keep alive stays connected.
    @Test
    void pingIsAnswered() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), new Packet.Connect("a", true, 1, 0, 10, 1_000, null))) {
            for (int i = 0; i < 3; i++) {
                assertThat(c.ping()).isEmpty();
            }
        }
    }

    /// A client silent for one and a half times its keep alive is sent DISCONNECT 0x8D and closed (§3.1.2.10).
    @Test
    void keepAliveClosesASilentClient() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), new Packet.Connect("a", true, 1, 0, 10, 1_000, null))) {
            final long start = System.nanoTime();
            assertThat(c.untilClosed()).containsExactly(new Packet.Disconnect(Packet.KEEP_ALIVE_TIMEOUT, null));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(1_000));
        }
    }

    /// A second connection with the same client id takes the session over: the first gets DISCONNECT 0x8E and is
    /// closed, the second gets Session Present and the session's subscriptions.
    @Test
    void aNewConnectionTakesTheSessionOver() throws IOException {
        try (RawClient first = RawClient.connected(test.port(), RawClient.connect("a", true, 60));
                RawClient publisher = RawClient.connected(test.port(), "p")) {
            assertThat(first.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
            try (RawClient second = RawClient.open(test.port(), RawClient.connect("a", false, 60))) {
                assertThat(second.read(Packet.ConnAck.class).sessionPresent()).isTrue();
                assertThat(first.untilClosed()).containsExactly(new Packet.Disconnect(Packet.SESSION_TAKEN_OVER, null));
                assertThat(publisher.publish(RawClient.qos1("t", "m"))).isEqualTo(Packet.SUCCESS);
                assertThat(second.receive().topic()).isEqualTo("t");
            }
        }
    }

    /// Clean Start discards the session the client had: no Session Present, no subscriptions, so a publish to its
    /// old topic matches nobody (PUBACK 0x10).
    @Test
    void cleanStartDiscardsTheSession() throws IOException {
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect("a", true, 60))) {
            a.subscribe(new Packet.Subscription("t", 1));
            a.send(new Packet.Disconnect(Packet.SUCCESS, null));
            a.untilClosed();
        }
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", true, 60));
                RawClient p = RawClient.connected(test.port(), "p")) {
            assertThat(a.read(Packet.ConnAck.class).sessionPresent()).isFalse();
            assertThat(p.publish(RawClient.qos1("t", "m"))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
        }
    }

    /// An offline session keeps its subscriptions and queues the QoS 1 messages they match, in order, for its
    /// client's return; QoS 0 messages are not queued (§3.1.2.4).
    @Test
    void anOfflineSessionQueuesQos1AndDeliversItOnReturn() throws IOException {
        offlineSubscriber("a");
        try (RawClient p = RawClient.connected(test.port(), "p")) {
            assertThat(p.publish(RawClient.qos1("t", "1"))).isEqualTo(Packet.SUCCESS);
            p.send(new Packet.Publish("t", new byte[] {0}, 0, false, false, 0, null, null));
            assertThat(p.publish(RawClient.qos1("t", "2"))).isEqualTo(Packet.SUCCESS);
        }
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", false, 60))) {
            assertThat(a.read(Packet.ConnAck.class).sessionPresent()).isTrue();
            assertThat(new String(a.receive().payload(), StandardCharsets.UTF_8)).isEqualTo("1");
            assertThat(new String(a.receive().payload(), StandardCharsets.UTF_8)).isEqualTo("2");
            assertThat(a.ping()).isEmpty();
        }
    }

    /// An offline session's queue is bounded: past it the newest message is dropped and counted.
    @Test
    void aFullQueueDropsTheNewestAndCountsIt() throws IOException {
        offlineSubscriber("a");
        try (RawClient p = RawClient.connected(test.port(), "p")) {
            for (int i = 0; i < TestBroker.MAX_QUEUED + 2; i++) {
                assertThat(p.publish(RawClient.qos1("t", Integer.toString(i)))).isEqualTo(Packet.SUCCESS);
            }
        }
        assertThat(test.broker.stats().droppedFull()).isEqualTo(2);
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", false, 60))) {
            a.read(Packet.ConnAck.class);
            for (int i = 0; i < TestBroker.MAX_QUEUED; i++) {
                assertThat(a.receive().payload()).isEqualTo(Integer.toString(i).getBytes(StandardCharsets.UTF_8));
            }
            assertThat(a.ping()).isEmpty();
        }
    }

    /// A session past its expiry ends: it is gone from the broker, and its client returns to no session.
    @Test
    void aSessionPastItsExpiryEnds() throws IOException {
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect("a", true, 5))) {
            a.send(new Packet.Disconnect(Packet.SUCCESS, null));
            a.untilClosed();
        }
        test.clock.advance(Duration.ofSeconds(4));
        assertThat(test.broker.stats().sessions()).isEqualTo(1);
        test.clock.advance(Duration.ofSeconds(1));
        await().atMost(TestBroker.WAIT).until(() -> test.broker.stats().sessions() == 0);
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", false, 5))) {
            assertThat(a.read(Packet.ConnAck.class).sessionPresent()).isFalse();
        }
    }

    /// A session expiry of 0 ends the session with its connection.
    @Test
    void sessionExpiryZeroEndsTheSessionWithTheConnection() throws IOException {
        try (RawClient a = RawClient.connected(test.port(), "a")) {
            assertThat(test.broker.stats().sessions()).isEqualTo(1);
            a.send(new Packet.Disconnect(Packet.SUCCESS, null));
            a.untilClosed();
        }
        await().atMost(TestBroker.WAIT).until(() -> test.broker.stats().sessions() == 0);
    }

    /// A client `id` with a 60 s session subscribed to `t` at QoS 1, then disconnected.
    private void offlineSubscriber(final String id) throws IOException {
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect(id, true, 60))) {
            assertThat(a.subscribe(new Packet.Subscription("t", 1))).containsExactly(1);
            a.send(new Packet.Disconnect(Packet.SUCCESS, null));
            a.untilClosed();
        }
    }

    /// Sends the CONNECT bytes `connect` spells and expects a CONNACK with `code`, then the connection closed.
    private void refusedWith(final String connect, final int code) throws IOException {
        try (RawClient c = RawClient.open(test.port())) {
            c.sendHex(connect);
            assertThat(c.read(Packet.ConnAck.class).reasonCode()).isEqualTo(code);
            assertThat(c.untilClosed()).isEmpty();
        }
    }
}
