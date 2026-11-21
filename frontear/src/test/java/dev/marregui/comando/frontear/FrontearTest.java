package dev.marregui.comando.frontear;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.MqttException;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// Two frontear nodes on loopback, configured only by signed control messages: a control pod links node a to node b,
/// and a client's message on a reaches a subscriber on b, carried by the link's log.
class FrontearTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    // how long the held links are watched to deliver nothing: the link's first two tries, at once and after its 1 s
    // backoff, fall inside it
    private static final Duration HELD = Duration.ofMillis(1_500);

    /// A signed `PutLink` on a makes a link to b; a message a client publishes on a for the link's filter arrives at a
    /// client subscribed on b, once, with one hop counted. A second link from a to b connects beside the first rather
    /// than taking over its session, since each link has its own client id.
    @Test
    void aSignedPutLinkCarriesAClientsMessageFromOneNodeToAnother(@TempDir final Path dir) throws Exception {
        final KeyPair pod = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Signer signer = new Signer(pod.getPrivate(), Signer.keyId(pod.getPublic()));
        final List<Packet.Publish> onB = new CopyOnWriteArrayList<>();
        final List<Packet.Publish> replies = new CopyOnWriteArrayList<>();
        final ExecutorService callbacks = Threads.strand("frontear-test-callbacks");
        try (Frontear b = Frontear.start("b", 0, dir.resolve("b"), Set.of(pod.getPublic()), InstantSource.system());
                Frontear a = Frontear.start("a", 0, dir.resolve("a"), Set.of(pod.getPublic()), InstantSource.system());
                MqttClient onA = client(a.port(), "pod", replies, callbacks);
                MqttClient subscriber = client(b.port(), "reader", onB, callbacks)) {
            onA.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            subscriber.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            subscriber.subscribe("t/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            onA.subscribe("replies/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            for (final String link : List.of("l1", "l2")) {
                final String body = "{\"id\":\"" + link + "\",\"host\":\"127.0.0.1\",\"port\":" + b.port()
                        + ",\"out\":[\"" + (link.equals("l1") ? "t/#" : "u/#") + "\"],\"in\":[],\"hopLimit\":3}";
                assertThat(onA.publishAcknowledged(signer.sign("$frontear/control/a/PutLink",
                        body.getBytes(StandardCharsets.UTF_8), "replies/" + link, new byte[] {1}))
                        .get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            }
            await().atMost(WAIT).until(() -> replies.size() == 2);
            assertThat(replies).allSatisfy(r -> assertThat(new String(r.payload(), StandardCharsets.UTF_8))
                    .contains("\"ok\":true"));
            await().atMost(WAIT).until(() -> b.broker().stats().connections() == 3);

            onA.publishAcknowledged(new Packet.Publish("t/x", "hello".getBytes(StandardCharsets.UTF_8), 1, false,
                    false, 0, null, null)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> !onB.isEmpty());
            assertThat(onB).singleElement().satisfies(m -> {
                assertThat(new String(m.payload(), StandardCharsets.UTF_8)).isEqualTo("hello");
                assertThat(m.meta().userProperties()).contains(new Packet.UserProperty("frontear-hops", "1"));
            });
        } finally {
            ExecutorStop.now("frontear-test", callbacks);
        }
    }

    /// A node started with its links held takes a plain client but refuses a link client 0x88 (Server unavailable);
    /// a's link to it keeps trying after that refusal rather than giving up, so the message it queued meanwhile reaches
    /// b's subscriber, once, after b lets the links in, and a link client connects from then on.
    @Test
    void aNodeHoldingItsLinksRefusesThemUntilItOpensThemAndTheLinkThenDrains(@TempDir final Path dir)
            throws Exception {
        final KeyPair pod = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Signer signer = new Signer(pod.getPrivate(), Signer.keyId(pod.getPublic()));
        final List<Packet.Publish> onB = new CopyOnWriteArrayList<>();
        final List<Packet.Publish> replies = new CopyOnWriteArrayList<>();
        final ExecutorService callbacks = Threads.strand("frontear-test-callbacks");
        try (Frontear b = Frontear.start("b", 0, dir.resolve("b"), Set.of(pod.getPublic()), InstantSource.system(),
                true, true);
                Frontear a = Frontear.start("a", 0, dir.resolve("a"), Set.of(pod.getPublic()), InstantSource.system(),
                        true);
                MqttClient onA = client(a.port(), "pod", replies, callbacks);
                MqttClient subscriber = client(b.port(), "reader", onB, callbacks);
                MqttClient early = client(b.port(), "probe~b", new CopyOnWriteArrayList<>(), callbacks);
                MqttClient late = client(b.port(), "probe~b", new CopyOnWriteArrayList<>(), callbacks)) {
            subscriber.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThatThrownBy(() -> early.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(0x88));
            onA.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            onA.subscribe("replies/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            final String body = "{\"id\":\"b\",\"host\":\"127.0.0.1\",\"port\":" + b.port()
                    + ",\"out\":[\"t/#\"],\"in\":[],\"hopLimit\":1}";
            onA.publishAcknowledged(signer.sign("$frontear/control/a/PutLink", body.getBytes(StandardCharsets.UTF_8),
                    "replies/b", new byte[] {1})).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> replies.size() == 1);
            onA.publishAcknowledged(new Packet.Publish("t/x", "hello".getBytes(StandardCharsets.UTF_8), 1, false,
                    false, 0, null, null)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            subscriber.subscribe("t/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().during(HELD).atMost(WAIT).until(onB::isEmpty);
            assertThat(a.snapshot().links()).singleElement().satisfies(l -> assertThat(l.connected()).isFalse());

            b.openLinks();
            await().atMost(WAIT).until(() -> !onB.isEmpty());
            assertThat(onB).singleElement().satisfies(m -> {
                assertThat(new String(m.payload(), StandardCharsets.UTF_8)).isEqualTo("hello");
                assertThat(m.retain()).isFalse();
            });
            late.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            ExecutorStop.now("frontear-test", callbacks);
        }
    }

    /// Holding the links is refused on a node whose links do not name nodes: it is a mode of comando's nodes, whose
    /// link ids are node names.
    @Test
    void holdingTheLinksNeedsLinksThatNameNodes(@TempDir final Path dir) throws Exception {
        final KeyPair pod = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThatThrownBy(() -> Frontear.start("b", 0, dir.resolve("b"), Set.of(pod.getPublic()),
                InstantSource.system(), false, true)).isInstanceOf(IllegalArgumentException.class);
    }

    /// A trusted key file holds base64 of the key's X.509 encoding; anything else is refused naming the file.
    @Test
    void aKeyFileIsReadOrRefused(@TempDir final Path dir) throws Exception {
        final KeyPair pod = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Path good = Files.writeString(dir.resolve("pod.pub"),
                Base64.getEncoder().encodeToString(pod.getPublic().getEncoded()) + "\n");
        assertThat(Frontear.readKey(good)).isEqualTo(pod.getPublic());
        final Path bad = Files.writeString(dir.resolve("bad.pub"), "not a key");
        assertThatThrownBy(() -> Frontear.readKey(bad)).hasMessageContaining("file=" + bad);
    }

    private static MqttClient client(final int port, final String id, final List<Packet.Publish> into,
            final ExecutorService callbacks) {
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
