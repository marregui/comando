package dev.marregui.comando.frontear.watch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
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
import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Two frontear nodes linked as the stress run links them (a signed `PutLink` on a, the link named for b, both nodes
/// refusing a link aimed elsewhere), watched while a client on a publishes QoS 1 messages a subscriber on b takes: what
/// each node's watch keeps, and what each node's snapshot shows once the link has drained.
class WatchNodeTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    // how long a refused link is watched for a connection it must never make: several of its backoff rounds
    private static final Duration REFUSED_FOR = Duration.ofSeconds(2);
    private static final int MESSAGES = 100;

    private static ExecutorService callbacks;

    @BeforeAll
    static void startCallbacks() {
        callbacks = Threads.strand("watch-node-test-callbacks");
    }

    @AfterAll
    static void stopCallbacks() {
        ExecutorStop.now("watch-node-test", callbacks);
    }

    /// 100 QoS 1 messages over the link: a keeps a Published for each, then the link's APPENDED at increasing offsets
    /// and SENT for each offset, and a force round per log round, each with records; b keeps the ARRIVED of each from
    /// node a, published with one hop. Once drained, a's snapshot shows the link connected with its cursor at its
    /// tail, in its state and in its log's view, and b's shows the link's client connection.
    @Test
    void aWatchedLinkShowsEveryStepOfItsMessages(@TempDir final Path dir) throws Exception {
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
                MqttClient publisher = client(a.port(), "pub", new CopyOnWriteArrayList<>())) {
            connect(controller, subscriber, publisher);
            controller.subscribe("replies/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            subscriber.subscribe("t/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            putLink(controller, signer, replies, b.port());
            await().atMost(WAIT).until(() -> b.broker().stats().connections() == 2);
            a.watch().on(true);
            b.watch().on(true);

            for (int i = 0; i < MESSAGES; i++) {
                assertThat(publisher.publishAcknowledged(new Packet.Publish("t/" + i, ("m" + i).getBytes(
                        StandardCharsets.UTF_8), 1, false, false, 0, null, null))
                        .get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            }
            await().atMost(WAIT).until(() -> onB.size() == MESSAGES);
            await().atMost(WAIT).until(() -> drained(a.snapshot()));

            final List<Observation> onA = all(a.watch());
            final List<Published> published = only(onA, Published.class).stream()
                    .filter(p -> p.topic().startsWith("t/")).toList();
            assertThat(published).hasSize(MESSAGES).allSatisfy(p -> {
                assertThat(p.origin()).isEqualTo("pub");
                assertThat(p.qos()).isEqualTo(1);
                assertThat(p.hops()).isZero();
                assertThat(p.matches()).isPositive();
            });
            final List<LinkStep> appended = steps(onA, "b", LinkStep.Kind.APPENDED);
            assertThat(appended).hasSize(MESSAGES);
            assertThat(appended).extracting(LinkStep::offset).isSorted().doesNotHaveDuplicates();
            final List<LinkStep> sent = steps(onA, "b", LinkStep.Kind.SENT);
            assertThat(sent).extracting(LinkStep::offset)
                    .containsExactlyInAnyOrderElementsOf(appended.stream().map(LinkStep::offset).toList());
            assertThat(sent).allSatisfy(s -> assertThat(s.topic()).startsWith("t/"));
            final List<ForceRound> rounds = only(onA, ForceRound.class).stream()
                    .filter(r -> r.log().equals("b")).toList();
            assertThat(rounds).isNotEmpty().allSatisfy(r -> {
                assertThat(r.records()).isPositive();
                assertThat(r.bytes()).isPositive();
                assertThat(r.nanos()).isPositive();
            });
            assertThat(rounds.stream().mapToInt(ForceRound::records).sum()).isEqualTo(MESSAGES);

            final List<Observation> onBWatch = all(b.watch());
            assertThat(steps(onBWatch, "a", LinkStep.Kind.ARRIVED)).hasSize(MESSAGES)
                    .allSatisfy(s -> assertThat(s.topic()).startsWith("t/"));
            assertThat(only(onBWatch, Published.class)).filteredOn(p -> p.topic().startsWith("t/")).hasSize(MESSAGES)
                    .allSatisfy(p -> {
                        assertThat(p.origin()).isEqualTo("a~b");
                        assertThat(p.hops()).isEqualTo(1);
                    });

            final NodeSnapshot snapA = a.snapshot();
            assertThat(snapA.node()).isEqualTo("a");
            assertThat(snapA.port()).isEqualTo(a.port());
            assertThat(snapA.links()).singleElement().satisfies(l -> {
                assertThat(l.id()).isEqualTo("b");
                assertThat(l.connected()).isTrue();
                assertThat(l.cursor()).isEqualTo(l.tail());
                assertThat(l.tail()).isGreaterThanOrEqualTo(MESSAGES);
            });
            assertThat(snapA.logs()).singleElement().satisfies(v -> {
                assertThat(v.link()).isEqualTo("b");
                assertThat(v.cursor()).isEqualTo(v.tail());
                assertThat(v.bytes()).isPositive();
                assertThat(v.segments()).isPositive();
            });
            assertThat(snapA.connections()).extracting(ConnectionView::clientId).contains("control", "pub");
            final NodeSnapshot snapB = b.snapshot();
            assertThat(snapB.connections()).extracting(ConnectionView::clientId).containsExactly("a~b", "reader");
            assertThat(snapB.connections()).allSatisfy(c -> {
                assertThat(c.receiveMaximum()).isPositive();
                assertThat(c.sinceMillis()).isPositive();
            });
            assertThat(snapB.broker().connections()).isEqualTo(2);
        }
    }

    /// A link named for b but aimed at node c's port: c refuses its client id with 0x85, so it never connects there,
    /// its watch says why each time it is refused, and a message for its filter stays in a's log, delivered to no
    /// subscriber on c.
    @Test
    void aLinkAimedAtTheWrongNodeNeverConnectsAndDeliversNothing(@TempDir final Path dir) throws Exception {
        final KeyPair pod = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Signer signer = new Signer(pod.getPrivate(), Signer.keyId(pod.getPublic()));
        final List<Packet.Publish> replies = new CopyOnWriteArrayList<>();
        final List<Packet.Publish> onC = new CopyOnWriteArrayList<>();
        try (Frontear c = Frontear.start("c", 0, dir.resolve("c"), Set.of(pod.getPublic()), InstantSource.system(),
                true);
                Frontear a = Frontear.start("a", 0, dir.resolve("a"), Set.of(pod.getPublic()),
                        InstantSource.system(), true);
                MqttClient controller = client(a.port(), "control", replies);
                MqttClient subscriber = client(c.port(), "reader", onC);
                MqttClient publisher = client(a.port(), "pub", new CopyOnWriteArrayList<>())) {
            connect(controller, subscriber, publisher);
            controller.subscribe("replies/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            subscriber.subscribe("t/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            a.watch().on(true);
            putLink(controller, signer, replies, c.port());
            await().atMost(WAIT).until(() -> !refusals(a.watch()).isEmpty());
            assertThat(refusals(a.watch())).allSatisfy(s -> assertThat(s.detail()).contains("it is not node b",
                    "clientId=a~b", "reasonCode=0x85"));

            assertThat(publisher.publishAcknowledged(new Packet.Publish("t/x", "lost".getBytes(
                    StandardCharsets.UTF_8), 1, false, false, 0, null, null))
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            await().during(REFUSED_FOR).atMost(REFUSED_FOR.plus(WAIT))
                    .until(() -> onC.isEmpty() && c.broker().stats().connections() == 1);
            final List<Observation> onA = all(a.watch());
            assertThat(steps(onA, "b", LinkStep.Kind.APPENDED)).hasSize(1);
            assertThat(steps(onA, "b", LinkStep.Kind.CONNECTED)).isEmpty();
            assertThat(steps(onA, "b", LinkStep.Kind.SENT)).isEmpty();
            assertThat(a.snapshot().links()).singleElement().satisfies(l -> {
                assertThat(l.connected()).isFalse();
                assertThat(l.cursor()).isLessThan(l.tail());
            });
            assertThat(c.snapshot().connections()).extracting(ConnectionView::clientId).containsExactly("reader");
        }
    }

    private static boolean drained(final NodeSnapshot s) {
        return s.links().size() == 1 && s.links().getFirst().cursor() == s.links().getFirst().tail()
                && s.logs().size() == 1 && s.logs().getFirst().cursor() == s.logs().getFirst().tail()
                && s.links().getFirst().tail() >= MESSAGES;
    }

    private static List<LinkStep> refusals(final Watch watch) {
        return steps(all(watch), "b", LinkStep.Kind.DISCONNECTED);
    }

    private static List<Observation> all(final Watch watch) {
        final List<Observation> events = new ArrayList<>();
        long last = 0;
        while (true) {
            final Watch.Batch batch = watch.since(last, Frontear.WATCH_CAPACITY);
            assertThat(batch.missed()).isZero();
            events.addAll(batch.events());
            if (batch.last() == last) {
                return events;
            }
            last = batch.last();
        }
    }

    private static <T extends Observation> List<T> only(final List<Observation> events, final Class<T> kind) {
        return events.stream().filter(kind::isInstance).map(kind::cast).toList();
    }

    private static List<LinkStep> steps(final List<Observation> events, final String link, final LinkStep.Kind kind) {
        return only(events, LinkStep.class).stream().filter(s -> s.link().equals(link) && s.kind() == kind).toList();
    }

    private static void putLink(final MqttClient controller, final Signer signer, final List<Packet.Publish> replies,
            final int port) throws Exception {
        final String link = "{\"id\":\"b\",\"host\":\"127.0.0.1\",\"port\":" + port
                + ",\"out\":[\"t/#\"],\"in\":[],\"hopLimit\":3}";
        assertThat(controller.publishAcknowledged(signer.sign("$frontear/control/a/PutLink",
                link.getBytes(StandardCharsets.UTF_8), "replies/b", new byte[] {1}))
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
        await().atMost(WAIT).until(() -> !replies.isEmpty());
        assertThat(new String(replies.getFirst().payload(), StandardCharsets.UTF_8)).contains("\"ok\":true");
    }

    private static void connect(final MqttClient... clients) throws Exception {
        for (final MqttClient client : clients) {
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static MqttClient client(final int port, final String id, final List<Packet.Publish> into) {
        return new MqttClient(new MqttClient.Options("127.0.0.1", port, id, 0, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE,
                null), new MqttClient.Handler() {

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
