package dev.marregui.comando.app;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.app.FrontearOctopus.Entry;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.port.MachinesPort.Grant;
import dev.marregui.comando.core.port.MachinesPort.Token;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.frontear.octopus.LinkSpec;
import dev.marregui.comando.frontear.octopus.LinkState;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.mcp.Peers;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [FrontearOctopus] across machines (record 189): the node of `main` is the machine's gateway. The links a node keeps
/// are worked out from the registry and the pairings ([FrontearOctopus#wanted]) for three instances, `main`, `alice`
/// and `bob`, on machine `a` paired with `b` and `c`; the live tests run the nodes in this process on ports from a
/// free one up, each machine's folder, remotes file and registry in a temporary folder, never the user's.
/// - nothing paired, every node keeps the links it keeps on one machine
/// - the gateway links each paired machine over TLS, hop limit 2, and carries to each instance its topics on this
///   machine and the paired machines' presences, hop limit 3
/// - every other node carries the paired machines' inbox and reply topics and its own presence to the gateway
/// - unpairing a machine removes its link and its topics from every link
/// - a look puts the machines' topics on the links and takes them off once unpaired
/// - a question published on one machine reaches an instance on the other, its origin the asking machine (disabled
///   until the remote listener, the TLS context and the remote gate are in)
/// - a link to one paired machine takes no other paired machine's certificate
class FrontearOctopusMachinesTest {

    /// Each wait's bound: a start, a connect or a control reply takes well under a second here.
    private static final Duration WAIT = Duration.ofSeconds(30);
    /// Each poll that asks a node for its links opens a client; five a second keeps that cheap.
    private static final Duration POLL = Duration.ofMillis(200);
    private static final long PID = ProcessHandle.current().pid();
    private static final String MAIN = FrontearOctopus.BORDER;
    private static final String LOOPBACK = "127.0.0.1";
    private static final String FP_B = "b".repeat(64);
    private static final String FP_C = "c".repeat(64);
    /// The ports the registry gives the three nodes in the pure tests; nothing listens on them.
    private static final Map<String, Entry> NODES = new TreeMap<>(Map.of(
            MAIN, entry(41_000), "alice", entry(41_001), "bob", entry(41_002)));
    private static final Map<String, Machines.Remote> PAIRED = new TreeMap<>(Map.of(
            "b", new Machines.Remote("b", "192.0.2.2", 18_893, FP_B, Grant.PEER),
            "c", new Machines.Remote("c", "192.0.2.3", 18_894, FP_C, Grant.OVERSEER)));

    @TempDir
    Path dir;
    private final List<FrontearOctopus> octopuses = new ArrayList<>();
    private final AtomicInteger probes = new AtomicInteger();

    @AfterEach
    void tearDown() {
        // each stops the node it hosts, should a test have failed with one running
        octopuses.forEach(FrontearOctopus::close);
    }

    /// With nothing paired, each node links the others with the peer's inbox and reply topics and its own presence
    /// out, hop limit 1, the gateway too, and the gateway links no machine.
    @Test
    void nothingPairedKeepsTheLinksOfOneMachine() {
        for (final String slug : NODES.keySet()) {
            final Map<String, LinkSpec> wanted = FrontearOctopus.wanted(slug, NODES, "a", Map.of());

            final Map<String, LinkSpec> local = new TreeMap<>();
            NODES.forEach((peer, e) -> {
                if (!peer.equals(slug)) {
                    local.put(peer, new LinkSpec(peer, LOOPBACK, e.port(), Set.of(Peers.inboxTopic(peer),
                            Peers.replyTopic(peer), Peers.presenceTopic(slug)), Set.of(), FrontearOctopus.HOP_LIMIT));
                }
            });
            assertThat(wanted).as(slug).isEqualTo(local);
        }
    }

    /// The gateway keeps a link `main@<m>` to each paired machine `m`, over TLS to its host and port, with `m`'s
    /// inbox and reply topics and this machine's presences out, nothing in, hop limit 2; its link to each instance
    /// `X` adds `X`'s inbox and reply topics on this machine and each paired machine's presences, hop limit 3.
    @Test
    void theBorderGatewayLinksEachPairedMachineAndCarriesItsTopicsToEachInstance() {
        final Map<String, LinkSpec> wanted = FrontearOctopus.wanted(MAIN, NODES, "a", PAIRED);

        assertThat(wanted).containsOnlyKeys("alice", "bob", "main@b", "main@c");
        for (final String peer : List.of("alice", "bob")) {
            assertThat(wanted.get(peer)).as(peer).isEqualTo(new LinkSpec(peer, LOOPBACK, NODES.get(peer).port(),
                    Set.of(Peers.inboxTopic(peer), Peers.replyTopic(peer), Peers.presenceTopic(MAIN),
                            "comando/v1/a/instance/" + peer + "/inbox", "comando/v1/a/instance/" + peer + "/reply",
                            "comando/v1/b/instance/+/presence", "comando/v1/c/instance/+/presence"),
                    Set.of(), 3, false));
        }
        assertThat(wanted.get("main@b")).isEqualTo(new LinkSpec("main@b", "192.0.2.2", 18_893,
                Set.of("comando/v1/b/instance/+/inbox", "comando/v1/b/instance/+/reply",
                        "comando/v1/a/instance/+/presence"), Set.of(), 2, true));
        assertThat(wanted.get("main@c")).isEqualTo(new LinkSpec("main@c", "192.0.2.3", 18_894,
                Set.of("comando/v1/c/instance/+/inbox", "comando/v1/c/instance/+/reply",
                        "comando/v1/a/instance/+/presence"), Set.of(), 2, true));
    }

    /// A node other than the gateway adds to its link to the gateway each paired machine's inbox and reply topics and
    /// its own presence on this machine, hop limit still 1; its links to the other instances stay as they are, and it
    /// links no machine itself.
    @Test
    void anInstanceCarriesThePairedMachinesTopicsToTheBorderGateway() {
        final Map<String, LinkSpec> wanted = FrontearOctopus.wanted("alice", NODES, "a", PAIRED);

        assertThat(wanted).containsOnlyKeys(MAIN, "bob");
        assertThat(wanted.get(MAIN)).isEqualTo(new LinkSpec(MAIN, LOOPBACK, NODES.get(MAIN).port(),
                Set.of(Peers.inboxTopic(MAIN), Peers.replyTopic(MAIN), Peers.presenceTopic("alice"),
                        "comando/v1/b/instance/+/inbox", "comando/v1/b/instance/+/reply",
                        "comando/v1/c/instance/+/inbox", "comando/v1/c/instance/+/reply",
                        "comando/v1/a/instance/alice/presence"),
                Set.of(), 1, false));
        assertThat(wanted.get("bob")).isEqualTo(new LinkSpec("bob", LOOPBACK, NODES.get("bob").port(),
                Set.of(Peers.inboxTopic("bob"), Peers.replyTopic("bob"), Peers.presenceTopic("alice")), Set.of(), 1,
                false));
    }

    /// Unpairing machine `c` on `a` takes its link off the gateway's, and its topics off every node's links, as the
    /// pairings read after it say.
    @Test
    void unpairRemovesTheLinkSpec() throws Exception {
        final Machines a = machine("a");
        final Machines b = machine("b");
        final Machines c = machine("c");
        a.listen(LOOPBACK);
        b.listen(LOOPBACK);
        c.listen(LOOPBACK);
        a.pair(b.self().token(), Grant.PEER);
        a.pair(c.self().token(), Grant.PEER);
        assertThat(FrontearOctopus.wanted(MAIN, NODES, "a", a.remotes())).containsKeys("main@b", "main@c");

        assertThat(a.unpair("c")).isTrue();

        final Map<String, LinkSpec> border = FrontearOctopus.wanted(MAIN, NODES, "a", a.remotes());
        final Map<String, LinkSpec> alice = FrontearOctopus.wanted("alice", NODES, "a", a.remotes());
        assertThat(border).containsOnlyKeys("alice", "bob", "main@b");
        for (final LinkSpec spec : List.of(border.get("alice"), border.get("bob"), alice.get(MAIN))) {
            assertThat(spec.out()).as(spec.id()).noneMatch(f -> f.startsWith("comando/v1/c/"))
                    .anyMatch(f -> f.startsWith("comando/v1/b/"));
        }
    }

    /// Two nodes of machine `a`, `main` and `alice`, with `b` paired: a look of each puts on `alice`'s link to the
    /// gateway the paired machine's topics and its own presence on `a`, and on the gateway's link to `alice` hop limit
    /// 3 and `alice`'s topics on `a`; once `b` is unpaired, the next looks put both back as on one machine.
    @Test
    void aLookPutsTheMachinesTopicsOnTheLinksAndUnpairTakesThemOff() throws Exception {
        final Machines a = machine("a");
        final Machines b = machine("b");
        a.listen(LOOPBACK);
        b.listen(LOOPBACK);
        a.pair(b.self().token(), Grant.PEER);
        final Path registry = dir.resolve("a-fabric.json");
        final int base = freePort();
        final FrontearOctopus main = octopus("a-main", MAIN, registry, base, a);
        final FrontearOctopus alice = octopus("a-alice", "alice", registry, base, a);
        host(main);
        host(alice);

        main.reconcile();
        alice.reconcile();

        final Path toMain = dir.resolve("a-alice/fabric/" + MAIN + "/link.json");
        final Path toAlice = dir.resolve("a-main/fabric/alice/link.json");
        await().atMost(WAIT).pollInterval(POLL).until(() -> out(toMain).contains("comando/v1/b/instance/+/inbox"));
        assertThat(out(toMain)).containsExactlyInAnyOrder(Peers.inboxTopic(MAIN), Peers.replyTopic(MAIN),
                Peers.presenceTopic("alice"), "comando/v1/b/instance/+/inbox", "comando/v1/b/instance/+/reply",
                "comando/v1/a/instance/alice/presence");
        assertThat(link(toMain).path("hopLimit").asInt(0)).isEqualTo(FrontearOctopus.HOP_LIMIT);
        await().atMost(WAIT).pollInterval(POLL).until(() -> link(toAlice).path("hopLimit").asInt(0)
                == FrontearOctopus.BORDER_HOP_LIMIT);
        assertThat(out(toAlice)).contains("comando/v1/a/instance/alice/inbox", "comando/v1/a/instance/alice/reply",
                "comando/v1/b/instance/+/presence");

        assertThat(a.unpair("b")).isTrue();
        main.reconcile();
        alice.reconcile();

        await().atMost(WAIT).pollInterval(POLL).until(() -> out(toMain).size() == 3);
        assertThat(out(toMain)).containsExactlyInAnyOrder(Peers.inboxTopic(MAIN), Peers.replyTopic(MAIN),
                Peers.presenceTopic("alice"));
        await().atMost(WAIT).pollInterval(POLL).until(() -> link(toAlice).path("hopLimit").asInt(0)
                == FrontearOctopus.HOP_LIMIT);
        assertThat(out(toAlice)).containsExactlyInAnyOrder(Peers.inboxTopic("alice"), Peers.replyTopic("alice"),
                Peers.presenceTopic(MAIN));
    }

    /// Machines `a` and `b`, each with a gateway and one other instance, `alice` on `a` and `carol` on `b`, each
    /// listening on the loopback and paired with the other's token: once the gateways' links to each other connect,
    /// a question published on `alice`'s node to `carol`'s inbox on `b` reaches a client of `carol`'s node, its
    /// origin `@a`, as `b`'s gateway stamps it on what comes from `a`.
    @Test
    void aQuestionOnOneMachineReachesAnInstanceOnTheOther() throws Exception {
        final Machines a = machine("a");
        final Machines b = machine("b");
        a.listen(LOOPBACK);
        b.listen(LOOPBACK);
        a.pair(b.self().token(), Grant.PEER);
        b.pair(a.self().token(), Grant.PEER);
        final Path registryA = dir.resolve("a-fabric.json");
        final Path registryB = dir.resolve("b-fabric.json");
        final int baseA = freePort();
        final int baseB = freePort();
        final FrontearOctopus mainA = octopus("a-main", MAIN, registryA, baseA, a);
        final FrontearOctopus alice = octopus("a-alice", "alice", registryA, baseA, a);
        final FrontearOctopus mainB = octopus("b-main", MAIN, registryB, baseB, b);
        final FrontearOctopus carol = octopus("b-carol", "carol", registryB, baseB, b);
        for (final FrontearOctopus f : List.of(mainA, alice, mainB, carol)) {
            host(f);
        }
        await().atMost(WAIT).pollInterval(POLL).until(() -> {
            for (final FrontearOctopus f : List.of(mainA, alice, mainB, carol)) {
                f.reconcile();
            }
            return connected("a-main", MAIN, mainA.mqttPort(), "main@b")
                    && connected("b-main", MAIN, mainB.mqttPort(), "main@a")
                    && connected("a-alice", "alice", alice.mqttPort(), MAIN)
                    && connected("b-main", MAIN, mainB.mqttPort(), "carol");
        });
        final String inbox = "comando/v1/b/instance/carol/inbox";
        final List<Packet.Publish> got = new CopyOnWriteArrayList<>();
        try (MqttClient onCarol = client(carol.mqttPort(), "carol-reader", got);
                MqttClient onAlice = client(alice.mqttPort(), "alice-asker", new CopyOnWriteArrayList<>())) {
            onCarol.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            onCarol.subscribe(inbox, 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            onAlice.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            onAlice.publishAcknowledged(new Packet.Publish(inbox, bytes("what is X?"), 1, false, false, 0,
                    "comando/v1/a/instance/alice/reply", bytes("id-1"))).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

            await().atMost(WAIT).pollInterval(POLL).until(() -> !got.isEmpty());
        }
        final Packet.Publish question = got.getFirst();
        assertThat(question.topic()).isEqualTo(inbox);
        assertThat(new String(question.payload(), StandardCharsets.UTF_8)).isEqualTo("what is X?");
        // the origin property frontear's protocol names (record 189): "@" and the machine it came from
        assertThat(question.meta().userProperties()).contains(new Packet.UserProperty("frontear-origin", "@a"));
    }

    /// Machine `a` pairs `c` and a `b` whose token holds `b`'s key but `c`'s port, so the gateway's link `main@b`
    /// reaches `c`'s listener, which shows `c`'s certificate, pinned on `a` for `c`: the link trusts `b`'s key alone,
    /// so its handshake fails, as `c`'s gateway counts it, and it never connects, while `main@c` to the same listener
    /// does. A link that took `c`'s key would finish its handshake and only then be refused its CONNECT.
    @Test
    void aLinkToOnePairedMachineRefusesTheCertificateOfAnother() throws Exception {
        final Machines a = machine("a");
        final Machines b = machine("b");
        final Machines c = machine("c");
        a.listen(LOOPBACK);
        b.listen(LOOPBACK);
        c.listen(LOOPBACK);
        final Token real = Token.parse(b.self().token());
        final Token atC = Token.parse(c.self().token());
        a.pair(new Token(real.machine(), real.host(), atC.port(), real.fingerprint()).text(), Grant.PEER);
        a.pair(c.self().token(), Grant.PEER);
        c.pair(a.self().token(), Grant.PEER);
        final FrontearOctopus mainA = octopus("a-main", MAIN, dir.resolve("a-fabric.json"), freePort(), a);
        final FrontearOctopus mainC = octopus("c-main", MAIN, dir.resolve("c-fabric.json"), freePort(), c);
        host(mainA);
        host(mainC);

        await().atMost(WAIT).pollInterval(POLL).until(() -> {
            mainA.reconcile();
            mainC.reconcile();
            return link(mainA, "main@c").connected()
                    && mainC.node().gateway().remoteStats().handshakeFailures() > 0;
        });
        assertThat(link(mainA, "main@b").connected()).isFalse();
        assertThat(link(mainA, "main@b").sent()).isZero();
    }

    /// Link `id` of `octopus`'s node as the node holds it now; a link not there yet reads as down with no reason.
    private static LinkState link(final FrontearOctopus octopus, final String id) {
        return octopus.node().snapshot().links().stream().filter(l -> l.id().equals(id)).findFirst()
                .orElse(new LinkState(id, false, 0, 0, 0, 0, 0));
    }

    /// Machine `id`: its folder and remotes file in the temporary folder, a free port for its gateway.
    private Machines machine(final String id) throws IOException {
        return new Machines(dir.resolve(id + "-machine"), dir.resolve(id + "-remotes.json"), freePort(), () -> id,
                Instant::now);
    }

    /// The fabric of instance `name` on data root `root` in the temporary folder, of the machine whose registry is
    /// `registry`, its ports from `base` up, reading `machines`.
    private FrontearOctopus octopus(final String root, final String name, final Path registry, final int base,
            final Machines machines) {
        final FrontearOctopus f = new FrontearOctopus(dir.resolve(root + "/fabric"), dir.resolve(root), name,
                new RecordingSink(), registry, base);
        f.machines(machines);
        octopuses.add(f);
        return f;
    }

    /// Starts `octopus`'s node, waits until it runs, and lets the peers' links in.
    private static void host(final FrontearOctopus octopus) {
        await().atMost(WAIT).until(() -> !octopus.busy());
        octopus.want(true);
        await().atMost(WAIT).until(() -> !octopus.busy() && octopus.isRunning());
        octopus.openLinks(octopus.generation());
    }

    /// The link spec in `file`, as the node keeps it on disk; an empty object while there is none.
    private static JsonNode link(final Path file) throws IOException {
        return Files.isRegularFile(file) ? Json.readTree(Files.readString(file)) : Json.object();
    }

    /// The out filters of the link spec in `file`.
    private static List<String> out(final Path file) throws IOException {
        final List<String> out = new ArrayList<>();
        link(file).path("out").forEach(n -> out.add(n.asString()));
        return out;
    }

    /// Whether node `slug` of data root `root`, on `port`, has link `peer` and it is connected, as its control plane
    /// answers a `GetState` signed with the instance's pod key.
    private boolean connected(final String root, final String slug, final int port, final String peer)
            throws Exception {
        final KeyPair pair = FrontearOctopus.keys(dir.resolve(root + "/fabric"));
        final String id = "probe-" + probes.incrementAndGet();
        final List<Packet.Publish> replies = new CopyOnWriteArrayList<>();
        try (MqttClient pod = client(port, id, replies)) {
            pod.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.subscribe("replies/" + id, 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            pod.publishAcknowledged(new Signer(pair.getPrivate(), Signer.keyId(pair.getPublic())).sign(Names.CONTROL
                    + slug + "/GetState", bytes("{}"), "replies/" + id, bytes(id)))
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            await().atMost(WAIT).until(() -> !replies.isEmpty());
        }
        for (final JsonNode l : Json.readTree(replies.getFirst().payload()).path("links")) {
            if (peer.equals(l.path("id").asString(""))) {
                return l.path("connected").asBoolean(false);
            }
        }
        return false;
    }

    /// A plain client of the node on `port`, its messages collected in `into` on its reader thread.
    private static MqttClient client(final int port, final String id, final List<Packet.Publish> into) {
        return new MqttClient(new MqttClient.Options(LOOPBACK, port, id, 0, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE,
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

    private static Entry entry(final int port) {
        return new Entry(port, PID, Peers.PROCESS_STARTED, "", "");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
