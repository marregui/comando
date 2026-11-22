package dev.marregui.comando.app;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.loop.Processes;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.port.MachinesPort;
import dev.marregui.comando.frontear.tls.MachineKey;

/**
 * This machine and the machines paired with it (record 189), kept in two files of the user's home. The folder
 * {@code ~/.comando-machine/}, owner-only, holds the machine's key ({@link MachineKey}) and {@code machine.json},
 * {@code {"version":1,"machine":"<id>","host":"<listen address or empty>","port":18893}}, written at the first
 * {@link #self} with the host's short name as the id. {@code ~/.comando-remotes.json}, owner-only, holds the pairings,
 * {@code {"version":1,"machines":{"<id>":{"host","port","fingerprint","grant"}}}}. Both are read without a lock, as
 * every write replaces a file whole, and changed read-modify-write under the lock file beside the remotes file, taken
 * as the fabric takes its registry's. Every instance of this machine reads the same two files; the gateway's fabric
 * reads them at each look.
 * <p>
 * A pairing token is {@code comando-pair:1:<machine>:<host>:<port>:<fingerprint>}. Nothing here touches the network:
 * a listen address is an IP literal checked against this machine's interfaces.
 * <p>
 * A file that does not read leaves this machine unnamed in {@link #self} and nothing paired in {@link #paired}, said
 * once per failure; {@link #listen}, {@link #pair} and {@link #unpair} throw {@link UncheckedIOException} for it.
 */
final class Machines implements MachinesPort {

    private static final System.Logger LOG = System.getLogger(Machines.class.getName());

    /** The machine file's and the remotes file's version (record 133's rule: a later one is read by none). */
    static final int VERSION = 1;
    /** The gateway's remote port while {@code port} of {@code machine.json} names none (ADR 0189 §2.1). */
    static final int PORT = 18_893;
    /** This machine's folder: its key and {@code machine.json}. */
    static final Path FOLDER = Path.of(System.getProperty("user.home"), ".comando-machine");
    /** The machines paired with this one. */
    static final Path REMOTES = Path.of(System.getProperty("user.home"), ".comando-remotes.json");
    /** The machine file's name in {@link #FOLDER}. */
    static final String MACHINE_FILE = "machine.json";
    /** The id when the host's name gives none. */
    private static final String UNNAMED = "machine";
    /** {@code hostname -s} reads a kernel value and answers at once; this only bounds a hung one. */
    private static final Duration HOSTNAME_WITHIN = Duration.ofSeconds(2);
    /** A host's name is at most 255 bytes (POSIX {@code HOST_NAME_MAX}). */
    private static final int HOSTNAME_BYTES = 256;

    private final Path folder;
    private final Path machineFile;
    private final Path remotes;
    private final Path lock;
    private final int port;
    private final Supplier<String> defaultId;
    private final InstantSource clock;
    /** Whether each paired machine's gateway link is connected, as the gateway's last reconcile read it. */
    private final Map<String, Boolean> linkUp = new ConcurrentHashMap<>();
    /** When each paired machine's gateway link was last seen connected, epoch milliseconds. */
    private final Map<String, Long> lastConnectedMillis = new ConcurrentHashMap<>();
    /**
     * The pairings as last read or written: written by the gateway's look thread through {@link #remotes}, by the
     * Machines window's {@code machines-*} errands and by the MCP request threads through {@link #pair} and
     * {@link #unpair}; read by {@link #grantOf} on the peers' threads.
     */
    private volatile Map<String, Remote> known;
    /**
     * The last failure said by {@link #self}, {@link #paired} or {@link #grantOf}, so one repeated at every look is
     * said once; written and read by their callers' threads: the looks, the peers' and the window's.
     */
    private volatile String said = "";
    /** This machine's key, loaded at the first ask; guarded by this. */
    private MachineKey key;

    /**
     * The machine kept in {@code folder}, its pairings in {@code remotes}; a machine file written here names
     * {@code defaultId}'s id and {@code port}; {@code clock} dates the links' last connects.
     */
    Machines(final Path folder, final Path remotes, final int port, final Supplier<String> defaultId,
            final InstantSource clock) {
        this.folder = folder.toAbsolutePath().normalize();
        this.machineFile = this.folder.resolve(MACHINE_FILE);
        this.remotes = remotes.toAbsolutePath().normalize();
        this.lock = FrontearFabric.lockFile(this.remotes);
        this.port = port;
        this.defaultId = defaultId;
        this.clock = clock;
    }

    /** The machine of the user's home: {@link #FOLDER}, {@link #REMOTES}, {@link #PORT}, the host's short name. */
    static Machines of() {
        return new Machines(FOLDER, REMOTES, PORT, Machines::hostId, InstantSource.system());
    }

    /**
     * The token {@code token} as the pairing it names, {@code grant} or {@link Grant#PEER}. Refused with an
     * {@link IllegalArgumentException} as {@link Token#parse} says.
     */
    static Remote parse(final String token, final Grant grant) {
        final Token t = Token.parse(token);
        return new Remote(t.machine(), t.host(), t.port(), t.fingerprint(), grant == null ? Grant.PEER : grant);
    }

    /**
     * The id this host's short name gives ({@code hostname -s}, a local read, never a DNS lookup): lowercase, every
     * run of other characters a dash, at most {@link MachinesPort#MACHINE_ID_CHARS} characters; {@code machine} when it
     * gives none.
     */
    static String hostId() {
        try {
            final Processes.Result r = Processes.run(List.of("hostname", "-s"), HOSTNAME_WITHIN, HOSTNAME_BYTES);
            return r.exitCode() == 0 ? id(r.out()) : UNNAMED;
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.INFO, "machine named " + UNNAMED + ": the host's name does not read [cause="
                    + e.getMessage() + ']');
            return UNNAMED;
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            return UNNAMED;
        }
    }

    /** {@code name} as a machine id, as {@link #hostId} says. */
    static String id(final String name) {
        String s = name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "");
        if (s.length() > MACHINE_ID_CHARS) {
            s = s.substring(0, MACHINE_ID_CHARS);
        }
        // the cut may end on a dash, as may the name
        s = s.replaceAll("-+$", "");
        return s.isEmpty() ? UNNAMED : s;
    }

    /**
     * This machine; the machine file is written at the first ask, under the lock. Its token is empty while it
     * listens nowhere, as another machine could not reach it.
     */
    @Override
    public Self self() {
        try {
            final MachineFile m = machine();
            final String fingerprint = key(m.machine()).fingerprint();
            return new Self(m.machine(), m.host(), m.port(), fingerprint,
                    m.host().isEmpty() ? "" : new Token(m.machine(), m.host(), m.port(), fingerprint).text());
        } catch (final IOException | UncheckedIOException e) {
            sayOnce("this machine does not read [folder=" + folder + ", cause=" + e.getMessage() + ']');
            return new Self("", "", 0, "", "");
        }
    }

    @Override
    public List<Paired> paired() {
        final Map<String, Remote> all;
        try {
            all = remotes();
        } catch (final IOException e) {
            sayOnce("the paired machines do not read [file=" + remotes + ", cause=" + e.getMessage() + ']');
            return List.of();
        }
        final List<Paired> out = new ArrayList<>(all.size());
        all.values().forEach(r -> out.add(paired(r)));
        return out;
    }

    /**
     * Sets the address this machine's gateway listens on, an IP literal of one of its interfaces, never the wildcard;
     * empty to stop listening. The gateway reads it at its next look.
     *
     * @throws UncheckedIOException when the machine file does not read or write
     */
    @Override
    public void listen(final String host) {
        final String h = host == null ? "" : host.strip();
        if (!h.isEmpty()) {
            local(h);
        }
        final MachineFile before = machineOrThrow();
        locked(() -> {
            final MachineFile now = readMachine();
            final MachineFile m = now == null ? before : now;
            writeMachine(new MachineFile(m.machine(), h, m.port()));
            return null;
        });
        LOG.log(System.Logger.Level.INFO, "machine listen address set [machine=" + before.machine() + ", host=" + h
                + ']');
    }

    /**
     * Pairs the machine of {@code token}, replacing an earlier pairing of it; the gateway links it at its next look.
     *
     * @throws UncheckedIOException when a file does not read or write
     */
    @Override
    public Paired pair(final String token, final Grant grant) {
        final Remote r = parse(token, grant);
        final Self me = self();
        if (me.machine().isEmpty()) {
            throw new UncheckedIOException(new IOException("this machine does not read [folder=" + folder + ']'));
        }
        if (r.machine().equals(me.machine())) {
            throw new IllegalArgumentException("pairing token names this machine [machine=" + r.machine() + ']');
        }
        if (r.fingerprint().equals(me.fingerprint())) {
            throw new IllegalArgumentException("pairing token holds this machine's key [machine=" + r.machine()
                    + ", fingerprint=" + r.fingerprint() + ']');
        }
        locked(() -> {
            final Map<String, Remote> all = readRemotes();
            all.put(r.machine(), r);
            writeRemotes(all);
            return null;
        });
        LOG.log(System.Logger.Level.INFO, "machine paired [machine=" + r.machine() + ", host=" + r.host() + ", port="
                + r.port() + ", fingerprint=" + r.fingerprint() + ", grant=" + r.grant() + ']');
        return paired(r);
    }

    /**
     * Unpairs {@code machine}; the gateway removes its link at its next look.
     *
     * @throws UncheckedIOException when the remotes file does not read or write
     */
    @Override
    public boolean unpair(final String machine) {
        final boolean removed = locked(() -> {
            final Map<String, Remote> all = readRemotes();
            if (all.remove(machine) == null) {
                return Boolean.FALSE;
            }
            writeRemotes(all);
            return Boolean.TRUE;
        });
        if (removed) {
            linkUp.remove(machine);
            lastConnectedMillis.remove(machine);
            LOG.log(System.Logger.Level.INFO, "machine unpaired [machine=" + machine + ']');
        }
        return removed;
    }

    /**
     * The pairings by machine id, read from the file now; none when there is no file. Throws when it does not read or
     * is of a later version.
     */
    Map<String, Remote> remotes() throws IOException {
        final Map<String, Remote> all = readRemotes();
        known = Map.copyOf(all);
        return all;
    }

    /**
     * The grant of paired machine {@code machine}, null when it is not paired: from the pairings as last read, so it
     * does no file read but the first.
     */
    Grant grantOf(final String machine) {
        Map<String, Remote> all = known;
        if (all == null) {
            try {
                all = remotes();
            } catch (final IOException e) {
                sayOnce("the paired machines do not read [file=" + remotes + ", cause=" + e.getMessage() + ']');
                return null;
            }
        }
        final Remote r = all.get(machine);
        return r == null ? null : r.grant();
    }

    /**
     * Whether each paired machine's gateway link is connected, by machine id, as the gateway read its links; a
     * machine not named is not connected. Called by the gateway's reconciles and its stop.
     */
    void connected(final Map<String, Boolean> byMachine) {
        final long now = clock.millis();
        linkUp.keySet().retainAll(byMachine.keySet());
        byMachine.forEach((m, up) -> {
            linkUp.put(m, up);
            if (up) {
                lastConnectedMillis.put(m, now);
            }
        });
    }

    /** This machine's key, loaded or made at the first ask, in {@link #FOLDER}, owner-only. */
    synchronized MachineKey key(final String machine) throws IOException {
        if (key == null) {
            key = MachineKey.loadOrCreate(OwnerOnly.folder(folder), machine);
        }
        return key;
    }

    /** This machine's file: the one kept, else a new one, written under the lock. */
    MachineFile machine() throws IOException {
        final MachineFile m = readMachine();
        if (m != null) {
            return m;
        }
        return lockedIo(() -> {
            final MachineFile again = readMachine();
            if (again != null) {
                return again;
            }
            final MachineFile made = new MachineFile(id(defaultId.get()), "", port);
            OwnerOnly.folder(folder);
            writeMachine(made);
            LOG.log(System.Logger.Level.INFO, "machine named [machine=" + made.machine() + ", folder=" + folder + ']');
            return made;
        });
    }

    /** {@link #machine}, a failure unchecked. */
    private MachineFile machineOrThrow() {
        try {
            return machine();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code host} when it is an IP literal of this machine that is not the wildcard; refused otherwise. */
    private static void local(final String host) {
        final InetAddress a;
        try {
            a = InetAddress.ofLiteral(host);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("listen address is not an IP literal [host=" + host + ']', e);
        }
        if (a.isAnyLocalAddress()) {
            throw new IllegalArgumentException("listen address is the wildcard; name one interface's [host=" + host
                    + ']');
        }
        try {
            if (NetworkInterface.getByInetAddress(a) == null) {
                throw new IllegalArgumentException("listen address is not one of this machine's [host=" + host + ']');
            }
        } catch (final SocketException e) {
            throw new UncheckedIOException("this machine's interfaces do not read [host=" + host + ']', e);
        }
    }

    /** {@code r} with its link's state. */
    private Paired paired(final Remote r) {
        return new Paired(r.machine(), r.host(), r.port(), r.fingerprint(), r.grant(),
                Boolean.TRUE.equals(linkUp.get(r.machine())), lastConnectedMillis.getOrDefault(r.machine(), 0L));
    }

    /** The machine file, or null when there is none; throws when it does not read or is of a later version. */
    private MachineFile readMachine() throws IOException {
        if (!Files.isRegularFile(machineFile)) {
            return null;
        }
        final JsonNode tree = tree(machineFile);
        final String machine = tree.path("machine").asString("");
        if (!MACHINE_ID.matcher(machine).matches()) {
            throw new IOException("machine file names no machine id [file=" + machineFile + ", machine=" + machine
                    + ']');
        }
        return new MachineFile(machine, tree.path("host").asString(""), tree.path("port").asInt(port));
    }

    /** Writes {@code m} as the machine file, owner-only; under the lock. */
    private void writeMachine(final MachineFile m) throws IOException {
        final ObjectNode tree = Json.object().put("version", VERSION).put("machine", m.machine()).put("host", m.host())
                .put("port", m.port());
        AtomicFiles.write(machineFile, JsonFile.pretty(tree));
        OwnerOnly.narrow(machineFile);
    }

    /** The pairings by id, none when there is no file; throws when it does not read or is of a later version. */
    private Map<String, Remote> readRemotes() throws IOException {
        final Map<String, Remote> all = new TreeMap<>();
        if (!Files.isRegularFile(remotes)) {
            return all;
        }
        for (final Map.Entry<String, JsonNode> e : tree(remotes).path("machines").properties()) {
            final JsonNode r = e.getValue();
            final String text = r.path("grant").asString(Grant.PEER.text());
            final Optional<Grant> named = Grant.of(text);
            if (named.isEmpty()) {
                sayOnce("a paired machine's grant names none, read as " + Grant.PEER.text() + " [file=" + remotes
                        + ", machine=" + e.getKey() + ", grant=" + text + ']');
            }
            all.put(e.getKey(), new Remote(e.getKey(), r.path("host").asString(""), r.path("port").asInt(0),
                    r.path("fingerprint").asString(""), named.orElse(Grant.PEER)));
        }
        return all;
    }

    /** Writes {@code all} as the remotes file, owner-only, replacing it whole; under the lock. */
    private void writeRemotes(final Map<String, Remote> all) throws IOException {
        final ObjectNode tree = Json.object().put("version", VERSION);
        final ObjectNode machines = tree.putObject("machines");
        all.forEach((id, r) -> machines.putObject(id).put("host", r.host()).put("port", r.port())
                .put("fingerprint", r.fingerprint()).put("grant", r.grant().text()));
        final Path parent = remotes.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
        AtomicFiles.write(remotes, JsonFile.pretty(tree));
        OwnerOnly.narrow(remotes);
        known = Map.copyOf(all);
    }

    /** {@code file}'s JSON object, of this version or one before; throws otherwise. */
    private static JsonNode tree(final Path file) throws IOException {
        final JsonNode tree;
        try {
            tree = Mapped.read(file, m -> Json.readFirst(m.stream()));
        } catch (final JsonException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (!(tree instanceof ObjectNode)) {
            throw new IOException("not a JSON object [file=" + file + ']');
        }
        final int version = tree.path("version").asInt(VERSION);
        if (version > VERSION) {
            throw new IOException("written by a later comando [file=" + file + ", version=" + version + ", supported="
                    + VERSION + ']');
        }
        return tree;
    }

    /** {@link #lockedIo}, a failure unchecked. */
    private <T> T locked(final IoAction<T> action) {
        try {
            return lockedIo(action);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Runs {@code action} under the remotes file's lock, as {@link FrontearFabric} runs its registry's writes, and
     * returns what it returns; a runtime failure of {@code action} goes to the caller as it is. Never nested: the lock
     * is not reentrant across its file.
     */
    private <T> T lockedIo(final IoAction<T> action) throws IOException {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<IOException> failed = new AtomicReference<>();
        final boolean ran;
        try {
            ran = FrontearFabric.underLock(lock, () -> {
                try {
                    out.set(action.run());
                } catch (final IOException e) {
                    failed.set(e);
                }
            });
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for the machines' lock [lock=" + lock + ']');
        }
        if (!ran) {
            throw new IOException("the machines' lock stays held [lock=" + lock + ", waited="
                    + FrontearFabric.LOCK_WITHIN.toSeconds() + " s]");
        }
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }

    /** Logs {@code text} as a warning unless it is the last one said. */
    private void sayOnce(final String text) {
        if (!text.equals(said)) {
            said = text;
            LOG.log(System.Logger.Level.WARNING, text);
        }
    }

    /** A paired machine as the remotes file holds it. */
    record Remote(String machine, String host, int port, String fingerprint, Grant grant) {
    }

    /** This machine's file: its id, the address its gateway listens on (empty for none) and its port. */
    record MachineFile(String machine, String host, int port) {
    }

    /** A step under the lock that reads or writes a file. */
    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }
}
