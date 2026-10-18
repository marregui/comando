package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
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
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.FileView;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.PanelTexts;
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
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.panels.EditorPanel;
import dev.marregui.comando.ui.panels.LowerPanel;
import dev.marregui.comando.ui.panels.SessionsSidebar;
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
import dev.marregui.comando.ui.widgets.console.ConsoleWidget;
import dev.marregui.comando.ui.widgets.diff.DiffWidget;
import dev.marregui.comando.ui.widgets.markdown.MarkdownWidget;
import dev.marregui.comando.ui.widgets.plot.PlotWidget;
import dev.marregui.comando.ui.widgets.table.TableViewWidget;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;
import dev.marregui.comando.ui.widgets.text.TextWidget;
import dev.marregui.comando.ui.widgets.tree.TreeWidget;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

/**
 * The Swing shell and the UI port. Renders the state on the EDT, at most once per pending change; builds
 * the menus, key bindings and palette from one action registry; owns the editor text, the dialogs and
 * desktop notifications.
 */
public final class ComandoUi implements UiPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ComandoUi.class.getName());
    private static final int CMD = Platform.menuShortcutMask();
    /** How long closing waits for the event thread to close the window. */
    private static final Duration CLOSE_BOUND = Duration.ofSeconds(5);
    /** False once the user said not to offer the kit when comando starts. */
    static final String KIT_OFFER = "kit.offer";

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
    private final ActionRegistry actions = new ActionRegistry();
    private final WidgetRegistry widgets = new WidgetRegistry();
    private final Coalescer render = new Coalescer(this::render);
    private final Coalescer transcriptRender = new Coalescer(this::renderTranscript);
    /** Window layout; tests point {@code comando.prefsNode} elsewhere so they never read or change the user's. */
    private final Preferences prefs = Preferences.userRoot().node(System.getProperty("comando.prefsNode", "dev/marregui/comando"));
    private JFrame frame;
    private Workspace workspace;
    private LowerPanel lower;
    private SidePane side;
    private SessionsSidebar sidebar;
    private StatusBar status;
    private FindBar findBar;
    private JSplitPane outer;
    private int outerDividerSize;
    private JSplitPane vertical;
    private JSplitPane editorAndSide;
    private JPanel top;
    private Notifier notifier;
    private ResourceDialog<ConnectionSpec> connectionsDialog;
    private ResourceDialog<SessionSpec> sessionsDialog;
    private ImportSessionsDialog importDialog;
    /** The About card while it is open. */
    private JDialog about;
    private Transcript watched;
    private final Runnable transcriptListener = transcriptRender::request;
    private boolean sidePaneShown;
    private LivePlots livePlots;
    private EdtWatchdog watchdog;
    private KeyPriority keyPriority;
    /** The user or Claude hid the side pane; a change in the side views shows it again. */
    private boolean sidePaneHidden;
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
    /** The lower tab the last render showed. */
    private LowerTab renderedLowerTab = LowerTab.SESSION;
    static final long TYPING_PAUSE_MILLIS = 2_000;

    private record LayoutSnapshot(boolean sidebar, boolean sidePaneHidden, int lowerDivider) {
    }

    public ComandoUi(Dependencies deps) {
        this.deps = deps;
    }

    /** Builds and shows the window; call from any thread. */
    public void start() {
        Edt.later(this::build);
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
            Desktop.getDesktop().setAboutHandler(event -> Edt.later(this::showAbout));
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
        AppIcon.install(frame);
        livePlots = new LivePlots(deps.intents(), deps.results());
        Thread.ofVirtual().name("pty-preload").start(TerminalWidget::preload);
        workspace = new Workspace(deps.intents(), deps.texts(), deps.schemas());
        workspace.onAttention(notifier::attention);
        workspace.onClose(this::closePanel);
        workspace.onOpenFile(this::openFile);
        workspace.onThemeChanged(on -> prefs.putBoolean("editor.light", on));
        deps.schemas().addListener(schemaListener);
        lower = new LowerPanel(ctx, deps.results(), deps.transcripts(), widgets);
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
        }, deps.texts(), () -> actions.run("view.sidebar"));
        status = new StatusBar(deps.intents());
        findBar = new FindBar();
        top = new JPanel(new BorderLayout());
        top.add(workspace.component(), BorderLayout.CENTER);
        editorAndSide = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        editorAndSide.setResizeWeight(0.55);
        editorAndSide.setBorder(null);
        vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, lower.component());
        vertical.setResizeWeight(0.45);
        vertical.setBorder(null);
        outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar.component(), vertical);
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
        actions.bind(frame.getRootPane());
        actions.areas(Menus.AREAS);
        keyPriority = KeyPriority.install(actions, frame);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", focusListener);
        Toolkit.getDefaultToolkit().addAWTEventListener(keyClock, AWTEvent.KEY_EVENT_MASK);
        restoreLayout();
        deps.loop().addListener(stateListener);
        render();
        frame.setVisible(true);
        if (prefs.getBoolean("window.maximized", false)) {
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
        writeTutorial();
        EditorPanel p = workspace.focused();
        if (p != null) {
            p.editor().requestFocus();
        }
        // started last: a probe posted during the build waits for the rest of it and reports the build itself
        watchdog = new EdtWatchdog(stall -> LOG.log(System.Logger.Level.WARNING,
                "the EDT has not answered for " + stall.millis() + " ms " + label(stall) + ":\n" + stall.stack()), this::stalled);
    }

    /**
     * A stall of the EDT ended after {@code millis}: a short one is a line with its kind and where the EDT was, a
     * hang a warning after the stack logged at its limit; the user hears of one that lasted a second or more.
     */
    private void stalled(EdtWatchdog.Stall stall, long millis) {
        if (stall.reported()) {
            LOG.log(System.Logger.Level.WARNING, "the EDT answered again after " + millis + " ms");
        } else {
            LOG.log(System.Logger.Level.INFO, "the EDT was busy for " + millis + " ms " + label(stall));
        }
        if (millis >= 1_000) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN,
                    "The window stopped responding for " + Labels.elapsed(millis) + "; the log has where (Tools ▸ Help ▸ Show the log)", false));
        }
    }

    /** {@code [kind] in=… comando=…}, the part of a stall's line that groups stalls of one cause. */
    private static String label(EdtWatchdog.Stall stall) {
        return stall.where().isEmpty() ? "[" + stall.kind() + "]" : "[" + stall.kind() + "] " + stall.where();
    }

    /** The tutorial's file exists from the start, to be opened as any file, not only from Help. */
    private void writeTutorial() {
        Path home = deps.home();
        if (home == null) {
            return;
        }
        Thread.ofVirtual().name("write-tutorial").start(() -> {
            try {
                HelpFiles.writeTutorial(home);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot write the tutorial into " + home + ": " + e.getMessage());
            }
        });
    }

    /** Once a second: elapsed times on the panels and the results, uptimes on the session rows. */
    private void tick() {
        workspace.tick();
        lower.results().tick();
        sidebar.tick();
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
                int saved = prefs.getInt("divider.side", -1);
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
                notifier.notify(title, text);
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
        Thread.ofVirtual().name("show-file").start(() -> {
            try {
                Path p = FileView.resolve(path, baseDir);
                FileView file = FileView.read(p, line);
                // a file opens on the user's click only: it shows even in a side pane the user hid earlier, and even when
                // it replaces the same file's view in place
                Edt.run(() -> {
                    sidePaneHidden = false;
                    deps.intents().dispatch(new Intent.ShowView(session, file.title(), file.spec(), Placement.SIDE));
                    render.request();
                });
            } catch (IOException | RuntimeException e) {
                // a path that is no path (a NUL in it) is told like a file that cannot be read
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot open " + path + ": " + why, false));
            }
        });
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
        cmd("panel.new", "New panel", key(KeyEvent.VK_T, CMD), () -> workspace.newPanel());
        cmd("panel.openSpec", "Open file…", key(KeyEvent.VK_O, CMD), this::openSpec);
        cmd("panel.reopen", "Reopen closed panel", key(KeyEvent.VK_T, CMD | SHIFT), this::reopenPanel, () -> workspace.hasClosed());
        // ⌘S saves the focused panel wherever the keyboard is; exporting a view is ⌘⇧S
        cmd("panel.save", "Save", key(KeyEvent.VK_S, CMD), this::savePanel);
        cmd("panel.rename", "Rename panel…", null, () -> {
            EditorPanel p = workspace.focused();
            if (p != null) {
                workspace.rename(p);
            }
        });
        // ⌘W closes the view that has the keyboard, else the panel
        cmd("file.close", "Close", key(KeyEvent.VK_W, CMD), () -> {
            long view = focusedView();
            if (view >= 0) {
                deps.intents().dispatch(new Intent.CloseView(view));
            } else {
                closePanel(focusedId());
            }
        });
        cmd("view.export", "Export view…", key(KeyEvent.VK_S, CMD | SHIFT), this::export, () -> capability(Exportable.class) != null);
        // edit: each forwards its key to the focused component's own binding, so the menu shows the keys the
        // components already have; a component without the binding (the terminal) gets the key itself
        edit("edit.undo", "Undo", key(KeyEvent.VK_Z, CMD));
        edit("edit.redo", "Redo", key(KeyEvent.VK_Z, CMD | SHIFT));
        edit("edit.cut", "Cut", key(KeyEvent.VK_X, CMD));
        edit("edit.copy", "Copy", key(KeyEvent.VK_C, CMD));
        edit("edit.paste", "Paste", key(KeyEvent.VK_V, CMD));
        edit("edit.selectAll", "Select all", key(KeyEvent.VK_A, CMD));
        cmd("view.copy", "Copy view content", key(KeyEvent.VK_C, CMD | SHIFT), () -> {
            Copyable c = capability(Copyable.class);
            if (c != null && Capabilities.copy(c)) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Copied the view's content", false));
            }
        }, () -> {
            Copyable c = capability(Copyable.class);
            return c != null && c.canCopy();
        });
        cmd("view.find", "Find…", key(KeyEvent.VK_F, CMD), () -> findBar.open(capability(Searchable.class)),
                () -> capability(Searchable.class) != null);
        cmd("view.findNext", "Find next", key(KeyEvent.VK_G, CMD), () -> findBar.next(true), () -> findBar.isOpen());
        cmd("view.findPrevious", "Find previous", key(KeyEvent.VK_G, CMD | SHIFT), () -> findBar.next(false), () -> findBar.isOpen());
        KeyStroke complete = key(KeyEvent.VK_SPACE, CTRL);
        cmd("edit.complete", "Complete SQL", complete, () -> Keys.forward(editorArea(), complete, true),
                () -> editorFocused() && Keys.forward(editorArea(), complete, false));
        // ⌥⎋ as well, as in macOS text views: ⌃Space is the system's input-source switch on many Macs
        cmd("edit.completeAlt", "Complete SQL", key(KeyEvent.VK_ESCAPE, ALT), () -> Keys.forward(editorArea(), complete, true),
                () -> editorFocused() && Keys.forward(editorArea(), complete, false));
        cmd("panel.preview", "Edit / Preview Markdown", key(KeyEvent.VK_M, CMD | SHIFT), () -> {
            EditorPanel p = workspace.focused();
            if (p != null) {
                p.togglePreview();
            }
        });
        // panel: running
        cmd("run", "Run", key(KeyEvent.VK_ENTER, CMD), () -> run(Target.AUTO), this::canRun);
        cmd("run.claude", "Send to Claude", key(KeyEvent.VK_ENTER, CMD | SHIFT), () -> run(Target.CLAUDE), this::canRun);
        cmd("run.sql", "Run as SQL", key(KeyEvent.VK_ENTER, ALT), () -> run(Target.SQL), this::canRun);
        cmd("terminal.run", "Run in the terminal", key(KeyEvent.VK_ENTER, CTRL), this::runInTerminal, this::editorFocused);
        cmd("run.stop", "Stop", key(KeyEvent.VK_PERIOD, CMD), () -> deps.intents().dispatch(new Intent.CancelRun(focusedId())),
                this::canStop);
        // panel: its session
        cmd("session.toggle", "Connect or disconnect", null, () -> focusedSession().ifPresent(x ->
                deps.intents().dispatch(x.status().isUsable() ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name()))),
                () -> focusedSession().isPresent());
        cmd("session.interrupt", "Interrupt the turn", key(KeyEvent.VK_PERIOD, CMD | SHIFT),
                () -> focusedSession().ifPresent(x -> deps.intents().dispatch(new Intent.InterruptSession(x.name()))),
                () -> focusedSession().map(SessionState::isBusy).orElse(false));
        // as the panel's and the row's buttons do: only a running session has a conversation to compact
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
        cmd("session.dialog", "Session settings…", key(KeyEvent.VK_K, CMD | SHIFT), () -> openSessions(deps.state().get().focusedPanel().session()));
        cmd("session.import", "Import conversations…", null, this::openImport);
        // panel: its connection
        cmd("connection.toggle", "Connect or disconnect", null, () -> {
            AppState s = deps.state().get();
            s.connection(s.focusedPanel().connection()).ifPresent(c -> deps.intents().dispatch(c.status().isUsable()
                    ? new Intent.CloseConnection(c.name()) : new Intent.OpenConnection(c.name())));
        }, () -> !deps.state().get().focusedPanel().connection().isEmpty());
        cmd("connection.schema", "Explore the schema", key(KeyEvent.VK_E, CMD | SHIFT),
                () -> exploreSchema(deps.state().get().focusedPanel().connection()),
                () -> !deps.state().get().focusedPanel().connection().isEmpty());
        cmd("panel.bindConnection", "Bind a connection…", null, () -> choose("Connection for this panel",
                deps.state().get().connections().stream().map(x -> x.name()).toList(),
                name -> deps.intents().dispatch(new Intent.BindConnection(deps.state().get().focused(), name))));
        cmd("connection.dialog", "Connection settings…", key(KeyEvent.VK_O, CMD | SHIFT), () -> openConnections(deps.state().get().focusedPanel().connection()));
        if (deps.server() != null) {
            cmd("server.start", "Start", null, () -> deps.server().toggle(), () -> !deps.server().isRunning());
            cmd("server.stop", "Stop", null, () -> deps.server().toggle(), () -> deps.server().isRunning());
        }
        // panel: its results
        cmd("run.insertResult", "Attach to the editor", key(KeyEvent.VK_I, CMD | SHIFT), this::insertLastResult, this::hasRun);
        cmd("run.keep", "Keep in a tab", key(KeyEvent.VK_R, CMD | SHIFT), this::keepResult, this::hasRun);
        // always claimed: the editor's own ⌘J joins lines, which the user never means here; without a run it says so
        cmd("run.plot", "Plot", key(KeyEvent.VK_J, CMD), this::plotLastResult);
        cmd("run.plotLive", "Plot, refreshing…", key(KeyEvent.VK_J, CMD | SHIFT), this::plotLive, this::hasRun);
        cmd("run.plotStop", "Stop refreshing plots", null, livePlots::stopAll, () -> livePlots.size() > 0);
        // panel: tools that run in its project
        cmd("tools.gradle", "Run Gradle tasks…", key(KeyEvent.VK_G, CMD | ALT), this::runGradle, () -> focusedSession().isPresent());
        cmd("tools.settings", "Tool settings", null, this::openToolSettings);
        cmd("files.settings", "Files tab folders", null, this::openFolderSettings);
        cmd("terminal.restart", "Restart the shell", null, () -> lower.terminals().restartFocused(deps.state().get()));
        // view
        cmd("view.palette", "Command palette", key(KeyEvent.VK_P, CMD | SHIFT), () -> new CommandPalette(actions).show(frame));
        cmd("focus.editor", "Focus the editor", key(KeyEvent.VK_E, CMD), () -> {
            EditorPanel p = workspace.focused();
            if (p != null) {
                p.editor().requestFocus();
            }
        });
        cmd("view.sidebar", "Sidebar", key(KeyEvent.VK_B, CMD), this::toggleSidebar);
        cmd("view.sidePane", "Side pane", key(KeyEvent.VK_BACK_SLASH, CMD), () -> {
            sidePaneHidden = sidePaneShown;
            render();
        }, () -> sidePaneShown || side.hasViews());
        cmd("view.files", "Files", null, () -> {
            if (!sidebar.isShown()) {
                toggleSidebar();
            }
            sidebar.selectTab(SessionsSidebar.FILES);
            sidebar.focusFilesFilter();
        });
        cmd("view.restoreLayout", "Restore layout", null, this::restoreArranged, () -> beforeArrange != null);
        // ⌃1–⌃4 follow the lower tabs' order: Session, Approvals, Terminal, Results
        cmd("view.session", "Session", key(KeyEvent.VK_1, CTRL), () -> showLowerAndFocus(LowerTab.SESSION, lower.transcript().canvas()));
        cmd("view.approvals", "Approvals", key(KeyEvent.VK_2, CTRL),
                () -> showLowerAndFocus(LowerTab.APPROVALS, lower.inbox().component(), lower.inbox()::focusFirst));
        cmd("view.terminal", "Terminal", key(KeyEvent.VK_3, CTRL), () -> {
            deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.TERMINAL));
            Edt.later(() -> lower.terminals().focusShown(deps.state().get()));
        });
        cmd("view.results", "Results", key(KeyEvent.VK_4, CTRL), () -> showLowerAndFocus(LowerTab.RESULTS, lower.results().grid().table()));
        // ⌘⌥1–4 as well: macOS Mission Control takes ⌃1–⌃4 when its desktop shortcuts are on
        String[] lowerTabs = {"view.session", "view.approvals", "view.terminal", "view.results"};
        for (int i = 0; i < lowerTabs.length; i++) {
            String target = lowerTabs[i];
            cmd(target + ".alt", actions.get(target).label() + " tab", key(KeyEvent.VK_1 + i, CMD | ALT), () -> actions.run(target));
        }
        cmd("view.nextTab", "Next tab", key(KeyEvent.VK_TAB, CTRL), () -> lower.selectRelative(1));
        cmd("view.previousTab", "Previous tab", key(KeyEvent.VK_TAB, CTRL | SHIFT), () -> lower.selectRelative(-1));
        cmd("view.closeLower", "Close the view in front", key(KeyEvent.VK_W, CMD | SHIFT),
                () -> deps.intents().dispatch(new Intent.CloseView(lower.selectedView())), () -> lower.selectedView() >= 0);
        cmd("panel.next", "Next panel", key(KeyEvent.VK_CLOSE_BRACKET, CMD | SHIFT), () -> workspace.selectRelative(1));
        cmd("panel.previous", "Previous panel", key(KeyEvent.VK_OPEN_BRACKET, CMD | SHIFT), () -> workspace.selectRelative(-1));
        for (int i = 1; i <= 9; i++) {
            int index = i - 1;
            cmd("panel." + i, "Go to panel " + i, key(KeyEvent.VK_0 + i, CMD), () -> workspace.select(index));
        }
        cmd("view.zoomIn", "Zoom in", key(KeyEvent.VK_EQUALS, CMD), () -> zoom(1), () -> capability(Zoomable.class) != null);
        cmd("view.zoomOut", "Zoom out", key(KeyEvent.VK_MINUS, CMD), () -> zoom(-1), () -> capability(Zoomable.class) != null);
        cmd("view.zoomReset", "Reset zoom", key(KeyEvent.VK_0, CMD), () -> {
            Zoomable z = capability(Zoomable.class);
            if (z != null) {
                z.resetZoom();
            }
        }, () -> capability(Zoomable.class) != null);
        cmd("view.notices", "Recent notices", null, () -> status.showHistory());
        // help
        cmd("help.tutorial", "Tutorial", null, this::showTutorial, () -> deps.home() != null);
        // ⌘/ stays the editor's comment toggle; ⌘⇧/ is the Mac's Help key
        cmd("help.keys", "Keyboard shortcuts", key(KeyEvent.VK_SLASH, CMD | SHIFT), this::showKeys);
        cmd("help.markdown", "Markdown sheet", null, this::showMarkdownSheet);
        cmd("help.kit", "Claude Code kit…", null, this::showKit);
        cmd("help.log", "Show the log", null, this::showLog, () -> deps.logDir() != null);
        cmd("help.dataRoot", "Reveal comando folder", null, () -> reveal(deps.home()), () -> deps.home() != null);
        // on macOS the application menu owns ⌘Q and calls the quit handler; a second binding would quit twice
        boolean mac = Platform.isMac();
        cmd("app.about", "About comando", null, this::showAbout);
        cmd("app.quit", "Quit comando", mac ? null : key(KeyEvent.VK_Q, CMD), this::quit);
    }

    private void cmd(String id, String label, KeyStroke key, Runnable run) {
        actions.register(Command.of(id, label, Menus.icon(id), key, "", run));
    }

    private void cmd(String id, String label, KeyStroke key, Runnable run, BooleanSupplier enabled) {
        actions.register(Command.of(id, label, Menus.icon(id), key, "", run, enabled));
    }

    /** An Edit command: runs the focused component's own action for {@code key}; enabled when it has one. */
    private void edit(String id, String label, KeyStroke key) {
        cmd(id, label, key, () -> Keys.forward(focusOwnerInFrame(), key, true), () -> Keys.forward(focusOwnerInFrame(), key, false));
    }

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    private JMenuBar menuBar() {
        return actions.menuBar(Menus.bar(deps.server() != null), Menus::tip, Menus.MENU_TIPS::get);
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
        for (PanelState p : deps.state().get().panels()) {
            if (deps.texts().file(p.id()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                deps.intents().dispatch(new Intent.FocusPanel(p.id()));
                return;
            }
        }
        // the file of a closed panel: the panel comes back, with its bindings
        for (PanelState p : deps.state().get().closed()) {
            if (deps.texts().file(p.id()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                deps.intents().dispatch(new Intent.ReopenPanel(p.id()));
                return;
            }
        }
        if (!openingFiles.add(file)) {
            return;
        }
        Thread.ofVirtual().name("open-file").start(() -> {
            if (!OpenFiles.isText(file)) {
                deps.intents().dispatch(new Intent.OpenFile("", file.toString(), 0));
                Edt.later(() -> openingFiles.remove(file));
                return;
            }
            PanelId id = PanelId.random();
            // the title the store gives a panel opened on a file, so the file keeps its name
            String title = PanelTexts.titleOf(file);
            deps.texts().open(id, file).thenAccept(opened -> {
                switch (opened.kind()) {
                    case BOUND -> deps.intents().dispatch(new Intent.AddPanel(id, title, deps.texts().document(id)));
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
                    : new Intent.Notify(Notice.Level.ERROR, "Cannot save the panels: " + error, false)));
        }
    }

    /** Closes a panel; asks first when its shell runs a job in front, which closing would end. */
    private void closePanel(PanelId id) {
        Optional<String> job = lower.terminals().runningJob(id);
        // the last panel is never closed: the reducer says so, with nothing asked first
        if (job.isPresent() && deps.state().get().panels().size() > 1) {
            String title = deps.state().get().panel(id).map(x -> x.title()).orElse("the panel");
            String why = job.get().equals(TerminalWidget.UNCHECKED_JOB)
                    ? "comando cannot tell whether its shell is running a program; closing the panel ends anything it runs."
                    : "Its shell is running " + job.get() + "; closing the panel ends it.";
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
        Path dir = Path.of(prefs.get("export.dir", System.getProperty("user.home")));
        Capabilities.export(frame, e, dir, (file, error) -> {
            if (error == null) {
                prefs.put("export.dir", file.toAbsolutePath().getParent().toString());
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Exported to " + file, false));
            } else {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot export to " + file + ": " + error, false));
            }
        });
    }

    // ---- help

    /** The user asked for the explorer: it shows, even in a side pane the user hid earlier. */
    private void exploreSchema(String connection) {
        sidePaneHidden = false;
        deps.intents().dispatch(new Intent.ExploreSchema(connection));
    }

    /** The tutorial in a panel, as a document: its file in comando's folder, written from this build's copy first. */
    private void showTutorial() {
        Path home = deps.home();
        Thread.ofVirtual().name("show-tutorial").start(() -> {
            try {
                Path file = HelpFiles.writeTutorial(home);
                Edt.later(() -> openFile(file));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot show the tutorial: " + e.getMessage(), false));
            }
        });
    }

    /** Opens {@code tools.json} in a panel, as any document, written with its defaults first when missing. */
    private void openToolSettings() {
        Thread.ofVirtual().name("tool-settings").start(() -> {
            try {
                Path file = deps.setup().toolSettings();
                Edt.later(() -> openFile(file));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot open the tool settings: " + e.getMessage(), false));
            }
        });
    }

    /**
     * Opens {@code folders.json} in a panel, as any document, written with the tab's folders first when missing; the
     * tab follows the file as it is saved.
     */
    private void openFolderSettings() {
        List<FolderRoot> shown = deps.state().get().folders();
        Thread.ofVirtual().name("folder-settings").start(() -> {
            try {
                Path file = deps.setup().folderSettings(shown);
                Edt.later(() -> openFile(file));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot open the Files tab's folders: " + e.getMessage(), false));
            }
        });
    }

    /**
     * About comando: the card of {@link About}, over the window; when it is open already it comes forward. The Claude
     * Code version is the one the most recent session reported; before any session started there is none to show.
     */
    public void showAbout() {
        if (about != null && about.isDisplayable()) {
            about.toFront();
            return;
        }
        String claude = deps.state().get().sessions().stream().filter(s -> !s.info().cliVersion().isEmpty())
                .max(Comparator.comparingLong(SessionState::startedMillis)).map(s -> s.info().cliVersion()).orElse("");
        about = About.show(frame, About.load(About.facts(deps.version(), deps.home(), claude)), this::followAbout);
    }

    /** A link of the About card: the tutorial, the data root, or a page in the browser. */
    private void followAbout(String target) {
        switch (target) {
            case "comando:tutorial" -> actions.run("help.tutorial");
            case "comando:home" -> {
                if (deps.home() != null) {
                    reveal(deps.home());
                }
            }
            default -> {
                if (Browser.isWebLink(target)) {
                    Browser.open(target);
                }
            }
        }
    }

    /** Tools ▸ Help ▸ Claude Code kit: what the kit is here, and an install when there is anything to install. */
    private void showKit() {
        Thread.ofVirtual().name("kit").start(() -> {
            try {
                Setup.KitOffer offer = deps.setup().kit();
                Edt.later(() -> askKit(offer, false));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot read the Claude Code kit's state: " + e.getMessage(), false));
            }
        });
    }

    /**
     * Offers the kit when comando starts: once the window shows, and unless the user said not to ask again. Any
     * thread.
     */
    public void offerKit(Setup.KitOffer offer) {
        Edt.later(() -> {
            if (frame != null && prefs.getBoolean(KIT_OFFER, true)) {
                askKit(offer, true);
            }
        });
    }

    private void askKit(Setup.KitOffer found, boolean atStart) {
        Setup.KitOffer offer = found.withFolders(Setup.missingFolders(deps.state().get().folders(), deps.setup().kitFolders()).stream()
                .map(f -> "Add " + f.name() + " (" + HomeDir.shorten(f.path()) + ") to the Files tab").toList());
        Object[] options = offer.buttons(atStart).toArray();
        int pick = JOptionPane.showOptionDialog(frame, Plain.text(offer.message()), "Claude Code kit", JOptionPane.DEFAULT_OPTION,
                offer.pending() ? JOptionPane.QUESTION_MESSAGE : JOptionPane.INFORMATION_MESSAGE, null, options,
                options[atStart && options.length > 1 ? 1 : 0]);
        if (atStart && pick == Setup.DONT_ASK) {
            prefs.putBoolean(KIT_OFFER, false);
        }
        if (!offer.pending() || pick != Setup.DO) {
            return;
        }
        Thread.ofVirtual().name("kit-install").start(() -> {
            try {
                List<String> said = new ArrayList<>(deps.setup().installKit());
                List<String> added = addKitFolders();
                if (!added.isEmpty()) {
                    said.add("added " + String.join(" and ", added) + " to the Files tab");
                }
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, said.isEmpty()
                        ? "The Claude Code kit was already in place"
                        : "Claude Code kit: " + String.join("; ", said) + ". New Claude sessions load it.", false));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "The Claude Code kit install stopped: " + e.getMessage(), false));
            }
        });
    }

    /** Adds the kit's folders the Files tab lacks; their names. */
    private List<String> addKitFolders() {
        List<String> added = new ArrayList<>();
        for (FolderRoot f : Setup.missingFolders(deps.state().get().folders(), deps.setup().kitFolders())) {
            deps.intents().dispatch(new Intent.AddFolder(f));
            added.add(f.name());
        }
        return added;
    }

    /** Every command with a key, by menu, as a table in the side pane. */
    private void showKeys() {
        sidePaneHidden = false;
        deps.intents().dispatch(new Intent.ShowView("", "Keyboard shortcuts", new ViewSpec.Markdown(Keys.markdown(actions.all())), Placement.SIDE));
    }

    /** The Markdown sheet in the side pane, as the keyboard shortcuts are, to keep beside the editor. */
    private void showMarkdownSheet() {
        sidePaneHidden = false;
        deps.intents().dispatch(new Intent.ShowView("", "Markdown", new ViewSpec.Markdown(MarkdownSheet.markdown()), Placement.SIDE));
    }

    private void reveal(Path dir) {
        Thread.ofVirtual().name("reveal").start(() -> {
            try {
                Desktop.getDesktop().open(dir.toFile());
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot open " + dir + ": " + e.getMessage(), false));
            }
        });
    }

    /** The log comando writes now, in a panel, as a document: read-only, following its end, reloaded as it grows. */
    private void showLog() {
        Path log = HelpFiles.log(deps.logDir());
        if (Files.isRegularFile(log)) {
            openFile(log);
        } else {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "No log yet at " + log, false));
        }
    }

    /** Asks for Gradle tasks, naming the build they run in; the last ones are remembered across restarts. */
    private void runGradle() {
        String dir = focusedSession().map(x -> Labels.shortPath(x.spec().workDir())).orElse("the session's directory");
        String tasks = (String) JOptionPane.showInputDialog(frame, Plain.text("Tasks and options, run in " + dir + ":"),
                "Run Gradle", JOptionPane.PLAIN_MESSAGE, null, null, prefs.get("tools.gradle", "build"));
        if (tasks != null && !tasks.isBlank()) {
            prefs.put("tools.gradle", tasks.strip());
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
            prefs.putInt("divider.sidebar", outer.getDividerLocation());
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
        outer.setDividerLocation(shown ? prefs.getInt("divider.sidebar", 260) : sidebar.railWidth());
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

    /**
     * Restores the window's bounds, kept on a screen that still exists (a window saved on a detached display opens
     * centred on the main one), the dividers and the sidebar; maximized is restored once the window shows.
     */
    private void restoreLayout() {
        int w = prefs.getInt("window.width", 1500);
        int h = prefs.getInt("window.height", 950);
        int x = prefs.getInt("window.x", Integer.MIN_VALUE);
        int y = prefs.getInt("window.y", Integer.MIN_VALUE);
        Rectangle saved = new Rectangle(x, y, w, h);
        Rectangle screen = onScreen(saved);
        if (x != Integer.MIN_VALUE && screen != null) {
            Rectangle fit = saved.intersection(screen);
            frame.setBounds(fit.width < 400 || fit.height < 300 ? new Rectangle(screen.x, screen.y, Math.min(w, screen.width),
                    Math.min(h, screen.height)) : fit);
        } else {
            frame.setSize(w, h);
            frame.setLocationRelativeTo(null);
        }
        sidebar.selectTab(prefs.getInt("sidebar.tab", SessionsSidebar.SESSIONS));
        sidebar.manualOrder(prefs.getBoolean("sessions.manualOrder", false));
        workspace.light(prefs.getBoolean("editor.light", false));
        showSidebar(prefs.getBoolean("sidebar.visible", true));
        vertical.setDividerLocation(prefs.getInt("divider.lower", (int) (h * 0.45)));
        sidePaneHidden = prefs.getBoolean("sidePane.hidden", false);
    }

    /** The bounds of the screen {@code r} overlaps most, or null when it is on none. */
    private static Rectangle onScreen(Rectangle r) {
        Rectangle best = null;
        long bestArea = 0;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle b = d.getDefaultConfiguration().getBounds();
            Rectangle i = b.intersection(r);
            long area = i.isEmpty() ? 0 : (long) i.width * i.height;
            if (area > bestArea) {
                best = b;
                bestArea = area;
            }
        }
        return best;
    }

    private void saveLayout() {
        boolean maximized = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        prefs.putBoolean("window.maximized", maximized);
        if (!maximized) {
            prefs.putInt("window.width", frame.getWidth());
            prefs.putInt("window.height", frame.getHeight());
            prefs.putInt("window.x", frame.getX());
            prefs.putInt("window.y", frame.getY());
        }
        if (sidebar.isShown()) {
            prefs.putInt("divider.sidebar", outer.getDividerLocation());
        }
        prefs.putBoolean("sidebar.visible", sidebar.isShown());
        prefs.putInt("sidebar.tab", sidebar.selectedTab());
        prefs.putBoolean("sessions.manualOrder", sidebar.manualOrder());
        prefs.putBoolean("sidePane.hidden", sidePaneHidden);
        prefs.putInt("divider.lower", vertical.getDividerLocation());
        if (sidePaneShown) {
            prefs.putInt("divider.side", editorAndSide.getDividerLocation());
        }
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
            frame.dispose();
        }, CLOSE_BOUND);
        if (!closed) {
            LOG.log(System.Logger.Level.WARNING, "the window did not close within " + CLOSE_BOUND.toSeconds()
                    + " s: the event thread is busy; quitting without it");
        }
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
        int jobs = lower.terminals().runningJobs();
        if (jobs > 0) {
            work.add(jobs + (jobs == 1 ? " shell job" : " shell jobs"));
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
                if (!confirm("Quit comando?", "Quitting stops " + String.join(", ", work) + ".", "Quit")) {
                    pendingQuits.forEach(QuitResponse::cancelQuit);
                    pendingQuits.clear();
                    return false;
                }
            } finally {
                confirmingQuit = false;
            }
        }
        quitting = true;
        saveLayoutQuietly();
        teardown();
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
