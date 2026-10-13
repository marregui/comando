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
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.panels.EditorPanel;
import dev.marregui.comando.ui.panels.LowerPanel;
import dev.marregui.comando.ui.panels.SessionsSidebar;
import dev.marregui.comando.ui.panels.SidePane;
import dev.marregui.comando.ui.panels.StatusBar;
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
import javax.swing.JFileChooser;
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
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Desktop;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
            Supplier<List<StoredConversation>> catalogue,
            ServerToggle server,
            String version,
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
    private long sideViewsKey;

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
        // same quit as closing the window; the response stays unanswered because the exit ends the process.
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler((event, response) -> SwingUtilities.invokeLater(this::quit));
        }
        notifier = new Notifier(frame);
        livePlots = new LivePlots(deps.intents(), deps.results());
        watchdog = EdtWatchdog.logging();
        Thread.ofVirtual().name("pty-preload").start(TerminalWidget::preload);
        workspace = new Workspace(deps.intents(), deps.texts(), deps.schemas());
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
        });
        status = new StatusBar();
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
        restoreLayout();
        deps.loop().addListener(stateListener);
        render();
        frame.setVisible(true);
        sidebarTimer = new Timer(30_000, e -> sidebar.tick());
        sidebarTimer.start();
        EditorPanel p = workspace.focused();
        if (p != null) {
            p.editor().requestFocus();
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
        long key = s.views().stream().filter(v -> v.placement() == Placement.SIDE).mapToLong(v -> v.id() * 31 + v.version()).sum();
        if (key != sideViewsKey) {
            sideViewsKey = key;
            sidePaneHidden = false;
        }
        boolean show = hasSide && !sidePaneHidden;
        if (show != sidePaneShown) {
            showSidePane(show);
        }
        status.render(s);
        connectionsDialog.render(s);
        sessionsDialog.render(s);
        watchTranscript(s.focusedPanel().session());
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
                case APPEND -> p.editor().append(text);
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
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                ViewSpec spec = ext.equals("md") && line <= 0 ? new ViewSpec.Markdown(text) : new ViewSpec.Text(p.toString(), text, ext, line);
                deps.intents().dispatch(new Intent.ShowView(session, name, spec, Placement.SIDE));
            } catch (IOException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot open " + p + ": " + e.getMessage(), false));
            }
        });
    }

    @Override
    public void arrange(Layout layout) {
        Edt.run(() -> {
            if (frame == null) {
                return;
            }
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
        });
    }

    // ---- commands

    private void registerCommands() {
        // run
        cmd("run", "Run selection or block", "play", key(KeyEvent.VK_ENTER, CMD), "Run", () -> run(Target.AUTO));
        cmd("run.claude", "Send selection or block to Claude", "claude", key(KeyEvent.VK_ENTER, CMD | InputEvent.SHIFT_DOWN_MASK), "Run",
                () -> run(Target.CLAUDE));
        cmd("run.sql", "Run selection or block as SQL", "database", key(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK), "Run", () -> run(Target.SQL));
        cmd("run.stop", "Stop query or interrupt turn", "stop", key(KeyEvent.VK_PERIOD, CMD), "Run",
                () -> deps.intents().dispatch(new Intent.CancelRun(focusedId())));
        cmd("run.insertResult", "Attach the result, selected rows or error to the editor", "attach", key(KeyEvent.VK_I, CMD | InputEvent.SHIFT_DOWN_MASK), "Run",
                this::insertLastResult);
        cmd("run.plot", "Plot last result", "chart", key(KeyEvent.VK_J, CMD), "Run", this::plotLastResult);
        cmd("run.plotLive", "Plot last result, refreshing…", "chart", key(KeyEvent.VK_J, CMD | InputEvent.SHIFT_DOWN_MASK), "Run", this::plotLive);
        cmd("run.plotStop", "Stop refreshing plots", "stop", null, "Run", livePlots::stopAll);
        cmd("run.keep", "Keep the result in a tab", "table", key(KeyEvent.VK_R, CMD | InputEvent.SHIFT_DOWN_MASK), "Run", this::keepResult);
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
        cmd("panel.close", "Close panel", "close", key(KeyEvent.VK_W, CMD), "Panels",
                () -> deps.intents().dispatch(new Intent.ClosePanel(focusedId())));
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
        cmd("panel.next", "Next panel", null, key(KeyEvent.VK_CLOSE_BRACKET, CMD | InputEvent.SHIFT_DOWN_MASK), "Panels",
                () -> workspace.selectRelative(1));
        cmd("panel.previous", "Previous panel", null, key(KeyEvent.VK_OPEN_BRACKET, CMD | InputEvent.SHIFT_DOWN_MASK), "Panels",
                () -> workspace.selectRelative(-1));
        for (int i = 1; i <= 9; i++) {
            int index = i - 1;
            cmd("panel." + i, "Go to panel " + i, null, key(KeyEvent.VK_0 + i, CMD), "", () -> workspace.select(index));
        }
        // view
        cmd("view.results", "Show results", "table", key(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK), "View",
                () -> deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.RESULTS)));
        cmd("view.session", "Show session transcript", "session", key(KeyEvent.VK_2, InputEvent.CTRL_DOWN_MASK), "View",
                () -> deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.SESSION)));
        cmd("view.approvals", "Show approvals", "check", key(KeyEvent.VK_3, InputEvent.CTRL_DOWN_MASK), "View", () -> {
            deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.APPROVALS));
            Edt.later(() -> lower.inbox().focusFirst());
        });
        cmd("view.terminal", "Show the panel's terminal", "terminal", key(KeyEvent.VK_4, InputEvent.CTRL_DOWN_MASK), "View", () -> {
            deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.TERMINAL));
            Edt.later(() -> {
                TerminalWidget t = lower.terminals().focused();
                if (t != null) {
                    t.focus();
                }
            });
        });
        cmd("terminal.run", "Run selection or line in the terminal", "terminal", key(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "Run",
                this::runInTerminal);
        cmd("terminal.restart", "Restart the panel's shell", "refresh", null, "View", () -> {
            TerminalWidget t = lower.terminals().focused();
            if (t != null) {
                t.restart();
            }
        });
        cmd("connection.schema", "Explore the schema", "tree", key(KeyEvent.VK_E, CMD | InputEvent.SHIFT_DOWN_MASK), "Connections", () -> {
            AppState s = deps.state().get();
            String c = s.focusedPanel().connection();
            if (c.isEmpty()) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Bind a connection to the panel first", false));
            } else {
                deps.intents().dispatch(new Intent.ExploreSchema(c));
            }
        });
        cmd("tools.gradle", "Run Gradle tasks…", "play", key(KeyEvent.VK_G, CMD | InputEvent.SHIFT_DOWN_MASK), "Tools", this::runGradle);
        cmd("tools.recording", "Open JFR recording…", "chart", null, "Tools", () -> chooseRecording().ifPresent(f ->
                deps.intents().dispatch(new Intent.OpenRecording(f))));
        cmd("tools.jfrq", "Run jfrq on a recording…", "terminal", null, "Tools", this::runJfrq);
        cmd("tools.scratchpad", "Browse the scratchpad", "file", null, "Tools", () -> deps.intents().dispatch(new Intent.BrowseScratchpad()));
        cmd("view.sidebar", "Toggle sidebar", "sidebar", key(KeyEvent.VK_B, CMD), "View", this::toggleSidebar);
        cmd("view.sidePane", "Toggle side pane", null, key(KeyEvent.VK_BACK_SLASH, CMD), "View", () -> {
            sidePaneHidden = sidePaneShown;
            render();
        });
        cmd("view.lastPrompt", "Jump to the last prompt", "session", null, "View", () -> {
            deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
            Edt.later(() -> lower.transcript().jumpToLastPrompt());
        });
        cmd("view.find", "Find", "search", key(KeyEvent.VK_F, CMD), "View", () -> findBar.open(Capabilities.focused(Searchable.class)));
        cmd("view.copy", "Copy view content", "copy", key(KeyEvent.VK_C, CMD | InputEvent.SHIFT_DOWN_MASK), "View", () -> {
            Copyable c = Capabilities.focused(Copyable.class);
            if (c != null) {
                Capabilities.copy(c);
            }
        });
        cmd("view.export", "Export view…", "export", key(KeyEvent.VK_S, CMD), "View", () -> {
            Exportable e = Capabilities.focused(Exportable.class);
            if (e != null) {
                Capabilities.export(frame, e);
            }
        });
        cmd("view.zoomIn", "Zoom in", null, key(KeyEvent.VK_EQUALS, CMD), "View", () -> zoom(1));
        cmd("view.zoomOut", "Zoom out", null, key(KeyEvent.VK_MINUS, CMD), "View", () -> zoom(-1));
        cmd("view.zoomReset", "Reset zoom", null, key(KeyEvent.VK_0, CMD), "View", () -> {
            Zoomable z = Capabilities.focused(Zoomable.class);
            if (z != null) {
                z.resetZoom();
            }
        });
        cmd("view.palette", "Command palette", "palette", key(KeyEvent.VK_P, CMD | InputEvent.SHIFT_DOWN_MASK), "View",
                () -> new CommandPalette(actions).show(frame));
        // sessions
        cmd("session.dialog", "Sessions…", "session", key(KeyEvent.VK_K, CMD | InputEvent.SHIFT_DOWN_MASK), "Sessions", () -> openSessions(""));
        cmd("session.import", "Import stored conversations…", "import", null, "Sessions", this::openImport);
        cmd("session.toggle", "Start or stop the panel's session", "play", null, "Sessions", () -> focusedSession().ifPresent(x ->
                deps.intents().dispatch(x.status().isUsable() ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name()))));
        cmd("session.interrupt", "Interrupt the turn", "interrupt", key(KeyEvent.VK_PERIOD, CMD | InputEvent.SHIFT_DOWN_MASK), "Sessions",
                () -> focusedSession().ifPresent(x -> deps.intents().dispatch(new Intent.InterruptSession(x.name()))));
        cmd("session.compact", "Compact the conversation", null, null, "Sessions", () -> sendCommand("/compact"));
        for (String m : List.of("fable", "opus", "sonnet", "haiku")) {
            cmd("session.model." + m, "Model: " + m, null, null, "", () -> focusedSession().ifPresent(x ->
                    deps.intents().dispatch(new Intent.SetSessionModel(x.name(), m))));
        }
        for (String e : List.of("low", "medium", "high", "xhigh", "max")) {
            cmd("session.effort." + e, "Effort: " + e, null, null, "", () -> focusedSession().ifPresent(x ->
                    deps.intents().dispatch(new Intent.SetSessionEffort(x.name(), e))));
        }
        // connections
        cmd("connection.dialog", "Connections…", "database", key(KeyEvent.VK_O, CMD | InputEvent.SHIFT_DOWN_MASK), "Connections",
                () -> openConnections(""));
        cmd("connection.toggle", "Connect or disconnect the panel's connection", "plug", null, "Connections", () -> {
            AppState s = deps.state().get();
            s.connection(s.focusedPanel().connection()).ifPresent(c -> deps.intents().dispatch(c.status().isUsable()
                    ? new Intent.CloseConnection(c.name()) : new Intent.OpenConnection(c.name())));
        });
        if (deps.server() != null) {
            cmd("server.toggle", "Start or stop the embedded " + deps.server().name(), "database", null, "Connections",
                    () -> deps.server().toggle());
        }
        cmd("app.quit", "Quit comando", null, key(KeyEvent.VK_Q, CMD), "", this::quit);
    }

    private void cmd(String id, String label, String icon, KeyStroke key, String group, Runnable run) {
        actions.register(Command.of(id, label, icon, key, group, run));
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

    // ---- actions

    private PanelId focusedId() {
        return deps.state().get().focused();
    }

    private Optional<SessionState> focusedSession() {
        AppState s = deps.state().get();
        return s.session(s.focusedPanel().session());
    }

    private void run(Target target) {
        EditorPanel p = workspace.focused();
        if (p != null) {
            p.runSelection(target);
        }
    }

    private void sendCommand(String command) {
        deps.intents().dispatch(new Intent.RunText(focusedId(), command, Target.CLAUDE));
    }

    private String lastGradleTasks = "build";
    private String lastRecording = "";
    private String lastJfrqArgs = "info";

    private void runGradle() {
        String tasks = (String) JOptionPane.showInputDialog(frame, "Tasks and options, run in the build of the panel's session:",
                "Run Gradle", JOptionPane.PLAIN_MESSAGE, null, null, lastGradleTasks);
        if (tasks != null && !tasks.isBlank()) {
            lastGradleTasks = tasks.strip();
            deps.intents().dispatch(new Intent.RunGradle(focusedId(), lastGradleTasks));
        }
    }

    private Optional<String> chooseRecording() {
        JFileChooser chooser = new JFileChooser(lastRecording.isEmpty() ? System.getProperty("user.home") : lastRecording);
        chooser.setFileFilter(new FileNameExtensionFilter("JFR recordings", "jfr"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return Optional.empty();
        }
        lastRecording = chooser.getSelectedFile().getAbsolutePath();
        return Optional.of(lastRecording);
    }

    private void runJfrq() {
        Optional<String> file = chooseRecording();
        if (file.isEmpty()) {
            return;
        }
        String args = (String) JOptionPane.showInputDialog(frame, "jfrq command and options (info, health, alloc, locks, stalls):",
                "Run jfrq", JOptionPane.PLAIN_MESSAGE, null, null, lastJfrqArgs);
        if (args != null && !args.isBlank()) {
            lastJfrqArgs = args.strip();
            deps.intents().dispatch(new Intent.RunJfrq(file.get(), lastJfrqArgs));
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
            TerminalWidget t = lower.terminals().focused();
            if (t != null) {
                t.send(command + "\n");
            }
        });
    }

    private void zoom(int steps) {
        Zoomable z = Capabilities.focused(Zoomable.class);
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

    private void restoreLayout() {
        int w = prefs.getInt("window.width", 1500);
        int h = prefs.getInt("window.height", 950);
        frame.setSize(w, h);
        int x = prefs.getInt("window.x", -1);
        int y = prefs.getInt("window.y", -1);
        if (x >= 0 && y >= 0) {
            frame.setLocation(x, y);
        } else {
            frame.setLocationRelativeTo(null);
        }
        outer.setDividerLocation(prefs.getInt("divider.sidebar", 260));
        vertical.setDividerLocation(prefs.getInt("divider.lower", (int) (h * 0.45)));
    }

    private void saveLayout() {
        prefs.putInt("window.width", frame.getWidth());
        prefs.putInt("window.height", frame.getHeight());
        prefs.putInt("window.x", frame.getX());
        prefs.putInt("window.y", frame.getY());
        if (sidebar.component().isVisible()) {
            prefs.putInt("divider.sidebar", outer.getDividerLocation());
        }
        prefs.putInt("divider.lower", vertical.getDividerLocation());
        if (sidePaneShown) {
            prefs.putInt("divider.side", editorAndSide.getDividerLocation());
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
            watchdog.close();
            keyPriority.close();
            sidebarTimer.stop();
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

    private boolean quitting;
    private Timer sidebarTimer;
    private final java.util.function.Consumer<String> schemaListener = connection -> render.request();
    private final StateLoop.Listener stateListener = (previous, next) -> render.request();

    private void quit() {
        if (quitting) {
            return;
        }
        quitting = true;
        try {
            saveLayout();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot save the window layout: " + e.getMessage());
        }
        lower.terminals().closeAll();
        livePlots.stopAll();
        watchdog.close();
        keyPriority.close();
        frame.dispose();
        deps.onQuit().run();
    }
}
