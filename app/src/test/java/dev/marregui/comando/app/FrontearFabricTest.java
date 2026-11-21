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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.app.FrontearFabric.Entry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.MqttException;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.mcp.PeerBroker;
import dev.marregui.comando.mcp.Peers;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// [FrontearFabric]: every instance hosts a frontear node of its own, and the nodes are linked (record 187). Every
/// test runs its instances in this one process, each on its own data root in a temporary folder, against a registry
/// there and ports from a free one up, never the user's `~/.comando-fabric.json` or port 18883.
/// - each instance's node runs on a port of its own, and the registry, version 2, holds every slug's node
/// - one reconcile links each node to the others with the peer's inbox and reply topics and its own presence out
/// - a question and its reply cross the links, and a presence is retained on the peer's node
/// - a question queued for a peer that is down reaches its inbox once after its restart on the same port, which holds
///   the links until its peer broker is subscribed, and the connect tells the listener, which sends the presence again
/// - a peer's changed start tells the listener as a connect would
/// - a subscribe before the start publishes its node lets the links in once it does, and a stop forgets it
/// - a link to a peer first seen is put at once when wanted, not at the next look
/// - a peer whose process is gone is told with its start; forgetting a peer removes its link and its entry
/// - a registry of version 1 is replaced, one of a later version is read by none
/// - the control key pair is made once, readable as its halves require, reused, and the node trusts it alone
/// - a busy port moves the node to the next free one; a held registry lock, a second instance of the same name and a
///   stop by hand start nothing; looks at once give each instance a port of its own
/// - off the peers, the registry and the port are the data root's own
class FrontearFabricTest {

    /// Each wait's bound: a start, a connect or a control reply takes well under a second here.
    private static final Duration WAIT = Duration.ofSeconds(30);
    /// A link's reconnects back off to 30 s, so a drain after a restart may take that long.
    private static final Duration DRAIN = Duration.ofSeconds(60);
    /// Each poll that asks a node for its links opens a client; five a second keeps that cheap.
    private static final Duration POLL = Duration.ofMillis(200);
    private static final long PID = ProcessHandle.current().pid();
    private static final String INBOX_Y = Peers.inboxTopic("y");
    private static final String REPLY_X = Peers.replyTopic("x");
    private static final String PRESENCE_X = Peers.presenceTopic("x");

    @TempDir
    Path dir;
    private Path registry;
    private int base;
    private final List<FrontearFabric> fabrics = new ArrayList<>();
    private final AtomicInteger probes = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        registry = dir.resolve("comando-fabric.json");
        base = freePort();
    }

    @AfterEach
    void tearDown() {
        // each stops the node it hosts, should a test have failed with one running
        fabrics.forEach(FrontearFabric::close);
    }

    /// The peer link's first look hosts the node without a word before it, on the lowest free port; a second instance
    /// takes another; the registry, version 2, holds both with their ports, this process and its start. After one
    /// reconcile each node keeps a link to the other, kept on disk: to the peer's port, the peer's inbox and reply
    /// topics and its own presence out, nothing in, hop limit 1; both connect.
    @Test
    void eachInstanceHostsItsOwnNodeAndOneReconcileLinksThem() throws Exception {
        final RecordingSink sinkX = new RecordingSink();
        final FrontearFabric x = fabric("x", sinkX);
        final FrontearFabric y = fabric("y", new RecordingSink());

        assertThat(look(x)).as("none ran at the look").isFalse();
        await().atMost(WAIT).until(x::isRunning);
        x.openLinks(x.mqttPort());
        host(y);

        final int xp = x.mqttPort();
        final int yp = y.mqttPort();
        assertThat(xp).isEqualTo(base);
        assertThat(yp).as("a port of its own").isGreaterThan(xp);
        assertThat(sinkX.of(Intent.Notify.class)).extracting(Intent.Notify::text).as("only the outcome is said")
                .containsExactly("frontear is up: MQTT 127.0.0.1:" + xp);
        assertThat(x.status()).isEqualTo("running on 127.0.0.1:" + xp + " as node x");
        assertThat(nodes()).containsExactly(
                Map.entry("x", new Entry(xp, PID, Peers.PROCESS_STARTED, "x", dir.resolve("x").toString())),
                Map.entry("y", new Entry(yp, PID, Peers.PROCESS_STARTED, "y", dir.resolve("y").toString())));

        x.reconcile();
        y.reconcile();

        final JsonNode spec = Json.readTree(Files.readString(dir.resolve("x/fabric/y/link.json")));
        assertThat(spec.path("id").asString()).isEqualTo("y");
        assertThat(spec.path("host").asString()).isEqualTo("127.0.0.1");
        assertThat(spec.path("port").asInt()).isEqualTo(yp);
        assertThat(strings(spec.path("out"))).containsExactlyInAnyOrder(INBOX_Y, Peers.replyTopic("y"), PRESENCE_X);
        assertThat(strings(spec.path("in"))).isEmpty();
        assertThat(spec.path("hopLimit").asInt()).isEqualTo(FrontearFabric.HOP_LIMIT);
        assertThat(strings(Json.readTree(Files.readString(dir.resolve("y/fabric/x/link.json"))).path("out")))
                .containsExactlyInAnyOrder(Peers.inboxTopic("x"), REPLY_X, Peers.presenceTopic("y"));
        await().atMost(WAIT).pollInterval(POLL).until(() -> connected("x", xp, "y") && connected("y", yp, "x"));
        assertThat(links("x", xp).keySet()).containsExactly("y");
    }

    /// Two instances' peer brokers on their own nodes, as the peer link runs them: a question published on X's node
    /// with a response topic and correlation data reaches Y's inbox over X's link, the answer comes back over Y's link
    /// on that topic with that correlation, and both links count what they sent. X's presence, retained, reaches Y's
    /// node, which hands it to a client that subscribes later as retained.
    @Test
    void aQuestionAndItsReplyCrossTheLinksAndThePresenceIsRetainedOnThePeersNode() throws Exception {
        final FrontearFabric x = fabric("x", new RecordingSink());
        final FrontearFabric y = fabric("y", new RecordingSink());
        link(x, y);
        final int xp = x.mqttPort();
        final int yp = y.mqttPort();
        final List<PeerBroker.Message> answers = new CopyOnWriteArrayList<>();
        final List<PeerBroker.Message> questions = new CopyOnWriteArrayList<>();
        try (MqttPeerBroker onX = new MqttPeerBroker("comando-x", x::mqttPort, x::isRunning);
                MqttPeerBroker onY = new MqttPeerBroker("comando-y", y::mqttPort, y::isRunning)) {
            onY.subscribe(INBOX_Y, m -> {
                questions.add(m);
                onY.publish(m.responseTopic(), bytes("X is 42"), false, null, m.correlation());
            });
            onX.subscribe(REPLY_X, answers::add);
            onX.follow();
            onY.follow();
            await().atMost(WAIT).until(() -> onX.connected() && onY.connected());
            // the subscriptions are granted some time after the connect; ask until an answer comes back
            await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).until(() -> {
                onX.publish(INBOX_Y, bytes("what is X?"), false, REPLY_X, bytes("id-1"));
                return !answers.isEmpty();
            });

            final PeerBroker.Message question = questions.getFirst();
            assertThat(question.payload()).isEqualTo(bytes("what is X?"));
            assertThat(question.responseTopic()).isEqualTo(REPLY_X);
            assertThat(question.correlation()).isEqualTo(bytes("id-1"));
            final PeerBroker.Message answer = answers.getFirst();
            assertThat(answer.topic()).isEqualTo(REPLY_X);
            assertThat(answer.payload()).isEqualTo(bytes("X is 42"));
            assertThat(answer.correlation()).isEqualTo(bytes("id-1"));
            assertThat(links("x", xp).get("y").path("sent").asLong()).as("X's link sent the question").isPositive();
            assertThat(links("y", yp).get("x").path("sent").asLong()).as("Y's link sent the answer").isPositive();

            final List<Packet.Publish> live = new CopyOnWriteArrayList<>();
            try (MqttClient watcher = client(yp, "watcher", live)) {
                watcher.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                watcher.subscribe(PRESENCE_X, 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                onX.publish(PRESENCE_X, bytes("x is here"), true, null, null).get(WAIT.toMillis(),
                        TimeUnit.MILLISECONDS);
                await().atMost(WAIT).until(() -> !live.isEmpty());
            }
        }
        final List<Packet.Publish> late = new CopyOnWriteArrayList<>();
        try (MqttClient reader = client(yp, "late", late)) {
            reader.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            reader.subscribe(PRESENCE_X, 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> !late.isEmpty());
        }
        assertThat(late.getFirst().retain()).as("kept on Y's node").isTrue();
        assertThat(late.getFirst().payload()).isEqualTo(bytes("x is here"));
    }

    /// Once Y closes, X's link to it disconnects and stays; a question for Y, not retained, queues in its log. Y
    /// started again takes the port it had and holds the links: a link client is refused 0x88, and a port other than
    /// its node's lets none in. Y's peer broker, as the peer link wires it, lets them in once its inbox is subscribed;
    /// X's link then drains the question into that subscription, once, with its response topic and correlation data,
    /// and X's next reconcile tells the listener that a link connected, whose resend of X's presence reaches Y.
    @Test
    void aQuestionQueuedForAPeerThatIsDownReachesItsInboxOnceAfterItsRestart() throws Exception {
        final FrontearFabric x = fabric("x", new RecordingSink());
        final FrontearFabric y = fabric("y", new RecordingSink());
        link(x, y);
        final int xp = x.mqttPort();
        final int yp = y.mqttPort();
        final AtomicInteger ups = new AtomicInteger();
        try (MqttClient onX = client(xp, "comando-x", new CopyOnWriteArrayList<>())) {
            onX.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            x.onPeers((_, _) -> { }, () -> onX.publish(new Packet.Publish(PRESENCE_X,
                    bytes("presence " + ups.incrementAndGet()), 1, true, false, 0, null, null)));
            final long queued = links("x", xp).get("y").path("queued").asLong();

            y.close();

            await().atMost(WAIT).pollInterval(POLL).until(() -> !connected("x", xp, "y"));
            // a look while the link is down, as the peer link makes every few seconds
            x.reconcile();
            assertThat(links("x", xp)).as("a peer merely down keeps its link").containsKey("y");
            onX.publishAcknowledged(new Packet.Publish(INBOX_Y, bytes("while you were away"), 1, false, false, 0,
                    REPLY_X, bytes("id-1"))).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).pollInterval(POLL)
                    .until(() -> links("x", xp).get("y").path("queued").asLong() > queued);
            assertThat(ups.get()).as("no link connected since").isZero();

            final FrontearFabric back = fabric("y", new RecordingSink());
            start(back);

            assertThat(back.mqttPort()).as("the port kept in the registry").isEqualTo(yp);
            back.openLinks(yp + 1);
            try (MqttClient probe = client(yp, "probe~y", new CopyOnWriteArrayList<>())) {
                assertThatThrownBy(() -> probe.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause()
                        .isInstanceOfSatisfying(MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(0x88));
            }
            final List<PeerBroker.Message> questions = new CopyOnWriteArrayList<>();
            final List<String> presences = new CopyOnWriteArrayList<>();
            try (MqttPeerBroker onY = new MqttPeerBroker("comando-y", back::mqttPort, back::isRunning)) {
                onY.subscribe(INBOX_Y, questions::add);
                onY.subscribe(PRESENCE_X, m -> presences.add(new String(m.payload(), StandardCharsets.UTF_8)));
                onY.onSubscribed(back::openLinks);
                onY.follow();
                await().atMost(DRAIN).until(() -> !questions.isEmpty());
                await().atMost(WAIT).pollInterval(POLL)
                        .until(() -> links("x", xp).get("y").path("queued").asLong() == queued);
                assertThat(questions).singleElement().satisfies(q -> {
                    assertThat(q.payload()).isEqualTo(bytes("while you were away"));
                    assertThat(q.responseTopic()).isEqualTo(REPLY_X);
                    assertThat(q.correlation()).isEqualTo(bytes("id-1"));
                });
                await().atMost(WAIT).pollInterval(POLL).until(() -> {
                    x.reconcile();
                    return ups.get() > 0;
                });
                await().atMost(WAIT).until(() -> presences.contains("presence 1"));
            }
        }
    }

    /// A peer whose node started again between two reconciles shows its link connected at both: the new start its
    /// entry records tells the listener once, as a connect would, and the same start read again tells nothing.
    @Test
    void aPeerWhoseStartChangedGetsThePresenceAgain() throws Exception {
        final FrontearFabric x = fabric("x", new RecordingSink());
        final FrontearFabric y = fabric("y", new RecordingSink());
        final AtomicInteger ups = new AtomicInteger();
        x.onPeers((_, _) -> { }, ups::incrementAndGet);
        // the link's connect is seen at the reconcile that put it or at the next, by the time both are done
        link(x, y);
        x.reconcile();
        assertThat(ups).as("the link's connect, once").hasValue(1);
        final Map<String, Entry> nodes = nodes();
        final Entry e = nodes.get("y");
        nodes.put("y", new Entry(e.port(), e.pid(), "2026-11-21T11:00:00Z", e.name(), e.owner()));
        write(nodes);

        x.reconcile();
        assertThat(ups).as("the new start").hasValue(2);
        x.reconcile();
        assertThat(ups).as("the same start again").hasValue(2);
    }

    /// The peer link's subscribe that completes before a start has published its node, as a reconnect between the bind
    /// and the registry write does, finds no node to open; the start, once it publishes the node on that port, lets the
    /// links in. A stop forgets that subscribe: the next start holds its links until the peer link subscribes again.
    @Test
    void aSubscribeBeforeTheStartPublishesItsNodeLetsTheLinksInOnceItDoes() throws Exception {
        final FrontearFabric y = fabric("y", new RecordingSink());
        y.openLinks(base);
        start(y);
        assertThat(y.mqttPort()).isEqualTo(base);
        try (MqttClient probe = client(base, "probe~y", new CopyOnWriteArrayList<>())) {
            probe.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        y.want(false);
        await().atMost(WAIT).until(() -> !y.busy() && !y.isRunning());
        start(y);
        assertThat(y.mqttPort()).as("the port kept").isEqualTo(base);
        try (MqttClient probe = client(base, "probe~y", new CopyOnWriteArrayList<>())) {
            assertThatThrownBy(() -> probe.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(0x88));
        }
        y.openLinks(base);
        try (MqttClient probe = client(base, "probe~y", new CopyOnWriteArrayList<>())) {
            probe.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /// A link to a peer first seen is put at once, on the caller's thread, without waiting for the next look's
    /// reconcile, to the peer's port with its inbox out; this node's own slug puts none, and a slug the registry does
    /// not hold puts none of its own.
    @Test
    void aLinkToAPeerIsPutAtOnceWhenItIsWanted() throws Exception {
        final FrontearFabric x = fabric("x", new RecordingSink());
        final FrontearFabric y = fabric("y", new RecordingSink());
        host(x);
        host(y);
        final int xp = x.mqttPort();
        assertThat(links("x", xp)).as("no reconcile yet").isEmpty();

        x.linkTo("x");
        assertThat(links("x", xp)).isEmpty();

        x.linkTo("y");
        x.linkTo("z");
        assertThat(links("x", xp).keySet()).containsExactly("y");
        final JsonNode spec = Json.readTree(Files.readString(dir.resolve("x/fabric/y/link.json")));
        assertThat(spec.path("port").asInt()).isEqualTo(y.mqttPort());
        assertThat(strings(spec.path("out"))).contains(INBOX_Y);
    }

    /// A reconcile tells the listener of each peer whose entry names a process that is gone, with the start the entry
    /// records, and of no live one; both keep their links, the gone one's queueing.
    @Test
    void aPeerWhoseProcessIsGoneIsToldWithItsStart() throws Exception {
        final FrontearFabric x = fabric("x", new RecordingSink());
        host(x);
        final List<String> gone = new CopyOnWriteArrayList<>();
        x.onPeers((slug, started) -> gone.add(slug + ' ' + started), () -> { });
        final Process dead = new ProcessBuilder("true").start();
        dead.waitFor();
        final Map<String, Entry> nodes = nodes();
        nodes.put("z", new Entry(freePort(), dead.pid(), "2026-11-21T10:00:00Z", "z", dir.resolve("z").toString()));
        nodes.put("w", new Entry(freePort(), PID, Peers.PROCESS_STARTED, "w", dir.resolve("w").toString()));
        write(nodes);

        x.reconcile();

        assertThat(gone).containsExactly("z 2026-11-21T10:00:00Z");
        assertThat(links("x", x.mqttPort()).keySet()).containsExactlyInAnyOrder("w", "z");
    }

    /// Forgetting a peer whose node runs changes nothing; once it is down, its registry entry, this node's link to it
    /// and the link's spec go, and the next reconcile does not put it back. Forgetting oneself does nothing.
    @Test
    void forgettingAPeerRemovesItsLinkAndItsEntry() throws Exception {
        final FrontearFabric x = fabric("x", new RecordingSink());
        final FrontearFabric y = fabric("y", new RecordingSink());
        link(x, y);
        final int xp = x.mqttPort();

        x.forgetNow("y");
        x.forgetNow("x");

        assertThat(nodes().keySet()).containsExactly("x", "y");
        assertThat(links("x", xp).keySet()).as("its node runs").containsExactly("y");

        y.close();
        x.forgetNow("y");

        assertThat(nodes().keySet()).containsExactly("x");
        assertThat(links("x", xp)).isEmpty();
        assertThat(dir.resolve("x/fabric/y/link.json")).doesNotExist();
        x.reconcile();
        assertThat(links("x", xp)).isEmpty();
    }

    /// A registry of version 1, one node for the machine, is replaced at the next start by one of this version holding
    /// this node alone; one of a later version is read by no instance of this comando, which then starts nothing and
    /// leaves it as it was.
    @Test
    void aRegistryOfVersion1IsReplacedAndOneOfALaterVersionIsNotRead() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        Files.writeString(registry, "{\"pid\":" + PID + ",\"owner\":\"" + dir.resolve("b") + "\",\"name\":\"b\","
                + "\"port\":" + base + ",\"started\":\"" + Peers.PROCESS_STARTED + "\"}\n");

        assertThat(toggle(a, sink).text()).isEqualTo("frontear is up: MQTT 127.0.0.1:" + base);

        assertThat(nodes().keySet()).containsExactly("a");
        a.close();

        final String later = "{\"version\":" + (FrontearFabric.REGISTRY_VERSION + 1) + ",\"nodes\":{}}\n";
        Files.writeString(registry, later);
        final FrontearFabric b = fabric("b", sink);
        final Intent.Notify refused = toggle(b, sink);

        assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
        assertThat(refused.text()).startsWith("Cannot read the frontear registry; remove it if no frontear runs"
                + " [registry=" + registry + ", cause=");
        assertThat(Files.readString(registry)).isEqualTo(later);
        assertThat(b.isRunning()).isFalse();
    }

    /// Closing an instance stops its node within a bounded time and marks its entry down, pid 0, its port and start
    /// kept, so the port is free and another instance's node keeps running; a second close does nothing, and the
    /// instance started again takes the same port. The node it runs is handed out while it runs, and none before or
    /// after.
    @Test
    void closingMarksTheNodeDownAndKeepsItsPort() throws Exception {
        final FrontearFabric a = fabric("a", new RecordingSink());
        final FrontearFabric b = fabric("b", new RecordingSink());
        assertThat(a.node()).as("none before the start").isNull();
        host(a);
        host(b);
        final int port = a.mqttPort();
        assertThat(a.node().port()).as("the node it runs").isEqualTo(port);

        final long start = System.nanoTime();
        a.close();
        final Duration closed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(closed).isLessThan(WAIT);
        final Entry down = new Entry(port, 0L, Peers.PROCESS_STARTED, "a", dir.resolve("a").toString());
        assertThat(nodes().get("a")).isEqualTo(down);
        assertThat(FrontearFabric.taken(port)).isFalse();
        assertThat(a.isRunning()).isFalse();
        assertThat(a.node()).as("none once closed").isNull();
        assertThat(a.mqttPort()).as("the lowest port a start may take").isEqualTo(base);
        assertThat(b.isRunning()).isTrue();
        a.close();
        assertThat(nodes().get("a")).isEqualTo(down);

        final FrontearFabric again = fabric("a", new RecordingSink());
        host(again);
        assertThat(again.mqttPort()).isEqualTo(port);
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
        final String getState = Names.CONTROL + "a/GetState";
        try (MqttClient pod = client(a.mqttPort(), "pod", replies)) {
            pod.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.subscribe("replies/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.publishAcknowledged(new Signer(key, Signer.keyId(pub)).sign(getState, bytes("{}"), "replies/pod",
                    bytes("1"))).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.publishAcknowledged(new Signer(stranger.getPrivate(), Signer.keyId(stranger.getPublic()))
                    .sign(getState, bytes("{}"), "replies/stranger", bytes("2")))
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
        assertThat(FrontearFabric.taken(base)).isFalse();
    }

    /// With the lowest port held by another listener on loopback, a start takes the next free one, records it, and
    /// leaves the listener its port.
    @Test
    void aBusyPortMovesTheNodeToTheNextFreeOne() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        try (ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), base));

            final Intent.Notify up = toggle(a, sink);

            final int port = a.mqttPort();
            assertThat(port).isGreaterThan(base);
            assertThat(up.text()).isEqualTo("frontear is up: MQTT 127.0.0.1:" + port);
            assertThat(nodes().get("a").port()).isEqualTo(port);
            assertThat(holder.isBound() && !holder.isClosed()).isTrue();
        }
    }

    /// While another comando holds the registry's lock, a look neither hosts nor writes, says nothing, and gives up
    /// within its bound; once the lock is let go, the next look hosts.
    @Test
    void aHeldRegistryLockHoldsOffAStartUntilItIsLetGo() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        try (final FileChannel channel = FileChannel.open(FrontearFabric.lockFile(registry),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                final FileLock _ = channel.lock()) {

            assertThat(look(a)).isFalse();

            assertThat(a.isRunning()).isFalse();
            assertThat(registry).doesNotExist();
            assertThat(FrontearFabric.taken(base)).as("nothing bound").isFalse();
            assertThat(sink.of(Intent.Notify.class)).as("a look says nothing of it").isEmpty();
        }
        await().atMost(WAIT).until(() -> {
            look(a);
            return a.isRunning();
        });
        assertThat(nodes().keySet()).containsExactly("a");
    }

    /// A second instance of the same name, on another data root, is refused a start while the first's node runs: the
    /// warning names the other's root, process and port, and the registry keeps the first.
    @Test
    void aSecondInstanceOfTheSameNameIsRefused() throws Exception {
        final FrontearFabric first = fabric("a", new RecordingSink());
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric second = new FrontearFabric(dir.resolve("other/fabric"), dir.resolve("other"), "a", sink,
                registry, base);
        fabrics.add(second);
        host(first);
        final Entry entry = nodes().get("a");

        final Intent.Notify refused = toggle(second, sink);

        assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
        assertThat(refused.text()).isEqualTo("Cannot start frontear: another comando named a runs its node [owner="
                + dir.resolve("a") + ", pid=" + PID + ", port=" + first.mqttPort() + ']');
        assertThat(second.isRunning()).isFalse();
        assertThat(second.status()).isEqualTo("stopped");
        assertThat(nodes().get("a")).isEqualTo(entry);
    }

    /// Three instances looking at once with none running, round after round, each host a node on a port of its own,
    /// the one the registry records for it; closed, each keeps its port, and the next round's take others.
    @Test
    void looksAtOnceGiveEachInstanceAPortOfItsOwn() throws Exception {
        final List<Integer> taken = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            final List<String> names = List.of(round + "a", round + "b", round + "c");
            final List<FrontearFabric> all = names.stream().map(n -> fabric(n, new RecordingSink())).toList();
            all.forEach(FrontearFabric::runningOrHost);
            await().atMost(WAIT).until(() -> all.stream().noneMatch(FrontearFabric::busy));

            assertThat(all.stream().allMatch(FrontearFabric::isRunning)).as("round " + round).isTrue();
            final Map<String, Entry> nodes = nodes();
            for (int i = 0; i < all.size(); i++) {
                assertThat(nodes.get(names.get(i)).port()).as("round " + round).isEqualTo(all.get(i).mqttPort());
                taken.add(all.get(i).mqttPort());
            }
            all.forEach(FrontearFabric::close);
        }
        assertThat(new HashSet<>(taken)).as("no port twice").hasSize(taken.size());
        assertThat(nodes().values()).allMatch(e -> e.pid() == 0L);
    }

    /// A stop by hand holds in its own instance: its entry is marked down, and its next looks start nothing until a
    /// start by hand, which takes the same port.
    @Test
    void aStopByHandHoldsInItsOwnInstance() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final FrontearFabric a = fabric("a", sink);
        look(a);
        await().atMost(WAIT).until(a::isRunning);
        final int port = a.mqttPort();

        assertThat(toggle(a, sink).text()).isEqualTo("frontear is down");

        assertThat(look(a)).isFalse();
        assertThat(a.busy()).as("no start under way").isFalse();
        assertThat(nodes().get("a").pid()).isZero();
        assertThat(a.want(false)).isEqualTo("frontear is not running");

        assertThat(a.want(true)).startsWith("starting frontear");
        await().atMost(WAIT).until(() -> !a.busy() && a.isRunning());
        assertThat(a.want(true)).isEqualTo("frontear already runs");
        assertThat(a.mqttPort()).isEqualTo(port);
    }

    /// Off the peers, as every test and picture tool runs, the fabric's registry is the data root's and its port one
    /// the OS picks: a toggle hosts there and never binds the machine's port.
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
        final Entry entry = nodes(home.resolve(FrontearFabric.REGISTRY.getFileName())).get("off");
        assertThat(entry.owner()).isEqualTo(home.toString());
        assertThat(entry.pid()).isEqualTo(PID);
        assertThat(entry.port()).isEqualTo(off.mqttPort());
    }

    /// Starts `fabric`'s node as an agent's `server_start frontear` does, waits until it runs, and lets the peers'
    /// links in, as its peer broker does once subscribed.
    private static void host(final FrontearFabric fabric) {
        start(fabric);
        fabric.openLinks(fabric.mqttPort());
    }

    /// Starts `fabric`'s node as an agent's `server_start frontear` does, and waits until it runs, its links held.
    private static void start(final FrontearFabric fabric) {
        await().atMost(WAIT).until(() -> !fabric.busy());
        fabric.want(true);
        await().atMost(WAIT).until(() -> !fabric.busy() && fabric.isRunning());
    }

    /// Hosts the nodes of `x` and `y`, named so, reconciles each once, and waits until each one's link to the other
    /// connected.
    private void link(final FrontearFabric x, final FrontearFabric y) {
        host(x);
        host(y);
        x.reconcile();
        y.reconcile();
        await().atMost(WAIT).pollInterval(POLL)
                .until(() -> connected("x", x.mqttPort(), "y") && connected("y", y.mqttPort(), "x"));
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

    /// Whether node `instance`, on `port`, has a link to `peer` and it is connected.
    private boolean connected(final String instance, final int port, final String peer) throws Exception {
        final JsonNode link = links(instance, port).get(peer);
        return link != null && link.path("connected").asBoolean(false);
    }

    /// The links of node `instance` on `port` by id, as its control plane answers a `GetState` signed with the
    /// instance's pod key.
    private Map<String, JsonNode> links(final String instance, final int port) throws Exception {
        final KeyPair pair = FrontearFabric.keys(dir.resolve(instance + "/fabric"));
        final String id = "probe-" + probes.incrementAndGet();
        final List<Packet.Publish> replies = new CopyOnWriteArrayList<>();
        try (MqttClient pod = client(port, id, replies)) {
            pod.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.subscribe("replies/" + id, 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.publishAcknowledged(new Signer(pair.getPrivate(), Signer.keyId(pair.getPublic())).sign(Names.CONTROL
                    + instance + "/GetState", bytes("{}"), "replies/" + id, bytes(id)))
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> !replies.isEmpty());
        }
        final JsonNode state = Json.readTree(replies.getFirst().payload());
        assertThat(state.path("ok").asBoolean(false)).as("GetState answered").isTrue();
        final Map<String, JsonNode> links = new TreeMap<>();
        for (final JsonNode l : state.path("links")) {
            links.put(l.path("id").asString(""), l);
        }
        return links;
    }

    /// The text elements of JSON array `array`.
    private static List<String> strings(final JsonNode array) {
        final List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
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
                sink, registry, base);
        fabrics.add(f);
        return f;
    }

    /// A plain client of the node on `port`, its messages collected in `into` on its reader thread.
    private static MqttClient client(final int port, final String id, final List<Packet.Publish> into) {
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

    /// The registry's nodes by slug, as written on disk; it must be of this comando's version.
    private Map<String, Entry> nodes() throws IOException {
        return nodes(registry);
    }

    private static Map<String, Entry> nodes(final Path file) throws IOException {
        final JsonNode tree = Json.readTree(Files.readString(file));
        assertThat(tree.path("version").asInt(0)).isEqualTo(FrontearFabric.REGISTRY_VERSION);
        final Map<String, Entry> nodes = new TreeMap<>();
        for (final Map.Entry<String, JsonNode> n : tree.path("nodes").properties()) {
            final JsonNode e = n.getValue();
            nodes.put(n.getKey(), new Entry(e.path("port").asInt(0), e.path("pid").asLong(0L),
                    e.hasNonNull("started") ? e.path("started").asString() : null, e.path("name").asString(""),
                    e.path("owner").asString("")));
        }
        return nodes;
    }

    /// Writes `nodes` as the registry, as another instance's start would.
    private void write(final Map<String, Entry> nodes) throws IOException {
        final ObjectNode tree = Json.object().put("version", FrontearFabric.REGISTRY_VERSION);
        final ObjectNode all = tree.putObject("nodes");
        nodes.forEach((slug, e) -> all.putObject(slug).put("port", e.port()).put("pid", e.pid())
                .put("started", e.started()).put("name", e.name()).put("owner", e.owner()));
        Files.write(registry, JsonFile.pretty(tree));
    }
}
