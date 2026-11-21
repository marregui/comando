package dev.marregui.comando.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
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
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ServerSwitch;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.ui.shell.ServerToggle;

/**
 * frontear, comando's fabric between instances (records 184, 185): a node hosted in this process on MQTT port
 * {@value #PORT} on {@code 127.0.0.1}, where every instance connects. One node runs on the machine whichever instance
 * started it: a registry file in the user's home ({@code ~/.comando-fabric.json}, versioned as record 133 says) names
 * the process that hosts it and that instance's data root, and only that instance stops it; the others see it running
 * and connect as clients. The node lives as long as the instance that hosts it: its close stops the node and clears the
 * registry, and the next instance whose peer link finds no live host starts one ({@link #runningOrHost}).
 * <p>
 * The node's data folder is {@code fabric/} in the instance's data root (the links' logs). The one control key it
 * trusts is this instance's own, an Ed25519 pair made at the first start and kept beside them ({@code pod.key},
 * readable by the user alone, and {@code pod.pub}, base64 of the X.509 encoding as {@code Frontear.main} reads a key):
 * this comando is the control pod of the node it hosts.
 * <p>
 * It answers to the name {@code frontear}: the window's toggle and an agent's {@code server_start frontear}.
 */
final class FrontearFabric implements ServerToggle, ServerSwitch, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(FrontearFabric.class.getName());

    /** The registry's schema version this comando reads and writes; a later one is read by no instance of this one. */
    static final int REGISTRY_VERSION = 1;
    /** The registry every comando instance on this machine reads. */
    static final Path REGISTRY = Path.of(System.getProperty("user.home"), ".comando-fabric.json");
    /**
     * The node's MQTT port: the one the instances' broker has had since record 123, so an instance of an older comando
     * still finds the node, and whatever holds it refuses a start.
     */
    static final int PORT = 18883;
    /** The node's name, one topic level: control messages go to {@code $frontear/control/comando/<Method>}. */
    static final String NODE = "comando";
    /** The control pod's private key, PKCS#8 in base64, readable by the user alone. */
    static final String PRIVATE_KEY = "pod.key";
    /** The control pod's public key, X.509 in base64, the form {@code Frontear.main} reads. */
    static final String PUBLIC_KEY = "pod.pub";
    private static final String ALGORITHM = "Ed25519";
    private static final Set<PosixFilePermission> READABLE = PosixFilePermissions.fromString("rw-r--r--");
    /** How long {@link #isRunning} trusts its last read of the registry: the window asks once a second. */
    private static final Duration CHECK_EVERY = Duration.ofSeconds(1);
    /**
     * A process whose start differs from the recorded one by more than this is another process on a reused pid; the
     * margin comando's instance registries have kept since the first of them.
     */
    private static final Duration SAME_PROCESS = Duration.ofSeconds(2);
    private static final long PID = ProcessHandle.current().pid();
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
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
     * This JVM's holds of a registry's lock, by lock file: a file lock is the process's, so the instances of one
     * process take turns here first.
     */
    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private final Path root;
    private final String owner;
    private final String name;
    private final IntentSink intents;
    private final Path registry;
    /**
     * The registry's lock file beside it, never removed: whoever holds its lock alone reads and writes the registry to
     * start or stop.
     */
    private final Path lock;
    private final int port;
    /** The starts, stops and registry reads under way, each on a virtual thread; closed with the fabric. */
    private final Errands errands = new Errands("fabric");
    /** A start or stop is under way; a toggle meanwhile does nothing but say so. */
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean checking = new AtomicBoolean();
    /** Written by the errands that read the registry, read by the window every second: a live host, as last read. */
    private volatile boolean running;
    /** Written by the errands that read the registry, read by {@link #isRunning} on its callers' threads. */
    private volatile long checkedAtNanos = System.nanoTime() - CHECK_EVERY.toNanos();
    /** The live entry the registry named at its last read, or null; written by every read, read by the window. */
    private volatile Entry seen;
    /** What a start or stop under way does; written by its errand, read by the window and by agents' calls. */
    private volatile String doing = "";
    /**
     * This instance's user or agent stopped the node it hosted: it starts one again only when asked, not on its own.
     * Written by the errands that start and stop and by {@link #want}, read by the peer link's strand.
     */
    private volatile boolean stoppedByHand;
    /**
     * The last start failed because another process holds the port; written by the errands that start, read by the
     * window every second.
     */
    private volatile boolean portHeld;
    /** The node this instance hosts, or null. Guarded by this. */
    private Hosting hosting;
    /**
     * The last notice a start of this instance's own posted, so one repeated at every look of the peer link is said
     * once; empty after a start that hosts. Guarded by this.
     */
    private String saidOnItsOwn = "";

    /**
     * The fabric with its data in {@code root}, for the instance on data root {@code owner} named {@code name}, on
     * {@code registry} and {@code port}.
     */
    FrontearFabric(final Path root, final Path owner, final String name, final IntentSink intents, final Path registry,
            final int port) {
        this.root = root.toAbsolutePath().normalize();
        this.owner = owner.toAbsolutePath().normalize().toString();
        this.name = name == null ? "" : name;
        this.intents = intents;
        this.registry = registry;
        this.lock = lockFile(registry);
        this.port = port;
    }

    /**
     * The fabric of the instance on data root {@code home} named {@code name}, its data in {@code fabric/} there. On
     * the machine's ({@code machine}), it uses {@link #REGISTRY} and {@link #PORT}; off it, as {@code -Dcomando.peers}
     * false keeps every test and picture tool, a registry in the data root and a port the OS picks, so neither the
     * toggle nor {@code server_start frontear} touches the machine's.
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

    /**
     * Whether the registry names a live host, whichever instance it is. Returns at once: a read older than a second is
     * redone on a virtual thread, so the answer may be a second late.
     */
    @Override
    public boolean isRunning() {
        if (System.nanoTime() - checkedAtNanos >= CHECK_EVERY.toNanos() && checking.compareAndSet(false, true)) {
            final Thread t = errands.start("fabric-check", () -> {
                try {
                    running = running();
                } finally {
                    checkedAtNanos = System.nanoTime();
                    checking.set(false);
                }
            });
            if (t == null) {
                checking.set(false);
            }
        }
        return running;
    }

    @Override
    public boolean busy() {
        return busy.get();
    }

    /**
     * The node's state for the toggle's tooltip, always said, as last read: a start or stop under way, running and
     * which comando hosts it, or stopped and, when another process holds the port, that.
     */
    @Override
    public String status() {
        if (busy.get()) {
            return doing.isEmpty() ? "busy" : doing;
        }
        final Entry e = seen;
        if (running && e != null) {
            return ours(e) ? "running, hosted here" : "running, hosted by " + host(e) + "; only that comando stops it";
        }
        return portHeld ? "stopped; port " + port + " on 127.0.0.1 is in use by another process" : "stopped";
    }

    /**
     * Whether the registry names a live host, read now; when it names none, this instance starts one on a virtual
     * thread and returns at once (record 185). The peer link asks it at its first look and at every one after, so
     * frontear starts at launch, and when its host quits, the next instance to look hosts it; when several look at
     * once, the registry's claim leaves one host and the others connect to it. After this instance's own stop by hand,
     * it starts none until asked. A start that fails says so once, not at every look.
     */
    boolean runningOrHost() {
        final boolean live = running();
        if (!live && !stoppedByHand && busy.compareAndSet(false, true)) {
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
        return live;
    }

    /** Whether this instance hosts the fabric that runs, as last read; what its presence says (record 124). */
    public boolean startedHere() {
        final Entry e = seen;
        return isRunning() && e != null && ours(e);
    }

    /**
     * Starts the fabric when none runs, stops it when this instance hosts it, and says who does otherwise; on a virtual
     * thread. While one start or stop is under way, a second toggle changes nothing and says what is happening.
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
     * An agent's start or stop (record 121): the registry is read now, so the answer says whether there is anything to
     * do, and the start or stop re-checks under the monitor, so a change meanwhile turns it into nothing.
     */
    @Override
    public String want(final boolean up) {
        if (up) {
            stoppedByHand = false;
        }
        final boolean live = running();
        final Entry e = seen;
        if (up && live) {
            return "frontear already runs" + (e != null && !ours(e) ? ", " + startedBy(e) : "");
        }
        if (!up && !live) {
            return "frontear is not running";
        }
        if (!up && e != null && !ours(e)) {
            return "refused: frontear was " + startedBy(e) + ", and only that comando stops it";
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

    /** The node's MQTT port on {@code 127.0.0.1}. */
    int mqttPort() {
        return port;
    }

    /** Whether the registry names a live host, read now; a registry it cannot read names none. */
    boolean running() {
        try {
            final Entry e = read();
            final boolean live = e != null && live(e).isPresent();
            seen = live ? e : null;
            return live;
        } catch (final IOException _) {
            seen = null;
            return false;
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

    private static String startedBy(final Entry e) {
        return "started by " + host(e);
    }

    /** The instance that hosts the node {@code e} names, as the user knows it: its name and its data root. */
    private static String host(final Entry e) {
        return InstanceName.display(e.name()) + " (" + e.owner() + ")";
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

    /** The entry {@code tree} holds, null for a JSON {@code null}; an empty file holds none and does not read. */
    private static Entry entry(final JsonNode tree) {
        if (tree.isMissingNode()) {
            throw new JsonException("cannot read an empty registry", "");
        }
        return JsonFile.BINDER.fromTree(tree, Entry.class);
    }

    /** The entry's process when it lives and is the one recorded, not another on its pid. */
    private static Optional<ProcessHandle> live(final Entry e) {
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
     * Starts or stops as the registry finds it; {@code want}, when given, is the only direction it may go.
     * {@code onItsOwn} is a start no one asked for ({@link #runningOrHost}): it says nothing before it starts, and a
     * failure it met at its last try it does not say again.
     * <p>
     * All of it runs under the registry's lock, taken within {@link #LOCK_WITHIN}: the registry is a file no write to
     * can be fenced, so the lock is what makes the read, a stale entry's removal, the port's probe, the claim and the
     * node's bind one step no other comando's start or stop can come between. A start that does not get the lock
     * starts nothing: asked, it says so; a look's tries again at the next look.
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
        Entry e;
        try {
            e = read();
        } catch (final IOException ex) {
            say(onItsOwn, Notice.Level.WARN, "Cannot read the frontear registry; remove it if no frontear runs"
                    + " [registry=" + registry + ", cause=" + ex.getMessage() + ']', null);
            return;
        }
        if (e != null && live(e).isEmpty()) {
            // its process is gone, or the pid is another process's now: the entry is stale
            LOG.log(System.Logger.Level.INFO, "stale fabric registry removed [pid=" + e.pid() + ", owner=" + e.owner()
                    + ']');
            remove(e);
            e = null;
        }
        if (want != null && want != (e == null)) {
            // what was asked for holds already: running when it wanted a start, gone when it wanted a stop
            running = e != null;
            checkedAtNanos = System.nanoTime();
            return;
        }
        try {
            if (e == null) {
                doing = "starting";
                if (!onItsOwn) {
                    stoppedByHand = false;
                    intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Starting frontear…", false));
                }
                start(onItsOwn);
            } else if (ours(e)) {
                doing = "stopping";
                stoppedByHand = true;
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Stopping frontear…", false));
                stop(e);
            } else {
                say(false, Notice.Level.WARN, "Cannot stop frontear: only the comando that started it stops it [name="
                        + e.name() + ", owner=" + e.owner() + ", pid=" + e.pid() + ']', null);
            }
        } finally {
            doing = "";
        }
        running = running();
        checkedAtNanos = System.nanoTime();
    }

    /**
     * Claims the registry, then starts the node; a node that does not start gives the claim back. A port held by
     * another process refuses the start: that process is only probed, never signalled. Under the registry's lock, so
     * no other comando's start probes or binds the port meanwhile, and a probe that finds it held finds a node that
     * runs or another process.
     */
    private void start(final boolean onItsOwn) {
        if (taken(port)) {
            if (running()) {
                // another instance claimed and bound it since the registry was read: it hosts, this one connects
                LOG.log(System.Logger.Level.INFO, "frontear started by another comando meanwhile [port=" + port + ']');
                return;
            }
            portHeld = true;
            say(onItsOwn, Notice.Level.WARN, "Cannot start frontear: another process holds its port; comando leaves"
                    + " it alone, and the instances reach each other once it is free [host=127.0.0.1, port=" + port
                    + ']', null);
            return;
        }
        portHeld = false;
        final PublicKey pod;
        try {
            pod = keys(OwnerOnly.folder(root)).getPublic();
        } catch (final IOException | RuntimeException e) {
            say(onItsOwn, Notice.Level.ERROR, "Cannot start frontear [root=" + root + ", cause=" + e.getMessage()
                    + ']', e);
            return;
        }
        final Entry entry = new Entry(PID, owner, name, port, ProcessHandle.current().info().startInstant()
                .map(Instant::toString).orElse(null));
        try {
            if (!claim(entry)) {
                if (onItsOwn) {
                    LOG.log(System.Logger.Level.INFO, "frontear started by another comando meanwhile [port=" + port
                            + ']');
                } else {
                    intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Another comando started frontear"
                            + " meanwhile; this one connects to it", false));
                }
                return;
            }
        } catch (final IOException e) {
            say(onItsOwn, Notice.Level.ERROR, "Cannot write the frontear registry [registry=" + registry + ", cause="
                    + e.getMessage() + ']', e);
            return;
        }
        final Frontear node;
        try {
            node = Frontear.start(NODE, port, root, Set.of(pod), InstantSource.system());
        } catch (final IOException | RuntimeException e) {
            remove(entry);
            say(onItsOwn, Notice.Level.ERROR, "Cannot start frontear [port=" + port + ", root=" + root + ", cause="
                    + e.getMessage() + ']', e);
            return;
        }
        hosting = new Hosting(node, entry);
        running = true;
        saidOnItsOwn = "";
        LOG.log(System.Logger.Level.INFO, "fabric up [node=" + NODE + ", port=" + node.port() + ", root=" + root
                + ", key=" + Signer.keyId(pod) + ", asked=" + !onItsOwn + ']');
        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "frontear is up: MQTT 127.0.0.1:" + node.port(), false));
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

    /** Stops the node this instance hosts and gives the registry back. */
    private void stop(final Entry e) {
        final Hosting h = hosting;
        hosting = null;
        try {
            if (h != null) {
                h.node().close();
            }
        } catch (final RuntimeException ex) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN, "frontear did not close cleanly [cause="
                    + ex.getMessage() + ']', ex));
        } finally {
            remove(e);
        }
        LOG.log(System.Logger.Level.INFO, "fabric stopped [pid=" + e.pid() + ", port=" + e.port() + ']');
        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "frontear is down", false));
    }

    /** Whether {@code e} names this instance in this process, the only one that holds the node it names. */
    private boolean ours(final Entry e) {
        return owner.equals(e.owner()) && e.pid() == PID;
    }

    /** The registry's entry, or null when there is none; throws when it does not read or is of a later version. */
    private Entry read() throws IOException {
        final Entry e;
        try {
            e = Files.isRegularFile(registry) ? Mapped.read(registry, m -> entry(Json.readFirst(m.stream()))) : null;
        } catch (final JsonException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        if (e != null && e.version() > REGISTRY_VERSION) {
            throw new IOException("written by a later comando [version=" + e.version() + ", supported="
                    + REGISTRY_VERSION + ']');
        }
        return e;
    }

    /**
     * Writes {@code e} unless an entry exists: a temporary file of its own, so two instances of one process never
     * share one, linked into place fails when another came first.
     */
    private boolean claim(final Entry e) throws IOException {
        final Path tmp = Files.createTempFile(registry.toAbsolutePath().getParent(), "." + registry.getFileName()
                + ".", ".tmp");
        try {
            Files.write(tmp, JsonFile.pretty(JsonFile.BINDER.toTree(e)));
            Files.createLink(registry, tmp);
            return true;
        } catch (final FileAlreadyExistsException _) {
            return false;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Removes the registry when it still names {@code e}'s process and instance. */
    private void remove(final Entry e) {
        try {
            final Entry now = read();
            if (now != null && now.pid() == e.pid() && Objects.equals(now.owner(), e.owner())) {
                Files.deleteIfExists(registry);
            }
        } catch (final IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "cannot remove the registry [file=" + registry + ']', ex);
        }
    }

    /** Stops the node this instance hosts; one another instance hosts is left running. Idempotent. */
    private synchronized void closeFabric() {
        final Hosting h = hosting;
        if (h == null) {
            return;
        }
        hosting = null;
        try {
            h.node().close();
        } finally {
            // under the registry's lock, as a start's claim is; one that stays held leaves the entry to go stale
            try {
                if (!underLock(() -> remove(h.entry()))) {
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
            LOG.log(System.Logger.Level.INFO, "fabric stopped with comando [port=" + h.entry().port() + ']');
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
     * The registry's content: its schema's version (record 133; an entry without one is version 1), the hosting
     * process, when it started, the instance that hosts the node and its MQTT port.
     */
    record Entry(int version, long pid, String owner, String name, int port, String started) {
        Entry {
            version = version <= 0 ? 1 : version;
        }

        /** An entry of the schema this comando writes. */
        Entry(final long pid, final String owner, final String name, final int port, final String started) {
            this(REGISTRY_VERSION, pid, owner, name, port, started);
        }
    }

    /** The node this instance hosts, and the registry entry that names it. */
    private record Hosting(Frontear node, Entry entry) {
    }
}
