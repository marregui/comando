package dev.marregui.comando.app;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.questdb.PropertyKey;
import io.questdb.ServerMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [MachineQuestDb]: the machine's one QuestDB, a process of its own that outlives the comando that started it.
/// - `start.sh` binds every listener to `127.0.0.1`, turns line-protocol UDP off, and refuses a path it cannot quote
/// - `start.sh` runs only what comando uses (HTTP and PG wire, small column pages, one query and one write worker, two
///   network workers), names only properties QuestDB knows, and sets `stdout.log` aside past 10 MiB
/// - comando's log configuration, errors and advisories only, is written at each start; the first start copies the old
///   database and leaves it
/// - a port another process holds, and a `server.json` of a later version, start nothing
/// - a start makes `start.sh`, `lib/` and `server.json` v1 with its owner and a token the server answers; another
///   instance uses it, starts no second one, and may not stop it; the owner's close leaves it up, its stop ends it
/// - two concurrent starts start one server
///
/// Every server runs in a `@TempDir` machine folder on free loopback ports, never QuestDB's defaults, and is stopped in
/// a `finally` through its owner's stop; one a start left without a `server.json` is ended by the pid `start.sh`
/// printed to the instance that launched it, while that process still names the test's machine folder.
class MachineQuestDbTest {

    /// QuestDB's default ports, which no test binds: a developer's machine QuestDB may hold them.
    private static final List<Integer> DEFAULT_PORTS = List.of(8812, 9000, 9003, 9009);
    /// A start copies the jar and waits up to 60 s for the server; a stop waits up to 25 s.
    private static final Duration START_WITHIN = Duration.ofSeconds(90);
    private static final Duration STOP_WITHIN = Duration.ofSeconds(40);

    /// `start.sh` sets four `*_BIND_TO` variables, each to `127.0.0.1:` and the port asked for, turns line-protocol UDP
    /// and telemetry off, and names the jar of the version given; a folder holding `$` is refused.
    @Test
    void everyListenerStaysOnTheLoopbackInterface(@TempDir final Path dir) throws IOException {
        final MachineQuestDb.Ports ports = new MachineQuestDb.Ports(19000, 19003, 18812, 19009);
        final String script = MachineQuestDb.script(dir, ports, Path.of("/opt/jdk"), "10.1.0");

        final List<String> binds = script.lines().filter(l -> l.contains("_BIND_TO=")).toList();
        assertThat(binds).containsExactly(
                "export QDB_HTTP_NET_BIND_TO=127.0.0.1:19000",
                "export QDB_HTTP_MIN_NET_BIND_TO=127.0.0.1:19003",
                "export QDB_PG_NET_BIND_TO=127.0.0.1:18812",
                "export QDB_LINE_TCP_NET_BIND_TO=127.0.0.1:19009");
        assertThat(script.lines()).contains("export QDB_LINE_UDP_ENABLED=false", "export QDB_TELEMETRY_ENABLED=false",
                "echo $!");
        assertThat(script).contains("nohup \"/opt/jdk/bin/java\" ",
                "-cp \"" + dir + "/lib/questdb-10.1.0.jar\" io.questdb.ServerMain -d \"" + dir + "/root\"",
                "-Dout=\"" + dir + "/root/conf/comando-log.conf\"");
        assertThatThrownBy(() -> MachineQuestDb.script(dir.resolve("a$b"), ports, Path.of("/opt/jdk"), "10.1.0"))
                .isInstanceOf(IOException.class);
    }

    /// `start.sh` turns off what nothing uses: ILP over TCP, the health server, the web console, `sys.metrics` and
    /// views; it runs one query and one write worker, two network workers so that a long query leaves one free for the
    /// token check, parks idle workers early, and grows column, index and WAL files in 64 KiB pages, not 16 MiB.
    @Test
    void theServerRunsOnlyWhatComandoUses(@TempDir final Path dir) throws IOException {
        final String script = MachineQuestDb.script(dir, MachineQuestDb.Ports.DEFAULT, Path.of("/opt/jdk"), "10.1.0");

        assertThat(script.lines()).contains(
                "export QDB_LINE_TCP_ENABLED=false",
                "export QDB_HTTP_MIN_ENABLED=false",
                "export QDB_HTTP_STATIC_PUBLIC_DIRECTORY=no-console",
                "export QDB_METRICS_PERSIST_ENABLED=false",
                "export QDB_CAIRO_MAT_VIEW_ENABLED=false",
                "export QDB_MAT_VIEW_REFRESH_WORKER_COUNT=0",
                "export QDB_CAIRO_LIVE_VIEW_ENABLED=false",
                "export QDB_LIVE_VIEW_REFRESH_WORKER_COUNT=0",
                "export QDB_VIEW_COMPILER_WORKER_COUNT=1",
                "export QDB_SHARED_WORKER_COUNT=1",
                "export QDB_WAL_APPLY_WORKER_COUNT=1",
                "export QDB_SHARED_NETWORK_WORKER_COUNT=2",
                "export QDB_SHARED_WORKER_NAP_THRESHOLD=20",
                "export QDB_SHARED_WORKER_SLEEP_THRESHOLD=50",
                "export QDB_SHARED_WORKER_SLEEP_TIMEOUT=50",
                "export QDB_WAL_APPLY_WORKER_NAP_THRESHOLD=20",
                "export QDB_WAL_APPLY_WORKER_SLEEP_THRESHOLD=50",
                "export QDB_WAL_APPLY_WORKER_SLEEP_TIMEOUT=50",
                "export QDB_CAIRO_WRITER_DATA_APPEND_PAGE_SIZE=64k",
                "export QDB_CAIRO_WRITER_DATA_INDEX_VALUE_APPEND_PAGE_SIZE=64k",
                "export QDB_CAIRO_WAL_WRITER_DATA_APPEND_PAGE_SIZE=64k",
                "export QDB_CAIRO_WAL_WRITER_EVENT_APPEND_PAGE_SIZE=64k",
                "export QDB_CAIRO_O3_COLUMN_MEMORY_SIZE=256k");
    }

    /// Every `QDB_*` name `start.sh` exports is a property of the QuestDB it starts, the jar on this classpath, which a
    /// start copies into the machine folder: QuestDB ignores an environment variable it does not know, even under
    /// strict validation, so a misspelt or renamed name would bring a default back unnoticed.
    @Test
    void everyNameTheScriptExportsIsAPropertyOfQuestDb(@TempDir final Path dir) throws IOException {
        final List<String> known = List.of(PropertyKey.values()).stream().map(PropertyKey::getEnvVarName).toList();
        final List<String> exported = MachineQuestDb.script(dir, MachineQuestDb.Ports.DEFAULT, Path.of("/opt/jdk"),
                        ServerMain.class.getPackage().getImplementationVersion()).lines()
                .filter(l -> l.startsWith("export QDB_"))
                .map(l -> l.substring("export ".length(), l.indexOf('=')))
                .toList();

        assertThat(exported).isNotEmpty();
        assertThat(exported).as("names QuestDB does not know").filteredOn(n -> !known.contains(n)).isEmpty();
    }

    /// `start.sh` moves a `stdout.log` over 10 MiB to `stdout.log.1`, over the one there, and starts an owner-only
    /// `stdout.log`; one of exactly 10 MiB stays. The Java it runs is a stub that exits at once.
    @Test
    void stdoutLogIsSetAsidePastTenMebibytes(@TempDir final Path dir) throws Exception {
        final Path jdk = Files.createDirectories(dir.resolve("jdk").resolve("bin")).getParent();
        final Path java = Files.writeString(jdk.resolve("bin").resolve("java"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        final Path script = Files.writeString(dir.resolve("start.sh"),
                MachineQuestDb.script(dir, MachineQuestDb.Ports.DEFAULT, jdk, "10.1.0"));
        final Path log = dir.resolve("stdout.log");
        final Path aside = dir.resolve("stdout.log.1");
        final int tenMebibytes = 10 * 1024 * 1024;

        Files.write(log, new byte[tenMebibytes]);
        runScript(script);
        assertThat(aside).as("10 MiB is not past the limit").doesNotExist();
        assertThat(Files.size(log)).isGreaterThanOrEqualTo(tenMebibytes);

        Files.writeString(aside, "the older copy");
        Files.write(log, new byte[tenMebibytes + 1]);
        runScript(script);
        assertThat(Files.size(aside)).isEqualTo(tenMebibytes + 1);
        assertThat(Files.size(log)).isLessThan(1024);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log))).isEqualTo("rw-------");
    }

    /// comando's log configuration, one file a day in the root's `log/` with errors and advisories, and errors on
    /// stdout, never a line per query, is written owner-only at each start, over what the file held.
    @Test
    void theLogConfigurationIsComandosAtEachStart(@TempDir final Path dir) throws IOException {
        final Path root = dir.resolve("root");
        final Path ours = root.resolve("conf").resolve(MachineQuestDb.LOG_CONFIG_NAME);
        assertThat(MachineQuestDb.logConfig(root)).isEqualTo(ours);
        assertThat(ours).hasContent(MachineQuestDb.LOG_CONFIG);
        assertThat(MachineQuestDb.LOG_CONFIG).contains("w.file.location=${log.dir}/questdb-${date:yyyyMMdd}.log");
        assertThat(MachineQuestDb.LOG_CONFIG.lines().filter(l -> l.contains(".level="))).containsExactly(
                "w.file.level=ERROR,CRITICAL,ADVISORY",
                "w.stdout.level=ERROR,CRITICAL");
        Files.writeString(ours, "writers=stdout\n");
        MachineQuestDb.logConfig(root);
        assertThat(ours).hasContent(MachineQuestDb.LOG_CONFIG);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(ours))).isEqualTo("rw-------");
    }

    /// The first start copies `<dataRoot>/questdb/db` to the root's `db/` and leaves the old folder as it was; a root
    /// that has a `db/` copies nothing.
    @Test
    void theFirstStartCopiesTheOldDatabaseAndLeavesIt(@TempDir final Path dir) throws IOException {
        final Path old = dir.resolve("data").resolve("questdb");
        Files.createDirectories(old.resolve("db").resolve("metrics~1"));
        Files.writeString(old.resolve("db").resolve("metrics~1").resolve("_meta"), "meta");
        final Path root = dir.resolve("machine").resolve("root");
        Files.createDirectories(root);

        assertThat(MachineQuestDb.migrate(old, root)).isTrue();
        assertThat(root.resolve("db").resolve("metrics~1").resolve("_meta")).hasContent("meta");
        assertThat(old.resolve("db").resolve("metrics~1").resolve("_meta")).as("the old folder, left")
                .hasContent("meta");
        assertThat(root.resolve("db.migrating")).doesNotExist();
        assertThat(MachineQuestDb.migrate(old, root)).as("a root with a db").isFalse();
    }

    /// A start whose HTTP port a plain `ServerSocket` holds is refused with the contract's text, and nothing is
    /// started: no `start.sh`, no `server.json`.
    @Test
    void aPortAnotherProcessHoldsIsRefused(@TempDir final Path dir) throws IOException {
        // a backlog for both connects below: the socket never accepts, and a full backlog drops a connect unanswered
        try (final ServerSocket held = new ServerSocket(0, 50, loopback())) {
            final MachineQuestDb.Ports free = freePorts();
            final MachineQuestDb.Ports ports = new MachineQuestDb.Ports(held.getLocalPort(), free.httpMin(), free.pg(),
                    free.ilp());
            final RecordingSink sink = new RecordingSink();
            try (final MachineQuestDb db = new MachineQuestDb(dir.resolve("machine"), dir.resolve("data"), "", sink,
                    ports)) {
                assertThat(db.ensureUp()).isEmpty();
                assertThat(db.want(true)).startsWith("starting QuestDB");
                final Intent.Notify refused = sink.await(Intent.Notify.class, n -> n.text().startsWith("Cannot start"),
                        START_WITHIN);

                assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
                assertThat(refused.text()).isEqualTo("Cannot start QuestDB: port " + held.getLocalPort()
                        + " is held by a process that is not this machine's comando QuestDB");
                assertThat(dir.resolve("machine").resolve("server.json")).doesNotExist();
                assertThat(dir.resolve("machine").resolve("bin").resolve("start.sh")).doesNotExist();
            }
        }
    }

    /// A `server.json` of version 2 whose server is not live is used if live, else left: no start, the file unchanged,
    /// and a stop is refused.
    @Test
    void aRegistryOfALaterVersionIsNeverStartedOrRewritten(@TempDir final Path dir) throws IOException {
        final Path machine = Files.createDirectories(dir.resolve("machine"));
        final MachineQuestDb.Ports ports = freePorts();
        final String later = """
                {"version": 2, "pid": %d, "owner": "kit:/elsewhere", "name": "kit", "started": "2026-11-09T16:30:00Z",
                 "ports": {"http": %d, "httpMin": %d, "pg": %d, "ilp": %d}, "token": "00", "questdb": "11.0.0",
                 "more": true}
                """.formatted(ProcessHandle.current().pid(), ports.http(), ports.httpMin(), ports.pg(), ports.ilp());
        Files.writeString(machine.resolve("server.json"), later);
        try (final MachineQuestDb db = new MachineQuestDb(machine, dir.resolve("data"), "", new RecordingSink(),
                ports)) {
            assertThat(db.ensureUp()).isEmpty();
            assertThat(db.want(false))
                    .isEqualTo("refused: its server.json is of version 2, which this comando uses and does not manage");
            assertThat(machine.resolve("server.json")).hasContent(later);
            assertThat(machine.resolve("bin").resolve("start.sh")).doesNotExist();
        }
    }

    /// One server's life. Instance A starts it: `bin/start.sh` (0700), `lib/questdb-<version>.jar`, and `server.json`
    /// v1 (0600) naming A's data root as owner, its live pid, its ports and a 32-hex token that `/exec` answers; its PG
    /// port answers on loopback and not on this machine's LAN address, when it has one. Instance B, on another data
    /// root, sees it live, gets the same endpoint from `ensureUp` without a second start (the pid unchanged), says who
    /// started it, and is refused a stop. It answers ILP's ping over HTTP, but not the web console's page, and nothing
    /// listens on the ILP TCP and health ports. A's close leaves the process alive; the owner's stop ends it and
    /// removes `server.json`.
    @Test
    void anOwnerStartsTheServerAnotherInstanceUsesItAndOnlyTheOwnerStopsIt(@TempDir final Path dir) throws Exception {
        final Path machine = dir.resolve("machine");
        final Path rootA = dir.resolve("a");
        final MachineQuestDb.Ports ports = freePorts();
        final RecordingSink sink = new RecordingSink();
        final MachineQuestDb a = new MachineQuestDb(machine, rootA, "a", sink, ports);
        final MachineQuestDb b = new MachineQuestDb(machine, dir.resolve("b"), "b", sink, ports);
        try {
            assertThat(a.want(false)).isEqualTo("QuestDB is not running");
            assertThat(a.want(true))
                    .isEqualTo("starting QuestDB; the outcome comes as a notice, and ui_get_state's servers show it");
            final Intent.Notify up = sink.await(Intent.Notify.class,
                    n -> n.text().startsWith("QuestDB is up") || n.text().startsWith("Cannot"), START_WITHIN);
            assertThat(up.level()).as(up.text()).isEqualTo(Notice.Level.INFO);
            assertThat(up.text()).isEqualTo("QuestDB is up: PG wire on 127.0.0.1:" + ports.pg()
                    + ", HTTP on http://localhost:" + ports.http());

            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(machine))).isEqualTo("rwx------");
            final Path script = machine.resolve("bin").resolve("start.sh");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(script))).isEqualTo("rwx------");
            final String version = ServerMain.class.getPackage().getImplementationVersion();
            assertThat(machine.resolve("lib").resolve("questdb-" + version + ".jar")).isRegularFile();
            final Path registry = machine.resolve("server.json");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(registry))).isEqualTo("rw-------");
            final JsonNode entry = registry(machine);
            assertThat(entry.path("version").asInt()).isEqualTo(1);
            assertThat(entry.path("owner").asString()).isEqualTo(rootA.toAbsolutePath().normalize().toString());
            assertThat(entry.path("name").asString()).isEqualTo("comando · a");
            assertThat(entry.path("ports").path("http").asInt()).isEqualTo(ports.http());
            assertThat(entry.path("ports").path("httpMin").asInt()).isEqualTo(ports.httpMin());
            assertThat(entry.path("ports").path("pg").asInt()).isEqualTo(ports.pg());
            assertThat(entry.path("ports").path("ilp").asInt()).isEqualTo(ports.ilp());
            assertThat(entry.path("questdb").asString()).isEqualTo(version);
            final String token = entry.path("token").asString();
            assertThat(token).matches("[0-9a-f]{32}");
            final long pid = entry.path("pid").asLong();
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).contains(true);
            assertThat(query(ports, "SELECT token FROM qdb_server ORDER BY ts DESC LIMIT 1").path("dataset").path(0)
                    .path(0).asString()).isEqualTo(token);
            assertThat(a.want(true)).startsWith("QuestDB is up already: PG wire on 127.0.0.1:" + ports.pg() + ',');
            assertThat(a.log()).as("the day's log, in the root's log folder").isRegularFile()
                    .hasParent(machine.resolve("root").resolve("log"));
            try (final Socket s = new Socket()) {
                s.connect(new InetSocketAddress(loopback(), ports.pg()), 2_000);
            }
            final Optional<InetAddress> lan = lanAddress();
            if (lan.isPresent()) {
                assertThatThrownBy(() -> {
                    try (final Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(lan.get(), ports.pg()), 2_000);
                    }
                }).isInstanceOf(IOException.class);
            }
            assertThat(status(ports, "/ping")).as("ILP over HTTP").isEqualTo(204);
            assertThat(status(ports, "/index.html")).as("the web console").isEqualTo(404);
            for (final int off : List.of(ports.ilp(), ports.httpMin())) {
                assertThatThrownBy(() -> {
                    try (final Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(loopback(), off), 2_000);
                    }
                }).as("port %d", off).isInstanceOf(IOException.class);
            }

            final String written = Files.readString(registry);
            assertThat(b.live()).contains(URI.create("http://127.0.0.1:" + ports.http()));
            assertThat(b.ensureUp()).contains(URI.create("http://127.0.0.1:" + ports.http()));
            assertThat(registry).as("no second start rewrote it").hasContent(written);
            assertThat(query(ports, "SELECT count() FROM qdb_server").path("dataset").path(0).path(0).asLong())
                    .as("one start").isEqualTo(1);
            final String startedBy = "started by comando · a (" + rootA.toAbsolutePath().normalize()
                    + "); only it stops it";
            assertThat(b.status()).isEqualTo(startedBy);
            assertThat(b.want(false)).isEqualTo("refused: " + startedBy);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).contains(true);

            a.close();
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).as("the owner's close leaves it")
                    .contains(true);
            assertThat(b.live()).isPresent();

            sink.clear();
            stopThroughOwner(machine, ports);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(registry).doesNotExist();
            assertThat(b.live()).isEmpty();
        } finally {
            a.close();
            b.close();
            try {
                stopThroughOwner(machine, ports);
            } finally {
                endLaunched(machine, a, b);
            }
        }
    }

    /// Two instances on two data roots that call `ensureUp` at once, released by a barrier, get one endpoint from one
    /// start: the `start.lock` serialises them, and `qdb_server` holds one row.
    @Test
    void twoConcurrentStartsStartOneServer(@TempDir final Path dir) throws Exception {
        final Path machine = dir.resolve("machine");
        final MachineQuestDb.Ports ports = freePorts();
        final MachineQuestDb a = new MachineQuestDb(machine, dir.resolve("a"), "a", new RecordingSink(), ports);
        final MachineQuestDb b = new MachineQuestDb(machine, dir.resolve("b"), "b", new RecordingSink(), ports);
        try {
            final CyclicBarrier start = new CyclicBarrier(2);
            final CompletableFuture<Optional<URI>> first = CompletableFuture.supplyAsync(
                    () -> afterBarrier(start, a));
            final CompletableFuture<Optional<URI>> second = CompletableFuture.supplyAsync(
                    () -> afterBarrier(start, b));
            final Optional<URI> one = first.get(START_WITHIN.toSeconds() * 2, TimeUnit.SECONDS);
            final Optional<URI> other = second.get(START_WITHIN.toSeconds() * 2, TimeUnit.SECONDS);

            assertThat(one).contains(URI.create("http://127.0.0.1:" + ports.http()));
            assertThat(other).isEqualTo(one);
            assertThat(query(ports, "SELECT count() FROM qdb_server").path("dataset").path(0).path(0).asLong())
                    .as("one start").isEqualTo(1);
        } finally {
            a.close();
            b.close();
            try {
                stopThroughOwner(machine, ports);
            } finally {
                endLaunched(machine, a, b);
            }
        }
    }

    private static Optional<URI> afterBarrier(final CyclicBarrier start, final MachineQuestDb db) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException("the other start did not come", e);
        }
        return db.ensureUp();
    }

    /// Four ports free on loopback now, none of QuestDB's defaults; released before the server binds them.
    private static MachineQuestDb.Ports freePorts() throws IOException {
        final List<ServerSocket> held = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                held.add(new ServerSocket(0, 1, loopback()));
            }
            final List<Integer> ports = held.stream().map(ServerSocket::getLocalPort).toList();
            assertThat(ports).doesNotContainAnyElementsOf(DEFAULT_PORTS);
            return new MachineQuestDb.Ports(ports.get(0), ports.get(1), ports.get(2), ports.get(3));
        } finally {
            for (final ServerSocket s : held) {
                s.close();
            }
        }
    }

    private static Optional<InetAddress> lanAddress() throws SocketException {
        for (final NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (n.isUp() && !n.isLoopback()) {
                for (final InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) {
                        return Optional.of(a);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    }

    /// Ends the server each of `dbs` launched while it still runs and names this test's `machine` folder: a start
    /// that timed out, failed its statements or was cut off leaves its process without a `server.json`, which
    /// [#stopThroughOwner] then finds nothing in. The pid is the one `start.sh` printed to that instance, never a
    /// process found by name; a pid the system gave to another process since names another folder and is left.
    private static void endLaunched(final Path machine, final MachineQuestDb... dbs) throws Exception {
        final String root = machine.toAbsolutePath().normalize().resolve("root").toString();
        for (final MachineQuestDb db : dbs) {
            final long pid = db.launchedPid();
            final Optional<ProcessHandle> ours = pid <= 0 ? Optional.empty() : ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .filter(h -> h.info().arguments().map(args -> List.of(args).contains(root)).orElse(false));
            if (ours.isPresent()) {
                final ProcessHandle h = ours.get();
                h.destroy();
                try {
                    h.onExit().get(STOP_WITHIN.toSeconds(), TimeUnit.SECONDS);
                } catch (final TimeoutException _) {
                    h.destroyForcibly();
                    h.onExit().get(STOP_WITHIN.toSeconds(), TimeUnit.SECONDS);
                }
            }
        }
    }

    /// `sql`'s answer from the server's `/exec`, which must be 200.
    private static JsonNode query(
            final MachineQuestDb.Ports ports,
            final String sql
    ) throws IOException, InterruptedException {
        try (final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            final URI uri = URI.create("http://127.0.0.1:" + ports.http() + "/exec?query="
                    + URLEncoder.encode(sql, StandardCharsets.UTF_8).replace("+", "%20"));
            final HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            return JsonFile.JSON.readTree(r.body());
        }
    }

    /// Runs `script` with bash, as a start does, and waits up to 10 s for it to end with 0.
    private static void runScript(final Path script) throws Exception {
        final Process p = new ProcessBuilder("/bin/bash", script.toString()).redirectErrorStream(true).start();
        try {
            assertThat(p.waitFor(10, TimeUnit.SECONDS)).as("start.sh ended").isTrue();
            assertThat(p.exitValue()).as(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .isZero();
        } finally {
            p.destroyForcibly();
        }
    }

    private static JsonNode registry(final Path machine) throws IOException {
        return JsonFile.JSON.readTree(Files.readString(machine.resolve("server.json")));
    }

    /// The status of a GET of `path` on the server's HTTP port.
    private static int status(
            final MachineQuestDb.Ports ports,
            final String path
    ) throws IOException, InterruptedException {
        try (final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            final URI uri = URI.create("http://127.0.0.1:" + ports.http() + path);
            return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    /// Stops the server `server.json` names through a fresh instance on its owner's data root, as the owner would, and
    /// waits for the notice; nothing when there is no `server.json`.
    private static void stopThroughOwner(final Path machine, final MachineQuestDb.Ports ports) throws IOException {
        if (!Files.exists(machine.resolve("server.json"))) {
            return;
        }
        final RecordingSink sink = new RecordingSink();
        final Path ownerRoot = Path.of(registry(machine).path("owner").asString());
        try (final MachineQuestDb owner = new MachineQuestDb(machine, ownerRoot, "", sink, ports)) {
            if (owner.want(false).startsWith("stopping")) {
                sink.await(Intent.Notify.class,
                        n -> n.text().equals("QuestDB is down") || n.text().contains("not stopped"), STOP_WITHIN);
            }
        }
    }
}
