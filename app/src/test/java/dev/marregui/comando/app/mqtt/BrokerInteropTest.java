package dev.marregui.comando.app.mqtt;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The client against a real MQTT 5 broker, the one the environment variable `COMANDO_MQTT_BROKER` names as
/// `host:port`; skipped without it. Connect and the broker's acknowledgement, two clients exchanging messages through
/// `+` and `#` filters at QoS 0 and 1, a retained message, response topic and correlation data, the will of a
/// connection cut without DISCONNECT and its absence after one, pings across several keep-alive intervals, and payloads
/// from empty to 1 MiB. Clients that must be cut connect through a [Relay]. Every test publishes under a topic prefix
/// of its own run, so runs and other clients of the broker do not meet, and clears what it retained.
class BrokerInteropTest {

    private static final System.Logger LOG = System.getLogger(BrokerInteropTest.class.getName());
    private static final Duration WAIT = Duration.ofSeconds(15);
    /// What the client reads: the protocol's largest packet, as `MqttPeerBroker` asks for, so none is announced.
    private static final int MAXIMUM_PACKET_SIZE = Packet.PROTOCOL_MAXIMUM_PACKET_SIZE;
    private static final int PINGREQ = 12;
    private static final int PINGRESP = 13;
    private static final int PUBACK = 4;

    private static String host;
    private static int port;
    private static ExecutorService callbacks;

    private final String run = "comando-interop-" + ProcessHandle.current().pid() + '-' + System.nanoTime();
    private final String prefix = "comando-interop/" + run;

    @BeforeAll
    static void broker() {
        final String at = System.getenv("COMANDO_MQTT_BROKER");
        assumeTrue(at != null && at.lastIndexOf(':') > 0, "set COMANDO_MQTT_BROKER=host:port to run against a broker");
        host = at.substring(0, at.lastIndexOf(':'));
        port = Integer.parseInt(at.substring(at.lastIndexOf(':') + 1));
        callbacks = Threads.strand("interop-callbacks");
    }

    @AfterAll
    static void stopCallbacks() {
        if (callbacks != null) {
            ExecutorStop.now("interop", callbacks);
        }
    }

    /// The broker accepts a CONNECT with an empty client id and assigns one; its CONNACK reads, with limits that let
    /// this client's largest payload through; and the client's own connect completes.
    @Test
    void theBrokerAcceptsAConnectAndItsAcknowledgementReads() throws Exception {
        final Packet.ConnAck ack;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), (int) WAIT.toMillis());
            s.setSoTimeout((int) WAIT.toMillis());
            final InputStream in = new BufferedInputStream(s.getInputStream());
            s.getOutputStream().write(Codec.encode(new Packet.Connect("", true, 30, 0, Packet.DEFAULT_RECEIVE_MAXIMUM,
                    MAXIMUM_PACKET_SIZE, null)));
            final Packet answer = Codec.read(in, in.read(), MAXIMUM_PACKET_SIZE);
            if (!(answer instanceof final Packet.ConnAck a)) {
                throw new AssertionError("first packet not a CONNACK [packet=" + answer + ']');
            }
            ack = a;
            s.getOutputStream().write(Codec.encode(new Packet.Disconnect(Packet.SUCCESS, null)));
        }
        LOG.log(System.Logger.Level.INFO, "broker acknowledged [connAck=" + ack + ']');
        assertThat(ack.reasonCode()).isEqualTo(Packet.SUCCESS);
        assertThat(ack.sessionPresent()).isFalse();
        assertThat(ack.assignedClientId()).isNotBlank();
        assertThat(ack.maximumPacketSize()).isGreaterThan(1_048_576 + 1_024);

        final Recorder recorder = new Recorder();
        try (MqttClient client = direct("connect", recorder, 30, null)) {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(client.connected()).isTrue();
            await().atMost(WAIT).until(() -> recorder.connects.get() == 1);
        }
    }

    /// Two clients, each subscribed through a wildcard, exchange messages both ways at QoS 0 and 1: `+` takes one
    /// level and no more, `#` its parent level and every level below; a QoS 1 publish completes on the broker's
    /// PUBACK, a QoS 1 message in is acknowledged, and the response topic and correlation data arrive byte for byte.
    @Test
    void twoClientsExchangeMessagesAtQos0And1ThroughWildcards() throws Exception {
        final Recorder a = new Recorder();
        final Recorder b = new Recorder();
        final byte[] correlation = {0, 1, 2, (byte) 0xFF, 'c'};
        try (Relay relay = new Relay(host, port);
                MqttClient ca = relayed(relay, "a", a, 30, null);
                MqttClient cb = direct("b", b, 30, null)) {
            ca.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            cb.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            ca.subscribe(prefix + "/to-a/+", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            cb.subscribe(prefix + "/to-b/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

            ca.publish(new Packet.Publish(prefix + "/to-b/x/y", bytes("q1"), 1, false, false, 0, prefix + "/to-a/reply",
                    correlation)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            ca.publish(new Packet.Publish(prefix + "/other", bytes("none"), 1, false, false, 0, null, null))
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            ca.publish(message(prefix + "/to-b", "q0", 0)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            cb.publish(message(prefix + "/to-a/r/deep", "none", 1)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            cb.publish(message(prefix + "/to-a/r0", "r0", 0)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            cb.publish(new Packet.Publish(prefix + "/to-a/r1", bytes("r1"), 1, false, false, 0, prefix + "/to-b/reply",
                    correlation)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

            await().atMost(WAIT).until(() -> b.messages.size() == 2 && a.messages.size() == 2);
            final Map<String, Packet.Publish> toB = byTopic(b.messages);
            assertThat(toB).containsOnlyKeys(prefix + "/to-b/x/y", prefix + "/to-b");
            assertThat(toB.get(prefix + "/to-b/x/y")).satisfies(m -> {
                assertThat(m.qos()).isEqualTo(1);
                assertThat(m.payload()).isEqualTo(bytes("q1"));
                assertThat(m.responseTopic()).isEqualTo(prefix + "/to-a/reply");
                assertThat(m.correlation()).isEqualTo(correlation);
            });
            assertThat(toB.get(prefix + "/to-b").qos()).isZero();
            final Map<String, Packet.Publish> toA = byTopic(a.messages);
            assertThat(toA).containsOnlyKeys(prefix + "/to-a/r0", prefix + "/to-a/r1");
            assertThat(toA.get(prefix + "/to-a/r1")).satisfies(m -> {
                assertThat(m.qos()).isEqualTo(1);
                assertThat(m.responseTopic()).isEqualTo(prefix + "/to-b/reply");
                assertThat(m.correlation()).isEqualTo(correlation);
            });
            // the relay counts a packet once it has forwarded it, so a little after its receiver may have read it
            await().atMost(WAIT).until(() -> relay.fromBroker(PUBACK) == 2 && relay.fromClient(PUBACK) == 1);
        }
    }

    /// A message published retained reaches a client that subscribes only later, flagged retained; an empty retained
    /// payload clears it.
    @Test
    void aRetainedMessageReachesALaterSubscriber() throws Exception {
        final Recorder a = new Recorder();
        final Recorder b = new Recorder();
        final String topic = prefix + "/retained";
        try (MqttClient ca = direct("a", a, 30, null)) {
            ca.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            ca.publish(new Packet.Publish(topic, bytes("kept"), 1, true, false, 0, null, null))
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            try (MqttClient cb = direct("b", b, 30, null)) {
                cb.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                cb.subscribe(prefix + "/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                await().atMost(WAIT).until(() -> b.messages.size() == 1);
                final Packet.Publish m = b.messages.getFirst();
                assertThat(m.topic()).isEqualTo(topic);
                assertThat(m.retain()).isTrue();
                assertThat(m.payload()).isEqualTo(bytes("kept"));
            } finally {
                ca.publish(new Packet.Publish(topic, new byte[0], 1, true, false, 0, null, null))
                        .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /// When a client's connection is cut without DISCONNECT, the broker publishes its will, with the will's QoS,
    /// response topic and correlation data; after a client's own DISCONNECT it does not.
    @Test
    void theWillIsPublishedWhenTheConnectionIsCutAndNotAfterADisconnect() throws Exception {
        final Recorder watcher = new Recorder();
        final Recorder cut = new Recorder();
        cut.retry = null;
        final Packet.Publish will = new Packet.Publish(prefix + "/will/cut", bytes("gone"), 1, false, false, 0,
                prefix + "/will/reply", bytes("w-1"));
        try (Relay relay = new Relay(host, port);
                MqttClient w = direct("watcher", watcher, 30, null);
                MqttClient c = relayed(relay, "cut", cut, 30, will);
                MqttClient polite = direct("polite", new Recorder(), 30, message(prefix + "/will/polite", "gone", 1))) {
            w.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            w.subscribe(prefix + "/will/+", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            c.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            polite.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

            relay.cut();
            await().atMost(WAIT).until(() -> watcher.messages.size() == 1);
            final Packet.Publish m = watcher.messages.getFirst();
            assertThat(m.topic()).isEqualTo(will.topic());
            assertThat(m.payload()).isEqualTo(bytes("gone"));
            assertThat(m.qos()).isEqualTo(1);
            assertThat(m.responseTopic()).isEqualTo(will.responseTopic());
            assertThat(m.correlation()).isEqualTo(will.correlation());
            await().atMost(WAIT).until(() -> cut.causes.size() == 1);
            assertThat(c.connected()).isFalse();

            polite.disconnect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            // published after the disconnect, so it arrives after a will the broker would have sent
            w.publish(message(prefix + "/will/after", "after", 1)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> watcher.messages.size() >= 2);
            assertThat(watcher.messages.stream().map(Packet.Publish::topic))
                    .containsExactly(will.topic(), prefix + "/will/after");
        }
    }

    /// With a two-second keep alive and nothing else to send, the client pings the broker within each interval and the
    /// broker answers, so the connection, which the broker would close after one and a half silent intervals, stays up
    /// across three of them.
    @Test
    void pingsKeepAnIdleConnectionOpenAcrossSeveralKeepAliveIntervals() throws Exception {
        final Recorder recorder = new Recorder();
        try (Relay relay = new Relay(host, port); MqttClient client = relayed(relay, "pings", recorder, 2, null)) {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> relay.fromClient(PINGREQ) >= 3 && relay.fromBroker(PINGRESP) >= 3);
            assertThat(client.connected()).isTrue();
            assertThat(recorder.connects).hasValue(1);
            assertThat(recorder.causes).isEmpty();
            assertThat(relay.links()).isEqualTo(1);
        }
    }

    /// Payloads of 0 bytes, 1 byte, 64 KiB, 1 MiB and 5 MiB arrive whole at QoS 0 and at QoS 1: no limit below the
    /// protocol's is announced, so the broker drops none of them.
    @Test
    void payloadsFromEmptyToFiveMebibytesArriveWhole() throws Exception {
        final Recorder a = new Recorder();
        final Recorder b = new Recorder();
        final int[] sizes = {0, 1, 65_536, 1_048_576, 5 * 1_048_576};
        try (MqttClient ca = direct("a", a, 30, null); MqttClient cb = direct("b", b, 30, null)) {
            ca.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            cb.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            cb.subscribe(prefix + "/payload/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            for (final int size : sizes) {
                for (int qos = 0; qos <= 1; qos++) {
                    ca.publish(new Packet.Publish(prefix + "/payload/" + size + '/' + qos, payload(size), qos, false,
                            false, 0, null, null)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
            await().atMost(WAIT).until(() -> b.messages.size() == sizes.length * 2);
            final Map<String, Packet.Publish> got = byTopic(b.messages);
            for (final int size : sizes) {
                for (int qos = 0; qos <= 1; qos++) {
                    final Packet.Publish m = got.get(prefix + "/payload/" + size + '/' + qos);
                    assertThat(m).as("size %d qos %d", size, qos).isNotNull();
                    assertThat(m.qos()).isEqualTo(qos);
                    assertThat(m.payload()).as("size %d qos %d", size, qos).isEqualTo(payload(size));
                }
            }
        }
    }

    private MqttClient direct(final String name, final Recorder recorder, final int keepAliveSeconds,
            final Packet.Publish will) {
        return new MqttClient(new MqttClient.Options(host, port, run + '-' + name, keepAliveSeconds,
                MAXIMUM_PACKET_SIZE, will), recorder, callbacks);
    }

    private MqttClient relayed(final Relay relay, final String name, final Recorder recorder,
            final int keepAliveSeconds, final Packet.Publish will) {
        return new MqttClient(new MqttClient.Options("127.0.0.1", relay.port(), run + '-' + name, keepAliveSeconds,
                MAXIMUM_PACKET_SIZE, will), recorder, callbacks);
    }

    private static Map<String, Packet.Publish> byTopic(final List<Packet.Publish> messages) {
        return messages.stream().collect(Collectors.toMap(Packet.Publish::topic, Function.identity()));
    }

    private static Packet.Publish message(final String topic, final String text, final int qos) {
        return new Packet.Publish(topic, bytes(text), qos, false, false, 0, null, null);
    }

    /// `size` bytes in a pattern that a shifted or truncated copy does not match.
    private static byte[] payload(final int size) {
        final byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i * 31 + i / 251);
        }
        return b;
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
