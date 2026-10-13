package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeAdapter;
import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.claude.ClaudeVersion;
import dev.marregui.comando.claude.SessionCatalogue;
import dev.marregui.comando.claude.UiInstructions;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.effect.EffectRouter;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.db.DbAdapter;
import dev.marregui.comando.db.DbSettings;
import dev.marregui.comando.mcp.AuditLog;
import dev.marregui.comando.mcp.McpServer;
import dev.marregui.comando.mcp.McpTools;
import dev.marregui.comando.tools.ToolSettings;
import dev.marregui.comando.tools.ToolsAdapter;
import dev.marregui.comando.ui.dialogs.sessions.StoredConversation;
import dev.marregui.comando.ui.shell.ComandoUi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The ownership tree. Builds every part in dependency order and closes them in reverse, each once: the tool
 * runs, Claude's processes, the MCP server, the database lanes, the intent log, the reducer, the store (which
 * writes the panels last), the embedded QuestDB, and last the window, which stays up, greyed, until the rest has
 * closed; quitting has already ended its shells and live plots.
 */
public final class App implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(App.class.getName());
    static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(55);

    private final Deque<AutoCloseable> owned = new ArrayDeque<>();
    private final StateLoop loop;
    private final ClaudeAdapter claude;
    private final ComandoUi ui;
    private final String claudeExecutable;
    private volatile boolean closed;

    public App(AppConfig config, String version, Runnable onQuit) {
        claudeExecutable = config.claudeExecutable();
        try {
            Store store = own(new Store(config.home(), config.keychain() ? Secrets.forPlatform() : Secrets.NONE));
            Store.Loaded loaded = store.load(config.legacy() == null ? Legacy.none() : new Legacy(config.legacy()));
            AppState initial = AppState.initial(loaded.panels(),
                    loaded.connections().stream().map(ConnectionState::closed).toList(),
                    loaded.sessions().stream().map(SessionState::closed).toList());

            TranscriptRegistry transcripts = new TranscriptRegistry();
            ResultRegistry results = new ResultRegistry();
            ConsoleRegistry consoles = new ConsoleRegistry();
            SchemaRegistry schemas = new SchemaRegistry();
            DeferredEffects effects = new DeferredEffects();
            loop = new StateLoop(initial, new Reducer(Clock.SYSTEM), effects);
            owned.push(loop);
            if (config.recordIntents()) {
                loop.addObserver(own(new IntentLog(config.logDir(), initial)));
            }

            DbAdapter db = own(new DbAdapter(loop, results, schemas, DbSettings.DEFAULTS));
            McpServer mcp;
            try {
                McpTools tools = new McpTools(loop, loop::state, db, new AuditLog(config.auditDir()), Clock.SYSTEM, APPROVAL_TIMEOUT);
                mcp = own(McpServer.start(tools, 0, version));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot start the MCP server", e);
            }
            claude = own(new ClaudeAdapter(loop, transcripts, mcp,
                    new ClaudeSettings(config.claudeExecutable(), config.runDir(), config.claudeProjects(), UiInstructions.TEXT)));
            ToolsAdapter devTools = own(new ToolsAdapter(loop, consoles, ToolSettings.defaults()));
            EmbeddedQuestDb questDb = new EmbeddedQuestDb(config.questDbRoot(), loop);
            SessionCatalogue catalogue = new SessionCatalogue(config.claudeProjects());
            ui = new ComandoUi(new ComandoUi.Dependencies(
                    loop, loop::state, loop, transcripts, results, consoles, schemas, store, store,
                    () -> catalogue.scan(500).stream().map(e -> new StoredConversation(e.sessionId(), e.cwd(), e.branch(), e.title(),
                            e.lastPrompt(), e.modifiedMillis(), e.sizeBytes())).toList(),
                    questDb, version, config.home(), config.logDir(), onQuit));
            effects.target = new EffectRouter(db, claude, mcp, ui, store, devTools);
            // the embedded QuestDB stops last: it may still serve the connections closed above
            owned.addLast(questDb);
            // the window closes last: it stays, greyed and titled "closing…", until everything behind it has closed
            owned.addLast(ui);
            LOG.log(System.Logger.Level.INFO, "comando " + version + " ready [home=" + config.home() + ", mcp=" + mcp.url()
                    + ", claude=" + config.claudeExecutable() + ']');
        } catch (RuntimeException | Error e) {
            // roll back: close what was built, in reverse order, then report the cause
            close();
            throw e;
        }
    }

    /** Starts the reducer, shows the window and loads stored conversations for display. */
    public void start() {
        loop.start();
        ui.start();
        List<SessionSpec> withHistory = loop.state().sessions().stream().map(SessionState::spec)
                .filter(s -> !s.claudeSessionId().isEmpty()).toList();
        for (SessionSpec s : withHistory) {
            claude.loadHistory(s);
        }
        String executable = claudeExecutable;
        Thread.ofVirtual().name("claude-version").start(() -> {
            String problem = ClaudeVersion.check(executable);
            if (!problem.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, problem);
                loop.dispatch(new Intent.Notify(Notice.Level.WARN, problem, false));
            }
        });
    }

    public StateLoop loop() {
        return loop;
    }

    private <T extends AutoCloseable> T own(T resource) {
        owned.push(resource);
        return resource;
    }

    /** Closes everything in reverse order of creation; a failure in one does not stop the others. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        while (!owned.isEmpty()) {
            AutoCloseable c = owned.pop();
            try {
                c.close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "close failed [part=" + c.getClass().getSimpleName() + "]: " + e.getMessage(), e);
            }
        }
        LOG.log(System.Logger.Level.INFO, "comando closed");
    }

    /** The reducer needs its effect handler before the adapters that implement it exist. */
    static final class DeferredEffects implements StateLoop.EffectHandler {
        volatile StateLoop.EffectHandler target;

        @Override
        public void handle(Effect effect) {
            StateLoop.EffectHandler t = target;
            if (t != null) {
                t.handle(effect);
            }
        }
    }
}
