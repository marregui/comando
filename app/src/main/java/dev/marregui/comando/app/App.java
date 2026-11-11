package dev.marregui.comando.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import dev.marregui.comando.claude.ClaudeAdapter;
import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.claude.ClaudeTopics;
import dev.marregui.comando.claude.ClaudeVersion;
import dev.marregui.comando.claude.MetricsLog;
import dev.marregui.comando.claude.QuestDbEndpoint;
import dev.marregui.comando.claude.RootLaunch;
import dev.marregui.comando.claude.SessionCatalogue;
import dev.marregui.comando.claude.TranscriptSearch;
import dev.marregui.comando.claude.UiInstructions;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.effect.EffectRouter;
import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.db.DbAdapter;
import dev.marregui.comando.db.DbSettings;
import dev.marregui.comando.mcp.AuditLog;
import dev.marregui.comando.mcp.McpServer;
import dev.marregui.comando.mcp.McpTools;
import dev.marregui.comando.mcp.Peers;
import dev.marregui.comando.tools.FolderSearch;
import dev.marregui.comando.tools.KitGit;
import dev.marregui.comando.tools.ToolSettings;
import dev.marregui.comando.tools.ToolsAdapter;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.dialogs.sessions.StoredConversation;
import dev.marregui.comando.ui.kit.desktop.DesktopTrash;
import dev.marregui.comando.ui.kit.text.LocalImages;
import dev.marregui.comando.ui.kit.text.MermaidDiagrams;
import dev.marregui.comando.ui.shell.ComandoUi;
import dev.marregui.comando.ui.shell.Setup;

/**
 * The ownership tree. Builds every part in dependency order and closes them in reverse, each once: this instance's
 * presence among the other instances and its link to the comando Edge, the tool runs, Claude's processes, the MCP
 * server, the database lanes, the reducer, the intent log, the store (which writes the panels last), the Edge this
 * instance started, the embedded QuestDB, and last the window, which stays up, greyed, until the rest has closed;
 * quitting has already ended its shells and live plots.
 */
public final class App implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(App.class.getName());
    /**
     * How long a card waits for the user before it is denied, nothing of it kept: under the MCP server timeout Claude
     * Code is given (24 h 5 min), so comando answers first (Miguel, 2026-11-10: a card waits while its session waits).
     */
    static final Duration APPROVAL_TIMEOUT = Duration.ofHours(24);

    private final Deque<AutoCloseable> owned = new ArrayDeque<>();
    /** How often the Files tab's open folders are read for changes: often enough to follow a session writing. */
    static final Duration FOLDERS_EVERY = Duration.ofSeconds(5);
    /**
     * How often the status bar's plan usage is read; each read is one local {@code claude -p /usage}, about a second.
     */
    static final Duration PLAN_USAGE_EVERY = Duration.ofMinutes(20);
    /** How often the kit's version and its remote's releases are read, on a strand of their own. */
    static final Duration KIT_EVERY = Duration.ofHours(6);
    /** How often the unbounded queues are read for the metrics log; a reading is written only when it changed. */
    static final Duration QUEUES_EVERY = Duration.ofMinutes(1);
    /** {@code -Dcomando.peers=false}, set by window tests and picture tools, keeps this instance off the Edge. */
    static final String PEERS_PROPERTY = "comando.peers";
    /**
     * {@code -Dcomando.questdb=false}, set by every test and picture tool, keeps this instance off the machine's QuestDB:
     * no metrics are kept, and the toggle works on a folder in the data root, never on the machine's.
     */
    static final String QUESTDB_PROPERTY = "comando.questdb";
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
    /** Reads the kit's version at start and every {@link #KIT_EVERY}, and the queues every {@link #QUEUES_EVERY}. */
    private final ScheduledExecutorService kitCheck = Threads.timedStrand("kit-check");
    /** Where the queues' readings go: the sessions' metrics log. */
    private final MetricsLog metrics;
    /** The queues' last reading written; the kit-check strand's own. */
    private List<Queues.Reading> queuesWritten = List.of();
    private volatile boolean closed;

    public App(final AppConfig config, final String version, final Runnable onQuit) {
        claudeExecutable = config.claudeExecutable();
        root = config.root();
        // every claude this instance starts gets its kit and its data root (record 122)
        launch = RootLaunch.on(root, config.home());
        try {
            ownerOnlyFolders(config);
            // what reads HomeDir.claudeDir without being handed the root reads this one, until this closes
            final KitRoot before = KitRoot.current();
            KitRoot.use(root);
            // the New session dialog names the kit's defaults beside Model, Effort and Permission mode (record 125)
            SessionKind.INSTANCE.defaults(sessionDefaults(root.dir()));
            own(() -> KitRoot.use(before));
            final KitGit git = own(new KitGit());
            setup = new RootSetup(root, git);
            final Store store = own(new Store(config.home(), config.keychain() ? Secrets.forPlatform() : Secrets.NONE));
            // the start-up checks run as errands, closed just before the store
            errands = own(new Errands("app"));
            final Store.Loaded loaded = store.load();
            final AppState initial = AppState.initial(loaded.panels(),
                    loaded.connections().stream().map(ConnectionState::closed).toList(),
                    loaded.sessions().stream().map(SessionState::closed).toList(),
                    store.withKitFolders(store.loadFolders(root.dir()), root)).withClosed(loaded.closed());

            transcripts = new TranscriptRegistry();
            final ResultRegistry results = new ResultRegistry();
            final ConsoleRegistry consoles = new ConsoleRegistry();
            final SchemaRegistry schemas = new SchemaRegistry();
            final DeferredEffects effects = new DeferredEffects();
            // the checkpoint threshold (record 129): the window's setting, in percent of a session's context window, read
            // by the reducer at each turn's end, never below 10 % (Reflection.clamp)
            final AtomicInteger checkpointThreshold = new AtomicInteger(Reflection.DEFAULT_PERCENT);
            // the log is owned before the loop, so it closes after it and records the intents of the shutdown; it records
            // the threshold too, which the reducer reads, and each change of it
            final IntentLog log = config.recordIntents() ? own(new IntentLog(config.logDir(), initial, checkpointThreshold::get)) : null;
            loop = new StateLoop(initial, new Reducer(Clock.SYSTEM, appliedThreshold(log, checkpointThreshold::get)), effects);
            owned.push(loop);
            store.onNotice(text -> loop.dispatch(new Intent.Notify(Notice.Level.WARN, text, false)));
            store.onFoldersChanged(folders -> loop.dispatch(new Intent.FoldersRead(folders)));
            // what is wrong with the kit's manifest is told, never dropped silently (record 133): the file refused whole,
            // or each entry left out, with the palette's repair, which rewrites the file without them
            if (!root.refused().isEmpty()) {
                loop.dispatch(new Intent.Notify(Notice.Level.WARN, root.refused(), false));
            }
            for (final String problem : root.kit().map(KitRoot.Manifest::problems).orElse(List.of())) {
                loop.dispatch(new Intent.Notify(Notice.Level.WARN, KitRoot.FILE + ": " + problem + "; left out. Kit ▸ Repair "
                        + KitRoot.FILE + "… rewrites the file without it", false));
            }
            if (log != null) {
                loop.addObserver(log);
            }
            // the New session dialog shows the model Claude Code picks for a session that names none (record 125)
            loop.addObserver(intent -> {
                if (intent instanceof Intent.SessionInit i && !i.info().model().isBlank()
                        && loop.state().session(i.name()).map(x -> x.spec().model().isEmpty()).orElse(false)) {
                    SessionKind.INSTANCE.cliModel(i.info().model());
                }
            });

            final DbAdapter db = own(new DbAdapter(loop, results, schemas, DbSettings.DEFAULTS));
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
                mcpTools.results(results);
                mcpTools.readOnly(panel -> store.document(panel) && store.file(panel).map(PanelTexts::isLog).orElse(false));
                // the user's grants to callers: who may run destructive actions (record 121)
                mcpTools.agentsFile(config.home().resolve("agents.json"));
                mcp = own(McpServer.start(mcpTools, 0, version));
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot start the MCP server", e);
            }
            claude = own(new ClaudeAdapter(loop, transcripts, mcp,
                    new ClaudeSettings(config.claudeExecutable(), config.runDir(), root.projects(), UiInstructions.TEXT, launch)));
            // the machine's one QuestDB, shared by every instance and the kit, outliving comando (record 146)
            final boolean machineQuestDb = machineQuestDb();
            final MachineQuestDb questDb = new MachineQuestDb(machineQuestDb ? Path.of(System.getProperty("user.home"), ".comando-questdb")
                    : config.home().resolve("machine-questdb"), config.home(), InstanceName.current(), loop);
            metrics = own(metricsLog(machineQuestDb, config.home(), questDb, loop));
            claude.metrics(metrics);
            claude.checkpointByComando();
            final ToolsAdapter devTools = own(new ToolsAdapter(loop, consoles, ToolSettings::now, DesktopTrash::moveToTrash));
            tools = devTools;
            final EmbeddedEdge edge = new EmbeddedEdge(config.edgeRoot(), config.home(), InstanceName.current(), loop);
            // agents start and stop them as the toggles do (record 121)
            mcpTools.servers(List.of(edge, questDb));
            final SessionCatalogue catalogue = new SessionCatalogue(root.projects());
            ui = new ComandoUi(new ComandoUi.Dependencies(
                    loop, loop::state, loop, transcripts, results, consoles, schemas, store,
                    () -> catalogue.scan(500).stream().map(e -> new StoredConversation(e.sessionId(), e.cwd(), e.branch(), e.title(),
                            e.lastPrompt(), e.modifiedMillis(), e.sizeBytes())).toList(),
                    questDb, edge, setup, version, config.home(), config.logDir(), onQuit));
            // the tabs' topics: one reader, one Haiku process at a time, whatever the number of panels
            ui.topics(own(new ClaudeTopics(config.claudeExecutable(), launch, config.runDir())));
            // the search window's answers: its threads end with the application (record 139)
            ui.search(own(new Searcher(List.of(new FolderSearch(Searcher.SCANNERS, Searcher.MAX_HITS + 1),
                    new TranscriptSearch(root.projects())))));
            ui.checkpointThreshold(checkpointThreshold::set);
            // agents reach the panels' Terminals through the window, which refuses them until it is built (record 148)
            mcpTools.terminals(ui.terminals());
            // the instances consult each other through the comando Edge while it runs, whichever instance started it
            // (record 123); this instance's presence is cleared, and its link closed, before the Edge stops
            if (Boolean.parseBoolean(System.getProperty(PEERS_PROPERTY, "true"))) {
                final Peers.Me me = Peers.Me.of(InstanceName.current(), version, Main.roleFile(config.home()));
                peerBroker = own(new MqttPeerBroker(me.clientId(), EmbeddedEdge.Ports.DEFAULT.mqtt(), edge::running));
                // a consult session is made where a new session is: the home folder
                peers = own(new Peers(peerBroker, me, loop, loop::state, transcripts, HomeDir.home(), config.home().resolve("agents.json")));
                // the instances seen are remembered in the data root, and the presence says whether this one started the Edge (record 124)
                peers.dataRoot(config.home());
                peers.edge(edge::startedHere);
                mcpTools.peers(peers);
                // the sidebar's Instances tab asks and refuses through it (record 124)
                ui.instances(peers);
            } else {
                peerBroker = null;
                peers = null;
            }
            effects.target = new EffectRouter(db, claude, mcp, ui, store, devTools);
            // the kit check uses KitGit, Peers and the window: its strand stops before them
            own(() -> ExecutorStop.now("app", kitCheck));
            // the Edge this instance started stops before QuestDB; one another instance started keeps running
            owned.addLast(edge);
            // the machine's QuestDB is left running: its errands stop last, after the metrics log that writes to it
            owned.addLast(questDb);
            // the window closes last: it stays, greyed and titled "closing…", until everything behind it has closed
            owned.addLast(ui);
            LOG.log(System.Logger.Level.INFO, "comando ready [version=" + version + ", home=" + config.home()
                    + ", mcp=" + mcp.url() + ", claude=" + config.claudeExecutable() + ", root=" + root.dir()
                    + ", kit=" + (root.kit().isPresent() ? root.name() : "none") + ']');
        } catch (final RuntimeException | Error e) {
            // roll back: close what was built, in reverse order, then report the cause; the kit check's strand too, when
            // the failure came before it was owned
            try {
                close();
            } finally {
                ExecutorStop.now("app", kitCheck);
            }
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
        // the Logbook tab: the open tasks of the brain's newest scratchpad day folder, read on the folder poll
        // (record 150)
        root.brain().ifPresent(brain -> tools.watchLogbook(brain.resolve("SCRATCHPAD"),
                logbook -> loop.dispatch(new Intent.LogbookRead(logbook))));
        claude.watchPlanUsage(PLAN_USAGE_EVERY);
        final List<SessionSpec> withHistory = loop.state().sessions().stream().map(SessionState::spec)
                .filter(s -> !s.claudeSessionId().isEmpty()).toList();
        for (final SessionSpec s : withHistory) {
            claude.loadHistory(s);
        }
        final String executable = claudeExecutable;
        errands.start("claude-version", () -> {
            final String problem = ClaudeVersion.check(executable, launch);
            if (!problem.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "claude version check failed [problem=" + problem + ']');
                loop.dispatch(new Intent.Notify(Notice.Level.WARN, problem, false));
            }
        });
        if (root.kit().isPresent()) {
            kitCheck.scheduleWithFixedDelay(this::checkKit, 0, KIT_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        }
        kitCheck.scheduleWithFixedDelay(this::readQueues, QUEUES_EVERY.toMillis(), QUEUES_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        if (peers != null) {
            // the last will and the subscriptions are set before the first connect
            peers.start();
            peerBroker.start();
        }
    }

    /**
     * The checkpoint threshold the reducer applies each intent under: with an intent log, the value the log read and
     * wrote for that intent, so the window's setting changed between the log's line and the reducer's read cannot make
     * a replay apply the intent otherwise than the run did; without one, the setting as it is.
     */
    static IntSupplier appliedThreshold(final IntentLog log, final IntSupplier setting) {
        return log != null ? log.applied() : setting;
    }

    /**
     * What a session gets when it names no model, effort or permission mode: the root's {@code settings.json}
     * ({@code model}, {@code effortLevel}, {@code permissions.defaultMode}); none when it is missing or unreadable.
     */
    static SessionKind.Defaults sessionDefaults(final Path root) {
        final Path file = root.resolve("settings.json");
        if (!Files.isRegularFile(file)) {
            return SessionKind.Defaults.NONE;
        }
        try {
            final JsonNode s = Mapped.read(file, m -> JsonFile.JSON.readTree(m.stream()));
            return new SessionKind.Defaults(s.path("model").asString(""), s.path("effortLevel").asString(""),
                    s.path("permissions").path("defaultMode").asString(""));
        } catch (final IOException | JacksonException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the kit's settings [file=" + file + ", cause=" + e.getMessage() + ']');
            return SessionKind.Defaults.NONE;
        }
    }

    /**
     * Reads the unbounded queues' depths and peaks ({@link Queues}) and writes them to the metrics log when they differ
     * from the last reading written, so an idle window writes nothing; measured first, each queue is bounded later from
     * how it is used.
     */
    private void readQueues() {
        // an exception escaping a periodic task cancels every later run
        try {
            final List<Queues.Reading> now = Queues.read();
            if (!now.equals(queuesWritten)) {
                metrics.queues(now);
                queuesWritten = now;
            }
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the queues", e);
        }
    }

    /** Reads the kit's version, its remote's newer releases, and whether the brain can be backed up, for the window. */
    private void checkKit() {
        // an exception escaping a periodic task cancels every later run
        try {
            final Setup.Status status = setup.status();
            ui.kitStatus(status);
            if (peers != null) {
                // the kit's commit travels in this instance's presence
                peers.kit(status.version().described());
            }
        } catch (final RuntimeException e) {
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

    /** Whether this instance uses the machine's QuestDB: unless {@link #QUESTDB_PROPERTY} is false. */
    static boolean machineQuestDb() {
        return Boolean.parseBoolean(System.getProperty(QUESTDB_PROPERTY, "true"));
    }

    /**
     * The sessions' metrics log: with the machine's QuestDB, its only store, at {@code qdb}, each row naming
     * {@code home}, an outage told to {@code notices}; without it, as the tests run, {@link MetricsLog#NONE}, which
     * keeps nothing (record 149).
     */
    static MetricsLog metricsLog(
            final boolean machineQuestDb,
            final Path home,
            final QuestDbEndpoint qdb,
            final IntentSink notices
    ) {
        return machineQuestDb ? new MetricsLog(home.toAbsolutePath().toString(), qdb, notices) : MetricsLog.NONE;
    }

    /**
     * Creates the data root's logs, run and audit folders readable by the user alone, and narrows them when they exist
     * (rule 15): the audit log records every tool call with its arguments, the run folder holds the MCP configurations
     * with their bearer tokens, the logs name files and sessions. A folder that cannot be made so is logged; comando
     * still runs.
     */
    static void ownerOnlyFolders(final AppConfig config) {
        for (final Path dir : List.of(config.logDir(), config.runDir(), config.auditDir())) {
            try {
                OwnerOnly.folder(dir);
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot make the folder the user's alone [dir=" + dir + ']', e);
            }
        }
    }

    /** A part closing this long or longer is named in the log. */
    static final long SLOW_CLOSE_MILLIS = 100;

    private <T extends AutoCloseable> T own(final T resource) {
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
        final long start = System.nanoTime();
        try {
            // the comparison window's edits are saved before the parts behind the window close: on the shutdown hook's
            // path no quit flow ran, and the window itself closes last; a start that failed before the window was built
            // rolls back through here with no window
            if (ui != null) {
                ui.saveEdits();
            }
            closeAll(owned);
        } finally {
            LOG.log(System.Logger.Level.INFO, "comando closed [millis=" + (System.nanoTime() - start) / 1_000_000 + ']');
        }
    }

    /**
     * Closes and takes out every part of {@code owned}, the last pushed first. A part that throws is logged and the
     * rest still close: an {@link Exception} as a warning; an {@link Error} too, the first rethrown once every part has
     * closed, the later ones suppressed in it, so a failing part never leaves the processes and files of the parts after
     * it behind.
     */
    static void closeAll(final Deque<AutoCloseable> owned) {
        Error failed = null;
        while (!owned.isEmpty()) {
            final AutoCloseable c = owned.pop();
            final long partStart = System.nanoTime();
            try {
                c.close();
            } catch (final Exception e) {
                LOG.log(System.Logger.Level.WARNING, "close failed [part=" + c.getClass().getSimpleName() + ']', e);
            } catch (final Error e) {
                LOG.log(System.Logger.Level.ERROR, "close failed, the other parts still close [part=" + c.getClass().getSimpleName() + ']', e);
                if (failed == null) {
                    failed = e;
                } else {
                    failed.addSuppressed(e);
                }
            }
            final long millis = (System.nanoTime() - partStart) / 1_000_000;
            if (millis >= SLOW_CLOSE_MILLIS) {
                // a quit that takes long says which part held it
                LOG.log(System.Logger.Level.INFO, "closed slowly [part=" + c.getClass().getSimpleName() + ", millis=" + millis + ']');
            }
        }
        if (failed != null) {
            throw failed;
        }
    }

    /** The reducer needs its effect handler before the adapters that implement it exist. */
    static final class DeferredEffects implements StateLoop.EffectHandler {
        volatile StateLoop.EffectHandler target;

        @Override
        public void handle(final Effect effect) {
            final StateLoop.EffectHandler t = target;
            if (t != null) {
                t.handle(effect);
            }
        }
    }
}
