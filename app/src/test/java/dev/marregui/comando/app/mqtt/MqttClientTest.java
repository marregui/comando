package dev.marregui.comando.app.mqtt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// The client against [FakeBroker] on a real socket: connect, subscriptions, publishes both ways at QoS 0 and 1, the
/// broker's Receive Maximum, keep alive and its timeout, a broker that drops, refuses or disconnects, a malformed or
/// too large packet in, a connect reason code outside the table, and the client's own disconnect.
class MqttClientTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

    private static FakeBroker broker;
    private static ExecutorService callbacks;

    private final Recorder recorder = new Recorder();

    @BeforeAll
    static void startBroker() {
        broker = new FakeBroker();
        callbacks = Threads.strand("mqtt-test-callbacks");
    }

    @AfterAll
    static void stopBroker() {
        try {
            if (broker != null) {
                broker.close();
            }
        } finally {
            if (callbacks != null) {
                ExecutorStop.now("mqtt-test", callbacks);
            }
        }
    }

    @BeforeEach
    void resetBroker() {
        broker.reset();
    }

    /// The CONNECT carries the options: client id, a clean start whose session ends with the connection, the keep
    /// alive, the largest packet the client reads and the will; once the broker accepts it the handler hears of it.
    @Test
    void connectCarriesTheOptions() throws Exception {
        final Packet.Publish will = new Packet.Publish("peers/a/presence", bytes("gone"), 1, true, false, 0, null,
                null);
        try (MqttClient client = client(30, 1_048_576, will)) {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(client.connected()).isTrue();
            assertThat(broker.received(Packet.Connect.class)).containsExactly(new Packet.Connect("test-client", true,
                    30, 0, Packet.DEFAULT_RECEIVE_MAXIMUM, 1_048_576, will));
            await().atMost(WAIT).until(() -> recorder.connects.get() == 1);
            assertThat(client.connect()).isSameAs(client.connect());
        }
    }

    /// Messages in reach the handler in the order the broker sent them, QoS 0 and 1 mixed, and each QoS 1 one is
    /// acknowledged with its own packet id.
    @Test
    void messagesInReachTheHandlerInOrderAndQos1OnesAreAcknowledged() throws Exception {
        try (MqttClient client = connected()) {
            client.subscribe("a/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(broker.received(Packet.Subscribe.class).getFirst().subscriptions())
                    .containsExactly(new Packet.Subscription("a/#", 1));
            for (int i = 1; i <= 5; i++) {
                broker.send(new Packet.Publish("a/" + i, bytes("m" + i), i == 3 ? 0 : 1, false, false, 0, "a/reply",
                        bytes("c" + i)));
            }
            await().atMost(WAIT).until(() -> recorder.messages.size() == 5);
            assertThat(recorder.messages).extracting(p -> new String(p.payload(), StandardCharsets.UTF_8))
                    .containsExactly("m1", "m2", "m3", "m4", "m5");
            assertThat(recorder.messages.getFirst().responseTopic()).isEqualTo("a/reply");
            assertThat(recorder.messages.getFirst().correlation()).isEqualTo(bytes("c1"));
            await().atMost(WAIT).until(() -> broker.received(Packet.PubAck.class).size() == 4);
            assertThat(broker.received(Packet.PubAck.class)).extracting(Packet.PubAck::packetId)
                    .containsExactly(1, 2, 3, 4);
            assertThat(broker.received(Packet.PubAck.class)).extracting(Packet.PubAck::reasonCode)
                    .containsOnly(Packet.SUCCESS);
        }
    }

    /// A QoS 1 publish completes when its PUBACK arrives, not before, and fails with the broker's reason code when the
    /// PUBACK refuses it.
    @Test
    void aQos1PublishCompletesOnItsAcknowledgement() throws Exception {
        broker.ackPublishes(false);
        try (MqttClient client = connected()) {
            final CompletableFuture<Void> sent = client.publish(publish("b/1", 1));
            await().atMost(WAIT).until(() -> broker.received(Packet.Publish.class).size() == 1);
            final Packet.Publish out = broker.received(Packet.Publish.class).getFirst();
            assertThat(out.qos()).isEqualTo(1);
            assertThat(out.packetId()).isPositive();
            assertThat(sent).isNotDone();
            broker.send(new Packet.PubAck(out.packetId(), Packet.SUCCESS, null));
            sent.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

            final CompletableFuture<Void> refused = client.publish(publish("b/2", 1));
            await().atMost(WAIT).until(() -> broker.received(Packet.Publish.class).size() == 2);
            broker.send(new Packet.PubAck(broker.received(Packet.Publish.class).get(1).packetId(), 0x87,
                    "not allowed"));
            assertFailsWith(refused, 0x87);
            assertThat(client.connected()).isTrue();
        }
    }

    /// A QoS 0 publish completes once written and goes out without a packet id.
    @Test
    void aQos0PublishCompletesOnceWritten() throws Exception {
        broker.ackPublishes(false);
        try (MqttClient client = connected()) {
            client.publish(publish("b/0", 0)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> broker.received(Packet.Publish.class).size() == 1);
            assertThat(broker.received(Packet.Publish.class).getFirst()).isEqualTo(publish("b/0", 0));
        }
    }

    /// Before the broker accepts a connect a publish and a subscribe fail at once; QoS 2 is refused outright.
    @Test
    void publishingOrSubscribingWhileNotConnectedFailsAtOnce() {
        try (MqttClient client = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            assertThat(client.connected()).isFalse();
            assertThat(client.publish(publish("b/1", 1))).isCompletedExceptionally();
            assertThat(client.subscribe("b/#", 1)).isCompletedExceptionally();
            assertThatThrownBy(() -> client.publish(publish("b/2", 2))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /// Past the broker's Receive Maximum a QoS 1 publish waits, unsent, until an acknowledgement frees a slot; then it
    /// goes out in its turn.
    @Test
    void publishesPastTheReceiveMaximumWaitForASlot() throws Exception {
        broker.receiveMaximum(2);
        broker.ackPublishes(false);
        try (MqttClient client = connected()) {
            final List<CompletableFuture<Void>> sent = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                sent.add(client.publish(publish("c/" + i, 1)));
            }
            await().during(Duration.ofMillis(300)).atMost(WAIT)
                    .until(() -> broker.received(Packet.Publish.class).size() == 2);
            assertThat(sent.get(2)).isNotDone();
            broker.send(new Packet.PubAck(broker.received(Packet.Publish.class).getFirst().packetId(),
                    Packet.SUCCESS, null));
            await().atMost(WAIT).until(() -> broker.received(Packet.Publish.class).size() == 3);
            assertThat(broker.received(Packet.Publish.class)).extracting(Packet.Publish::topic)
                    .containsExactly("c/1", "c/2", "c/3");
            for (final Packet.Publish p : broker.received(Packet.Publish.class).subList(1, 3)) {
                broker.send(new Packet.PubAck(p.packetId(), Packet.SUCCESS, null));
            }
            CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new))
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /// The broker's Server Keep Alive replaces the client's: at 1 s the client pings within each second of quiet and
    /// stays connected while the broker answers.
    @Test
    void theClientPingsWithinTheServersKeepAlive() throws Exception {
        broker.serverKeepAliveSeconds(1);
        try (MqttClient client = connected()) {
            await().atMost(WAIT).until(() -> broker.received(Packet.PingReq.class).size() >= 3);
            assertThat(client.connected()).isTrue();
            assertThat(recorder.causes).isEmpty();
        }
    }

    /// A ping unanswered within the keep alive ends the connection with Keep Alive timeout, and the client reconnects
    /// as its handler asks.
    @Test
    void anUnansweredPingEndsTheConnection() throws Exception {
        broker.serverKeepAliveSeconds(1);
        broker.answerPings(false);
        try (MqttClient client = connected()) {
            await().atMost(WAIT).until(() -> !recorder.causes.isEmpty());
            assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.KEEP_ALIVE_TIMEOUT));
            assertThat(recorder.attempts.getFirst()).isZero();
            await().atMost(WAIT).until(() -> recorder.connects.get() >= 2);
            assertThat(broker.accepted()).isGreaterThanOrEqualTo(2);
            assertThat(client.connect()).isDone();
        }
    }

    /// Only a PINGRESP answers a ping: a broker that keeps sending messages but never answers is timed out with Keep
    /// Alive timeout all the same (§3.12.4).
    @Test
    void aBrokerThatSendsMessagesButNeverAnswersAPingIsTimedOut() throws Exception {
        broker.serverKeepAliveSeconds(1);
        broker.answerPings(false);
        recorder.retry = null;
        try (MqttClient client = connected()) {
            await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
                broker.send(publish("p/chatter", 0));
                return !recorder.causes.isEmpty();
            });
            assertThat(broker.received(Packet.PingReq.class)).isNotEmpty();
            assertThat(recorder.messages).isNotEmpty();
            assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.KEEP_ALIVE_TIMEOUT));
            assertThat(client.connected()).isFalse();
        }
    }

    /// A broker that drops the connection fails the publish in flight, is reported as an accepted connection ended,
    /// and is connected to again after the handler's delay.
    @Test
    void aDroppedConnectionFailsWhatWasInFlightAndReconnects() throws Exception {
        broker.ackPublishes(false);
        try (MqttClient client = connected()) {
            final CompletableFuture<Void> inFlight = client.publish(publish("d/1", 1));
            await().atMost(WAIT).until(() -> broker.received(Packet.Publish.class).size() == 1);
            broker.drop();
            assertFailsWith(inFlight, Packet.UNSPECIFIED);
            await().atMost(WAIT).until(() -> recorder.connects.get() == 2 && client.connected());
            assertThat(recorder.attempts).containsExactly(0);
            assertThat(broker.accepted()).isEqualTo(2);
            broker.ackPublishes(true);
            client.publish(publish("d/2", 1)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /// A malformed packet in, a topic longer than its packet, is answered with DISCONNECT Malformed Packet before the
    /// client closes the connection.
    @Test
    void aMalformedPacketIsAnsweredWithDisconnect() throws Exception {
        recorder.retry = null;
        try (MqttClient client = connected()) {
            broker.sendRaw(HEX.parseHex("30 02 00 05"));
            await().atMost(WAIT).until(() -> !broker.received(Packet.Disconnect.class).isEmpty());
            assertThat(broker.received(Packet.Disconnect.class).getFirst().reasonCode()).isEqualTo(Packet.MALFORMED);
            await().atMost(WAIT).until(() -> !recorder.causes.isEmpty());
            assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
            assertThat(client.connected()).isFalse();
        }
    }

    /// A packet in larger than the client's maximum is answered with DISCONNECT Packet too large.
    @Test
    void aPacketLargerThanTheClientReadsIsAnsweredWithDisconnect() throws Exception {
        recorder.retry = null;
        try (MqttClient client = client(60, 1_024, null)) {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            broker.send(new Packet.Publish("e/1", new byte[2_000], 0, false, false, 0, null, null));
            await().atMost(WAIT).until(() -> !broker.received(Packet.Disconnect.class).isEmpty());
            assertThat(broker.received(Packet.Disconnect.class).getFirst().reasonCode())
                    .isEqualTo(Packet.PACKET_TOO_LARGE);
            await().atMost(WAIT).until(() -> !client.connected());
        }
    }

    /// A refused connect is retried as the handler asks, its attempts counted from 1; when the handler stops asking
    /// the first-connect future fails with the broker's reason code.
    @Test
    void aRefusedConnectIsRetriedUntilTheHandlerStops() throws Exception {
        broker.connectCode(0x87);
        try (MqttClient retried = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            final CompletableFuture<Void> first = retried.connect();
            await().atMost(WAIT).until(() -> recorder.attempts.size() >= 2);
            broker.connectCode(Packet.SUCCESS);
            first.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(recorder.attempts.subList(0, 2)).containsExactly(1, 2);
            assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(0x87));
        }
        broker.connectCode(0x87);
        recorder.retry = null;
        try (MqttClient given = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            assertFailsWith(given.connect(), 0x87);
            assertThat(given.connected()).isFalse();
        }
    }

    /// A CONNACK whose reason code is in no table, 0x01 here, is neither a success nor a refusal: the client answers
    /// it with DISCONNECT Protocol Error and the connection never counts as up.
    @Test
    void aConnectReasonCodeOutsideTheTableNeverCountsAsConnected() throws Exception {
        broker.connectCode(0x01);
        recorder.retry = null;
        try (MqttClient client = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            assertFailsWith(client.connect(), Packet.PROTOCOL_ERROR);
            await().atMost(WAIT).until(() -> !broker.received(Packet.Disconnect.class).isEmpty());
            assertThat(broker.received(Packet.Disconnect.class).getFirst().reasonCode())
                    .isEqualTo(Packet.PROTOCOL_ERROR);
            assertThat(recorder.connects).hasValue(0);
            assertThat(client.connected()).isFalse();
        }
    }

    /// A DISCONNECT from the broker ends the connection with the broker's reason code, unanswered.
    @Test
    void theBrokersDisconnectEndsTheConnection() throws Exception {
        recorder.retry = null;
        try (MqttClient client = connected()) {
            broker.send(new Packet.Disconnect(0x8B, "shutting down"));
            await().atMost(WAIT).until(() -> !recorder.causes.isEmpty());
            assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(0x8B));
            assertThat(client.connected()).isFalse();
            assertThat(broker.received(Packet.Disconnect.class)).isEmpty();
        }
    }

    /// The client's disconnect sends DISCONNECT Normal, so the broker drops the will, without calling the handler;
    /// every later call returns the same future and the client stays down.
    @Test
    void theClientsDisconnectSendsDisconnectAndStops() throws Exception {
        try (MqttClient client = connected()) {
            final CompletableFuture<Void> stopped = client.disconnect();
            stopped.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> !broker.received(Packet.Disconnect.class).isEmpty());
            assertThat(broker.received(Packet.Disconnect.class)).containsExactly(new Packet.Disconnect(
                    Packet.SUCCESS, null));
            assertThat(client.disconnect()).isSameAs(stopped);
            assertThat(client.connected()).isFalse();
            assertThat(recorder.causes).isEmpty();
            assertThat(broker.accepted()).isEqualTo(1);
        }
    }

    /// A subscription the broker refuses fails with its reason code; the connection stays up.
    @Test
    void aRefusedSubscriptionFails() throws Exception {
        broker.subscribeCode(0x87);
        try (MqttClient client = connected()) {
            assertFailsWith(client.subscribe("f/#", 1), 0x87);
            assertThat(client.connected()).isTrue();
        }
    }

    /// Closing twice, connected or never connected, is harmless, and a closed client does not connect again.
    @Test
    void closingTwiceIsHarmless() throws Exception {
        final MqttClient client = connected();
        try {
            client.close();
            assertThat(client.connected()).isFalse();
        } finally {
            client.close();
        }
        final MqttClient idle = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null);
        try {
            idle.close();
            assertThat(idle.connect()).isCompletedExceptionally();
        } finally {
            idle.close();
        }
        assertThat(broker.accepted()).isEqualTo(1);
    }

    private MqttClient connected() throws Exception {
        final MqttClient client = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null);
        try {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final Exception e) {
            client.close();
            throw e;
        }
        return client;
    }

    private MqttClient client(final int keepAliveSeconds, final int maximumPacketSize, final Packet.Publish will) {
        return new MqttClient(new MqttClient.Options("127.0.0.1", broker.port(), "test-client", keepAliveSeconds,
                maximumPacketSize, will), recorder, callbacks);
    }

    private static void assertFailsWith(final CompletableFuture<Void> future, final int reasonCode) {
        assertThatThrownBy(() -> future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .cause().isInstanceOfSatisfying(MqttException.class,
                        e -> assertThat(e.reasonCode()).isEqualTo(reasonCode));
    }

    private static Packet.Publish publish(final String topic, final int qos) {
        return new Packet.Publish(topic, bytes(topic), qos, false, false, 0, null, null);
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

}
