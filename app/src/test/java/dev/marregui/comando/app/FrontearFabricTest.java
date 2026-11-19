package dev.marregui.comando.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.app.FrontearFabric.Entry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.frontear.control.Signer;
import dev.marregui.comando.frontear.mqtt.MqttClient;
import dev.marregui.comando.frontear.mqtt.Packet;
import dev.marregui.comando.mcp.PeerBroker;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// [FrontearFabric]: a frontear node hosted in this process as the instances' broker, in place of the comando Edge.
/// Every test runs its instances in this one process, each on its own data root in a temporary folder, against a
/// registry there and a free port, never the user's `~/.comando-fabric.json` or port 18883.
/// - the first instance to start hosts; the registry names it, and the others see it and cannot stop it
/// - the registry lets one instance host: a live entry refuses a second start, two starts at once make one host, a
///   stale entry is cleared, and one of a later version is read by none
/// - closing the host stops the node and clears the registry; closing another instance leaves it running
/// - the control key pair is made once, readable as its halves require, reused, and the node trusts it alone
/// - two instances' peer brokers exchange a question and its answer through the node
class FrontearFabricTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String INBOX = "comando/v1/local/instance/b/inbox";
    private static final String REPLY = "comando/v1/local/instance/a/reply";
    private static final long PID = ProcessHandle.current().pid();

    @TempDir
    Path dir;
    private Path registry;
    private int port;
    private final List<FrontearFabric> fabrics = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        registry = dir.resolve("comando-fabric.json");
        port = freePort();
    }

    @AfterEach
    void tearDown() {
        // each stops the node it hosts, should a test have failed with one running
        fabrics.forEach(FrontearFabric::close);
    }

    /// A start hosts the node on the port on loopback, says so, and writes the registry with this process, its start
    /// and the instance's data root; another instance sees it running, is not its host, is refused a stop and installs
    /// nothing; the host's second toggle stops the node, frees the port and removes the registry.
    @Test
    void theFirstInstanceHostsAndTheOthersSeeItAndCannotStopIt() throws Exception {
        final RecordingSink sinkA = new RecordingSink();
        final FrontearFabric a = fabric("a", sinkA);
        final RecordingSink sinkB = new RecordingSink();
        final FrontearFabric b = fabric("b", sinkB);

        final Intent.Notify up = toggle(a, sinkA);

        assertThat(up.text()).isEqualTo("The fabric is up: MQTT 127.0.0.1:" + port + ", frontear node comando");
        assertThat(sinkA.of(Intent.Notify.class).getFirst().text()).as("said at the click")
                .isEqualTo("Starting the fabric…");
        final Entry entry = read();
        assertThat(entry.version()).isEqualTo(FrontearFabric.REGISTRY_VERSION);
        assertThat(entry.pid()).isEqualTo(PID);
        assertThat(entry.owner()).isEqualTo(dir.resolve("a").toString());
        assertThat(entry.name()).isEqualTo("a");
        assertThat(entry.port()).isEqualTo(port);
        assertThat(EmbeddedEdge.taken(port)).as("the node listens").isTrue();
        await().atMost(WAIT).until(a::startedHere);
        assertThat(a.status()).as("the host's button adds nothing").isEmpty();

        assertThat(b.running()).isTrue();
        await().atMost(WAIT).until(b::isRunning);
        assertThat(b.startedHere()).isFalse();
        final String host = "started by comando · a (" + dir.resolve("a") + ")";
        assertThat(b.status()).as("the button's tooltip says whose fabric it is")
                .isEqualTo(host + "; only that comando stops it");
        assertThat(b.want(false)).isEqualTo("refused: the fabric was " + host + ", and only that comando stops it");
        final Intent.Notify refused = toggle(b, sinkB);
        assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
        assertThat(refused.text()).isEqualTo("Cannot stop the fabric: it was " + host
                + ", and only that comando stops it");
        assertThat(sinkB.of(Intent.Notify.class)).as("a refusal says nothing else").hasSize(1);
        assertThat(read()).isEqualTo(entry);
        assertThat(dir.resolve("b")).as("nothing made for the instance that does not host").doesNotExist();

        final Intent.Notify down = toggle(a, sinkA);

        assertThat(down.text()).isEqualTo("The fabric is down");
        assertThat(registry).doesNotExist();
        assertThat(EmbeddedEdge.taken(port)).as("the port is free").isFalse();
        assertThat(a.running()).isFalse();
        assertThat(b.running()).isFalse();
    }

    /// While the registry names a live host, a second instance's start changes nothing and says who hosts: its
    /// registry, its folder and the host's node are left as they were.
    @Test
    void aLiveEntryRefusesASecondHost() throws Exception {
        final RecordingSink sinkA = new RecordingSink();
        final FrontearFabric a = fabric("a", sinkA);
        final RecordingSink sinkB = new RecordingSink();
        final FrontearFabric b = fabric("b", sinkB);
        assertThat(a.want(false)).isEqualTo("the fabric is not running");
        assertThat(a.want(true)).startsWith("starting the fabric");
        await().atMost(WAIT).until(() -> !a.busy() && a.running());
        final Entry started = read();

        assertThat(b.want(true)).isEqualTo("the fabric already runs, started by comando · a (" + dir.resolve("a")
                + ")");
        assertThat(a.want(true)).as("its own, running").isEqualTo("the fabric already runs");

        assertThat(read()).isEqualTo(started);
        assertThat(sinkB.of(Intent.Notify.class)).as("answered, not toggled").isEmpty();
        assertThat(dir.resolve("b")).doesNotExist();
        assertThat(EmbeddedEdge.taken(port)).isTrue();
    }

    /// Two instances asked to start at the same moment, round after round, leave exactly one host: the registry names
    /// it, it alone holds the node, and the other connects to it; the host's stop leaves none.
    @Test
    void twoStartsAtOnceMakeOneHost() throws Exception {
        final FrontearFabric a = fabric("a", new RecordingSink());
        final FrontearFabric b = fabric("b", new RecordingSink());
        for (int round = 0; round < 10; round++) {
            a.want(true);
            b.want(true);
            await().atMost(WAIT).until(() -> !a.busy() && !b.busy());

            final Entry entry = read();
            final FrontearFabric host = entry.owner().equals(dir.resolve("a").toString()) ? a : b;
            assertThat(a.running() && b.running()).as("round " + round).isTrue();
            assertThat(List.of(a, b).stream().filter(FrontearFabric::startedHere).toList()).as("round " + round)
                    .containsExactly(host);
            assertThat(EmbeddedEdge.taken(port)).isTrue();

            host.want(false);
            await().atMost(WAIT).until(() -> !host.busy() && !host.running());
            assertThat(registry).doesNotExist();
            assertThat(EmbeddedEdge.taken(port)).as("round " + round).isFalse();
        }
    }

    /// An entry whose pid is dead, or alive but started at another time (another process on a reused pid), names no
    /// running fabric; the next start clears it and hosts.
    @Test
    void aStaleEntryIsClearedAndTheNextStartHosts() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        final Process gone = new ProcessBuilder("true").start();
        gone.waitFor();
        write(new Entry(gone.pid(), dir.resolve("b").toString(), "b", port, Instant.now().toString()));

        assertThat(a.running()).as("a dead pid").isFalse();
        assertThat(toggle(a, sink).text()).startsWith("The fabric is up");
        assertThat(read().owner()).isEqualTo(dir.resolve("a").toString());
        toggle(a, sink);

        write(new Entry(PID, dir.resolve("b").toString(), "b", port, "2000-01-01T00:00:00Z"));

        assertThat(a.running()).as("this pid, started at another time").isFalse();
        assertThat(toggle(a, sink).text()).startsWith("The fabric is up");
        assertThat(read().owner()).isEqualTo(dir.resolve("a").toString());
    }

    /// The registry keeps the Edge's schema rules (record 133): an entry with no version is version 1 and names its
    /// host; one of a later version is read by no instance of this comando, which then starts nothing and leaves it.
    @Test
    void anEntryWithoutAVersionIsReadAndOneOfALaterVersionIsNot() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        final String started = ProcessHandle.current().info().startInstant().orElseThrow().toString();
        Files.writeString(registry, "{\"pid\":" + PID + ",\"owner\":\"" + dir.resolve("b") + "\",\"name\":\"b\","
                + "\"port\":" + port + ",\"started\":\"" + started + "\"}\n");

        assertThat(read().version()).isEqualTo(1);
        assertThat(a.running()).isTrue();
        assertThat(a.want(true)).startsWith("the fabric already runs, started by comando · b");

        write(new Entry(FrontearFabric.REGISTRY_VERSION + 1, PID, dir.resolve("b").toString(), "b", port, started));
        final String later = Files.readString(registry);

        assertThat(a.running()).isFalse();
        final Intent.Notify refused = toggle(a, sink);
        assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
        assertThat(refused.text()).startsWith("Cannot read the fabric registry; remove it if no comando fabric runs"
                + " [registry=" + registry + ", cause=");
        assertThat(Files.readString(registry)).isEqualTo(later);
        assertThat(EmbeddedEdge.taken(port)).isFalse();
    }

    /// Closing an instance that does not host leaves the node running; closing the host stops it within a bounded
    /// time, frees the port and removes the registry, so the others see it stop; a second close does nothing.
    @Test
    void closingTheHostStopsTheFabricAndClosingAnotherDoesNot() throws Exception {
        final RecordingSink sinkA = new RecordingSink();
        final FrontearFabric a = fabric("a", sinkA);
        final FrontearFabric b = fabric("b", new RecordingSink());
        toggle(a, sinkA);
        final Entry started = read();

        b.close();

        assertThat(read()).isEqualTo(started);
        assertThat(EmbeddedEdge.taken(port)).isTrue();
        assertThat(a.running()).isTrue();

        final long start = System.nanoTime();
        a.close();
        final Duration closed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(closed).isLessThan(WAIT);
        assertThat(registry).doesNotExist();
        assertThat(EmbeddedEdge.taken(port)).isFalse();
        assertThat(fabric("c", new RecordingSink()).running()).as("another instance sees it stop").isFalse();
        a.close();
        assertThat(registry).doesNotExist();
    }

    /// The first start makes the control key pair in the fabric's folder, the folder and the private half readable by
    /// the user alone and the public half by all; the node answers a `GetState` signed with the private half read back
    /// from disk and refuses one signed by another key; a restart, in this instance or a new one on the same root,
    /// reuses the pair byte for byte.
    @Test
    void theControlKeyPairIsMadeOnceAndTheNodeTrustsIt() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        final Path root = dir.resolve("a/fabric");
        toggle(a, sink);
        final Path secret = root.resolve(FrontearFabric.PRIVATE_KEY);
        final Path shared = root.resolve(FrontearFabric.PUBLIC_KEY);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(secret))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shared))).isEqualTo("rw-r--r--");
        final byte[] secretBytes = Files.readAllBytes(secret);
        final byte[] sharedBytes = Files.readAllBytes(shared);

        final PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64
                .getDecoder().decode(Files.readString(secret).strip())));
        final PublicKey pub = publicKey(shared);
        final KeyPair stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final List<Packet.Publish> replies = new CopyOnWriteArrayList<>();
        try (MqttClient pod = client("pod", replies)) {
            pod.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.subscribe("replies/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.publishAcknowledged(new Signer(key, Signer.keyId(pub)).sign("$frontear/control/comando/GetState",
                    bytes("{}"), "replies/pod", bytes("1"))).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.publishAcknowledged(new Signer(stranger.getPrivate(), Signer.keyId(stranger.getPublic()))
                    .sign("$frontear/control/comando/GetState", bytes("{}"), "replies/stranger", bytes("2")))
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> replies.size() == 2);
        }
        final JsonNode own = reply(replies, "replies/pod").path("ok");
        final JsonNode other = reply(replies, "replies/stranger").path("ok");
        assertThat(own.isBoolean() && other.isBoolean()).as("both answered").isTrue();
        assertThat(own.booleanValue()).as("the pod's own key").isTrue();
        assertThat(other.booleanValue()).as("another key").isFalse();

        toggle(a, sink);
        assertThat(toggle(a, sink).text()).startsWith("The fabric is up");
        a.close();
        final FrontearFabric again = fabric("a", sink);
        assertThat(toggle(again, sink).text()).startsWith("The fabric is up");

        assertThat(Files.readAllBytes(secret)).isEqualTo(secretBytes);
        assertThat(Files.readAllBytes(shared)).isEqualTo(sharedBytes);
        assertThat(FrontearFabric.keys(root).getPublic()).isEqualTo(pub);
    }

    /// Half a pair, as a crash between the two writes leaves it, is replaced by a whole one; a pair that does not read
    /// is refused and left in place, and a start with it hosts nothing and writes no registry.
    @Test
    void halfAPairIsReplacedAndAPairThatDoesNotReadIsKept() throws Exception {
        final Path half = Files.createDirectories(dir.resolve("half"));
        Files.writeString(half.resolve(FrontearFabric.PRIVATE_KEY), "left by a crash\n");

        final KeyPair made = FrontearFabric.keys(half);

        assertThat(FrontearFabric.keys(half).getPublic()).isEqualTo(made.getPublic());
        assertThat(FrontearFabric.keys(half).getPrivate()).isEqualTo(made.getPrivate());

        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        final Path root = Files.createDirectories(dir.resolve("a/fabric"));
        Files.writeString(root.resolve(FrontearFabric.PRIVATE_KEY), "not a key\n");
        Files.writeString(root.resolve(FrontearFabric.PUBLIC_KEY), "not a key\n");

        assertThatThrownBy(() -> FrontearFabric.keys(root)).isInstanceOf(IOException.class);
        final Intent.Notify refused = toggle(a, sink);

        assertThat(refused.level()).isEqualTo(Notice.Level.ERROR);
        assertThat(Files.readString(root.resolve(FrontearFabric.PRIVATE_KEY))).isEqualTo("not a key\n");
        assertThat(registry).doesNotExist();
        assertThat(EmbeddedEdge.taken(port)).isFalse();
    }

    /// With the port held by another listener on loopback, a toggle warns naming that port, makes no folder and no key
    /// and writes no registry; the listener keeps its port.
    @Test
    void aBusyPortRefusesTheStart() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        try (ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));

            final Intent.Notify refused = toggle(a, sink);

            assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
            assertThat(refused.text()).isEqualTo("Cannot start the fabric: port " + port
                    + " on 127.0.0.1 is in use by another process");
            assertThat(registry).doesNotExist();
            assertThat(dir.resolve("a")).doesNotExist();
            assertThat(holder.isBound() && !holder.isClosed()).isTrue();
        }
    }

    /// Two instances' peer brokers follow the fabric as they follow the Edge: each connects while it runs, a question
    /// published with a response topic and correlation data reaches the other's inbox, and the answer comes back on
    /// that topic with that correlation; once the host closes, the client's broker stops following and disconnects.
    @Test
    void twoInstancesExchangeAQuestionAndItsAnswerThroughTheFabric() {
        final RecordingSink sinkA = new RecordingSink();
        final FrontearFabric a = fabric("a", sinkA);
        final FrontearFabric b = fabric("b", new RecordingSink());
        toggle(a, sinkA);
        final List<PeerBroker.Message> answers = new CopyOnWriteArrayList<>();
        final List<PeerBroker.Message> questions = new CopyOnWriteArrayList<>();
        try (MqttPeerBroker onA = new MqttPeerBroker("comando-a", a.mqttPort(), a::running);
                MqttPeerBroker onB = new MqttPeerBroker("comando-b", b.mqttPort(), b::running)) {
            onB.subscribe(INBOX, m -> {
                questions.add(m);
                onB.publish(m.responseTopic(), bytes("X is 42"), false, null, m.correlation());
            });
            onA.subscribe(REPLY, answers::add);
            onA.follow();
            onB.follow();
            await().atMost(WAIT).until(() -> onA.connected() && onB.connected());
            // the subscriptions are granted some time after the connect; ask until an answer comes back
            await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).until(() -> {
                onA.publish(INBOX, bytes("what is X?"), false, REPLY, bytes("id-1"));
                return !answers.isEmpty();
            });

            final PeerBroker.Message question = questions.getFirst();
            assertThat(question.payload()).isEqualTo(bytes("what is X?"));
            assertThat(question.responseTopic()).isEqualTo(REPLY);
            assertThat(question.correlation()).isEqualTo(bytes("id-1"));
            final PeerBroker.Message answer = answers.getFirst();
            assertThat(answer.topic()).isEqualTo(REPLY);
            assertThat(answer.payload()).isEqualTo(bytes("X is 42"));
            assertThat(answer.correlation()).isEqualTo(bytes("id-1"));

            a.close();
            onB.follow();
            await().atMost(WAIT).until(() -> !onB.connected());
        }
    }

    /// `comando.fabric` chooses the broker: unset or `edge` keeps the comando Edge, `frontear` in any case and with
    /// surrounding blanks chooses the fabric, and any other value keeps the Edge.
    @Test
    void thePropertyChoosesTheFabricAndTheEdgeIsTheDefault() {
        final String before = System.getProperty(App.FABRIC_PROPERTY);
        try {
            System.clearProperty(App.FABRIC_PROPERTY);
            assertThat(App.usesFrontear()).isFalse();
            for (final String value : List.of("frontear", " Frontear ", "FRONTEAR")) {
                System.setProperty(App.FABRIC_PROPERTY, value);
                assertThat(App.usesFrontear()).as(value).isTrue();
            }
            for (final String value : List.of("edge", "hivemq", "")) {
                System.setProperty(App.FABRIC_PROPERTY, value);
                assertThat(App.usesFrontear()).as(value).isFalse();
            }
        } finally {
            if (before == null) {
                System.clearProperty(App.FABRIC_PROPERTY);
            } else {
                System.setProperty(App.FABRIC_PROPERTY, before);
            }
        }
    }

    /// Toggles once the previous toggle has finished, and returns the notice of its outcome: the last it posts, after
    /// the "Starting the fabric…" or "Stopping the fabric…" said at once.
    private static Intent.Notify toggle(final FrontearFabric fabric, final RecordingSink sink) {
        await().atMost(WAIT).until(() -> !fabric.busy());
        sink.clear();
        fabric.toggle();
        sink.await(Intent.Notify.class, WAIT);
        await().atMost(WAIT).until(() -> !fabric.busy());
        return sink.of(Intent.Notify.class).getLast();
    }

    /// The one reply that came on `topic`, read as JSON.
    private static JsonNode reply(final List<Packet.Publish> replies, final String topic) {
        return Json.readTree(replies.stream().filter(r -> r.topic().equals(topic)).findFirst().orElseThrow()
                .payload());
    }

    /// The public key in `file` as the node's own reader takes it: base64 of its X.509 encoding.
    private static PublicKey publicKey(final Path file) throws Exception {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
                .decode(Files.readString(file).strip())));
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private FrontearFabric fabric(final String instance, final RecordingSink sink) {
        final FrontearFabric f = new FrontearFabric(dir.resolve(instance + "/fabric"), dir.resolve(instance), instance,
                sink, registry, port);
        fabrics.add(f);
        return f;
    }

    /// A plain client of the node on the test's port, its messages collected in `into` on its reader thread.
    private MqttClient client(final String id, final List<Packet.Publish> into) {
        return new MqttClient(new MqttClient.Options("127.0.0.1", port, id, 0, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE,
                null), new MqttClient.Handler() {

                    @Override
                    public void connected() {
                        // connects once; nothing to renew
                    }

                    @Override
                    public void message(final Packet.Publish message) {
                        into.add(message);
                    }

                    @Override
                    public Duration disconnected(final int attempts, final Throwable cause) {
                        return null;
                    }
                }, Runnable::run);
    }

    private Entry read() throws IOException {
        return JsonFile.BINDER.fromTree(Json.readTree(Files.readString(registry)), Entry.class);
    }

    private void write(final Entry e) throws IOException {
        Files.write(registry, JsonFile.pretty(JsonFile.BINDER.toTree(e)));
    }
}
