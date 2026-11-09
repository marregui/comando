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
/// - comando's log configuration is written at each start; the first start copies the old database and leaves it
/// - a port another process holds, and a `server.json` of a later version, start nothing
/// - a start makes `start.sh`, `lib/` and `server.json` v1 with its owner and a token the server answers; another
///   instance uses it, starts no second one, and may not stop it; the owner's close leaves it up, its stop ends it
/// - two concurrent starts start one server
///
/// Every server runs in a `@TempDir` machine folder on free loopback ports, never QuestDB's defaults, and is stopped in
/// a `finally` through its owner's stop.
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
        assertThat(script.lines()).contains("export QDB_LINE_UDP_ENABLED=false", "export QDB_TELEMETRY_ENABLED=false", "echo $!");
        assertThat(script).contains("nohup \"/opt/jdk/bin/java\" ", "-cp \"" + dir + "/lib/questdb-10.1.0.jar\" io.questdb.ServerMain -d \""
                + dir + "/root\"", "-Dout=\"" + dir + "/root/conf/comando-log.conf\"");
        assertThatThrownBy(() -> MachineQuestDb.script(dir.resolve("a$b"), ports, Path.of("/opt/jdk"), "10.1.0"))
                .isInstanceOf(IOException.class);
    }

    /// comando's log configuration, one file a day in the root's `log/`, is written owner-only at each start, over what
    /// the file held.
    @Test
    void theLogConfigurationIsComandosAtEachStart(@TempDir final Path dir) throws IOException {
        final Path root = dir.resolve("root");
        final Path ours = root.resolve("conf").resolve(MachineQuestDb.LOG_CONFIG_NAME);
        assertThat(MachineQuestDb.logConfig(root)).isEqualTo(ours);
        assertThat(ours).hasContent(MachineQuestDb.LOG_CONFIG);
        assertThat(MachineQuestDb.LOG_CONFIG).contains("w.file.location=${log.dir}/questdb-${date:yyyyMMdd}.log");
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
        assertThat(old.resolve("db").resolve("metrics~1").resolve("_meta")).as("the old folder, left").hasContent("meta");
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
            final MachineQuestDb.Ports ports = new MachineQuestDb.Ports(held.getLocalPort(), free.httpMin(), free.pg(), free.ilp());
            final RecordingSink sink = new RecordingSink();
            try (final MachineQuestDb db = new MachineQuestDb(dir.resolve("machine"), dir.resolve("data"), "", sink, ports)) {
                assertThat(db.ensureUp()).isEmpty();
                assertThat(db.want(true)).startsWith("starting QuestDB");
                final Intent.Notify refused = sink.await(Intent.Notify.class, n -> n.text().startsWith("Cannot start"), START_WITHIN);

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
                 "ports": {"http": %d, "httpMin": %d, "pg": %d, "ilp": %d}, "token": "00", "questdb": "11.0.0", "more": true}
                """.formatted(ProcessHandle.current().pid(), ports.http(), ports.httpMin(), ports.pg(), ports.ilp());
        Files.writeString(machine.resolve("server.json"), later);
        try (final MachineQuestDb db = new MachineQuestDb(machine, dir.resolve("data"), "", new RecordingSink(), ports)) {
            assertThat(db.ensureUp()).isEmpty();
            assertThat(db.want(false)).isEqualTo("refused: its server.json is of version 2, which this comando uses and does not manage");
            assertThat(machine.resolve("server.json")).hasContent(later);
            assertThat(machine.resolve("bin").resolve("start.sh")).doesNotExist();
        }
    }

    /// One server's life. Instance A starts it: `bin/start.sh` (0700), `lib/questdb-<version>.jar`, and `server.json`
    /// v1 (0600) naming A's data root as owner, its live pid, its ports and a 32-hex token that `/exec` answers; its PG
    /// port answers on loopback and not on this machine's LAN address, when it has one. Instance B, on another data
    /// root, sees it live, gets the same endpoint from `ensureUp` without a second start (the pid unchanged), says who
    /// started it, and is refused a stop. A's close leaves the process alive; the owner's stop ends it and removes
    /// `server.json`.
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
            assertThat(a.want(true)).isEqualTo("starting QuestDB; the outcome comes as a notice, and ui_get_state's servers show it");
            final Intent.Notify up = sink.await(Intent.Notify.class, n -> n.text().startsWith("QuestDB is up") || n.text().startsWith("Cannot"),
                    START_WITHIN);
            assertThat(up.level()).as(up.text()).isEqualTo(Notice.Level.INFO);
            assertThat(up.text()).isEqualTo("QuestDB is up: PG wire on 127.0.0.1:" + ports.pg() + ", console on http://localhost:" + ports.http());

            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(machine))).isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(machine.resolve("bin").resolve("start.sh"))))
                    .isEqualTo("rwx------");
            assertThat(machine.resolve("lib").resolve("questdb-" + ServerMain.class.getPackage().getImplementationVersion() + ".jar"))
                    .isRegularFile();
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
            assertThat(entry.path("questdb").asString()).isEqualTo(ServerMain.class.getPackage().getImplementationVersion());
            final String token = entry.path("token").asString();
            assertThat(token).matches("[0-9a-f]{32}");
            final long pid = entry.path("pid").asLong();
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).contains(true);
            assertThat(query(ports, "SELECT token FROM qdb_server ORDER BY ts DESC LIMIT 1").path("dataset").path(0).path(0).asString())
                    .isEqualTo(token);
            assertThat(a.want(true)).startsWith("QuestDB is up already: PG wire on 127.0.0.1:" + ports.pg() + ',');
            assertThat(a.log()).as("the day's log, in the root's log folder").isRegularFile().hasParent(machine.resolve("root").resolve("log"));
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

            final String written = Files.readString(registry);
            assertThat(b.live()).contains(URI.create("http://127.0.0.1:" + ports.http()));
            assertThat(b.ensureUp()).contains(URI.create("http://127.0.0.1:" + ports.http()));
            assertThat(registry).as("no second start rewrote it").hasContent(written);
            assertThat(query(ports, "SELECT count() FROM qdb_server").path("dataset").path(0).path(0).asLong()).as("one start").isEqualTo(1);
            final String startedBy = "started by comando · a (" + rootA.toAbsolutePath().normalize() + "); only it stops it";
            assertThat(b.status()).isEqualTo(startedBy);
            assertThat(b.want(false)).isEqualTo("refused: " + startedBy);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).contains(true);

            a.close();
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).as("the owner's close leaves it").contains(true);
            assertThat(b.live()).isPresent();

            sink.clear();
            stopThroughOwner(machine, ports);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(registry).doesNotExist();
            assertThat(b.live()).isEmpty();
        } finally {
            a.close();
            b.close();
            stopThroughOwner(machine, ports);
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
            final CompletableFuture<Optional<URI>> first = CompletableFuture.supplyAsync(() -> afterBarrier(start, a));
            final CompletableFuture<Optional<URI>> second = CompletableFuture.supplyAsync(() -> afterBarrier(start, b));
            final Optional<URI> one = first.get(START_WITHIN.toSeconds() * 2, TimeUnit.SECONDS);
            final Optional<URI> other = second.get(START_WITHIN.toSeconds() * 2, TimeUnit.SECONDS);

            assertThat(one).contains(URI.create("http://127.0.0.1:" + ports.http()));
            assertThat(other).isEqualTo(one);
            assertThat(query(ports, "SELECT count() FROM qdb_server").path("dataset").path(0).path(0).asLong()).as("one start").isEqualTo(1);
        } finally {
            a.close();
            b.close();
            stopThroughOwner(machine, ports);
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

    /// `sql`'s answer from the server's `/exec`, which must be 200.
    private static JsonNode query(final MachineQuestDb.Ports ports, final String sql) throws IOException, InterruptedException {
        try (final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            final HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports.http() + "/exec?query="
                    + URLEncoder.encode(sql, StandardCharsets.UTF_8).replace("+", "%20"))).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            return JsonFile.JSON.readTree(r.body());
        }
    }

    private static JsonNode registry(final Path machine) throws IOException {
        return JsonFile.JSON.readTree(Files.readString(machine.resolve("server.json")));
    }

    /// Stops the server `server.json` names through a fresh instance on its owner's data root, as the owner would, and
    /// waits for the notice; nothing when there is no `server.json`.
    private static void stopThroughOwner(final Path machine, final MachineQuestDb.Ports ports) throws IOException {
        if (!Files.exists(machine.resolve("server.json"))) {
            return;
        }
        final RecordingSink sink = new RecordingSink();
        try (final MachineQuestDb owner = new MachineQuestDb(machine, Path.of(registry(machine).path("owner").asString()), "", sink, ports)) {
            if (owner.want(false).startsWith("stopping")) {
                sink.await(Intent.Notify.class, n -> n.text().equals("QuestDB is down") || n.text().contains("not stopped"), STOP_WITHIN);
            }
        }
    }
}
