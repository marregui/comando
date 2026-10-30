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
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.async.Debouncer;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.overlay.Flash;
import dev.marregui.comando.ui.kit.text.Editors;
import dev.marregui.comando.ui.kit.text.HtmlPane;
import dev.marregui.comando.ui.kit.text.LogLines;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.RenderedMarkdown;
import dev.marregui.comando.ui.kit.text.SourceLinks;
import dev.marregui.comando.ui.kit.text.TextLinks;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widgets.editor.CommandEditor;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Document;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One editor panel: a header with its connection and its controls, and the command editor; its session is shown in the
 * session bar under the lower panel (record 105). A panel's own text is SQL and prompts; a panel opened on a file, such
 * as a document from the Files tab, is coloured as the file's language, and a Markdown one opens rendered. Such a panel
 * switches between the editor and the rendered text with its header button, which names the other view: Preview while
 * editing, Edit while previewing. An HTML file opens as its text, with a button that opens it in the browser. A link to
 * a file on this machine, of any kind, double-clicked in the rendered text, opens that file in a panel (record 94). A
 * production connection frames the panel in red.
 */
public final class EditorPanel extends JPanel {

    private final IntentSink intents;
    private final CommandEditor editor = new CommandEditor();
    private final JButton connection = chip();
    private final JButton connectionToggle = iconButton();
    /** The most characters of a connection's name shown beside its icon; the tooltip has it whole. */
    static final int NAME_CHARS = 24;
    /** A tooltip is one short line (`UiKeysTest`). */
    static final int TIP_CHARS = 80;
    private final JButton run = iconButton();
    private final JButton stop = iconButton();
    /** Empties the panel's own text in one step ⌘Z takes back; a document, a file of its own, is never emptied here. */
    private final JButton clear = iconButton();
    /**
     * Shows where the panel comes from in the sidebar (record 119): a document's file in the Files tab, else the
     * panel's session in the Sessions tab; not there for a panel with neither.
     */
    private final JButton locate = iconButton();
    private BiConsumer<Path, String> locator = (file, session) -> {
    };
    /** Shows the other view: the rendered Markdown while the text is edited, the text while it shows rendered. */
    private final JButton mode = iconButton();
    /** Opens an HTML document in the browser, which renders it; shown for HTML only. */
    private final JButton browser = iconButton();
    /** Shows or hides the document's outline at the right (record 113); shown where the text renders as a page. */
    private final JButton outlineButton = iconButton();
    private final OutlinePane outline = new OutlinePane();
    /** The text or the preview at the left, the outline at the right, while the outline shows. */
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    /** What the panel's body holds: the text or the preview, alone or beside the outline. */
    private final JPanel body = new JPanel(new BorderLayout());
    /** Whether the user wants the outline in this panel; it shows only where the text renders as a page. */
    private boolean outlineWanted;
    /** Hears the user show or hide the outline, so the next panel opens the same way. */
    private Consumer<Boolean> outlineKept = on -> { };
    /** Reads the headings again once typing pauses. */
    private final Debouncer outlineWait = new Debouncer(OUTLINE_WAIT_MILLIS, this::readOutline);
    /** The text read for the outline last, so a read already under way for older text is not shown. */
    private String outlineText;
    static final int OUTLINE_WAIT_MILLIS = 250;
    static final int OUTLINE_WIDTH = 220;
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
    /** The log entry's time to scroll to once the text holds it, {@link Long#MIN_VALUE} when none waits. EDT only. */
    private long pendingEntry = Long.MIN_VALUE;
    /**
     * A log's view shows its end, so what is appended keeps it there: true when it opens, then as the reader leaves it,
     * scrolled up to read, or brings it back to the end. Read and written on the EDT.
     */
    private boolean followsEnd = true;
    /** The view moves because a reload moves it, not the reader: {@link #followsEnd} stays as it was. */
    private boolean reloading;
    /** The log's viewport, watched once the panel holds a log. */
    private JViewport watched;
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
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, Theme.GAP, 4, Theme.GAP));
        // the tools at the right, the connection first: its icon connects or disconnects and shows the state, its name
        // binds another; icons close together
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right.add(connectionToggle);
        right.add(connection);
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
        outlineButton.setIcon(Icons.get("outline"));
        outlineButton.getAccessibleContext().setAccessibleName("Outline");
        showOutlineButton();
        outlineButton.addActionListener(e -> {
            outlineWanted = !outlineWanted;
            outlineKept.accept(outlineWanted);
            layOutBody();
        });
        outline.onOpen(this::goTo);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        // the outline at the right, beside the tools' button that shows it; a wider panel widens the text
        split.setResizeWeight(1);
        right.add(browser);
        right.add(outlineButton);
        right.add(mode);
        right.add(run);
        right.add(stop);
        right.add(locate);
        right.add(clear);
        header.add(right, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        rendered.setLinkHandler(href -> Links.open(href, linkFolder(), (f, heading) -> opener.accept(f, heading)));
        // a link in the text itself, double-clicked as in the rendered page (record 94)
        editor.area().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    followLinkAt(editor.area().viewToModel2D(e.getPoint()));
                }
            }
        });
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
        body.add(center, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        run.addActionListener(e -> runSelection(Target.AUTO));
        // a block's gutter mark runs that block, or the selection inside it, where the mark says it goes
        editor.onMarkClicked(line -> {
            run(editor.runText(line), Target.AUTO);
            editor.requestFocus();
        });
        stop.addActionListener(e -> intents.dispatch(new Intent.CancelRun(this.panel.id())));
        locate.addActionListener(e -> {
            if (document && file != null) {
                locator.accept(file, "");
            } else if (!this.panel.session().isEmpty()) {
                locator.accept(null, this.panel.session());
            }
        });
        clear.addActionListener(e -> {
            editor.clear();
            editor.requestFocus();
        });
        editor.area().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                showClear();
                outlineSoon();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                showClear();
                outlineSoon();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // attributes only: the text is the same
            }
        });
        showClear();
        showLocate();
        connection.addActionListener(e -> connectionMenu().show(connection, 0, connection.getHeight()));
        connectionToggle.addActionListener(e -> toggleConnection());
    }

    /** The tooltip of the sessions sidebar's menu item that has a session rewrite its panel. */
    static final String POLISH_TIP = "Have the session rewrite the panel's text as a professional Markdown document";

    /**
     * The prompt that has the panel's session rewrite {@code text}, the whole of the panel titled {@code title}, as a
     * professional Markdown document and put it in place of the panel's text: the author's intent and facts kept, the
     * sentences addressed to Claude dropped, nothing of its own added but the questions the text leaves open; then a
     * few lines on what changed beyond wording.
     */
    static String polishPrompt(String title, String text) {
        return "Rewrite the text below, the whole text of the comando panel \"" + title + "\", as a professional Markdown "
                + "document, and replace the panel's text with it (comando's ui_set_editor, panel \"" + title + "\"). Keep the "
                + "author's intent and every fact; correct spelling and names, checking them where you can; give it a title "
                + "and numbered sections, and tables where they make it denser; drop the sentences addressed to you as "
                + "instructions; add nothing of your own but a short section of the questions the text leaves open. Then say in "
                + "a few lines what changed beyond wording.\n\n" + text.strip();
    }

    public CommandEditor editor() {
        return editor;
    }

    /**
     * The last {@code max} characters of the text, read without copying the rest: where the user writes what a session
     * is asked, the newest at the end.
     */
    String textEnd(int max) {
        Document d = editor.area().getDocument();
        int from = Math.max(0, d.getLength() - max);
        try {
            return d.getText(from, d.getLength() - from);
        } catch (BadLocationException e) {
            return "";
        }
    }

    /** The first {@code max} characters of the text, read without copying the rest: a log's text may be large. */
    String textStart(int max) {
        Document d = editor.area().getDocument();
        try {
            return d.getText(0, Math.min(max, d.getLength()));
        } catch (BadLocationException e) {
            return "";
        }
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
            followsEnd = true;
            watchView();
        }
        showClear();
        showLocate();
        boolean renders = rendersAsPage();
        mode.setVisible(renders);
        outlineButton.setVisible(renders);
        if (opened || renders != outlineShows()) {
            layOutBody();
        }
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

    /**
     * A log is a live view of its file: what was appended is added at the end, and the reader's caret, selection and
     * scroll stay as they were; while the view shows the end it stays at the end. A file that did not grow from the
     * text shown (rolled over, truncated) replaces it.
     */
    private void reloadLog(String text) {
        RSyntaxTextArea area = editor.area();
        boolean follow = followsEnd;
        boolean selecting = area.getSelectionStart() != area.getSelectionEnd();
        reloading = true;
        try {
            String shown = editor.text();
            if (text.length() >= shown.length() && text.startsWith(shown)) {
                if (text.length() > shown.length()) {
                    editor.grow(text.substring(shown.length()));
                }
            } else {
                editor.reload(text);
            }
            if (follow) {
                if (!selecting) {
                    followEnd();
                }
                showEnd();
            }
        } finally {
            reloading = false;
        }
    }

    /** Scrolls the log's view to its last line, the caret and the selection left where they are. */
    private void showEnd() {
        JViewport v = viewport();
        if (v == null || v.getView() == null) {
            return;
        }
        // the text grew: the view takes its new height now, laid out directly (validate waits for a window on screen)
        v.doLayout();
        int bottom = Math.max(0, v.getView().getHeight() - v.getExtentSize().height);
        if (v.getViewPosition().y != bottom) {
            v.setViewPosition(new Point(v.getViewPosition().x, bottom));
        }
    }

    /** Whether the view shows the text's last line. */
    private boolean viewAtEnd(JViewport v) {
        Rectangle seen = v.getViewRect();
        return seen.y + seen.height >= v.getView().getHeight() - editor.area().getLineHeight();
    }

    private JViewport viewport() {
        return (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, editor.area());
    }

    /**
     * Watches the log's view: the reader scrolling up stops the following, back to the end resumes it; a log that
     * comes back to the front while following shows its end, as it grew while hidden.
     */
    private void watchView() {
        JViewport v = viewport();
        if (v == null || v == watched) {
            return;
        }
        watched = v;
        java.awt.Dimension[] extent = {v.getExtentSize()};
        v.addChangeListener(e -> {
            java.awt.Dimension now = v.getExtentSize();
            // laid out with a size: a tab never shown has none, and nothing scrolls it
            if (reloading || !isLog() || now.height <= 0) {
                return;
            }
            if (!now.equals(extent[0])) {
                // the view was resized or laid out, not scrolled: a log that follows its end shows it again
                extent[0] = now;
                if (followsEnd) {
                    Edt.later(this::showEndFollowing);
                }
                return;
            }
            followsEnd = viewAtEnd(v);
        });
        editor.area().addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && editor.area().isShowing()
                    && isLog() && followsEnd) {
                Edt.later(this::showEndFollowing);
            }
        });
    }

    /** Shows the end of a log that still follows it, without the move counting as the reader's. */
    private void showEndFollowing() {
        if (!followsEnd || !isLog()) {
            return;
        }
        reloading = true;
        try {
            showEnd();
        } finally {
            reloading = false;
        }
    }

    /** Whether new lines keep the log's view at its end; for tests. */
    boolean followsEnd() {
        return followsEnd;
    }

    /**
     * The outline as the user wants it in panels opened from now: shown or not, and {@code keep} hears each change of
     * it in this panel (record 113).
     */
    public void outline(boolean wanted, Consumer<Boolean> keep) {
        outlineWanted = wanted;
        outlineKept = keep;
        layOutBody();
    }

    /** What takes the keyboard when the panel gets it back: the rendered page while it shows, else the text. */
    public Component focusTarget() {
        return previewing ? rendered : editor.area();
    }

    /** Whether the outline shows: wanted, and the text renders as a page. */
    boolean outlineShows() {
        return body.getComponentCount() > 0 && body.getComponent(0) == split;
    }

    /** The outline; for tests. */
    OutlinePane outlinePane() {
        return outline;
    }

    /** Puts the outline beside the text or the preview when wanted and the text renders as a page, else removes it. */
    private void layOutBody() {
        boolean on = outlineWanted && rendersAsPage();
        showOutlineButton();
        if (on == outlineShows()) {
            return;
        }
        body.removeAll();
        if (on) {
            split.setLeftComponent(center);
            split.setRightComponent(outline.component());
            body.add(split, BorderLayout.CENTER);
            placeDivider();
            readOutline();
        } else {
            body.add(center, BorderLayout.CENTER);
        }
        body.revalidate();
        body.repaint();
    }

    /** The outline {@link #OUTLINE_WIDTH} wide at the split's right, once the split has a width. */
    private void placeDivider() {
        if (split.getWidth() > 0) {
            split.setDividerLocation(Math.max(0, split.getWidth() - OUTLINE_WIDTH - split.getDividerSize()));
            return;
        }
        split.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (split.getWidth() > 0) {
                    split.removeComponentListener(this);
                    placeDivider();
                }
            }
        });
    }

    private void showOutlineButton() {
        outlineButton.setToolTipText(outlineWanted ? "Hide the outline" : "Show the document's outline: a double click on a heading goes to it");
    }

    /** The text changed: the outline reads its headings again once typing pauses, while it shows. */
    private void outlineSoon() {
        if (outlineShows()) {
            outlineWait.request();
        }
    }

    /** Reads the headings off the EDT and lists them, unless the text changed again meanwhile. */
    private void readOutline() {
        String text = editor.text();
        outlineText = text;
        UiErrands.start("outline", () -> {
            List<MarkdownHtml.Section> sections = MarkdownHtml.sections(text);
            Edt.later(() -> {
                if (text.equals(outlineText)) {
                    outline.show(sections);
                }
            });
        });
    }

    /**
     * Goes to {@code section}: the preview scrolls its heading to the top, the text puts its line at the top with the
     * caret at its start; the keyboard goes there.
     */
    void goTo(MarkdownHtml.Section section) {
        if (previewing) {
            showHeading(section.id());
            rendered.requestFocusInWindow();
            return;
        }
        RSyntaxTextArea area = editor.area();
        try {
            int at = area.getLineStartOffset(Math.min(section.line(), Math.max(0, area.getLineCount() - 1)));
            area.setCaretPosition(at);
            Rectangle2D r = area.modelToView2D(at);
            if (r != null && SwingUtilities.getAncestorOfClass(JViewport.class, area) instanceof JViewport port) {
                int max = Math.max(0, area.getHeight() - port.getExtentSize().height);
                port.setViewPosition(new Point(port.getViewPosition().x, Math.min(max, (int) r.getY())));
            }
        } catch (BadLocationException e) {
            return;
        }
        area.requestFocusInWindow();
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

    /**
     * Scrolls the preview to the heading whose id is {@code fragment}: now when it is laid out, else once it is. A
     * panel showing its text, not the preview, stays as it is.
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
     * Where the panel's links are read from: its file's folder; for the panel's own text, its session's working
     * directory; else none.
     */
    Path linkFolder() {
        Path folder = folder();
        if (folder != null || state == null) {
            return folder;
        }
        return state.session(panel.session()).map(x -> Path.of(x.spec().workDir())).orElse(null);
    }

    /**
     * Follows the link in the text at {@code offset}, if there is one there: a web address, a {@code [[name]]} note, a
     * Markdown link or a {@code path:line} reference, as the transcript finds them. Reads only the line it is on.
     */
    boolean followLinkAt(int offset) {
        Element root = editor.area().getDocument().getDefaultRootElement();
        if (offset < 0) {
            return false;
        }
        Element line = root.getElement(root.getElementIndex(offset));
        String text;
        try {
            text = editor.area().getText(line.getStartOffset(), line.getEndOffset() - line.getStartOffset());
        } catch (BadLocationException e) {
            return false;
        }
        TextLinks.Ref ref = TextLinks.at(text, offset - line.getStartOffset(), true);
        if (ref == null) {
            return false;
        }
        if (ref.href().startsWith(SourceLinks.SCHEME)) {
            intents.dispatch(new Intent.OpenFile(panel.session(), SourceLinks.path(ref.href()), SourceLinks.line(ref.href())));
            return true;
        }
        return Links.open(ref.href(), linkFolder(), (f, heading) -> opener.accept(f, heading));
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

    /** The folder of the panel's file, which its Markdown's relative image links resolve against; null before one. */
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

    /** The Locate button: the Files tab's icon for a document, the Sessions tab's for a session's panel, else none. */
    private void showLocate() {
        boolean isFile = document && file != null;
        boolean isSession = panel != null && !panel.session().isEmpty();
        locate.setVisible(isFile || isSession);
        locate.setIcon(Icons.locate(isFile ? "files" : "session"));
        locate.setToolTipText(isFile ? "Show the file in the Files tab" : "Show the session in the Sessions tab");
        locate.getAccessibleContext().setAccessibleName(isFile ? "Show in Files" : "Show in Sessions");
    }

    /** Where the Locate button shows a document's file, or the panel's session, in the sidebar. */
    public void onLocate(BiConsumer<Path, String> locate) {
        locator = locate;
    }

    /** For tests: the Locate button. */
    JButton locateButton() {
        return locate;
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
        mode.setIcon(Icons.get(previewing ? "edit" : "preview"));
        mode.setToolTipText(KeyText.of(previewing ? "Edit the Markdown (⌘⇧M)" : "Show the Markdown rendered (⌘⇧M)"));
        mode.getAccessibleContext().setAccessibleName(label);
    }

    /**
     * The file changed outside comando: the editor takes the text, and the preview shows it when it is on. A log whose
     * caret was at its end stays at the end.
     */
    public void reload(String text) {
        if (isLog()) {
            reloadLog(text);
            showPendingEntry();
            return;
        }
        editor.reload(text);
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
        run(editor.runText(), target);
    }

    private void run(String text, Target target) {
        if (!text.isBlank()) {
            intents.dispatch(new Intent.RunText(panel.id(), text, target));
        }
    }

    public void render(PanelState p, AppState s) {
        panel = p;
        state = s;
        showLocate();
        Optional<ConnectionState> c = p.connection().isEmpty() ? Optional.empty() : s.connection(p.connection());
        Optional<SessionState> x = p.session().isEmpty() ? Optional.empty() : s.session(p.session());
        // the connection: one icon in the state's colour, a click connects or disconnects; the name beside it binds
        // another
        if (c.isPresent()) {
            ConnectionState cs = c.get();
            connectionToggle.setIcon(Icons.get(connectionIcon(cs.status())));
            String name = cut(cs.name(), NAME_CHARS);
            connectionToggle.setToolTipText(cut(name + " " + statusWord(cs.status()) + "; click to " + (cs.status().isUsable() ? "disconnect" : "connect")
                    + "; its name binds another" + (cs.error().isEmpty() ? "" : " · " + cs.error()), TIP_CHARS));
            connection.setVisible(true);
            connection.setText(name + " ▾");
            connection.setToolTipText(cut(cs.spec().label() + "; click to bind another connection or unbind", TIP_CHARS));
            setBorder(cs.spec().prod() ? BorderFactory.createLineBorder(Theme.DANGER, 2) : BorderFactory.createEmptyBorder(2, 2, 2, 2));
        } else {
            connectionToggle.setIcon(Icons.get("database-off"));
            connectionToggle.setToolTipText("No connection; click to bind one");
            connection.setVisible(false);
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
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

    /** Connects or disconnects the panel's connection; without one, opens the menu that binds one. */
    private void toggleConnection() {
        Optional<ConnectionState> c = panel.connection().isEmpty() ? Optional.empty() : state.connection(panel.connection());
        if (c.isEmpty()) {
            connectionMenu().show(connectionToggle, 0, connectionToggle.getHeight());
            return;
        }
        intents.dispatch(c.get().status().isUsable() ? new Intent.CloseConnection(c.get().name()) : new Intent.OpenConnection(c.get().name()));
    }

    /** {@code text}, ending in "…" when it is longer than {@code most} characters. */
    static String cut(String text, int most) {
        return text.length() <= most ? text : text.substring(0, most - 1) + "…";
    }

    /** The database icon in the colour of {@code s}: blue open, amber on its way, red failed, grey closed. */
    static String connectionIcon(Status s) {
        return switch (s) {
            case OPEN -> "database";
            case OPENING, CLOSING -> "database-starting";
            case FAILED -> "database-failed";
            case CLOSED -> "database-off";
        };
    }

    static String statusWord(Status s) {
        return switch (s) {
            case OPEN -> "connected";
            case OPENING -> "connecting";
            case CLOSING -> "disconnecting";
            case FAILED -> "failed";
            case CLOSED -> "disconnected";
        };
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
            m.add(new JMenuItem("No connections; New connection… in the Connections tab's menu adds one")).setEnabled(false);
        }
        return m;
    }

    static Color color(Status s) {
        return switch (s) {
            case OPEN -> Theme.OK;
            case OPENING, CLOSING -> Theme.ATTENTION;
            case FAILED -> Theme.DANGER;
            case CLOSED -> Theme.MUTED;
        };
    }

    /** A chip: a name with an icon, a button that opens its menu; the session bar's too. */
    static JButton chip() {
        JButton b = new JButton();
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        return b;
    }

    static JButton iconButton() {
        JButton b = new JButton();
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        return b;
    }
}
