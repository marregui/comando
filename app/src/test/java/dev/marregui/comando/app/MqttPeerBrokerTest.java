package dev.marregui.comando.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.app.mqtt.FakeBroker;
import dev.marregui.comando.app.mqtt.MqttClient;
import dev.marregui.comando.app.mqtt.Packet;
import dev.marregui.comando.app.mqtt.Relay;
import dev.marregui.comando.mcp.PeerBroker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The instances' broker on comando's own MQTT client (record 123): its message mapping and handler routing without
/// a broker, its connect, will, resubscription, publish and following of the Edge against a fake broker on a local
/// port, its renewal after a cut against the real broker `COMANDO_MQTT_BROKER` names, and one round trip through the
/// running comando Edge when `-PedgeLiveTest=true` and one runs.
class MqttPeerBrokerTest {

    private static final String INBOX = "comando/v1/local/instance/b/inbox";
    private static final String REPLY = "comando/v1/local/instance/main/reply";
    private static final String PRESENCE = "comando/v1/local/instance/b/presence";
    private static final String OTHER_INBOX = "comando/v1/local/instance/c/inbox";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static FakeBroker fake;

    @BeforeAll
    static void startFake() {
        fake = new FakeBroker();
    }

    @AfterAll
    static void stopFake() {
        if (fake != null) {
            fake.close();
        }
    }

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    /// A publish carries its payload, response topic and correlation data into the client's message at least once and
    /// unretained, and reads back out of it unchanged; a retained presence with no payload, response topic or
    /// correlation reads back empty and null.
    @Test
    void aQuestionKeepsItsResponseTopicAndCorrelationDataThroughTheClientsTypes() {
        final Packet.Publish p = MqttPeerBroker.publishOf(INBOX, bytes("{\"text\":\"what is X?\"}"), false, REPLY,
                bytes("id-1"));
        assertThat(p.qos()).isEqualTo(1);
        assertThat(p.retain()).isFalse();
        final PeerBroker.Message m = MqttPeerBroker.message(p);
        assertThat(m.topic()).isEqualTo(INBOX);
        assertThat(m.payload()).isEqualTo(bytes("{\"text\":\"what is X?\"}"));
        assertThat(m.responseTopic()).isEqualTo(REPLY);
        assertThat(m.correlation()).isEqualTo(bytes("id-1"));

        final PeerBroker.Message presence = MqttPeerBroker.message(MqttPeerBroker.publishOf(PRESENCE, new byte[0],
                true, null, null));
        assertThat(presence.payload()).isEmpty();
        assertThat(presence.responseTopic()).isNull();
        assertThat(presence.correlation()).isNull();
    }

    /// A delivered message goes to the handlers whose topic filter matches it, wildcard or exact, and to no other:
    /// presence reaches the presence handler, the inbox message the inbox handler, and a third instance's inbox
    /// neither.
    @Test
    void aMessageReachesOnlyTheHandlersWhoseFiltersMatchItsTopic() {
        try (MqttPeerBroker broker = new MqttPeerBroker("comando-test", 1, () -> false)) {
            final List<String> presence = new CopyOnWriteArrayList<>();
            final List<String> inbox = new CopyOnWriteArrayList<>();
            broker.subscribe("comando/v1/local/instance/+/presence", m -> presence.add(m.topic()));
            broker.subscribe(INBOX, m -> inbox.add(m.topic()));
            broker.deliver(MqttPeerBroker.publishOf(PRESENCE, bytes("{}"), true, null, null));
            broker.deliver(MqttPeerBroker.publishOf(INBOX, bytes("{}"), false, REPLY, bytes("id-1")));
            broker.deliver(MqttPeerBroker.publishOf(OTHER_INBOX, bytes("{}"), false, null,
                    null));
            assertThat(presence).containsExactly(PRESENCE);
            assertThat(inbox).containsExactly(INBOX);
        }
    }

    /// While the Edge is not running, following asks the question each time and never connects, and a publish fails at
    /// once instead of waiting; a second close does nothing.
    @Test
    void withNoEdgeRunningItNeverConnectsAndAPublishFailsAtOnce() {
        final AtomicInteger looked = new AtomicInteger();
        final MqttPeerBroker broker = new MqttPeerBroker("comando-test", 1, () -> {
            looked.incrementAndGet();
            return false;
        });
        try {
            broker.follow();
            broker.follow();
            assertThat(looked).hasValue(2);
            assertThat(broker.connected()).isFalse();
            assertThat(broker.publish(INBOX, bytes("{}"), false, REPLY, bytes("id-1"))).isCompletedExceptionally();
            broker.close();
        } finally {
            broker.close();
        }
        assertThat(fake.accepted()).isZero();
    }

    /// The reconnect delay doubles from one second (1, 2, 4) and then settles at the follow interval, however many
    /// attempts have failed.
    @Test
    void aDroppedConnectionIsRetriedSoonThenAtMostEveryFiveSeconds() {
        assertThat(MqttPeerBroker.reconnectDelay(0)).isEqualTo(1_000);
        assertThat(MqttPeerBroker.reconnectDelay(1)).isEqualTo(2_000);
        assertThat(MqttPeerBroker.reconnectDelay(2)).isEqualTo(4_000);
        assertThat(MqttPeerBroker.reconnectDelay(3)).isEqualTo(MqttPeerBroker.FOLLOW_EVERY.toMillis());
        assertThat(MqttPeerBroker.reconnectDelay(1_000)).isEqualTo(MqttPeerBroker.FOLLOW_EVERY.toMillis());
    }

    /// Following a running Edge connects with the client id, a 60 s keep alive and the will as a retained QoS 1
    /// message; the connect listeners run, then the subscriptions made before are sent; after the broker drops the
    /// connection the client is back about a second later, and the listeners and subscriptions run again.
    @Test
    void itConnectsWithItsWillAndRenewsListenersAndSubscriptionsAfterADrop() {
        final AtomicInteger connects = new AtomicInteger();
        try (MqttPeerBroker broker = new MqttPeerBroker("comando-a", fake.port(), () -> true)) {
            broker.will(PRESENCE, bytes("{\"state\":\"gone\"}"), true);
            broker.onConnected(connects::incrementAndGet);
            broker.subscribe(INBOX, _ -> { });
            broker.follow();
            await().atMost(WAIT).until(() -> fake.received(Packet.Subscribe.class).size() == 1);
            assertThat(broker.connected()).isTrue();
            assertThat(connects).hasValue(1);
            final Packet.Connect c = fake.received(Packet.Connect.class).getFirst();
            assertThat(c.clientId()).isEqualTo("comando-a");
            assertThat(c.keepAliveSeconds()).isEqualTo(60);
            assertThat(c.cleanStart()).isTrue();
            assertThat(c.will()).isEqualTo(new Packet.Publish(PRESENCE, bytes("{\"state\":\"gone\"}"), 1, true, false, 0,
                    null, null));
            assertThat(fake.received(Packet.Subscribe.class).getFirst().subscriptions())
                    .containsExactly(new Packet.Subscription(INBOX, 1));

            fake.drop();
            await().atMost(WAIT).until(() -> fake.received(Packet.Subscribe.class).size() == 2);
            assertThat(connects).hasValue(2);
            assertThat(fake.accepted()).isEqualTo(2);
            assertThat(broker.connected()).isTrue();
        }
    }

    /// A publish completes when the broker acknowledges it, carrying its response topic and correlation data; messages
    /// in reach the subscribed handler in the order the broker sent them.
    @Test
    void aPublishCompletesOnItsAcknowledgementAndMessagesArriveInOrder() throws Exception {
        fake.ackPublishes(false);
        final List<String> heard = new CopyOnWriteArrayList<>();
        try (MqttPeerBroker broker = new MqttPeerBroker("comando-a", fake.port(), () -> true)) {
            broker.subscribe(INBOX, m -> heard.add(new String(m.payload(), StandardCharsets.UTF_8)));
            broker.follow();
            await().atMost(WAIT).until(broker::connected);
            final CompletableFuture<Void> sent = broker.publish(OTHER_INBOX, bytes("q"), false, REPLY, bytes("id-1"));
            await().atMost(WAIT).until(() -> fake.received(Packet.Publish.class).size() == 1);
            final Packet.Publish out = fake.received(Packet.Publish.class).getFirst();
            assertThat(out.responseTopic()).isEqualTo(REPLY);
            assertThat(out.correlation()).isEqualTo(bytes("id-1"));
            assertThat(sent).isNotDone();
            fake.send(new Packet.PubAck(out.packetId(), Packet.SUCCESS, null));
            sent.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

            for (int i = 1; i <= 20; i++) {
                fake.send(new Packet.Publish(INBOX, bytes("m" + i), 1, false, false, 0, null, null));
            }
            await().atMost(WAIT).until(() -> heard.size() == 20);
            assertThat(heard).containsExactlyElementsOf(IntStream.rangeClosed(1, 20)
                    .mapToObj(i -> "m" + i).toList());
        }
    }

    /// Once the Edge stops running, following disconnects with DISCONNECT Normal, so the broker drops the will, and a
    /// publish fails at once.
    @Test
    void whenTheEdgeStopsItDisconnects() {
        final AtomicBoolean up = new AtomicBoolean(true);
        try (MqttPeerBroker broker = new MqttPeerBroker("comando-a", fake.port(), up::get)) {
            broker.follow();
            await().atMost(WAIT).until(broker::connected);
            up.set(false);
            broker.follow();
            await().atMost(WAIT).until(() -> !fake.received(Packet.Disconnect.class).isEmpty());
            assertThat(fake.received(Packet.Disconnect.class)).containsExactly(new Packet.Disconnect(Packet.SUCCESS,
                    null));
            assertThat(broker.connected()).isFalse();
            assertThat(broker.publish(INBOX, bytes("{}"), false, null, null)).isCompletedExceptionally();
        }
    }

    /// Against the running comando Edge, with `-PedgeLiveTest=true`: a message published with a response topic and
    /// correlation data comes back to its own subscription unchanged. Skipped when the flag is unset or no Edge runs.
    @Test
    void aMessageRoundTripsThroughTheRunningEdge(@TempDir final Path dir) {
        Assumptions.assumeTrue(Boolean.getBoolean("comando.edgeLiveTest"),
                "set -PedgeLiveTest=true to run against the comando Edge");
        final EmbeddedEdge edge = new EmbeddedEdge(dir.resolve("edge"), dir, "", _ -> { });
        Assumptions.assumeTrue(edge.running(), "no comando Edge runs: start one in comando first");
        final String id = "comando-test-" + ProcessHandle.current().pid() + '-' + System.nanoTime();
        final String topic = "comando-test/" + id + "/echo";
        final List<PeerBroker.Message> heard = new CopyOnWriteArrayList<>();
        try (MqttPeerBroker broker = new MqttPeerBroker(id, EmbeddedEdge.Ports.DEFAULT.mqtt(), edge::running)) {
            broker.subscribe(topic, heard::add);
            broker.follow();
            await().atMost(Duration.ofSeconds(30)).until(broker::connected);
            // the subscription is granted some time after the connect; publish until one comes back
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> {
                broker.publish(topic, bytes("ping"), false, topic + "/reply", bytes(id));
                return !heard.isEmpty();
            });
            final PeerBroker.Message m = heard.getFirst();
            assertThat(m.payload()).isEqualTo(bytes("ping"));
            assertThat(m.responseTopic()).isEqualTo(topic + "/reply");
            assertThat(m.correlation()).isEqualTo(bytes(id));
        }
    }

    /// Against the broker the environment variable `COMANDO_MQTT_BROKER` names as `host:port`, skipped without it,
    /// through a relay whose link is cut as a failed network would cut it: the broker publishes the will, the client is
    /// back about a second later, the connect listeners run again, and the subscription is renewed, so a message
    /// published after the cut arrives.
    @Test
    void aCutConnectionToARealBrokerIsRenewedAndItsWillPublished() throws Exception {
        final String at = System.getenv("COMANDO_MQTT_BROKER");
        Assumptions.assumeTrue(at != null && at.lastIndexOf(':') > 0,
                "set COMANDO_MQTT_BROKER=host:port to run against a broker");
        final String host = at.substring(0, at.lastIndexOf(':'));
        final int port = Integer.parseInt(at.substring(at.lastIndexOf(':') + 1));
        final String id = "comando-interop-" + ProcessHandle.current().pid() + '-' + System.nanoTime();
        final String inbox = "comando-interop/" + id + "/inbox";
        final String presence = "comando-interop/" + id + "/presence";
        final List<String> heard = new CopyOnWriteArrayList<>();
        final List<Packet.Publish> watched = new CopyOnWriteArrayList<>();
        final AtomicInteger connects = new AtomicInteger();
        final MqttClient.Handler watching = new MqttClient.Handler() {

            @Override
            public void connected() {
                // the watcher connects once; nothing to renew
            }

            @Override
            public void message(final Packet.Publish message) {
                watched.add(message);
            }

            @Override
            public Duration disconnected(final int attempts, final Throwable cause) {
                return null;
            }
        };
        try (Relay relay = new Relay(host, port);
                MqttPeerBroker broker = new MqttPeerBroker(id, relay.port(), () -> true);
                MqttClient watcher = new MqttClient(new MqttClient.Options(host, port, id + "-watcher", 30,
                        MqttPeerBroker.MAXIMUM_PACKET_SIZE, null), watching, Runnable::run)) {
            watcher.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            watcher.subscribe(presence, 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            broker.will(presence, bytes("{\"state\":\"gone\"}"), false);
            broker.onConnected(connects::incrementAndGet);
            broker.subscribe(inbox, m -> heard.add(new String(m.payload(), StandardCharsets.UTF_8)));
            broker.follow();
            await().atMost(WAIT).until(() -> broker.connected() && connects.get() == 1);
            // the subscription is granted some time after the connect; publish until one comes back
            await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
                watcher.publish(new Packet.Publish(inbox, bytes("before"), 1, false, false, 0, null, null));
                return heard.contains("before");
            });

            relay.cut();
            await().atMost(WAIT).until(() -> watched.size() == 1);
            assertThat(watched.getFirst().payload()).isEqualTo(bytes("{\"state\":\"gone\"}"));
            await().atMost(WAIT).until(() -> broker.connected() && connects.get() == 2);
            await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
                watcher.publish(new Packet.Publish(inbox, bytes("after"), 1, false, false, 0, null, null));
                return heard.contains("after");
            });
            assertThat(relay.links()).isEqualTo(2);
        }
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
