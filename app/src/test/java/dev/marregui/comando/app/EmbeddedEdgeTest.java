package dev.marregui.comando.app;

import com.sun.net.httpserver.HttpServer;
import dev.marregui.comando.app.EmbeddedEdge.Entry;
import dev.marregui.comando.app.EmbeddedEdge.Located;
import dev.marregui.comando.app.EmbeddedEdge.Ports;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [EmbeddedEdge]: comando's one Edge on the machine, started and stopped from the window, run from a fake distribution
/// whose `bin/run.sh` records its environment and its pid and sleeps, so no test starts a real Edge or uses its ports.
/// - it installs the zip into the data root, writes the configuration and patches the JMX port at every start
/// - a newer zip replaces the home and keeps the data folder beside it
/// - the registry names the running Edge; another instance sees it and cannot stop it; a stale entry is cleared and the
///   process it names is never signalled
/// - a busy port, a missing distribution or an Edge that never answers starts nothing
/// - closing comando stops the Edge this instance started, and only that one
class EmbeddedEdgeTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path dir;
    private Path registry;
    private Ports ports;
    private final List<EmbeddedEdge> edges = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        registry = dir.resolve("comando-edge.json");
        ports = new Ports(freePort(), freePort(), freePort(), freePort());
    }

    @AfterEach
    void tearDown() {
        // each stops the Edge it started, should a test have failed with one running
        edges.forEach(EmbeddedEdge::close);
    }

    /// A start unzips the distribution into the root, writes `conf/config.xml` with the MQTT, WebSocket and admin API
    /// ports on `127.0.0.1`, sets `run.sh`'s JMX port, names the data and log folders and a loopback JMX host to the
    /// process, says where it is up and writes the registry with its pid; a second toggle stops that process, says so
    /// and removes the registry.
    @Test
    void startsFromTheDistributionOnItsOwnLoopbackPortsAndStops() throws Exception {
        Path root = dir.resolve("a/edge");
        RecordingSink sink = new RecordingSink();
        EmbeddedEdge edge = edge(root, dir.resolve("a"), "", sink, zip("hivemq-edge-1"));

        Intent.Notify up = toggle(edge, sink);

        assertThat(up.text()).isEqualTo("Edge is up: MQTT 127.0.0.1:" + ports.mqtt() + ", UI http://localhost:" + ports.api() + " (admin/hivemq)");
        Path home = root.resolve("hivemq-edge-1");
        String config = Files.readString(root.resolve("conf/config.xml"));
        for (int port : List.of(ports.mqtt(), ports.websocket(), ports.api())) {
            assertThat(config).as("port " + port).contains("<port>" + port + "</port><bind-address>127.0.0.1</bind-address>");
        }
        assertThat(config).doesNotContain("protocol-adapter").contains("<path>/mqtt</path>");
        String run = Files.readString(home.resolve("bin/run.sh"));
        assertThat(run).contains("jmxremote.port=" + ports.jmx()).doesNotContain("jmxremote.port=9010");
        String env = Files.readString(home.resolve("env.txt"));
        assertThat(env).contains("HIVEMQ_DATA_FOLDER=" + root.resolve("data"), "HIVEMQ_LOG_FOLDER=" + root.resolve("log"),
                "HIVEMQ_CONFIG_FOLDER=" + root.resolve("conf"),
                "-Dcom.sun.management.jmxremote.host=127.0.0.1");
        long pid = Long.parseLong(Files.readString(home.resolve("edge.pid")).strip());
        Entry entry = read();
        assertThat(entry.pid()).isEqualTo(pid);
        assertThat(entry.owner()).isEqualTo(dir.resolve("a").toString());
        assertThat(entry.ports()).isEqualTo(ports);
        assertThat(edge.running()).isTrue();

        Intent.Notify down = toggle(edge, sink);

        assertThat(down.text()).isEqualTo("Edge is down");
        assertThat(sink.of(Intent.Notify.class).getFirst().text()).as("said at the click").isEqualTo("Stopping the Edge…");
        assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
        assertThat(registry).doesNotExist();
        assertThat(edge.running()).isFalse();
    }

    /// A click says at once what it does, before the start's work; while the start runs the Edge is busy and its
    /// status says so, and a second click only says to wait.
    @Test
    void aStartIsSaidAtOnceAndTheEdgeIsBusyUntilItEnds() throws Exception {
        Path root = dir.resolve("a/edge");
        RecordingSink sink = new RecordingSink();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Path zip = zip("hivemq-edge-1");
        Predicate<Ports> written = pidWritten(root);
        EmbeddedEdge edge = new EmbeddedEdge(root, dir.resolve("a"), "", sink, registry, ports, () -> Located.of(zip), p -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return written.test(p);
        }, WAIT);
        edges.add(edge);

        edge.toggle();
        Intent.Notify first = sink.await(Intent.Notify.class, WAIT);
        assertThat(first.text()).isEqualTo("Starting the Edge…");
        assertThat(edge.busy()).isTrue();
        assertThat(edge.status()).isEqualTo("the Edge is starting");
        sink.clear();
        edge.toggle();
        assertThat(sink.await(Intent.Notify.class, WAIT).text()).isEqualTo("The Edge is starting; wait for it");

        release.countDown();
        await().atMost(WAIT).until(() -> !edge.busy());
        assertThat(sink.of(Intent.Notify.class).getLast().text()).startsWith("Edge is up");
        assertThat(edge.status()).isEmpty();
        toggle(edge, sink);
    }

    /// What the user or the Edge's UI saved in the configuration stays across starts; only the listeners and the admin
    /// API are set to comando's ports again, and the shipped files the folder lacks are copied in.
    @Test
    void theSavedConfigurationStaysAndOnlyThePortsAreSetAgain() throws Exception {
        Path shipped = Files.createDirectories(dir.resolve("shipped"));
        Files.writeString(shipped.resolve("logback.xml"), "<configuration/>");
        Files.writeString(shipped.resolve("config.xml"), "<hivemq/>");
        Path conf = dir.resolve("conf");
        EmbeddedEdge.prepareConfig(shipped, conf, ports);
        assertThat(conf.resolve("logback.xml")).hasContent("<configuration/>");
        String saved = Files.readString(conf.resolve("config.xml")).replace("<port>" + ports.mqtt() + "</port>", "<port>1883</port>")
                .replace("</hivemq>", "<protocol-adapters><protocol-adapter><adapterId>plc-1</adapterId></protocol-adapter></protocol-adapters></hivemq>");
        Files.writeString(conf.resolve("config.xml"), saved);

        EmbeddedEdge.prepareConfig(shipped, conf, ports);

        String config = Files.readString(conf.resolve("config.xml"));
        assertThat(config).contains("<adapterId>plc-1</adapterId>", "<port>" + ports.mqtt() + "</port>").doesNotContain("<port>1883</port>");
        assertThat(config.indexOf("mqtt-listeners")).isLessThan(config.indexOf("protocol-adapters"));
        assertThat(EmbeddedEdge.enforce("not xml", ports)).as("unparseable: the template").contains("<port>" + ports.api() + "</port>");
    }

    /// The same zip again starts the installed home as it is; a newer zip with another top folder replaces the home,
    /// the old one is gone, `installed` names the new zip, and a file in `edge/data` is still there.
    @Test
    void aNewerZipReplacesTheHomeAndTheDataStays() throws Exception {
        Path root = dir.resolve("a/edge");
        RecordingSink sink = new RecordingSink();
        AtomicReference<Path> zip = new AtomicReference<>(zip("hivemq-edge-1"));
        EmbeddedEdge edge = new EmbeddedEdge(root, dir.resolve("a"), "", sink, registry, ports, () -> Located.of(zip.get()), pidWritten(root), WAIT);
        edges.add(edge);
        toggle(edge, sink);
        toggle(edge, sink);
        Files.writeString(root.resolve("data/kept.txt"), "kept");
        Files.writeString(root.resolve("hivemq-edge-1/mine.txt"), "same zip, same home");

        toggle(edge, sink);
        toggle(edge, sink);
        assertThat(root.resolve("hivemq-edge-1/mine.txt")).as("the same zip is not unzipped again").exists();

        Path newer = zip("hivemq-edge-2");
        Files.setLastModifiedTime(newer, FileTime.from(Instant.now().plusSeconds(60)));
        zip.set(newer);
        Intent.Notify up = toggle(edge, sink);

        assertThat(up.text()).startsWith("Edge is up");
        assertThat(root.resolve("hivemq-edge-1")).doesNotExist();
        assertThat(root.resolve("hivemq-edge-2/edge.pid")).exists();
        assertThat(root.resolve("data/kept.txt")).hasContent("kept");
        assertThat(Files.readString(root.resolve("installed"))).contains("home=hivemq-edge-2");
        toggle(edge, sink);
    }

    /// While instance `b` runs the Edge, the main instance on the same registry sees it running; its toggle says who
    /// started it and leaves the process and the registry as they were; its close leaves them too. `b` then stops it.
    @Test
    void anotherInstanceSeesTheEdgeRunningAndCannotStopIt() throws Exception {
        RecordingSink sinkB = new RecordingSink();
        EmbeddedEdge b = edge(dir.resolve("b/edge"), dir.resolve("b"), "b", sinkB, zip("hivemq-edge-1"));
        RecordingSink sinkMain = new RecordingSink();
        EmbeddedEdge main = edge(dir.resolve("main/edge"), dir.resolve("main"), "", sinkMain, zip("hivemq-edge-1"));
        toggle(b, sinkB);
        Entry started = read();

        assertThat(main.running()).isTrue();
        await().atMost(WAIT).until(main::isRunning);
        Intent.Notify refused = toggle(main, sinkMain);
        main.close();

        assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
        assertThat(refused.text()).isEqualTo("Cannot stop the Edge: it was started by comando · b (" + dir.resolve("b")
                + "), and only that comando stops it");
        assertThat(sinkMain.of(Intent.Notify.class)).as("a refusal says nothing else").hasSize(1);
        assertThat(main.status()).as("the button's tooltip says whose Edge it is")
                .isEqualTo("started by comando · b (" + dir.resolve("b") + "); only that comando stops it");
        assertThat(b.status()).as("the owner's button adds nothing").isEmpty();
        assertThat(ProcessHandle.of(started.pid()).filter(ProcessHandle::isAlive)).isPresent();
        assertThat(read()).isEqualTo(started);
        assertThat(dir.resolve("main/edge")).as("nothing installed for the instance that did not start it").doesNotExist();

        assertThat(toggle(b, sinkB).text()).isEqualTo("Edge is down");
        assertThat(ProcessHandle.of(started.pid()).filter(ProcessHandle::isAlive)).isEmpty();
    }

    /// A registry whose pid is dead names no running Edge and is replaced by the next start; one whose pid is alive but
    /// started at another time is another process on a reused pid: it is stale too, and that process is left running.
    @Test
    void aStaleRegistryIsClearedAndTheProcessItNamesIsNeverSignalled() throws Exception {
        Path root = dir.resolve("a/edge");
        RecordingSink sink = new RecordingSink();
        EmbeddedEdge edge = edge(root, dir.resolve("a"), "", sink, zip("hivemq-edge-1"));
        Process gone = new ProcessBuilder("true").start();
        gone.waitFor();
        write(new Entry(gone.pid(), dir.resolve("a").toString(), "", ports, Instant.now().toString()));

        assertThat(edge.running()).as("a dead pid").isFalse();
        assertThat(toggle(edge, sink).text()).startsWith("Edge is up");
        assertThat(read().pid()).isNotEqualTo(gone.pid());
        toggle(edge, sink);

        Process other = new ProcessBuilder("sleep", "60").start();
        try {
            write(new Entry(other.pid(), dir.resolve("a").toString(), "", ports, Instant.now().minusSeconds(3600).toString()));

            assertThat(edge.running()).as("a live pid of another process").isFalse();
            assertThat(toggle(edge, sink).text()).startsWith("Edge is up");
            assertThat(other.isAlive()).as("the process on the reused pid is left alone").isTrue();
            assertThat(read().pid()).isNotEqualTo(other.pid());
            toggle(edge, sink);
        } finally {
            other.destroy();
        }
    }

    /// With the MQTT port held by another listener on loopback, a toggle warns naming that port, starts no process,
    /// installs nothing and writes no registry; the listener keeps its port.
    @Test
    void aBusyPortRefusesTheStart() throws Exception {
        Path root = dir.resolve("a/edge");
        RecordingSink sink = new RecordingSink();
        EmbeddedEdge edge = edge(root, dir.resolve("a"), "", sink, zip("hivemq-edge-1"));
        try (ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), ports.mqtt()));

            Intent.Notify refused = toggle(edge, sink);

            assertThat(refused.level()).isEqualTo(Notice.Level.WARN);
            assertThat(refused.text()).isEqualTo("Cannot start the Edge: port " + ports.mqtt() + " on 127.0.0.1 is in use by another process");
            assertThat(registry).doesNotExist();
            assertThat(root).doesNotExist();
            assertThat(holder.isBound() && !holder.isClosed()).isTrue();
        }
    }

    /// A port whose only trace is a connection in TIME_WAIT, as the stopped Edge leaves behind the instances' MQTT
    /// connections, is free; one with a listener is taken (2026-10-29: a start right after a stop was refused).
    @Test
    void aConnectionInTimeWaitLeavesThePortFree() throws Exception {
        InetAddress lo = InetAddress.getLoopbackAddress();
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(lo, 0));
        int port = server.getLocalPort();
        Socket client = new Socket(lo, port);
        Socket accepted = server.accept();
        assertThat(EmbeddedEdge.taken(port)).as("listening").isTrue();
        // the server's side closes first, as the Edge's does when it stops: that side keeps the port in TIME_WAIT
        accepted.close();
        server.close();
        Thread.sleep(100);
        client.close();
        assertThat(EmbeddedEdge.taken(port)).as("only TIME_WAIT left").isFalse();
    }

    /// Closing the instance that started the Edge stops its process and removes the registry, bounded.
    @Test
    void closeStopsTheEdgeThisInstanceStarted() throws Exception {
        RecordingSink sink = new RecordingSink();
        EmbeddedEdge edge = edge(dir.resolve("a/edge"), dir.resolve("a"), "", sink, zip("hivemq-edge-1"));
        toggle(edge, sink);
        long pid = read().pid();

        long start = System.nanoTime();
        edge.close();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(EmbeddedEdge.STOP_WAIT);
        assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
        assertThat(registry).doesNotExist();
    }

    /// An Edge whose admin API never answers within the bound is stopped, its registry removed, and the failure said.
    @Test
    void anEdgeThatNeverAnswersIsStopped() throws Exception {
        Path root = dir.resolve("a/edge");
        RecordingSink sink = new RecordingSink();
        Path zip = zip("hivemq-edge-1");
        EmbeddedEdge edge = new EmbeddedEdge(root, dir.resolve("a"), "", sink, registry, ports, () -> Located.of(zip), p -> false, Duration.ofSeconds(2));
        edges.add(edge);

        Intent.Notify failed = toggle(edge, sink);

        assertThat(failed.level()).isEqualTo(Notice.Level.ERROR);
        assertThat(failed.text()).startsWith("The Edge was not up within 2 s and was stopped; see " + root.resolve("stdout.log"));
        long pid = Long.parseLong(Files.readString(root.resolve("hivemq-edge-1/edge.pid")).strip());
        assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
        assertThat(registry).doesNotExist();
    }

    /// The distribution is the zip named by the property before the variable, else the pinned release in `dist`,
    /// downloaded there once: a second start reads the kept zip without asking the server again. A download whose
    /// SHA-256 differs keeps nothing, and the toggle warns saying why and how to name a zip, and starts nothing.
    @Test
    void theDistributionIsTheNamedZipElseTheReleaseDownloadedOnce() throws Exception {
        byte[] release = "the release".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(release));
        AtomicInteger asked = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/edge/", x -> {
            if (!x.getRequestURI().getPath().endsWith("/hivemq-edge-full-2026.15.zip")) {
                x.sendResponseHeaders(404, -1);
                x.close();
                return;
            }
            asked.incrementAndGet();
            x.sendResponseHeaders(200, release.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(release);
            }
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/edge/hivemq-edge-full-2026.15.zip");
            Path dist = dir.resolve("dist");
            RecordingSink sink = new RecordingSink();

            Path named = Files.writeString(dir.resolve("mine.zip"), "x");
            Path other = Files.writeString(dir.resolve("other.zip"), "x");
            assertThat(EmbeddedEdge.locate(null, named.toString(), dist, url, sha, sink).zip()).isEqualTo(named);
            assertThat(EmbeddedEdge.locate(named.toString(), other.toString(), dist, url, sha, sink).zip()).as("the property first").isEqualTo(named);
            assertThat(EmbeddedEdge.locate(dir.resolve("missing.zip").toString(), null, dist, url, sha, sink).problem()).contains("missing.zip");
            assertThat(asked).as("a named zip downloads nothing").hasValue(0);

            Located fetched = EmbeddedEdge.locate(null, " ", dist, url, sha, sink);
            assertThat(fetched.zip()).isEqualTo(dist.resolve("hivemq-edge-full-2026.15.zip"));
            assertThat(fetched.zip()).hasBinaryContent(release);
            assertThat(sink.of(Intent.Notify.class).getLast().text()).contains("Downloading the Edge", "hivemq-edge-full-2026.15.zip");
            assertThat(EmbeddedEdge.locate(null, null, dist, url, sha, sink).zip()).isEqualTo(fetched.zip());
            assertThat(asked).as("downloaded once").hasValue(1);

            Path elsewhere = dir.resolve("elsewhere");
            Located refused = EmbeddedEdge.locate(null, null, elsewhere, url, "00" + sha.substring(2), sink);
            assertThat(refused.zip()).isNull();
            assertThat(refused.problem()).contains("SHA-256", "-Dcomando.edge.zip", "COMANDO_EDGE_ZIP");
            try (Stream<Path> left = Files.list(elsewhere)) {
                assertThat(left).as("neither the zip nor its .part").isEmpty();
            }

            EmbeddedEdge edge = new EmbeddedEdge(dir.resolve("a/edge"), dir.resolve("a"), "", sink, registry, ports,
                    () -> EmbeddedEdge.locate(null, null, dir.resolve("a/edge/dist"), url.resolve("missing.zip"), sha, sink), p -> true, WAIT);
            edges.add(edge);
            Intent.Notify warned = toggle(edge, sink);
            assertThat(warned.level()).isEqualTo(Notice.Level.WARN);
            assertThat(warned.text()).contains("HTTP 404", "-Dcomando.edge.zip");
            assertThat(registry).doesNotExist();
        } finally {
            server.stop(0);
        }
    }

    /// The pinned release, downloaded from hivemq.com and started for real on free loopback ports: its admin API
    /// answers and its MQTT listener accepts a connection; the toggle stops it. Opt-in, as it downloads 359 MB the
    /// first time (`./gradlew :app:test -PedgeLiveTest=true`); `-Dcomando.edge.zip` is not read here.
    @Test
    void thePinnedReleaseStartsForReal() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("comando.edgeLiveTest"), "set -Dcomando.edgeLiveTest=true to download and run the release");
        Path cache = Path.of(System.getProperty("comando.edgeLiveCache", dir.resolve("dist").toString()));
        RecordingSink sink = new RecordingSink();
        EmbeddedEdge edge = new EmbeddedEdge(dir.resolve("live/edge"), dir.resolve("live"), "", sink, registry, ports,
                () -> EmbeddedEdge.locate(null, null, cache, EmbeddedEdge.RELEASE_ZIP, EmbeddedEdge.RELEASE_SHA256, sink),
                p -> answers(p.api()), Duration.ofMinutes(10));
        edges.add(edge);
        Intent.Notify up = toggle(edge, sink, Duration.ofMinutes(15));
        assertThat(up.text()).as(up.text()).contains("Edge is up", "127.0.0.1:" + ports.mqtt());
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), ports.mqtt()), 2_000);
        }
        assertThat(toggle(edge, sink).text()).isEqualTo("Edge is down");
    }

    // ---- helpers

    private EmbeddedEdge edge(Path root, Path owner, String name, RecordingSink sink, Path zip) {
        EmbeddedEdge e = new EmbeddedEdge(root, owner, name, sink, registry, ports, () -> Located.of(zip), pidWritten(root), WAIT);
        edges.add(e);
        return e;
    }

    /// Up once the fake `run.sh` of a home under `root` has written the pid of a live process.
    private static Predicate<Ports> pidWritten(Path root) {
        return p -> {
            try (Stream<Path> homes = Files.list(root)) {
                return homes.map(h -> h.resolve("edge.pid")).filter(Files::isRegularFile).anyMatch(EmbeddedEdgeTest::names);
            } catch (IOException e) {
                return false;
            }
        };
    }

    private static boolean names(Path pidFile) {
        try {
            return ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip())).filter(ProcessHandle::isAlive).isPresent();
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    /// Toggles once the previous toggle has finished, and returns the notice of its outcome: the last it posts, after
    /// the "Starting the Edge…" or "Stopping the Edge…" said at once.
    private static Intent.Notify toggle(EmbeddedEdge edge, RecordingSink sink) {
        return toggle(edge, sink, WAIT);
    }

    private static Intent.Notify toggle(EmbeddedEdge edge, RecordingSink sink, Duration within) {
        await().atMost(within).until(() -> !edge.busy());
        sink.clear();
        edge.toggle();
        sink.await(Intent.Notify.class, within);
        await().atMost(within).until(() -> !edge.busy());
        return sink.of(Intent.Notify.class).getLast();
    }

    /// Something answers HTTP on `port` on loopback, whatever its status.
    private static boolean answers(int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
            c.setConnectTimeout(1_000);
            c.setReadTimeout(2_000);
            try {
                return c.getResponseCode() > 0;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /// A distribution with one top folder: `bin/run.sh` with the shipped JMX port records its environment and pid in
    /// the home and becomes `sleep`, so the recorded pid is the process comando started; `conf/config.xml` as shipped.
    private Path zip(String top) throws IOException {
        Path zip = dir.resolve(top + "-" + System.nanoTime() + ".zip");
        String run = """
                #!/bin/bash
                JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9010"
                cd "$(dirname "${BASH_SOURCE[0]}")/.."
                rm -f edge.pid
                env > env.txt
                echo $$ > edge.pid
                exec sleep 300
                """;
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : Map.of(top + "/bin/run.sh", run, top + "/conf/config.xml", "<hivemq/>\n").entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return zip;
    }

    private Entry read() throws IOException {
        return JsonFile.JSON.readValue(registry.toFile(), Entry.class);
    }

    private void write(Entry e) throws IOException {
        Files.write(registry, JsonFile.JSON.writeValueAsBytes(e));
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
