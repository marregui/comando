package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.shell.ServerToggle;
import io.questdb.ServerMain;

import java.nio.file.Path;

/** A QuestDB server comando starts in its data root, on QuestDB's default ports (PG wire 8812, web console 9000). */
final class EmbeddedQuestDb implements ServerToggle, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(EmbeddedQuestDb.class.getName());

    private final Path root;
    private final IntentSink intents;
    private ServerMain server; // guarded by this

    EmbeddedQuestDb(Path root, IntentSink intents) {
        this.root = root;
        this.intents = intents;
    }

    @Override
    public String name() {
        return "QuestDB";
    }

    @Override
    public synchronized boolean isRunning() {
        return server != null;
    }

    @Override
    public void toggle() {
        Thread.ofVirtual().name("questdb-toggle").start(() -> {
            synchronized (this) {
                if (server == null) {
                    try {
                        ServerMain s = new ServerMain("-d", root.toString());
                        s.start(false);
                        server = s;
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
        });
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
