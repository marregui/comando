package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [EmbeddedQuestDb]: the bundled database, started and stopped from the window, reachable from this machine only.
/// - every listener binds to `127.0.0.1` and line-protocol UDP is off
/// - it starts on a fresh data root, reports it, and listens on loopback only
/// - `isRunning` answers at once while a start holds the server, and a second toggle waits with a notice
class EmbeddedQuestDbTest {

    /// Every `*_BIND_TO` setting in [EmbeddedQuestDb#LOOPBACK] starts with `127.0.0.1:` and `QDB_LINE_UDP_ENABLED` is
    /// `false`.
    @Test
    void everyListenerStaysOnTheLoopbackInterface() {
        assertThat(EmbeddedQuestDb.LOOPBACK).containsEntry("QDB_LINE_UDP_ENABLED", "false");
        assertThat(EmbeddedQuestDb.LOOPBACK.entrySet().stream().filter(e -> e.getKey().endsWith("_BIND_TO")).map(Map.Entry::getValue))
                .hasSize(4)
                .allSatisfy(bind -> assertThat(bind).startsWith("127.0.0.1:"));
    }

    /// A start on a missing data root creates it, posts an INFO notice and runs; its Postgres port accepts a connection
    /// on loopback and refuses one on this machine's LAN address, when it has one.
    @Test
    void startsOnAFreshDataRootAndListensOnLoopbackOnly(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("questdb");
        int pg = freePort();
        int http = freePort();
        RecordingSink sink = new RecordingSink();
        EmbeddedQuestDb db = new EmbeddedQuestDb(root, sink, Map.of(
                "QDB_HTTP_NET_BIND_TO", "127.0.0.1:" + http,
                "QDB_HTTP_MIN_NET_BIND_TO", "127.0.0.1:" + freePort(),
                "QDB_PG_NET_BIND_TO", "127.0.0.1:" + pg,
                "QDB_LINE_TCP_NET_BIND_TO", "127.0.0.1:" + freePort(),
                "QDB_LINE_UDP_ENABLED", "false"));
        String property = System.getProperty(EmbeddedQuestDb.LOG_CONFIG_PROPERTY);
        // QuestDB reads its log configuration once per process: another test's start may have read it already
        io.questdb.log.LogFactory.closeInstance();
        try {
            db.toggle();
            Intent.Notify up = sink.await(Intent.Notify.class, n -> n.text().startsWith("QuestDB") || n.text().startsWith("Cannot"), Duration.ofSeconds(60));

            assertThat(up.level()).as(up.text()).isEqualTo(Notice.Level.INFO);
            assertThat(db.isRunning()).isTrue();
            assertThat(Files.isDirectory(root)).isTrue();
            assertThat(db.log()).as("the day's log, in the root's log folder").isRegularFile()
                    .hasParent(root.resolve("log"));
            assertThat(db.log().getFileName().toString()).matches("questdb-\\d{8}\\.log");
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), pg), 2_000);
            }
            Optional<InetAddress> lan = lanAddress();
            if (lan.isPresent()) {
                assertThatThrownBy(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(lan.get(), pg), 2_000);
                    }
                }).isInstanceOf(IOException.class);
            }
        } finally {
            db.close();
            io.questdb.log.LogFactory.closeInstance();
            if (property == null) {
                System.clearProperty(EmbeddedQuestDb.LOG_CONFIG_PROPERTY);
            } else {
                System.setProperty(EmbeddedQuestDb.LOG_CONFIG_PROPERTY, property);
            }
        }
    }

    /// comando's log configuration is written unless the log is chosen already: by the property set to another file, or
    /// by a file writer in the root's `conf/log.conf`; a property naming comando's own file from another root is
    /// replaced.
    @Test
    void theLogConfigurationIsComandosUnlessOneIsChosen(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("questdb");
        Path ours = root.resolve("conf").resolve(EmbeddedQuestDb.LOG_CONFIG_NAME);

        assertThat(EmbeddedQuestDb.logConfig(root, null)).contains(ours);
        assertThat(ours).hasContent(EmbeddedQuestDb.LOG_CONFIG);
        assertThat(EmbeddedQuestDb.LOG_CONFIG).contains("w.file.location=${log.dir}/questdb-${date:yyyyMMdd}.log");
        assertThat(EmbeddedQuestDb.logConfig(root, "/elsewhere/" + EmbeddedQuestDb.LOG_CONFIG_NAME)).contains(ours);
        assertThat(EmbeddedQuestDb.logConfig(root, "/mine/log.conf")).as("the user's choice").isEmpty();

        Files.writeString(root.resolve("conf").resolve("log.conf"), "writers=file\n#w.file.class=x\n");
        assertThat(EmbeddedQuestDb.logConfig(root, null)).as("a commented file writer chooses nothing").contains(ours);
        Files.writeString(root.resolve("conf").resolve("log.conf"), "writers=file\n w.file.class=io.questdb.log.LogFileWriter\n");
        assertThat(EmbeddedQuestDb.logConfig(root, null)).as("a file writer of the user's").isEmpty();
    }

    /// While a start on a data root that is a file holds the server, `isRunning` answers false within 2 s and a second
    /// toggle notices "QuestDB is starting"; the start then fails with one ERROR notice and the database is not
    /// running.
    @Test
    void isRunningAnswersAtOnceWhileAStartHoldsTheServerAndASecondToggleWaits(@TempDir Path dir) throws Exception {
        // a root that is a file: the start fails quickly, once the test lets it run
        Path root = Files.writeString(dir.resolve("not-a-directory"), "x");
        RecordingSink sink = new RecordingSink();
        EmbeddedQuestDb db = new EmbeddedQuestDb(root, sink);
        synchronized (db) {
            db.toggle(); // its thread now waits for the monitor, as behind a start in progress
            assertThat(CompletableFuture.supplyAsync(db::isRunning).get(2, TimeUnit.SECONDS)).isFalse();

            db.toggle();
            Intent.Notify busy = sink.await(Intent.Notify.class, Duration.ofSeconds(5));
            assertThat(busy.text()).isEqualTo("QuestDB is starting");
        }
        Intent.Notify failed = sink.await(Intent.Notify.class, n -> n.text().startsWith("Cannot start"), Duration.ofSeconds(10));
        assertThat(failed.level()).isEqualTo(Notice.Level.ERROR);
        assertThat(sink.of(Intent.Notify.class)).hasSize(2);
        assertThat(db.isRunning()).isFalse();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Optional<InetAddress> lanAddress() throws SocketException {
        for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (n.isUp() && !n.isLoopback()) {
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) {
                        return Optional.of(a);
                    }
                }
            }
        }
        return Optional.empty();
    }
}
