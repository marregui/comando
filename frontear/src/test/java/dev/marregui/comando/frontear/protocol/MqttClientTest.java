package dev.marregui.comando.frontear.protocol;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.loop.Threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// The client against [FakeGateway] on a real socket: connect, subscriptions with their options and unsubscribes,
/// publishes both ways at QoS 0 and 1, PUBACKs in that wait for the handler, the bound on QoS 0 messages in that wait
/// for it, the Receive Maximum both ways, keep alive
/// and its timeout, a gateway that drops, refuses or disconnects, a malformed or too large packet in, a connect reason
/// code outside the table, and the client's own disconnect.
class MqttClientTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

    private static FakeGateway gateway;
    private static ExecutorService callbacks;

    private final Recorder recorder = new Recorder();

    @BeforeAll
    static void startGateway() {
        gateway = new FakeGateway();
        callbacks = Threads.strand("mqtt-test-callbacks");
    }

    @AfterAll
    static void stopGateway() {
        try {
            if (gateway != null) {
                gateway.close();
            }
        } finally {
            if (callbacks != null) {
                ExecutorStop.now("mqtt-test", callbacks);
            }
        }
    }

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    /// The CONNECT carries the options: client id, a clean start whose session ends with the connection, the keep
    /// alive, the largest packet the client reads and the will; once the gateway accepts it the handler hears of it.
    @Test
    void connectCarriesTheOptions() throws Exception {
        final Packet.Publish will = new Packet.Publish("peers/a/presence", bytes("gone"), 1, true, false, 0, null,
                null);
        try (MqttClient client = client(30, 1_048_576, will)) {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(client.connected()).isTrue();
            assertThat(gateway.received(Packet.Connect.class)).containsExactly(new Packet.Connect("test-client", true,
                    30, 0, Packet.DEFAULT_RECEIVE_MAXIMUM, 1_048_576, will));
            await().atMost(WAIT).until(() -> recorder.connects.get() == 1);
            assertThat(client.connect()).isSameAs(client.connect());
        }
    }

    /// Messages in reach the handler in the order the gateway sent them, QoS 0 and 1 mixed, and each QoS 1 one is
    /// acknowledged with its own packet id.
    @Test
    void messagesInReachTheHandlerInOrderAndQos1OnesAreAcknowledged() throws Exception {
        try (MqttClient client = connected()) {
            client.subscribe("a/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(gateway.received(Packet.Subscribe.class).getFirst().subscriptions())
                    .containsExactly(new Packet.Subscription("a/#", 1));
            for (int i = 1; i <= 5; i++) {
                gateway.send(new Packet.Publish("a/" + i, bytes("m" + i), i == 3 ? 0 : 1, false, false, 0, "a/reply",
                        bytes("c" + i)));
            }
            await().atMost(WAIT).until(() -> recorder.messages.size() == 5);
            assertThat(recorder.messages).extracting(p -> new String(p.payload(), StandardCharsets.UTF_8))
                    .containsExactly("m1", "m2", "m3", "m4", "m5");
            assertThat(recorder.messages.getFirst().responseTopic()).isEqualTo("a/reply");
            assertThat(recorder.messages.getFirst().correlation()).isEqualTo(bytes("c1"));
            await().atMost(WAIT).until(() -> gateway.received(Packet.PubAck.class).size() == 4);
            assertThat(gateway.received(Packet.PubAck.class)).extracting(Packet.PubAck::packetId)
                    .containsExactly(1, 2, 3, 4);
            assertThat(gateway.received(Packet.PubAck.class)).extracting(Packet.PubAck::reasonCode)
                    .containsOnly(Packet.SUCCESS);
        }
    }

    /// A QoS 1 publish completes when its PUBACK arrives, not before, and fails with the gateway's reason code when the
    /// PUBACK refuses it.
    @Test
    void aQos1PublishCompletesOnItsAcknowledgement() throws Exception {
        gateway.ackPublishes(false);
        try (MqttClient client = connected()) {
            final CompletableFuture<Void> sent = client.publish(publish("b/1", 1));
            await().atMost(WAIT).until(() -> gateway.received(Packet.Publish.class).size() == 1);
            final Packet.Publish out = gateway.received(Packet.Publish.class).getFirst();
            assertThat(out.qos()).isEqualTo(1);
            assertThat(out.packetId()).isPositive();
            assertThat(sent).isNotDone();
            gateway.send(new Packet.PubAck(out.packetId(), Packet.SUCCESS, null));
            sent.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

            final CompletableFuture<Void> refused = client.publish(publish("b/2", 1));
            await().atMost(WAIT).until(() -> gateway.received(Packet.Publish.class).size() == 2);
            gateway.send(new Packet.PubAck(gateway.received(Packet.Publish.class).get(1).packetId(), 0x87,
                    "not allowed"));
            assertFailsWith(refused, 0x87);
            assertThat(client.connected()).isTrue();
        }
    }

    /// A QoS 0 publish completes once written and goes out without a packet id.
    @Test
    void aQos0PublishCompletesOnceWritten() throws Exception {
        gateway.ackPublishes(false);
        try (MqttClient client = connected()) {
            client.publish(publish("b/0", 0)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> gateway.received(Packet.Publish.class).size() == 1);
            assertThat(gateway.received(Packet.Publish.class).getFirst()).isEqualTo(publish("b/0", 0));
        }
    }

    /// Before the gateway accepts a connect a publish and a subscribe fail at once; QoS 2 is refused outright.
    @Test
    void publishingOrSubscribingWhileNotConnectedFailsAtOnce() {
        try (MqttClient client = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            assertThat(client.connected()).isFalse();
            assertThat(client.publish(publish("b/1", 1))).isCompletedExceptionally();
            assertThat(client.subscribe("b/#", 1)).isCompletedExceptionally();
            assertThatThrownBy(() -> client.publish(publish("b/2", 2))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /// Past the gateway's Receive Maximum a QoS 1 publish waits, unsent, until an acknowledgement frees a slot; then it
    /// goes out in its turn.
    @Test
    void publishesPastTheReceiveMaximumWaitForASlot() throws Exception {
        gateway.receiveMaximum(2);
        gateway.ackPublishes(false);
        try (MqttClient client = connected()) {
            final List<CompletableFuture<Void>> sent = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                sent.add(client.publish(publish("c/" + i, 1)));
            }
            await().during(Duration.ofMillis(300)).atMost(WAIT)
                    .until(() -> gateway.received(Packet.Publish.class).size() == 2);
            assertThat(sent.get(2)).isNotDone();
            gateway.send(new Packet.PubAck(gateway.received(Packet.Publish.class).getFirst().packetId(),
                    Packet.SUCCESS, null));
            await().atMost(WAIT).until(() -> gateway.received(Packet.Publish.class).size() == 3);
            assertThat(gateway.received(Packet.Publish.class)).extracting(Packet.Publish::topic)
                    .containsExactly("c/1", "c/2", "c/3");
            for (final Packet.Publish p : gateway.received(Packet.Publish.class).subList(1, 3)) {
                gateway.send(new Packet.PubAck(p.packetId(), Packet.SUCCESS, null));
            }
            CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new))
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /// The gateway's Server Keep Alive replaces the client's: at 1 s the client pings within each second of quiet and
    /// stays connected while the gateway answers.
    @Test
    void theClientPingsWithinTheServersKeepAlive() throws Exception {
        gateway.serverKeepAliveSeconds(1);
        try (MqttClient client = connected()) {
            await().atMost(WAIT).until(() -> gateway.received(Packet.PingReq.class).size() >= 3);
            assertThat(client.connected()).isTrue();
            assertThat(recorder.causes).isEmpty();
        }
    }

    /// A ping unanswered within the keep alive ends the connection with Keep Alive timeout, and the client reconnects
    /// as its handler asks.
    @Test
    void anUnansweredPingEndsTheConnection() throws Exception {
        gateway.serverKeepAliveSeconds(1);
        gateway.answerPings(false);
        try (MqttClient client = connected()) {
            await().atMost(WAIT).until(() -> !recorder.causes.isEmpty());
            assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.KEEP_ALIVE_TIMEOUT));
            assertThat(recorder.attempts.getFirst()).isZero();
            await().atMost(WAIT).until(() -> recorder.connects.get() >= 2);
            assertThat(gateway.accepted()).isGreaterThanOrEqualTo(2);
            assertThat(client.connect()).isDone();
        }
    }

    /// Only a PINGRESP answers a ping: a gateway that keeps sending messages but never answers is timed out with Keep
    /// Alive timeout all the same (§3.12.4).
    @Test
    void aGatewayThatSendsMessagesButNeverAnswersAPingIsTimedOut() throws Exception {
        gateway.serverKeepAliveSeconds(1);
        gateway.answerPings(false);
        recorder.retry = null;
        try (MqttClient client = connected()) {
            await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
                gateway.send(publish("p/chatter", 0));
                return !recorder.causes.isEmpty();
            });
            assertThat(gateway.received(Packet.PingReq.class)).isNotEmpty();
            assertThat(recorder.messages).isNotEmpty();
            assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.KEEP_ALIVE_TIMEOUT));
            assertThat(client.connected()).isFalse();
        }
    }

    /// A connection left idle for 5 s waits for each packet with no read timeout: no SocketTimeoutException passes
    /// through its socket's stream, where a reader woken by a timeout to run its checks would see one a second.
    @Test
    void anIdleConnectionReadsWithoutATimeout() throws Exception {
        final AtomicInteger timeouts = new AtomicInteger();
        try (MqttClient client = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            client.sockets(() -> new CountingSocket(timeouts));
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8)).until(client::connected);
            assertThat(timeouts).hasValue(0);
            assertThat(recorder.causes).isEmpty();
        }
    }

    /// A gateway that accepts the connection and then stops reading blocks the client's writes once the socket
    /// buffers fill; the watcher ends the connection with Unspecified error no sooner than the write bound, and
    /// within it and a second of checks, plus slack for a loaded machine.
    @Test
    void aWriteThatStallsEndsTheConnection() throws Exception {
        recorder.retry = null;
        final CompletableFuture<Socket> accepted = new CompletableFuture<>();
        try (ServerSocket deaf = new ServerSocket()) {
            deaf.setReceiveBufferSize(4_096);
            deaf.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final Thread answering = Threads.fork("mqtt-test-deaf-gateway", () -> answerConnectThenStopReading(
                    deaf, accepted));
            try (MqttClient client = client(recorder, new MqttClient.Options("127.0.0.1", deaf.getLocalPort(),
                    "stalled-client", 60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null))) {
                client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                final byte[] payload = new byte[64 * 1_024];
                final long startNanos = System.nanoTime();
                // 32 MiB: more than a loopback socket's send and receive buffers hold together
                for (int i = 0; i < 512; i++) {
                    client.publish(new Packet.Publish("s/big", payload, 0, false, false, 0, null, null));
                }
                await().atMost(MqttClient.WRITE_WITHIN.plusSeconds(10)).until(() -> !recorder.causes.isEmpty());
                final Duration took = Duration.ofNanos(System.nanoTime() - startNanos);
                assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                        e -> assertThat(e.reasonCode()).isEqualTo(Packet.UNSPECIFIED));
                assertThat(took).isGreaterThanOrEqualTo(MqttClient.WRITE_WITHIN);
                assertThat(client.connected()).isFalse();
            } finally {
                answering.join(WAIT.toMillis());
                if (accepted.isDone() && !accepted.isCompletedExceptionally()) {
                    accepted.join().close();
                }
            }
        }
    }

    /// A gateway that drops the connection fails the publish in flight, is reported as an accepted connection ended,
    /// and is connected to again after the handler's delay.
    @Test
    void aDroppedConnectionFailsWhatWasInFlightAndReconnects() throws Exception {
        gateway.ackPublishes(false);
        try (MqttClient client = connected()) {
            final CompletableFuture<Void> inFlight = client.publish(publish("d/1", 1));
            await().atMost(WAIT).until(() -> gateway.received(Packet.Publish.class).size() == 1);
            gateway.drop();
            assertFailsWith(inFlight, Packet.UNSPECIFIED);
            await().atMost(WAIT).until(() -> recorder.connects.get() == 2 && client.connected());
            assertThat(recorder.attempts).containsExactly(0);
            assertThat(gateway.accepted()).isEqualTo(2);
            gateway.ackPublishes(true);
            client.publish(publish("d/2", 1)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /// A malformed packet in, a topic longer than its packet, is answered with DISCONNECT Malformed Packet before the
    /// client closes the connection.
    @Test
    void aMalformedPacketIsAnsweredWithDisconnect() throws Exception {
        recorder.retry = null;
        try (MqttClient client = connected()) {
            gateway.sendRaw(HEX.parseHex("30 02 00 05"));
            await().atMost(WAIT).until(() -> !gateway.received(Packet.Disconnect.class).isEmpty());
            assertThat(gateway.received(Packet.Disconnect.class).getFirst().reasonCode()).isEqualTo(Packet.MALFORMED);
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
            gateway.send(new Packet.Publish("e/1", new byte[2_000], 0, false, false, 0, null, null));
            await().atMost(WAIT).until(() -> !gateway.received(Packet.Disconnect.class).isEmpty());
            assertThat(gateway.received(Packet.Disconnect.class).getFirst().reasonCode())
                    .isEqualTo(Packet.PACKET_TOO_LARGE);
            await().atMost(WAIT).until(() -> !client.connected());
        }
    }

    /// A refused connect is retried as the handler asks, its attempts counted from 1; when the handler stops asking
    /// the first-connect future fails with the gateway's reason code.
    @Test
    void aRefusedConnectIsRetriedUntilTheHandlerStops() throws Exception {
        gateway.connectCode(0x87);
        try (MqttClient retried = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            final CompletableFuture<Void> first = retried.connect();
            await().atMost(WAIT).until(() -> recorder.attempts.size() >= 2);
            gateway.connectCode(Packet.SUCCESS);
            first.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(recorder.attempts.subList(0, 2)).containsExactly(1, 2);
            assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(0x87));
        }
        gateway.connectCode(0x87);
        recorder.retry = null;
        try (MqttClient given = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            assertFailsWith(given.connect(), 0x87);
            assertThat(given.connected()).isFalse();
        }
    }

    /// A wake that lands after the scheduled reconnect has begun, but before it opened its connection, cancels
    /// nothing; the begun connect gives way to the woken one, so one connect chain runs. The seam holds the second
    /// connect at its start: each connect records how many attempts had ended when it began, and the woken third
    /// begins after one attempt, not two.
    @Test
    void aWakeWhileTheScheduledConnectHasBegunLeavesOneConnectChain() throws Exception {
        gateway.connectCode(0x87);
        final List<Integer> startedAfter = new CopyOnWriteArrayList<>();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (MqttClient client = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            client.beforeSession(() -> {
                startedAfter.add(recorder.attempts.size());
                if (startedAfter.size() == 2) {
                    held.countDown();
                    try {
                        release.await(2 * WAIT.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (final InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            final CompletableFuture<Void> first = client.connect();
            try {
                assertThat(held.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
                recorder.retry = null;
                assertThat(client.wake()).isTrue();
            } finally {
                release.countDown();
            }
            await().atMost(WAIT).until(() -> startedAfter.size() == 3);
            assertFailsWith(first, 0x87);
            assertThat(startedAfter).containsExactly(0, 1, 1);
            assertThat(recorder.attempts).containsExactly(1, 2);
        }
    }

    /// A CONNACK whose reason code is in no table, 0x01 here, is neither a success nor a refusal: the client answers
    /// it with DISCONNECT Protocol Error and the connection never counts as up.
    @Test
    void aConnectReasonCodeOutsideTheTableNeverCountsAsConnected() throws Exception {
        gateway.connectCode(0x01);
        recorder.retry = null;
        try (MqttClient client = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            assertFailsWith(client.connect(), Packet.PROTOCOL_ERROR);
            await().atMost(WAIT).until(() -> !gateway.received(Packet.Disconnect.class).isEmpty());
            assertThat(gateway.received(Packet.Disconnect.class).getFirst().reasonCode())
                    .isEqualTo(Packet.PROTOCOL_ERROR);
            assertThat(recorder.connects).hasValue(0);
            assertThat(client.connected()).isFalse();
        }
    }

    /// [MQTT-3.2.2-4]: the client always asks Clean Start, so a CONNACK with Session Present 1 is a Protocol Error and
    /// the connection never counts as up.
    @Test
    void aSessionPresentAfterACleanStartIsAProtocolError() throws Exception {
        gateway.sessionPresent(true);
        recorder.retry = null;
        try (MqttClient client = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            assertFailsWith(client.connect(), Packet.PROTOCOL_ERROR);
            assertThat(recorder.connects).hasValue(0);
            assertThat(client.connected()).isFalse();
        }
    }

    /// [MQTT-3.2.2-11] and [MQTT-3.2.2-14]: past a CONNACK that states Maximum QoS 0 and Retain Available 0, a QoS 1
    /// publish fails with 0x9B and a retained one with 0x9A, neither sent; a plain QoS 0 publish still goes out.
    @Test
    void aPublishPastTheGatewaysCapabilitiesFailsUnsent() throws Exception {
        gateway.capabilities(new Packet.Capabilities(0, false, true, false, false));
        try (MqttClient client = connected()) {
            assertFailsWith(client.publish(publish("q/1", 1)), Packet.QOS_NOT_SUPPORTED);
            assertFailsWith(client.publish(new Packet.Publish("q/r", bytes("r"), 0, true, false, 0, null, null)),
                    Packet.RETAIN_NOT_SUPPORTED);
            client.publish(publish("q/0", 0)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> gateway.received(Packet.Publish.class).size() == 1);
            assertThat(gateway.received(Packet.Publish.class)).containsExactly(publish("q/0", 0));
            assertThat(client.connected()).isTrue();
        }
    }

    /// A DISCONNECT from the gateway ends the connection with the gateway's reason code, unanswered.
    @Test
    void theGatewaysDisconnectEndsTheConnection() throws Exception {
        recorder.retry = null;
        try (MqttClient client = connected()) {
            gateway.send(new Packet.Disconnect(0x8B, "shutting down"));
            await().atMost(WAIT).until(() -> !recorder.causes.isEmpty());
            assertThat(recorder.causes.getFirst()).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(0x8B));
            assertThat(client.connected()).isFalse();
            assertThat(gateway.received(Packet.Disconnect.class)).isEmpty();
        }
    }

    /// The client's disconnect sends DISCONNECT Normal, so the gateway drops the will, without calling the handler;
    /// every later call returns the same future and the client stays down.
    @Test
    void theClientsDisconnectSendsDisconnectAndStops() throws Exception {
        try (MqttClient client = connected()) {
            final CompletableFuture<Void> stopped = client.disconnect();
            stopped.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> !gateway.received(Packet.Disconnect.class).isEmpty());
            assertThat(gateway.received(Packet.Disconnect.class)).containsExactly(new Packet.Disconnect(
                    Packet.SUCCESS, null));
            assertThat(client.disconnect()).isSameAs(stopped);
            assertThat(client.connected()).isFalse();
            assertThat(recorder.causes).isEmpty();
            assertThat(gateway.accepted()).isEqualTo(1);
        }
    }

    /// A subscription the gateway refuses fails with its reason code; the connection stays up.
    @Test
    void aRefusedSubscriptionFails() throws Exception {
        gateway.subscribeCode(0x87);
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
        assertThat(gateway.accepted()).isEqualTo(1);
    }

    /// A subscription goes out with its options, No Local, Retain As Published and Retain Handling, and an
    /// unsubscribe completes on its acknowledgement, for a filter the gateway held no subscription for as well (0x11).
    @Test
    void subscriptionOptionsAndUnsubscribesGoOnTheWire() throws Exception {
        try (MqttClient client = connected()) {
            final Packet.Subscription options = new Packet.Subscription("g/#", 1, true, true, 2);
            client.subscribe(options).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(gateway.received(Packet.Subscribe.class).getFirst().subscriptions()).containsExactly(options);
            client.unsubscribe("g/#").get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            client.unsubscribe("never/#").get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(gateway.received(Packet.Unsubscribe.class)).extracting(Packet.Unsubscribe::filters)
                    .containsExactly(List.of("g/#"), List.of("never/#"));
            assertThat(client.connected()).isTrue();
        }
        try (MqttClient idle = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            assertThat(idle.unsubscribe("g/#")).isCompletedExceptionally();
        }
    }

    /// A QoS 1 message in is acknowledged only once the stage its handler returned completes, with the code it gave,
    /// and PUBACKs leave in arrival order (§4.6): a later message answered first waits for the earlier one; a stage
    /// that fails is acknowledged with Unspecified error.
    @Test
    void aMessageInIsAcknowledgedWhenItsHandlerStageCompletesInArrivalOrder() throws Exception {
        final Answering answering = new Answering();
        try (MqttClient client = client(answering, new MqttClient.Options("127.0.0.1", gateway.port(), "test-client",
                60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null))) {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            for (int i = 1; i <= 3; i++) {
                gateway.send(publish("h/" + i, 1));
            }
            await().atMost(WAIT).until(() -> answering.answers.size() == 3);
            answering.answers.get(1).complete(Packet.SUCCESS);
            await().during(Duration.ofMillis(300)).atMost(WAIT)
                    .until(() -> gateway.received(Packet.PubAck.class).isEmpty());
            answering.answers.getFirst().complete(Packet.QUOTA_EXCEEDED);
            await().atMost(WAIT).until(() -> gateway.received(Packet.PubAck.class).size() == 2);
            answering.answers.get(2).completeExceptionally(new IllegalStateException("local publish failed"));
            await().atMost(WAIT).until(() -> gateway.received(Packet.PubAck.class).size() == 3);
            assertThat(gateway.received(Packet.PubAck.class)).containsExactly(
                    new Packet.PubAck(1, Packet.QUOTA_EXCEEDED, null), new Packet.PubAck(2, Packet.SUCCESS, null),
                    new Packet.PubAck(3, Packet.UNSPECIFIED, null));
            assertThat(recorder.messages).extracting(Packet.Publish::topic).containsExactly("h/1", "h/2", "h/3");
        }
    }

    /// The CONNECT carries the client's Receive Maximum, and a gateway that sends more QoS 1 messages than that
    /// unacknowledged is answered with DISCONNECT Receive Maximum exceeded.
    @Test
    void moreMessagesInUnacknowledgedThanTheClientsReceiveMaximumIsAnsweredWithDisconnect() throws Exception {
        recorder.retry = null;
        final Answering answering = new Answering();
        try (MqttClient client = client(answering, new MqttClient.Options("127.0.0.1", gateway.port(), "test-client",
                60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null, 2))) {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(gateway.received(Packet.Connect.class).getFirst().receiveMaximum()).isEqualTo(2);
            for (int i = 1; i <= 3; i++) {
                gateway.send(publish("i/" + i, 1));
            }
            await().atMost(WAIT).until(() -> !gateway.received(Packet.Disconnect.class).isEmpty());
            assertThat(gateway.received(Packet.Disconnect.class).getFirst().reasonCode())
                    .isEqualTo(Packet.RECEIVE_MAXIMUM_EXCEEDED);
            await().atMost(WAIT).until(() -> !client.connected());
        }
    }

    /// A flood of QoS 0 messages in while the handler blocks: the client's bound of them wait for it and every later
    /// one is dropped and counted, the queue's reading shows the bound in use, and the connection stays up; once free,
    /// the handler gets exactly the messages that waited, in order, and the next message in. A bound below one is
    /// refused.
    @Test
    void qos0MessagesInPastTheBoundWhileTheHandlerBlocksAreDroppedAndCounted() throws Exception {
        assertThatThrownBy(() -> new MqttClient.Options("127.0.0.1", gateway.port(), "test-client", 60,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null, Packet.DEFAULT_RECEIVE_MAXIMUM, 0))
                .isInstanceOf(IllegalArgumentException.class);
        final int max = 10;
        final int count = 100;
        final Blocking blocking = new Blocking();
        try (MqttClient client = client(blocking, new MqttClient.Options("127.0.0.1", gateway.port(), "test-client",
                60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null, Packet.DEFAULT_RECEIVE_MAXIMUM, max))) {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> recorder.connects.get() == 1);
            for (int i = 0; i < count; i++) {
                gateway.send(publish("q/" + i, 0));
            }
            await().atMost(WAIT).until(() -> client.droppedQos0() == count - max);
            assertThat(waitingQos0("test-client")).isEqualTo(max);
            assertThat(client.connected()).isTrue();
            blocking.release.countDown();
            await().atMost(WAIT).until(() -> recorder.messages.size() == max);
            gateway.send(publish("q/after", 0));
            await().atMost(WAIT).until(() -> recorder.messages.size() == max + 1);
            final List<String> expected = new ArrayList<>();
            for (int i = 0; i < max; i++) {
                expected.add("q/" + i);
            }
            expected.add("q/after");
            assertThat(recorder.messages).extracting(Packet.Publish::topic).containsExactlyElementsOf(expected);
            assertThat(client.droppedQos0()).isEqualTo(count - max);
            assertThat(waitingQos0("test-client")).isZero();
        } finally {
            blocking.release.countDown();
        }
    }

    /// The client reports the gateway's Receive Maximum while connected, 0 otherwise.
    @Test
    void theClientReportsTheGatewaysReceiveMaximum() throws Exception {
        gateway.receiveMaximum(7);
        try (MqttClient client = client(60, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null)) {
            assertThat(client.receiveMaximum()).isZero();
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(client.receiveMaximum()).isEqualTo(7);
        }
    }

    /// An acknowledged publish completes with the PUBACK's reason code, a refusal included, and with Success for QoS 0
    /// once written; it fails only when no PUBACK can come, here a dropped connection.
    @Test
    void anAcknowledgedPublishCompletesWithThePubackReasonCode() throws Exception {
        gateway.ackPublishes(false);
        try (MqttClient client = connected()) {
            final CompletableFuture<Integer> refused = client.publishAcknowledged(publish("j/1", 1));
            await().atMost(WAIT).until(() -> gateway.received(Packet.Publish.class).size() == 1);
            gateway.send(new Packet.PubAck(gateway.received(Packet.Publish.class).getFirst().packetId(),
                    Packet.QUOTA_EXCEEDED, "full"));
            assertThat(refused.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.QUOTA_EXCEEDED);
            assertThat(client.publishAcknowledged(publish("j/0", 0)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(Packet.SUCCESS);
            final CompletableFuture<Integer> lost = client.publishAcknowledged(publish("j/2", 1));
            await().atMost(WAIT).until(() -> gateway.received(Packet.Publish.class).size() == 3);
            gateway.drop();
            assertThatThrownBy(() -> lost.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).cause()
                    .isInstanceOfSatisfying(MqttException.class,
                            e -> assertThat(e.reasonCode()).isEqualTo(Packet.UNSPECIFIED));
        }
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
        return new MqttClient(new MqttClient.Options("127.0.0.1", gateway.port(), "test-client", keepAliveSeconds,
                maximumPacketSize, will), recorder, callbacks);
    }

    private static MqttClient client(final MqttClient.Handler handler, final MqttClient.Options options) {
        return new MqttClient(options, handler, callbacks);
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

    /// The QoS 0 messages in of client `clientId` waiting for its handler, as the queue readings show them; -1 when
    /// there is no reading.
    private static int waitingQos0(final String clientId) {
        return Queues.read().stream().filter(r -> r.name().equals("mqtt-qos0-" + clientId))
                .mapToInt(Queues.Reading::depth).findFirst().orElse(-1);
    }

    /// Accepts one connection on `deaf`, answers its CONNECT with a successful CONNACK and reads nothing more,
    /// handing the open socket to `accepted` for the test to close.
    private static void answerConnectThenStopReading(final ServerSocket deaf,
            final CompletableFuture<Socket> accepted) {
        try {
            final Socket s = deaf.accept();
            accepted.complete(s);
            final InputStream in = new BufferedInputStream(s.getInputStream());
            final int first = in.read();
            if (first < 0) {
                return;
            }
            Codec.read(in, first, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE);
            s.getOutputStream().write(Codec.encode(new Packet.ConnAck(false, Packet.SUCCESS,
                    Packet.DEFAULT_RECEIVE_MAXIMUM, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 0,
                    Packet.NO_SERVER_KEEP_ALIVE, null, null)));
        } catch (final IOException e) {
            accepted.completeExceptionally(e);
        }
    }

    /// A plain socket whose input stream counts the read timeouts thrown through it into `timeouts`.
    private static final class CountingSocket extends Socket {

        private final AtomicInteger timeouts;

        CountingSocket(final AtomicInteger timeouts) {
            this.timeouts = timeouts;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FilterInputStream(super.getInputStream()) {

                @Override
                public int read() throws IOException {
                    try {
                        return super.read();
                    } catch (final SocketTimeoutException e) {
                        timeouts.incrementAndGet();
                        throw e;
                    }
                }

                @Override
                public int read(final byte[] b, final int off, final int len) throws IOException {
                    try {
                        return super.read(b, off, len);
                    } catch (final SocketTimeoutException e) {
                        timeouts.incrementAndGet();
                        throw e;
                    }
                }
            };
        }
    }

    /// Records as the test's [Recorder] does, and holds the callbacks executor in each message until released.
    private final class Blocking implements MqttClient.Handler {

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void connected() {
            recorder.connected();
        }

        @Override
        public void message(final Packet.Publish message) {
            recorder.message(message);
            try {
                // bounded past the test's own waits, so a failed test does not hold the shared executor for good
                release.await(2 * WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public Duration disconnected(final int attempts, final Throwable cause) {
            return recorder.disconnected(attempts, cause);
        }
    }

    /// Records as the test's [Recorder] does, and answers each message with a stage the test completes.
    private final class Answering implements MqttClient.Handler {

        private final List<CompletableFuture<Integer>> answers = new CopyOnWriteArrayList<>();

        @Override
        public void connected() {
            recorder.connected();
        }

        @Override
        public void message(final Packet.Publish message) {
            recorder.message(message);
        }

        @Override
        public CompletionStage<Integer> received(final Packet.Publish message) {
            recorder.message(message);
            final CompletableFuture<Integer> answer = new CompletableFuture<>();
            answers.add(answer);
            return answer;
        }

        @Override
        public Duration disconnected(final int attempts, final Throwable cause) {
            return recorder.disconnected(attempts, cause);
        }
    }
}
