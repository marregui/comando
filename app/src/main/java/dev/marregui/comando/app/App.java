package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeAdapter;
import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.claude.ClaudeVersion;
import dev.marregui.comando.claude.Kit;
import dev.marregui.comando.claude.SessionCatalogue;
import dev.marregui.comando.claude.UiInstructions;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.effect.EffectRouter;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.HomeDir;
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
import dev.marregui.comando.tools.ToolsAdapter;
import dev.marregui.comando.ui.dialogs.sessions.StoredConversation;
import dev.marregui.comando.ui.kit.text.MermaidDiagrams;
import dev.marregui.comando.ui.shell.ComandoUi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The ownership tree. Builds every part in dependency order and closes them in reverse, each once: the tool
 * runs, Claude's processes, the MCP server, the database lanes, the reducer, the intent log, the store (which
 * writes the panels last), the embedded QuestDB, and last the window, which stays up, greyed, until the rest has
 * closed; quitting has already ended its shells and live plots.
 */
public final class App implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(App.class.getName());
    static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(55);

    private final Deque<AutoCloseable> owned = new ArrayDeque<>();
    /** How often the Files tab's open folders are read for changes: often enough to follow a session writing. */
    static final Duration FOLDERS_EVERY = Duration.ofSeconds(5);
    /** How often the status bar's plan usage is read; each read is one local {@code claude -p /usage}, about a second. */
    static final Duration PLAN_USAGE_EVERY = Duration.ofMinutes(5);
    private final StateLoop loop;
    private final ClaudeAdapter claude;
    private final ComandoUi ui;
    private final ToolsAdapter tools;
    private final String claudeExecutable;
    private final Kit kit;
    private final boolean offerKit;
    private volatile boolean closed;

    public App(AppConfig config, String version, Runnable onQuit) {
        claudeExecutable = config.claudeExecutable();
        offerKit = config.offerKit();
        try {
            Store store = own(new Store(config.home(), config.keychain() ? Secrets.forPlatform() : Secrets.NONE));
            Store.Loaded loaded = store.load();
            AppState initial = AppState.initial(loaded.panels(),
                    loaded.connections().stream().map(ConnectionState::closed).toList(),
                    loaded.sessions().stream().map(SessionState::closed).toList(),
                    store.loadFolders(HomeDir.claudeDir())).withClosed(loaded.closed());

            TranscriptRegistry transcripts = new TranscriptRegistry();
            ResultRegistry results = new ResultRegistry();
            ConsoleRegistry consoles = new ConsoleRegistry();
            SchemaRegistry schemas = new SchemaRegistry();
            DeferredEffects effects = new DeferredEffects();
            // the log is owned before the loop, so it closes after it and records the intents of the shutdown
            IntentLog log = config.recordIntents() ? own(new IntentLog(config.logDir(), initial)) : null;
            loop = new StateLoop(initial, new Reducer(Clock.SYSTEM), effects);
            owned.push(loop);
            store.onNotice(text -> loop.dispatch(new Intent.Notify(Notice.Level.WARN, text, false)));
            store.onFoldersChanged(folders -> loop.dispatch(new Intent.FoldersRead(folders)));
            if (log != null) {
                loop.addObserver(log);
            }

            DbAdapter db = own(new DbAdapter(loop, results, schemas, DbSettings.DEFAULTS));
            // the previews draw Mermaid diagrams with mmdc into a cache, once per diagram text
            MermaidDiagrams.install(config.home().resolve("cache").resolve("mermaid"));
            own(MermaidDiagrams::shutdown);
            McpServer mcp;
            try {
                McpTools tools = new McpTools(loop, loop::state, transcripts, db, new AuditLog(config.auditDir()), Clock.SYSTEM, APPROVAL_TIMEOUT);
                mcp = own(McpServer.start(tools, 0, version));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot start the MCP server", e);
            }
            claude = own(new ClaudeAdapter(loop, transcripts, mcp,
                    new ClaudeSettings(config.claudeExecutable(), config.runDir(), config.claudeProjects(), UiInstructions.TEXT)));
            kit = new Kit(Kit.Bundle.resources(), Kit.Cli.process(config.claudeExecutable()), config.kitDir(), HomeDir.claudeDir());
            ToolsFile toolsFile = new ToolsFile(config.home(), kit.gradleSerial());
            ToolsAdapter devTools = own(new ToolsAdapter(loop, consoles, toolsFile::read));
            tools = devTools;
            EmbeddedQuestDb questDb = new EmbeddedQuestDb(config.questDbRoot(), loop);
            SessionCatalogue catalogue = new SessionCatalogue(config.claudeProjects());
            ui = new ComandoUi(new ComandoUi.Dependencies(
                    loop, loop::state, loop, transcripts, results, consoles, schemas, store,
                    () -> catalogue.scan(500).stream().map(e -> new StoredConversation(e.sessionId(), e.cwd(), e.branch(), e.title(),
                            e.lastPrompt(), e.modifiedMillis(), e.sizeBytes())).toList(),
                    questDb, new KitSetup(kit, toolsFile, store), version, config.home(), config.logDir(), onQuit));
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
        // the Files tab's open folders stay in sync whether the tab shows or not; closed ones are not read
        tools.pollFolders(FOLDERS_EVERY);
        claude.watchPlanUsage(PLAN_USAGE_EVERY);
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
        Thread.ofVirtual().name("kit").start(this::checkKit);
    }

    /**
     * Keeps comando's copy of its Claude Code kit equal to this build's, then, on the user's own data root, offers the
     * kit when Claude Code does not have it and no setup of the user's own was found, or says that the installed kit
     * changed with comando.
     */
    private void checkKit() {
        boolean changed;
        try {
            changed = kit.refresh();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write the Claude Code kit [dir=" + kit.dir() + "]: " + e.getMessage(), e);
            loop.dispatch(new Intent.Notify(Notice.Level.WARN, "comando could not write its Claude Code kit into " + kit.dir() + ": "
                    + e.getMessage() + "; Gradle runs use ./gradlew", false));
            return;
        }
        if (!offerKit) {
            return;
        }
        try {
            Kit.Status status = kit.status();
            switch (atStart(status, changed)) {
                case UPDATED -> loop.dispatch(new Intent.Notify(Notice.Level.INFO, "comando's Claude Code kit is now "
                        + status.installedVersion() + "; new Claude sessions load it", false));
                case OFFER -> ui.offerKit(KitSetup.offer(status));
                case NOTHING -> {
                }
            }
        } catch (IOException | RuntimeException e) {
            // Claude Code missing or answering oddly: the version check has said so; the kit waits for Help
            LOG.log(System.Logger.Level.INFO, "the Claude Code kit's state is unknown: " + e.getMessage());
        }
    }

    /** What comando does about its kit at start, on the user's own data root. */
    enum KitAtStart {
        /** Claude Code loads comando's copy and the copy changed: say so. */
        UPDATED,
        /** Claude Code lacks the kit and no setup of the user's own stands in its way: offer it. */
        OFFER,
        NOTHING
    }

    static KitAtStart atStart(Kit.Status status, boolean changed) {
        if (status.installed()) {
            return changed && status.loadsCopy() ? KitAtStart.UPDATED : KitAtStart.NOTHING;
        }
        return status.alreadySetUp() ? KitAtStart.NOTHING : KitAtStart.OFFER;
    }

    public StateLoop loop() {
        return loop;
    }

    /** The window; the snapshot tool drives it as a user would. */
    ComandoUi ui() {
        return ui;
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
