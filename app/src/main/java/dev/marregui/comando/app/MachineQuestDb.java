package dev.marregui.comando.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import io.questdb.ServerMain;
import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.claude.QuestDbEndpoint;
import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.loop.Processes;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ServerSwitch;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.shell.ServerToggle;

/**
 * The machine's one QuestDB (record 146): a process of its own, started from {@code bin/start.sh} in the machine folder
 * ({@code ~/.comando-questdb}) by whichever writer finds none up, a comando instance or the kit, and outliving it. The
 * registry {@code server.json} names its process, its owner (the data root of the comando that started it, or
 * {@code kit:<kit root>}), its ports and a random token the server keeps in its {@code qdb_server} table: the server is
 * live when its process lives and it answers that token, so a pid another process reused never passes for it. Only its
 * owner stops it; quitting comando leaves it running. Every listener binds {@code 127.0.0.1}: QuestDB binds every
 * interface by default, and its default login is public.
 * <p>
 * Starts are serialised across processes by a POSIX lock on {@code start.lock}, which the kit takes too: two writers
 * that find none up start one server.
 */
final class MachineQuestDb implements ServerToggle, ServerSwitch, QuestDbEndpoint, AutoCloseable {

    /**
     * comando's QuestDB log: one file a day in the root's {@code log/}, ending in {@code .log} so that a panel follows
     * it, kept a week, with errors and advisories only; {@code stdout.log} gets errors and, as QuestDB writes them to
     * every writer, the advisories of each start (21 lines). At INFO the file took 40 MB a day, mostly a line per
     * query, and {@code stdout.log}, never rotated, reached 196 MB (2026-11-14).
     */
    static final String LOG_CONFIG = """
            # Written by comando at each start of its QuestDB: one file a day in log/, kept a week, for
            # Help > Show QuestDB's log, with errors and advisories (the settings at start) but no line per
            # query; stdout.log gets errors (and the advisories, which QuestDB writes to every writer).
            # start.sh names this file (-Dout), so conf/log.conf is not read.
            writers=file,stdout
            w.file.class=io.questdb.log.LogRollingFileWriter
            w.file.location=${log.dir}/questdb-${date:yyyyMMdd}.log
            w.file.level=ERROR,CRITICAL,ADVISORY
            w.file.rollEvery=day
            w.file.lifeDuration=7d
            w.stdout.class=io.questdb.log.LogConsoleWriter
            w.stdout.level=ERROR,CRITICAL
            """;
    /** comando's log configuration, written in the root's {@code conf/} at each start; {@code start.sh} names it. */
    static final String LOG_CONFIG_NAME = "comando-log.conf";
    /** The registry's schema version this comando reads and writes; a later one's server is used, never managed. */
    static final int REGISTRY_VERSION = 1;
    /**
     * The launcher, written whole at each start this comando makes: the machine folder, the four ports, the Java home
     * and QuestDB's version filled in. Its server runs in a process group of its own ({@code set -m}), immune to the
     * launcher's hang-up ({@code nohup}), so it outlives the launcher and whoever ran it.
     * <p>
     * The server is sized for what uses it, tens of small tables written a few rows a second over ILP on HTTP and read
     * over HTTP and PG wire: only the HTTP and PG wire listeners, no web console, no {@code sys.metrics}, one query and
     * one write worker, two network workers so that a long query does not hold the token check, and column files grown
     * in 64 KiB steps. On a scratch server after 100 rows (2026-11-14), the table took 1.2 MB instead of 163 MB and the
     * database 7.7 MB instead of 431 MB; idle for 60 s it used 0.9 to 1.2 % of one core instead of 3.2 %, about 350 MiB
     * resident instead of 401 MiB. {@code stdout.log}, appended to at every start, is rotated past 10 MiB.
     */
    static final String SCRIPT = """
            #!/bin/bash
            # Written by comando at each start of the machine's QuestDB (record 146). Prints the server's pid.
            set -m
            cd "%1$s" || exit 1
            # stdout.log gains every start's output: past 10 MiB it becomes stdout.log.1, the one copy kept
            if [ -f "%1$s/stdout.log" ] && [ "$(wc -c < "%1$s/stdout.log")" -gt 10485760 ]; then
              mv -f "%1$s/stdout.log" "%1$s/stdout.log.1"
            fi
            (umask 077 && : >> "%1$s/stdout.log")
            export QDB_HTTP_NET_BIND_TO=127.0.0.1:%2$d
            export QDB_HTTP_MIN_NET_BIND_TO=127.0.0.1:%3$d
            export QDB_PG_NET_BIND_TO=127.0.0.1:%4$d
            export QDB_LINE_TCP_NET_BIND_TO=127.0.0.1:%5$d
            export QDB_LINE_UDP_ENABLED=false
            export QDB_TELEMETRY_ENABLED=false
            # ILP over TCP: every writer sends ILP over HTTP; the bind above holds should it come back
            export QDB_LINE_TCP_ENABLED=false
            # the health server: comando checks its token over HTTP, nothing asks this port
            export QDB_HTTP_MIN_ENABLED=false
            # no web console: its files are served from a folder that does not exist, so its page answers 404
            export QDB_HTTP_STATIC_PUBLIC_DIRECTORY=no-console
            # sys.metrics, a row a second that nobody reads (93 MB on 2026-11-14)
            export QDB_METRICS_PERSIST_ENABLED=false
            # no table is a materialized or live view
            export QDB_CAIRO_MAT_VIEW_ENABLED=false
            export QDB_MAT_VIEW_REFRESH_WORKER_COUNT=0
            export QDB_CAIRO_LIVE_VIEW_ENABLED=false
            export QDB_LIVE_VIEW_REFRESH_WORKER_COUNT=0
            export QDB_VIEW_COMPILER_WORKER_COUNT=1
            # one query, one write and one WAL apply worker: tens of small tables, a few rows a second
            export QDB_SHARED_WORKER_COUNT=1
            export QDB_WAL_APPLY_WORKER_COUNT=1
            # two network workers, as HTTP and PG wire run queries on them: with one, a query of 11.5 s held a
            # SELECT 1 for 10.5 s, past the 2 s token check, so every instance read the server as down
            export QDB_SHARED_NETWORK_WORKER_COUNT=2
            # idle workers nap after 20 empty loops and sleep 50 ms after 50 (QuestDB: 7,000, 10,000 and 10 ms)
            export QDB_SHARED_WORKER_NAP_THRESHOLD=20
            export QDB_SHARED_WORKER_SLEEP_THRESHOLD=50
            export QDB_SHARED_WORKER_SLEEP_TIMEOUT=50
            export QDB_WAL_APPLY_WORKER_NAP_THRESHOLD=20
            export QDB_WAL_APPLY_WORKER_SLEEP_THRESHOLD=50
            export QDB_WAL_APPLY_WORKER_SLEEP_TIMEOUT=50
            # an open table's column file takes a whole page: 16 MiB made 1.8 GB of tables of 61 to 11,425 rows
            export QDB_CAIRO_WRITER_DATA_APPEND_PAGE_SIZE=64k
            export QDB_CAIRO_WRITER_DATA_INDEX_VALUE_APPEND_PAGE_SIZE=64k
            # WAL segment columns and events, 1 MiB and 128 KiB by default
            export QDB_CAIRO_WAL_WRITER_DATA_APPEND_PAGE_SIZE=64k
            export QDB_CAIRO_WAL_WRITER_EVENT_APPEND_PAGE_SIZE=64k
            # out-of-order merge memory per column, 8 MiB by default; 256 KiB is QuestDB's for its system tables
            export QDB_CAIRO_O3_COLUMN_MEMORY_SIZE=256k
            export QDB_LINE_AUTO_CREATE_NEW_TABLES=false
            export QDB_LINE_AUTO_CREATE_NEW_COLUMNS=false
            nohup "%6$s/bin/java" --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED -Xmx1g \\
              -Dout="%1$s/root/conf/comando-log.conf" \\
              -cp "%1$s/lib/questdb-%7$s.jar" io.questdb.ServerMain -d "%1$s/root" \\
              >> "%1$s/stdout.log" 2>&1 < /dev/null &
            echo $!
            """;

    /**
     * How long {@link #isRunning} trusts its last live check: each costs an HTTP request; the window asks every second.
     */
    private static final Duration CHECK_EVERY = Duration.ofSeconds(2);
    /** After SIGKILL, how long the process is given to be gone; as the Edge's stop, not measured. */
    private static final Duration KILL_WAIT = Duration.ofSeconds(5);
    /** The token's answer counts only within this (record 146, liveness). */
    private static final Duration LIVE_WITHIN = Duration.ofSeconds(2);
    /** How long a start waits for another writer's start to release {@code start.lock}, then refuses (record 146). */
    private static final Duration LOCK_WITHIN = Duration.ofSeconds(90);
    /**
     * One holder of each machine folder's {@code start.lock} at a time in this JVM: a POSIX lock is the process's, and
     * closing any channel on the file drops it, also while another thread of this JVM still works under it.
     */
    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();
    private static final System.Logger LOG = System.getLogger(MachineQuestDb.class.getName());
    /** How often a start asks the new server whether it is up, and retries a held lock (record 146). */
    private static final Duration POLL_EVERY = Duration.ofMillis(100);
    /** A connect to the HTTP port that succeeds within this means another process holds it (record 146). */
    private static final Duration PORT_PROBE = Duration.ofMillis(500);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** {@code start.sh} prints the pid and exits; the kit gives it the same 10 s (record 146). */
    private static final Duration SCRIPT_WITHIN = Duration.ofSeconds(10);
    /** How long a stop waits for the server after SIGTERM before it kills it (record 146). */
    private static final Duration STOP_WAIT = Duration.ofSeconds(20);
    private static final String TOKEN_QUERY = "SELECT token FROM qdb_server ORDER BY ts DESC LIMIT 1";
    /** How long a start waits for the new server's {@code /exec}, then refuses and leaves it (record 146). */
    private static final Duration UP_WITHIN = Duration.ofSeconds(60);

    /** A start or stop is under way; a toggle meanwhile does nothing but say so. */
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean checking = new AtomicBoolean();
    private final Path dataRoot;
    /**
     * The starts, stops and live checks under way, each on a virtual thread; closed with comando, the server left up.
     */
    private final Errands errands = new Errands("questdb");
    private final Path folder;
    private final IntentSink intents;
    private final String name;
    private final String owner;
    private final Ports ports;
    private final Path registry;
    private final Path root;
    /** Written by the live checks, on any thread; read by {@link #isRunning} on the EDT, which never waits for one. */
    private volatile long checkedAtNanos = System.nanoTime() - CHECK_EVERY.toNanos();
    /** What a start or stop under way does; written by its errand, read by the EDT's {@link #status}. */
    private volatile String doing = "";
    /**
     * The pid {@code start.sh} printed at this instance's last launch, 0 before one; written by a start's errand, read
     * by tests.
     */
    private volatile long launchedPid;
    /** The registry names a live server, as last checked; written by the live checks, read by the EDT. */
    private volatile boolean running;
    /** The live entry at the last check, or null: whose server the button stands for; written and read as above. */
    private volatile Entry seen;

    /** The machine's QuestDB in {@code machineFolder} on the standard ports, for the instance on {@code dataRoot}. */
    MachineQuestDb(final Path machineFolder, final Path dataRoot, final String instanceName, final IntentSink intents) {
        this(machineFolder, dataRoot, instanceName, intents, Ports.DEFAULT);
    }

    /** As above, on {@code ports}; for tests, which never bind the standard ones. */
    MachineQuestDb(
            final Path machineFolder,
            final Path dataRoot,
            final String instanceName,
            final IntentSink intents,
            final Ports ports
    ) {
        this.folder = machineFolder.toAbsolutePath().normalize();
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.owner = this.dataRoot.toString();
        this.name = InstanceName.display(instanceName);
        this.intents = intents;
        this.ports = ports;
        this.registry = folder.resolve("server.json");
        this.root = folder.resolve("root");
    }

    @Override
    public boolean busy() {
        return busy.get();
    }

    /** Stops this instance's errands, waiting a bounded time; never the server, which outlives comando. Idempotent. */
    @Override
    public void close() {
        errands.close();
    }

    /**
     * Runs record 146's start: the live server's endpoint, else one started now under {@code start.lock}, with this
     * instance as its owner; empty with the refusal logged. Blocks for the live checks ({@link #LIVE_WITHIN} each),
     * this JVM's hold of the folder and then {@code start.lock} ({@link #LOCK_WITHIN} each), the jar's copy when it is
     * missing, {@code start.sh} ({@link #SCRIPT_WITHIN}), the wait for the server ({@link #UP_WITHIN}) and its two
     * statements ({@link #UP_WITHIN} each): minutes, never on the EDT.
     */
    @Override
    public Optional<URI> ensureUp() {
        final Outcome o = up();
        if (o.entry() == null) {
            LOG.log(System.Logger.Level.WARNING, "cannot have the machine's QuestDB [folder=" + folder + ", why="
                    + o.refusal() + ']');
            return Optional.empty();
        }
        return Optional.of(uri(o.entry()));
    }

    /**
     * Whether the registry names a live server, whichever writer started it. Returns at once: the window asks every
     * second, and a check older than {@link #CHECK_EVERY} is redone on a virtual thread, so the answer may be late by
     * that much.
     */
    @Override
    public boolean isRunning() {
        if (System.nanoTime() - checkedAtNanos >= CHECK_EVERY.toNanos() && checking.compareAndSet(false, true)) {
            final Thread t = errands.start("questdb-check", () -> {
                try {
                    liveEntry();
                } finally {
                    checking.set(false);
                }
            });
            if (t == null) {
                checking.set(false);
            }
        }
        return running;
    }

    /** The live server's endpoint, checked now (its token, at most 2 s), starting nothing. */
    @Override
    public Optional<URI> live() {
        final Entry e = liveEntry();
        return e == null ? Optional.empty() : Optional.of(uri(e));
    }

    /** The newest daily log in the root's {@code log/}, else today's, which the next start writes. */
    @Override
    public Path log() {
        final Path dir = root.resolve("log");
        try (final Stream<Path> files = Files.list(dir)) {
            final Optional<Path> newest = files.filter(f -> f.getFileName().toString().matches("questdb-\\d{8}\\.log"))
                    .max(Comparator.comparing(f -> f.getFileName().toString()));
            if (newest.isPresent()) {
                return newest.get();
            }
        } catch (final IOException | UncheckedIOException _) {
            // no log folder yet: today's file, which says there is no log yet
        }
        return dir.resolve("questdb-" + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC))
                + ".log");
    }

    @Override
    public String name() {
        return "QuestDB";
    }

    /** The start or stop under way, else who started the server when this instance did not; as last checked. */
    @Override
    public String status() {
        if (busy.get()) {
            return "QuestDB is " + (doing.isEmpty() ? "busy" : doing);
        }
        final Entry e = seen;
        return e != null && !mine(e) ? refusal(e) : "";
    }

    /**
     * Starts the server when none is live, stops it when this instance started it, and says who did otherwise; on a
     * virtual thread. While one start or stop is under way, a second toggle changes nothing and says what is happening.
     */
    @Override
    public void toggle() {
        if (!busy.compareAndSet(false, true)) {
            intents.dispatch(new Intent.Notify(Notice.Level.INFO,
                    "QuestDB is " + (doing.isEmpty() ? "busy" : doing) + "; wait for it", false));
            return;
        }
        final Thread t = errands.start("questdb-toggle", () -> {
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
     * An agent's start or stop (record 121): answers at once from the registry and its process, without a network
     * call; the start or stop re-checks the server's token on its own thread, so a change meanwhile turns it into
     * nothing.
     */
    @Override
    public String want(final boolean up) {
        final Entry e = readQuietly();
        final boolean alive = e != null && ProcessHandle.of(e.pid()).map(ProcessHandle::isAlive).orElse(false);
        if (up && alive && running) {
            return "QuestDB is up already: " + where(e) + (mine(e) ? "" : ", " + startedBy(e));
        }
        if (!up && !alive) {
            return "QuestDB is not running";
        }
        if (!up && !mine(e)) {
            return "refused: " + refusal(e);
        }
        if (!busy.compareAndSet(false, true)) {
            return "QuestDB is " + (doing.isEmpty() ? "busy" : doing) + "; try again when that ends";
        }
        final Thread t = errands.start(up ? "questdb-start" : "questdb-stop", () -> {
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
        return (up ? "starting" : "stopping")
                + " QuestDB; the outcome comes as a notice, and ui_get_state's servers show it";
    }

    /**
     * The pid {@code start.sh} printed at this instance's last launch, 0 before one: the process a test that started it
     * ends.
     */
    @TestOnly
    long launchedPid() {
        return launchedPid;
    }

    /** Writes {@link #LOG_CONFIG} as {@code root/conf/comando-log.conf}, which {@code start.sh} names; returns it. */
    static Path logConfig(final Path root) throws IOException {
        final Path ours = OwnerOnly.folder(root.resolve("conf")).resolve(LOG_CONFIG_NAME);
        AtomicFiles.writeString(ours, LOG_CONFIG);
        OwnerOnly.narrow(ours);
        return ours;
    }

    /**
     * The first start on a machine: {@code old/db}, the database of the QuestDB comando ran in its data root, copied to
     * {@code root/db} when that does not exist yet; the old folder is left as it is. Copied beside it first, then moved
     * into place, so a copy cut short is redone at the next start. True when it copied.
     */
    static boolean migrate(final Path old, final Path root) throws IOException {
        final Path from = old.resolve("db");
        final Path to = root.resolve("db");
        if (Files.exists(to) || !Files.isDirectory(from)) {
            return false;
        }
        final Path copying = root.resolve("db.migrating");
        deleteTree(copying);
        try (final Stream<Path> all = Files.walk(from)) {
            for (final Path p : all.toList()) {
                final Path target = copying.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    OwnerOnly.folder(target);
                } else {
                    Files.copy(p, target);
                    OwnerOnly.narrow(target);
                }
            }
        }
        Files.move(copying, to);
        LOG.log(System.Logger.Level.INFO, "QuestDB database copied to the machine's [from=" + from + ", to=" + to
                + ']');
        return true;
    }

    /**
     * {@link #SCRIPT} for {@code folder}; refused when a path holds a character the double-quoted shell words would
     * read ({@code "}, {@code $}, {@code `}, {@code \}).
     */
    static String script(
            final Path folder,
            final Ports ports,
            final Path javaHome,
            final String version
    ) throws IOException {
        for (final String s : List.of(folder.toString(), javaHome.toString(), version)) {
            if (s.chars().anyMatch(c -> c == '"' || c == '$' || c == '`' || c == '\\')) {
                throw new IOException("a path start.sh cannot quote [path=" + s + ']');
            }
        }
        return SCRIPT.formatted(folder, ports.http(), ports.httpMin(), ports.pg(), ports.ilp(), javaHome, version);
    }

    /** A process answers a TCP connect on {@code port} on loopback within {@link #PORT_PROBE}. */
    private static boolean answers(final int port) {
        try (final Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), (int) PORT_PROBE.toMillis());
            return true;
        } catch (final IOException _) {
            return false;
        }
    }

    private static void deleteTree(final Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (final Stream<Path> all = Files.walk(dir)) {
            for (final Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * {@code sql} through {@code /exec} on {@code port}: its JSON answer when the status is 200, else null, and null
     * when the server does not answer within {@code within} (connect and read, half each).
     */
    private static JsonNode exec(final int port, final String sql, final Duration within) {
        final String query = URLEncoder.encode(sql, StandardCharsets.UTF_8).replace("+", "%20");
        try {
            final HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/exec?query="
                    + query).toURL().openConnection();
            final int halfMillis = (int) Math.max(1, within.toMillis() / 2);
            c.setConnectTimeout(halfMillis);
            c.setReadTimeout(halfMillis);
            try {
                if (c.getResponseCode() != 200) {
                    return null;
                }
                try (final InputStream in = c.getInputStream()) {
                    return Json.readFirst(in);
                }
            } finally {
                c.disconnect();
            }
        } catch (final IOException | JsonException _) {
            return null;
        }
    }

    /** {@code file} created owner-only when it is missing, narrowed to its owner when it is there. */
    private static void ownerOnlyFile(final Path file) throws IOException {
        if (!Files.exists(file)) {
            try {
                OwnerOnly.file(file);
                return;
            } catch (final FileAlreadyExistsException _) {
                // another writer made it in between: narrowed below
            }
        }
        OwnerOnly.narrow(file);
    }

    /** Why this instance may not stop {@code e}'s server (record 146). */
    private static String refusal(final Entry e) {
        return e.version() > REGISTRY_VERSION
                ? "its server.json is of version " + e.version() + ", which this comando uses and does not manage"
                : startedBy(e) + "; only it stops it";
    }

    private static String startedBy(final Entry e) {
        return "started by " + e.name() + " (" + e.owner() + ")";
    }

    /**
     * SIGTERM to the server, then up to {@link #STOP_WAIT} for it to exit, then SIGKILL; true once it is gone. The
     * handle keeps its process's start time, so no other process on a reused pid is signalled.
     */
    private static boolean terminate(final ProcessHandle h) {
        h.destroy();
        try {
            h.onExit().get(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (final TimeoutException _) {
            LOG.log(System.Logger.Level.WARNING, "QuestDB did not stop within " + STOP_WAIT.toSeconds()
                    + " s; killing it [pid=" + h.pid() + ']');
            h.destroyForcibly();
            try {
                h.onExit().get(KILL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (final TimeoutException | ExecutionException _) {
                return !h.isAlive();
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
                return !h.isAlive();
            }
        } catch (final ExecutionException _) {
            return !h.isAlive();
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            return !h.isAlive();
        }
    }

    /** 16 random bytes in hex: the token the server answers (record 146). */
    private static String token() {
        final byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static URI uri(final Entry e) {
        try {
            return new URI("http", null, "127.0.0.1", e.ports().http(), null, null, null);
        } catch (final URISyntaxException ex) {
            throw new IllegalStateException("a loopback URI with a port is always valid [port=" + e.ports().http()
                    + ']', ex);
        }
    }

    private static String where(final Entry e) {
        return "PG wire on 127.0.0.1:" + e.ports().pg() + ", HTTP on http://localhost:" + e.ports().http();
    }

    /**
     * Asks the new server {@code SELECT 1} every {@link #POLL_EVERY} until it answers 200: empty then, else why the
     * start failed. Past {@link #UP_WITHIN} the server is left running (it may still come up); one that exited is
     * reported at once.
     */
    private String awaitUp(final long pid) throws InterruptedException {
        final long deadline = System.nanoTime() + UP_WITHIN.toNanos();
        while (deadline - System.nanoTime() > 0) {
            if (exec(ports.http(), "SELECT 1", LIVE_WITHIN) != null) {
                return "";
            }
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return "QuestDB [pid=" + pid + "] exited before it was up; see " + stdout();
            }
            Thread.sleep(POLL_EVERY);
        }
        return "QuestDB [pid=" + pid + "] was not up within " + UP_WITHIN.toSeconds() + " s and is left running; see "
                + stdout();
    }

    /** The jar {@code ServerMain} was loaded from, copied to {@code jar}: beside it first, then moved into place. */
    private void copyJar(final Path jar) throws IOException {
        final Path source;
        try {
            source = Path.of(ServerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (final URISyntaxException | RuntimeException e) {
            throw new IOException("cannot locate QuestDB's jar", e);
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("QuestDB is not loaded from a jar [source=" + source + ']');
        }
        final Path tmp = Files.createTempFile(jar.getParent(), "." + jar.getFileName() + ".", ".tmp");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            AtomicFiles.move(tmp, jar);
        } catch (final IOException | RuntimeException e) {
            AtomicFiles.deleteAfter(e, tmp);
            throw e;
        }
        OwnerOnly.narrow(jar);
        LOG.log(System.Logger.Level.INFO, "QuestDB jar copied to the machine folder [from=" + source + ", to=" + jar
                + ']');
    }

    /**
     * Starts or stops as the registry finds it; {@code want}, when given, is the only direction it may go. One at a
     * time: its callers hold {@link #busy}, so no monitor is held while it waits or tells its notices.
     */
    private void flip(final Boolean want) {
        final Entry e = readQuietly();
        final Optional<ProcessHandle> h = e == null ? Optional.empty() : live(e);
        if (want != null && want == h.isPresent()) {
            liveEntry();
            return;
        }
        try {
            if (h.isEmpty()) {
                doing = "starting";
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Starting QuestDB…", false));
                final Outcome o = up();
                if (o.entry() != null) {
                    intents.dispatch(new Intent.Notify(Notice.Level.INFO, "QuestDB is up: " + where(o.entry()), false));
                } else {
                    intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN,
                            "Cannot start QuestDB: " + o.refusal(), null));
                }
            } else if (!mine(e)) {
                // the user asked for a stop this comando may not do: say why, where the eye is, and in the log
                intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN,
                        "Cannot stop QuestDB: " + refusal(e), null));
            } else {
                doing = "stopping";
                stopLocked(e, h.get());
            }
        } finally {
            doing = "";
        }
        liveEntry();
    }

    /** Runs {@code start.sh} through {@link Processes} and returns the pid it printed. */
    private long launch(final Path script) throws IOException, InterruptedException {
        final Processes.Result r = Processes.run(List.of("/bin/bash", script.toString()), null, folder, null,
                SCRIPT_WITHIN, 64);
        final String printed = r.out().strip();
        if (r.timedOut() || r.exitCode() != 0 || !printed.matches("\\d{1,10}")) {
            throw new IOException("start.sh did not print a pid [exit=" + r.exitCode() + ", timedOut=" + r.timedOut()
                    + ", stdout=" + stdout() + ']');
        }
        return Long.parseLong(printed);
    }

    /**
     * {@code e}'s process when it lives and answers {@code e}'s token within {@link #LIVE_WITHIN} (record 146); the
     * handle is taken before the token is asked, so it is the process that answered.
     */
    private Optional<ProcessHandle> live(final Entry e) {
        final Optional<ProcessHandle> h = ProcessHandle.of(e.pid()).filter(ProcessHandle::isAlive);
        if (h.isEmpty()) {
            return h;
        }
        final JsonNode answer = exec(e.ports().http(), TOKEN_QUERY, LIVE_WITHIN);
        final JsonNode token = answer == null ? null : answer.path("dataset").path(0).path(0);
        return token != null && token.isString() && e.token().equals(token.asString()) ? h : Optional.empty();
    }

    /** The registry's live entry, checked now, else null; the cache {@link #isRunning} reads is set from it. */
    private Entry liveEntry() {
        final Entry e = readQuietly();
        final Entry live = e != null && live(e).isPresent() ? e : null;
        seen = live;
        running = live != null;
        checkedAtNanos = System.nanoTime();
        return live;
    }

    /**
     * The lock on {@code channel}, polled every {@link #POLL_EVERY} until {@link #LOCK_WITHIN}; null when it stays
     * held. Another process's lock answers null; one of this JVM's (another instance, or a second caller here) throws,
     * and is waited for the same way.
     */
    private FileLock lock(final FileChannel channel) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + LOCK_WITHIN.toNanos();
        while (true) {
            try {
                final FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
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

    /** {@code e} is this instance's to stop: of this comando's schema and started by its data root. */
    private boolean mine(final Entry e) {
        return e.version() <= REGISTRY_VERSION && owner.equals(e.owner());
    }

    /**
     * What a start needs in the machine folder: QuestDB's jar copied to {@code lib/} when absent (a build or
     * {@code :app:run} deletes the copy comando runs from), the first start's database, comando's log configuration,
     * the stdout log made owner-only, and {@code start.sh} written whole; returns the script.
     */
    private Path prepare() throws IOException {
        final String version = ServerMain.class.getPackage().getImplementationVersion();
        if (version == null) {
            throw new IOException("QuestDB's jar names no version in its manifest");
        }
        final Path javaHome = Path.of(System.getProperty("java.home"));
        if (!Files.isExecutable(javaHome.resolve("bin").resolve("java"))) {
            throw new IOException("no java launcher in comando's runtime [java.home=" + javaHome + ']');
        }
        final String text = script(folder, ports, javaHome, version);
        final Path jar = OwnerOnly.folder(folder.resolve("lib")).resolve("questdb-" + version + ".jar");
        if (!Files.isRegularFile(jar)) {
            copyJar(jar);
        }
        OwnerOnly.folder(root);
        migrate(dataRoot.resolve("questdb"), root);
        logConfig(root);
        ownerOnlyFile(stdout());
        final Path script = OwnerOnly.folder(folder.resolve("bin")).resolve("start.sh");
        AtomicFiles.writeString(script, text);
        OwnerOnly.executable(script);
        return script;
    }

    /**
     * The registry's entry, or null when there is none, or it is not JSON, or it is of this schema and lacks a field:
     * no server is known then, and a start finds one that runs by its port. An entry of a later schema is returned when
     * it reads, else throws: its server is used if live, never started, stopped or rewritten.
     */
    private Entry read() throws IOException {
        if (!Files.isRegularFile(registry)) {
            return null;
        }
        final JsonNode tree;
        try {
            tree = Mapped.read(registry, m -> Json.readFirst(m.stream()));
        } catch (final JsonException e) {
            LOG.log(System.Logger.Level.WARNING, "the machine's QuestDB registry is not JSON; no server is known [file="
                    + registry + ']', e);
            return null;
        }
        final int version = tree.path("version").asInt(REGISTRY_VERSION);
        final Entry e;
        try {
            e = JsonFile.BINDER.fromTree(tree, Entry.class);
        } catch (final JsonException ex) {
            if (version > REGISTRY_VERSION) {
                throw new IOException("the registry is of version " + version
                        + ", written by a later comando or kit [file=" + registry + ']', ex);
            }
            LOG.log(System.Logger.Level.WARNING, "the machine's QuestDB registry does not read; no server is known"
                    + " [file=" + registry + ']', ex);
            return null;
        }
        return e == null || e.ports() == null || e.token() == null || e.owner() == null ? null : e;
    }

    /** The registry's entry, or null when there is none or it does not read; one that does not read is logged. */
    private Entry readQuietly() {
        try {
            return read();
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the machine's QuestDB registry [file=" + registry + ']',
                    e);
            return null;
        }
    }

    /** Record 146's steps 2 to 6, the lock held. */
    private Outcome startLocked() throws IOException, InterruptedException {
        final Entry e = read();
        if (e != null && live(e).isPresent()) {
            return Outcome.of(e);
        }
        if (e != null && e.version() > REGISTRY_VERSION) {
            return Outcome.refused(refusal(e) + "; its server is not live, and only a later comando or kit starts it");
        }
        if (answers(ports.http())) {
            return Outcome.refused("port " + ports.http()
                    + " is held by a process that is not this machine's comando QuestDB");
        }
        final Path script = prepare();
        final long pid = launch(script);
        launchedPid = pid;
        LOG.log(System.Logger.Level.INFO, "QuestDB starting [pid=" + pid + ", folder=" + folder + ']');
        final String failure = awaitUp(pid);
        if (!failure.isEmpty()) {
            return Outcome.refused(failure);
        }
        final String token = token();
        // the token is in this INSERT and in server.json only: neither the statement nor its failure is logged
        if (exec(ports.http(), "CREATE TABLE IF NOT EXISTS qdb_server"
                + " (ts TIMESTAMP, token VARCHAR, pid LONG, owner VARCHAR) TIMESTAMP(ts) PARTITION BY NONE BYPASS WAL",
                UP_WITHIN) == null
                || exec(ports.http(), "INSERT INTO qdb_server VALUES (now(), '" + token + "', " + pid + ", '"
                        + owner.replace("'", "''") + "')", UP_WITHIN) == null) {
            return Outcome.refused("QuestDB [pid=" + pid
                    + "] did not record its token in qdb_server and is left running; see " + stdout());
        }
        final Entry entry = new Entry(REGISTRY_VERSION, pid, owner, name,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), ports, token,
                ServerMain.class.getPackage().getImplementationVersion());
        AtomicFiles.write(registry, JsonFile.pretty(JsonFile.BINDER.toTree(entry)));
        OwnerOnly.narrow(registry);
        LOG.log(System.Logger.Level.INFO, "QuestDB up [pid=" + pid + ", ports=" + ports + ", owner=" + owner + ']');
        return Outcome.of(entry);
    }

    private Path stdout() {
        return folder.resolve("stdout.log");
    }

    /**
     * {@link #stop} under {@code start.lock}: a writer that finds the server gone waits until it has left the root. Its
     * notices are told once both locks are released.
     */
    private void stopLocked(final Entry e, final ProcessHandle h) {
        try {
            final Outcome o = underLock(() -> stop(e, h));
            if (o.entry() == null) {
                intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN,
                        "Cannot stop QuestDB: " + o.refusal(), null));
            } else {
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "QuestDB is down", false));
            }
        } catch (final IOException ex) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN,
                    "Cannot stop QuestDB [pid=" + e.pid() + "]: " + ex.getMessage(), ex));
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Record 146's stop of this instance's server, then its entry removed while it still names that process; refused
     * while the server has not stopped. It tells nothing: its caller holds the start locks.
     */
    private Outcome stop(final Entry e, final ProcessHandle h) {
        if (!terminate(h)) {
            return Outcome.refused("the server [pid=" + e.pid() + "] has not stopped yet");
        }
        try {
            final Entry now = read();
            if (now != null && now.pid() == e.pid()) {
                Files.deleteIfExists(registry);
            }
        } catch (final IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "cannot remove the machine's QuestDB registry [file=" + registry
                    + ']', ex);
        }
        LOG.log(System.Logger.Level.INFO, "QuestDB stopped [pid=" + e.pid() + ']');
        return Outcome.of(e);
    }

    /**
     * Takes this JVM's hold of the folder, then {@code start.lock}, each at most {@link #LOCK_WITHIN}, and runs
     * {@code action} under both: a start, or a stop, so that no start begins while a stopping server still holds the
     * root.
     */
    private Outcome underLock(final Locked action) throws IOException, InterruptedException {
        final ReentrantLock here = IN_PROCESS.computeIfAbsent(folder.toAbsolutePath().normalize(),
                _ -> new ReentrantLock());
        final Path lockFile = folder.resolve("start.lock");
        if (!here.tryLock(LOCK_WITHIN.toMillis(), TimeUnit.MILLISECONDS)) {
            return Outcome.refused(lockFile + " is held by another start in this comando for over "
                    + LOCK_WITHIN.toSeconds() + " s");
        }
        try {
            OwnerOnly.folder(folder);
            ownerOnlyFile(lockFile);
            try (final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                final FileLock lock = lock(channel);
                if (lock == null) {
                    return Outcome.refused(lockFile + " is held by another start for over " + LOCK_WITHIN.toSeconds()
                            + " s");
                }
                try {
                    return action.run();
                } finally {
                    lock.release();
                }
            }
        } finally {
            here.unlock();
        }
    }

    /** Work done under {@code start.lock}. */
    @FunctionalInterface
    private interface Locked {
        Outcome run() throws IOException, InterruptedException;
    }

    /** Record 146's start; the outcome refreshes the cache {@link #isRunning} reads. */
    private Outcome up() {
        final Entry first = liveEntry();
        if (first != null) {
            return Outcome.of(first);
        }
        final Outcome o;
        try {
            o = underLock(this::startLocked);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot start the machine's QuestDB [folder=" + folder + ']', e);
            return Outcome.refused("cannot start it: " + e.getMessage());
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            return Outcome.refused("its start was interrupted");
        }
        if (o.entry() != null) {
            seen = o.entry();
            running = true;
            checkedAtNanos = System.nanoTime();
        }
        return o;
    }

    /** The ports of the machine's QuestDB, QuestDB's own defaults; {@code server.json} names them. */
    record Ports(int http, int httpMin, int pg, int ilp) {

        static final Ports DEFAULT = new Ports(9000, 9003, 8812, 9009);
    }

    /**
     * {@code server.json}, version 1 (record 146): the server's process, its owner and instance name, when it started,
     * its ports, the token it answers and QuestDB's version.
     */
    record Entry(
            int version,
            long pid,
            String owner,
            String name,
            String started,
            Ports ports,
            String token,
            String questdb
    ) {

        Entry {
            version = version <= 0 ? REGISTRY_VERSION : version;
        }
    }

    /** A start's outcome: the live entry, or why there is none. */
    private record Outcome(Entry entry, String refusal) {

        static Outcome of(final Entry e) {
            return new Outcome(e, "");
        }

        static Outcome refused(final String why) {
            return new Outcome(null, why);
        }
    }
}
