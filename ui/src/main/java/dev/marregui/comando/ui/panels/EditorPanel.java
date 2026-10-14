package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
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
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
    private final JButton run = new JButton("Run", Icons.get("play"));
    private final JButton stop = new JButton(Icons.get("stop"));
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
        connectionToggle.getAccessibleContext().setAccessibleName("Connect or disconnect");
        sessionToggle.getAccessibleContext().setAccessibleName("Start or stop the session");
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, Theme.GAP, 4, Theme.GAP));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        // the session first, as a panel is mostly for it; the database second
        left.add(session);
        left.add(sessionToggle);
        left.add(Box.createHorizontalStrut(Theme.PAD));
        left.add(connection);
        left.add(connectionToggle);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        run.setToolTipText("Run the selection or the block under the caret (⌘⏎); ⌘⇧⏎ sends to Claude, ⌥⏎ runs as SQL");
        stop.setToolTipText("Stop the running SQL or interrupt the session (⌘.)");
        stop.putClientProperty("JButton.buttonType", "toolBarButton");
        // a click leaves the keyboard in the editor, ready for the next edit or run
        run.setFocusable(false);
        stop.setFocusable(false);
        mode.setFocusable(false);
        showMode();
        mode.addActionListener(e -> showPreview(!previewing));
        right.add(mode);
        right.add(run);
        right.add(stop);
        header.add(left, BorderLayout.WEST);
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
        connection.addActionListener(e -> connectionMenu().show(connection, 0, connection.getHeight()));
        session.addActionListener(e -> sessionMenu().show(session, 0, session.getHeight()));
        connectionToggle.addActionListener(e -> toggleConnection());
        sessionToggle.addActionListener(e -> toggleSession());
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
        editor.syntax(document && file != null ? Editors.styleFor(file) : Editors.styleFor("sql"));
        boolean markdown = isMarkdown();
        mode.setVisible(markdown);
        if (!markdown && previewing()) {
            showPreview(false);
        } else if (markdown && opened) {
            showPreview(true);
        }
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
        if (!on) {
            editor.requestFocus();
        }
    }

    private void showMode() {
        String label = previewing ? "Edit" : "Preview";
        mode.setText(label);
        mode.setIcon(Icons.get(previewing ? "edit" : "preview"));
        mode.setToolTipText(previewing ? "Edit the Markdown (⌘⇧M)" : "Show the Markdown rendered (⌘⇧M)");
        mode.getAccessibleContext().setAccessibleName(label);
    }

    /** The file changed outside comando: the editor takes the text, and the preview shows it when it is on. */
    public void reload(String text) {
        editor.reload(text);
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
            connectionToggle.setIcon(Icons.get(cs.status().isUsable() ? "unplug" : "plug"));
            connectionToggle.setToolTipText(cs.status().isUsable() ? "Disconnect" : "Connect");
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
            // the plug, as on the session's row in the sidebar and in Panel ▸ Session: a ▶ here would read as Run
            sessionToggle.setIcon(Icons.get(ss.status().isUsable() ? "unplug" : "plug"));
            sessionToggle.setToolTipText(ss.status().isUsable() ? "Disconnect: stop the session" : "Connect: start the session");
        } else {
            session.setIcon(Icons.get("claude"));
            session.setText("no session ▾");
            session.setToolTipText("Bind a session");
            sessionToggle.setVisible(false);
        }
        editor.setSchema(p.connection().isEmpty() ? Schema.EMPTY : schemas.of(p.connection()));
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
        if (!ss.isBusy()) {
            return "";
        }
        String what = ss.activity().isEmpty() ? "working" : ss.activity();
        long since = ss.turnStartedMillis();
        int queued = ss.pendingTurns() - 1;
        return "  " + what + (since > 0 ? " · " + Labels.elapsed(System.currentTimeMillis() - since) : "")
                + (queued > 0 ? " · " + queued + " queued" : "");
    }

    /** Refreshes what changes with time alone: the session chip's elapsed time. */
    public void tick() {
        if (state != null) {
            state.session(panel.session()).filter(SessionState::isBusy).ifPresent(ss -> {
                String branch = ss.branch().isEmpty() ? "" : "  ⎇ " + ss.branch();
                session.setText(ss.name() + branch + "  " + dot(ss.status()) + busyText(ss));
            });
        }
    }

    private void toggleConnection() {
        state.connection(panel.connection()).ifPresent(c -> intents.dispatch(c.status().isUsable()
                ? new Intent.CloseConnection(c.name()) : new Intent.OpenConnection(c.name())));
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
            m.add(new JMenuItem("No connections; add one with Connections…")).setEnabled(false);
        }
        return m;
    }

    private JPopupMenu sessionMenu() {
        JPopupMenu m = new JPopupMenu();
        for (SessionState x : state.sessions()) {
            JMenuItem item = new JMenuItem(x.name() + "  " + Labels.shortPath(x.spec().workDir()), Icons.get("claude"));
            item.addActionListener(e -> intents.dispatch(new Intent.BindSession(panel.id(), x.name())));
            m.add(item);
        }
        if (!panel.session().isEmpty()) {
            m.addSeparator();
            JMenuItem none = new JMenuItem("Unbind");
            none.addActionListener(e -> intents.dispatch(new Intent.BindSession(panel.id(), "")));
            m.add(none);
        }
        if (state.sessions().isEmpty()) {
            m.add(new JMenuItem("No sessions; add or import one with Sessions…")).setEnabled(false);
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
