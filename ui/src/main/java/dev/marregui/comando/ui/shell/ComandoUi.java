package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
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
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.PromptHistory;
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
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.kit.format.Labels;
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

import javax.swing.SwingUtilities;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * The Swing shell and the UI port. Renders the state on the EDT, at most once per pending change; builds
 * the menus, key bindings and palette from one action registry; owns the editor text, the dialogs and
 * desktop notifications.
 */
public final class ComandoUi implements UiPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ComandoUi.class.getName());
    private static final int CMD = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

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
            PromptHistory prompts,
            Supplier<List<StoredConversation>> catalogue,
            ServerToggle server,
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
    private JSplitPane vertical;
    private JSplitPane editorAndSide;
    private JPanel top;
    private Notifier notifier;
    private ResourceDialog<ConnectionSpec> connectionsDialog;
    private ResourceDialog<SessionSpec> sessionsDialog;
    private ImportSessionsDialog importDialog;
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
    private final java.awt.event.AWTEventListener keyClock = e -> {
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
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler((event, response) -> SwingUtilities.invokeLater(() -> {
                // a trigger that arrives while the question is open is answered with it
                pendingQuits.add(response);
                if (!confirmingQuit && !quit()) {
                    pendingQuits.forEach(java.awt.desktop.QuitResponse::cancelQuit);
                    pendingQuits.clear();
                }
            }));
        }
        notifier = new Notifier(frame);
        livePlots = new LivePlots(deps.intents(), deps.results());
        Thread.ofVirtual().name("pty-preload").start(TerminalWidget::preload);
        workspace = new Workspace(deps.intents(), deps.texts(), deps.schemas(), deps.prompts());
        workspace.onClose(this::closePanel);
        deps.schemas().addListener(schemaListener);
        lower = new LowerPanel(ctx, deps.results(), deps.transcripts(), widgets);
        side = new SidePane(ctx, widgets);
        sidebar = new SessionsSidebar(deps.intents(), new SessionsSidebar.Dialogs() {
            @Override
            public void sessions(String select) {
                openSessions(select);
            }

            @Override
            public void importSessions() {
                openImport();
            }

            @Override
            public void connections(String select) {
                openConnections(select);
            }

            @Override
            public void exploreSchema(String connection) {
                ComandoUi.this.exploreSchema(connection);
            }
        });
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
                Intent.RemoveConnection::new,
                Intent.TestConnection::new,
                Intent.OpenConnection::new,
                Intent.CloseConnection::new,
                (s, name) -> new Intent.BindConnection(s.focused(), name),
                "database", null, null), deps.intents());
        sessionsDialog = new ResourceDialog<>(frame, new ResourceDialog.Config<>(
                SessionKind.INSTANCE,
                s -> s.sessions().stream().map(SessionState::spec).toList(),
                s -> statuses(s.sessions().stream().map(x -> Map.entry(x.name(), x.status())).toList()),
                Intent.SaveSession::new,
                Intent.RemoveSession::new,
                null,
                Intent.StartSession::new,
                Intent.StopSession::new,
                (s, name) -> new Intent.BindSession(s.focused(), name),
                "claude", this::openImport, "Import…"), deps.intents());
        importDialog = new ImportSessionsDialog(frame, deps.intents(), deps.catalogue());

        registerCommands();
        frame.setJMenuBar(menuBar());
        actions.bind(frame.getRootPane());
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
        sidebarTimer = new Timer(30_000, e -> sidebar.tick());
        sidebarTimer.start();
        tickTimer = new Timer(1_000, e -> tick());
        tickTimer.start();
        EditorPanel p = workspace.focused();
        if (p != null) {
            p.editor().requestFocus();
        }
        // started last: a probe posted during the build waits for the rest of it and reports the build itself
        watchdog = new EdtWatchdog((millis, stack) -> LOG.log(System.Logger.Level.WARNING,
                "the EDT has not answered for " + millis + " ms:\n" + stack), this::stalled);
    }

    /** A stall of the EDT ended after {@code millis}: the user hears of one that lasted a second or more. */
    private void stalled(long millis) {
        LOG.log(System.Logger.Level.WARNING, "the EDT answered again after " + millis + " ms");
        if (millis >= 1_000) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN,
                    "The window stopped responding for " + Labels.elapsed(millis) + "; the log has where (Help ▸ Show the log)", false));
        }
    }

    /** Once a second: elapsed times on the panels and the results, the embedded server's state. */
    private void tick() {
        workspace.tick();
        lower.results().tick();
        if (deps.server() != null) {
            status.server(deps.server().name(), deps.server().isRunning(), () -> deps.server().toggle());
        }
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
        Map<Long, Long> versions = new java.util.HashMap<>();
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
        if (focusAfterRender != null && focusAfterRender.shown().isShowing()) {
            Runnable focus = focusAfterRender.focus();
            focusAfterRender = null;
            Edt.later(focus);
        }
        if (findBar.isOpen() && !findBar.targetShowing()) {
            findBar.close(); // the view it searched was closed
        }
        actions.refresh();
        frame.setTitle("comando — " + s.focusedPanel().title()
                + (s.focusedPanel().session().isEmpty() ? "" : " · " + s.focusedPanel().session()));
    }

    private void showSidePane(boolean show) {
        sidePaneShown = show;
        top.removeAll();
        if (show) {
            editorAndSide.setLeftComponent(workspace.component());
            editorAndSide.setRightComponent(side.component());
            top.add(editorAndSide, BorderLayout.CENTER);
            // place the divider once the split has a size; a stored position that would hide either side is ignored
            SwingUtilities.invokeLater(() -> {
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
                notifier.notify(title, text);
            }
        });
    }

    @Override
    public void forgetPanel(PanelId panel) {
        deps.texts().forget(panel);
        Edt.run(() -> {
            if (lower != null) {
                lower.terminals().forget(panel);
            }
        });
    }

    @Override
    public void showFile(String session, String path, String baseDir, int line) {
        Thread.ofVirtual().name("show-file").start(() -> {
            Path p = path.startsWith("~/") ? Path.of(System.getProperty("user.home"), path.substring(2)) : Path.of(path);
            if (!p.isAbsolute()) {
                p = Path.of(baseDir).resolve(p);
            }
            try {
                if (Files.size(p) > MAX_FILE_BYTES) {
                    deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "File larger than 4 MB: " + p, false));
                    return;
                }
                String text = Files.readString(p, StandardCharsets.UTF_8);
                // titled with the parent folder, so two files with one name (two READMEs) get two tabs
                Path parent = p.getParent() == null ? null : p.getParent().getFileName();
                String name = (parent == null ? "" : parent + "/") + p.getFileName();
                int dot = name.lastIndexOf('.');
                String ext = dot < 0 || name.lastIndexOf('/') > dot ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                ViewSpec spec = ext.equals("md") && line <= 0 ? new ViewSpec.Markdown(text) : new ViewSpec.Text(p.toString(), text, ext, line);
                // a file opens on the user's click only: it shows even in a side pane the user hid earlier, and even when
                // it replaces the same file's view in place
                Edt.run(() -> {
                    sidePaneHidden = false;
                    deps.intents().dispatch(new Intent.ShowView(session, name, spec, Placement.SIDE));
                    render.request();
                });
            } catch (IOException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot open " + p + ": " + e.getMessage(), false));
            }
        });
    }

    @Override
    public void arrange(Layout layout) {
        Edt.run(() -> arrangeWhenIdle(layout));
    }

    /** Applies a session's layout once the user has not typed for two seconds, so panes never move under the keys. */
    private void arrangeWhenIdle(Layout layout) {
        if (frame == null) {
            return;
        }
        long idle = (System.nanoTime() - lastKeyNanos) / 1_000_000;
        if (lastKeyNanos != 0 && idle < TYPING_PAUSE_MILLIS) {
            Timer later = new Timer((int) (TYPING_PAUSE_MILLIS - idle) + 50, e -> arrangeWhenIdle(layout));
            later.setRepeats(false);
            later.start();
            return;
        }
        if (beforeArrange == null) {
            // Restore layout goes back to before the first arrangement the user has not undone
            beforeArrange = new LayoutSnapshot(sidebar.component().isVisible(), sidePaneHidden, vertical.getDividerLocation());
        }
        applyLayout(layout);
    }

    private void applyLayout(Layout layout) {
        if (layout.sidebar() != Layout.Toggle.KEEP && sidebar.component().isVisible() != (layout.sidebar() == Layout.Toggle.SHOW)) {
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
        if (sidebar.component().isVisible() != b.sidebar()) {
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

    private void registerCommands() {
        // edit: each forwards its key to the focused component's own binding, so the menu shows the keys the
        // components already have; a component without the binding (the terminal) gets the key itself
        edit("edit.undo", "Undo", key(KeyEvent.VK_Z, CMD));
        edit("edit.redo", "Redo", key(KeyEvent.VK_Z, CMD | SHIFT));
        edit("edit.cut", "Cut", key(KeyEvent.VK_X, CMD));
        edit("edit.copy", "Copy", key(KeyEvent.VK_C, CMD));
        edit("edit.paste", "Paste", key(KeyEvent.VK_V, CMD));
        edit("edit.selectAll", "Select all", key(KeyEvent.VK_A, CMD));
        cmd("view.find", "Find…", "search", key(KeyEvent.VK_F, CMD), "Edit", () -> findBar.open(capability(Searchable.class)),
                () -> capability(Searchable.class) != null);
        cmd("view.findNext", "Find next", null, key(KeyEvent.VK_G, CMD), "Edit", () -> findBar.next(true), () -> findBar.isOpen());
        cmd("view.findPrevious", "Find previous", null, key(KeyEvent.VK_G, CMD | SHIFT), "Edit", () -> findBar.next(false),
                () -> findBar.isOpen());
        KeyStroke complete = key(KeyEvent.VK_SPACE, CTRL);
        cmd("edit.complete", "Complete SQL", null, complete, "Edit", () -> Keys.forward(editorArea(), complete, true),
                () -> editorFocused() && Keys.forward(editorArea(), complete, false));
        // ⌥⎋ as well, as in macOS text views: ⌃Space is the system's input-source switch on many Macs
        cmd("edit.completeAlt", "Complete SQL", null, key(KeyEvent.VK_ESCAPE, ALT), "", () -> Keys.forward(editorArea(), complete, true),
                () -> editorFocused() && Keys.forward(editorArea(), complete, false));
        // run
        cmd("run", "Run selection or block", "play", key(KeyEvent.VK_ENTER, CMD), "Run", () -> run(Target.AUTO), this::canRun);
        cmd("run.claude", "Send selection or block to Claude", "claude", key(KeyEvent.VK_ENTER, CMD | SHIFT), "Run",
                () -> run(Target.CLAUDE), this::canRun);
        // ⌥⏎ in the prompt bar is a new line, so the prompt bar keeps it
        cmd("run.sql", "Run selection or block as SQL", "database", key(KeyEvent.VK_ENTER, ALT), "Run", () -> run(Target.SQL),
                () -> canRun() && !promptFocused());
        cmd("terminal.run", "Run selection or line in the terminal", "terminal", key(KeyEvent.VK_ENTER, CTRL), "Run",
                this::runInTerminal, this::editorFocused);
        cmd("run.stop", "Stop query or interrupt turn", "stop", key(KeyEvent.VK_PERIOD, CMD), "Run",
                () -> deps.intents().dispatch(new Intent.CancelRun(focusedId())), this::canStop);
        cmd("run.insertResult", "Attach the result, selected rows or error to the editor", "attach", key(KeyEvent.VK_I, CMD | SHIFT), "Run",
                this::insertLastResult, this::hasRun);
        // always claimed: the editor's own ⌘J joins lines, which the user never means here; without a run it says so
        cmd("run.plot", "Plot last result", "chart", key(KeyEvent.VK_J, CMD), "Run", this::plotLastResult);
        cmd("run.plotLive", "Plot last result, refreshing…", "chart", key(KeyEvent.VK_J, CMD | SHIFT), "Run", this::plotLive, this::hasRun);
        cmd("run.plotStop", "Stop refreshing plots", "stop", null, "Run", livePlots::stopAll, () -> livePlots.size() > 0);
        cmd("run.keep", "Keep the result in a tab", "table", key(KeyEvent.VK_R, CMD | SHIFT), "Run", this::keepResult, this::hasRun);
        cmd("focus.prompt", "Focus the prompt bar", "claude", key(KeyEvent.VK_L, CMD), "Run", () -> {
            EditorPanel p = workspace.focused();
            if (p != null) {
                p.prompt().focus();
            }
        });
        cmd("focus.editor", "Focus the editor", "edit", key(KeyEvent.VK_E, CMD), "Run", () -> {
            EditorPanel p = workspace.focused();
            if (p != null) {
                p.editor().requestFocus();
            }
        });
        // panels
        cmd("panel.new", "New panel", "add", key(KeyEvent.VK_T, CMD), "Panels", () -> workspace.newPanel());
        cmd("panel.reopen", "Reopen closed panel", "refresh", key(KeyEvent.VK_T, CMD | SHIFT), "Panels", this::reopenPanel,
                () -> workspace.hasClosed());
        // ⌘S saves the focused panel wherever the keyboard is; exporting a view is ⌘⇧S
        cmd("panel.save", "Save panel", null, key(KeyEvent.VK_S, CMD), "Panels", this::savePanel);
        // ⌘W closes the view that has the focus, else the panel
        cmd("panel.close", "Close panel", "close", key(KeyEvent.VK_W, CMD), "Panels", () -> closePanel(focusedId()),
                () -> focusedView() < 0);
        cmd("panel.rename", "Rename panel", "edit", null, "Panels", () -> {
            EditorPanel p = workspace.focused();
            if (p != null) {
                workspace.rename(p);
            }
        });
        cmd("panel.bindSession", "Bind a session to the panel…", "claude", null, "Panels", () -> choose("Session for this panel",
                deps.state().get().sessions().stream().map(x -> x.name()).toList(),
                name -> deps.intents().dispatch(new Intent.BindSession(deps.state().get().focused(), name))));
        cmd("panel.bindConnection", "Bind a connection to the panel…", "database", null, "Panels", () -> choose("Connection for this panel",
                deps.state().get().connections().stream().map(x -> x.name()).toList(),
                name -> deps.intents().dispatch(new Intent.BindConnection(deps.state().get().focused(), name))));
        cmd("panel.next", "Next panel", null, key(KeyEvent.VK_CLOSE_BRACKET, CMD | SHIFT), "Panels",
                () -> workspace.selectRelative(1));
        cmd("panel.previous", "Previous panel", null, key(KeyEvent.VK_OPEN_BRACKET, CMD | SHIFT), "Panels",
                () -> workspace.selectRelative(-1));
        for (int i = 1; i <= 9; i++) {
            int index = i - 1;
            cmd("panel." + i, "Go to panel " + i, null, key(KeyEvent.VK_0 + i, CMD), "", () -> workspace.select(index));
        }
        // view
        cmd("view.results", "Show results", "table", key(KeyEvent.VK_1, CTRL), "View",
                () -> showLowerAndFocus(LowerTab.RESULTS, lower.results().grid().table()));
        cmd("view.session", "Show session transcript", "session", key(KeyEvent.VK_2, CTRL), "View",
                () -> showLowerAndFocus(LowerTab.SESSION, lower.transcript().canvas()));
        cmd("view.approvals", "Show approvals", "check", key(KeyEvent.VK_3, CTRL), "View",
                () -> showLowerAndFocus(LowerTab.APPROVALS, lower.inbox().component(), lower.inbox()::focusFirst));
        cmd("view.terminal", "Show the panel's terminal", "terminal", key(KeyEvent.VK_4, CTRL), "View", () -> {
            deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.TERMINAL));
            Edt.later(() -> lower.terminals().focusShown(deps.state().get()));
        });
        // ⌘⌥1–4 as well: macOS Mission Control takes ⌃1–⌃4 when its desktop shortcuts are on
        String[] lowerTabs = {"view.results", "view.session", "view.approvals", "view.terminal"};
        for (int i = 0; i < lowerTabs.length; i++) {
            String target = lowerTabs[i];
            cmd(target + ".alt", actions.get(target).label(), null, key(KeyEvent.VK_1 + i, CMD | ALT), "", () -> actions.run(target));
        }
        cmd("view.nextTab", "Next lower tab", null, key(KeyEvent.VK_TAB, CTRL), "View", () -> lower.selectRelative(1));
        cmd("view.previousTab", "Previous lower tab", null, key(KeyEvent.VK_TAB, CTRL | SHIFT), "View", () -> lower.selectRelative(-1));
        cmd("view.closeFocused", "Close view", "close", key(KeyEvent.VK_W, CMD), "View",
                () -> deps.intents().dispatch(new Intent.CloseView(focusedView())), () -> focusedView() >= 0);
        cmd("view.closeLower", "Close the lower view in front", null, key(KeyEvent.VK_W, CMD | SHIFT), "View",
                () -> deps.intents().dispatch(new Intent.CloseView(lower.selectedView())), () -> lower.selectedView() >= 0);
        cmd("terminal.restart", "Restart the panel's shell", "refresh", null, "View",
                () -> lower.terminals().restartFocused(deps.state().get()));
        cmd("view.sidebar", "Toggle sidebar", "sidebar", key(KeyEvent.VK_B, CMD), "View", this::toggleSidebar);
        cmd("view.sidePane", "Toggle side pane", null, key(KeyEvent.VK_BACK_SLASH, CMD), "View", () -> {
            sidePaneHidden = sidePaneShown;
            render();
        }, () -> sidePaneShown || side.component().getComponentCount() > 0);
        cmd("view.restoreLayout", "Restore layout", null, null, "View", this::restoreArranged, () -> beforeArrange != null);
        cmd("view.lastPrompt", "Jump to the last prompt", "session", null, "View", () -> {
            deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
            Edt.later(() -> lower.transcript().jumpToLastPrompt());
        }, () -> focusedSession().isPresent());
        cmd("view.notices", "Show recent notices", "info", null, "View", () -> status.showHistory());
        cmd("view.copy", "Copy view content", "copy", key(KeyEvent.VK_C, CMD | SHIFT), "View", () -> {
            Copyable c = capability(Copyable.class);
            if (c != null && Capabilities.copy(c)) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Copied the view's content", false));
            }
        }, () -> {
            Copyable c = capability(Copyable.class);
            return c != null && c.canCopy();
        });
        cmd("view.export", "Export view…", "export", key(KeyEvent.VK_S, CMD | SHIFT), "View", this::export,
                () -> capability(Exportable.class) != null);
        cmd("view.zoomIn", "Zoom in", null, key(KeyEvent.VK_EQUALS, CMD), "View", () -> zoom(1), () -> capability(Zoomable.class) != null);
        cmd("view.zoomOut", "Zoom out", null, key(KeyEvent.VK_MINUS, CMD), "View", () -> zoom(-1), () -> capability(Zoomable.class) != null);
        cmd("view.zoomReset", "Reset zoom", null, key(KeyEvent.VK_0, CMD), "View", () -> {
            Zoomable z = capability(Zoomable.class);
            if (z != null) {
                z.resetZoom();
            }
        }, () -> capability(Zoomable.class) != null);
        cmd("view.palette", "Command palette", "palette", key(KeyEvent.VK_P, CMD | SHIFT), "View",
                () -> new CommandPalette(actions).show(frame));
        // sessions
        cmd("session.dialog", "Sessions…", "session", key(KeyEvent.VK_K, CMD | SHIFT), "Sessions", () -> openSessions(""));
        cmd("session.import", "Import stored conversations…", "import", null, "Sessions", this::openImport);
        cmd("session.toggle", "Start or stop the panel's session", "play", null, "Sessions", () -> focusedSession().ifPresent(x ->
                deps.intents().dispatch(x.status().isUsable() ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name()))),
                () -> focusedSession().isPresent());
        cmd("session.interrupt", "Interrupt the turn", "interrupt", key(KeyEvent.VK_PERIOD, CMD | SHIFT), "Sessions",
                () -> focusedSession().ifPresent(x -> deps.intents().dispatch(new Intent.InterruptSession(x.name()))),
                () -> focusedSession().map(SessionState::isBusy).orElse(false));
        cmd("session.compact", "Compact the conversation", null, null, "Sessions", () -> sendCommand("/compact"),
                () -> focusedSession().isPresent());
        for (String m : List.of("fable", "opus", "sonnet", "haiku")) {
            cmd("session.model." + m, "Model: " + m, null, null, "", () -> focusedSession().ifPresent(x ->
                    deps.intents().dispatch(new Intent.SetSessionModel(x.name(), m))), () -> focusedSession().isPresent());
        }
        for (String e : List.of("low", "medium", "high", "xhigh", "max")) {
            cmd("session.effort." + e, "Effort: " + e, null, null, "", () -> focusedSession().ifPresent(x ->
                    deps.intents().dispatch(new Intent.SetSessionEffort(x.name(), e))), () -> focusedSession().isPresent());
        }
        // connections
        cmd("connection.dialog", "Connections…", "database", key(KeyEvent.VK_O, CMD | SHIFT), "Connections",
                () -> openConnections(""));
        cmd("connection.toggle", "Connect or disconnect the panel's connection", "plug", null, "Connections", () -> {
            AppState s = deps.state().get();
            s.connection(s.focusedPanel().connection()).ifPresent(c -> deps.intents().dispatch(c.status().isUsable()
                    ? new Intent.CloseConnection(c.name()) : new Intent.OpenConnection(c.name())));
        }, () -> !deps.state().get().focusedPanel().connection().isEmpty());
        cmd("connection.schema", "Explore the schema", "tree", key(KeyEvent.VK_E, CMD | SHIFT), "Connections",
                () -> exploreSchema(deps.state().get().focusedPanel().connection()),
                () -> !deps.state().get().focusedPanel().connection().isEmpty());
        if (deps.server() != null) {
            String name = deps.server().name();
            cmd("server.start", "Start the embedded " + name, "play", null, "Connections", () -> deps.server().toggle(),
                    () -> !deps.server().isRunning());
            cmd("server.stop", "Stop the embedded " + name, "stop", null, "Connections", () -> deps.server().toggle(),
                    () -> deps.server().isRunning());
        }
        // tools
        cmd("tools.gradle", "Run Gradle tasks…", "play", key(KeyEvent.VK_G, CMD | ALT), "Tools", this::runGradle,
                () -> focusedSession().isPresent());
        cmd("tools.recording", "Open JFR recording…", "chart", null, "Tools", () -> chooseRecording().ifPresent(f ->
                deps.intents().dispatch(new Intent.OpenRecording(f))));
        cmd("tools.jfrq", "Run jfrq on a recording…", "terminal", null, "Tools", this::runJfrq);
        cmd("tools.scratchpad", "Browse the scratchpad", "file", null, "Tools", () -> {
            sidePaneHidden = false;
            deps.intents().dispatch(new Intent.BrowseScratchpad());
        });
        // help
        cmd("help.tutorial", "Tutorial", "file", null, "Help", this::showTutorial);
        // ⌘/ stays the editor's comment toggle; ⌘⇧/ is the Mac's Help key
        cmd("help.keys", "Keyboard shortcuts", "palette", key(KeyEvent.VK_SLASH, CMD | SHIFT), "Help", this::showKeys);
        cmd("help.dataRoot", "Reveal the data folder", "file", null, "Help", () -> reveal(deps.home()), () -> deps.home() != null);
        cmd("help.log", "Show the log", "file", null, "Help", this::showLog, () -> deps.logDir() != null);
        // on macOS the application menu owns ⌘Q and calls the quit handler; a second binding would quit twice
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        cmd("app.quit", "Quit comando", null, mac ? null : key(KeyEvent.VK_Q, CMD), "", this::quit);
    }

    private void cmd(String id, String label, String icon, KeyStroke key, String group, Runnable run) {
        actions.register(Command.of(id, label, icon, key, group, run));
    }

    private void cmd(String id, String label, String icon, KeyStroke key, String group, Runnable run, BooleanSupplier enabled) {
        actions.register(Command.of(id, label, icon, key, group, run, enabled));
    }

    /** An Edit command: runs the focused component's own action for {@code key}; enabled when it has one. */
    private void edit(String id, String label, KeyStroke key) {
        cmd(id, label, null, key, "Edit", () -> Keys.forward(focusOwnerInFrame(), key, true), () -> Keys.forward(focusOwnerInFrame(), key, false));
    }

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        for (String group : actions.groups()) {
            JMenu menu = actions.menu(group, group);
            bar.add(menu);
        }
        return bar;
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

    private boolean promptFocused() {
        EditorPanel p = workspace == null ? null : workspace.focused();
        Component c = focusOwnerInFrame();
        return p != null && c != null && SwingUtilities.isDescendingFrom(c, p.prompt());
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

    /** Run keys act while the keyboard is in the focused panel's editor or prompt bar; elsewhere the key is the component's. */
    private boolean canRun() {
        return editorFocused() || promptFocused();
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

    /** ⌘⏎ and ⌘⇧⏎ in the prompt bar send it; anywhere else they run the editor's selection or block. */
    private void run(Target target) {
        EditorPanel p = workspace.focused();
        if (p == null) {
            return;
        }
        if (target != Target.SQL && promptFocused()) {
            p.prompt().sendNow();
        } else {
            p.runSelection(target);
        }
    }

    /** Opens the most recently closed panel again after the focused one, with its text, title and bindings. */
    private void reopenPanel() {
        workspace.popClosed().ifPresent(c -> {
            PanelId id = PanelId.random();
            deps.texts().changed(id, c.text());
            deps.intents().dispatch(new Intent.ReopenPanel(id, c.title(), c.connection(), c.session()));
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
        if (job.isPresent()) {
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

    private void showTutorial() {
        sidePaneHidden = false;
        try (var in = ComandoUi.class.getResourceAsStream("/dev/marregui/comando/ui/help/tutorial.md")) {
            if (in == null) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The tutorial is not in this build", false));
                return;
            }
            // links to other docs are relative to the repository, which the app does not ship, and the view does not
            // follow in-page anchors: keep their text only
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\[([^\\]]+)\\]\\((\\.\\./[^)]+|[a-z-]+\\.md[^)]*|#[^)]+)\\)", "$1");
            deps.intents().dispatch(new Intent.ShowView("", "Tutorial", new ViewSpec.Markdown(text), Placement.SIDE));
        } catch (IOException e) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot read the tutorial: " + e.getMessage(), false));
        }
    }

    /** Every command with a key, by menu, as a table in the side pane. */
    private void showKeys() {
        sidePaneHidden = false;
        deps.intents().dispatch(new Intent.ShowView("", "Keyboard shortcuts", new ViewSpec.Markdown(Keys.markdown(actions.all())), Placement.SIDE));
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

    /** The newest log file, last lines, in the side pane with the caret at the end. */
    private void showLog() {
        sidePaneHidden = false;
        Path file = deps.logDir().resolve("comando-0.log");
        Thread.ofVirtual().name("show-log").start(() -> {
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                List<String> tail = lines.subList(Math.max(0, lines.size() - 2_000), lines.size());
                String text = String.join("\n", tail);
                deps.intents().dispatch(new Intent.ShowView("", "comando log", new ViewSpec.Text(file.toString(), text, "log", tail.size()),
                        Placement.SIDE));
            } catch (IOException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot read " + file + ": " + e.getMessage(), false));
            }
        });
    }

    private void sendCommand(String command) {
        deps.intents().dispatch(new Intent.RunText(focusedId(), command, Target.CLAUDE));
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

    private Optional<String> chooseRecording() {
        Path start = Path.of(prefs.get("tools.recording", System.getProperty("user.home")));
        Optional<Path> file = FileChoosers.open(frame, "Open JFR recording", start, "jfr");
        file.ifPresent(f -> prefs.put("tools.recording", f.toAbsolutePath().toString()));
        return file.map(f -> f.toAbsolutePath().toString());
    }

    private void runJfrq() {
        Optional<String> file = chooseRecording();
        if (file.isEmpty()) {
            return;
        }
        String args = (String) JOptionPane.showInputDialog(frame, "jfrq command and options (info, health, alloc, locks, stalls):",
                "Run jfrq", JOptionPane.PLAIN_MESSAGE, null, null, prefs.get("tools.jfrq", "info"));
        if (args != null && !args.isBlank()) {
            prefs.put("tools.jfrq", args.strip());
            deps.intents().dispatch(new Intent.RunJfrq(file.get(), args.strip()));
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
            } catch (javax.swing.text.BadLocationException e) {
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
        boolean visible = sidebar.component().isVisible();
        sidebar.component().setVisible(!visible);
        if (!visible) {
            outer.setDividerLocation(prefs.getInt("divider.sidebar", 260));
        }
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
        try {
            ResultViews.plot(lower.results().shown());
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
        String title = "live · " + s.focusedPanel().title();
        livePlots.start(s.focused(), run.sql(), title, seconds);
        deps.intents().dispatch(new Intent.ShowView("", title, ResultViews.plot(lower.results().shown()), Placement.LOWER));
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
        String title = "result · " + (sql.length() > 40 ? sql.substring(0, 40) + "…" : sql) + " #" + run.requestId();
        deps.intents().dispatch(new Intent.ShowView("", title, ResultViews.table(r), Placement.LOWER));
    }

    private void openConnections(String select) {
        connectionsDialog.show(deps.state().get(), select);
    }

    private void openSessions(String select) {
        sessionsDialog.show(deps.state().get(), select);
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
        outer.setDividerLocation(prefs.getInt("divider.sidebar", 260));
        vertical.setDividerLocation(prefs.getInt("divider.lower", (int) (h * 0.45)));
        if (!prefs.getBoolean("sidebar.visible", true)) {
            sidebar.component().setVisible(false);
        }
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
        if (sidebar.component().isVisible()) {
            prefs.putInt("divider.sidebar", outer.getDividerLocation());
        }
        prefs.putBoolean("sidebar.visible", sidebar.component().isVisible());
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
        Edt.runAndWait(() -> {
            if (frame == null) {
                return;
            }
            lower.terminals().closeAll();
            livePlots.stopAll();
            if (watchdog != null) {
                watchdog.close();
            }
            keyPriority.close();
            stopListening();
            deps.loop().removeListener(stateListener);
            deps.schemas().removeListener(schemaListener);
            if (watched != null) {
                watched.removeListener(transcriptListener);
                watched = null;
            }
            frame.dispose();
        });
    }

    /** Asks for one of {@code names}; nothing happens when there are none or the user cancels. */
    private void choose(String title, List<String> names, java.util.function.Consumer<String> chosen) {
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

    private void stopListening() {
        sidebarTimer.stop();
        tickTimer.stop();
        layoutSaver.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", focusListener);
        Toolkit.getDefaultToolkit().removeAWTEventListener(keyClock);
    }

    /** What quitting would stop: running queries, sessions in a turn, shells running a job; empty when nothing. */
    private List<String> runningWork() {
        AppState s = deps.state().get();
        List<String> work = new java.util.ArrayList<>();
        long queries = s.runs().stream().filter(r -> r.status().isActive()).count();
        if (queries > 0) {
            work.add(queries + (queries == 1 ? " running query" : " running queries"));
        }
        List<String> busy = s.sessions().stream().filter(SessionState::isBusy).map(SessionState::name).toList();
        if (!busy.isEmpty()) {
            work.add(String.join(", ", busy) + (busy.size() == 1 ? "'s turn" : "' turns"));
        }
        int jobs = lower.terminals().runningJobs();
        if (jobs > 0) {
            work.add(jobs + (jobs == 1 ? " shell job" : " shell jobs"));
        }
        // Gradle and jfrq runs show as console views until they finish
        s.views().stream().filter(v -> v.spec() instanceof ViewSpec.Console c && c.state() == ViewSpec.RunState.RUNNING)
                .forEach(v -> work.add(v.title()));
        return work;
    }

    private boolean quitting;
    private Timer sidebarTimer;
    private final java.util.function.Consumer<String> schemaListener = connection -> render.request();
    private final StateLoop.Listener stateListener = (previous, next) -> render.request();

    private boolean confirmingQuit;
    /** The macOS quit requests waiting for the answer to the open question. */
    private final List<java.awt.desktop.QuitResponse> pendingQuits = new java.util.ArrayList<>();

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
                    pendingQuits.forEach(java.awt.desktop.QuitResponse::cancelQuit);
                    pendingQuits.clear();
                    return false;
                }
            } finally {
                confirmingQuit = false;
            }
        }
        quitting = true;
        saveLayoutQuietly();
        stopListening();
        lower.terminals().closeAll();
        livePlots.stopAll();
        if (watchdog != null) {
            watchdog.close();
        }
        keyPriority.close();
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
