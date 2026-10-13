package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.shell.ServerToggle;
import io.questdb.Bootstrap;
import io.questdb.DefaultBootstrapConfiguration;
import io.questdb.ServerMain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A QuestDB server comando starts in its data root, on QuestDB's default ports (PG wire 8812, web console 9000,
 * ILP 9009), reachable from this machine only: QuestDB binds every interface by default and its default login is
 * public. The settings go through QuestDB's environment overrides, so the user's {@code server.conf} stays as it is.
 */
final class EmbeddedQuestDb implements ServerToggle, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(EmbeddedQuestDb.class.getName());

    /** QuestDB's {@code QDB_} overrides that keep every listener on the loopback interface. */
    static final Map<String, String> LOOPBACK = Map.of(
            "QDB_HTTP_NET_BIND_TO", "127.0.0.1:9000",
            "QDB_HTTP_MIN_NET_BIND_TO", "127.0.0.1:9003",
            "QDB_PG_NET_BIND_TO", "127.0.0.1:8812",
            "QDB_LINE_TCP_NET_BIND_TO", "127.0.0.1:9009",
            "QDB_LINE_UDP_ENABLED", "false");

    private final Path root;
    private final IntentSink intents;
    private final Map<String, String> overrides;
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
        Thread.ofVirtual().name("questdb-toggle").start(() -> {
            try {
                flip();
            } finally {
                busy.set(false);
            }
        });
    }

    private synchronized void flip() {
        if (server == null) {
            try {
                server = start();
                LOG.log(System.Logger.Level.INFO, "QuestDB started [root=" + root + "]");
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, "QuestDB is up: PG wire on 8812, console on http://localhost:9000", false));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot start QuestDB [root=" + root + "]", e);
                intents.dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot start QuestDB: " + e.getMessage(), false));
            }
        } else {
            stop();
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "QuestDB is down", false));
        }
    }

    /** Starts the server, creating its root on first use; a server that fails to start is closed again. */
    private ServerMain start() {
        try {
            Files.createDirectories(root);
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
            LOG.log(System.Logger.Level.WARNING, "QuestDB did not close cleanly: " + e.getMessage());
        } finally {
            server = null;
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            stop();
        }
    }
}
