package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeAdapter;
import dev.marregui.comando.claude.RootLaunch;
import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.claude.MetricsLog;
import dev.marregui.comando.claude.ClaudeTopics;
import dev.marregui.comando.claude.ClaudeVersion;
import dev.marregui.comando.claude.SessionCatalogue;
import dev.marregui.comando.claude.UiInstructions;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.effect.EffectRouter;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.model.KitRoot;
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
import dev.marregui.comando.mcp.Peers;
import dev.marregui.comando.tools.KitGit;
import dev.marregui.comando.tools.ToolSettings;
import dev.marregui.comando.tools.ToolsAdapter;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.dialogs.sessions.StoredConversation;
import dev.marregui.comando.ui.kit.desktop.DesktopTrash;
import dev.marregui.comando.ui.kit.text.LocalImages;
import dev.marregui.comando.ui.kit.text.MermaidDiagrams;
import dev.marregui.comando.ui.shell.ComandoUi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The ownership tree. Builds every part in dependency order and closes them in reverse, each once: this instance's
 * presence among the other instances and its link to the comando Edge, the tool runs, Claude's processes, the MCP
 * server, the database lanes, the reducer, the intent log, the store (which writes the panels last), the Edge this
 * instance started, the embedded QuestDB, and last the window, which stays up, greyed, until the rest has closed;
 * quitting has already ended its shells and live plots.
 */
public final class App implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(App.class.getName());
    static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(55);

    private final Deque<AutoCloseable> owned = new ArrayDeque<>();
    /** How often the Files tab's open folders are read for changes: often enough to follow a session writing. */
    static final Duration FOLDERS_EVERY = Duration.ofSeconds(5);
    /**
     * How often the status bar's plan usage is read; each read is one local {@code claude -p /usage}, about a second.
     */
    static final Duration PLAN_USAGE_EVERY = Duration.ofMinutes(5);
    /** How often the kit's version and its remote's releases are read, on a strand of their own. */
    static final Duration KIT_EVERY = Duration.ofHours(6);
    /** {@code -Dcomando.peers=false}, set by window tests and picture tools, keeps this instance off the Edge. */
    static final String PEERS_PROPERTY = "comando.peers";
    private final StateLoop loop;
    private final TranscriptRegistry transcripts;
    private final ClaudeAdapter claude;
    private final Errands errands;
    private final ComandoUi ui;
    private final ToolsAdapter tools;
    private final String claudeExecutable;
    private final KitRoot root;
    private final RootLaunch launch;
    private final RootSetup setup;
    /** The comando Edge as the instances' broker, and this instance among them (record 123); null when left out. */
    private final MqttPeerBroker peerBroker;
    private final Peers peers;
    /** Reads the kit's version at start and every {@link #KIT_EVERY}. */
    private final ScheduledExecutorService kitCheck = Threads.timedStrand("kit-check");
    private volatile boolean closed;

    public App(AppConfig config, String version, Runnable onQuit) {
        claudeExecutable = config.claudeExecutable();
        root = config.root();
        launch = RootLaunch.on(root);
        try {
            // what reads HomeDir.claudeDir without being handed the root reads this one, until this closes
            KitRoot before = KitRoot.current();
            KitRoot.use(root);
            own(() -> KitRoot.use(before));
            own(kitCheck::shutdownNow);
            KitGit git = own(new KitGit());
            setup = new RootSetup(root, git);
            Store store = own(new Store(config.home(), config.keychain() ? Secrets.forPlatform() : Secrets.NONE));
            // the start-up checks run as errands, closed just before the store
            errands = own(new Errands("app"));
            Store.Loaded loaded = store.load();
            AppState initial = AppState.initial(loaded.panels(),
                    loaded.connections().stream().map(ConnectionState::closed).toList(),
                    loaded.sessions().stream().map(SessionState::closed).toList(),
                    store.withKitFolders(store.loadFolders(root.dir()), root)).withClosed(loaded.closed());

            transcripts = new TranscriptRegistry();
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
            // and show the pictures a document names on this machine, an SVG drawn into a cache once
            LocalImages.install(config.home().resolve("cache").resolve("images"));
            own(LocalImages::shutdown);
            McpServer mcp;
            McpTools mcpTools;
            try {
                mcpTools = new McpTools(loop, loop::state, transcripts, db, new AuditLog(config.auditDir()), Clock.SYSTEM, APPROVAL_TIMEOUT);
                mcpTools.readOnly(panel -> store.document(panel) && store.file(panel).map(PanelTexts::isLog).orElse(false));
                // the user's grants to callers: who may run destructive actions (record 121)
                mcpTools.agentsFile(config.home().resolve("agents.json"));
                mcp = own(McpServer.start(mcpTools, 0, version));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot start the MCP server", e);
            }
            claude = own(new ClaudeAdapter(loop, transcripts, mcp,
                    new ClaudeSettings(config.claudeExecutable(), config.runDir(), root.projects(), UiInstructions.TEXT, launch)));
            claude.metrics(new MetricsLog(config.metricsDir()));
            ToolsAdapter devTools = own(new ToolsAdapter(loop, consoles, ToolSettings::now, DesktopTrash::moveToTrash));
            tools = devTools;
            EmbeddedQuestDb questDb = new EmbeddedQuestDb(config.questDbRoot(), loop);
            EmbeddedEdge edge = new EmbeddedEdge(config.edgeRoot(), config.home(), InstanceName.current(), loop);
            SessionCatalogue catalogue = new SessionCatalogue(root.projects());
            ui = new ComandoUi(new ComandoUi.Dependencies(
                    loop, loop::state, loop, transcripts, results, consoles, schemas, store,
                    () -> catalogue.scan(500).stream().map(e -> new StoredConversation(e.sessionId(), e.cwd(), e.branch(), e.title(),
                            e.lastPrompt(), e.modifiedMillis(), e.sizeBytes())).toList(),
                    questDb, edge, setup, version, config.home(), config.logDir(), onQuit));
            // the tabs' topics: one reader, one Haiku process at a time, whatever the number of panels
            ui.topics(own(new ClaudeTopics(config.claudeExecutable(), launch, config.runDir())));
            // the instances consult each other through the comando Edge while it runs, whichever instance started it
            // (record 123); this instance's presence is cleared, and its link closed, before the Edge stops
            if (Boolean.parseBoolean(System.getProperty(PEERS_PROPERTY, "true"))) {
                Peers.Me me = Peers.Me.of(InstanceName.current(), version);
                peerBroker = own(new MqttPeerBroker(me.clientId(), EmbeddedEdge.Ports.DEFAULT.mqtt(), edge::running));
                // a consult session is made where a new session is: the home folder
                peers = own(new Peers(peerBroker, me, loop, loop::state, transcripts, HomeDir.home(), config.home().resolve("agents.json")));
                mcpTools.peers(peers);
            } else {
                peerBroker = null;
                peers = null;
            }
            effects.target = new EffectRouter(db, claude, mcp, ui, store, devTools);
            // the Edge this instance started stops before QuestDB; one another instance started keeps running
            owned.addLast(edge);
            // the embedded QuestDB stops last: it may still serve the connections closed above
            owned.addLast(questDb);
            // the window closes last: it stays, greyed and titled "closing…", until everything behind it has closed
            owned.addLast(ui);
            LOG.log(System.Logger.Level.INFO, "comando " + version + " ready [home=" + config.home() + ", mcp=" + mcp.url()
                    + ", claude=" + config.claudeExecutable() + ", root=" + root.dir() + ", kit=" + (root.kit().isPresent() ? root.name() : "none") + ']');
        } catch (RuntimeException | Error e) {
            // roll back: close what was built, in reverse order, then report the cause
            close();
            throw e;
        }
    }

    /**
     * Starts the reducer and the window, the folders' and the plans' reads, the stored conversations' loads, the
     * version and kit checks, and the link to the other instances.
     */
    public void start() {
        loop.start();
        ui.start();
        // every folder's repository, for the Files tab's git marks; the reducer says when folders or Changes change
        tools.watchRepos(loop.state().folders(), false);
        // the Files tab's open folders stay in sync whether the tab shows or not; closed ones are not read
        tools.pollFolders(FOLDERS_EVERY);
        claude.watchPlanUsage(PLAN_USAGE_EVERY);
        List<SessionSpec> withHistory = loop.state().sessions().stream().map(SessionState::spec)
                .filter(s -> !s.claudeSessionId().isEmpty()).toList();
        for (SessionSpec s : withHistory) {
            claude.loadHistory(s);
        }
        String executable = claudeExecutable;
        errands.start("claude-version", () -> {
            String problem = ClaudeVersion.check(executable, launch);
            if (!problem.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "claude version check failed [problem=" + problem + ']');
                loop.dispatch(new Intent.Notify(Notice.Level.WARN, problem, false));
            }
        });
        if (root.kit().isPresent()) {
            kitCheck.scheduleWithFixedDelay(this::checkKit, 0, KIT_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (peers != null) {
            // the last will and the subscriptions are set before the first connect
            peers.start();
            peerBroker.start();
        }
    }

    /** Reads the kit's version, its remote's newer releases, and whether the brain can be backed up, for the window. */
    private void checkKit() {
        // an exception escaping a periodic task cancels every later run
        try {
            ui.kitStatus(setup.status());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the kit's version", e);
        }
    }

    public StateLoop loop() {
        return loop;
    }

    /** The sessions' transcripts; for tests. */
    TranscriptRegistry transcripts() {
        return transcripts;
    }

    /** The window; the snapshot tool drives it as a user would. */
    ComandoUi ui() {
        return ui;
    }

    /** A part closing this long or longer is named in the log. */
    static final long SLOW_CLOSE_MILLIS = 100;

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
        long start = System.nanoTime();
        while (!owned.isEmpty()) {
            AutoCloseable c = owned.pop();
            long partStart = System.nanoTime();
            try {
                c.close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "close failed [part=" + c.getClass().getSimpleName() + ']', e);
            }
            long millis = (System.nanoTime() - partStart) / 1_000_000;
            if (millis >= SLOW_CLOSE_MILLIS) {
                // a quit that takes long says which part held it
                LOG.log(System.Logger.Level.INFO, "closed slowly [part=" + c.getClass().getSimpleName() + ", millis=" + millis + ']');
            }
        }
        LOG.log(System.Logger.Level.INFO, "comando closed [millis=" + (System.nanoTime() - start) / 1_000_000 + ']');
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
