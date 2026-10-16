package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.layout.ShrinkingRow;
import dev.marregui.comando.ui.kit.text.Editors;
import dev.marregui.comando.ui.kit.text.HtmlPane;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widgets.editor.CommandEditor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One editor panel: a header with its session and connection, and the command editor. A panel's own text is SQL and
 * prompts; a panel opened on a file, a scratchpad document, is coloured as the file's language, and a Markdown one
 * opens rendered. Any Markdown panel switches between the editor and the rendered text with its header button, which
 * names the other view: Preview while editing, Edit while previewing. A
 * production connection frames the panel in red.
 */
public final class EditorPanel extends JPanel {

    private final IntentSink intents;
    private final CommandEditor editor = new CommandEditor();
    private final JButton connection = chip();
    private final JButton connectionToggle = iconButton();
    private final JButton session = chip();
    private final JButton sessionToggle = iconButton();
    /** Starts a stopped session on a new conversation; shown only while there is a conversation to leave. */
    private final JButton sessionFresh = iconButton();
    /** Sends the session's checkpoint command; shown only while the session has one. */
    private final JButton sessionCheckpoint = iconButton();
    private final JButton run = new JButton("Run", Icons.get("play"));
    private final JButton stop = new JButton("Stop", Icons.get("stop"));
    /** Empties the panel's own text in one step ⌘Z takes back; a document, a file of its own, is never emptied from here. */
    private final JButton clear = new JButton(Icons.get("clear"));
    /** Names the other view: Preview while the text is edited, Edit while the Markdown shows rendered. */
    private final JButton mode = new JButton();
    private boolean previewing;
    private final HtmlPane rendered = new HtmlPane();
    private final JPanel center = new JPanel(new CardLayout());
    private Path file;
    private boolean document;
    private final SchemaRegistry schemas;
    private PanelState panel;
    private AppState state;

    public EditorPanel(PanelState panel, IntentSink intents, SchemaRegistry schemas) {
        super(new BorderLayout(0, 0));
        this.panel = panel;
        this.intents = intents;
        this.schemas = schemas;
        run.getAccessibleContext().setAccessibleName("Run");
        stop.getAccessibleContext().setAccessibleName("Stop");
        clear.getAccessibleContext().setAccessibleName("Clear");
        connectionToggle.getAccessibleContext().setAccessibleName("Connect or disconnect");
        sessionToggle.getAccessibleContext().setAccessibleName("Start or stop the session");
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, Theme.GAP, 4, Theme.GAP));
        // on a narrow panel the chips' names give way, ending in "…"; the plugs and buttons beside them stay whole
        JPanel left = new JPanel(new ShrinkingRow(2, 64, Set.of(session, connection)));
        // the session first, as a panel is mostly for it; the database second
        left.add(session);
        left.add(sessionToggle);
        left.add(sessionFresh);
        left.add(sessionCheckpoint);
        sessionFresh.setIcon(Icons.get("fresh"));
        sessionFresh.getAccessibleContext().setAccessibleName("Start a new conversation");
        sessionCheckpoint.setIcon(Icons.get("checkpoint"));
        sessionCheckpoint.getAccessibleContext().setAccessibleName("Checkpoint");
        left.add(Box.createHorizontalStrut(Theme.PAD));
        left.add(connection);
        left.add(connectionToggle);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        run.setToolTipText("Run the selection or the block under the caret (⌘⏎); ⌘⇧⏎ sends to Claude, ⌥⏎ runs as SQL");
        stop.setToolTipText("Stop the running SQL or interrupt the session (⌘.)");
        clear.setToolTipText("Clear the text (⌘Z brings it back)");
        // a click leaves the keyboard in the editor, ready for the next edit or run
        run.setFocusable(false);
        stop.setFocusable(false);
        clear.setFocusable(false);
        mode.setFocusable(false);
        showMode();
        mode.addActionListener(e -> showPreview(!previewing));
        right.add(mode);
        right.add(run);
        right.add(stop);
        right.add(clear);
        // the chips take what the buttons leave, never drawn under them
        header.add(left, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        rendered.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD * 2, Theme.PAD, Theme.PAD * 2));
        JScrollPane renderedScroll = new JScrollPane(rendered);
        renderedScroll.setBorder(null);
        center.add(editor.component(), EDIT);
        center.add(renderedScroll, PREVIEW);
        add(center, BorderLayout.CENTER);

        run.addActionListener(e -> runSelection(Target.AUTO));
        stop.addActionListener(e -> intents.dispatch(new Intent.CancelRun(this.panel.id())));
        clear.addActionListener(e -> {
            editor.clear();
            editor.requestFocus();
        });
        editor.area().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                showClear();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                showClear();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // attributes only: the text is the same
            }
        });
        showClear();
        connection.addActionListener(e -> connectionMenu().show(connection, 0, connection.getHeight()));
        session.addActionListener(e -> sessionMenu().show(session, 0, session.getHeight()));
        connectionToggle.addActionListener(e -> toggleConnection());
        sessionToggle.addActionListener(e -> toggleSession());
        sessionFresh.addActionListener(e -> state.session(panel.session()).ifPresent(x -> intents.dispatch(new Intent.StartFresh(x.name()))));
        sessionCheckpoint.addActionListener(e -> state.session(panel.session()).ifPresent(x -> intents.dispatch(new Intent.Checkpoint(x.name()))));
    }

    public CommandEditor editor() {
        return editor;
    }

    private static final String EDIT = "edit";
    private static final String PREVIEW = "preview";

    /**
     * The panel's file, once it has one, and whether it is a {@code document}: a file opened in the panel rather than
     * the panel's own. A document is coloured as its language and, when Markdown, shows rendered first; the panel's
     * own text stays SQL and prompts, in the editor. A Markdown file offers the Edit switch, any other file does not.
     */
    public void file(Path file, boolean document) {
        if (Objects.equals(file, this.file) && document == this.document) {
            return;
        }
        boolean opened = document && !(this.document && Objects.equals(file, this.file));
        this.file = file;
        this.document = document;
        editor.syntax(document && file != null ? Editors.styleFor(file) : CommandEditor.PANEL_STYLE);
        // a log belongs to the program writing it: read here, never written
        editor.area().setEditable(!isLog());
        if (opened && isLog()) {
            followEnd();
        }
        showClear();
        boolean markdown = isMarkdown();
        mode.setVisible(markdown);
        if (!markdown && previewing()) {
            showPreview(false);
        } else if (markdown && opened) {
            showPreview(true);
        }
    }

    /** A document ending in {@code .log}: read-only, and it follows its end as it grows. */
    boolean isLog() {
        return document && file != null && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".log");
    }

    private void followEnd() {
        editor.area().setCaretPosition(editor.area().getDocument().getLength());
    }

    private boolean isMarkdown() {
        String name = file == null ? ".md" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    /** The rendered view; for tests. */
    HtmlPane rendered() {
        return rendered;
    }

    boolean previewOffered() {
        return mode.isVisible();
    }

    /** The mode button's label: the view a click switches to; for tests. */
    String modeLabel() {
        return mode.getText();
    }

    public boolean previewing() {
        return previewing;
    }

    /** Switches between the text to edit and the Markdown rendered; a file that is not Markdown stays as text. */
    public void togglePreview() {
        if (mode.isVisible()) {
            showPreview(!previewing());
        }
    }

    private void showPreview(boolean on) {
        previewing = on;
        showMode();
        if (on) {
            rendered.setMarkdown(editor.text());
            rendered.setCaretPosition(0);
        }
        ((CardLayout) center.getLayout()).show(center, on ? PREVIEW : EDIT);
        showClear();
        if (!on) {
            editor.requestFocus();
        }
    }

    /** Clear is offered for the panel's own text while it is edited, and does something only when there is text. */
    private void showClear() {
        clear.setVisible(!document);
        clear.setEnabled(!previewing && editor.area().getDocument().getLength() > 0);
    }

    /** For tests. */
    boolean clearOffered() {
        return clear.isVisible() && clear.isEnabled();
    }

    /** For tests: the Clear button's click. */
    void clickClear() {
        clear.doClick();
    }

    private void showMode() {
        String label = previewing ? "Edit" : "Preview";
        mode.setText(label);
        mode.setIcon(Icons.get(previewing ? "edit" : "preview"));
        mode.setToolTipText(previewing ? "Edit the Markdown (⌘⇧M)" : "Show the Markdown rendered (⌘⇧M)");
        mode.getAccessibleContext().setAccessibleName(label);
    }

    /**
     * The file changed outside comando: the editor takes the text, and the preview shows it when it is on. A log whose
     * caret was at its end stays at the end.
     */
    public void reload(String text) {
        boolean atEnd = isLog() && editor.area().getCaretPosition() >= editor.area().getDocument().getLength();
        editor.reload(text);
        if (atEnd) {
            followEnd();
        }
        if (previewing()) {
            int at = rendered.getCaretPosition();
            rendered.setMarkdown(text);
            rendered.setCaretPosition(Math.min(at, rendered.getDocument().getLength()));
        }
    }

    /** The failed run whose error position is underlined, so a re-render does not mark it again. */
    private long errorMarked = -1;

    public PanelState panel() {
        return panel;
    }

    public void runSelection(Target target) {
        String text = editor.runText();
        if (!text.isBlank()) {
            intents.dispatch(new Intent.RunText(panel.id(), text, target));
        }
    }

    public void render(PanelState p, AppState s) {
        panel = p;
        state = s;
        Optional<ConnectionState> c = p.connection().isEmpty() ? Optional.empty() : s.connection(p.connection());
        Optional<SessionState> x = p.session().isEmpty() ? Optional.empty() : s.session(p.session());
        // connection chip
        if (c.isPresent()) {
            ConnectionState cs = c.get();
            connection.setIcon(Icons.get("database"));
            connection.setText(cs.name() + "  " + dot(cs.status()));
            connection.setToolTipText(cs.spec().label() + " · " + cs.status() + (cs.error().isEmpty() ? "" : " · " + cs.error()));
            connectionToggle.setVisible(true);
            // the plug shows the state, as on the sidebar's session rows: green while connected, grey while not
            connectionToggle.setIcon(Icons.get(cs.status().isUsable() ? "plug" : "unplug"));
            connectionToggle.setToolTipText(cs.status().isUsable() ? "Connected; click to disconnect" : "Disconnected; click to connect");
            setBorder(cs.spec().prod() ? BorderFactory.createLineBorder(Theme.DANGER, 2) : BorderFactory.createEmptyBorder(2, 2, 2, 2));
        } else {
            connection.setIcon(Icons.get("database"));
            connection.setText("no connection ▾");
            connection.setToolTipText("Bind a connection");
            connectionToggle.setVisible(false);
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        }
        // session chip
        if (x.isPresent()) {
            SessionState ss = x.get();
            String branch = ss.branch().isEmpty() ? "" : "  ⎇ " + ss.branch();
            session.setIcon(Icons.get("claude"));
            session.setText(ss.name() + branch + "  " + dot(ss.status()) + busyText(ss));
            session.setToolTipText(Labels.shortPath(ss.spec().workDir()) + " · " + ss.status()
                    + (ss.error().isEmpty() ? "" : " · " + ss.error()));
            sessionToggle.setVisible(true);
            // the plug, as on the session's row in the sidebar: green while running, grey while not; a ▶ here would read as Run
            sessionToggle.setIcon(Icons.get(ss.status().isUsable() ? "plug" : "unplug"));
            sessionToggle.setToolTipText(ss.status().isUsable() ? "Connected; click to disconnect (stops the session)"
                    : "Disconnected; click to connect (starts the session)");
            sessionFresh.setVisible(showsFresh(ss));
            sessionFresh.setToolTipText("Start a new conversation; the current one (" + ss.spec().claudeSessionId() + ") stays importable");
            sessionCheckpoint.setVisible(showsCheckpoint(ss));
            sessionCheckpoint.setEnabled(ss.status() != Status.CLOSING);
            sessionCheckpoint.setIcon(Icons.get(checkpointIcon(ss)));
            sessionCheckpoint.setToolTipText(checkpointTip(ss));
        } else {
            sessionFresh.setVisible(false);
            sessionCheckpoint.setVisible(false);
            session.setIcon(Icons.get("claude"));
            session.setText("no session ▾");
            session.setToolTipText("Bind a session");
            sessionToggle.setVisible(false);
        }
        editor.setSchema(p.connection().isEmpty() ? Schema.EMPTY : schemas.of(p.connection()));
        // with no open database, the text is prompts and notes: nothing is coloured as SQL
        editor.sqlLive(c.map(cs -> cs.status().isUsable()).orElse(false));
        boolean running = s.runOf(p.id()).map(r -> r.status().isActive()).orElse(false) || x.map(SessionState::isBusy).orElse(false);
        stop.setEnabled(running);
        s.runOf(p.id()).filter(r -> r.status() == RunStatus.FAILED && r.requestId() != errorMarked).ifPresent(r -> {
            errorMarked = r.requestId();
            int position = errorPosition(r.error());
            if (position > 0) {
                editor.markError(r.sql(), position);
            }
        });
    }

    private static final Pattern POSITION = Pattern.compile("Position: (\\d+)");

    /** The 1-based position a database error names ("Position: 38"), or 0. */
    static int errorPosition(String error) {
        Matcher m = POSITION.matcher(error == null ? "" : error);
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "working · 12 s" while a turn runs; the shell's one-second tick refreshes it. */
    static String busyText(SessionState ss) {
        if (ss.isWaiting()) {
            long since = ss.waitingSinceMillis();
            return "  " + TabStatus.waiting(ss) + (since > 0 ? " · " + Labels.elapsed(System.currentTimeMillis() - since) : "");
        }
        if (!ss.isBusy()) {
            return "";
        }
        String what = ss.activity().isEmpty() ? "working" : ss.activity();
        long since = ss.turnStartedMillis();
        int queued = ss.pendingTurns() - 1;
        return "  " + what + (since > 0 ? " · " + Labels.elapsed(System.currentTimeMillis() - since) : "")
                + (queued > 0 ? " · " + queued + " queued" : "");
    }

    /** Whether the session chip shows a time that the shell's one-second tick moves on: working, or waiting. */
    static boolean counting(SessionState ss) {
        return ss.isBusy() || ss.isWaiting();
    }

    /** Refreshes what changes with time alone: the session chip's elapsed time, working or waiting. */
    public void tick() {
        if (state != null) {
            state.session(panel.session()).filter(EditorPanel::counting).ifPresent(ss -> {
                String branch = ss.branch().isEmpty() ? "" : "  ⎇ " + ss.branch();
                session.setText(ss.name() + branch + "  " + dot(ss.status()) + busyText(ss));
            });
        }
    }

    private void toggleConnection() {
        state.connection(panel.connection()).ifPresent(c -> intents.dispatch(c.status().isUsable()
                ? new Intent.CloseConnection(c.name()) : new Intent.OpenConnection(c.name())));
    }

    /** The fresh-start button shows while the session is stopped and has a conversation to leave behind. */
    static boolean showsFresh(SessionState ss) {
        return !ss.status().isUsable() && ss.status() != Status.CLOSING && !ss.spec().claudeSessionId().isEmpty();
    }

    static boolean showsCheckpoint(SessionState ss) {
        return !ss.spec().checkpoint().isEmpty();
    }

    /** Grey, purple once the session is due its checkpoint: the context filled past the threshold since the last one. */
    static String checkpointIcon(SessionState ss) {
        return ss.reflection().due() ? "checkpoint-due" : "checkpoint";
    }

    static String checkpointTip(SessionState ss) {
        String send = "send " + ss.spec().checkpoint() + " to the session";
        return ss.reflection().due() ? "Checkpoint due: the context passed " + Math.round(Reflection.THRESHOLD * 100) + "% of the window; " + send
                : "Checkpoint: " + send;
    }

    private void toggleSession() {
        state.session(panel.session()).ifPresent(x -> intents.dispatch(x.status().isUsable()
                ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name())));
    }

    private JPopupMenu connectionMenu() {
        JPopupMenu m = new JPopupMenu();
        for (ConnectionState c : state.connections()) {
            JMenuItem item = new JMenuItem(c.name() + "  " + c.spec().label(), Icons.get("database"));
            item.addActionListener(e -> intents.dispatch(new Intent.BindConnection(panel.id(), c.name())));
            m.add(item);
        }
        if (!panel.connection().isEmpty()) {
            m.addSeparator();
            JMenuItem none = new JMenuItem("Unbind");
            none.addActionListener(e -> intents.dispatch(new Intent.BindConnection(panel.id(), "")));
            m.add(none);
        }
        if (state.connections().isEmpty()) {
            m.add(new JMenuItem("No connections; + in the sidebar's Connections tab adds one")).setEnabled(false);
        }
        return m;
    }

    /**
     * A panel with a session offers to detach it: the session keeps running without a panel. A panel without one lists
     * the sessions; a choice opens it, here unless another panel has it, which then comes forward.
     */
    private JPopupMenu sessionMenu() {
        JPopupMenu m = new JPopupMenu();
        if (!panel.session().isEmpty()) {
            JMenuItem detach = new JMenuItem("Detach " + panel.session());
            detach.setToolTipText("The session keeps running without a panel; the text stays here");
            detach.addActionListener(e -> intents.dispatch(new Intent.BindSession(panel.id(), "")));
            m.add(detach);
            return m;
        }
        for (SessionState x : state.sessions()) {
            List<PanelState> elsewhere = state.panelsBoundToSession(x.name());
            String where = elsewhere.isEmpty() ? "" : "  · " + elsewhere.getFirst().title();
            JMenuItem item = new JMenuItem(x.name() + "  " + Labels.shortPath(x.spec().workDir()) + where, Icons.get("claude"));
            item.addActionListener(e -> intents.dispatch(new Intent.OpenSession(x.name(), panel.id(), PanelId.random())));
            m.add(item);
        }
        if (state.sessions().isEmpty()) {
            m.add(new JMenuItem("No sessions; + or import in the sidebar's Sessions tab adds one")).setEnabled(false);
        }
        return m;
    }

    static String dot(Status s) {
        return switch (s) {
            case OPEN -> "●";
            case OPENING, CLOSING -> "◐";
            case FAILED -> "✕";
            case CLOSED -> "○";
        };
    }

    static Color color(Status s) {
        return switch (s) {
            case OPEN -> Theme.OK;
            case OPENING, CLOSING -> Theme.WARN;
            case FAILED -> Theme.DANGER;
            case CLOSED -> Theme.MUTED;
        };
    }

    private static JButton chip() {
        JButton b = new JButton();
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        return b;
    }

    private static JButton iconButton() {
        JButton b = new JButton();
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        return b;
    }
}
