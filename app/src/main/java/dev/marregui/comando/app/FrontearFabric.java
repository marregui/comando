package dev.marregui.comando.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ServerSwitch;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.frontear.gateway.InternalSubscriber;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.mcp.Peers;
import dev.marregui.comando.ui.shell.ServerToggle;

/**
 * frontear, comando's fabric between instances (records 184, 185, 187): every instance hosts a node of its own in this
 * process, named after its slug, on a port of its own on {@code 127.0.0.1}, and its peer link connects to that node
 * alone. A registry file in the user's home ({@code ~/.comando-fabric.json}, versioned as record 133 says) holds every
 * slug's node: its port, kept for good so a peer that restarts is found where it was, and the process that runs it,
 * pid 0 once it is down.
 * <p>
 * Node X keeps one link per other slug Y in the registry, id {@code Y}, to Y's port: it forwards what Y reads (Y's
 * inbox and reply topics and X's presence) and, with a hop limit of 1, nothing it got from a link. The links are put
 * through X's own control plane, signed with this instance's key, at every look of the peer link
 * ({@link #runningOrHost}). A link to a peer that is down stays: the messages for it queue in its log, and the peer's
 * restart on the same port drains them.
 * <p>
 * The node's data folder is {@code fabric/} in the instance's data root (the links' logs). The one control key it
 * trusts is this instance's own, an Ed25519 pair made at the first start and kept beside them ({@code pod.key},
 * readable by the user alone, and {@code pod.pub}, base64 of the X.509 encoding as {@code Frontear.main} reads a key):
 * this comando is the control pod of its node.
 * <p>
 * It answers to the name {@code frontear}: the window's toggle and an agent's {@code server_start frontear}, which
 * start and stop this instance's node only.
 */
final class FrontearFabric implements ServerToggle, ServerSwitch, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(FrontearFabric.class.getName());

    /**
     * The registry's schema version this comando writes; version 1, one node for the machine, is replaced at the next
     * start, and a later one is read by no instance of this comando.
     */
    static final int REGISTRY_VERSION = 2;
    /** The registry every comando instance on this machine reads. */
    static final Path REGISTRY = Path.of(System.getProperty("user.home"), ".comando-fabric.json");
    /**
     * The lowest MQTT port a node takes on the machine: the one the instances' broker has had since record 123. Each
     * slug takes the lowest from here that no other slug holds and no process listens on (record 187).
     */
    static final int PORT = 18883;
    /** The control pod's private key, PKCS#8 in base64, readable by the user alone. */
    static final String PRIVATE_KEY = "pod.key";
    /** The control pod's public key, X.509 in base64, the form {@code Frontear.main} reads. */
    static final String PUBLIC_KEY = "pod.pub";
    /** A link's hop limit: each node pushes to a peer what that peer reads, and nothing it got from a link goes on. */
    static final int HOP_LIMIT = 1;
    private static final String ALGORITHM = "Ed25519";
    private static final Set<PosixFilePermission> READABLE = PosixFilePermissions.fromString("rw-r--r--");
    /**
     * A process whose start differs from the recorded one by more than this is another process on a reused pid; the
     * margin comando's instance registries have kept since the first of them.
     */
    private static final Duration SAME_PROCESS = Duration.ofSeconds(2);
    private static final long PID = ProcessHandle.current().pid();
    /** This process's start as the registry records it: the {@code started} its presence says, so the two match. */
    private static final String STARTED = Peers.PROCESS_STARTED;
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final String HOST = "127.0.0.1";
    private static final int LAST_PORT = 65_535;
    /** How long the port probe waits for a listener's answer on loopback, where one answers at once. */
    private static final int PROBE_MILLIS = 200;
    /**
     * How long a start, a stop or a close waits for another's hold of the registry's lock: a node starts and stops in
     * well under a second, so a hold this long is a comando stuck mid-start; a look that gives up tries again at the
     * peer link's next look.
     */
    private static final Duration LOCK_WITHIN = Duration.ofSeconds(5);
    /** How often a held lock is tried again; a start under way ends in tens of milliseconds. */
    private static final Duration POLL_EVERY = Duration.ofMillis(50);
    /**
     * How long a control request waits for the node's answer: its control strand answers in milliseconds, so one not
     * answered by then is a node closing, and the next look tries again.
     */
    private static final Duration CONTROL_WITHIN = Duration.ofSeconds(5);
    /** The internal subscriber that takes the control plane's answers, and the origin of the requests. */
    private static final String CONTROLLER = "comando-fabric";
    /**
     * This JVM's holds of a registry's lock, by lock file: a file lock is the process's, so the instances of one
     * process take turns here first.
     */
    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private final Path root;
    private final String owner;
    private final String name;
    /** The node's name and this instance's key in the registry. */
    private final String slug;
    private final IntentSink intents;
    private final Path registry;
    /**
     * The registry's lock file beside it, never removed: whoever holds its lock alone reads and writes the registry to
     * start, stop or forget.
     */
    private final Path lock;
    /** The lowest port a start may take; 0 for one the OS picks at each start. */
    private final int base;
    /** The starts, stops, reconciles and forgets under way, each on a virtual thread; closed with the fabric. */
    private final Errands errands = new Errands("fabric");
    /** A start or stop is under way; a toggle meanwhile does nothing but say so. */
    private final AtomicBoolean busy = new AtomicBoolean();
    /** A reconcile of the links is under way; a look meanwhile starts none. */
    private final AtomicBoolean linking = new AtomicBoolean();
    /**
     * The generation of the last node start, 0 before any: each start takes the next before it binds, so a
     * connection to a node, and the peer link's subscriptions over it, can only see that node's generation or a later
     * one. Written by the starts, read by the peer link's strand through {@link #generation}.
     */
    private final AtomicLong generation = new AtomicLong();
    /** What a start or stop under way does; written by its errand, read by the window and by agents' calls. */
    private volatile String doing = "";
    /**
     * This instance's user or agent stopped its node: it starts one again only when asked, not on its own. Written by
     * the errands that start and stop and by {@link #want}, read by the peer link's strand.
     */
    private volatile boolean stoppedByHand;
    /**
     * The node this instance hosts, or null. Written under this by the starts, stops and the close; read by the window,
     * the peer link's strand, the reconciles and whoever watches the node through {@link #node()}.
     */
    private volatile Hosted hosted;
    /**
     * The node generation this instance's peer link last finished subscribing against, 0 for none, so a subscribe
     * that completes before a start publishes {@link #hosted} still opens the node's links, and one to a node closed
     * since then opens no later node's, on the same port or not. Written by the peer link's strand
     * ({@link #openLinks}), read by the starts. Each side writes its own field before it reads the other's, both
     * volatile, so of a subscribe and a start that race at least one sees the other.
     */
    private volatile long subscribedGeneration;
    /** Told of a peer whose registry entry names a process that is gone; set at wiring, read by the reconciles. */
    private volatile BiConsumer<String, String> gone = (_, _) -> { };
    /** Run when a link to a peer connects or a peer restarted; set at wiring, read by the reconciles. */
    private volatile Runnable linkUp = () -> { };
    /**
     * The last notice a start of this instance's own posted, so one repeated at every look of the peer link is said
     * once; empty after a start that hosts. Guarded by this.
     */
    private String saidOnItsOwn = "";

    /**
     * The fabric with its data in {@code root}, for the instance on data root {@code owner} named {@code name}, on
     * {@code registry}; its node takes the lowest port from {@code port} up that is free, or, for 0, one the OS picks.
     */
    FrontearFabric(final Path root, final Path owner, final String name, final IntentSink intents, final Path registry,
            final int port) {
        this.root = root.toAbsolutePath().normalize();
        this.owner = owner.toAbsolutePath().normalize().toString();
        this.name = name == null ? "" : name;
        this.slug = InstanceName.slug(this.name);
        this.intents = intents;
        this.registry = registry;
        this.lock = lockFile(registry);
        this.base = port;
    }

    /**
     * The fabric of the instance on data root {@code home} named {@code name}, its data in {@code fabric/} there. On
     * the machine's ({@code machine}), it uses {@link #REGISTRY} and ports from {@link #PORT}; off it, as
     * {@code -Dcomando.peers} false keeps every test and picture tool, a registry in the data root and a port the OS
     * picks, so neither the toggle nor {@code server_start frontear} touches the machine's.
     */
    static FrontearFabric of(final Path home, final String name, final IntentSink intents, final boolean machine) {
        return machine ? new FrontearFabric(home.resolve("fabric"), home, name, intents, REGISTRY, PORT)
                : new FrontearFabric(home.resolve("fabric"), home, name, intents, home.resolve(REGISTRY.getFileName()),
                        0);
    }

    @Override
    public String name() {
        return "frontear";
    }

    /** Whether this instance's node runs. */
    @Override
    public boolean isRunning() {
        return hosted != null;
    }

    /** The node this instance runs now, or null; safe from any thread, as it takes no lock. */
    Frontear node() {
        final Hosted h = hosted;
        return h == null ? null : h.node();
    }

    @Override
    public boolean busy() {
        return busy.get();
    }

    /** The node's state for the toggle's tooltip: a start or stop under way, running on its port, or stopped. */
    @Override
    public String status() {
        if (busy.get()) {
            return doing.isEmpty() ? "busy" : doing;
        }
        final Hosted h = hosted;
        return h == null ? "stopped" : "running on " + HOST + ':' + h.node().port() + " as node " + slug;
    }

    /**
     * Whether this instance's node runs. When it does, the links to the registry's other nodes are reconciled on a
     * virtual thread; when it does not, a start begins on one, unless this instance stopped it by hand; either way it
     * returns at once. The peer link asks it at its first look and at every one after (record 187), so the node starts
     * at launch and the links follow the registry. A start that fails says so once, not at every look.
     */
    boolean runningOrHost() {
        if (hosted != null) {
            if (linking.compareAndSet(false, true)) {
                final Thread t = errands.start("fabric-links", () -> {
                    try {
                        reconcile();
                    } finally {
                        linking.set(false);
                    }
                });
                if (t == null) {
                    linking.set(false);
                }
            }
            return true;
        }
        if (!stoppedByHand && busy.compareAndSet(false, true)) {
            final Thread t = errands.start("fabric-host", () -> {
                try {
                    flip(Boolean.TRUE, true);
                } finally {
                    busy.set(false);
                }
            });
            if (t == null) {
                busy.set(false);
            }
        }
        return false;
    }

    /**
     * Who hears of the peers: {@code goneTo} is told the slug and start of each peer whose registry entry names a
     * process that is gone, at every reconcile, and {@code linkUpTo} runs when a link to a peer connected, or a peer's
     * node started again, since the reconcile before. Both are called on the reconcile's thread, outside its lock,
     * and must not block.
     */
    void onPeers(final BiConsumer<String, String> goneTo, final Runnable linkUpTo) {
        gone = goneTo;
        linkUp = linkUpTo;
    }

    /**
     * Starts this instance's node when it does not run and stops it when it does; on a virtual thread. While one start
     * or stop is under way, a second toggle changes nothing and says what is happening.
     */
    @Override
    public void toggle() {
        if (!busy.compareAndSet(false, true)) {
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "frontear is " + (doing.isEmpty() ? "busy" : doing)
                    + "; wait for it", false));
            return;
        }
        final Thread t = errands.start("fabric-toggle", () -> {
            try {
                flip(null, false);
            } finally {
                busy.set(false);
            }
        });
        if (t == null) {
            busy.set(false);
        }
    }

    /**
     * An agent's start or stop of this instance's node (record 121): the answer says whether there is anything to do,
     * and the start or stop re-checks under the monitor, so a change meanwhile turns it into nothing.
     */
    @Override
    public String want(final boolean up) {
        if (up) {
            stoppedByHand = false;
        }
        final boolean live = hosted != null;
        if (up && live) {
            return "frontear already runs";
        }
        if (!up && !live) {
            return "frontear is not running";
        }
        if (!busy.compareAndSet(false, true)) {
            return "frontear is " + (doing.isEmpty() ? "busy" : doing) + "; try again when that ends";
        }
        final Thread t = errands.start(up ? "fabric-start" : "fabric-stop", () -> {
            try {
                flip(up, false);
            } finally {
                busy.set(false);
            }
        });
        if (t == null) {
            busy.set(false);
            return "refused: comando is closing";
        }
        return (up ? "starting" : "stopping") + " frontear; the outcome comes as a notice, and ui_get_state's servers"
                + " show it";
    }

    @Override
    public void close() {
        // outside the monitor: a start or stop under way holds it, and the close waits for it, bounded
        try {
            errands.close();
        } finally {
            closeFabric();
        }
    }

    /**
     * Lets the other nodes' links into this instance's node, once its peer link has subscribed there: the listener of
     * {@link MqttPeerBroker#onSubscribed}, with the node {@link #generation} it subscribed against. Nothing when the
     * node now running is of another generation, as a node started since then waits for the peer link's
     * subscriptions to it; a node not yet published opens once its start publishes it. Safe from any thread.
     */
    void openLinks(final long subscribedAgainst) {
        // written before hosted is read: the start writes hosted before it reads this
        subscribedGeneration = subscribedAgainst;
        final Hosted h = hosted;
        if (h != null && h.generation() == subscribedAgainst) {
            h.node().openLinks();
        }
    }

    /**
     * The generation of this instance's last node start, 0 before any; a node started later has a greater one. The
     * peer link reads it once connected, before it subscribes, so the subscribe carries the node it went to.
     */
    long generation() {
        return generation.get();
    }

    /** This instance's node's MQTT port on {@code 127.0.0.1}; before it runs, the lowest port a start may take. */
    int mqttPort() {
        final Hosted h = hosted;
        return h == null ? base : h.node().port();
    }

    /**
     * Brings this node's links in line with the registry: a link to each other slug, put when it is missing or the
     * slug's port changed, and none to a slug the registry no longer holds; a peer that is merely down keeps its link.
     * Then tells {@link #onPeers}'s listeners of the peers whose process is gone, and whether a link connected or a
     * peer's node started again (its entry's {@code started} changed), since the reconcile before: a peer that
     * restarted between two reconciles is never seen disconnected, and its new node holds none of this one's retained
     * messages. Does nothing while the node does not run or the registry is not of this version.
     */
    void reconcile() {
        final Hosted h = hosted;
        if (h == null) {
            return;
        }
        final List<Map.Entry<String, String>> down = new ArrayList<>();
        boolean resend = false;
        try {
            // the registry is read under h too, so of two reconciles under way the later acts on the later registry,
            // and none removes a link the other put for a slug its older read did not hold
            synchronized (h) {
                final Map<String, Entry> nodes;
                try {
                    nodes = readCurrent();
                } catch (final IOException e) {
                    LOG.log(System.Logger.Level.DEBUG, "links not reconciled: the registry does not read [registry="
                            + registry + ", cause=" + e.getMessage() + ']');
                    return;
                }
                if (nodes == null) {
                    return;
                }
                for (final Map.Entry<String, Entry> n : nodes.entrySet()) {
                    final String peer = n.getKey();
                    final Entry e = n.getValue();
                    if (peer.equals(slug)) {
                        continue;
                    }
                    if (live(e).isEmpty() && e.started() != null) {
                        down.add(Map.entry(peer, e.started()));
                    }
                    resend |= restarted(h, peer, e.started());
                    put(h, peer, e.port());
                }
                resend |= links(h, nodes.keySet());
            }
        } catch (final InterruptedException _) {
            // the fabric is closing: its errands are interrupted
            Thread.currentThread().interrupt();
            return;
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "links not read [node=" + slug + ", cause=" + e.getMessage() + ']');
        }
        final BiConsumer<String, String> toGone = gone;
        down.forEach(d -> toGone.accept(d.getKey(), d.getValue()));
        if (resend) {
            linkUp.run();
        }
    }

    /**
     * Puts this node's link to peer {@code peer} now, by a reconcile on the caller's thread, when it has put none: the
     * link is the only subscriber here of the peer's inbox, so a question to a peer first seen present would otherwise
     * reach no one until the next look's reconcile. Blocks for the registry's read and the links' control requests,
     * each bounded; nothing while the node does not run.
     */
    void linkTo(final String peer) {
        final Hosted h = hosted;
        if (h == null || peer == null || peer.equals(slug)) {
            return;
        }
        synchronized (h) {
            if (h.ports.containsKey(peer)) {
                return;
            }
        }
        reconcile();
    }

    /**
     * Whether peer {@code peer}'s node started again since the reconcile before: its entry names a start other than
     * the one last read. A peer first read is not, as its link is new and its connect is the edge {@link #links} sees.
     * Under {@code h}.
     */
    private static boolean restarted(final Hosted h, final String peer, final String started) {
        if (started == null) {
            return false;
        }
        final String before = h.started.put(peer, started);
        return before != null && !before.equals(started);
    }

    /**
     * Forgets peer {@code peer} on a virtual thread: its registry entry goes, unless its node runs, and with it this
     * node's link to it; the link's log stays (record 184).
     */
    void forget(final String peer) {
        if (errands.start("fabric-forget", () -> forgetNow(peer)) == null) {
            LOG.log(System.Logger.Level.DEBUG, "peer not forgotten: comando is closing [peer=" + peer + ']');
        }
    }

    /** {@link #forget}'s work, on the caller's thread. */
    void forgetNow(final String peer) {
        if (peer == null || peer.equals(slug)) {
            return;
        }
        final AtomicBoolean dropped = new AtomicBoolean();
        try {
            if (!underLock(() -> dropped.set(drop(peer)))) {
                LOG.log(System.Logger.Level.WARNING, "peer not forgotten: the registry's lock stays held [peer=" + peer
                        + ", lock=" + lock + ", waited=" + LOCK_WITHIN.toSeconds() + " s]");
                return;
            }
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "peer not forgotten: cannot lock the registry [peer=" + peer
                    + ", lock=" + lock + ']', e);
            return;
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        }
        final Hosted h = hosted;
        if (!dropped.get() || h == null) {
            return;
        }
        try {
            synchronized (h) {
                h.ports.remove(peer);
                h.up.remove(peer);
                h.started.remove(peer);
                control(h, "RemoveLink", Json.object().put("id", peer));
            }
            LOG.log(System.Logger.Level.INFO, "link removed [node=" + slug + ", peer=" + peer + ']');
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "no link removed [node=" + slug + ", peer=" + peer + ", cause="
                    + e.getMessage() + ']');
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The control pod's key pair in {@code dir}: the one kept there, else a new one, written (the private half first,
     * readable by the user alone from its creation). Half a pair, as a crash between the two writes leaves it, is
     * replaced, since no node trusted a public half not yet written and a public half alone signs nothing.
     *
     * @throws IOException when a pair is there and does not read: removing both files makes a new one
     */
    static KeyPair keys(final Path dir) throws IOException {
        final Path secret = dir.resolve(PRIVATE_KEY);
        final Path shared = dir.resolve(PUBLIC_KEY);
        if (Files.isRegularFile(secret) && Files.isRegularFile(shared)) {
            try {
                final KeyFactory f = KeyFactory.getInstance(ALGORITHM);
                final PrivateKey key = f.generatePrivate(new PKCS8EncodedKeySpec(decode(secret)));
                final PublicKey pub = f.generatePublic(new X509EncodedKeySpec(decode(shared)));
                return new KeyPair(pub, key);
            } catch (final GeneralSecurityException | IllegalArgumentException e) {
                throw new IOException("not an Ed25519 key pair; remove both files to make a new one [folder=" + dir
                        + ", private=" + PRIVATE_KEY + ", public=" + PUBLIC_KEY + ']', e);
            }
        }
        final KeyPair pair;
        try {
            pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no key pair generator [algorithm=" + ALGORITHM + ']', e);
        }
        // a temporary file is born readable by its owner alone, so the private half is never readable by another
        AtomicFiles.writeString(secret, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
        OwnerOnly.narrow(secret);
        AtomicFiles.writeString(shared, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n");
        try {
            Files.setPosixFilePermissions(shared, READABLE);
        } catch (final UnsupportedOperationException _) {
            LOG.log(System.Logger.Level.DEBUG, "no POSIX permissions on this file system [file=" + shared + ']');
        }
        LOG.log(System.Logger.Level.INFO, "control key pair made [folder=" + dir + ", key="
                + Signer.keyId(pair.getPublic()) + ']');
        return pair;
    }

    private static byte[] decode(final Path file) throws IOException {
        return Base64.getDecoder().decode(Mapped.text(file).strip());
    }

    /**
     * Something listens on {@code port} on loopback, or the port cannot be bound there as a listener binds it, with
     * {@code SO_REUSEADDR}: a connection a stopped node closed lingers in TIME_WAIT for up to a minute, and a bind
     * without it fails on that, refusing a start the node itself would make. Whatever holds the port is only probed,
     * never signalled.
     */
    static boolean taken(final int port) {
        try (final Socket s = new Socket()) {
            s.connect(new InetSocketAddress(LOOPBACK, port), PROBE_MILLIS);
            return true;
        } catch (final IOException _) {
            // nobody answers; whether it can be bound decides
        }
        try (final ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(LOOPBACK, port));
            return false;
        } catch (final IOException _) {
            return true;
        }
    }

    /** The lock file of {@code registry}: beside it, its name with {@code .lock} added. */
    static Path lockFile(final Path registry) {
        final Path r = registry.toAbsolutePath().normalize();
        return r.resolveSibling(r.getFileName() + ".lock");
    }

    /** The entry's process when it lives and is the one recorded, not another on its pid; none for a node down. */
    private static Optional<ProcessHandle> live(final Entry e) {
        if (e.pid() <= 0) {
            return Optional.empty();
        }
        final Optional<ProcessHandle> h = ProcessHandle.of(e.pid()).filter(ProcessHandle::isAlive);
        if (h.isEmpty() || e.started() == null) {
            return h;
        }
        final Optional<Instant> began = h.get().info().startInstant();
        try {
            if (began.isPresent() && Duration.between(Instant.parse(e.started()), began.get()).abs()
                    .compareTo(SAME_PROCESS) > 0) {
                return Optional.empty();
            }
        } catch (final RuntimeException _) {
            return Optional.empty();
        }
        return h;
    }

    /**
     * Starts or stops this instance's node; {@code want}, when given, is the only direction it may go.
     * {@code onItsOwn} is a start no one asked for ({@link #runningOrHost}): it says nothing before it starts, and a
     * failure it met at its last try it does not say again.
     * <p>
     * All of it runs under the registry's lock, taken within {@link #LOCK_WITHIN}: the registry is a file no write to
     * can be fenced, so the lock is what makes the read, the port's choice and probe, the node's bind and the write one
     * step no other comando's start or stop can come between. A start that does not get the lock starts nothing:
     * asked, it says so; a look's tries again at the next look.
     */
    private synchronized void flip(final Boolean want, final boolean onItsOwn) {
        final boolean held;
        try {
            held = underLock(() -> flipHeld(want, onItsOwn));
        } catch (final IOException ex) {
            say(onItsOwn, Notice.Level.WARN, "Cannot lock the frontear registry [lock=" + lock + ", cause="
                    + ex.getMessage() + ']', null);
            return;
        } catch (final InterruptedException _) {
            // the fabric is closing: its errands are interrupted
            Thread.currentThread().interrupt();
            return;
        }
        if (held) {
            return;
        }
        if (onItsOwn) {
            LOG.log(System.Logger.Level.INFO, "frontear registry held by another start; tried again at the next look"
                    + " [lock=" + lock + ", waited=" + LOCK_WITHIN.toSeconds() + " s]");
        } else {
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Another comando is starting or stopping frontear;"
                    + " try again in a moment", false));
        }
    }

    /** {@link #flip}'s work, under the registry's lock. */
    private void flipHeld(final Boolean want, final boolean onItsOwn) {
        final boolean up = hosted != null;
        if (want != null && want == up) {
            // what was asked for holds already
            return;
        }
        try {
            if (up) {
                doing = "stopping";
                stoppedByHand = true;
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Stopping frontear…", false));
                stop();
            } else {
                doing = "starting";
                if (!onItsOwn) {
                    stoppedByHand = false;
                    intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Starting frontear…", false));
                }
                start(onItsOwn);
            }
        } finally {
            doing = "";
        }
    }

    /**
     * Starts this instance's node on its port, then records it in the registry; a node whose entry does not write is
     * closed again. A live entry of another process under this slug refuses the start. Under the registry's lock, so
     * no other comando's start takes or binds the port meanwhile.
     */
    private void start(final boolean onItsOwn) {
        final Map<String, Entry> nodes;
        try {
            nodes = readForWrite();
        } catch (final IOException ex) {
            say(onItsOwn, Notice.Level.WARN, "Cannot read the frontear registry; remove it if no frontear runs"
                    + " [registry=" + registry + ", cause=" + ex.getMessage() + ']', null);
            return;
        }
        final Entry mine = nodes.get(slug);
        if (mine != null && !ours(mine) && live(mine).isPresent()) {
            say(onItsOwn, Notice.Level.WARN, "Cannot start frontear: another comando named " + slug + " runs its node"
                    + " [owner=" + mine.owner() + ", pid=" + mine.pid() + ", port=" + mine.port() + ']', null);
            return;
        }
        final int port = port(nodes, mine);
        if (port < 0) {
            say(onItsOwn, Notice.Level.WARN, "Cannot start frontear: no port on 127.0.0.1 is free [from=" + base
                    + ']', null);
            return;
        }
        final KeyPair pair;
        try {
            pair = keys(OwnerOnly.folder(root));
        } catch (final IOException | RuntimeException e) {
            say(onItsOwn, Notice.Level.ERROR, "Cannot start frontear [root=" + root + ", cause=" + e.getMessage()
                    + ']', e);
            return;
        }
        // taken before the bind, so no connection to this node reads an earlier one
        final long started = generation.incrementAndGet();
        final Frontear node;
        try {
            // link ids are slugs, as node names are, so the node refuses a link aimed at another's port; the others'
            // links wait until this instance's peer link has subscribed here (openLinks), so what they queued for it
            // while it was down finds its subscribers rather than none
            node = Frontear.start(slug, port, root, Set.of(pair.getPublic()), InstantSource.system(), true, true);
        } catch (final IOException | RuntimeException e) {
            say(onItsOwn, Notice.Level.ERROR, "Cannot start frontear [port=" + port + ", root=" + root + ", cause="
                    + e.getMessage() + ']', e);
            return;
        }
        final Hosted h;
        try {
            h = Hosted.of(node, new Signer(pair.getPrivate(), Signer.keyId(pair.getPublic())), slug, started);
            final Map<String, Entry> next = new TreeMap<>(nodes);
            next.put(slug, new Entry(node.port(), PID, STARTED, name, owner));
            write(next);
        } catch (final IOException | RuntimeException e) {
            closeNode(node);
            say(onItsOwn, Notice.Level.ERROR, "Cannot write the frontear registry [registry=" + registry + ", cause="
                    + e.getMessage() + ']', e);
            return;
        }
        hosted = h;
        // the peer link may have subscribed here between the bind and now, its openLinks finding no node
        if (subscribedGeneration == started) {
            node.openLinks();
        }
        saidOnItsOwn = "";
        LOG.log(System.Logger.Level.INFO, "fabric up [node=" + slug + ", port=" + node.port() + ", root=" + root
                + ", key=" + Signer.keyId(pair.getPublic()) + ", asked=" + !onItsOwn + ']');
        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "frontear is up: MQTT " + HOST + ':' + node.port(),
                false));
    }

    /**
     * The port this slug's node takes: 0 when the OS picks; else the one the registry kept for it, unless another
     * process holds it, and otherwise the lowest from {@link #base} up that no other slug holds and no process listens
     * on; -1 when none is free.
     */
    private int port(final Map<String, Entry> nodes, final Entry mine) {
        if (base == 0) {
            return 0;
        }
        if (mine != null && mine.port() > 0 && !taken(mine.port())) {
            return mine.port();
        }
        final Set<Integer> held = new HashSet<>();
        nodes.forEach((peer, e) -> {
            if (!peer.equals(slug)) {
                held.add(e.port());
            }
        });
        for (int p = base; p <= LAST_PORT; p++) {
            if (!held.contains(p) && !taken(p)) {
                if (mine != null && mine.port() > 0) {
                    LOG.log(System.Logger.Level.INFO, "kept port held by another process; this node takes another"
                            + " [node=" + slug + ", kept=" + mine.port() + ", port=" + p + ']');
                }
                return p;
            }
        }
        return -1;
    }

    /**
     * Says {@code text} at {@code level}, a warning or an error also in comando's log with {@code cause}; for a start
     * of this instance's own, only when it is not what the last such start said, so a failure met at every look of the
     * peer link is said once.
     */
    private void say(final boolean onItsOwn, final Notice.Level level, final String text, final Throwable cause) {
        if (onItsOwn) {
            if (text.equals(saidOnItsOwn)) {
                return;
            }
            saidOnItsOwn = text;
        }
        intents.dispatch(level == Notice.Level.INFO ? new Intent.Notify(level, text, false)
                : Intent.Notify.logged(LOG, level, text, cause));
    }

    /** Stops this instance's node and marks it down in the registry, its port kept; under the registry's lock. */
    private void stop() {
        final Hosted h = hosted;
        hosted = null;
        try {
            h.node().close();
        } catch (final RuntimeException ex) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN, "frontear did not close cleanly [cause="
                    + ex.getMessage() + ']', ex));
        } finally {
            markDown();
        }
        LOG.log(System.Logger.Level.INFO, "fabric stopped [node=" + slug + ", port=" + h.node().port() + ']');
        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "frontear is down", false));
    }

    /** Closes {@code node}, which this instance started and will not keep; a failure is logged. */
    private static void closeNode(final Frontear node) {
        try {
            node.close();
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "frontear did not close cleanly [port=" + node.port() + ']', e);
        }
    }

    /** Whether {@code e} names this instance in this process. */
    private boolean ours(final Entry e) {
        return owner.equals(e.owner()) && e.pid() == PID;
    }

    /**
     * Marks this slug's entry down, pid 0, its port and start kept, when it still names this instance's process; under
     * the registry's lock.
     */
    private void markDown() {
        try {
            final Map<String, Entry> nodes = readForWrite();
            final Entry mine = nodes.get(slug);
            if (mine != null && ours(mine)) {
                nodes.put(slug, new Entry(mine.port(), 0L, mine.started(), mine.name(), mine.owner()));
                write(nodes);
            }
        } catch (final IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "cannot mark the node down in the registry [file=" + registry
                    + ", node=" + slug + ']', ex);
        }
    }

    /**
     * Removes {@code peer}'s entry unless its node runs; under the registry's lock. Whether the registry holds no
     * entry for it now.
     */
    private boolean drop(final String peer) {
        try {
            final Map<String, Entry> nodes = readForWrite();
            final Entry e = nodes.get(peer);
            if (e == null) {
                return true;
            }
            if (live(e).isPresent()) {
                LOG.log(System.Logger.Level.INFO, "peer kept in the registry: its node runs [peer=" + peer + ", pid="
                        + e.pid() + ']');
                return false;
            }
            nodes.remove(peer);
            write(nodes);
            LOG.log(System.Logger.Level.INFO, "peer forgotten [peer=" + peer + ", port=" + e.port() + ']');
            return true;
        } catch (final IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "cannot forget the peer in the registry [file=" + registry + ", peer="
                    + peer + ']', ex);
            return false;
        }
    }

    /**
     * The registry's nodes by slug, to change and write back: none when there is no registry, or one of version 1,
     * which this write replaces; throws when it does not read or is of a later version.
     */
    private Map<String, Entry> readForWrite() throws IOException {
        final Map<String, Entry> nodes = readCurrent();
        if (nodes != null) {
            return nodes;
        }
        if (Files.isRegularFile(registry)) {
            LOG.log(System.Logger.Level.INFO, "registry of version 1 replaced [registry=" + registry + ']');
        }
        return new TreeMap<>();
    }

    /**
     * The registry's nodes by slug when it is of this version; null when there is none or it is of version 1. Throws
     * when it does not read or is of a later version. Read without the lock: every write replaces the file whole.
     */
    private Map<String, Entry> readCurrent() throws IOException {
        if (!Files.isRegularFile(registry)) {
            return null;
        }
        final JsonNode tree;
        try {
            tree = Mapped.read(registry, m -> Json.readFirst(m.stream()));
        } catch (final JsonException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        if (!(tree instanceof ObjectNode)) {
            throw new IOException("not a registry [registry=" + registry + ']');
        }
        final int version = tree.path("version").asInt(1);
        if (version > REGISTRY_VERSION) {
            throw new IOException("written by a later comando [version=" + version + ", supported="
                    + REGISTRY_VERSION + ']');
        }
        if (version < REGISTRY_VERSION) {
            return null;
        }
        final Map<String, Entry> nodes = new TreeMap<>();
        for (final Map.Entry<String, JsonNode> n : tree.path("nodes").properties()) {
            final JsonNode e = n.getValue();
            nodes.put(n.getKey(), new Entry(e.path("port").asInt(0), e.path("pid").asLong(0L),
                    e.hasNonNull("started") ? e.path("started").asString() : null, e.path("name").asString(""),
                    e.path("owner").asString("")));
        }
        return nodes;
    }

    /** Writes {@code nodes} as the registry, replacing it whole; under the registry's lock. */
    private void write(final Map<String, Entry> nodes) throws IOException {
        final ObjectNode tree = Json.object().put("version", REGISTRY_VERSION);
        final ObjectNode all = tree.putObject("nodes");
        nodes.forEach((peer, e) -> all.putObject(peer).put("port", e.port()).put("pid", e.pid())
                .put("started", e.started()).put("name", e.name()).put("owner", e.owner()));
        AtomicFiles.write(registry, JsonFile.pretty(tree));
    }

    /** Puts the link to {@code peer} on {@code port} when this node has not put it there yet; under {@code h}. */
    private void put(final Hosted h, final String peer, final int port) throws InterruptedException {
        final Integer put = h.ports.get(peer);
        if (port <= 0 || (put != null && put == port)) {
            return;
        }
        final ObjectNode spec = Json.object().put("id", peer).put("host", HOST).put("port", port);
        spec.putArray("out").add(Peers.inboxTopic(peer)).add(Peers.replyTopic(peer)).add(Peers.presenceTopic(slug));
        spec.putArray("in");
        spec.put("hopLimit", HOP_LIMIT);
        try {
            control(h, "PutLink", spec);
            h.ports.put(peer, port);
            LOG.log(System.Logger.Level.INFO, "link put [node=" + slug + ", peer=" + peer + ", port=" + port + ']');
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot put the link to a peer [node=" + slug + ", peer=" + peer
                    + ", port=" + port + ", cause=" + e.getMessage() + ']');
        }
    }

    /**
     * Reads the node's links, removes those to a slug not in {@code slugs}, and says whether one connected since the
     * last read; under {@code h}.
     */
    private boolean links(final Hosted h, final Set<String> slugs) throws IOException, InterruptedException {
        boolean connected = false;
        for (final JsonNode link : control(h, "GetState", Json.object()).path("links")) {
            final String id = link.path("id").asString("");
            if (!slugs.contains(id)) {
                h.ports.remove(id);
                h.up.remove(id);
                h.started.remove(id);
                control(h, "RemoveLink", Json.object().put("id", id));
                LOG.log(System.Logger.Level.INFO, "link removed: its peer left the registry [node=" + slug + ", peer="
                        + id + ']');
                continue;
            }
            final boolean now = link.path("connected").asBoolean(false);
            final Boolean before = h.up.put(id, now);
            connected |= now && !Boolean.TRUE.equals(before);
        }
        return connected;
    }

    /**
     * Sends control request {@code method} with {@code body} to this node, signed with its pod key as a control client
     * would, and returns the answer once it came, within {@link #CONTROL_WITHIN}.
     *
     * @throws IOException when the node refuses it or does not answer in time
     */
    private JsonNode control(final Hosted h, final String method, final ObjectNode body)
            throws IOException, InterruptedException {
        final String id = Long.toString(h.calls.incrementAndGet());
        final CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        h.replies.put(id, reply);
        try {
            h.node().broker().publish(h.signer().sign(Names.CONTROL + slug + '/' + method, Json.writeBytes(body),
                    h.replyTopic(), id.getBytes(StandardCharsets.UTF_8)), CONTROLLER);
            final JsonNode r = reply.get(CONTROL_WITHIN.toNanos(), TimeUnit.NANOSECONDS);
            if (!r.path("ok").asBoolean(false)) {
                throw new IOException("control request refused [node=" + slug + ", method=" + method + ", reason="
                        + r.path("reason").asString("") + ", detail=" + r.path("detail").asString("") + ']');
            }
            return r;
        } catch (final ExecutionException e) {
            throw new IOException("control answer does not read [node=" + slug + ", method=" + method + ']',
                    e.getCause());
        } catch (final TimeoutException e) {
            throw new IOException("no control answer [node=" + slug + ", method=" + method + ", waited="
                    + CONTROL_WITHIN.toSeconds() + " s]", e);
        } finally {
            h.replies.remove(id);
        }
    }

    /** Stops this instance's node and marks it down in the registry, its port kept. Idempotent. */
    private synchronized void closeFabric() {
        final Hosted h = hosted;
        if (h == null) {
            return;
        }
        hosted = null;
        try {
            h.node().close();
        } finally {
            // under the registry's lock, as a start's write is; one that stays held leaves the entry to go stale
            try {
                if (!underLock(this::markDown)) {
                    LOG.log(System.Logger.Level.WARNING, "registry left to go stale with this process: its lock stays"
                            + " held [lock=" + lock + ", waited=" + LOCK_WITHIN.toSeconds() + " s]");
                }
            } catch (final IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "registry left to go stale with this process: cannot lock it"
                        + " [lock=" + lock + ']', ex);
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
                LOG.log(System.Logger.Level.WARNING, "registry left to go stale with this process: interrupted"
                        + " [lock=" + lock + ']');
            }
            LOG.log(System.Logger.Level.INFO, "fabric stopped with comando [node=" + slug + ", port="
                    + h.node().port() + ']');
        }
    }

    /**
     * Runs {@code action} holding this JVM's turn at the registry's lock, then the lock file's OS lock, both within
     * {@link #LOCK_WITHIN} together; false, with nothing run, when either stays held that long.
     */
    private boolean underLock(final Runnable action) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + LOCK_WITHIN.toNanos();
        final ReentrantLock here = IN_PROCESS.computeIfAbsent(lock, _ -> new ReentrantLock());
        if (!here.tryLock(LOCK_WITHIN.toNanos(), TimeUnit.NANOSECONDS)) {
            return false;
        }
        try (final FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            final FileLock held = tryLock(channel, deadline);
            if (held == null) {
                return false;
            }
            try {
                action.run();
                return true;
            } finally {
                held.release();
            }
        } finally {
            here.unlock();
        }
    }

    /**
     * The lock on {@code channel}, polled every {@link #POLL_EVERY} until {@code deadline}; null when it stays held.
     * Another process's lock answers null; one of this JVM's, taken by another route than {@link #underLock}, throws,
     * and is waited for the same way.
     */
    private static FileLock tryLock(
            final FileChannel channel,
            final long deadline
    ) throws IOException, InterruptedException {
        while (true) {
            try {
                final FileLock held = channel.tryLock();
                if (held != null) {
                    return held;
                }
            } catch (final OverlappingFileLockException _) {
                // held in this JVM: waited for as another process's lock is
            }
            if (deadline - System.nanoTime() <= 0) {
                return null;
            }
            Thread.sleep(POLL_EVERY);
        }
    }

    /**
     * A slug's node in the registry: its MQTT port, kept for good; the process that runs it, 0 while it is down; that
     * process's start, the {@code started} of its presence; and the instance's name and data root.
     */
    record Entry(int port, long pid, String started, String name, String owner) {
    }

    /**
     * The node this instance hosts, the start it came from, the signer of its control requests and the answers they
     * wait for. {@link #ports}, {@link #up} and {@link #started} are guarded by the instance: the links put, whether
     * each was connected and the start its peer's entry named, at the last read.
     */
    private static final class Hosted implements InternalSubscriber {

        private final Frontear node;
        private final Signer signer;
        private final String replyTopic;
        private final Map<String, CompletableFuture<JsonNode>> replies = new ConcurrentHashMap<>();
        private final AtomicLong calls = new AtomicLong();
        private final Map<String, Integer> ports = new HashMap<>();
        private final Map<String, Boolean> up = new HashMap<>();
        private final Map<String, String> started = new HashMap<>();
        private final long generation;

        private Hosted(final Frontear node, final Signer signer, final String slug, final long generation) {
            this.node = node;
            this.signer = signer;
            this.generation = generation;
            // under comando's own topics, so the control plane answers it; no link's filter covers it
            this.replyTopic = "comando/v1/local/fabric/" + slug + "/control";
        }

        /** The node, of start {@code generation}, with its answers' subscriber in place. */
        static Hosted of(final Frontear node, final Signer signer, final String slug, final long generation) {
            final Hosted h = new Hosted(node, signer, slug, generation);
            node.broker().subscribe(CONTROLLER, h.replyTopic, 1, false, h);
            return h;
        }

        Frontear node() {
            return node;
        }

        Signer signer() {
            return signer;
        }

        long generation() {
            return generation;
        }

        String replyTopic() {
            return replyTopic;
        }

        /** An answer of the control plane, on its strand: handed to the request that waits for it. */
        @Override
        public CompletionStage<Integer> deliver(final Packet.Publish message, final String origin) {
            final byte[] correlation = message.correlation();
            final CompletableFuture<JsonNode> waiting = correlation == null ? null
                    : replies.get(new String(correlation, StandardCharsets.UTF_8));
            if (waiting != null) {
                try {
                    waiting.complete(Json.readTree(message.payload()));
                } catch (final JsonException e) {
                    waiting.completeExceptionally(e);
                }
            }
            return CompletableFuture.completedFuture(Packet.SUCCESS);
        }
    }
}
