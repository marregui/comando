package dev.marregui.comando.frontear.broker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.mqtt.MqttClient;
import dev.marregui.comando.frontear.mqtt.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Wills and their delay, the presence of each client, the Gate, internal subscribers, the broker's own publishes,
/// a clean close, and one round trip with the project's own client.
class BrokerLifecycleTest {

    private static final String PRESENCE = "$frontear/presence/";
    private static final byte[] ONLINE = "{\"online\":true}".getBytes(StandardCharsets.UTF_8);

    private TestBroker test;
    private TestBroker.Recording watch;

    @BeforeEach
    void start() throws IOException {
        test = TestBroker.start();
        watch = new TestBroker.Recording();
        test.broker.subscribe("watch", "will/#", 1, false, watch);
        test.broker.subscribe("watch", PRESENCE + "+", 1, false, watch);
    }

    @AfterEach
    void stop() {
        test.close();
    }

    /// A connection that drops without DISCONNECT publishes its will, from the client, before its offline presence.
    @Test
    void aDroppedConnectionPublishesItsWill() throws IOException {
        try (RawClient a = RawClient.open(test.port(), withWill("a", 0))) {
            a.read(Packet.ConnAck.class);
        }
        awaitOffline("a");
        assertThat(watch.topics()).containsExactly(PRESENCE + "a", "will/a", PRESENCE + "a");
        assertThat(watch.deliveries.get(1).origin()).isEqualTo("a");
    }

    /// DISCONNECT 0x00 discards the will; DISCONNECT 0x04 publishes it (§3.14.2.1).
    @Test
    void disconnectDecidesWhetherTheWillGoesOut() throws IOException {
        int presences = 0;
        for (final int code : List.of(Packet.SUCCESS, Packet.DISCONNECT_WITH_WILL)) {
            try (RawClient a = RawClient.open(test.port(), withWill("a", 0))) {
                a.read(Packet.ConnAck.class);
                a.send(new Packet.Disconnect(code, null));
                a.untilClosed();
            }
            presences += 2;
            awaitPresence("a", presences);
        }
        assertThat(watch.topics()).containsExactly(PRESENCE + "a", PRESENCE + "a", PRESENCE + "a", "will/a",
                PRESENCE + "a");
    }

    /// A client whose connection dropped and that returns to its session before the broker detached the old
    /// connection still has its will published and its presence cleared: the reconnect detaches the old connection
    /// by the cause it ended with. The old connection's reader is held, by a log handler, between ending the
    /// connection and detaching it, until the reconnect is accepted.
    @Test
    void aReconnectBeforeTheOldConnectionDetachedKeepsItsEnd() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean held = new AtomicBoolean();
        final Handler hold = new Handler() {
            @Override
            public void publish(final LogRecord entry) {
                final String m = entry.getMessage();
                if (m.startsWith("connection ended [client=a,") && m.contains("cause=WILL")
                        && held.compareAndSet(false, true)) {
                    entered.countDown();
                    try {
                        release.await(TestBroker.WAIT.toSeconds(), TimeUnit.SECONDS);
                    } catch (final InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        final Logger log = Logger.getLogger(Connection.class.getName());
        final Level level = log.getLevel();
        log.setLevel(Level.FINE);
        log.addHandler(hold);
        try {
            try (RawClient a = RawClient.open(test.port(), withWill("a", 0))) {
                a.read(Packet.ConnAck.class);
            }
            assertThat(entered.await(TestBroker.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            try (RawClient again = RawClient.connected(test.port(), RawClient.connect("a", false, 60))) {
                release.countDown();
                again.send(new Packet.Disconnect(Packet.SUCCESS, null));
                again.untilClosed();
            }
        } finally {
            release.countDown();
            log.removeHandler(hold);
            log.setLevel(level);
        }
        awaitPresence("a", 4);
        assertThat(watch.topics()).containsExactly(PRESENCE + "a", "will/a", PRESENCE + "a", PRESENCE + "a",
                PRESENCE + "a");
    }

    /// A will with a delay goes out once the delay has passed, not before (§3.1.3.2.2).
    @Test
    void aDelayedWillGoesOutAfterItsDelay() throws IOException {
        try (RawClient a = RawClient.open(test.port(), withWill("a", 5))) {
            a.read(Packet.ConnAck.class);
        }
        awaitOffline("a");
        test.sweepsDone();
        assertThat(watch.topics()).doesNotContain("will/a");
        test.clock.advance(Duration.ofSeconds(2));
        test.sweepsDone();
        assertThat(watch.topics()).contains("will/a");
    }

    /// A client that returns to its session within the will delay cancels the will.
    @Test
    void aReconnectWithinTheDelayCancelsTheWill() throws IOException {
        try (RawClient a = RawClient.open(test.port(), withWill("a", 5))) {
            a.read(Packet.ConnAck.class);
        }
        awaitOffline("a");
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect("a", false, 60))) {
            test.clock.advance(Duration.ofSeconds(10));
            test.sweepsDone();
            assertThat(a.ping()).isEmpty();
        }
        assertThat(watch.topics()).doesNotContain("will/a");
    }

    /// Each accepted client is online as a retained JSON message on its presence topic, cleared by an empty retained
    /// message when its connection ends, a takeover included, so a later subscriber sees who is connected.
    @Test
    void presenceIsRetainedOnlineAndClearedOnEnd() throws IOException {
        test.broker.gate(new AllowAll());
        try (RawClient a = RawClient.connected(test.port(), "a");
                RawClient again = RawClient.connected(test.port(), "a");
                RawClient s = RawClient.connected(test.port(), "s")) {
            assertThat(a.untilClosed()).containsExactly(new Packet.Disconnect(Packet.SESSION_TAKEN_OVER, null));
            assertThat(s.subscribe(new Packet.Subscription(PRESENCE + "a", 1))).containsExactly(1);
            final Packet.Publish online = s.receive();
            assertThat(online.retain()).isTrue();
            assertThat(online.payload()).isEqualTo(ONLINE);
            assertThat(online.meta().contentType()).isEqualTo("application/json");
            again.send(new Packet.Disconnect(Packet.SUCCESS, null));
            again.untilClosed();
            awaitPresence("a", 4);
            final Packet.Publish offline = s.receive();
            assertThat(offline.topic()).isEqualTo(PRESENCE + "a");
            assertThat(offline.payload()).isEmpty();
            s.subscribe(new Packet.Subscription(PRESENCE + "a", 1));
            assertThat(s.ping()).isEmpty();
        }
        assertThat(watch.deliveries.stream().filter(d -> d.message().topic().equals(PRESENCE + "a")).limit(4))
                .extracting(d -> d.message().payload().length).containsExactly(ONLINE.length, 0, ONLINE.length, 0);
    }

    /// The default Gate refuses clients the reserved `$frontear/` topics, to publish (PUBACK 0x87) and to subscribe
    /// (SUBACK 0x87), and a will there (CONNACK 0x87).
    @Test
    void theDefaultGateKeepsClientsOffTheReservedTopics() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            assertThat(c.publish(RawClient.qos1("$frontear/x", "m"))).isEqualTo(Packet.NOT_AUTHORIZED);
            assertThat(c.subscribe(new Packet.Subscription("$frontear/#", 1), new Packet.Subscription("t", 1)))
                    .containsExactly(Packet.NOT_AUTHORIZED, 1);
        }
    }

    /// An installed Gate decides for clients, and never for the broker's own publishes.
    @Test
    void anInstalledGateDecidesForClientsButNotForTheBroker() throws IOException {
        test.broker.gate(new Gate() {
            @Override
            public boolean mayPublish(final String clientId, final String topic) {
                return false;
            }

            @Override
            public boolean maySubscribe(final String clientId, final String filter) {
                return !filter.equals("secret");
            }
        });
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            assertThat(c.subscribe(new Packet.Subscription("secret", 1), new Packet.Subscription("t", 1)))
                    .containsExactly(Packet.NOT_AUTHORIZED, 1);
            assertThat(c.publish(RawClient.qos1("t", "m"))).isEqualTo(Packet.NOT_AUTHORIZED);
            assertThat(test.publish(RawClient.qos1("t", "from the broker").withPacketId(0), "test"))
                    .isEqualTo(Packet.SUCCESS);
            assertThat(c.receive().payload()).isEqualTo("from the broker".getBytes(StandardCharsets.UTF_8));
        }
    }

    /// An internal subscriber gets what matches its filters, its own messages kept from it under No Local by origin;
    /// subscribing again under the same id replaces it, and unsubscribing leaves no subscriber (0x10).
    @Test
    void internalSubscribersFollowTheirFiltersAndOptions() {
        final TestBroker.Recording first = new TestBroker.Recording();
        test.broker.subscribe("$link/x", "t/#", 1, true, first);
        test.broker.subscribe("$link/x", "u", 1, true, first);
        assertThat(publish("t/1", "$link/x")).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
        assertThat(publish("t/1", "other")).isEqualTo(Packet.SUCCESS);
        assertThat(first.deliveries).extracting(TestBroker.Delivery::origin).containsExactly("other");

        final TestBroker.Recording second = new TestBroker.Recording();
        second.code = Packet.QUOTA_EXCEEDED;
        test.broker.subscribe("$link/x", "t/#", 1, true, second);
        assertThat(publish("t/2", "other")).isEqualTo(Packet.QUOTA_EXCEEDED);
        assertThat(first.deliveries).hasSize(1);
        assertThat(second.topics()).containsExactly("t/2");

        test.broker.unsubscribe("$link/x", "t/#");
        assertThat(publish("t/3", "other")).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
        assertThat(publish("u", "other")).isEqualTo(Packet.QUOTA_EXCEEDED);
        test.broker.unsubscribeAll("$link/x");
        assertThat(publish("u", "other")).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
    }

    /// Closing sends DISCONNECT 0x8B to every connected client and nothing to one that never sent CONNECT, publishes
    /// no will, leaves none of the broker's threads alive, and a second close does nothing.
    @Test
    void closeDisconnectsEveryClientAndStopsEveryThread() throws IOException {
        final int port = test.port();
        try (RawClient a = RawClient.open(port, withWill("a", 0));
                RawClient silent = RawClient.open(port)) {
            a.read(Packet.ConnAck.class);
            test.close();
            assertThat(a.untilClosed()).containsExactly(new Packet.Disconnect(Packet.SERVER_SHUTTING_DOWN, null));
            assertThat(silent.untilClosed()).isEmpty();
        }
        test.close();
        final String names = "frontear-broker-[a-z]+-" + port + "(-[0-9]+)?";
        await().atMost(TestBroker.WAIT).until(() -> Threads.alive().stream()
                .noneMatch(t -> t.getName().matches(names)));
        assertThat(watch.topics()).doesNotContain("will/a");
    }

    /// The project's own client connects, subscribes, publishes at QoS 1 with its PUBACK, and receives what it
    /// published.
    @Test
    void theProjectsClientMakesARoundTrip() throws Exception {
        final List<Packet.Publish> received = new CopyOnWriteArrayList<>();
        final MqttClient.Handler handler = new MqttClient.Handler() {
            @Override
            public void connected() {
                // the test waits on the connect future
            }

            @Override
            public void message(final Packet.Publish message) {
                received.add(message);
            }

            @Override
            public Duration disconnected(final int attempts, final Throwable cause) {
                return null;
            }
        };
        try (MqttClient client = new MqttClient(new MqttClient.Options("127.0.0.1", test.port(), "m", 0,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), handler, Runnable::run)) {
            client.connect().get(TestBroker.WAIT.toSeconds(), TimeUnit.SECONDS);
            client.subscribe("t", 1).get(TestBroker.WAIT.toSeconds(), TimeUnit.SECONDS);
            final Packet.Publish m = new Packet.Publish("t", "hello".getBytes(StandardCharsets.UTF_8), 1, false,
                    false, 0, null, null);
            assertThat(client.publishAcknowledged(m).get(TestBroker.WAIT.toSeconds(), TimeUnit.SECONDS))
                    .isEqualTo(Packet.SUCCESS);
            await().atMost(TestBroker.WAIT).until(() -> !received.isEmpty());
            assertThat(received.getFirst().payload()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        }
    }

    /// A clean-start CONNECT for `clientId` with a session of 60 s and a QoS 1 will on `will/<clientId>` delayed by
    /// `delaySeconds`.
    private static Packet.Connect withWill(final String clientId, final long delaySeconds) {
        final Packet.Publish will = new Packet.Publish("will/" + clientId, new byte[] {1}, 1, false, false, 0, null,
                null);
        return new Packet.Connect(clientId, true, 0, 60, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, will, delaySeconds);
    }

    /// Publishes a QoS 1 message on `topic` from `origin` through the broker and returns its code.
    private int publish(final String topic, final String origin) {
        return test.publish(new Packet.Publish(topic, new byte[] {1}, 1, false, false, 0, null, null), origin);
    }

    /// Waits until the watcher saw `clientId`'s presence cleared.
    private void awaitOffline(final String clientId) {
        await().atMost(TestBroker.WAIT).until(() -> watch.deliveries.stream()
                .anyMatch(d -> d.message().topic().equals(PRESENCE + clientId) && d.message().payload().length == 0));
    }

    /// Waits until the watcher saw `count` presence messages of `clientId`, online or cleared.
    private void awaitPresence(final String clientId, final int count) {
        await().atMost(TestBroker.WAIT).until(() -> watch.topics().stream()
                .filter(t -> t.equals(PRESENCE + clientId)).count() >= count);
    }

    /// A Gate that lets every client publish and subscribe anywhere.
    private static final class AllowAll implements Gate {

        @Override
        public boolean mayPublish(final String clientId, final String topic) {
            return true;
        }

        @Override
        public boolean maySubscribe(final String clientId, final String filter) {
            return true;
        }
    }
}
