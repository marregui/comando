package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
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
import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.FileView;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.dialogs.folders.FoldersDialog;
import dev.marregui.comando.ui.dialogs.resource.ResourceDialog;
import dev.marregui.comando.ui.dialogs.sessions.ImportSessionsDialog;
import dev.marregui.comando.ui.dialogs.sessions.StoredConversation;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.action.CommandPalette;
import dev.marregui.comando.ui.kit.action.KeyPriority;
import dev.marregui.comando.ui.kit.async.Coalescer;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.EdtWatchdog;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.kit.desktop.FileSearch;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.OpenedFiles;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;
import dev.marregui.comando.ui.panels.CommandButton;
import dev.marregui.comando.ui.panels.EditorPanel;
import dev.marregui.comando.ui.panels.LowerPanel;
import dev.marregui.comando.ui.panels.SessionsSidebar;
import dev.marregui.comando.ui.panels.SessionBar;
import dev.marregui.comando.ui.panels.SidePane;
import dev.marregui.comando.ui.panels.StatusBar;
import dev.marregui.comando.ui.panels.TerminalTabs;
import dev.marregui.comando.ui.panels.Workspace;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Exportable;
import dev.marregui.comando.ui.widget.capability.FindBar;
import dev.marregui.comando.ui.widget.capability.Searchable;
import dev.marregui.comando.ui.widget.capability.Zoomable;
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

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.desktop.QuitResponse;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * The Swing shell and the UI port. Renders the state on the EDT, at most once per pending change; builds
 * the menus, key bindings and palette from one action registry; owns the editor text, the dialogs and
 * desktop notifications.
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
    private final ActionRegistry actions = new ActionRegistry();
    private final WidgetRegistry widgets = new WidgetRegistry();
    private final Coalescer render = new Coalescer(this::render);
    private final Coalescer transcriptRender = new Coalescer(this::renderTranscript);
    /**
     * What the window keeps, in {@code settings.json} in the data root; read once from the Java preferences node that kept
     * it before, which tests point elsewhere with {@code comando.prefsNode}.
     */
    private final WindowLayout windowLayout;
    private JFrame frame;
    private Workspace workspace;
    private LowerPanel lower;
    private SidePane side;
    private SessionsSidebar sidebar;
    private StatusBar status;
    /** The focused panel's session, under the lower panel. */
    private SessionBar sessionBar;
    private FindBar findBar;
    private JSplitPane outer;
    private int outerDividerSize;
    private JSplitPane vertical;
    /** Each panel's lower divider, as last set while it was in front; brought back when it comes to the front. EDT only. */
    private final Map<PanelId, Integer> panelDividers = new HashMap<>();
    /** The panel whose divider the window shows; null before the first render. */
    private PanelId dividerPanel;
    private JSplitPane editorAndSide;
    private JPanel top;
    private Notifier notifier;
    private ResourceDialog<ConnectionSpec> connectionsDialog;
    private ResourceDialog<SessionSpec> sessionsDialog;
    private ImportSessionsDialog importDialog;
    /** The About card while it is open. */
    private Transcript watched;
    private final Runnable transcriptListener = transcriptRender::request;
    private boolean sidePaneShown;
    private LivePlots livePlots;
    private EdtWatchdog watchdog;
    /** When the stack of the EDT's last hang was logged, read just before; the watchdog thread's own. */
    private long hangLoggedAt;
    private KeyPriority keyPriority;
    /** The user or Claude hid the side pane; a change in the side views shows it again. */
    private boolean sidePaneHidden;
    /** The watched git repositories as last read, for the Changes view. */
    private final RepoChanges repoChanges = new RepoChanges();
    /** The files opened in a panel or the side pane: a new file opened once is no longer marked in the Files tab. */
    private final OpenedFiles openedFiles;
    /** A changed file compared with HEAD, floating over the window. */
    private DiffWindow diffWindow;
    /** The side views at the last render, by id, with their versions. */
    private Map<Long, Long> sideVersions = Map.of();
    /** Refreshes what changes with time alone: elapsed times, the server state. */
    private Timer tickTimer;
    /** Saves the window layout a second after the last resize or move. */
    private Timer layoutSaver;
    /** When the user last pressed a key in the window; a session's layout change waits until typing pauses. */
    private long lastKeyNanos;
    private final AWTEventListener keyClock = e -> {
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getSource() instanceof Component c && SwingUtilities.getWindowAncestor(c) == frame) {
            lastKeyNanos = System.nanoTime();
        }
    };
    private final PropertyChangeListener focusListener = e -> actions.refresh();
    /** The panes as they were before a session last arranged them; Restore layout puts them back. */
    private LayoutSnapshot beforeArrange;
    /** What gives the keyboard away once the lower tab holding {@code shown} shows, after the render that selects it. */
    private record FocusAfterRender(Component shown, Runnable focus) {
    }

    private FocusAfterRender focusAfterRender;
    /** Where the keyboard was when each panel and view opened, to give it back when it closes (record 112). */
    private final FocusOrigins focusOrigins = new FocusOrigins();
    /** The panel in front at the last render. */
    private String renderedFront;
    /** The component the keyboard goes back to once it shows, and how many renders it may wait; null when none. */
    private Component focusBack;
    private int focusBackRenders;
    /** The lower tab the last render showed. */
    private LowerTab renderedLowerTab = LowerTab.SESSION;
    static final long TYPING_PAUSE_MILLIS = 2_000;

    private record LayoutSnapshot(boolean sidebar, boolean sidePaneHidden, int lowerDivider) {
    }

    public ComandoUi(Dependencies deps) {
        this.deps = deps;
        this.windowLayout = new WindowLayout(deps.home() == null ? Settings.inMemory() : Settings.open(deps.home().resolve("settings.json"),
                Preferences.userRoot().node(System.getProperty("comando.prefsNode", "dev/marregui/comando"))));
        this.openedFiles = deps.home() == null ? OpenedFiles.inMemory() : OpenedFiles.load(deps.home().resolve("opened-files"));
        this.help = new HelpActions(deps, () -> frame, this::openFile, actions::run, () -> sidePaneHidden = false, actions::all);
        this.kit = new KitActions(deps, () -> frame);
    }

    /** Every command registered once {@link #start} has run, with its key on this desktop; the keyboard tests read them. */
    public List<Command> commands() {
        return actions.all();
    }

    /** About comando, over the window; the application menu's item and the palette open it. */
    public void showAbout() {
        help.showAbout();
    }

    /** Shows the kit's version and newer releases in the status bar, and whether the brain can be backed up. Any thread. */
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
                diffWindow.drop(root, changes.stream().map(RepoStatus.FileChange::path).toList());
                deps.intents().dispatch(new Intent.RollBack(root, changes));
            }

            @Override
            public void remove(String root, List<String> paths) {
                diffWindow.drop(root, paths);
                deps.intents().dispatch(new Intent.RemoveFiles(root, paths));
            }
        }, ResourceActions.Questions.MODAL));

        frame = new JFrame("comando");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
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
            Desktop.getDesktop().setQuitHandler((event, response) -> Edt.later(() -> {
                // a trigger that arrives while the question is open is answered with it
                pendingQuits.add(response);
                if (!confirmingQuit && !quit()) {
                    pendingQuits.forEach(QuitResponse::cancelQuit);
                    pendingQuits.clear();
                }
            }));
        }
        notifier = new Notifier(frame);
        diffWindow = new DiffWindow(frame, () -> {
            if (diffWindow.change() != null) {
                deps.intents().dispatch(new Intent.OpenChange(diffWindow.root(), currentChange(diffWindow.root(), diffWindow.change()),
                        ChangeAsk.REFRESH));
            }
        }, deps.intents(), deps.texts()::write, ResourceActions.Questions.MODAL);
        AppIcon.install(frame);
        livePlots = new LivePlots(deps.intents(), deps.results());
        UiErrands.start("pty-preload", TerminalWidget::preload);
        workspace = new Workspace(deps.intents(), deps.texts(), deps.schemas());
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
        lower = new LowerPanel(ctx, deps.results(), deps.transcripts(), widgets);
        workspace.onThemeChosen(this::themeChosen);
        workspace.onRunsProgram(id -> lower.terminals().runningJob(id).isPresent());
        side = new SidePane(ctx, widgets);
        sidebar = new SessionsSidebar(deps.intents(), new SessionsSidebar.Dialogs() {
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
        sidebar.onTabMenu(this::tabMenu);
        // a session row shows the card a tab shows, the workspace's; a connection row a card of its facts
        sidebar.cards(workspace.rowCards());
        sidebar.watchOpened(openedFiles);
        status = new StatusBar(deps.intents(), this::noticeTarget);
        sessionBar = new SessionBar(deps.intents());
        sessionBar.onNewSession(name -> sessionsDialog.showNew(deps.state().get(), name));
        status.onKitMenu(this::kitMenu);
        kit.attach(status);
        sidebar.onFolderMenu(this::folderMenu);
        findBar = new FindBar();
        top = new JPanel(new BorderLayout());
        top.add(workspace.component(), BorderLayout.CENTER);
        editorAndSide = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        editorAndSide.setResizeWeight(0.55);
        editorAndSide.setBorder(null);
        vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, lower.component());
        vertical.setResizeWeight(0.45);
        vertical.setBorder(null);
        // a move of the divider, by the user, an arrangement or the window's resize, is the panel in front's
        vertical.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (dividerPanel != null && vertical.getDividerLocation() > 0) {
                panelDividers.put(dividerPanel, vertical.getDividerLocation());
            }
        });
        // the session bar closes the column of the panels and the lower panel; the window's status bar runs under all
        JPanel column = new JPanel(new BorderLayout());
        column.add(vertical, BorderLayout.CENTER);
        column.add(sessionBar.component(), BorderLayout.SOUTH);
        outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar.component(), column);
        outer.setBorder(null);
        outerDividerSize = outer.getDividerSize();
        JPanel south = new JPanel(new BorderLayout());
        south.add(findBar, BorderLayout.NORTH);
        south.add(status.component(), BorderLayout.SOUTH);
        frame.getContentPane().add(outer, BorderLayout.CENTER);
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

        registerCommands();
        frame.setJMenuBar(menuBar());
        workspace.corner(cornerButtons());
        actions.bind(frame.getRootPane());
        actions.areas(Menus.AREAS);
        keyPriority = KeyPriority.install(actions, frame, TerminalWidget::keepsKey);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", focusListener);
        Toolkit.getDefaultToolkit().addAWTEventListener(keyClock, AWTEvent.KEY_EVENT_MASK);
        restoreLayout();
        deps.loop().addListener(stateListener);
        render();
        frame.setVisible(true);
        if (windowLayout.maximized()) {
            frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
        }
        layoutSaver = new Timer(1_000, e -> saveLayoutQuietly());
        layoutSaver.setRepeats(false);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutSaver.restart();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                layoutSaver.restart();
            }
        });
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
                    workspace.entryWhenOpen(log.get().toAbsolutePath().normalize(), atMillis);
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
        sessionBar.tick();
        workspace.tick();
        lower.results().tick();
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
        Component keyboard = focusOwnerInFrame();
        Set<String> openNow = openKeys(s);
        FocusOrigins.Origin back = focusOrigins.onRender(openNow, originAt(keyboard, renderedFront), placeOf(s, keyboard),
                o -> o.owner().isDisplayable() && (o.ownerKey() == null ? !inLower(o.owner()) || o.front() == null || openNow.contains(o.front())
                        : openNow.contains(o.ownerKey())));
        renderedFront = "panel:" + s.focused().value();
        lowerDividerOf(s.focused());
        livePlots.onState(s);
        sidebar.render(s);
        workspace.render(s);
        lower.render(s);
        boolean hasSide = side.render(s);
        // a hidden side pane comes back for a new side view of the user's or of the focused panel's session; another
        // session's view, an update of a view, or only a change of focus leaves it hidden; the user's own commands that
        // show a side view reveal the pane themselves
        String mine = s.focusedPanel().session();
        Map<Long, Long> versions = new HashMap<>();
        for (var v : s.views()) {
            if (v.placement() == Placement.SIDE) {
                versions.put(v.id(), v.version());
                Long before = sideVersions.get(v.id());
                // a new view only: an update of a view the user already has (a schema reloaded after a DDL) leaves it hidden
                if (before == null && (v.session().isEmpty() || v.session().equals(mine))) {
                    sidePaneHidden = false;
                }
            }
        }
        sideVersions = versions;
        boolean show = hasSide && !sidePaneHidden;
        if (show != sidePaneShown) {
            showSidePane(show);
        }
        status.render(s);
        sessionBar.render(s);
        kit.render(s);
        connectionsDialog.render(s);
        sessionsDialog.render(s);
        watchTranscript(s.focusedPanel().session());
        // the last card answered: the Session tab came forward, and the keyboard the inbox held goes with it
        Component owner = focusOwnerInFrame();
        if (renderedLowerTab.equals(LowerTab.APPROVALS) && s.lowerTab().equals(LowerTab.SESSION)
                && owner != null && SwingUtilities.isDescendingFrom(owner, lower.inbox().component())) {
            Component canvas = lower.transcript().canvas();
            focusAfterRender = new FocusAfterRender(canvas, canvas::requestFocusInWindow);
        }
        renderedLowerTab = s.lowerTab();
        if (back != null) {
            giveKeyboardBack(back, s);
        }
        if (focusBack != null) {
            if (focusBack.isShowing()) {
                Component c = focusBack;
                focusBack = null;
                Edt.later(c::requestFocusInWindow);
            } else if (--focusBackRenders <= 0) {
                focusBack = null; // its tab was not brought forward: the keyboard does not jump there later
            }
        }
        if (focusAfterRender != null && focusAfterRender.shown().isShowing()) {
            Runnable focus = focusAfterRender.focus();
            focusAfterRender = null;
            Edt.later(focus);
        }
        if (findBar.isOpen() && !findBar.targetShowing()) {
            findBar.close(); // the view it searched was closed
        }
        actions.refresh();
        if (!quitting) {
            // a render already asked for when quit began must not take back the closing title
            frame.setTitle("comando — " + s.focusedPanel().title()
                    + (s.focusedPanel().session().isEmpty() ? "" : " · " + s.focusedPanel().session()));
        }
    }

    /**
     * Where an opening made now is opened from (record 112): where the keyboard is; but a keyboard left in a panel that
     * is not in front, or nowhere, counts as the panel in front, whose text or page takes it back.
     */
    private FocusOrigins.Origin originNow() {
        return originAt(focusOwnerInFrame(), deps.state().get() == null ? null : "panel:" + deps.state().get().focused().value());
    }

    /**
     * Where the keyboard at {@code c} is, with {@code front} the panel in front: a keyboard left in a panel that is not
     * in front, or nowhere, counts as that panel, whose text or page takes it back.
     */
    private FocusOrigins.Origin originAt(Component c, String front) {
        String key = keyOf(c);
        if (c == null || key != null && key.startsWith("panel:") && !key.equals(front)) {
            EditorPanel p = front == null || workspace == null ? null : workspace.panel(new PanelId(front.substring("panel:".length())));
            if (p == null) {
                return null;
            }
            c = p.focusTarget();
            key = front;
        }
        return new FocusOrigins.Origin(c, key, renderedLowerTab, front);
    }

    /** The place the user is at (record 112): a view the keyboard is in, else the panel in front. */
    private String placeOf(AppState s, Component keyboard) {
        String key = keyOf(keyboard);
        return key != null && key.startsWith("view:") ? key : "panel:" + s.focused().value();
    }

    private boolean inLower(Component c) {
        return SwingUtilities.isDescendingFrom(c, lower.component());
    }

    /** The panels and views open, as {@link FocusOrigins} keys them. */
    private static Set<String> openKeys(AppState s) {
        Set<String> keys = new HashSet<>();
        s.panels().forEach(p -> keys.add("panel:" + p.id().value()));
        s.views().forEach(v -> keys.add("view:" + v.id()));
        return keys;
    }

    /** The panel or view that holds {@code c}, as {@link FocusOrigins} keys it; null outside both. */
    private String keyOf(Component c) {
        if (c == null) {
            return null;
        }
        if (SwingUtilities.getAncestorOfClass(EditorPanel.class, c) instanceof EditorPanel p) {
            return "panel:" + p.panel().id().value();
        }
        long view = lower.viewHolding(c);
        if (view < 0) {
            view = side.viewHolding(c);
        }
        return view < 0 ? null : "view:" + view;
    }

    /**
     * Gives the keyboard back to where it was when the panel or view that held it opened (record 112): the panel the
     * keyboard was in, else the panel in front then, whose session the lower tabs (Session, Approvals, Terminal,
     * Results) showed, comes forward; then that lower tab or view; and the component takes the keyboard once it shows.
     */
    private void giveKeyboardBack(FocusOrigins.Origin back, AppState s) {
        String key = back.ownerKey();
        String panel = key != null && key.startsWith("panel:") ? key : back.front();
        if (panel != null && panel.startsWith("panel:")) {
            PanelId id = new PanelId(panel.substring("panel:".length()));
            if (!s.focused().equals(id) && s.panel(id).isPresent()) {
                deps.intents().dispatch(new Intent.FocusPanel(id));
            }
        }
        if (key != null && key.startsWith("view:")) {
            long id = Long.parseLong(key.substring("view:".length()));
            boolean lowerView = s.views().stream().anyMatch(v -> v.id() == id && v.placement() == Placement.LOWER);
            if (lowerView && !s.lowerTab().equals(LowerTab.view(id))) {
                deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.view(id)));
            }
        } else if (SwingUtilities.isDescendingFrom(back.owner(), lower.component()) && !s.lowerTab().equals(back.lowerTab())) {
            deps.intents().dispatch(new Intent.SelectLowerTab(back.lowerTab()));
        }
        focusBack = back.owner();
        focusBackRenders = 3;
    }

    private void showSidePane(boolean show) {
        sidePaneShown = show;
        top.removeAll();
        if (show) {
            editorAndSide.setLeftComponent(workspace.component());
            editorAndSide.setRightComponent(side.component());
            top.add(editorAndSide, BorderLayout.CENTER);
            // place the divider once the split has a size; a stored position that would hide either side is ignored
            Edt.later(() -> {
                int width = editorAndSide.getWidth();
                int saved = windowLayout.sideDivider();
                if (width > 0 && saved >= 200 && saved <= width - 200) {
                    editorAndSide.setDividerLocation(saved);
                } else {
                    editorAndSide.setDividerLocation(0.55);
                }
            });
        } else {
            top.add(workspace.component(), BorderLayout.CENTER);
        }
        top.revalidate();
        top.repaint();
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
        lower.renderTranscript(lower.transcriptSession());
    }

    // ---- UiPort

    @Override
    public void editPanel(PanelId panel, String text, EditMode mode) {
        Edt.run(() -> {
            EditorPanel p = workspace == null ? null : workspace.panel(panel);
            if (p == null) {
                return;
            }
            if (!p.editable()) {
                refuseReadOnly();
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

    private void refuseReadOnly() {
        deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The panel shows a log, which is read-only; nothing was written", false));
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
            if (lower != null) {
                lower.terminals().forget(panel);
            }
        });
    }

    @Override
    public void showFile(String session, String path, String baseDir, int line) {
        UiErrands.start("show-file", () -> {
            try {
                Path p = located(session, path, baseDir);
                // a file named without a line opens as the Files tab opens it: in a panel, or its panel comes forward;
                // a binary or large one, and one at a line, show in the side pane (record 111)
                if (line <= 0 && Files.isRegularFile(p) && OpenFiles.isText(p)) {
                    Edt.run(() -> openFile(p));
                    return;
                }
                FileView file = FileView.read(p, line);
                // a file opens on the user's click only: it shows even in a side pane the user hid earlier, and even when
                // it replaces the same file's view in place
                Edt.run(() -> {
                    sidePaneHidden = false;
                    openedFiles.add(p);
                    deps.intents().dispatch(new Intent.ShowView(session, file.title(), file.spec(), Placement.SIDE));
                    render.request();
                });
            } catch (IOException | RuntimeException e) {
                // a path that is no path (a NUL in it) is told like a file that cannot be read
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                deps.intents().dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot open " + path + ": " + why, e));
            }
        });
    }

    /**
     * The file {@code path} names, read from {@code baseDir}; a relative one that is not there is looked for under that
     * folder's tree, then under the repositories of the files {@code session}'s own tool calls named, the latest first,
     * then under the Files tab's folders (record 111). Off the EDT.
     */
    private Path located(String session, String path, String baseDir) {
        Path p = FileView.resolve(path, baseDir);
        if (Files.exists(p) || Path.of(HomeDir.expand(path)).isAbsolute()) {
            return p;
        }
        List<Path> roots = new ArrayList<>();
        if (baseDir != null && !baseDir.isBlank()) {
            roots.add(Path.of(baseDir));
        }
        if (session != null && !session.isEmpty()) {
            roots.addAll(WorkedIn.roots(deps.transcripts().of(session), WorkedIn.MAX_ROOTS));
        }
        for (FolderRoot f : deps.state().get().folders()) {
            roots.add(Path.of(HomeDir.expand(f.path())));
        }
        return FileSearch.bySuffix(path, roots).orElse(p);
    }

    @Override
    public void arrange(Layout layout) {
        Edt.run(() -> arrangeWhenIdle(layout));
    }

    @Override
    public void showFolder(FolderRoot folder, ViewSpec.TreeNode tree) {
        Edt.run(() -> sidebar.showFolder(folder, tree));
    }

    @Override
    public void showRepos(List<RepoStatus> repos) {
        Edt.run(() -> {
            repoChanges.set(repos);
            if (sidebar != null) {
                sidebar.showRepos(repos);
            }
        });
    }

    /** A change read for the comparison window; one that follows the selection, or is read again, shows only in a window already open. */
    @Override
    public void showChange(ChangeTexts texts, ChangeAsk ask) {
        Edt.run(() -> {
            if (diffWindow == null || ask != ChangeAsk.OPEN && !diffWindow.isShowing()) {
                return;
            }
            diffWindow.show(texts, ask);
        });
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

    /**
     * A theme was chosen, from the swatch beside New panel or the palette: kept, and every window takes its ground and
     * its tint at once (record 103).
     */
    private void themeChosen(NamedTheme theme) {
        windowLayout.theme(theme);
        Theme.ground(theme.ground);
        paintIn(theme.tint);
        // the colours set from the state (the Approvals tab's title, the status bar's approvals) are set again
        render();
    }

    /**
     * Every window in {@code tint} now: what listens to the tint (the transcript, the tab card, rendered Markdown) lays
     * itself out or recolours in it, and the rest (icons, the Files tab, About) read it as they repaint.
     */
    private void paintIn(Tint tint) {
        Theme.tint(tint);
        for (Window w : Window.getWindows()) {
            w.repaint();
        }
    }

    /** A sidebar tab's menu gains the window's commands for that tab, each with its key and enabled as it can run. */
    private void tabMenu(int tab, JPopupMenu menu) {
        List<String> ids = switch (tab) {
            case SessionsSidebar.SESSIONS -> List.of("panel.new", "panel.reopen");
            case SessionsSidebar.CONNECTIONS -> deps.server() == null ? List.<String>of() : List.of("server.start", "server.stop");
            case SessionsSidebar.FILES -> List.of("files.settings");
            default -> List.of();
        };
        for (String id : ids) {
            Command c = actions.get(id);
            if (c != null) {
                JMenuItem item = new JMenuItem(ActionRegistry.action(c));
                item.setEnabled(c.enabled().getAsBoolean());
                menu.add(item);
            }
        }
    }

    /** The Files tab's folders in their window; saving replaces them. */
    private void editFolders() {
        FoldersDialog.show(frame, deps.state().get().folders(), repoChanges, deps.intents(), deps.setup().root().folders());
    }

    /** The kit's menu under its label in the status bar: its upgrade and the brain's backup, enabled as each can run. */
    private void kitMenu(JComponent under) {
        JPopupMenu menu = new JPopupMenu();
        for (String id : List.of("kit.upgrade", "kit.backup")) {
            Command c = actions.get(id);
            if (c != null) {
                JMenuItem item = new JMenuItem(ActionRegistry.action(c));
                item.setEnabled(c.enabled().getAsBoolean());
                menu.add(item);
            }
        }
        menu.show(under, 0, -menu.getPreferredSize().height);
    }

    /** A Files tab folder row's menu: a folder of the kit's brain gains the brain's backup. */
    private void folderMenu(FolderRoot folder, JPopupMenu menu) {
        Optional<Path> brain = deps.setup().root().brain();
        Command c = actions.get("kit.backup");
        if (brain.isEmpty() || c == null || !inside(Path.of(folder.path()), brain.get())) {
            return;
        }
        menu.addSeparator();
        JMenuItem item = new JMenuItem(ActionRegistry.action(c));
        item.setEnabled(c.enabled().getAsBoolean());
        menu.add(item);
    }

    /** Whether {@code p} is {@code dir} or inside it, links followed: {@code ~/.claude/MEMORY} may be a link into the brain. */
    static boolean inside(Path p, Path dir) {
        Path a = p.toAbsolutePath().normalize();
        Path b = dir.toAbsolutePath().normalize();
        try {
            a = a.toRealPath();
            b = b.toRealPath();
        } catch (IOException | RuntimeException e) {
            // as written: a folder not there yet is compared by its path
        }
        return a.startsWith(b);
    }

    /** Opens the Changes view of every folder's repository in the side pane; closes it when it shows them all, widens it when it shows one. */
    private void toggleChanges() {
        Optional<ViewState> open = deps.state().get().views().stream().filter(v -> v.spec() instanceof ViewSpec.Changes).findFirst();
        if (open.isPresent() && ((ViewSpec.Changes) open.get().spec()).root().isEmpty()) {
            deps.intents().dispatch(new Intent.CloseView(open.get().id()));
        } else {
            sidePaneHidden = false;
            deps.intents().dispatch(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes(), Placement.SIDE));
        }
    }

    @Override
    public void showPlanUsage(PlanUsage usage) {
        Edt.run(() -> status.planUsage(usage));
    }

    /** Applies a session's layout once the user has not typed for two seconds, so panes never move under the keys. */
    private void arrangeWhenIdle(Layout layout) {
        if (frame == null) {
            return;
        }
        long idle = (System.nanoTime() - lastKeyNanos) / 1_000_000;
        if (lastKeyNanos != 0 && idle < TYPING_PAUSE_MILLIS) {
            // one layout waits for the pause: a newer one replaces it, so an older never lands after it
            boolean waiting = pendingLayout != null;
            pendingLayout = layout;
            if (!waiting) {
                Timer later = new Timer((int) (TYPING_PAUSE_MILLIS - idle) + 50, e -> {
                    Layout next = pendingLayout;
                    pendingLayout = null;
                    if (next != null) {
                        arrangeWhenIdle(next);
                    }
                });
                later.setRepeats(false);
                later.start();
            }
            return;
        }
        // a layout applied now is newer than one still waiting for the pause, which then never lands
        pendingLayout = null;
        if (beforeArrange == null) {
            // Restore layout goes back to before the first arrangement the user has not undone
            beforeArrange = new LayoutSnapshot(sidebar.isShown(), sidePaneHidden, vertical.getDividerLocation());
        }
        applyLayout(layout);
    }

    private void applyLayout(Layout layout) {
        if (layout.sidebar() != Layout.Toggle.KEEP && sidebar.isShown() != (layout.sidebar() == Layout.Toggle.SHOW)) {
            toggleSidebar();
        }
        if (layout.sidePane() != Layout.Toggle.KEEP) {
            sidePaneHidden = layout.sidePane() == Layout.Toggle.HIDE;
            render();
        }
        if (!Double.isNaN(layout.lowerShare())) {
            vertical.setDividerLocation(1.0 - layout.lowerShare());
        }
        if (layout.focus() != null) {
            // the reducer checks again that the panel is still the session's: it may have been rebound meanwhile
            deps.intents().dispatch(new Intent.SessionFocus(layout.session(), layout.focus(), layout.tab()));
        }
    }

    /** Puts the panes back as they were before a session last arranged them. */
    private void restoreArranged() {
        LayoutSnapshot b = beforeArrange;
        if (b == null) {
            return;
        }
        beforeArrange = null;
        if (sidebar.isShown() != b.sidebar()) {
            toggleSidebar();
        }
        sidePaneHidden = b.sidePaneHidden();
        render();
        vertical.setDividerLocation(b.lowerDivider());
        deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Restored the layout", false));
    }

    // ---- commands

    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;

    /** Registers every command; where each sits, in a menu or an area, its icon and its tooltip come from {@link Menus}. */
    private void registerCommands() {
        // file
        cmd("panel.new", "New panel", key(KeyEvent.VK_T, shortcut), () -> workspace.newPanel());
        cmd("panel.openSpec", "Open file…", key(KeyEvent.VK_O, shortcut), this::openSpec);
        cmd("panel.reopen", "Reopen closed panel", key(KeyEvent.VK_T, shortcut | SHIFT), this::reopenPanel, () -> workspace.hasClosed());
        // ⌘S saves the focused panel wherever the keyboard is; exporting a view is ⌘⇧S
        cmd("panel.save", "Save", key(KeyEvent.VK_S, shortcut), this::savePanel);
        cmd("panel.rename", "Rename panel…", null, () -> {
            EditorPanel p = workspace.focused();
            if (p != null) {
                workspace.rename(p);
            }
        });
        // ⌘W closes the view that has the keyboard, else the panel
        cmd("file.close", "Close", key(KeyEvent.VK_W, shortcut), () -> {
            long view = focusedView();
            if (view >= 0) {
                deps.intents().dispatch(new Intent.CloseView(view));
            } else {
                closePanel(focusedId());
            }
        });
        cmd("view.export", "Export view…", key(KeyEvent.VK_S, shortcut | SHIFT), this::export, () -> capability(Exportable.class) != null);
        // edit: each forwards its key to the focused component's own binding, so the menu shows the keys the
        // components already have; a component without the binding (the terminal) gets the key itself
        edit("edit.undo", "Undo", key(KeyEvent.VK_Z, shortcut));
        edit("edit.redo", "Redo", key(KeyEvent.VK_Z, shortcut | SHIFT));
        edit("edit.cut", "Cut", key(KeyEvent.VK_X, shortcut));
        edit("edit.copy", "Copy", key(KeyEvent.VK_C, shortcut));
        edit("edit.paste", "Paste", key(KeyEvent.VK_V, shortcut));
        edit("edit.selectAll", "Select all", key(KeyEvent.VK_A, shortcut));
        cmd("view.copy", "Copy view content", key(KeyEvent.VK_C, shortcut | SHIFT), () -> {
            Copyable c = capability(Copyable.class);
            if (c != null && Capabilities.copy(c)) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Copied the view's content", false));
            }
        }, () -> {
            Copyable c = capability(Copyable.class);
            return c != null && c.canCopy();
        });
        cmd("view.find", "Find…", key(KeyEvent.VK_F, shortcut), () -> findBar.open(capability(Searchable.class)),
                () -> capability(Searchable.class) != null);
        cmd("view.findNext", "Find next", key(KeyEvent.VK_G, shortcut), () -> findBar.next(true), () -> findBar.isOpen());
        cmd("view.findPrevious", "Find previous", key(KeyEvent.VK_G, shortcut | SHIFT), () -> findBar.next(false), () -> findBar.isOpen());
        KeyStroke complete = key(KeyEvent.VK_SPACE, CTRL);
        cmd("edit.complete", "Complete SQL", complete, () -> Keys.forward(editorArea(), complete, true),
                () -> editorFocused() && Keys.forward(editorArea(), complete, false));
        // ⌥⎋ as well, as in macOS text views: ⌃Space is the system's input-source switch on many Macs
        cmd("edit.completeAlt", "Complete SQL", key(KeyEvent.VK_ESCAPE, ALT), () -> Keys.forward(editorArea(), complete, true),
                () -> editorFocused() && Keys.forward(editorArea(), complete, false));
        cmd("panel.preview", "Edit / Preview Markdown", key(KeyEvent.VK_M, shortcut | SHIFT), () -> {
            EditorPanel p = workspace.focused();
            if (p != null) {
                p.togglePreview();
            }
        });
        // panel: running
        cmd("run", "Run", key(KeyEvent.VK_ENTER, shortcut), () -> run(Target.AUTO), this::canRun);
        cmd("run.claude", "Send to Claude", key(KeyEvent.VK_ENTER, shortcut | SHIFT), () -> run(Target.CLAUDE), this::canRun);
        cmd("run.sql", "Run as SQL", key(KeyEvent.VK_ENTER, ALT), () -> run(Target.SQL), this::canRun);
        // ⌃⏎ on macOS; elsewhere Ctrl+⏎ is Run and Alt+⏎ Run as SQL, so Ctrl+Alt+⏎
        cmd("terminal.run", "Run in the terminal", key(KeyEvent.VK_ENTER, Platform.macKeys() ? CTRL : shortcut | ALT), this::runInTerminal,
                this::editorFocused);
        cmd("run.stop", "Stop", key(KeyEvent.VK_PERIOD, shortcut), () -> deps.intents().dispatch(new Intent.CancelRun(focusedId())),
                this::canStop);
        // panel: its session
        cmd("session.toggle", "Connect or disconnect", null, () -> focusedSession().ifPresent(x ->
                deps.intents().dispatch(x.status().isUsable() ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name()))),
                () -> focusedSession().isPresent());
        cmd("session.interrupt", "Interrupt the turn", key(KeyEvent.VK_PERIOD, shortcut | SHIFT),
                () -> focusedSession().ifPresent(x -> deps.intents().dispatch(new Intent.InterruptSession(x.name()))),
                () -> focusedSession().map(SessionState::isBusy).orElse(false));
        // the Session tab emptied, the conversation going on: from the palette alone, once a turn has ended (record 104)
        cmd("transcript.clear", "Clear the Session tab", null, lower::clearTranscript, lower::canClearTranscript);
        // as the session's menu in the sidebar: only a running session has a conversation to compact
        cmd("session.compact", "Compact the conversation", null, () -> focusedSession().ifPresent(x ->
                        deps.intents().dispatch(new Intent.SessionCommand(x.name(), SessionSpec.COMPACT_COMMAND))),
                () -> focusedSession().filter(x -> x.status().isUsable()).isPresent());
        for (String m : SessionSpec.MODELS) {
            cmd("session.model." + m, m, null, () -> focusedSession().ifPresent(x ->
                    deps.intents().dispatch(new Intent.SetSessionModel(x.name(), m))), () -> focusedSession().isPresent());
        }
        for (String e : SessionSpec.EFFORTS) {
            cmd("session.effort." + e, e, null, () -> focusedSession().ifPresent(x ->
                    deps.intents().dispatch(new Intent.SetSessionEffort(x.name(), e))), () -> focusedSession().isPresent());
        }
        cmd("panel.bindSession", "Open a session…", null, () -> choose("Session to open",
                deps.state().get().sessions().stream().map(x -> x.name()).toList(),
                name -> deps.intents().dispatch(new Intent.OpenSession(name, deps.state().get().focused(), PanelId.random()))));
        // the focused panel's session in the dialog, or a new one when the panel has none
        cmd("session.dialog", "Session settings…", key(KeyEvent.VK_K, shortcut | SHIFT), () -> openSessions(deps.state().get().focusedPanel().session()));
        cmd("session.import", "Import conversations…", null, this::openImport);
        // panel: its connection
        cmd("connection.toggle", "Connect or disconnect", null, () -> {
            AppState s = deps.state().get();
            s.connection(s.focusedPanel().connection()).ifPresent(c -> deps.intents().dispatch(c.status().isUsable()
                    ? new Intent.CloseConnection(c.name()) : new Intent.OpenConnection(c.name())));
        }, () -> !deps.state().get().focusedPanel().connection().isEmpty());
        cmd("connection.schema", "Explore the schema", key(KeyEvent.VK_E, shortcut | SHIFT),
                () -> exploreSchema(deps.state().get().focusedPanel().connection()),
                () -> !deps.state().get().focusedPanel().connection().isEmpty());
        cmd("panel.bindConnection", "Bind a connection…", null, () -> choose("Connection for this panel",
                deps.state().get().connections().stream().map(x -> x.name()).toList(),
                name -> deps.intents().dispatch(new Intent.BindConnection(deps.state().get().focused(), name))));
        cmd("connection.dialog", "Connection settings…", key(KeyEvent.VK_O, shortcut | SHIFT), () -> openConnections(deps.state().get().focusedPanel().connection()));
        if (deps.server() != null) {
            cmd("server.start", "Start embedded QuestDB", null, () -> deps.server().toggle(), () -> !deps.server().isRunning());
            cmd("server.stop", "Stop embedded QuestDB", null, () -> deps.server().toggle(), () -> deps.server().isRunning());
        }
        // panel: its results
        cmd("run.insertResult", "Attach to the editor", key(KeyEvent.VK_I, shortcut | SHIFT), this::insertLastResult, this::hasRun);
        cmd("run.keep", "Keep in a tab", key(KeyEvent.VK_R, shortcut | SHIFT), this::keepResult, this::hasRun);
        // always claimed: the editor's own ⌘J joins lines, which the user never means here; without a run it says so
        cmd("run.plot", "Plot", key(KeyEvent.VK_J, shortcut), this::plotLastResult);
        cmd("run.plotLive", "Plot, refreshing…", key(KeyEvent.VK_J, shortcut | SHIFT), this::plotLive, this::hasRun);
        cmd("run.plotStop", "Stop refreshing plots", null, livePlots::stopAll, () -> livePlots.size() > 0);
        // panel: tools that run in its project
        cmd("tools.gradle", "Run Gradle tasks…", key(KeyEvent.VK_G, shortcut | ALT), this::runGradle, () -> focusedSession().isPresent());
        cmd("files.settings", "Configure " + FoldersDialog.title(), null, this::editFolders);
        // the kit: its upgrade, by a session in its root, and the brain's backup; never on their own
        cmd("kit.upgrade", KitActions.UPGRADE, null, kit::upgrade, kit::canUpgrade);
        cmd("kit.backup", KitActions.BACK_UP, null, kit::backUp, kit::canBackUp);
        cmd("terminal.restart", "Restart the shell", null, () -> lower.terminals().restartFocused(deps.state().get()));
        cmd("terminal.start", "Start the shell", null, () -> lower.terminals().startFocused(deps.state().get()),
                () -> !lower.terminals().focusedRunning());
        cmd("terminal.stop", "Stop the shell", null, () -> lower.terminals().stopFocused(), () -> lower.terminals().focusedRunning());
        // view
        cmd("view.palette", "Command palette", key(KeyEvent.VK_P, shortcut | SHIFT), () -> new CommandPalette(actions).show(frame));
        cmd("focus.editor", "Focus the editor", key(KeyEvent.VK_E, shortcut), () -> {
            EditorPanel p = workspace.focused();
            if (p != null) {
                p.editor().requestFocus();
            }
        });
        cmd("view.sidebar", "Sidebar", key(KeyEvent.VK_B, shortcut), this::toggleSidebar);
        cmd("view.sidePane", "Side pane", key(KeyEvent.VK_BACK_SLASH, shortcut), () -> {
            sidePaneHidden = sidePaneShown;
            render();
        }, () -> sidePaneShown || side.hasViews());
        // ⌘K, IntelliJ's key for its commit view; a focused terminal keeps it as its Clear Buffer
        cmd("view.changes", "Uncommitted changes", key(KeyEvent.VK_K, shortcut), this::toggleChanges);
        cmd("view.files", "Files", null, () -> {
            if (!sidebar.isShown()) {
                toggleSidebar();
            }
            sidebar.selectTab(SessionsSidebar.FILES);
            sidebar.focusFilesFilter();
        });
        cmd("view.restoreLayout", "Restore layout", null, this::restoreArranged, () -> beforeArrange != null);
        // ⌃1–⌃4 follow the lower tabs' order: Session, Approvals, Terminal, Results
        cmd("view.session", "Session", key(KeyEvent.VK_1, lowerTabKey), () -> showLowerAndFocus(LowerTab.SESSION, lower.transcript().canvas()));
        cmd("view.approvals", "Approvals", key(KeyEvent.VK_2, lowerTabKey),
                () -> showLowerAndFocus(LowerTab.APPROVALS, lower.inbox().component(), lower.inbox()::focusFirst));
        cmd("view.terminal", "Terminal", key(KeyEvent.VK_3, lowerTabKey), () -> {
            deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.TERMINAL));
            Edt.later(() -> lower.terminals().focusShown(deps.state().get()));
        });
        cmd("view.results", "Results", key(KeyEvent.VK_4, lowerTabKey), () -> showLowerAndFocus(LowerTab.RESULTS, lower.results().grid().table()));
        // ⌘⌥1–4 as well: macOS Mission Control takes ⌃1–⌃4 when its desktop shortcuts are on
        String[] lowerTabs = {"view.session", "view.approvals", "view.terminal", "view.results"};
        for (int i = 0; i < lowerTabs.length; i++) {
            String target = lowerTabs[i];
            cmd(target + ".alt", actions.get(target).label() + " tab", key(KeyEvent.VK_1 + i, shortcut | ALT), () -> actions.run(target));
        }
        cmd("view.nextTab", "Next tab", key(KeyEvent.VK_TAB, CTRL), () -> lower.selectRelative(1));
        cmd("view.previousTab", "Previous tab", key(KeyEvent.VK_TAB, CTRL | SHIFT), () -> lower.selectRelative(-1));
        cmd("view.closeLower", "Close the view in front", key(KeyEvent.VK_W, shortcut | SHIFT),
                () -> deps.intents().dispatch(new Intent.CloseView(lower.selectedView())), () -> lower.selectedView() >= 0);
        cmd("panel.next", "Next panel", key(KeyEvent.VK_CLOSE_BRACKET, shortcut | SHIFT), () -> workspace.selectRelative(1));
        cmd("panel.previous", "Previous panel", key(KeyEvent.VK_OPEN_BRACKET, shortcut | SHIFT), () -> workspace.selectRelative(-1));
        for (int i = 1; i <= 9; i++) {
            int index = i - 1;
            cmd("panel." + i, "Go to panel " + i, key(KeyEvent.VK_0 + i, shortcut), () -> workspace.select(index));
        }
        cmd("view.zoomIn", "Zoom in", key(KeyEvent.VK_EQUALS, shortcut), () -> zoom(1), () -> capability(Zoomable.class) != null);
        cmd("view.zoomOut", "Zoom out", key(KeyEvent.VK_MINUS, shortcut), () -> zoom(-1), () -> capability(Zoomable.class) != null);
        cmd("view.zoomReset", "Reset zoom", key(KeyEvent.VK_0, shortcut), () -> {
            Zoomable z = capability(Zoomable.class);
            if (z != null) {
                z.resetZoom();
            }
        }, () -> capability(Zoomable.class) != null);
        cmd("view.notices", "Recent notices", null, () -> status.showHistory());
        // help
        cmd("help.tutorial", "Tutorial", null, help::showTutorial, () -> deps.home() != null);
        // ⌘/ stays the editor's comment toggle; ⌘⇧/ is the Mac's Help key
        cmd("help.keys", "Keyboard shortcuts", key(KeyEvent.VK_SLASH, shortcut | SHIFT), help::showKeys);
        cmd("help.markdown", "Markdown sheet", null, help::showMarkdownSheet);
        cmd("help.log", "Show the log", null, help::showLog, () -> deps.logDir() != null);
        cmd("help.dataRoot", "Reveal comando folder", null, () -> help.reveal(deps.home()), () -> deps.home() != null);
        // the themes, from the swatch beside New panel: a ground and its tint chosen together, each with its swatch (Menus), the one chosen
        // ticked (record 103)
        for (NamedTheme t : NamedTheme.values()) {
            cmd(t.commandId(), t.label, null, () -> themeChosen(t));
            actions.choice(t.commandId(), () -> NamedTheme.chosen().orElse(null) == t);
        }
        // on macOS the application menu owns ⌘Q and calls the quit handler; a second binding would quit twice
        boolean mac = Platform.isMac();
        cmd("app.about", "About comando", null, help::showAbout);
        cmd("app.quit", "Quit comando", mac ? null : key(KeyEvent.VK_Q, shortcut), this::quit);
    }

    private void cmd(String id, String label, KeyStroke key, Runnable run) {
        actions.register(Command.of(id, label, Menus.icon(id), key, "", run));
    }

    private void cmd(String id, String label, KeyStroke key, Runnable run, BooleanSupplier enabled) {
        actions.register(Command.of(id, label, Menus.icon(id), key, "", run, enabled));
    }

    /** An Edit command: runs the focused component's own action for {@code key}; enabled when it has one. */
    private void edit(String id, String label, KeyStroke key) {
        cmd(id, label, key, () -> Keys.forward(editTarget(), key, true), () -> Keys.forward(editTarget(), key, false));
    }

    /**
     * What an Edit command acts on: the comparison window's text while it has the keyboard, since macOS's menu bar takes
     * the command's key before that window sees it; else the main window's.
     */
    private Component editTarget() {
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return c != null && diffWindow != null && diffWindow.holds(c) ? c : focusOwnerInFrame();
    }

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    /** The buttons left of the theme's swatch: the shell, the refreshing plots, the embedded server when there is one. */
    private List<CommandButton> cornerButtons() {
        List<CommandButton> buttons = new ArrayList<>();
        for (List<String> ids : Menus.corner(deps.server() != null)) {
            List<Command> commands = ids.stream().map(actions::get).toList();
            buttons.add(new CommandButton(commands, Menus::tip));
        }
        return buttons;
    }

    private JMenuBar menuBar() {
        return actions.menuBar(Menus.bar(), Menus::tip, id -> null);
    }

    // ---- focus

    /** The component with the keyboard in the main window; while a popup or the palette has it, the last one it had. */
    private Component focusOwnerInFrame() {
        if (frame == null) {
            return null;
        }
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (c != null && SwingUtilities.getWindowAncestor(c) == frame) {
            return c;
        }
        return frame.getMostRecentFocusOwner();
    }

    private boolean editorFocused() {
        EditorPanel p = workspace == null ? null : workspace.focused();
        Component c = focusOwnerInFrame();
        return p != null && c != null && SwingUtilities.isDescendingFrom(c, p.editor().area());
    }

    private Component editorArea() {
        EditorPanel p = workspace == null ? null : workspace.focused();
        return p == null ? null : p.editor().area();
    }

    /**
     * The capability of the view the user means: the one holding the focus, else the lower tab in front, so ⌘F, ⌘⇧C
     * and zoom work on the results or transcript while the editor has the keyboard.
     */
    private <T> T capability(Class<T> type) {
        T t = Capabilities.at(focusOwnerInFrame(), type);
        return t != null || lower == null ? t : Capabilities.within(lower.selected(), type);
    }

    /** The placed view that holds the focus, in the lower panel or the side pane; -1 when the focus is elsewhere. */
    private long focusedView() {
        Component c = focusOwnerInFrame();
        if (c == null || lower == null) {
            return -1;
        }
        long id = lower.viewHolding(c);
        return id >= 0 ? id : side.viewHolding(c);
    }

    /** Run keys act while the keyboard is in the focused panel's editor; elsewhere the key is the component's. */
    private boolean canRun() {
        return editorFocused();
    }

    /**
     * Selects a lower tab and gives {@code target} the keyboard once the tab shows: a component in a hidden tab
     * refuses focus, so the request waits for the render that switches the tab.
     */
    private void showLowerAndFocus(LowerTab tab, Component target) {
        showLowerAndFocus(tab, target, target::requestFocusInWindow);
    }

    /**
     * Selects {@code tab} and runs {@code focus} once {@code shown} is on screen: a component in a tab that is not
     * showing yet refuses the keyboard, so the focus waits for the render that shows the tab.
     */
    private void showLowerAndFocus(LowerTab tab, Component shown, Runnable focus) {
        if (deps.state().get().lowerTab().equals(tab) && shown.isShowing()) {
            focus.run();
            return;
        }
        focusAfterRender = new FocusAfterRender(shown, focus);
        deps.intents().dispatch(new Intent.SelectLowerTab(tab));
    }

    private boolean canStop() {
        AppState s = deps.state().get();
        return s.runOf(s.focused()).map(r -> r.status().isActive()).orElse(false)
                || focusedSession().map(SessionState::isBusy).orElse(false);
    }

    private boolean hasRun() {
        AppState s = deps.state().get();
        return s.runOf(s.focused()).isPresent();
    }

    // ---- actions

    private PanelId focusedId() {
        return deps.state().get().focused();
    }

    private Optional<SessionState> focusedSession() {
        AppState s = deps.state().get();
        return s.session(s.focusedPanel().session());
    }

    /** Runs the editor's selection or the block under the caret. */
    private void run(Target target) {
        EditorPanel p = workspace.focused();
        if (p != null) {
            p.runSelection(target);
        }
    }

    /**
     * Opens the most recently closed tab again: a panel of comando's own, still closed, comes back with its text and
     * bindings; a document opens on its file again. A panel removed since is skipped.
     */
    private void reopenPanel() {
        while (workspace.hasClosed()) {
            Workspace.ClosedPanel c = workspace.popClosed().orElseThrow();
            if (!c.document()) {
                if (deps.state().get().closedPanel(c.id()).isPresent()) {
                    deps.intents().dispatch(new Intent.ReopenPanel(c.id()));
                    return;
                }
            } else if (c.file() != null) {
                openFile(c.file());
                return;
            }
        }
    }

    /** Files ▸ Open file…: a file chosen anywhere, starting in the panels' folder. */
    private void openSpec() {
        Optional<Path> folder = deps.texts().folder();
        if (folder.isPresent()) {
            FileChoosers.open(frame, "Open a file in a panel", folder.get()).ifPresent(this::openFile);
        }
    }

    /** Files whose opening is under way, so a second click does not open them twice. EDT only. */
    private final Set<Path> openingFiles = new HashSet<>();

    /**
     * Opens a file in a new panel bound to it, or brings forward the panel that has it. The check and the read run
     * off the EDT; a file that is binary or over {@link OpenFiles#MAX_PANEL_BYTES} goes to the side pane, read as
     * {@link FileView} reads it (a binary file said to be one, a file over {@link FileView#MAX_BYTES} refused).
     * Called on the EDT.
     */
    public void openFile(Path chosen) {
        Path file = chosen.toAbsolutePath().normalize();
        openedFiles.add(file);
        // what the file opens from, read now, at the request: closing it gives the keyboard back there (record 112)
        FocusOrigins.Origin from = originNow();
        for (PanelState p : deps.state().get().panels()) {
            if (deps.texts().file(p.id()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                focusOrigins.opened("panel:" + p.id().value(), from);
                deps.intents().dispatch(new Intent.FocusPanel(p.id()));
                return;
            }
        }
        // the file of a closed panel: the panel comes back, with its bindings
        for (PanelState p : deps.state().get().closed()) {
            if (deps.texts().file(p.id()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                focusOrigins.opened("panel:" + p.id().value(), from);
                deps.intents().dispatch(new Intent.ReopenPanel(p.id()));
                return;
            }
        }
        if (!openingFiles.add(file)) {
            return;
        }
        UiErrands.start("open-file", () -> {
            if (!OpenFiles.isText(file)) {
                deps.intents().dispatch(new Intent.OpenFile("", file.toString(), 0));
                Edt.later(() -> openingFiles.remove(file));
                return;
            }
            PanelId id = PanelId.random();
            Edt.run(() -> focusOrigins.opened("panel:" + id.value(), from));
            deps.texts().open(id, file).thenAccept(opened -> {
                switch (opened.kind()) {
                    case BOUND -> {
                        // a file elsewhere keeps its extension, so it never takes a session's or a panel's name; a file of
                        // the panels' folder is named after its panel's title, so it takes the title without .md
                        boolean document = deps.texts().document(id);
                        String title = document ? PanelTexts.documentTitle(file, deps.state().get()::titleTaken) : PanelTexts.titleOf(file);
                        deps.intents().dispatch(new Intent.AddPanel(id, title, document));
                    }
                    // a click racing the first one's panel, which comes forward on its own
                    case ALREADY_OPEN -> { }
                    case REFUSED -> deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, opened.reason(), false));
                }
                Edt.later(() -> openingFiles.remove(file));
            });
        });
    }

    /** Panels save as they change; ⌘S saves now and says so, for the habit. */
    private void savePanel() {
        EditorPanel p = workspace.focused();
        if (p != null) {
            String title = p.panel().title();
            deps.texts().changed(p.panel().id(), p.editor().text());
            deps.texts().saveNow().thenAccept(error -> deps.intents().dispatch(error == null
                    ? new Intent.Notify(Notice.Level.INFO, "Saved " + title + " (panels also save as you type)", false)
                    : Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot save the panels: " + error, null)));
        }
    }

    /** Closes a panel; asks first when its shell runs a job in front, which closing would end. */
    private void closePanel(PanelId id) {
        Optional<String> job = lower.terminals().runningJob(id);
        // the last panel is never closed: the reducer says so, with nothing asked first
        if (job.isPresent() && deps.state().get().panels().size() > 1) {
            String title = deps.state().get().panel(id).map(x -> x.title()).orElse("the panel");
            String why = job.get().equals(TerminalWidget.UNCHECKED_JOB)
                    ? "comando cannot tell whether the shell in its Terminal tab is running a program; closing the panel ends anything it runs."
                    : "The shell in its Terminal tab is running " + job.get() + "; closing the panel ends it.";
            if (!confirm("Close " + title + "?", why, "Close panel")) {
                return;
            }
        }
        deps.intents().dispatch(new Intent.ClosePanel(id));
    }

    /** A yes-or-no question with the action named on the button; Cancel is the default. */
    private boolean confirm(String title, String message, String action) {
        Object[] options = {action, "Cancel"};
        return JOptionPane.showOptionDialog(frame, Plain.text(message), title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]) == 0;
    }

    private void export() {
        Exportable e = capability(Exportable.class);
        if (e == null) {
            return;
        }
        Path dir = windowLayout.exportDir();
        Capabilities.export(frame, e, dir, (file, error) -> {
            if (error == null) {
                windowLayout.exportDir(file.toAbsolutePath().getParent());
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Exported to " + file, false));
            } else {
                deps.intents().dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot export to " + file + ": " + error, null));
            }
        });
    }

    // ---- help

    /** The user asked for the explorer: it shows, even in a side pane the user hid earlier. */
    private void exploreSchema(String connection) {
        sidePaneHidden = false;
        deps.intents().dispatch(new Intent.ExploreSchema(connection));
    }

    /** Asks for Gradle tasks, naming the build they run in; the last ones are remembered across restarts. */
    private void runGradle() {
        String dir = focusedSession().map(x -> Labels.shortPath(x.spec().workDir())).orElse("the session's directory");
        String tasks = (String) JOptionPane.showInputDialog(frame, Plain.text("Tasks and options, run in " + dir + ":"),
                "Run Gradle", JOptionPane.PLAIN_MESSAGE, null, null, windowLayout.gradleTasks());
        if (tasks != null && !tasks.isBlank()) {
            windowLayout.gradleTasks(tasks.strip());
            deps.intents().dispatch(new Intent.RunGradle(focusedId(), tasks.strip()));
        }
    }

    /** Types the editor's selection, or the line under the caret, into the panel's shell and runs it. */
    private void runInTerminal() {
        EditorPanel p = workspace.focused();
        if (p == null) {
            return;
        }
        String text = p.editor().area().getSelectedText();
        if (text == null || text.isBlank()) {
            var area = p.editor().area();
            try {
                int line = area.getLineOfOffset(area.getCaretPosition());
                text = area.getText(area.getLineStartOffset(line), area.getLineEndOffset(line) - area.getLineStartOffset(line));
            } catch (BadLocationException e) {
                return;
            }
        }
        String command = text.strip();
        if (command.isEmpty()) {
            return;
        }
        deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.TERMINAL));
        Edt.later(() -> {
            // the posted send may run before the render that creates the panel's terminal: render it here first
            TerminalTabs tabs = lower.terminals();
            tabs.render(deps.state().get(), true);
            TerminalWidget t = tabs.focused();
            if (t != null) {
                t.send(command + "\n");
            }
        });
    }

    private void zoom(int steps) {
        Zoomable z = capability(Zoomable.class);
        if (z != null) {
            z.zoom(steps);
        }
    }

    private void toggleSidebar() {
        if (sidebar.isShown()) {
            windowLayout.sidebarDivider(outer.getDividerLocation());
        }
        showSidebar(!sidebar.isShown());
    }

    /**
     * Shows the sidebar at its saved width, or collapses it to its rail with no divider to drag. The split keeps
     * both sides visible: a hidden side keeps its old width when the split's size changes in the same layout.
     */
    private void showSidebar(boolean shown) {
        sidebar.show(shown);
        outer.setDividerSize(shown ? outerDividerSize : 0);
        outer.setDividerLocation(shown ? windowLayout.sidebarDivider() : sidebar.railWidth());
        outer.revalidate();
    }

    /**
     * Puts the focused panel's last result in the editor as a fenced block, to send with a prompt: the selected
     * rows when the grid has a selection, else the first 50 rows; the error when the run failed.
     */
    private void insertLastResult() {
        AppState s = deps.state().get();
        SqlRun run = s.runOf(s.focused()).orElse(null);
        EditorPanel p = workspace.focused();
        if (p == null) {
            return;
        }
        if (!p.editable()) {
            refuseReadOnly();
            return;
        }
        if (run == null) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The panel has no result to attach", false));
            return;
        }
        ResultView r = lower.results().shown();
        String block;
        if (run.status() == RunStatus.FAILED) {
            block = Attachments.error(run.sql(), run.error());
        } else if (r == null) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The panel's run returned no rows", false));
            return;
        } else {
            block = Attachments.rows(run.sql(), r, selectedResultRows(r));
        }
        p.editor().append(block);
        p.editor().requestFocus();
    }

    /** The grid's selected rows as result rows, in the order shown, at most 500; empty when nothing is selected. */
    private int[] selectedResultRows(ResultView r) {
        return Arrays.stream(lower.results().grid().selectedSourceRows())
                .filter(i -> i >= 0 && i < r.rowCount()).limit(500).toArray();
    }

    /** Plots the focused panel's last result: the first time or numeric column as x, the other numeric columns as series. */
    private void plotLastResult() {
        try {
            ViewSpec.Plot plot = ResultViews.plot(lower.results().shown());
            deps.intents().dispatch(new Intent.ShowView("", "plot · " + deps.state().get().focusedPanel().title(), plot, Placement.LOWER));
        } catch (IllegalArgumentException e) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, e.getMessage(), false));
        }
    }

    /** Re-runs the focused panel's last query every few seconds and re-plots each result. */
    private void plotLive() {
        AppState s = deps.state().get();
        SqlRun run = s.runOf(s.focused()).orElse(null);
        if (run == null) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Run the query to plot first", false));
            return;
        }
        ViewSpec.Plot plot;
        try {
            plot = ResultViews.plot(lower.results().shown());
        } catch (IllegalArgumentException e) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, e.getMessage(), false));
            return;
        }
        String every = (String) JOptionPane.showInputDialog(frame, "Re-run the query and refresh the plot every N seconds:",
                "Live plot", JOptionPane.PLAIN_MESSAGE, null, null, "5");
        if (every == null) {
            return;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(every.strip());
        } catch (NumberFormatException e) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Not a number of seconds: " + every, false));
            return;
        }
        if (seconds < 1 || seconds > LivePlots.MAX_SECONDS) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Refresh every 1 to " + LivePlots.MAX_SECONDS + " seconds", false));
            return;
        }
        String title = "live · " + s.focusedPanel().title();
        livePlots.start(s.focused(), run.sql(), title, seconds);
        // the plot checked before the dialog: a run that ended while it was open is plotted by the first refresh
        deps.intents().dispatch(new Intent.ShowView("", title, plot, Placement.LOWER));
    }

    /** Copies the focused panel's last result into its own tab, which later runs do not replace. */
    private void keepResult() {
        ResultView r = lower.results().shown();
        AppState s = deps.state().get();
        SqlRun run = s.runOf(s.focused()).orElse(null);
        if (r == null || run == null) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The panel has no result to keep", false));
            return;
        }
        String sql = run.sql().replace('\n', ' ').strip();
        String title = "result · " + Clip.keepStart(sql, 41) + " #" + run.requestId();
        deps.intents().dispatch(new Intent.ShowView("", title, ResultViews.table(r), Placement.LOWER));
    }

    private void openConnections(String select) {
        connectionsDialog.show(deps.state().get(), select);
    }

    private void openSessions(String select) {
        sessionsDialog.show(deps.state().get(), select);
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

    private void openImport() {
        importDialog.show(deps.state().get());
    }

    // ---- window layout

    /** The window's bounds, its dividers, the sidebar and the side pane as they were; maximized once the window shows. */
    private void restoreLayout() {
        int h = windowLayout.restoreBounds(frame);
        sidebar.selectTab(windowLayout.sidebarTab());
        sidebar.manualOrder(windowLayout.manualOrder());
        NamedTheme theme = windowLayout.theme();
        Theme.ground(theme.ground);
        paintIn(theme.tint);
        workspace.linear(windowLayout.linearSite(), windowLayout::linearSite);
        showSidebar(windowLayout.sidebarShown());
        vertical.setDividerLocation(windowLayout.lowerDivider(h));
        panelDividers.putAll(windowLayout.panelDividers());
        sidePaneHidden = windowLayout.sidePaneHidden();
    }

    /**
     * The panel {@code front} came to the front: the lower divider goes where it was when that panel was last in front;
     * a panel never in front keeps the divider as it is.
     */
    private void lowerDividerOf(PanelId front) {
        if (front.equals(dividerPanel)) {
            return;
        }
        dividerPanel = front;
        Integer at = panelDividers.get(front);
        if (at != null && at != vertical.getDividerLocation()) {
            vertical.setDividerLocation(at);
        } else if (vertical.getDividerLocation() > 0) {
            panelDividers.put(front, vertical.getDividerLocation());
        }
    }

    private void saveLayout() {
        // the dividers of panels that are open or can be reopened; a panel gone for good takes its own with it
        AppState s = deps.state().get();
        Map<PanelId, Integer> kept = new HashMap<>(panelDividers);
        kept.keySet().removeIf(id -> s.panel(id).isEmpty() && s.closedPanel(id).isEmpty());
        windowLayout.panelDividers(kept);
        windowLayout.save(frame, new WindowLayout.Saved(sidebar.isShown(), sidebar.isShown() ? outer.getDividerLocation() : -1,
                sidebar.selectedTab(), sidebar.manualOrder(), sidePaneHidden, vertical.getDividerLocation(),
                sidePaneShown ? editorAndSide.getDividerLocation() : -1));
    }

    private void saveLayoutQuietly() {
        try {
            saveLayout();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot save the window layout: " + e.getMessage());
        }
    }

    /** Ends the shells and live plots and closes the window, without quitting the application. */
    @Override
    public void close() {
        // bounded: an event thread stuck at quit must not keep the process, and comando's lock, alive forever
        boolean closed = Edt.runAndWait(() -> {
            if (frame == null) {
                return;
            }
            teardown();
            if (diffWindow != null) {
                diffWindow.dispose();
            }
            frame.dispose();
        }, CLOSE_BOUND);
        if (!closed) {
            LOG.log(System.Logger.Level.WARNING, "the window did not close within " + CLOSE_BOUND.toSeconds()
                    + " s: the event thread is busy; quitting without it");
        }
        // the gestures' errands still running: interrupted and waited for, bounded
        UiErrands.closeAll();
    }

    /** Asks for one of {@code names}; nothing happens when there are none or the user cancels. */
    private void choose(String title, List<String> names, Consumer<String> chosen) {
        if (names.isEmpty()) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Nothing to choose: add one first", false));
            return;
        }
        // names can come from stored conversations: shown guarded, mapped back to the real name
        List<String> shown = names.stream().map(Plain::text).toList();
        Object pick = JOptionPane.showInputDialog(frame, title, title, JOptionPane.PLAIN_MESSAGE, null, shown.toArray(), shown.getFirst());
        if (pick != null) {
            chosen.accept(names.get(shown.indexOf(pick.toString())));
        }
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
        if (diffWindow != null) {
            diffWindow.saveNow(); // the comparison's edits reach their file before the store closes
        }
        stopListening();
        lower.terminals().closeAll();
        livePlots.stopAll();
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

    private void stopListening() {
        tickTimer.stop();
        layoutSaver.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", focusListener);
        Toolkit.getDefaultToolkit().removeAWTEventListener(keyClock);
    }

    /** "Quitting stops main's turn.", or what it stops one per line when several things run. */
    static String quitQuestion(List<String> work) {
        return work.size() == 1 ? "Quitting stops " + work.getFirst() + "."
                : "Quitting stops:\n" + work.stream().map(w -> "• " + w).collect(Collectors.joining("\n"));
    }

    /** What quitting would stop: running queries, sessions in a turn, shells running a job; empty when nothing. */
    private List<String> runningWork() {
        AppState s = deps.state().get();
        List<String> work = new ArrayList<>();
        long queries = s.runs().stream().filter(r -> r.status().isActive()).count();
        if (queries > 0) {
            work.add(queries + (queries == 1 ? " running query" : " running queries"));
        }
        List<String> busy = s.sessions().stream().filter(SessionState::isBusy).map(SessionState::name).toList();
        if (!busy.isEmpty()) {
            work.add(String.join(", ", busy) + (busy.size() == 1 ? "'s turn" : "' turns"));
        }
        List<String> waiting = s.sessions().stream().filter(SessionState::isWaiting).map(SessionState::name).toList();
        if (!waiting.isEmpty()) {
            work.add(String.join(", ", waiting) + (waiting.size() == 1 ? "'s background work" : "' background work"));
        }
        // each job named with the panel whose Terminal tab runs it, in the panels' order
        Map<PanelId, String> jobs = lower.terminals().runningJobs();
        for (PanelState p : s.panels()) {
            String job = jobs.get(p.id());
            if (job != null) {
                work.add(TerminalTabs.jobLine(job, p.title()));
            }
        }
        // Gradle runs show as console views until they finish
        s.views().stream().filter(v -> v.spec() instanceof ViewSpec.Console c && c.state() == ViewSpec.RunState.RUNNING)
                .forEach(v -> work.add(v.title()));
        return work;
    }

    private boolean quitting;
    private boolean tornDown;
    /** The layout a session asked for while the user typed, applied at the pause; null when none waits. */
    private Layout pendingLayout;
    private final Consumer<String> schemaListener = connection -> render.request();
    private final StateLoop.Listener stateListener = (previous, next) -> render.request();

    private boolean confirmingQuit;
    /** The macOS quit requests waiting for the answer to the open question. */
    private final List<QuitResponse> pendingQuits = new ArrayList<>();

    /** Quits, asking first when work would stop; false when the user kept comando open. */
    private boolean quit() {
        if (quitting) {
            return true;
        }
        if (confirmingQuit) {
            return true; // a second trigger while the question is open: the open question answers both
        }
        List<String> work = runningWork();
        if (!work.isEmpty()) {
            confirmingQuit = true;
            try {
                if (!confirm("Quit comando?", quitQuestion(work), "Quit")) {
                    pendingQuits.forEach(QuitResponse::cancelQuit);
                    pendingQuits.clear();
                    return false;
                }
            } finally {
                confirmingQuit = false;
            }
        }
        quitting = true;
        long start = System.nanoTime();
        saveLayoutQuietly();
        teardown();
        long millis = (System.nanoTime() - start) / 1_000_000;
        LOG.log(System.Logger.Level.INFO, "quitting [window teardown millis=" + millis + ']');
        // the window stays, saying so, until the application has closed (close() disposes it): a relaunch meanwhile
        // finds the lock held, and the user can see why
        frame.setTitle("comando — closing…");
        ClosingCover cover = new ClosingCover();
        frame.setGlassPane(cover);
        cover.setVisible(true);
        cover.requestFocusInWindow();
        JMenuBar bar = frame.getJMenuBar();
        for (int i = 0; bar != null && i < bar.getMenuCount(); i++) {
            if (bar.getMenu(i) != null) {
                bar.getMenu(i).setEnabled(false);
            }
        }
        deps.onQuit().run();
        return true;
    }
}
