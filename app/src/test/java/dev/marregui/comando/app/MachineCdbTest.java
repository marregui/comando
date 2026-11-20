package dev.marregui.comando.app;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
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
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.action.Action;
import dev.marregui.comando.core.action.Actions;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [MachineCdb]: the machine's one database server, cdb, a process of its own that outlives the comando that
/// started it.
/// - `start.sh` runs cdb's server from the copied jar on the machine folder's `cdb/`, its HTTP and PG wire listeners
///   on `127.0.0.1`, with the kit's heap and GC options, refuses a path it cannot quote, and sets `stdout.log` aside
///   past 10 MiB; the machine folder is its auth folder, and every request it makes carries the owner secret
/// - a port another process holds, and a `server.json` of a later version, start nothing
/// - a start makes `start.sh`, `lib/` and `server.json` v1 with its owner and a token the server answers; another
///   instance uses it, starts no second one, and may not stop it; the owner's close leaves it up, its stop ends it
/// - two concurrent starts start one server
///
/// Every server runs in a `@TempDir` machine folder on free loopback ports, never the defaults, and is stopped in
/// a `finally` through its owner's stop; one a start left without a `server.json` is ended by the pid `start.sh`
/// printed to the instance that launched it, while that process still names the test's machine folder.
class MachineCdbTest {

    /// The machine server's default ports, which no test binds: a developer's machine server may hold them.
    private static final List<Integer> DEFAULT_PORTS = List.of(8812, 9000, 9003, 9009);
    /// A start copies the jar and waits up to 60 s for the server; a stop waits up to 25 s.
    private static final Duration START_WITHIN = Duration.ofSeconds(90);
    private static final Duration STOP_WITHIN = Duration.ofSeconds(40);

    /// `start.sh` runs `CdbServe` from the copied jar on the machine folder's `cdb/`, on the HTTP and PG wire ports
    /// asked for, bound to `127.0.0.1`, with the machine folder as its auth folder, prints the pid, and names the Java
    /// given; a folder holding `$` is refused.
    @Test
    void theServerIsCdbOnTheLoopbackInterface(@TempDir final Path dir) throws IOException {
        final MachineCdb.Ports ports = new MachineCdb.Ports(19000, 19003, 18812, 19009);
        final String script = MachineCdb.script(dir, ports, Path.of("/opt/jdk"), "cdb-0123456789ab.jar");

        assertThat(script).contains("nohup \"/opt/jdk/bin/java\" ",
                "-cp \"" + dir + "/lib/cdb-0123456789ab.jar\" " + MachineCdb.CDB_SERVE + " \"" + dir
                        + "/cdb\" 19000 --pg-port 18812",
                "--bind 127.0.0.1 --auth-dir \"" + dir + "\" >> \"" + dir + "/stdout.log\"");
        assertThat(script.lines()).contains("echo $!");
        assertThat(script).doesNotContain("19003", "19009", "QDB_");
        assertThatThrownBy(() -> MachineCdb.script(dir.resolve("a$b"), ports, Path.of("/opt/jdk"), "cdb.jar"))
                .isInstanceOf(IOException.class);
    }

    /// The server runs with the kit's `cdb-serve` options: G1 with two parallel and one concurrent GC thread, a
    /// periodic GC every 10 s, compact headers, and a heap of 16 MiB growing to 256 MiB.
    @Test
    void theServerRunsWithTheKitsHeapAndGc(@TempDir final Path dir) throws IOException {
        final String script = MachineCdb.script(dir, MachineCdb.Ports.DEFAULT, Path.of("/opt/jdk"), "cdb.jar");

        assertThat(script).contains("-XX:+UseG1GC", "-XX:ParallelGCThreads=2", "-XX:ConcGCThreads=1",
                "-XX:G1PeriodicGCInterval=10000", "-XX:+UseCompactObjectHeaders", "-Xms16m", "-Xmx256m");
    }

    /// The copied jar's name follows its bytes: the same jar gives the same name, another gives another.
    @Test
    void theJarsNameFollowsItsBytes(@TempDir final Path dir) throws IOException {
        final Path one = Files.writeString(dir.resolve("one.jar"), "one");
        final Path two = Files.writeString(dir.resolve("two.jar"), "two");

        assertThat(MachineCdb.jarName(one)).matches("cdb-[0-9a-f]{12}\\.jar")
                .isEqualTo(MachineCdb.jarName(one)).isNotEqualTo(MachineCdb.jarName(two));
        assertThat(MachineCdb.cdbJar()).isRegularFile();
    }

    /// `start.sh` moves a `stdout.log` over 10 MiB to `stdout.log.1`, over the one there, and starts an owner-only
    /// `stdout.log`; one of exactly 10 MiB stays. The Java it runs is a stub that exits at once.
    @Test
    void stdoutLogIsSetAsidePastTenMebibytes(@TempDir final Path dir) throws Exception {
        final Path jdk = Files.createDirectories(dir.resolve("jdk").resolve("bin")).getParent();
        final Path java = Files.writeString(jdk.resolve("bin").resolve("java"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        final Path script = Files.writeString(dir.resolve("start.sh"),
                MachineCdb.script(dir, MachineCdb.Ports.DEFAULT, jdk, "cdb.jar"));
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

    /// A start whose HTTP port a plain `ServerSocket` holds is refused with the contract's text, and nothing is
    /// started: no `start.sh`, no `server.json`.
    @Test
    void aPortAnotherProcessHoldsIsRefused(@TempDir final Path dir) throws IOException {
        // a backlog for both connects below: the socket never accepts, and a full backlog drops a connect unanswered
        try (final ServerSocket held = new ServerSocket(0, 50, loopback())) {
            final MachineCdb.Ports free = freePorts();
            final MachineCdb.Ports ports = new MachineCdb.Ports(held.getLocalPort(), free.httpMin(), free.pg(),
                    free.ilp());
            final RecordingSink sink = new RecordingSink();
            try (final MachineCdb db = new MachineCdb(dir.resolve("machine"), dir.resolve("data"), "", sink,
                    ports)) {
                assertThat(db.ensureUp()).isEmpty();
                assertThat(db.want(true)).startsWith("starting cdb");
                final Intent.Notify refused = sink.await(Intent.Notify.class, n -> n.text().startsWith("Cannot start"),
                        START_WITHIN);

                assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
                assertThat(refused.text()).isEqualTo("Cannot start cdb: port " + held.getLocalPort()
                        + " is held by a process that is not this machine's comando cdb");
                assertThat(dir.resolve("machine").resolve("server.json")).doesNotExist();
                assertThat(dir.resolve("machine").resolve("bin").resolve("start.sh")).doesNotExist();
            }
        }
    }

    /// A `server.json` of version 4 whose server is not live is used if live, else left: no start, the file unchanged,
    /// and a stop is refused.
    @Test
    void aRegistryOfALaterVersionIsNeverStartedOrRewritten(@TempDir final Path dir) throws IOException {
        final Path machine = Files.createDirectories(dir.resolve("machine"));
        final MachineCdb.Ports ports = freePorts();
        final String later = """
                {"version": 4, "pid": %d, "owner": "kit:/elsewhere", "name": "kit", "started": "2026-11-09T16:30:00Z",
                 "ports": {"http": %d, "httpMin": %d, "pg": %d, "ilp": %d}, "token": "00", "jar": "11.0.0",
                 "more": true}
                """.formatted(ProcessHandle.current().pid(), ports.http(), ports.httpMin(), ports.pg(), ports.ilp());
        Files.writeString(machine.resolve("server.json"), later);
        try (final MachineCdb db = new MachineCdb(machine, dir.resolve("data"), "", new RecordingSink(),
                ports)) {
            assertThat(db.ensureUp()).isEmpty();
            assertThat(db.want(false))
                    .isEqualTo("refused: its server.json is of version 4, which this comando uses and does not manage");
            assertThat(machine.resolve("server.json")).hasContent(later);
            assertThat(machine.resolve("bin").resolve("start.sh")).doesNotExist();
        }
    }

    /// An empty `server.json`, as a write cut before its first byte leaves it, names no server: a stop finds none
    /// running.
    @Test
    void anEmptyRegistryNamesNoServer(@TempDir final Path dir) throws IOException {
        final Path machine = Files.createDirectories(dir.resolve("machine"));
        Files.writeString(machine.resolve("server.json"), "");
        try (final MachineCdb db = new MachineCdb(machine, dir.resolve("data"), "", new RecordingSink(),
                freePorts())) {
            assertThat(db.want(false)).isEqualTo("cdb is not running");
        }
    }

    /// The owner secret is read from the machine folder's `secret`: 64 lowercase hex characters with or without the
    /// line end; a missing file, a short, long, upper-case or two-line one gives none.
    @Test
    void theSecretIsSixtyFourLowercaseHexCharacters(@TempDir final Path dir) throws IOException {
        final String secret = "0123456789abcdef".repeat(4);
        final Path file = dir.resolve("secret");

        assertThat(MachineCdb.secretIn(dir)).isEmpty();
        Files.writeString(file, secret + "\n");
        assertThat(MachineCdb.secretIn(dir)).contains(secret);
        Files.writeString(file, secret);
        assertThat(MachineCdb.secretIn(dir)).contains(secret);
        for (final String bad : List.of(secret.substring(1) + "\n", secret + "0\n", secret.toUpperCase(Locale.ROOT),
                secret + "\n\n", "")) {
            Files.writeString(file, bad);
            assertThat(MachineCdb.secretIn(dir)).as(bad).isEmpty();
        }
    }

    /// A connection to the machine's PG port on `localhost` or `127.0.0.1` with no password, or QuestDB's `quest`,
    /// gets the owner secret as its password; another host, another port or a password of its own is left as it is,
    /// and so is every connection while the folder holds no secret.
    @Test
    void aBlankPasswordToTheMachinePortGetsTheSecret(@TempDir final Path dir) throws IOException {
        final Path machine = Files.createDirectories(dir.resolve("machine"));
        final MachineCdb.Ports ports = new MachineCdb.Ports(19000, 19003, 18812, 19009);
        final String secret = "0123456789abcdef".repeat(4);
        final ConnectionSpec local = new ConnectionSpec("m", "localhost", 18812, "qdb", "admin", "", false);
        final ConnectionSpec loopback = new ConnectionSpec("m", "127.0.0.1", 18812, "qdb", "admin", "", false);
        final List<ConnectionSpec> untouched = List.of(
                new ConnectionSpec("m", "db.example", 18812, "qdb", "admin", "", false),
                new ConnectionSpec("m", "localhost", 8812, "qdb", "admin", "", false),
                new ConnectionSpec("m", "localhost", 18812, "qdb", "admin", "own", false));
        try (final MachineCdb db = new MachineCdb(machine, dir.resolve("data"), "", new RecordingSink(), ports)) {
            assertThat(db.withSecret(local)).isEqualTo(local);
            Files.writeString(machine.resolve("secret"), secret + "\n");
            assertThat(db.withSecret(local).password()).isEqualTo(secret);
            assertThat(db.withSecret(loopback).password()).isEqualTo(secret);
            final ConnectionSpec template = new ConnectionSpec("m", "localhost", 18812, "qdb", "admin", "quest", false);
            assertThat(db.withSecret(template).password()).as("QuestDB's default password").isEqualTo(secret);
            for (final ConnectionSpec spec : untouched) {
                assertThat(db.withSecret(spec)).as(spec.host() + ":" + spec.port()).isEqualTo(spec);
            }
        }
    }

    /// With comando's own jar gone, the start falls back to the newest `cdb-*.jar` in `lib/` by modification time and
    /// writes `start.sh` to run it (the newest copy sorts neither first nor last by name, so a choice by name fails);
    /// with no copy there, the error that made it fall back is thrown unchanged.
    @Test
    void aGoneJarStartsFromTheNewestCopyElseRethrows(@TempDir final Path dir) throws IOException {
        final Path machine = Files.createDirectories(dir.resolve("machine"));
        final Path lib = Files.createDirectories(machine.resolve("lib"));
        final MachineCdb.Ports ports = new MachineCdb.Ports(19000, 19003, 18812, 19009);
        final IOException why = new IOException("cannot locate cdb's jar");
        try (final MachineCdb db = new MachineCdb(machine, dir.resolve("data"), "", new RecordingSink(), ports)) {
            assertThatThrownBy(() -> db.fromCopy(lib, why)).isSameAs(why);
            final Path first = Files.writeString(lib.resolve("cdb-000000000001.jar"), "a");
            final Path middle = Files.writeString(lib.resolve("cdb-000000000002.jar"), "b");
            final Path last = Files.writeString(lib.resolve("cdb-000000000003.jar"), "c");
            Files.setLastModifiedTime(first, FileTime.fromMillis(2_000_000L));
            Files.setLastModifiedTime(middle, FileTime.fromMillis(3_000_000L));
            Files.setLastModifiedTime(last, FileTime.fromMillis(1_000_000L));
            final MachineCdb.Prepared prepared = db.fromCopy(lib, why);
            assertThat(prepared.jar()).isEqualTo("cdb-000000000002.jar");
            assertThat(prepared.script()).isEqualTo(machine.resolve("bin").resolve("start.sh")).isExecutable();
            assertThat(Files.readString(prepared.script())).contains("cdb-000000000002.jar")
                    .doesNotContain("cdb-000000000001.jar").doesNotContain("cdb-000000000003.jar");
        }
    }

    /// The live check sends the machine folder's owner secret as `Authorization: Bearer`: a fake server that answers
    /// the registry's token only to that bearer, under this JVM's pid, is live while `secret` is there, and not once
    /// it is gone, when the check sends no header.
    @Test
    void theLiveCheckSendsTheSecret(@TempDir final Path dir) throws IOException {
        final Path machine = Files.createDirectories(dir.resolve("machine"));
        final String secret = "fedcba9876543210".repeat(4);
        Files.writeString(machine.resolve("secret"), secret + "\n");
        final List<String> seen = Collections.synchronizedList(new ArrayList<>());
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/exec", exchange -> {
            final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            seen.add(String.valueOf(authorization));
            final byte[] body = "{\"dataset\":[[\"tok\"]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(("Bearer " + secret).equals(authorization) ? 200 : 401, body.length);
            try (final OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            final int port = server.getAddress().getPort();
            final MachineCdb.Ports ports = new MachineCdb.Ports(port, port, port, port);
            Files.writeString(machine.resolve("server.json"), """
                    {"version": 3, "pid": %d, "owner": "%s", "name": "comando", "started": "2026-11-18T00:00:00Z",
                     "ports": {"http": %d, "httpMin": %d, "pg": %d, "ilp": %d}, "token": "tok", "jar": "cdb.jar"}
                    """.formatted(ProcessHandle.current().pid(), dir.resolve("data"), port, port, port, port));
            try (final MachineCdb db = new MachineCdb(machine, dir.resolve("data"), "", new RecordingSink(), ports)) {
                assertThat(db.secret()).contains(secret);
                assertThat(db.live()).contains(URI.create("http://127.0.0.1:" + port));
                Files.delete(machine.resolve("secret"));
                assertThat(db.secret()).isEmpty();
                assertThat(db.live()).isEmpty();
            }
            assertThat(seen).containsExactly("Bearer " + secret, "null");
        } finally {
            server.stop(0);
        }
    }

    /// An agent names this server as `server_start` and `server_stop` take it: its switch's name, lower-cased, is
    /// among the `server` argument's choices, which `ActionTools` checks before it looks the switch up by name.
    @Test
    void anAgentNamesTheServerAsTheSwitchDoes(@TempDir final Path dir) throws IOException {
        try (final MachineCdb db = new MachineCdb(dir.resolve("machine"), dir.resolve("data"), "",
                new RecordingSink(), freePorts())) {
            for (final String tool : List.of("server_start", "server_stop")) {
                final Action action = Actions.byTool(tool).orElseThrow();
                assertThat(action.args()).as(tool).anySatisfy(arg -> assertThat(arg.choices())
                        .contains(db.name().toLowerCase(Locale.ROOT)));
            }
        }
    }

    /// One server's life. Instance A starts it: `bin/start.sh` (0700), `lib/cdb-<hash>.jar`, and `server.json`
    /// v2 (0600) naming A's data root as owner, its live pid, its ports and a 32-hex token that `/exec` answers; its PG
    /// port answers on loopback and not on this machine's LAN address, when it has one. Instance B, on another data
    /// root, sees it live, gets the same endpoint from `ensureUp` without a second start (the pid unchanged), says who
    /// started it, and is refused a stop. It serves no web console page, and nothing listens on the ILP TCP and
    /// health ports. A's close leaves the process alive; the owner's stop ends it and
    /// removes `server.json`.
    @Test
    void anOwnerStartsTheServerAnotherInstanceUsesItAndOnlyTheOwnerStopsIt(@TempDir final Path dir) throws Exception {
        final Path machine = dir.resolve("machine");
        final Path rootA = dir.resolve("a");
        final MachineCdb.Ports ports = freePorts();
        final RecordingSink sink = new RecordingSink();
        final MachineCdb a = new MachineCdb(machine, rootA, "a", sink, ports);
        final MachineCdb b = new MachineCdb(machine, dir.resolve("b"), "b", sink, ports);
        try {
            assertThat(a.want(false)).isEqualTo("cdb is not running");
            assertThat(a.want(true))
                    .isEqualTo("starting cdb; the outcome comes as a notice, and ui_get_state's servers show it");
            final Intent.Notify up = sink.await(Intent.Notify.class,
                    n -> n.text().startsWith("cdb is up") || n.text().startsWith("Cannot"), START_WITHIN);
            assertThat(up.level()).as(up.text()).isEqualTo(Notice.Level.INFO);
            assertThat(up.text()).isEqualTo("cdb is up: PG wire on 127.0.0.1:" + ports.pg()
                    + ", HTTP on http://localhost:" + ports.http());

            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(machine))).isEqualTo("rwx------");
            final Path script = machine.resolve("bin").resolve("start.sh");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(script))).isEqualTo("rwx------");
            final String version = MachineCdb.jarName(MachineCdb.cdbJar());
            assertThat(machine.resolve("lib").resolve(version)).isRegularFile();
            final Path registry = machine.resolve("server.json");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(registry))).isEqualTo("rw-------");
            final JsonNode entry = registry(machine);
            assertThat(entry.path("version").asInt()).isEqualTo(3);
            assertThat(entry.path("owner").asString()).isEqualTo(rootA.toAbsolutePath().normalize().toString());
            assertThat(entry.path("name").asString()).isEqualTo("comando · a");
            assertThat(entry.path("ports").path("http").asInt()).isEqualTo(ports.http());
            assertThat(entry.path("ports").path("httpMin").asInt()).isEqualTo(ports.httpMin());
            assertThat(entry.path("ports").path("pg").asInt()).isEqualTo(ports.pg());
            assertThat(entry.path("ports").path("ilp").asInt()).isEqualTo(ports.ilp());
            assertThat(entry.path("jar").asString()).isEqualTo(version);
            final String token = entry.path("token").asString();
            assertThat(token).matches("[0-9a-f]{32}");
            final long pid = entry.path("pid").asLong();
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).contains(true);
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(machine.resolve("secret"))))
                    .as("the owner secret the server made").isEqualTo("rw-------");
            assertThat(status(machine, ports, "/exec?query=SELECT%201", false)).as("no bearer").isEqualTo(401);
            assertThat(query(machine, ports, "SELECT token FROM cdb_server ORDER BY ts DESC LIMIT 1").path("dataset")
                    .path(0).path(0).asString()).isEqualTo(token);
            assertThat(a.want(true)).startsWith("cdb is up already: PG wire on 127.0.0.1:" + ports.pg() + ',');
            assertThat(a.log()).as("cdb's stdout.log, in the machine folder").isRegularFile()
                    .isEqualTo(machine.toAbsolutePath().normalize().resolve("stdout.log"));
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
            assertThat(status(machine, ports, "/index.html", true)).as("the web console").isEqualTo(404);
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
            assertThat(query(machine, ports, "SELECT count() FROM cdb_server").path("dataset").path(0).path(0).asLong())
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
    /// start: the `start.lock` serialises them, and `cdb_server` holds one row.
    @Test
    void twoConcurrentStartsStartOneServer(@TempDir final Path dir) throws Exception {
        final Path machine = dir.resolve("machine");
        final MachineCdb.Ports ports = freePorts();
        final MachineCdb a = new MachineCdb(machine, dir.resolve("a"), "a", new RecordingSink(), ports);
        final MachineCdb b = new MachineCdb(machine, dir.resolve("b"), "b", new RecordingSink(), ports);
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
            assertThat(query(machine, ports, "SELECT count() FROM cdb_server").path("dataset").path(0).path(0).asLong())
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

    private static Optional<URI> afterBarrier(final CyclicBarrier start, final MachineCdb db) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException("the other start did not come", e);
        }
        return db.ensureUp();
    }

    /// `request` with the owner secret in `machine` as its bearer; the server made the file at its start.
    private static HttpRequest.Builder bearer(final Path machine, final HttpRequest.Builder request) {
        return request.header("Authorization", "Bearer " + MachineCdb.secretIn(machine).orElseThrow());
    }

    /// Four ports free on loopback now, none of the defaults; released before the server binds them.
    private static MachineCdb.Ports freePorts() throws IOException {
        final List<ServerSocket> held = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                held.add(new ServerSocket(0, 1, loopback()));
            }
            final List<Integer> ports = held.stream().map(ServerSocket::getLocalPort).toList();
            assertThat(ports).doesNotContainAnyElementsOf(DEFAULT_PORTS);
            return new MachineCdb.Ports(ports.get(0), ports.get(1), ports.get(2), ports.get(3));
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
    private static void endLaunched(final Path machine, final MachineCdb... dbs) throws Exception {
        final String root = machine.toAbsolutePath().normalize().resolve("cdb").toString();
        for (final MachineCdb db : dbs) {
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

    /// `sql`'s answer from the server's `/exec`, sent with the owner secret in `machine`, which must be 200.
    private static JsonNode query(
            final Path machine,
            final MachineCdb.Ports ports,
            final String sql
    ) throws IOException, InterruptedException {
        try (final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            final URI uri = URI.create("http://127.0.0.1:" + ports.http() + "/exec?query="
                    + URLEncoder.encode(sql, StandardCharsets.UTF_8).replace("+", "%20"));
            final HttpResponse<String> r = http.send(bearer(machine, HttpRequest.newBuilder(uri))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            return Json.readTree(r.body());
        }
    }

    /// Runs `script` with bash, as a start does: it ends with 0 within 10 s and prints the pid of the background
    /// `java`, which is then waited for as long again, since that child opens `stdout.log` for append after the script
    /// has ended and would put it back while `@TempDir` deletes the folder.
    private static void runScript(final Path script) throws Exception {
        final Process p = new ProcessBuilder("/bin/bash", script.toString()).redirectErrorStream(true).start();
        try {
            assertThat(p.waitFor(10, TimeUnit.SECONDS)).as("start.sh ended").isTrue();
            final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.exitValue()).as(out).isZero();
            final String last = out.strip().lines().toList().getLast();
            assertThat(last).as(out).matches("\\d+");
            final long pid = Long.parseLong(last);
            final Optional<ProcessHandle> child = ProcessHandle.of(pid);
            if (child.isPresent()) {
                child.get().onExit().get(10, TimeUnit.SECONDS);
            }
        } finally {
            p.destroyForcibly();
        }
    }

    private static JsonNode registry(final Path machine) throws IOException {
        return Json.readTree(Files.readString(machine.resolve("server.json")));
    }

    /// The status of a GET of `path` on the server's HTTP port, with the owner secret in `machine` when `withSecret`.
    private static int status(
            final Path machine,
            final MachineCdb.Ports ports,
            final String path,
            final boolean withSecret
    ) throws IOException, InterruptedException {
        try (final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            final URI uri = URI.create("http://127.0.0.1:" + ports.http() + path);
            final HttpRequest.Builder request = HttpRequest.newBuilder(uri);
            return http.send((withSecret ? bearer(machine, request) : request).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    /// Stops the server `server.json` names through a fresh instance on its owner's data root, as the owner would, and
    /// waits for the notice; nothing when there is no `server.json`.
    private static void stopThroughOwner(final Path machine, final MachineCdb.Ports ports) throws IOException {
        if (!Files.exists(machine.resolve("server.json"))) {
            return;
        }
        final RecordingSink sink = new RecordingSink();
        final Path ownerRoot = Path.of(registry(machine).path("owner").asString());
        try (final MachineCdb owner = new MachineCdb(machine, ownerRoot, "", sink, ports)) {
            if (owner.want(false).startsWith("stopping")) {
                sink.await(Intent.Notify.class,
                        n -> n.text().equals("cdb is down") || n.text().contains("not stopped"), STOP_WITHIN);
            }
        }
    }
}
