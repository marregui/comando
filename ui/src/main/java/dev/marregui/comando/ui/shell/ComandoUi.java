package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.TopicPort;
import dev.marregui.comando.core.port.UiPort;
import dev.marregui.comando.core.resource.ConnectionKind;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.dialogs.folders.FoldersDialog;
import dev.marregui.comando.ui.dialogs.resource.ResourceDialog;
import dev.marregui.comando.ui.dialogs.sessions.ImportSessionsDialog;
import dev.marregui.comando.ui.dialogs.sessions.StoredConversation;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.action.KeyPriority;
import dev.marregui.comando.ui.kit.async.Coalescer;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.EdtWatchdog;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.OpenedFiles;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;
import dev.marregui.comando.ui.panels.EditorPanel;
import dev.marregui.comando.ui.panels.LowerPanel;
import dev.marregui.comando.ui.panels.SessionBar;
import dev.marregui.comando.ui.panels.SessionsSidebar;
import dev.marregui.comando.ui.panels.SidePane;
import dev.marregui.comando.ui.panels.StatusBar;
import dev.marregui.comando.ui.panels.Workspace;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;
import dev.marregui.comando.ui.widget.capability.FindBar;
import dev.marregui.comando.ui.widgets.changes.ChangesWidget;
import dev.marregui.comando.ui.widgets.console.ConsoleWidget;
import dev.marregui.comando.ui.widgets.diff.DiffWidget;
import dev.marregui.comando.ui.widgets.markdown.MarkdownWidget;
import dev.marregui.comando.ui.widgets.plot.PlotWidget;
import dev.marregui.comando.ui.widgets.table.TableViewWidget;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;
import dev.marregui.comando.ui.widgets.text.TextWidget;
import dev.marregui.comando.ui.widgets.transcript.AgentWidget;
import dev.marregui.comando.ui.widgets.tree.TreeWidget;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Taskbar;
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * The Swing shell and the UI port. Builds the window from its parts and renders the state on the EDT, at most once
 * per pending change; the commands, the layout, the keyboard's routing, the panels' files and quitting are its
 * collaborators'. Owns the dialogs and desktop notifications.
 */
public final class ComandoUi implements UiPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ComandoUi.class.getName());
    /**
     * The modifier of comando's commands, ⌘ on macOS and Ctrl elsewhere; read per window, so a test may build one with
     * either desktop's keys ({@link Platform#KEYS_PROPERTY}).
     */
    private final int shortcut = Platform.menuShortcutMask();
    /**
     * The modifier of the lower tabs' keys: ⌃ on macOS; elsewhere Ctrl is the panels' (⌘1…⌘9), so Alt takes its place.
     */
    private final int lowerTabKey = Platform.macKeys() ? InputEvent.CTRL_DOWN_MASK : InputEvent.ALT_DOWN_MASK;
    /** How long closing waits for the event thread to close the window. */
    private static final Duration CLOSE_BOUND = Duration.ofSeconds(5);

    /** What the shell needs from the application. */
    public record Dependencies(
            IntentSink intents,
            Supplier<AppState> state,
            StateLoop loop,
            TranscriptRegistry transcripts,
            ResultRegistry results,
            ConsoleRegistry consoles,
            SchemaRegistry schemas,
            PanelTexts texts,
            Supplier<List<StoredConversation>> catalogue,
            ServerToggle server,
            ServerToggle edge,
            Setup setup,
            String version,
            Path home,
            Path logDir,
            Runnable onQuit) {
    }

    private final Dependencies deps;
    /** Reads the tabs' topics; the build hands it to the workspace. */
    private volatile TopicPort topics = TopicPort.NONE;
    private final HelpActions help;
    private final KitActions kit;
    /** The kit's scheduled jobs: the Files tab's Jobs row and the window that edits them. */
    private final JobActions jobs;
    private final ActionRegistry actions = new ActionRegistry();
    private final WidgetRegistry widgets = new WidgetRegistry();
    private final Coalescer render = new Coalescer(this::render);
    private final Coalescer transcriptRender = new Coalescer(this::renderTranscript);
    /**
     * What the window keeps, in {@code settings.json} in the data root; read once from the Java preferences node that kept
     * it before, which tests point elsewhere with {@code comando.prefsNode}.
     */
    private final WindowLayout windowLayout;
    /** The window's parts; null before the build. */
    private ShellParts parts;
    private LayoutKeeper layout;
    private FocusRouter focus;
    private PanelFiles files;
    private QuitFlow quitFlow;
    private Notifier notifier;
    private ResourceDialog<ConnectionSpec> connectionsDialog;
    private ResourceDialog<SessionSpec> sessionsDialog;
    private ImportSessionsDialog importDialog;
    /** The transcript the Session tab follows. */
    private Transcript watched;
    private final Runnable transcriptListener = transcriptRender::request;
    private EdtWatchdog watchdog;
    /** When the stack of the EDT's last hang was logged, read just before; the watchdog thread's own. */
    private long hangLoggedAt;
    private KeyPriority keyPriority;
    /** The watched git repositories as last read, for the Changes view. */
    private final RepoChanges repoChanges = new RepoChanges();
    /** The files opened in a panel or the side pane: a new file opened once is no longer marked in the Files tab. */
    private final OpenedFiles openedFiles;
    /** Refreshes what changes with time alone: elapsed times, the server state. */
    private Timer tickTimer;
    private final PropertyChangeListener focusListener = e -> actions.refresh();
    /** The requests waiting at the last render: one more is a reason to show the lower area. */
    private int renderedApprovals;
    private boolean tornDown;
    private final Consumer<String> schemaListener = connection -> render.request();
    private final StateLoop.Listener stateListener = (previous, next) -> render.request();

    public ComandoUi(Dependencies deps) {
        this.deps = deps;
        this.windowLayout = new WindowLayout(deps.home() == null ? Settings.inMemory() : Settings.open(deps.home().resolve("settings.json"),
                Preferences.userRoot().node(System.getProperty("comando.prefsNode", "dev/marregui/comando"))));
        this.openedFiles = deps.home() == null ? OpenedFiles.inMemory() : OpenedFiles.load(deps.home().resolve("opened-files"));
        this.help = new HelpActions(deps, this::frame, this::openFile, actions::run, () -> layout.revealSidePane(), actions::all);
        this.kit = new KitActions(deps, this::frame);
        this.jobs = new JobActions(deps, this::frame, this::openFile);
    }

    private JFrame frame() {
        return parts == null ? null : parts.frame();
    }

    /** Every command registered once {@link #start} has run, with its key on this desktop; the keyboard tests read them. */
    public List<Command> commands() {
        return actions.all();
    }

    /** About comando, over the window; the application menu's item and the palette open it. */
    public void showAbout() {
        help.showAbout();
    }

    /** Shows the kit's newer release on the Files tab's KIT row, and whether the brain and the kit can be backed up. Any thread. */
    public void kitStatus(Setup.Status status) {
        kit.show(status);
    }

    /** Builds and shows the window; call from any thread. */
    public void start() {
        Edt.later(this::build);
    }

    /** How the tabs' cards read each panel's topic; set before {@link #start()}. None by default. */
    public void topics(TopicPort port) {
        topics = port;
    }

    private void build() {
        Theme.install();
        WidgetContext ctx = new WidgetContext(deps.intents(), actions);
        widgets.register("table", ViewSpec.Table.class, c -> new TableViewWidget());
        widgets.register("plot", ViewSpec.Plot.class, c -> new PlotWidget());
        widgets.register("diff", ViewSpec.Diff.class, c -> new DiffWidget());
        widgets.register("markdown", ViewSpec.Markdown.class, c -> new MarkdownWidget());
        widgets.register("text", ViewSpec.Text.class, c -> new TextWidget());
        widgets.register("tree", ViewSpec.Tree.class, TreeWidget::new);
        widgets.register("console", ViewSpec.Console.class, c -> new ConsoleWidget(c, deps.consoles()));
        widgets.register("agent", ViewSpec.Agent.class, c -> new AgentWidget(c, deps.transcripts()));
        widgets.register("changes", ViewSpec.Changes.class, c -> new ChangesWidget(c, repoChanges, this::openFile, new ChangesWidget.Actions() {
            // the comparison window drops its edits to a file rolled back or removed, and closes
            @Override
            public void rollBack(String root, List<RepoStatus.FileChange> changes) {
                parts.diffWindow().drop(root, changes.stream().map(RepoStatus.FileChange::path).toList());
                deps.intents().dispatch(new Intent.RollBack(root, changes));
            }

            @Override
            public void remove(String root, List<String> paths) {
                parts.diffWindow().drop(root, paths);
                deps.intents().dispatch(new Intent.RemoveFiles(root, paths));
            }
        }, ResourceActions.Questions.MODAL));

        JFrame frame = new JFrame(InstanceName.display(InstanceName.current()));
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quitFlow.quit();
            }
        });
        // Quit from the Dock, the application menu or a logout: without a handler the JDK calls System.exit on
        // the AppKit thread, and closing then waits on an EDT that may need that thread. The handler runs the
        // same quit as closing the window; when the user cancels it the response cancels a pending logout too, and
        // when it goes ahead the response stays unanswered because the exit ends the process.
        // the application menu's About comando opens comando's own card, worded by about.md
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
            Desktop.getDesktop().setAboutHandler(event -> Edt.later(help::showAbout));
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler((event, response) -> Edt.later(() -> quitFlow.request(response)));
        }
        notifier = new Notifier(frame);
        DiffWindow diffWindow = new DiffWindow(frame, () -> {
            DiffWindow d = parts.diffWindow();
            if (d.change() != null) {
                deps.intents().dispatch(new Intent.OpenChange(d.root(), currentChange(d.root(), d.change()), ChangeAsk.REFRESH));
            }
        }, deps.intents(), deps.texts()::write, ResourceActions.Questions.MODAL);
        AppIcon.install(frame);
        // a named instance carries its name's first letter on its Dock icon, so the icons differ without hovering (record 122)
        String instance = InstanceName.current();
        if (!instance.isEmpty() && Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
            Taskbar.getTaskbar().setIconBadge(instance.substring(0, 1).toUpperCase(Locale.ROOT));
        }
        LivePlots livePlots = new LivePlots(deps.intents(), deps.results());
        UiErrands.start("pty-preload", TerminalWidget::preload);
        Workspace workspace = new Workspace(deps.intents(), deps.texts(), deps.schemas());
        workspace.onAttention(notifier::attention);
        workspace.onClose(this::closePanel);
        workspace.onOpenFile(this::openFile);
        workspace.outline(windowLayout.outlineShown(), windowLayout::outlineShown);
        // a link double-clicked anywhere opens its file as a link in a panel's page does: a panel, at its heading (record 94)
        Links.install((file, heading) -> {
            if (heading != null) {
                workspace.headingWhenOpen(file, heading);
            }
            openFile(file);
        }, text -> deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, text, false)),
                session -> deps.state().get().session(session).map(x -> Path.of(x.spec().workDir())).orElse(null));
        // a panel's "New session ‹title›…": the session dialog, named after the panel, which becomes its own (record 88)
        workspace.topics(topics, deps.transcripts()::of);
        deps.schemas().addListener(schemaListener);
        LowerPanel lower = new LowerPanel(ctx, deps.results(), deps.transcripts(), widgets);
        workspace.onThemeChosen(this::themeChosen);
        workspace.onRunsProgram(id -> lower.terminals().runningJob(id).isPresent());
        SidePane side = new SidePane(ctx, widgets);
        SessionsSidebar sidebar = new SessionsSidebar(deps.intents(), new SessionsSidebar.Dialogs() {
            @Override
            public ResourceActions.Editor sessions() {
                return editor(() -> sessionsDialog);
            }

            @Override
            public void importSessions() {
                openImport();
            }

            @Override
            public ResourceActions.Editor connections() {
                return editor(() -> connectionsDialog);
            }

            @Override
            public void exploreSchema(String connection) {
                ComandoUi.this.exploreSchema(connection);
            }

            @Override
            public void openFile(Path file) {
                ComandoUi.this.openFile(file);
            }

            @Override
            public void editFile(Path file) {
                workspace.editWhenOpen(file.toAbsolutePath().normalize());
                ComandoUi.this.openFile(file);
            }

            @Override
            public void newSession(String name) {
                sessionsDialog.showNew(deps.state().get(), name);
            }
        }, deps.texts(), () -> actions.run("view.sidebar"));
        MenuBuilder menus = new MenuBuilder(deps, actions);
        sidebar.onTabMenu(menus::tabMenu);
        // a session row shows the card a tab shows, the workspace's; a connection row a card of its facts
        sidebar.cards(workspace.rowCards());
        sidebar.watchOpened(openedFiles);
        StatusBar status = new StatusBar(deps.intents(), this::noticeTarget);
        SessionBar sessionBar = new SessionBar(deps.intents());
        sessionBar.onNewSession(name -> sessionsDialog.showNew(deps.state().get(), name));
        kit.attach(sidebar::kitRelease);
        // a panel's Locate button: its document in the Files tab, else its session in the Sessions tab (record 119)
        workspace.onLocate((file, session) -> {
            if (!sidebar.isShown()) {
                layout.toggleSidebar();
            }
            if (file != null) {
                sidebar.revealFile(file);
            } else {
                sidebar.revealSession(session);
            }
        });
        sidebar.onFolderMenu(menus::folderMenu);
        jobs.attach(sidebar);
        FindBar findBar = new FindBar();
        parts = new ShellParts(frame, workspace, lower, side, sidebar, status, sessionBar, findBar, diffWindow, livePlots);
        layout = new LayoutKeeper(deps, windowLayout, parts, this::render);
        focus = new FocusRouter(deps, parts, layout);
        files = new PanelFiles(deps, parts, focus, layout, windowLayout, openedFiles, render::request);
        quitFlow = new QuitFlow(deps, parts, layout, this::teardown);
        JPanel south = new JPanel(new BorderLayout());
        south.add(findBar, BorderLayout.NORTH);
        south.add(status.component(), BorderLayout.SOUTH);
        frame.getContentPane().add(layout.component(), BorderLayout.CENTER);
        frame.getContentPane().add(south, BorderLayout.SOUTH);

        connectionsDialog = new ResourceDialog<>(frame, new ResourceDialog.Config<>(
                ConnectionKind.INSTANCE,
                s -> s.connections().stream().map(ConnectionState::spec).toList(),
                s -> statuses(s.connections().stream().map(c -> Map.entry(c.name(), c.status())).toList()),
                Intent.SaveConnection::new,
                Intent.TestConnection::new,
                Intent.OpenConnection::new,
                Intent.CloseConnection::new,
                (s, name) -> new Intent.BindConnection(s.focused(), name)), deps.intents());
        sessionsDialog = new ResourceDialog<>(frame, new ResourceDialog.Config<>(
                SessionKind.INSTANCE,
                s -> s.sessions().stream().map(SessionState::spec).toList(),
                s -> statuses(s.sessions().stream().map(x -> Map.entry(x.name(), x.status())).toList()),
                Intent.SaveSession::new,
                null,
                Intent.StartSession::new,
                Intent.StopSession::new,
                (s, name) -> new Intent.OpenSession(name, s.focused(), PanelId.random())), deps.intents());
        importDialog = new ImportSessionsDialog(frame, deps.intents(), deps.catalogue());

        new Commands(this, deps, actions, parts, focus, layout, files, quitFlow, windowLayout).register(shortcut, lowerTabKey, help, kit, jobs);
        frame.setJMenuBar(menus.menuBar());
        workspace.corner(menus.cornerButtons());
        actions.bind(frame.getRootPane());
        actions.areas(Menus.AREAS);
        keyPriority = KeyPriority.install(actions, frame, TerminalWidget::keepsKey);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", focusListener);
        layout.watchKeys();
        layout.restore();
        deps.loop().addListener(stateListener);
        render();
        frame.setVisible(true);
        if (windowLayout.maximized()) {
            frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
        }
        layout.saveOnMove();
        tickTimer = new Timer(1_000, e -> tick());
        tickTimer.start();
        help.writeTutorial();
        EditorPanel p = workspace.focused();
        if (p != null) {
            p.editor().requestFocus();
        }
        // started last: a probe posted during the build waits for the rest of it and reports the build itself
        watchdog = new EdtWatchdog(this::hung, this::stalled);
    }

    /** The EDT reached its hang limit: its stack goes to the log, where the stall's notice will take the user. */
    private void hung(EdtWatchdog.Stall stall) {
        hangLoggedAt = System.currentTimeMillis();
        LOG.log(System.Logger.Level.WARNING, "the EDT has not answered for " + stall.millis() + " ms " + label(stall) + ":\n" + stall.stack());
    }

    /**
     * A stall of the EDT ended after {@code millis}: a short one is a line with its kind and where the EDT was, a
     * hang a warning after the stack logged at its limit; the user hears of one that lasted a second or more, and a
     * click on the notice opens the log at the line that says where: the hang's stack, or the short one's line.
     */
    private void stalled(EdtWatchdog.Stall stall, long millis) {
        long where;
        if (stall.reported()) {
            // the watchdog reported this stall just before, on this thread
            where = hangLoggedAt;
            LOG.log(System.Logger.Level.WARNING, "the EDT answered again after " + millis + " ms");
        } else {
            where = System.currentTimeMillis();
            LOG.log(System.Logger.Level.INFO, "the EDT was busy for " + millis + " ms " + label(stall));
        }
        if (millis >= 1_000) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The window stopped responding for "
                    + Labels.elapsed(millis) + "; click to see where in the log", false, Notice.log(where)));
        }
    }

    /**
     * Where a click on a notice goes: the log's entry it names, the panel it is about; empty for a notice about
     * nothing to bring into view.
     */
    private Optional<Runnable> noticeTarget(Notice n) {
        return switch (n.kind()) {
            case "log" -> deps.logDir() == null ? Optional.empty() : millis(n.key()).map(at -> () -> showLogEntry(at));
            case "panel" -> deps.state().get().panel(new PanelId(n.key()))
                    .map(p -> () -> deps.intents().dispatch(new Intent.FocusPanel(p.id())));
            default -> Optional.empty();
        };
    }

    private static Optional<Long> millis(String key) {
        try {
            return Optional.of(Long.parseLong(key));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Opens the log that holds the entry written at {@code atMillis}, scrolled to it, its line flashed; the log as Actions
     * ▸ Show the log opens it, with a notice, when the logs kept no longer hold it.
     */
    private void showLogEntry(long atMillis) {
        Path dir = deps.logDir();
        UiErrands.start("log-entry", () -> {
            Optional<Path> log = HelpFiles.logHolding(dir, atMillis);
            Edt.later(() -> {
                if (log.isPresent()) {
                    parts.workspace().entryWhenOpen(log.get().toAbsolutePath().normalize(), atMillis);
                    openFile(log.get());
                } else {
                    help.showLog();
                    deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "The logs comando keeps no longer hold the entry of "
                            + LocalTime.ofInstant(Instant.ofEpochMilli(atMillis), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS), false));
                }
            });
        });
    }

    /** {@code [kind] in=… comando=…}, the part of a stall's line that groups stalls of one cause. */
    private static String label(EdtWatchdog.Stall stall) {
        return stall.where().isEmpty() ? "[" + stall.kind() + "]" : "[" + stall.kind() + "] " + stall.where();
    }

    /** Once a second: elapsed times on the session bar, the panels' tabs and the results, uptimes on the session rows. */
    private void tick() {
        parts.sessionBar().tick();
        parts.workspace().tick();
        parts.lower().results().tick();
    }

    private static Map<String, Status> statuses(List<Map.Entry<String, Status>> entries) {
        Map<String, Status> m = new LinkedHashMap<>();
        for (var e : entries) {
            m.put(e.getKey(), e.getValue());
        }
        return m;
    }

    // ---- rendering

    private void render() {
        AppState s = deps.state().get();
        // before the panes change: what just opened remembers where the keyboard was, what just closed gives it back
        focus.beforeRender(s);
        layout.lowerFor(s, !s.lowerTab().equals(focus.renderedLowerTab()) || s.approvals().size() > renderedApprovals);
        renderedApprovals = s.approvals().size();
        parts.livePlots().onState(s);
        parts.sidebar().render(s);
        parts.workspace().render(s);
        parts.lower().render(s);
        layout.sidePane(s, parts.side().render(s));
        parts.status().render(s);
        parts.sessionBar().render(s);
        kit.render(s);
        connectionsDialog.render(s);
        sessionsDialog.render(s);
        watchTranscript(s.focusedPanel().session());
        focus.afterRender(s);
        if (parts.findBar().isOpen() && !parts.findBar().targetShowing()) {
            parts.findBar().close(); // the view it searched was closed
        }
        actions.refresh();
        if (!quitFlow.quitting()) {
            // a render already asked for when quit began must not take back the closing title
            parts.frame().setTitle(InstanceName.display(InstanceName.current()));
        }
    }

    /** Follows the transcript the Session tab shows, re-rendering it as the session writes. */
    private void watchTranscript(String session) {
        Transcript t = session.isEmpty() ? null : deps.transcripts().of(session);
        if (t != watched) {
            if (watched != null) {
                watched.removeListener(transcriptListener);
            }
            watched = t;
            if (t != null) {
                t.addListener(transcriptListener);
            }
        }
    }

    private void renderTranscript() {
        parts.lower().renderTranscript(parts.lower().transcriptSession());
    }

    // ---- UiPort

    @Override
    public void editPanel(PanelId panel, String text, EditMode mode) {
        Edt.run(() -> {
            EditorPanel p = parts == null ? null : parts.workspace().panel(panel);
            if (p == null) {
                return;
            }
            if (!p.editable()) {
                files.refuseReadOnly();
                return;
            }
            switch (mode) {
                case REPLACE -> p.editor().replaceAll(text);
                case INSERT -> p.editor().insertAtCaret(text);
                case APPEND -> p.editor().appendQuietly(text);
                case CARET -> {
                    p.editor().area().replaceSelection(text);
                    p.editor().requestFocus();
                }
            }
        });
    }

    @Override
    public void notifyDesktop(String title, String text) {
        Edt.run(() -> {
            if (notifier != null) {
                notifier.notify(KeyText.of(title), KeyText.of(text));
            }
        });
    }

    @Override
    public void forgetPanel(PanelId panel) {
        deps.texts().forget(panel);
        forgetTerminal(panel);
    }

    @Override
    public void deletePanel(PanelId panel) {
        deps.texts().delete(panel);
        forgetTerminal(panel);
    }

    private void forgetTerminal(PanelId panel) {
        Edt.run(() -> {
            if (parts != null) {
                parts.lower().terminals().forget(panel);
            }
        });
    }

    /** Opens the file in a panel, or shows it in the side pane: a binary or large one, and one at a line (record 111). */
    @Override
    public void showFile(String session, String path, String baseDir, int line) {
        Edt.run(() -> files.showFile(session, path, baseDir, line));
    }

    @Override
    public void arrange(Layout layout) {
        Edt.run(() -> {
            if (this.layout != null) {
                this.layout.arrangeWhenIdle(layout);
            }
        });
    }

    @Override
    public void showFolder(FolderRoot folder, ViewSpec.TreeNode tree) {
        Edt.run(() -> parts.sidebar().showFolder(folder, tree));
    }

    @Override
    public void showRepos(List<RepoStatus> repos) {
        Edt.run(() -> {
            repoChanges.set(repos);
            if (parts != null) {
                parts.sidebar().showRepos(repos);
            }
        });
    }

    /** A change read for the comparison window; one that follows the selection, or is read again, shows only in a window already open. */
    @Override
    public void showChange(ChangeTexts texts, ChangeAsk ask) {
        Edt.run(() -> {
            if (parts == null || ask != ChangeAsk.OPEN && !parts.diffWindow().isShowing()) {
                return;
            }
            parts.diffWindow().show(texts, ask);
        });
    }

    @Override
    public void showPlanUsage(PlanUsage usage) {
        Edt.run(() -> parts.status().planUsage(usage));
    }

    /**
     * {@code shown}'s path as the repositories were last read: staged, committed or removed since it was opened, the
     * file is read again as it now differs; a file that no longer differs is read as changed, and shows no differences.
     */
    private RepoStatus.FileChange currentChange(String root, RepoStatus.FileChange shown) {
        for (RepoStatus r : repoChanges.repos()) {
            if (r.root().equals(root)) {
                for (RepoStatus.FileChange c : r.changes()) {
                    if (c.path().equals(shown.path())) {
                        return c;
                    }
                }
                // a deletion committed stays a file that is not there
                return new RepoStatus.FileChange(shown.path(), "",
                        shown.kind() == RepoStatus.Kind.DELETED ? RepoStatus.Kind.DELETED : RepoStatus.Kind.MODIFIED);
            }
        }
        return shown;
    }

    // ---- what the commands open

    /**
     * A theme was chosen, from the swatch beside New panel or the palette: kept, and every window takes its ground and
     * its tint at once (record 103).
     */
    void themeChosen(NamedTheme theme) {
        windowLayout.theme(theme);
        Theme.ground(theme.ground);
        LayoutKeeper.paintIn(theme.tint);
        // the colours set from the state (the Approvals tab's title, the status bar's approvals) are set again
        render();
    }

    /** The Files tab's folders in their window; saving replaces them. */
    void editFolders() {
        FoldersDialog.show(parts.frame(), deps.state().get().folders(), repoChanges, deps.intents(), deps.setup().root().folders());
    }

    /** The user asked for the explorer: it shows, even in a side pane the user hid earlier. */
    void exploreSchema(String connection) {
        layout.revealSidePane();
        deps.intents().dispatch(new Intent.ExploreSchema(connection));
    }

    void openConnections(String select) {
        connectionsDialog.show(deps.state().get(), select);
    }

    void openSessions(String select) {
        sessionsDialog.show(deps.state().get(), select);
    }

    void openImport() {
        importDialog.show(deps.state().get());
    }

    /**
     * What the sidebar's add, edit and clone buttons open in the dialog; read at the click, as the sidebar is built
     * before the dialogs.
     */
    private ResourceActions.Editor editor(Supplier<ResourceDialog<?>> dialog) {
        return new ResourceActions.Editor() {
            @Override
            public void add() {
                dialog.get().showNew(deps.state().get());
            }

            @Override
            public void edit(String name) {
                dialog.get().show(deps.state().get(), name);
            }

            @Override
            public void cloneOf(String name) {
                dialog.get().showClone(deps.state().get(), name);
            }
        };
    }

    /**
     * Opens a file in a new panel bound to it, or brings forward the panel that has it; a binary or large file goes to
     * the side pane. Called on the EDT.
     */
    public void openFile(Path chosen) {
        files.openFile(chosen);
    }

    private void closePanel(PanelId id) {
        files.closePanel(id);
    }

    /** Whether {@code p} is {@code dir} or inside it, links followed: {@code ~/.claude/MEMORY} may be a link into the brain. */
    static boolean inside(Path p, Path dir) {
        return MenuBuilder.inside(p, dir);
    }

    /** "Quitting stops main's turn.", or what it stops one per line when several things run. */
    static String quitQuestion(List<String> work) {
        return QuitFlow.quitQuestion(work);
    }

    // ---- closing

    /** Ends the shells and live plots and closes the window, without quitting the application. */
    @Override
    public void close() {
        // bounded: an event thread stuck at quit must not keep the process, and comando's lock, alive forever
        boolean closed = Edt.runAndWait(() -> {
            if (parts == null) {
                return;
            }
            teardown();
            jobs.stop();
            parts.diffWindow().dispose();
            parts.frame().dispose();
        }, CLOSE_BOUND);
        if (!closed) {
            LOG.log(System.Logger.Level.WARNING, "cannot close the window, the event thread is busy; quitting without it [seconds="
                    + CLOSE_BOUND.toSeconds() + ']');
        }
        // the gestures' errands still running: interrupted and waited for, bounded
        UiErrands.closeAll();
    }

    /**
     * Stops what the window runs: its timers and listeners, the shells, the live plots and the watchdog; once, at quit
     * or at close. No state renders after it, so the closing window keeps its title.
     */
    private void teardown() {
        if (tornDown) {
            return;
        }
        tornDown = true;
        parts.diffWindow().saveNow(); // the comparison's edits reach their file before the store closes
        tickTimer.stop();
        layout.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", focusListener);
        parts.lower().terminals().closeAll();
        parts.livePlots().stopAll();
        if (watchdog != null) {
            watchdog.close();
        }
        keyPriority.close();
        deps.loop().removeListener(stateListener);
        deps.schemas().removeListener(schemaListener);
        if (watched != null) {
            watched.removeListener(transcriptListener);
            watched = null;
        }
    }
}
