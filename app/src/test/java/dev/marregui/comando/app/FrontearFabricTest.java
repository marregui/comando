package dev.marregui.comando.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.SplittableRandom;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.app.FrontearFabric.Entry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.mcp.PeerBroker;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// [FrontearFabric]: the frontear node hosted in this process, the one fabric between comando instances (record 185).
/// Every test runs its instances in this one process, each on its own data root in a temporary folder, against a
/// registry there and a free port, never the user's `~/.comando-fabric.json` or port 18883.
/// - the first instance to start hosts; the registry names it, and the others see it and cannot stop it
/// - the registry lets one instance host: a live entry refuses a second start, two starts at once make one host, a
///   stale entry is cleared, and one of a later version is read by none
/// - the registry's lock makes a takeover one step: two takeovers of a stale entry at once make one host, and while
///   another holds the lock, a look takes nothing over
/// - off the peers, the registry and the port are the data root's own, never the machine's
/// - closing the host stops the node and clears the registry; closing another instance leaves it running
/// - the peer link's look hosts the node when none runs, so another instance takes over when the host quits; a stop by
///   hand holds in its own instance, and a port another process holds is said once and never touched
/// - the control key pair is made once, readable as its halves require, reused, and the node trusts it alone
/// - two instances' peer brokers exchange a question and its answer through the node
class FrontearFabricTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String INBOX = "comando/v1/local/instance/b/inbox";
    private static final String REPLY = "comando/v1/local/instance/a/reply";
    private static final long PID = ProcessHandle.current().pid();
    /// A start no process of this pid had: an entry naming this process with it is stale.
    private static final String LONG_AGO = "2000-01-01T00:00:00Z";

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

        assertThat(up.text()).isEqualTo("frontear is up: MQTT 127.0.0.1:" + port);
        assertThat(sinkA.of(Intent.Notify.class).getFirst().text()).as("said at the click")
                .isEqualTo("Starting frontear…");
        final Entry entry = read();
        assertThat(entry.version()).isEqualTo(FrontearFabric.REGISTRY_VERSION);
        assertThat(entry.pid()).isEqualTo(PID);
        assertThat(entry.owner()).isEqualTo(dir.resolve("a").toString());
        assertThat(entry.name()).isEqualTo("a");
        assertThat(entry.port()).isEqualTo(port);
        assertThat(FrontearFabric.taken(port)).as("the node listens").isTrue();
        await().atMost(WAIT).until(a::startedHere);
        assertThat(a.status()).as("the host's button says it hosts").isEqualTo("running, hosted here");

        assertThat(b.running()).isTrue();
        await().atMost(WAIT).until(b::isRunning);
        assertThat(b.startedHere()).isFalse();
        final String host = "comando · a (" + dir.resolve("a") + ")";
        assertThat(b.status()).as("the button's tooltip says whose node it is")
                .isEqualTo("running, hosted by " + host + "; only that comando stops it");
        assertThat(b.want(false)).isEqualTo("refused: frontear was started by " + host
                + ", and only that comando stops it");
        final Intent.Notify refused = toggle(b, sinkB);
        assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
        assertThat(refused.text()).isEqualTo("Cannot stop frontear: only the comando that started it stops it"
                + " [name=a, owner=" + dir.resolve("a") + ", pid=" + PID + ']');
        assertThat(sinkB.of(Intent.Notify.class)).as("a refusal says nothing else").hasSize(1);
        assertThat(read()).isEqualTo(entry);
        assertThat(dir.resolve("b")).as("nothing made for the instance that does not host").doesNotExist();

        final Intent.Notify down = toggle(a, sinkA);

        assertThat(down.text()).isEqualTo("frontear is down");
        assertThat(registry).doesNotExist();
        assertThat(FrontearFabric.taken(port)).as("the port is free").isFalse();
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
        assertThat(a.want(false)).isEqualTo("frontear is not running");
        assertThat(a.want(true)).startsWith("starting frontear");
        await().atMost(WAIT).until(() -> !a.busy() && a.running());
        final Entry started = read();

        assertThat(b.want(true)).isEqualTo("frontear already runs, started by comando · a (" + dir.resolve("a")
                + ")");
        assertThat(a.want(true)).as("its own, running").isEqualTo("frontear already runs");

        assertThat(read()).isEqualTo(started);
        assertThat(sinkB.of(Intent.Notify.class)).as("answered, not toggled").isEmpty();
        assertThat(dir.resolve("b")).doesNotExist();
        assertThat(FrontearFabric.taken(port)).isTrue();
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
            assertThat(FrontearFabric.taken(port)).isTrue();

            host.want(false);
            await().atMost(WAIT).until(() -> !host.busy() && !host.running());
            assertThat(registry).doesNotExist();
            assertThat(FrontearFabric.taken(port)).as("round " + round).isFalse();
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
        assertThat(toggle(a, sink).text()).startsWith("frontear is up");
        assertThat(read().owner()).isEqualTo(dir.resolve("a").toString());
        toggle(a, sink);

        write(new Entry(PID, dir.resolve("b").toString(), "b", port, LONG_AGO));

        assertThat(a.running()).as("this pid, started at another time").isFalse();
        assertThat(toggle(a, sink).text()).startsWith("frontear is up");
        assertThat(read().owner()).isEqualTo(dir.resolve("a").toString());
    }

    /// The registry keeps the schema rules of record 133: an entry with no version is version 1 and names its
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
        assertThat(a.want(true)).startsWith("frontear already runs, started by comando · b");

        write(new Entry(FrontearFabric.REGISTRY_VERSION + 1, PID, dir.resolve("b").toString(), "b", port, started));
        final String later = Files.readString(registry);

        assertThat(a.running()).isFalse();
        final Intent.Notify refused = toggle(a, sink);
        assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
        assertThat(refused.text()).startsWith("Cannot read the frontear registry; remove it if no frontear runs"
                + " [registry=" + registry + ", cause=");
        assertThat(Files.readString(registry)).isEqualTo(later);
        assertThat(FrontearFabric.taken(port)).isFalse();
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
        assertThat(FrontearFabric.taken(port)).isTrue();
        assertThat(a.running()).isTrue();

        final long start = System.nanoTime();
        a.close();
        final Duration closed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(closed).isLessThan(WAIT);
        assertThat(registry).doesNotExist();
        assertThat(FrontearFabric.taken(port)).isFalse();
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
        assertThat(toggle(a, sink).text()).startsWith("frontear is up");
        a.close();
        final FrontearFabric again = fabric("a", sink);
        assertThat(toggle(again, sink).text()).startsWith("frontear is up");

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
        assertThat(FrontearFabric.taken(port)).isFalse();
    }

    /// With the port held by another listener on loopback, a toggle warns naming that port, makes no folder and no key
    /// and writes no registry, and the button's tooltip says the port is held; the listener keeps its port.
    @Test
    void aBusyPortRefusesTheStart() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        try (ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));

            final Intent.Notify refused = toggle(a, sink);

            assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
            assertThat(refused.text()).isEqualTo(portHeld(port));
            assertThat(a.status()).isEqualTo("stopped; port " + port + " on 127.0.0.1 is in use by another process");
            assertThat(registry).doesNotExist();
            assertThat(dir.resolve("a")).doesNotExist();
            assertThat(holder.isBound() && !holder.isClosed()).isTrue();
        }
    }

    /// Two instances' peer brokers follow the fabric as the peer link does: each connects while it runs, a question
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

    /// The peer link's first look, with none running, hosts the node without a word before it; another instance's look
    /// finds it running and hosts nothing. Once the host quits, the other's next look hosts it, and the registry names
    /// that instance.
    @Test
    void aLookHostsWhenNoneRunsAndAnotherTakesOverWhenTheHostQuits() throws Exception {
        final RecordingSink sinkA = new RecordingSink();
        final FrontearFabric a = fabric("a", sinkA);
        final RecordingSink sinkB = new RecordingSink();
        final FrontearFabric b = fabric("b", sinkB);

        assertThat(look(a)).as("none ran at the look").isFalse();
        await().atMost(WAIT).until(a::startedHere);
        assertThat(sinkA.of(Intent.Notify.class)).extracting(Intent.Notify::text).as("only the outcome is said")
                .containsExactly("frontear is up: MQTT 127.0.0.1:" + port);
        assertThat(look(b)).as("a's node runs").isTrue();
        assertThat(b.startedHere()).isFalse();
        assertThat(read().owner()).isEqualTo(dir.resolve("a").toString());

        a.close();
        assertThat(FrontearFabric.taken(port)).as("the host quit").isFalse();

        await().atMost(WAIT).until(() -> {
            look(b);
            return b.startedHere();
        });
        assertThat(read().owner()).isEqualTo(dir.resolve("b").toString());
        assertThat(FrontearFabric.taken(port)).isTrue();
        assertThat(sinkB.of(Intent.Notify.class)).extracting(Intent.Notify::text)
                .containsExactly("frontear is up: MQTT 127.0.0.1:" + port);
    }

    /// Three instances looking at once with none running, round after round, leave exactly one host, the one the
    /// registry names; the others connect to it.
    @Test
    void looksAtOnceMakeOneHost() throws Exception {
        for (int round = 0; round < 5; round++) {
            final List<String> names = List.of(round + "a", round + "b", round + "c");
            final List<FrontearFabric> all = names.stream().map(n -> fabric(n, new RecordingSink())).toList();
            all.forEach(FrontearFabric::runningOrHost);
            await().atMost(WAIT).until(() -> all.stream().noneMatch(FrontearFabric::busy));

            final List<FrontearFabric> hosts = all.stream().filter(FrontearFabric::startedHere).toList();
            assertThat(hosts).as("round " + round).hasSize(1);
            assertThat(read().owner()).as("round " + round)
                    .isEqualTo(dir.resolve(names.get(all.indexOf(hosts.getFirst()))).toString());
            assertThat(all.stream().allMatch(FrontearFabric::running)).as("round " + round).isTrue();

            all.forEach(FrontearFabric::close);
            assertThat(registry).as("round " + round).doesNotExist();
            assertThat(FrontearFabric.taken(port)).as("round " + round).isFalse();
        }
    }

    /// A port another process holds: the looks start nothing and say so once, however many there are, the tooltip
    /// says why it is stopped, and the holder keeps its port; once it lets go, the next look hosts.
    @Test
    void aPortHeldByAnotherProcessIsSaidOnceAndLeftAlone() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        try (final ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));

            for (int i = 0; i < 3; i++) {
                assertThat(look(a)).isFalse();
            }

            assertThat(sink.of(Intent.Notify.class)).extracting(Intent.Notify::text).as("said once")
                    .containsExactly(portHeld(port));
            assertThat(sink.of(Intent.Notify.class).getFirst().level()).isEqualTo(Notice.Level.WARN);
            assertThat(a.status()).isEqualTo("stopped; port " + port + " on 127.0.0.1 is in use by another process");
            assertThat(registry).doesNotExist();
            assertThat(holder.isBound() && !holder.isClosed()).isTrue();
        }
        await().atMost(WAIT).until(() -> {
            look(a);
            return a.startedHere();
        });
    }

    /// A stop by hand holds in its own instance: its next looks start nothing until a start by hand; another
    /// instance's look, which cannot tell the stop from a quit, hosts it.
    @Test
    void aStopByHandHoldsInItsOwnInstance() throws Exception {
        final RecordingSink sinkA = new RecordingSink();
        final FrontearFabric a = fabric("a", sinkA);
        final FrontearFabric b = fabric("b", new RecordingSink());
        look(a);
        await().atMost(WAIT).until(a::startedHere);

        assertThat(toggle(a, sinkA).text()).isEqualTo("frontear is down");

        assertThat(look(a)).isFalse();
        assertThat(a.busy()).as("no start under way").isFalse();
        assertThat(registry).doesNotExist();

        await().atMost(WAIT).until(() -> {
            look(b);
            return b.startedHere();
        });
        b.close();
        assertThat(a.want(true)).startsWith("starting frontear");
        await().atMost(WAIT).until(() -> !a.busy() && a.startedHere());
    }

    /// While another comando holds the registry's lock, a look over a stale entry neither clears it nor hosts, and
    /// gives up within its bound; once the lock is let go, the next look clears it and hosts. The entry names this
    /// process at another start: a removal that read it before another instance's claim would delete that claim.
    @Test
    void aHeldRegistryLockHoldsOffATakeoverUntilItIsLetGo() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        final Entry stale = new Entry(PID, dir.resolve("b").toString(), "b", port, LONG_AGO);
        write(stale);
        try (final FileChannel channel = FileChannel.open(FrontearFabric.lockFile(registry),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                final FileLock _ = channel.lock()) {

            assertThat(look(a)).isFalse();

            assertThat(read()).as("left as it was").isEqualTo(stale);
            assertThat(a.startedHere()).isFalse();
            assertThat(FrontearFabric.taken(port)).as("nothing bound").isFalse();
            assertThat(sink.of(Intent.Notify.class)).as("a look says nothing of it").isEmpty();
        }
        await().atMost(WAIT).until(() -> {
            look(a);
            return a.startedHere();
        });
        assertThat(read().owner()).isEqualTo(dir.resolve("a").toString());
    }

    /// Two instances taking over a stale entry at the same moment, round after round with seeded timing, leave exactly
    /// one host, the one the registry names, and neither says another process holds the port: the entry names this
    /// process at another start and one of the two, so each finds it stale. Seeds `comando.fabric.seed0` and
    /// `comando.fabric.seed1` (`COMANDO_FABRIC_SEED0/1` through Gradle).
    @Test
    void staleTakeoversAtOnceMakeOneHost() throws Exception {
        final long seed0 = Long.getLong("comando.fabric.seed0", System.nanoTime());
        final long seed1 = Long.getLong("comando.fabric.seed1", System.currentTimeMillis() * 31L + System.nanoTime());
        System.out.println("staleTakeoversAtOnceMakeOneHost seeds [comando.fabric.seed0=" + seed0
                + ", comando.fabric.seed1=" + seed1 + ']');
        final SplittableRandom random = new SplittableRandom(seed0 ^ Long.rotateLeft(seed1, 32));
        for (int round = 0; round < 20; round++) {
            final List<RecordingSink> sinks = List.of(new RecordingSink(), new RecordingSink());
            final List<FrontearFabric> two = List.of(fabric(round + "a", sinks.get(0)),
                    fabric(round + "b", sinks.get(1)));
            final String named = round + (random.nextBoolean() ? "a" : "b");
            write(new Entry(PID, dir.resolve(named).toString(), named, port, LONG_AGO));
            final CyclicBarrier barrier = new CyclicBarrier(two.size());
            final AtomicReference<Throwable> failed = new AtomicReference<>();
            final List<Thread> racers = new ArrayList<>();
            for (final FrontearFabric f : two) {
                // up to 0.2 ms apart, spun: the race is between one's removal and the other's claim
                final long lagNanos = random.nextLong(200_000);
                racers.add(Thread.ofVirtual().name("fabric-test-takeover-" + round + "-" + racers.size()).start(() -> {
                    try {
                        barrier.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                        final long until = System.nanoTime() + lagNanos;
                        while (System.nanoTime() - until < 0) {
                            Thread.onSpinWait();
                        }
                        f.runningOrHost();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failed.compareAndSet(null, e);
                    } catch (final BrokenBarrierException | TimeoutException | RuntimeException e) {
                        failed.compareAndSet(null, e);
                    }
                }));
            }
            for (final Thread t : racers) {
                assertThat(t.join(WAIT)).as("round " + round).isTrue();
            }
            assertThat(failed.get()).as("round " + round).isNull();
            await().atMost(WAIT).until(() -> two.stream().noneMatch(FrontearFabric::busy));

            final List<FrontearFabric> hosts = two.stream().filter(FrontearFabric::startedHere).toList();
            assertThat(hosts).as("round " + round).hasSize(1);
            assertThat(read().owner()).as("round " + round)
                    .isEqualTo(dir.resolve(round + (hosts.getFirst() == two.get(0) ? "a" : "b")).toString());
            assertThat(two.stream().allMatch(FrontearFabric::running)).as("round " + round).isTrue();
            assertThat(sinks.stream().flatMap(k -> k.of(Intent.Notify.class).stream()).map(Intent.Notify::text))
                    .as("round " + round).noneMatch(t -> t.contains("another process"));

            two.forEach(FrontearFabric::close);
            assertThat(registry).as("round " + round).doesNotExist();
            assertThat(FrontearFabric.taken(port)).as("round " + round).isFalse();
        }
    }

    /// Off the peers, as every test and picture tool runs, the fabric's registry is the data root's and its port one
    /// the OS picks: a toggle hosts there and never reads or writes the machine's registry or binds its port.
    @Test
    void offThePeersTheFabricKeepsToTheDataRoot() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final Path home = Files.createDirectories(dir.resolve("off"));
        final FrontearFabric off = FrontearFabric.of(home, "off", sink, false);
        fabrics.add(off);

        assertThat(off.mqttPort()).isNotEqualTo(FrontearFabric.PORT);
        final Intent.Notify up = toggle(off, sink);

        assertThat(up.text()).startsWith("frontear is up: MQTT 127.0.0.1:")
                .isNotEqualTo("frontear is up: MQTT 127.0.0.1:" + FrontearFabric.PORT);
        final Entry entry = read(home.resolve(FrontearFabric.REGISTRY.getFileName()));
        assertThat(entry.owner()).isEqualTo(home.toString());
        assertThat(entry.pid()).isEqualTo(PID);
        await().atMost(WAIT).until(off::startedHere);
    }

    /// The warning a start meets when another process holds `port`.
    private static String portHeld(final int port) {
        return "Cannot start frontear: another process holds its port; comando leaves it alone, and the instances reach"
                + " each other once it is free [host=127.0.0.1, port=" + port + ']';
    }

    /// One look of the peer link, waited for: whether the node ran when it looked.
    private static boolean look(final FrontearFabric fabric) {
        await().atMost(WAIT).until(() -> !fabric.busy());
        final boolean ran = fabric.runningOrHost();
        await().atMost(WAIT).until(() -> !fabric.busy());
        return ran;
    }

    /// Toggles once the previous toggle has finished, and returns the notice of its outcome: the last it posts, after
    /// the "Starting frontear…" or "Stopping frontear…" said at once.
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
        return read(registry);
    }

    private static Entry read(final Path file) throws IOException {
        return JsonFile.BINDER.fromTree(Json.readTree(Files.readString(file)), Entry.class);
    }

    private void write(final Entry e) throws IOException {
        Files.write(registry, JsonFile.pretty(JsonFile.BINDER.toTree(e)));
    }
}
