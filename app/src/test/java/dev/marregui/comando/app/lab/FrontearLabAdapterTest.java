package dev.marregui.comando.app.lab;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.port.LabPort;
import dev.marregui.comando.core.port.LabPort.Frame;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Link;
import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The lab's drain on a real node: two frontear nodes in a temporary folder, on ports of their own, linked by a
/// signed `PutLink` on a for topics `t/#`; a client on a publishes 20 QoS 1 messages a subscriber on b takes. The
/// drain of a holds a Published for each, the link's APPENDED and SENT of each, and its links once drained show the
/// cursor at the tail.
class FrontearLabAdapterTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final int MESSAGES = 20;

    private static ExecutorService callbacks;
    private static ExecutorService edt;

    @BeforeAll
    static void startStrands() {
        callbacks = Threads.strand("frontear-lab-test-callbacks");
        edt = Threads.strand("frontear-lab-test-edt");
    }

    @AfterAll
    static void stopStrands() {
        ExecutorStop.now("frontear-lab-test", callbacks, edt);
    }

    /// Twenty publishes on a, linked to b: a's drain window holds the 20 Published of `t/` topics from client pub,
    /// 20 APPENDED and 20 SENT on link b, and a's node shows link b connected with its cursor at its tail.
    @Test
    void theDrainOfALinkedNodeHoldsItsPublishesAndTheirLinkSteps(@TempDir final Path dir) throws Exception {
        final KeyPair pod = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Signer signer = new Signer(pod.getPrivate(), Signer.keyId(pod.getPublic()));
        final List<Packet.Publish> replies = new CopyOnWriteArrayList<>();
        final List<Packet.Publish> onB = new CopyOnWriteArrayList<>();
        try (Frontear b = Frontear.start("b", 0, dir.resolve("b"), Set.of(pod.getPublic()), InstantSource.system(),
                true);
                Frontear a = Frontear.start("a", 0, dir.resolve("a"), Set.of(pod.getPublic()),
                        InstantSource.system(), true);
                MqttClient controller = client(a.port(), "control", replies);
                MqttClient subscriber = client(b.port(), "reader", onB);
                MqttClient publisher = client(a.port(), "pub", new CopyOnWriteArrayList<>());
                LabDrain drain = new LabDrain(new FrontearSource(() -> a), edt, PacketSink.off())) {
            for (final MqttClient c : List.of(controller, subscriber, publisher)) {
                c.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
            controller.subscribe("replies/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            subscriber.subscribe("t/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            putLink(controller, signer, replies, b.port());
            await().atMost(WAIT).until(() -> b.broker().stats().connections() == 2);
            drain.open();
            await().atMost(WAIT).until(() -> a.watch().on());

            for (int i = 0; i < MESSAGES; i++) {
                assertThat(publisher.publishAcknowledged(new Packet.Publish(
                        "t/" + i, ("m" + i).getBytes(StandardCharsets.UTF_8), 1, false, false, 0, null, null))
                        .get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                        .isEqualTo(Packet.SUCCESS);
            }
            await().atMost(WAIT).until(() -> onB.size() == MESSAGES);
            await().atMost(WAIT).until(() -> drained(drain.read(0L, 0)));
            await().atMost(WAIT).until(() -> steps(drain.read(0L, LabWindow.CAPACITY), Kind.SENT).size() == MESSAGES);

            final Frame f = drain.read(0L, LabWindow.CAPACITY);
            assertThat(f.packets()).filteredOn(p -> p.kind() == Kind.PUBLISHED && p.topic().startsWith("t/"))
                    .hasSize(MESSAGES).allSatisfy(p -> {
                        assertThat(p.origin()).isEqualTo("pub");
                        assertThat(p.qos()).isEqualTo(1);
                        assertThat(p.link()).isEmpty();
                    });
            final List<LabPort.Packet> appended = steps(f, Kind.APPENDED);
            assertThat(appended).hasSize(MESSAGES).extracting(LabPort.Packet::offset).isSorted()
                    .doesNotHaveDuplicates();
            assertThat(steps(f, Kind.SENT)).extracting(LabPort.Packet::offset)
                    .containsExactlyInAnyOrderElementsOf(appended.stream().map(LabPort.Packet::offset).toList());
            assertThat(f.missed()).isZero();
            assertThat(f.node().name()).isEqualTo("a");
            assertThat(f.node().port()).isEqualTo(a.port());
            assertThat(f.node().counts().ringMissed()).isZero();
            assertThat(f.node().counts().windowDropped()).isZero();
            final Link link = f.node().links().getFirst();
            assertThat(link.id()).isEqualTo("b");
            assertThat(link.connected()).isTrue();
            assertThat(link.cursor()).isEqualTo(link.tail());
            assertThat(f.node().logs()).singleElement().satisfies(v -> assertThat(v.cursor()).isEqualTo(v.tail()));
        }
    }

    /// While the instance hosts no node the drain reads nothing and shows no node.
    @Test
    void withNoNodeTheDrainShowsNone() {
        try (LabDrain drain = new LabDrain(new FrontearSource(() -> null), edt, PacketSink.off())) {
            drain.open();
            await().atMost(WAIT).until(() -> Threads.alive("frontear-lab-drain").size() == 1);
            final Frame f = drain.read(0L, 10);
            assertThat(f.node().up()).isFalse();
            assertThat(f.packets()).isEmpty();
            assertThat(f.last()).isZero();
        }
    }

    private static boolean drained(final Frame f) {
        return f.node().links().size() == 1 && f.node().links().getFirst().cursor() == f.node().links().getFirst()
                .tail() && f.node().links().getFirst().tail() >= MESSAGES;
    }

    private static List<LabPort.Packet> steps(final Frame f, final Kind kind) {
        return f.packets().stream().filter(p -> p.kind() == kind && p.link().equals("b")).toList();
    }

    private static void putLink(
            final MqttClient controller,
            final Signer signer,
            final List<Packet.Publish> replies,
            final int port
    ) throws Exception {
        final String link = "{\"id\":\"b\",\"host\":\"127.0.0.1\",\"port\":" + port
                + ",\"out\":[\"t/#\"],\"in\":[],\"hopLimit\":3}";
        assertThat(controller.publishAcknowledged(signer.sign("$frontear/control/a/PutLink",
                link.getBytes(StandardCharsets.UTF_8), "replies/b", new byte[] {1}))
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(Packet.SUCCESS);
        await().atMost(WAIT).until(() -> !replies.isEmpty());
        assertThat(new String(replies.getFirst().payload(), StandardCharsets.UTF_8)).contains("\"ok\":true");
    }

    private static MqttClient client(
            final int port,
            final String id,
            final List<Packet.Publish> into
    ) {
        return new MqttClient(new MqttClient.Options("127.0.0.1", port, id, 0,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null),
                new MqttClient.Handler() {

                    @Override
                    public void connected() {
                    }

                    @Override
                    public void message(final Packet.Publish message) {
                        into.add(message);
                    }

                    @Override
                    public Duration disconnected(final int attempts, final Throwable cause) {
                        return null;
                    }
                }, callbacks);
    }
}
