package dev.marregui.comando.app;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
import dev.marregui.comando.frontear.control.Signer;
import dev.marregui.comando.ui.shell.ServerToggle;

/**
 * frontear (record 184) as the instances' broker, in place of the comando Edge: a node hosted in this process on the
 * Edge's MQTT port on {@code 127.0.0.1}, so the instances connect to it as they do to the Edge, and the two never both
 * run. One node runs on the machine whichever instance started it: a registry file in the user's home
 * ({@code ~/.comando-fabric.json}, the Edge's schema rules, record 133) names the process that hosts it and that
 * instance's data root, and only that instance stops it; the others see it running and connect as clients. The node
 * lives as long as the instance that hosts it: its close stops the node and clears the registry.
 * <p>
 * The node's data folder is {@code fabric/} in the instance's data root (the links' logs). The one control key it
 * trusts is this instance's own, an Ed25519 pair made at the first start and kept beside them ({@code pod.key},
 * readable by the user alone, and {@code pod.pub}, base64 of the X.509 encoding as {@code Frontear.main} reads a key):
 * this comando is the control pod of the node it hosts.
 * <p>
 * It answers to the name {@code Edge}, so the window's toggle and an agent's {@code server_start edge} reach it as they
 * reach the Edge.
 */
final class FrontearFabric implements ServerToggle, ServerSwitch, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(FrontearFabric.class.getName());

    /** The registry's schema version this comando reads and writes; a later one is read by no instance of this one. */
    static final int REGISTRY_VERSION = 1;
    /** The registry every comando instance on this machine reads. */
    static final Path REGISTRY = Path.of(System.getProperty("user.home"), ".comando-fabric.json");
    /** The comando Edge's MQTT port: the instances connect where they do today, and a running Edge refuses a start. */
    static final int PORT = EmbeddedEdge.Ports.DEFAULT.mqtt();
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
     * Edge's registry allows the same.
     */
    private static final Duration SAME_PROCESS = Duration.ofSeconds(2);
    private static final long PID = ProcessHandle.current().pid();

    private final Path root;
    private final String owner;
    private final String name;
    private final IntentSink intents;
    private final Path registry;
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
    /** The node this instance hosts, or null. Guarded by this. */
    private Hosting hosting;

    /** The fabric with its data in {@code root}, for the instance on data root {@code owner} named {@code name}. */
    FrontearFabric(final Path root, final Path owner, final String name, final IntentSink intents) {
        this(root, owner, name, intents, REGISTRY, PORT);
    }

    /** As above, with the registry and the port given; for tests. */
    FrontearFabric(final Path root, final Path owner, final String name, final IntentSink intents, final Path registry,
            final int port) {
        this.root = root.toAbsolutePath().normalize();
        this.owner = owner.toAbsolutePath().normalize().toString();
        this.name = name == null ? "" : name;
        this.intents = intents;
        this.registry = registry;
        this.port = port;
    }

    @Override
    public String name() {
        return "Edge";
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

    /** The start or stop under way, else who hosts the fabric when another comando does; as last read. */
    @Override
    public String status() {
        if (busy.get()) {
            return "the fabric is " + (doing.isEmpty() ? "busy" : doing);
        }
        final Entry e = seen;
        return e != null && !ours(e) ? startedBy(e) + "; only that comando stops it" : "";
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
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "The fabric is " + (doing.isEmpty() ? "busy" : doing)
                    + "; wait for it", false));
            return;
        }
        final Thread t = errands.start("fabric-toggle", () -> {
            try {
                flip(null);
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
        final boolean live = running();
        final Entry e = seen;
        if (up && live) {
            return "the fabric already runs" + (e != null && !ours(e) ? ", " + startedBy(e) : "");
        }
        if (!up && !live) {
            return "the fabric is not running";
        }
        if (!up && e != null && !ours(e)) {
            return "refused: the fabric was " + startedBy(e) + ", and only that comando stops it";
        }
        if (!busy.compareAndSet(false, true)) {
            return "the fabric is " + (doing.isEmpty() ? "busy" : doing) + "; try again when that ends";
        }
        final Thread t = errands.start(up ? "fabric-start" : "fabric-stop", () -> {
            try {
                flip(up);
            } finally {
                busy.set(false);
            }
        });
        if (t == null) {
            busy.set(false);
            return "refused: comando is closing";
        }
        return (up ? "starting" : "stopping") + " the fabric; the outcome comes as a notice, and ui_get_state's servers"
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
        return "started by " + InstanceName.display(e.name()) + " (" + e.owner() + ")";
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

    /** Starts or stops as the registry finds it; {@code want}, when given, is the only direction it may go. */
    private synchronized void flip(final Boolean want) {
        Entry e;
        try {
            e = read();
        } catch (final IOException ex) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN, "Cannot read the fabric registry; remove it"
                    + " if no comando fabric runs [registry=" + registry + ", cause=" + ex.getMessage() + ']', null));
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
            // what the agent asked for holds already: running when it wanted a start, gone when it wanted a stop
            running = e != null;
            checkedAtNanos = System.nanoTime();
            return;
        }
        try {
            if (e == null) {
                doing = "starting";
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Starting the fabric…", false));
                start();
            } else if (ours(e)) {
                doing = "stopping";
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Stopping the fabric…", false));
                stop(e);
            } else {
                intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN, "Cannot stop the fabric: it was "
                        + startedBy(e) + ", and only that comando stops it", null));
            }
        } finally {
            doing = "";
        }
        running = running();
        checkedAtNanos = System.nanoTime();
    }

    /** Claims the registry, then starts the node; a node that does not start gives the claim back. */
    private void start() {
        if (EmbeddedEdge.taken(port)) {
            intents.dispatch(new Intent.Notify(Notice.Level.WARN, "Cannot start the fabric: port " + port
                    + " on 127.0.0.1 is in use by another process", false));
            return;
        }
        final PublicKey pod;
        try {
            pod = keys(OwnerOnly.folder(root)).getPublic();
        } catch (final IOException | RuntimeException e) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot start the fabric [root=" + root
                    + ", cause=" + e.getMessage() + ']', e));
            return;
        }
        final Entry entry = new Entry(PID, owner, name, port, ProcessHandle.current().info().startInstant()
                .map(Instant::toString).orElse(null));
        try {
            if (!claim(entry)) {
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Another comando started the fabric meanwhile;"
                        + " this one connects to it", false));
                return;
            }
        } catch (final IOException e) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot write the fabric registry [registry="
                    + registry + ", cause=" + e.getMessage() + ']', e));
            return;
        }
        final Frontear node;
        try {
            node = Frontear.start(NODE, port, root, Set.of(pod), InstantSource.system());
        } catch (final IOException | RuntimeException e) {
            remove(entry);
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot start the fabric [port=" + port
                    + ", root=" + root + ", cause=" + e.getMessage() + ']', e));
            return;
        }
        hosting = new Hosting(node, entry);
        running = true;
        LOG.log(System.Logger.Level.INFO, "fabric up [node=" + NODE + ", port=" + node.port() + ", root=" + root
                + ", key=" + Signer.keyId(pod) + ']');
        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "The fabric is up: MQTT 127.0.0.1:" + node.port()
                + ", frontear node " + NODE, false));
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
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN, "The fabric did not close cleanly [cause="
                    + ex.getMessage() + ']', ex));
        } finally {
            remove(e);
        }
        LOG.log(System.Logger.Level.INFO, "fabric stopped [pid=" + e.pid() + ", port=" + e.port() + ']');
        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "The fabric is down", false));
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
            remove(h.entry());
            LOG.log(System.Logger.Level.INFO, "fabric stopped with comando [port=" + h.entry().port() + ']');
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
