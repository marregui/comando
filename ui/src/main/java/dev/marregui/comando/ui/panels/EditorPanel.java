package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
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
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.layout.ShrinkingRow;
import dev.marregui.comando.ui.kit.overlay.Flash;
import dev.marregui.comando.ui.kit.text.Editors;
import dev.marregui.comando.ui.kit.text.HtmlPane;
import dev.marregui.comando.ui.kit.text.RenderedMarkdown;
import dev.marregui.comando.ui.kit.text.LocalLinks;
import dev.marregui.comando.ui.kit.text.LogLines;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widgets.editor.CommandEditor;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Rectangle2D;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One editor panel: a header with its session and connection, and the command editor. A panel's own text is SQL and
 * prompts; a panel opened on a file, such as a document from the Files tab, is coloured as the file's language, and a
 * Markdown one opens rendered. Such a panel switches between the editor and the rendered text with its header button,
 * which names the other view: Preview while editing, Edit while previewing. An HTML file opens as its text, with a
 * button that opens it in the browser. A link to another Markdown or HTML file, double-clicked in the rendered text,
 * opens that file in a panel. A production connection frames the panel in red.
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
    /** Sends {@code /compact} from this panel; shown while the session runs. */
    private final JButton sessionCompact = iconButton();
    /** Sends {@code /clear} from this panel; shown while the session runs. */
    private final JButton sessionClear = iconButton();
    private final JButton run = iconButton();
    private final JButton stop = iconButton();
    /** Empties the panel's own text in one step ⌘Z takes back; a document, a file of its own, is never emptied from here. */
    private final JButton clear = iconButton();
    /** Shows the other view: the rendered Markdown while the text is edited, the text while it shows rendered. */
    private final JButton mode = iconButton();
    /** Switches every editor between the light theme and the dark one. */
    private final JButton theme = iconButton();
    private Runnable themeToggle = () -> {
    };
    /** Opens an HTML document in the browser, which renders it; shown for HTML only. */
    private final JButton browser = iconButton();
    private boolean previewing;
    /** The rendered Markdown, as the comparison window renders it too. */
    private final RenderedMarkdown preview = new RenderedMarkdown();
    private final HtmlPane rendered = preview.pane();
    private final JPanel center = new JPanel(new CardLayout());
    private Path file;
    private boolean document;
    private final SchemaRegistry schemas;
    private PanelState panel;
    private AppState state;
    private BiConsumer<Path, String> opener = (f, heading) -> {
    };
    private final JScrollPane renderedScroll = preview.scroll();
    /** A heading the preview scrolls to once it is laid out; null when none waits. EDT only. */
    private String pendingHeading;
    /** How long a log entry asked for waits for the text to hold it and the panel to be laid out. */
    static final long ENTRY_WAIT_MILLIS = 5_000;
    /** The time of the log entry the text scrolls to once it holds it, {@link Long#MIN_VALUE} when none waits. EDT only. */
    private long pendingEntry = Long.MIN_VALUE;
    private long pendingEntryUntil;

    public EditorPanel(PanelState panel, IntentSink intents, SchemaRegistry schemas) {
        super(new BorderLayout(0, 0));
        this.panel = panel;
        this.intents = intents;
        this.schemas = schemas;
        run.setIcon(Icons.get("play"));
        run.getAccessibleContext().setAccessibleName("Run");
        stop.setIcon(Icons.get("stop"));
        stop.getAccessibleContext().setAccessibleName("Stop");
        clear.setIcon(Icons.get("clear"));
        clear.getAccessibleContext().setAccessibleName("Clear");
        browser.setIcon(Icons.get("browser"));
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
        left.add(sessionCompact);
        left.add(sessionClear);
        sessionCompact.setIcon(Icons.get("compact"));
        sessionCompact.getAccessibleContext().setAccessibleName("Compact the conversation");
        sessionCompact.setToolTipText(SessionRow.COMPACT_TIP);
        sessionClear.setIcon(Icons.get("clear-conversation"));
        sessionClear.getAccessibleContext().setAccessibleName("Clear the conversation");
        left.add(Box.createHorizontalStrut(Theme.PAD));
        left.add(connection);
        left.add(connectionToggle);
        // icons close together, as the session's at the left
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        run.setToolTipText(KeyText.of("Run the selection or block (⌘⏎); to Claude ⌘⇧⏎, as SQL ⌥⏎"));
        stop.setToolTipText(KeyText.of("Stop the running SQL or interrupt the session (⌘.)"));
        clear.setToolTipText(KeyText.of("Clear the text (⌘Z brings it back)"));
        // a click leaves the keyboard in the editor, ready for the next edit or run: every icon button is unfocusable
        browser.setToolTipText("Open the page in the browser");
        browser.getAccessibleContext().setAccessibleName("Open in the browser");
        browser.setVisible(false);
        browser.addActionListener(e -> {
            if (file != null) {
                Browser.openFile(file);
            }
        });
        showMode();
        mode.addActionListener(e -> showPreview(!previewing));
        theme.addActionListener(e -> themeToggle.run());
        showTheme(false);
        right.add(browser);
        right.add(mode);
        right.add(theme);
        right.add(run);
        right.add(stop);
        right.add(clear);
        // the chips take what the buttons leave, never drawn under them
        header.add(left, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        rendered.setLinkHandler(href -> linkedDocument(file, href).ifPresent(f -> opener.accept(f, linkedHeading(href))));
        // a panel opened for a link to one of its headings is laid out after it is made
        renderedScroll.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                scrollToPendingHeading();
            }
        });
        // a log opened for one of its entries is laid out after it is made
        editor.component().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                showPendingEntry();
            }
        });
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
        sessionCompact.addActionListener(e -> state.session(panel.session())
                .ifPresent(x -> intents.dispatch(new Intent.SessionCommand(x.name(), SessionSpec.COMPACT_COMMAND))));
        sessionClear.addActionListener(e -> state.session(panel.session())
                .ifPresent(x -> intents.dispatch(new Intent.SessionCommand(x.name(), SessionSpec.CLEAR_COMMAND))));
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
        boolean renders = rendersAsPage();
        mode.setVisible(renders);
        browser.setVisible(document && isHtml());
        if (!renders && previewing()) {
            showPreview(false);
        } else if (renders && opened) {
            showPreview(true);
        }
    }

    /** False for a log: nothing writes into it, neither the user nor a tool. */
    public boolean editable() {
        return editor.area().isEditable();
    }

    /** A document ending in {@code .log}: read-only, and it follows its end as it grows. */
    boolean isLog() {
        return document && file != null && PanelTexts.isLog(file);
    }

    private void followEnd() {
        editor.area().setCaretPosition(editor.area().getDocument().getLength());
    }

    /** A panel's own text and a Markdown file show rendered, and switch to the text to edit. */
    private boolean rendersAsPage() {
        return file == null || markdownName(file);
    }

    private boolean isHtml() {
        return file != null && htmlName(file);
    }

    private static boolean markdownName(Path file) {
        String n = lowerName(file);
        return n.endsWith(".md") || n.endsWith(".markdown");
    }

    private static boolean htmlName(Path file) {
        String n = lowerName(file);
        return n.endsWith(".html") || n.endsWith(".htm");
    }

    private static String lowerName(Path file) {
        Path name = file.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * How a followed link opens its file: the shell opens it in a panel, or brings forward the panel that has it; with
     * the heading the link names after {@code #}, or null.
     */
    public void onOpenFile(BiConsumer<Path, String> open) {
        opener = open;
    }

    /** The heading a link names after the file's name, as written (percent-encoded or not); null when it names none. */
    static String linkedHeading(String href) {
        int hash = href.indexOf('#');
        return hash < 0 || hash == href.length() - 1 ? null : href.substring(hash + 1);
    }

    /**
     * Scrolls the preview to the heading whose id is {@code fragment}: now when it is laid out, else once it is. A panel
     * showing its text, not the preview, stays as it is.
     */
    public void showHeading(String fragment) {
        if (!previewing) {
            return;
        }
        pendingHeading = fragment;
        scrollToPendingHeading();
    }

    private void scrollToPendingHeading() {
        if (pendingHeading == null || !previewing || renderedScroll.getHeight() <= 0) {
            return;
        }
        String heading = pendingHeading;
        pendingHeading = null;
        rendered.scrollToHeading(heading);
    }

    /**
     * The Markdown or HTML file a link names, a relative one read from {@code document}'s folder; empty for a link to
     * anything else: a web page, a heading of the same document, a file comando does not render. A heading after the
     * file's name is not part of it: {@link #linkedHeading} reads it.
     */
    static Optional<Path> linkedDocument(Path document, String href) {
        Path folder = document == null ? null : document.toAbsolutePath().getParent();
        return LocalLinks.file(folder, href).filter(f -> markdownName(f) || htmlName(f));
    }

    /** The rendered view; for tests. */
    HtmlPane rendered() {
        return rendered;
    }

    boolean previewOffered() {
        return mode.isVisible();
    }

    /** The mode button's name: the view a click switches to; for tests. */
    String modeLabel() {
        return mode.getAccessibleContext().getAccessibleName();
    }

    public boolean previewing() {
        return previewing;
    }

    /** Shows the text to edit, when it shows rendered and may be edited. */
    public void edit() {
        if (mode.isVisible() && previewing) {
            showPreview(false);
        }
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
            preview.show(editor.text(), folder());
        } else {
            pendingHeading = null;
        }
        ((CardLayout) center.getLayout()).show(center, on ? PREVIEW : EDIT);
        showClear();
        if (!on) {
            editor.requestFocus();
        }
    }

    /** The folder of the panel's file, which its Markdown's relative image links are read from; null before it has one. */
    private Path folder() {
        return file == null ? null : file.toAbsolutePath().getParent();
    }

    /** For tests. */
    boolean browserOffered() {
        return browser.isVisible();
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

    /** How the theme button switches the editors; the workspace switches every panel's. */
    public void onThemeToggle(Runnable toggle) {
        themeToggle = toggle;
    }

    /** The light editor theme or the dark one, for the text and its rendered Markdown. */
    public void light(boolean on) {
        editor.light(on);
        preview.light(on);
        showTheme(on);
    }

    /** For tests. */
    boolean light() {
        return rendered.light();
    }

    private void showTheme(boolean light) {
        theme.setIcon(Icons.get("theme"));
        theme.setToolTipText(light ? "Switch every editor to dark" : "Switch every editor to light");
        theme.getAccessibleContext().setAccessibleName(light ? "Dark editors" : "Light editors");
    }

    private void showMode() {
        String label = previewing ? "Edit" : "Preview";
        mode.setIcon(Icons.get(previewing ? "edit" : "preview"));
        mode.setToolTipText(KeyText.of(previewing ? "Edit the Markdown (⌘⇧M)" : "Show the Markdown rendered (⌘⇧M)"));
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
        showPendingEntry();
        if (previewing()) {
            int at = rendered.getCaretPosition();
            rendered.setMarkdown(text, folder());
            rendered.setCaretPosition(Math.min(at, rendered.getDocument().getLength()));
        }
    }

    /**
     * Shows the entry of the log written at {@code atMillis} or within {@link LogLines#SLACK_MILLIS} after it: the
     * caret goes to its line, which scrolls to the upper third of the view and flashes. While the text does not hold it
     * (the file is read again after the entry is written) or the panel is not laid out, it waits, for
     * {@link #ENTRY_WAIT_MILLIS}.
     */
    public void showLogEntry(long atMillis) {
        pendingEntry = atMillis;
        pendingEntryUntil = System.currentTimeMillis() + ENTRY_WAIT_MILLIS;
        showPendingEntry();
    }

    private void showPendingEntry() {
        if (pendingEntry == Long.MIN_VALUE) {
            return;
        }
        if (System.currentTimeMillis() > pendingEntryUntil) {
            pendingEntry = Long.MIN_VALUE;
            return;
        }
        RSyntaxTextArea area = editor.area();
        JViewport port = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, area);
        if (port == null || port.getHeight() <= 0) {
            return;
        }
        int at = LogLines.offsetOf(editor.text(), pendingEntry);
        if (at < 0) {
            return;
        }
        pendingEntry = Long.MIN_VALUE;
        area.setCaretPosition(at);
        try {
            Rectangle2D line = area.modelToView2D(at);
            int height = port.getExtentSize().height;
            area.scrollRectToVisible(new Rectangle(0, Math.max(0, (int) line.getY() - height / 3), 1, height));
        } catch (BadLocationException e) {
            return; // the offset was just found in this text
        }
        Flash.line(area, at);
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
            session.setIcon(Icons.get("claude"));
            session.setText(chipText(ss));
            session.setToolTipText(Labels.shortPath(ss.spec().workDir()) + " · " + ss.status()
                    + (ss.error().isEmpty() ? "" : " · " + ss.error()));
            sessionToggle.setVisible(true);
            // the plug, as on the session's row in the sidebar: green while running, grey while not; a ▶ here would read as Run
            sessionToggle.setIcon(Icons.get(ss.status().isUsable() ? "plug" : "unplug"));
            sessionToggle.setToolTipText(ss.status().isUsable() ? "Connected; click to disconnect (stops the session)"
                    : "Disconnected; click to connect (starts the session)");
            sessionFresh.setVisible(showsFresh(ss));
            sessionFresh.setToolTipText(SessionRow.freshTip(ss));
            sessionCheckpoint.setVisible(showsCheckpoint(ss));
            sessionCheckpoint.setEnabled(ss.status() != Status.CLOSING);
            sessionCheckpoint.setIcon(Icons.get(checkpointIcon(ss)));
            sessionCheckpoint.setToolTipText(checkpointTip(ss));
            // as on the session's row: compact and clear only while it runs
            sessionCompact.setVisible(ss.status().isUsable());
            sessionClear.setVisible(ss.status().isUsable());
            sessionClear.setToolTipText(SessionRow.clearTip(ss));
        } else {
            sessionFresh.setVisible(false);
            sessionCheckpoint.setVisible(false);
            sessionCompact.setVisible(false);
            sessionClear.setVisible(false);
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

    /** The session chip: name, branch, status dot and what it does; a name from a stored conversation is shown as text. */
    private static String chipText(SessionState ss) {
        String branch = ss.branch().isEmpty() ? "" : "  ⎇ " + ss.branch();
        return Plain.text(ss.name() + branch + "  " + dot(ss.status()) + busyText(ss));
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
        String what = TabStatus.working(ss);
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
            state.session(panel.session()).filter(EditorPanel::counting).ifPresent(ss -> session.setText(chipText(ss)));
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
        String send = "sends " + Clip.keepStart(ss.spec().checkpoint(), 40);
        return ss.reflection().due() ? "Checkpoint due, context past " + Math.round(Reflection.THRESHOLD * 100) + "%: " + send
                : "Checkpoint: " + send;
    }

    private void toggleSession() {
        state.session(panel.session()).ifPresent(x -> intents.dispatch(x.status().isUsable()
                ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name())));
    }

    private JPopupMenu connectionMenu() {
        JPopupMenu m = new JPopupMenu();
        for (ConnectionState c : state.connections()) {
            JMenuItem item = new JMenuItem(Plain.text(c.name() + "  " + c.spec().label()), Icons.get("database"));
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
            JMenuItem item = new JMenuItem(Plain.text(x.name() + "  " + Labels.shortPath(x.spec().workDir()) + where), Icons.get("claude"));
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
