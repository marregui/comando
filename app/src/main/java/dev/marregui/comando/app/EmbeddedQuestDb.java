package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ServerSwitch;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.shell.ServerToggle;
import io.questdb.Bootstrap;
import io.questdb.DefaultBootstrapConfiguration;
import io.questdb.ServerMain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * A QuestDB server comando starts in its data root, on QuestDB's default ports (PG wire 8812, web console 9000,
 * ILP 9009), reachable from this machine only: QuestDB binds every interface by default and its default login is
 * public. The settings go through QuestDB's environment overrides, so the user's {@code server.conf} stays as it is.
 */
final class EmbeddedQuestDb implements ServerToggle, ServerSwitch, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(EmbeddedQuestDb.class.getName());

    /** QuestDB's {@code QDB_} overrides that keep every listener on the loopback interface. */
    static final Map<String, String> LOOPBACK = Map.of(
            "QDB_HTTP_NET_BIND_TO", "127.0.0.1:9000",
            "QDB_HTTP_MIN_NET_BIND_TO", "127.0.0.1:9003",
            "QDB_PG_NET_BIND_TO", "127.0.0.1:8812",
            "QDB_LINE_TCP_NET_BIND_TO", "127.0.0.1:9009",
            "QDB_LINE_UDP_ENABLED", "false");

    /** The system property QuestDB reads its log configuration's path from, before {@code conf/log.conf}. */
    static final String LOG_CONFIG_PROPERTY = "out";
    /** comando's log configuration, written in the root's {@code conf/} at each start. */
    static final String LOG_CONFIG_NAME = "comando-log.conf";
    /**
     * QuestDB's shipped configuration with its file writer on: one file a day in the root's {@code log/}, ending in
     * {@code .log} so that a panel follows it, kept a week; the console as shipped.
     */
    static final String LOG_CONFIG = """
            # Written by comando at each start of its QuestDB: the console as shipped, and one file a day in log/, kept a
            # week, for Help > Show QuestDB's log. A file writer set in conf/log.conf is used instead of this file.
            writers=file,stdout,http.min
            w.file.class=io.questdb.log.LogRollingFileWriter
            w.file.location=${log.dir}/questdb-${date:yyyyMMdd}.log
            w.file.level=INFO,ERROR,CRITICAL,ADVISORY
            w.file.rollEvery=day
            w.file.lifeDuration=7d
            w.stdout.class=io.questdb.log.LogConsoleWriter
            w.stdout.level=INFO
            w.http.min.class=io.questdb.log.LogConsoleWriter
            w.http.min.level=ERROR
            w.http.min.scope=http-min-server
            """;

    private final Path root;
    private final IntentSink intents;
    private final Map<String, String> overrides;
    /** The starts and stops under way, each on a virtual thread; closed with the server. */
    private final Errands errands = new Errands("questdb");
    /** Written under the monitor; read without it, so the EDT never waits for a start or stop. */
    private volatile ServerMain server;
    /** A start or stop is under way; a toggle meanwhile does nothing but say so. */
    private final AtomicBoolean busy = new AtomicBoolean();

    EmbeddedQuestDb(Path root, IntentSink intents) {
        this(root, intents, LOOPBACK);
    }

    EmbeddedQuestDb(Path root, IntentSink intents, Map<String, String> overrides) {
        this.root = root;
        this.intents = intents;
        this.overrides = Map.copyOf(overrides);
    }

    @Override
    public String name() {
        return "QuestDB";
    }

    /** Returns at once, also while a start or stop holds the monitor: the window asks every second. */
    @Override
    public boolean isRunning() {
        return server != null;
    }

    /**
     * Starts a stopped server or stops a running one on a virtual thread. While one start or stop is under way, a
     * second toggle (a second click, or Start pressed during a stop) changes nothing and says what is happening.
     */
    @Override
    public void toggle() {
        if (!busy.compareAndSet(false, true)) {
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, server == null ? "QuestDB is starting" : "QuestDB is stopping", false));
            return;
        }
        errands.start("questdb-toggle", () -> {
            try {
                flip(null);
            } finally {
                busy.set(false);
            }
        });
    }

    /** Nothing to add: QuestDB runs in this instance or not at all. */
    @Override
    public String status() {
        return ServerToggle.super.status();
    }

    /** An agent's start or stop (record 121); the start or stop re-checks under the monitor. */
    @Override
    public String want(boolean up) {
        if (up == (server != null)) {
            return up ? "QuestDB is up already: PG wire on 8812, console on http://localhost:9000" : "QuestDB is not running";
        }
        if (!busy.compareAndSet(false, true)) {
            return (server == null ? "QuestDB is starting" : "QuestDB is stopping") + "; try again when that ends";
        }
        Thread t = errands.start(up ? "questdb-start" : "questdb-stop", () -> {
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
        return (up ? "starting" : "stopping") + " QuestDB; the outcome comes as a notice, and ui_get_state's servers show it";
    }

    /** Starts a stopped server or stops a running one; {@code want}, when given, is the only direction it may go. */
    private synchronized void flip(Boolean want) {
        if (want != null && want == (server != null)) {
            return;
        }
        if (server == null) {
            try {
                server = start();
                LOG.log(System.Logger.Level.INFO, "QuestDB started [root=" + root + "]");
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "QuestDB is up: PG wire on 8812, console on http://localhost:9000", false));
            } catch (RuntimeException e) {
                intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot start QuestDB [root=" + root + "]: " + e.getMessage(), e));
            }
        } else {
            stop();
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "QuestDB is down", false));
        }
    }

    /**
     * The newest daily log in the root's {@code log/}, else today's that the next start writes; null when the root's
     * {@code conf/log.conf} chose a file of its own, which then is where to look.
     */
    @Override
    public Path log() {
        Path dir = root.resolve("log");
        try (Stream<Path> files = Files.list(dir)) {
            Optional<Path> newest = files.filter(f -> f.getFileName().toString().matches("questdb-\\d{8}\\.log"))
                    .max(Comparator.comparing(f -> f.getFileName().toString()));
            if (newest.isPresent()) {
                return newest.get();
            }
        } catch (IOException | UncheckedIOException e) {
            // no log folder yet: today's file, which says there is no log yet
        }
        return dir.resolve("questdb-" + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC)) + ".log");
    }

    /**
     * Writes comando's log configuration into {@code root/conf} and returns it, unless the QuestDB log is chosen
     * already: by the {@link #LOG_CONFIG_PROPERTY} property set by anyone but comando, or by a file writer in the
     * root's {@code conf/log.conf}. QuestDB reads it once per process, at its first start.
     */
    static Optional<Path> logConfig(Path root, String property) throws IOException {
        Path ours = root.resolve("conf").resolve(LOG_CONFIG_NAME);
        if (property != null && !property.isBlank() && !Path.of(property).getFileName().toString().equals(LOG_CONFIG_NAME)) {
            return Optional.empty();
        }
        Path shipped = root.resolve("conf").resolve("log.conf");
        if (Files.isRegularFile(shipped) && Files.readAllLines(shipped).stream().map(String::strip).anyMatch(l -> l.startsWith("w.file.class="))) {
            return Optional.empty();
        }
        Files.createDirectories(ours.getParent());
        AtomicFiles.writeString(ours, LOG_CONFIG);
        return Optional.of(ours);
    }

    /** Starts the server, creating its root on first use; a server that fails to start is closed again. */
    private ServerMain start() {
        try {
            Files.createDirectories(root);
            logConfig(root, System.getProperty(LOG_CONFIG_PROPERTY))
                    .ifPresent(c -> System.setProperty(LOG_CONFIG_PROPERTY, c.toAbsolutePath().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + root, e);
        }
        Map<String, String> env = new HashMap<>(System.getenv());
        env.putAll(overrides);
        ServerMain s = new ServerMain(new Bootstrap(new DefaultBootstrapConfiguration() {
            @Override
            public Map<String, String> getEnv() {
                return env;
            }
        }, "-d", root.toString()));
        try {
            s.start(false);
            return s;
        } catch (RuntimeException e) {
            s.close();
            throw e;
        }
    }

    private void stop() {
        try {
            server.close();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot close QuestDB cleanly", e);
        } finally {
            server = null;
        }
    }

    @Override
    public void close() {
        // outside the monitor: a start or stop under way holds it, and the close waits for it, bounded
        try {
            errands.close();
        } finally {
            closeServer();
        }
    }

    private synchronized void closeServer() {
        if (server != null) {
            stop();
        }
    }
}
