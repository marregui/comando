package dev.marregui.comando.ui.widgets.transcript;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.kit.text.LocalLinks;
import dev.marregui.comando.ui.kit.text.SourceLinks;
import dev.marregui.comando.ui.kit.theme.FocusRing;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Searchable;
import dev.marregui.comando.ui.widget.capability.Zoomable;
import dev.marregui.comando.ui.widgets.transcript.render.Style;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;

/**
 * A session's transcript as structured blocks, painted by {@link TranscriptCanvas}. Holds the whole conversation, every
 * block an item, laid out near the view only; replaces items in place when the writer replaced a block; follows the end
 * while the user is at the end, and otherwise keeps the block at the top of the view where it is while blocks arrive or
 * change. {@link Move} goes to the first or last block, the previous or next prompt, or a page up or down.
 *
 * <p>Whether to follow is remembered rather than read from the scroll bar alone: a transcript loaded while its
 * tab is hidden has a scroll pane that was never laid out, so its bar reads "at the top" and a later turn would
 * not be followed. The bar decides only while the pane is on screen, and only when the user moved it: a bar
 * whose range changed because blocks arrived says nothing about where the user wants to be. Becoming visible
 * scrolls a following transcript to its end. A prompt the user sent from a panel always lands in view: it
 * follows again. While it does not follow, a "Latest" button over the bottom right returns to the end.
 *
 * <p>A turn that ended on a report folds what came before it to one row. {@link #showReport()} stops following and puts
 * the last report at the top of the view, as soon as the pane is on screen.
 *
 * <p>While sub-agents run, a strip above the transcript names them; a sub-agent card's header links to its stored
 * conversation.
 */
public final class TranscriptWidget implements Widget<TranscriptView>, Searchable, Copyable, Zoomable {

    /** A way through the conversation, with its key: the same in the widget and in the palette (Session ▸ …). */
    public enum Move {
        FIRST("first", "Go to the first block", KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0)),
        LAST("last", "Go to the latest block and follow it", KeyStroke.getKeyStroke(KeyEvent.VK_END, 0)),
        PREVIOUS_PROMPT("previousPrompt", "Go to the previous prompt", KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK)),
        NEXT_PROMPT("nextPrompt", "Go to the next prompt", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK)),
        PAGE_UP("pageUp", "Page up the transcript", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0)),
        PAGE_DOWN("pageDown", "Page down the transcript", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0));

        /** The command's id: {@code transcript.} and this. */
        public final String id;
        public final String label;
        public final KeyStroke key;

        Move(final String id, final String label, final KeyStroke key) {
            this.id = "transcript." + id;
            this.label = label;
            this.key = key;
        }
    }

    private final WidgetContext context;
    private final TranscriptCanvas canvas = new TranscriptCanvas(this::openLink);
    private final JScrollPane scroll = new JScrollPane(canvas);
    private final JButton latest = new JButton("↓ Latest");
    private final JPanel root = new JPanel(new BorderLayout());
    private final AgentStrip strip = new AgentStrip(canvas::reveal, id -> openLink(TranscriptCanvas.SUBAGENT_SCHEME + id),
            System::currentTimeMillis);
    private final Overlay overlay = new Overlay(root);
    private Transcript transcript;
    private String session = "";
    private int built;
    /** Follow the end on the next change; set from the scroll bar only while the pane is showing. */
    private boolean following = true;
    /** {@link #showReport} was asked while the pane was not on screen: the report shows when the pane does. */
    private boolean reportAsked;
    /**
     * A scroll to the end is queued and has not run: until it runs the bar still shows the old place, which says
     * nothing about where the reader wants to be, so a change meanwhile does not read {@link #following} off it.
     */
    private boolean endQueued;
    private int barMaximum = -1;
    private int barExtent = -1;
    private List<Approval> waiting = List.of();
    private List<BackgroundTask> tasks = List.of();

    public TranscriptWidget(final WidgetContext context) {
        this.context = context;
        FocusRing.install(scroll);
        // the canvas paints no ground of its own: the viewport's shows through it
        SidebarLook.ground(scroll); // the panels' ground, which follows a ground chosen (record 99)
        scroll.getViewport().setOpaque(true);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        latest.setToolTipText("Scroll to the latest block and follow it");
        latest.setFocusable(false);
        latest.addActionListener(e -> followLatest());
        final JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        strip.setAlignmentX(0f);
        north.add(strip);
        root.add(north, BorderLayout.NORTH);
        root.add(new Layered(scroll, latest), BorderLayout.CENTER);
        scroll.getVerticalScrollBar().getModel().addChangeListener(e -> barChanged());
        canvas.onAgentsChanged(this::updateStrip);
        scroll.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && scroll.isShowing()) {
                if (reportAsked) {
                    SwingUtilities.invokeLater(this::scrollToReport);
                } else if (following) {
                    queueEnd();
                }
            }
        });
        for (final Move m : Move.values()) {
            // while the keyboard is in the transcript, over the scroll pane's own page keys; elsewhere the window's
            // command of the same key, when the component with the keyboard does not take the key first
            scroll.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(m.key, m.id);
            scroll.getActionMap().put(m.id, new AbstractAction() {
                @Override
                public void actionPerformed(final ActionEvent e) {
                    move(m);
                }
            });
        }
        Capabilities.mark(overlay, this);
        overlay.showEmpty("No session bound to this panel. Bind one from the sidebar, then type a prompt below.");
    }

    @Override
    public JComponent component() {
        return overlay;
    }

    /** For tests: whether the next change scrolls to the end. */
    boolean following() {
        return following;
    }

    /** For tests: whether the "Latest" button shows. */
    boolean latestShown() {
        return latest.isVisible();
    }

    /**
     * The approval cards waiting on the session this tab shows: the tool calls they hold up read "waiting for your
     * approval" instead of "running". Does nothing when the list equals the last one.
     */
    public void setWaiting(final List<Approval> waiting) {
        if (waiting.equals(this.waiting)) {
            return;
        }
        this.waiting = List.copyOf(waiting);
        canvas.setWaiting(this.waiting);
    }

    /**
     * The background work of the session this tab shows: its sub-agents without a running card get a chip in the
     * strip. Does nothing when the list equals the last one.
     */
    public void setBackgroundTasks(final List<BackgroundTask> tasks) {
        if (tasks.equals(this.tasks)) {
            return;
        }
        this.tasks = List.copyOf(tasks);
        canvas.setBackgroundTasks(this.tasks);
        updateStrip();
    }

    /** The strip of running sub-agents; for tests. */
    AgentStrip strip() {
        return strip;
    }

    private void updateStrip() {
        final boolean shown = strip.isVisible();
        strip.show(transcript == null ? List.of() : canvas.runningAgents(), tasks);
        if (shown != strip.isVisible() && following) {
            // the strip took or gave back a line of the view: a transcript that followed its end still shows it
            queueEnd(); // after the layout the change asked for
        }
    }

    /** What the widget says over a transcript with no blocks yet; empty for a session's own words. */
    private String whenEmpty = "";

    /** Says {@code text} over a transcript with no blocks yet, not a session's (a sub-agent's tab, record 109). */
    public void whenEmpty(final String text) {
        whenEmpty = text;
    }

    /** The painter, for measurement and tests. */
    public TranscriptCanvas canvas() {
        return canvas;
    }

    @Override
    public void render(final TranscriptView view) {
        if (view == null || view.transcript() == null) {
            transcript = null;
            session = "";
            reset();
            overlay.showEmpty("No session bound to this panel. Bind one from the sidebar, then type a prompt below.");
            return;
        }
        if (view.transcript() != transcript || !view.session().equals(session) || view.transcript().size() < built) {
            if (!view.session().equals(session)) {
                tasks = List.of(); // the previous session's tasks say nothing of this one's cards; its own come next
            }
            transcript = view.transcript();
            session = view.session();
            reset();
        }
        if (transcript.size() == 0) {
            overlay.showEmpty(whenEmpty.isEmpty() ? "Session " + session + " has no conversation yet. Type a prompt below, or run text from the editor." : whenEmpty);
            return;
        }
        overlay.showContent();
        sync();
    }

    private void reset() {
        canvas.clear();
        canvas.setWaiting(waiting);
        canvas.setBackgroundTasks(tasks);
        following = true;
        reportAsked = false;
        endQueued = false;
        built = 0;
        updateLatest();
        updateStrip();
    }

    private void sync() {
        if (scroll.isShowing() && !endQueued) {
            following = atEnd();
        }
        boolean follow = following;
        final int n = transcript.size();
        for (int i = 0; i < built; i++) {
            final Block b = transcript.get(i);
            if (b != null && canvas.blockAt(i) != b) {
                canvas.update(i, b);
            }
        }
        for (int i = built; i < n; i++) {
            final Block b = transcript.get(i);
            if (b != null) {
                canvas.append(i, b);
                if (b instanceof Block.UserPrompt p && !p.origin().isEmpty() && p.parent().isEmpty()) {
                    // the user just sent this from a panel: it lands in view and the answer is followed
                    follow = true;
                }
            }
        }
        built = n;
        following = follow;
        updateLatest();
        updateStrip();
        if (follow) {
            queueEnd();
        }
    }

    /** Queues one scroll to the end, after the layout the changes so far asked for; one already queued serves. */
    private void queueEnd() {
        if (!endQueued) {
            endQueued = true;
            SwingUtilities.invokeLater(this::scrollToEndIfFollowing);
        }
    }

    /**
     * A scroll queued while following; the reader scrolling away, or a report asked for, since then stopped the
     * following, and the view stays where it is.
     */
    private void scrollToEndIfFollowing() {
        endQueued = false;
        if (following) {
            scrollToEnd();
        }
    }

    /**
     * A change of the bar's value alone is the user scrolling; a change of its range is content or the viewport. While
     * a scroll to the end is queued, a move says nothing either: the canvas keeps the block in view in its place as rows
     * become exact, and the queued scroll runs at the next event.
     */
    private void barChanged() {
        final JScrollBar bar = scroll.getVerticalScrollBar();
        final boolean ranged = bar.getMaximum() != barMaximum || bar.getVisibleAmount() != barExtent;
        barMaximum = bar.getMaximum();
        barExtent = bar.getVisibleAmount();
        if (ranged || !scroll.isShowing() || endQueued) {
            return;
        }
        following = atEnd();
        updateLatest();
    }

    private void updateLatest() {
        final boolean show = !following && transcript != null && transcript.size() > 0;
        if (latest.isVisible() != show) {
            latest.setVisible(show);
        }
    }

    private void followLatest() {
        following = true;
        updateLatest();
        scrollToEnd();
    }

    /**
     * Goes the way {@code m} names. Going to the latest block follows it, as the "Latest" button does; any other way
     * follows only when it lands at the end.
     */
    public void move(final Move m) {
        if (transcript == null || transcript.size() == 0) {
            return;
        }
        if (m == Move.LAST) {
            followLatest();
            return;
        }
        scroll.validate();
        final JScrollBar bar = scroll.getVerticalScrollBar();
        final int y = bar.getValue();
        final int page = canvas.getScrollableBlockIncrement(canvas.getVisibleRect(), SwingConstants.VERTICAL, 1);
        final int to = switch (m) {
            case FIRST -> 0;
            case PREVIOUS_PROMPT -> canvas.promptTop(y, false);
            case NEXT_PROMPT -> canvas.promptTop(y, true);
            case PAGE_UP -> Math.max(0, y - page);
            case PAGE_DOWN -> y + page;
            case LAST -> bar.getMaximum();
        };
        if (to < 0) {
            return; // no prompt that way
        }
        bar.setValue(to);
        following = atEnd();
        updateLatest();
    }

    /**
     * Shows the last turn's report from its first line: the transcript stops following its end and the report's top
     * goes to the top of the view. A transcript with no report stays where it is. On a pane not on screen yet the move
     * waits for it to show.
     */
    public void showReport() {
        if (canvas.reportTop() < 0) {
            return;
        }
        following = false;
        updateLatest();
        if (scroll.isShowing()) {
            scrollToReport();
        } else {
            reportAsked = true;
        }
    }

    private void scrollToReport() {
        reportAsked = false;
        scroll.validate();
        final int top = canvas.reportTop();
        if (top >= 0) {
            scroll.getVerticalScrollBar().setValue(top);
        }
    }

    /**
     * Follows a link in the transcript; package-private for tests. A Markdown link to a file the session's folder does
     * not hold is looked for as a bare file reference is (record 111): sessions name files relative to the folder they
     * worked in, which may lie deeper than their own.
     */
    void openLink(final String href) {
        if (href.startsWith(SourceLinks.SCHEME)) {
            context.intents().dispatch(new Intent.OpenFile(session, SourceLinks.path(href), SourceLinks.line(href)));
        } else if (href.startsWith(TranscriptCanvas.SUBAGENT_SCHEME)) {
            context.intents().dispatch(new Intent.OpenSubagent(session, href.substring(TranscriptCanvas.SUBAGENT_SCHEME.length())));
        } else {
            final Path folder = Links.folderOf(session);
            final Optional<Path> file = folder == null || !LocalLinks.isRelative(href) ? Optional.empty()
                    : LocalLinks.file(folder, href);
            if (file.isPresent() && !Files.exists(file.get())) {
                final Path relative = folder.toAbsolutePath().normalize().relativize(file.get());
                context.intents().dispatch(new Intent.OpenFile(session, relative.toString(), 0));
            } else {
                Links.open(href, folder);
            }
        }
    }

    private boolean atEnd() {
        final JScrollBar bar = scroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
    }

    /** Lays the pane out first, so the bar's maximum covers blocks appended since the last layout. */
    private void scrollToEnd() {
        scroll.validate();
        final JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setValue(bar.getMaximum());
    }

    /** Stops following the end, as the reader scrolling up does; for tests, where nothing is shown to scroll. */
    void scrolledAway() {
        following = false;
        updateLatest();
    }

    // ---- capabilities

    @Override
    public int find(final String text, final boolean forward) {
        return canvas.find(text, forward);
    }

    @Override
    public void rewindFind() {
        canvas.rewindFind();
    }

    @Override
    public void clearFind() {
        canvas.clearFind();
    }

    /** The selection when there is one, else every block as Markdown. */
    @Override
    public String copyText() {
        final String selected = canvas.selectionText();
        return selected.isEmpty() ? canvas.markdown() : selected;
    }

    /** A selection lies in an item, so there is something to copy when the canvas holds one. */
    @Override
    public boolean canCopy() {
        return canvas.itemCount() > 0;
    }

    @Override
    public void zoom(final int steps) {
        canvas.setZoom(Style.clampZoom(canvas.zoom() + steps));
    }

    @Override
    public void resetZoom() {
        canvas.setZoom(0);
    }

    /** The scroll pane with a button floating over its bottom right, clear of the scroll bar. */
    private static final class Layered extends JPanel {
        private final JScrollPane scroll;
        private final JButton button;

        Layered(final JScrollPane scroll, final JButton button) {
            super(null);
            this.scroll = scroll;
            this.button = button;
            button.setVisible(false);
            // the first child paints last, so the button stays above the canvas
            add(button);
            add(scroll);
        }

        @Override
        public boolean isOptimizedDrawingEnabled() {
            return false;
        }

        @Override
        public Dimension getPreferredSize() {
            return scroll.getPreferredSize();
        }

        @Override
        public void doLayout() {
            scroll.setBounds(0, 0, getWidth(), getHeight());
            final Dimension d = button.getPreferredSize();
            final JScrollBar bar = scroll.getVerticalScrollBar();
            final int barWidth = bar.isVisible() ? bar.getPreferredSize().width : 0;
            button.setBounds(Math.max(0, getWidth() - d.width - barWidth - Theme.PAD), Math.max(0, getHeight() - d.height - Theme.PAD), d.width, d.height);
        }
    }
}
