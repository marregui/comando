package dev.marregui.comando.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import tools.jackson.core.JacksonException;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ServerSwitch;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.shell.ServerToggle;

/**
 * A HiveMQ Edge comando starts from a distribution zip, installed in its data root ({@code edge/}), on ports of its own
 * (MQTT 18883, WebSocket 18800, admin API 18080, JMX 18010), every one bound to {@code 127.0.0.1}. One such Edge runs
 * on the machine whichever comando instance started it, so that every instance talks through it: a registry file in the
 * user's home ({@code ~/.comando-edge.json}) names its process, the data root that started it and its ports. Only that
 * instance stops it; the others see it running. No other process is ever signalled, so the user's own Edges, on their
 * own ports, are left alone.
 * <p>
 * The Edge's data and log folders sit beside its home ({@code edge/data}, {@code edge/log}), named to it through
 * {@code HIVEMQ_DATA_FOLDER} and {@code HIVEMQ_LOG_FOLDER}: a newer zip replaces the home and keeps them.
 * <p>
 * The distribution is the open-source HiveMQ Edge release comando pins ({@link #RELEASE}), downloaded once into {@code
 * edge/dist} and checked against its published SHA-256, unless the user names a zip of their own.
 */
final class EmbeddedEdge implements ServerToggle, ServerSwitch, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(EmbeddedEdge.class.getName());

    /** The ports of comando's Edge, none of them an Edge's default, so the user's own Edges start beside it. */
    record Ports(int mqtt, int websocket, int api, int jmx) {

        static final Ports DEFAULT = new Ports(18883, 18800, 18080, 18010);

        List<Integer> all() {
            return List.of(mqtt, websocket, api, jmx);
        }
    }

    /** The registry's content: the Edge's process, when it started, and the instance that started it. */
    record Entry(long pid, String owner, String name, Ports ports, String started) {
    }

    /** A distribution zip, or why there is none. */
    record Located(Path zip, String problem) {

        static Located of(final Path zip) {
            return new Located(zip, "");
        }
    }

    /** The registry every comando instance on this machine reads. */
    static final Path REGISTRY = Path.of(System.getProperty("user.home"), ".comando-edge.json");
    /** The HiveMQ Edge release comando runs when the user names no zip: the latest open-source one. */
    static final String RELEASE = "2026.15";
    static final URI RELEASE_ZIP = URI.create("https://www.hivemq.com/releases/edge/hivemq-edge-full-" + RELEASE + ".zip");
    /** The release zip's SHA-256, as published beside it ({@code .sha256}). */
    static final String RELEASE_SHA256 = "1368ee4a1df78227a3fe2c0e4a57e0df770f540f6f4d9101154b829f9c4c9a82";
    private static final Duration CONNECT_WITHIN = Duration.ofSeconds(30);
    static final String ZIP_PROPERTY = "comando.edge.zip";
    static final String ZIP_ENV = "COMANDO_EDGE_ZIP";
    static final Duration READY_WITHIN = Duration.ofSeconds(60);
    /** How long a stop waits for the Edge after SIGTERM before it kills it. */
    static final Duration STOP_WAIT = Duration.ofSeconds(30);
    /** How long {@link #isRunning} trusts its last read of the registry. */
    private static final Duration CHECK_EVERY = Duration.ofSeconds(1);
    /** A process whose start differs from the recorded one by more than this is another process on a reused pid. */
    private static final Duration SAME_PROCESS = Duration.ofSeconds(2);
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final Path root;
    private final String owner;
    private final String name;
    private final IntentSink intents;
    private final Path registry;
    private final Ports ports;
    private final Supplier<Located> distribution;
    private final Predicate<Ports> up;
    private final Duration readyWithin;
    /** The starts, stops and registry reads under way, each on a virtual thread; closed with the Edge. */
    private final Errands errands = new Errands("edge");
    /** A start or stop is under way; a toggle meanwhile does nothing but say so. */
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean checking = new AtomicBoolean();
    /** The registry names a live Edge, as last read; the window reads it every second without waiting. */
    private volatile boolean running;
    private volatile long checkedAt = System.nanoTime() - CHECK_EVERY.toNanos();
    /** The live entry the registry named at its last read, or null: whose Edge the button stands for. */
    private volatile Entry seen;
    /** What a start or stop under way does ("starting", "stopping"), empty until it has read the registry. */
    private volatile String doing = "";

    /**
     * comando's Edge in {@code root}, for the instance on data root {@code owner} named {@code name}; the shared
     * registry, the standard ports, the distribution as {@link #locate} finds it, the pinned release in
     * {@code root/dist}.
     */
    EmbeddedEdge(final Path root, final Path owner, final String name, final IntentSink intents) {
        this(root, owner, name, intents, REGISTRY, Ports.DEFAULT,
                () -> locate(System.getProperty(ZIP_PROPERTY), System.getenv(ZIP_ENV), root.resolve("dist"), RELEASE_ZIP, RELEASE_SHA256, intents),
                EmbeddedEdge::answers, READY_WITHIN);
    }

    /** As above, with the registry, the ports, the distribution, the readiness check and its bound given; for tests. */
    EmbeddedEdge(final Path root, final Path owner, final String name, final IntentSink intents, final Path registry, final Ports ports, final Supplier<Located> distribution,
                 final Predicate<Ports> up, final Duration readyWithin) {
        this.root = root.toAbsolutePath().normalize();
        this.owner = owner.toAbsolutePath().normalize().toString();
        this.name = name == null ? "" : name;
        this.intents = intents;
        this.registry = registry;
        this.ports = ports;
        this.distribution = distribution;
        this.up = up;
        this.readyWithin = readyWithin;
    }

    @Override
    public String name() {
        return "Edge";
    }

    /**
     * Whether the registry names a live Edge, whichever instance started it. Returns at once: the window asks every
     * second, and a read older than a second is redone on a virtual thread, so the answer may be a second late.
     */
    @Override
    public boolean isRunning() {
        if (System.nanoTime() - checkedAt >= CHECK_EVERY.toNanos() && checking.compareAndSet(false, true)) {
            final Thread t = errands.start("edge-check", () -> {
                try {
                    running = running();
                } finally {
                    checkedAt = System.nanoTime();
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

    /** The start or stop under way, else whose Edge it is when another comando started it; as last read. */
    @Override
    public String status() {
        if (busy.get()) {
            return "the Edge is " + (doing.isEmpty() ? "busy" : doing);
        }
        final Entry e = seen;
        return e != null && !owner.equals(e.owner()) ? startedBy(e) + "; only that comando stops it" : "";
    }

    /** Whether this instance started the Edge that runs, as last read; what its presence says (record 124). */
    public boolean startedHere() {
        final Entry e = seen;
        return isRunning() && e != null && owner.equals(e.owner());
    }

    /** The Edge's own log, {@code edge/log/hivemq.log} in this instance's data root. */
    @Override
    public Path log() {
        return root.resolve("log").resolve("hivemq.log");
    }

    private static String startedBy(final Entry e) {
        return "started by " + InstanceName.display(e.name()) + " (" + e.owner() + ")";
    }

    /** Whether the registry names a live Edge, read now; a registry it cannot read names none. */
    boolean running() {
        try {
            final Entry e = read();
            final boolean live = e != null && live(e).isPresent();
            seen = live ? e : null;
            return live;
        } catch (final IOException e) {
            seen = null;
            return false;
        }
    }

    /**
     * Starts comando's Edge when none runs, stops it when this instance started it, and says who did otherwise; on a
     * virtual thread. While one start or stop is under way, a second toggle changes nothing and says what is happening.
     */
    @Override
    public void toggle() {
        if (!busy.compareAndSet(false, true)) {
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "The Edge is " + (doing.isEmpty() ? "busy" : doing) + "; wait for it", false));
            return;
        }
        final Thread t = errands.start("edge-toggle", () -> {
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
            return "the Edge already runs" + (e != null && !owner.equals(e.owner()) ? ", " + startedBy(e) : "");
        }
        if (!up && !live) {
            return "the Edge is not running";
        }
        if (!up && e != null && !owner.equals(e.owner())) {
            return "refused: the Edge was " + startedBy(e) + ", and only that comando stops it";
        }
        if (!busy.compareAndSet(false, true)) {
            return "the Edge is " + (doing.isEmpty() ? "busy" : doing) + "; try again when that ends";
        }
        final Thread t = errands.start(up ? "edge-start" : "edge-stop", () -> {
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
        return (up ? "starting" : "stopping") + " the Edge; the outcome comes as a notice, and ui_get_state's servers show it";
    }

    /** Starts or stops as the registry finds it; {@code want}, when given, is the only direction it may go. */
    private synchronized void flip(final Boolean want) {
        Entry e;
        try {
            e = read();
        } catch (final IOException ex) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN, "Cannot read " + registry
                    + "; remove it if no comando Edge runs: " + ex.getMessage(), null));
            return;
        }
        if (e != null && live(e).isEmpty()) {
            // its process is gone, or the pid is another process's now: the entry is stale
            LOG.log(System.Logger.Level.INFO, "stale Edge registry removed [pid=" + e.pid() + ", owner=" + e.owner() + ']');
            remove(e);
            e = null;
        }
        if (want != null && want != (e == null)) {
            // what the agent asked for holds already: running when it wanted a start, gone when it wanted a stop
            running = e != null;
            checkedAt = System.nanoTime();
            return;
        }
        try {
            if (e == null) {
                doing = "starting";
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Starting the Edge…", false));
                start();
            } else if (owner.equals(e.owner())) {
                doing = "stopping";
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Stopping the Edge…", false));
                stop(e);
            } else {
                // the user asked for a stop this comando may not do: say why, where the eye is, and in the log
                intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN, "Cannot stop the Edge: it was " + startedBy(e)
                        + ", and only that comando stops it", null));
            }
        } finally {
            doing = "";
        }
        running = running();
        checkedAt = System.nanoTime();
    }

    private void start() {
        final Located d = distribution.get();
        if (d.zip() == null) {
            intents.dispatch(new Intent.Notify(Notice.Level.WARN, d.problem(), false));
            return;
        }
        for (final int port : ports.all()) {
            if (taken(port)) {
                intents.dispatch(new Intent.Notify(Notice.Level.WARN, "Cannot start the Edge: port " + port + " on 127.0.0.1 is in use by another process", false));
                return;
            }
        }
        Process p;
        Instant began;
        try {
            final Path home = install(d.zip());
            prepareConfig(home.resolve("conf"), root.resolve("conf"), ports);
            patchJmxPort(home.resolve("bin").resolve("run.sh"), ports.jmx());
            began = Instant.now();
            p = spawn(home);
        } catch (IOException | RuntimeException e) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot start the Edge [root=" + root + "]: " + e.getMessage(), e));
            return;
        }
        final ProcessHandle h = p.toHandle();
        final Entry entry = new Entry(p.pid(), owner, name, ports, h.info().startInstant().orElse(began).toString());
        try {
            if (!claim(entry)) {
                terminate(h);
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Another comando started an Edge meanwhile; this one stopped", false));
                return;
            }
        } catch (final IOException e) {
            terminate(h);
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot write " + registry + ": " + e.getMessage(), e));
            return;
        }
        running = true;
        LOG.log(System.Logger.Level.INFO, "Edge starting [pid=" + p.pid() + ", zip=" + d.zip() + ", root=" + root + ']');
        final String failure = awaitReady(p);
        if (failure.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Edge up [pid=" + p.pid() + ", ports=" + ports + ']');
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Edge is up: MQTT 127.0.0.1:" + ports.mqtt() + ", UI http://localhost:"
                    + ports.api() + " (admin/hivemq)", false));
            return;
        }
        if (terminate(h)) {
            remove(entry);
        }
        intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, failure + "; see " + stdout(), null));
    }

    /** Waits for the admin API: empty when it answers, else why the start failed. */
    private String awaitReady(final Process p) {
        final long deadline = System.nanoTime() + readyWithin.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                if (up.test(ports)) {
                    return "";
                }
                if (!p.isAlive()) {
                    return "The Edge exited before it was up (exit " + p.exitValue() + ")";
                }
                Thread.sleep(250);
            }
            return "The Edge was not up within " + readyWithin.toSeconds() + " s and was stopped";
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return "The Edge's start was interrupted";
        }
    }

    private void stop(final Entry e) {
        final Optional<ProcessHandle> h = live(e);
        if (h.isPresent() && !terminate(h.get())) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN, "The Edge [pid=" + e.pid() + "] has not stopped yet", null));
            return;
        }
        remove(e);
        LOG.log(System.Logger.Level.INFO, "Edge stopped [pid=" + e.pid() + ']');
        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Edge is down", false));
    }

    /**
     * SIGTERM to the recorded process, then up to {@link #STOP_WAIT} for it to exit, then SIGKILL; true once it is
     * gone. Only a process this code started and recorded comes here.
     */
    private static boolean terminate(final ProcessHandle h) {
        h.destroy();
        try {
            h.onExit().get(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (final TimeoutException e) {
            LOG.log(System.Logger.Level.WARNING, "the Edge did not stop within " + STOP_WAIT.toSeconds() + " s; killing it [pid=" + h.pid() + ']');
            h.destroyForcibly();
            try {
                h.onExit().get(5, TimeUnit.SECONDS);
                return true;
            } catch (TimeoutException | ExecutionException again) {
                return !h.isAlive();
            } catch (final InterruptedException again) {
                Thread.currentThread().interrupt();
                return !h.isAlive();
            }
        } catch (final ExecutionException e) {
            return !h.isAlive();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return !h.isAlive();
        }
    }

    /**
     * The distribution: the zip named by the property, else by the environment variable, else the release at {@code
     * url}, kept in {@code dist} under its own name and downloaded there the first time, saying so to the user; when it
     * cannot be had, a problem saying why and how to name a zip instead.
     */
    static Located locate(final String property, final String env, final Path dist, final URI url, final String sha256, final IntentSink intents) {
        final String named = property != null && !property.isBlank() ? property : env != null && !env.isBlank() ? env : null;
        if (named != null) {
            final Path zip = Path.of(named.strip());
            return Files.isRegularFile(zip) ? Located.of(zip)
                    : new Located(null, "No Edge distribution at " + zip + " (named by -D" + ZIP_PROPERTY + " or " + ZIP_ENV + ")");
        }
        final String file = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
        final Path zip = dist.resolve(file);
        if (Files.isRegularFile(zip)) {
            return Located.of(zip);
        }
        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Downloading the Edge, " + file + ", once…", false));
        try {
            download(url, sha256, zip);
            return Located.of(zip);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot download the Edge [url=" + url + ']', e);
            return new Located(null, "Cannot download the Edge from " + url + ": " + e.getMessage() + "; or name a zip with -D"
                    + ZIP_PROPERTY + " or " + ZIP_ENV);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Located(null, "The Edge's download was interrupted");
        }
    }

    /**
     * Downloads {@code url} to {@code zip}: into a {@code .part} file beside it, moved into place only when its SHA-256
     * is {@code sha256}, so a zip in {@code dist} is always a whole and checked one. A failed download leaves nothing.
     */
    static void download(final URI url, final String sha256, final Path zip) throws IOException, InterruptedException {
        Files.createDirectories(zip.getParent());
        final Path part = zip.resolveSibling(zip.getFileName() + ".part");
        try (HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT_WITHIN).build()) {
            final HttpResponse<Path> r = http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofFile(part));
            if (r.statusCode() != 200) {
                throw new IOException("HTTP " + r.statusCode());
            }
            final String actual = sha256(part);
            if (!actual.equalsIgnoreCase(sha256)) {
                throw new IOException("SHA-256 " + actual + ", expected " + sha256);
            }
            Files.move(part, zip, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOG.log(System.Logger.Level.INFO, "Edge downloaded [url=" + url + ", zip=" + zip + ']');
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static String sha256(final Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            final MessageDigest d = MessageDigest.getInstance("SHA-256");
            final byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                d.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /**
     * The Edge's home, unzipped from {@code zip} when there is none yet or the zip is another or newer than the one
     * installed ({@code edge/installed}). The old home is deleted; the data and log folders, beside it, stay.
     */
    Path install(final Path zip) throws IOException {
        Files.createDirectories(root);
        final Path record = root.resolve("installed");
        final Properties installed = new Properties();
        if (Files.isRegularFile(record)) {
            installed.load(new StringReader(Files.readString(record)));
        }
        final String zipPath = zip.toAbsolutePath().normalize().toString();
        final long mtime = Files.getLastModifiedTime(zip).toMillis();
        final String recordedHome = installed.getProperty("home", "");
        final Path old = recordedHome.isEmpty() ? null : inRoot(recordedHome);
        if (old != null && Files.isDirectory(old) && zipPath.equals(installed.getProperty("zip"))
                && mtime <= Long.parseLong(installed.getProperty("mtime", "0"))) {
            return old;
        }
        final Path unpacking = root.resolve(".unpacking");
        deleteTree(unpacking);
        final String top = unzip(zip, unpacking);
        if (old != null) {
            deleteTree(old);
        }
        final Path home = inRoot(top);
        deleteTree(home);
        Files.move(unpacking.resolve(top), home);
        deleteTree(unpacking);
        installed.setProperty("zip", zipPath);
        installed.setProperty("mtime", Long.toString(mtime));
        installed.setProperty("home", top);
        final StringWriter text = new StringWriter();
        installed.store(text, "the Edge distribution installed here");
        AtomicFiles.writeString(record, text.toString());
        LOG.log(System.Logger.Level.INFO, "Edge installed [zip=" + zip + ", home=" + home + ']');
        return home;
    }

    /** A folder directly in the root, never the data or log folder; anything else is refused. */
    private Path inRoot(final String folder) throws IOException {
        final Path p = root.resolve(folder).normalize();
        if (!root.equals(p.getParent()) || p.equals(root.resolve("data")) || p.equals(root.resolve("log"))) {
            throw new IOException("not an Edge home in " + root + ": " + folder);
        }
        return p;
    }

    /**
     * Unzips {@code zip} into {@code into}; returns its one top folder. The {@code .sh} scripts in its {@code bin} are
     * made executable.
     */
    private static String unzip(final Path zip, final Path into) throws IOException {
        String top = null;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (final ZipEntry e : z.stream().toList()) {
                final Path target = into.resolve(e.getName()).normalize();
                if (!target.startsWith(into) || target.equals(into)) {
                    throw new IOException("an entry outside the distribution's folder: " + e.getName());
                }
                final String first = into.relativize(target).getName(0).toString();
                if (top == null) {
                    top = first;
                } else if (!top.equals(first)) {
                    throw new IOException("the distribution has more than one top folder: " + top + ", " + first);
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = z.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (target.getParent().getFileName().toString().equals("bin") && target.toString().endsWith(".sh")) {
                        final Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(target));
                        perms.addAll(EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE));
                        Files.setPosixFilePermissions(target, perms);
                    }
                }
            }
        }
        if (top == null || !Files.isDirectory(into.resolve(top))) {
            throw new IOException("the distribution has no top folder: " + zip);
        }
        return top;
    }

    private static void deleteTree(final Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> all = Files.walk(dir)) {
            for (final Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** The configuration comando's Edge starts with: both MQTT listeners and the admin API, on loopback. */
    static String config(final Ports ports) {
        return """
                <?xml version="1.0"?>
                <hivemq>
                    <mqtt-listeners>
                        <tcp-listener><port>%d</port><bind-address>127.0.0.1</bind-address></tcp-listener>
                        <websocket-listener><port>%d</port><bind-address>127.0.0.1</bind-address><path>/mqtt</path></websocket-listener>
                    </mqtt-listeners>
                    <admin-api>
                        <enabled>true</enabled>
                        <listeners><http-listener><port>%d</port><bind-address>127.0.0.1</bind-address></http-listener></listeners>
                    </admin-api>
                </hivemq>
                """.formatted(ports.mqtt(), ports.websocket(), ports.api());
    }

    /**
     * The Edge's configuration folder in the data root: the shipped files the folder lacks are copied from the home's
     * {@code conf/}; {@code config.xml} is comando's template the first time, after that the saved one with only its
     * MQTT listeners and admin API set to comando's ports, which the instances need to find one another (record 122).
     */
    static void prepareConfig(final Path shipped, final Path conf, final Ports ports) throws IOException {
        prepareConfig(shipped, conf, ports, Files::move);
    }

    /**
     * As {@link #prepareConfig(Path, Path, Ports)}, {@code config.xml} moved into place by {@code mover}: whole or not
     * at all, so a kill mid-write leaves the user's saved settings as they were.
     */
    static void prepareConfig(final Path shipped, final Path conf, final Ports ports, final AtomicFiles.Mover mover) throws IOException {
        Files.createDirectories(conf);
        if (Files.isDirectory(shipped)) {
            try (Stream<Path> files = Files.walk(shipped)) {
                for (final Path f : files.toList()) {
                    final Path target = conf.resolve(shipped.relativize(f).toString());
                    if (Files.isDirectory(f)) {
                        Files.createDirectories(target);
                    } else if (!Files.exists(target) && !f.getFileName().toString().equals("config.xml")) {
                        Files.copy(f, target);
                    }
                }
            }
        }
        final Path config = conf.resolve("config.xml");
        if (!Files.isRegularFile(config)) {
            AtomicFiles.write(config, config(ports).getBytes(StandardCharsets.UTF_8), mover);
            return;
        }
        String next;
        try {
            next = merged(Files.readString(config), ports);
        } catch (final Exception e) {
            // the settings the Edge's own UI saved are not lost: the file stays beside the template that replaces it,
            // under a name of its own, so a later unparseable file never overwrites an earlier one
            Path kept = conf.resolve("config.xml.unparsed-1");
            for (int n = 2; Files.exists(kept); n++) {
                kept = conf.resolve("config.xml.unparsed-" + n);
            }
            Files.copy(config, kept);
            LOG.log(System.Logger.Level.WARNING, "cannot parse the Edge's config.xml, the template replaces it [kept=" + kept + ']', e);
            next = config(ports);
        }
        AtomicFiles.write(config, next.getBytes(StandardCharsets.UTF_8), mover);
    }

    /**
     * {@code xml} with its {@code mqtt-listeners} and {@code admin-api} replaced by comando's, in place, and everything
     * else as it was; the template when {@code xml} does not parse.
     */
    static String enforce(final String xml, final Ports ports) {
        try {
            return merged(xml, ports);
        } catch (final Exception e) {
            LOG.log(System.Logger.Level.WARNING, "cannot parse the Edge's config.xml, the template replaces it", e);
            return config(ports);
        }
    }

    /** {@code xml} with comando's listeners and admin API in place; throws when {@code xml} does not parse. */
    private static String merged(final String xml, final Ports ports) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        final Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        final Document ours = f.newDocumentBuilder().parse(new InputSource(new StringReader(config(ports))));
        final Element top = doc.getDocumentElement();
        for (final String name : List.of("mqtt-listeners", "admin-api")) {
            final Node replacement = doc.importNode(ours.getElementsByTagName(name).item(0), true);
            final NodeList have = top.getElementsByTagName(name);
            if (have.getLength() > 0 && have.item(0).getParentNode() == top) {
                top.replaceChild(replacement, have.item(0));
            } else if (name.equals("mqtt-listeners")) {
                top.insertBefore(replacement, top.getFirstChild());
            } else {
                top.appendChild(replacement);
            }
        }
        final Transformer t = TransformerFactory.newInstance().newTransformer();
        final StringWriter out = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }

    /** The run script's JMX port, 9010 as shipped, set to {@code port}. */
    static void patchJmxPort(final Path runScript, final int port) throws IOException {
        final String s = Files.readString(runScript);
        final String patched = s.replaceAll("jmxremote\\.port=\\d+", "jmxremote.port=" + port);
        if (!patched.equals(s)) {
            AtomicFiles.writeString(runScript, patched);
        }
    }

    /**
     * Runs the home's {@code bin/run.sh}, which execs Java: the process is the Edge's. Its output goes to {@code
     * edge/stdout.log}; the data and log folders are named to it, the JMX listener held on loopback, and the user's own
     * {@code HIVEMQ_*} settings are not passed on.
     */
    private Process spawn(final Path home) throws IOException {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("log"));
        final ProcessBuilder pb = new ProcessBuilder("/bin/bash", "bin/run.sh").directory(home.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdout().toFile()));
        final Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("HIVEMQ_"));
        env.put("HIVEMQ_DATA_FOLDER", root.resolve("data").toString());
        env.put("HIVEMQ_LOG_FOLDER", root.resolve("log").toString());
        // the configuration lives beside the data, outside the home a newer zip replaces: adapters, bridges and every
        // other setting the user or the Edge's UI saved survive a restart and a reinstall
        env.put("HIVEMQ_CONFIG_FOLDER", root.resolve("conf").toString());
        env.put("JAVA_OPTS", "-Dcom.sun.management.jmxremote.host=127.0.0.1 -Djava.rmi.server.hostname=127.0.0.1");
        final Process p = pb.start();
        p.getOutputStream().close();
        return p;
    }

    private Path stdout() {
        return root.resolve("stdout.log");
    }

    /**
     * Something listens on {@code port} on loopback, or the port cannot be bound there as a listener binds it, with
     * {@code SO_REUSEADDR}: a connection the stopped Edge closed lingers in TIME_WAIT for up to a minute, and a bind
     * without it fails on that, refusing a start the Edge itself would make.
     */
    static boolean taken(final int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(LOOPBACK, port), 200);
            return true;
        } catch (final IOException e) {
            // nobody answers; whether it can be bound decides
        }
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(LOOPBACK, port));
            return false;
        } catch (final IOException e) {
            return true;
        }
    }

    /** The admin API answers an HTTP request, whatever its status. */
    private static boolean answers(final Ports ports) {
        try {
            final HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + ports.api() + "/").toURL().openConnection();
            c.setConnectTimeout(1_000);
            c.setReadTimeout(2_000);
            try {
                return c.getResponseCode() > 0;
            } finally {
                c.disconnect();
            }
        } catch (final IOException e) {
            return false;
        }
    }

    /** The registry's entry, or null when there is none. */
    private Entry read() throws IOException {
        try {
            return Files.isRegularFile(registry) ? JsonFile.JSON.readValue(registry.toFile(), Entry.class) : null;
        } catch (final JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The entry's process when it lives and is the one recorded, not another on its pid. */
    private static Optional<ProcessHandle> live(final Entry e) {
        final Optional<ProcessHandle> h = ProcessHandle.of(e.pid()).filter(ProcessHandle::isAlive);
        if (h.isEmpty() || e.started() == null) {
            return h;
        }
        final Optional<Instant> began = h.get().info().startInstant();
        try {
            if (began.isPresent() && Duration.between(Instant.parse(e.started()), began.get()).abs().compareTo(SAME_PROCESS) > 0) {
                return Optional.empty();
            }
        } catch (final RuntimeException unparsable) {
            return Optional.empty();
        }
        return h;
    }

    /** Writes {@code e} unless an entry exists: a temporary file linked into place fails when another came first. */
    private boolean claim(final Entry e) throws IOException {
        final Path tmp = registry.resolveSibling(registry.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.write(tmp, JsonFile.JSON.writeValueAsBytes(e));
        try {
            Files.createLink(registry, tmp);
            return true;
        } catch (final FileAlreadyExistsException taken) {
            return false;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Removes the registry when it still names {@code e}'s process. */
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

    @Override
    public void close() {
        // outside the monitor: a start or stop under way holds it, and the close waits for it, bounded
        try {
            errands.close();
        } finally {
            closeEdge();
        }
    }

    /** Stops the Edge when this instance started it, as QuestDB stops with comando; another's is left running. */
    private synchronized void closeEdge() {
        try {
            final Entry e = read();
            if (e == null || !owner.equals(e.owner())) {
                return;
            }
            final Optional<ProcessHandle> h = live(e);
            if (h.isEmpty() || terminate(h.get())) {
                remove(e);
                LOG.log(System.Logger.Level.INFO, "Edge stopped with comando [pid=" + e.pid() + ']');
            }
        } catch (final IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the registry at close [file=" + registry + ']', ex);
        }
    }
}
