package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.kit.text.SourceLinks;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Searchable;
import dev.marregui.comando.ui.widget.capability.Zoomable;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.HierarchyEvent;
import java.util.List;

/**
 * A session's transcript as structured blocks, painted by {@link TranscriptCanvas}. Holds the last
 * {@link #WINDOW} blocks, with a button for earlier ones; replaces items in place when the writer replaced
 * a block; follows the end while the user is at the end.
 *
 * <p>Whether to follow is remembered rather than read from the scroll bar alone: a transcript loaded while its
 * tab is hidden has a scroll pane that was never laid out, so its bar reads "at the top" and a later turn would
 * not be followed. The bar decides only while the pane is on screen, and only when the user moved it: a bar
 * whose range changed because blocks arrived says nothing about where the user wants to be. Becoming visible
 * scrolls a following transcript to its end. A prompt the user sent from a panel always lands in view: it
 * follows again. While it does not follow, a "Latest" button over the bottom right returns to the end.
 */
public final class TranscriptWidget implements Widget<TranscriptView>, Searchable, Copyable, Zoomable {

    static final int WINDOW = 2000;

    private final WidgetContext context;
    private final TranscriptCanvas canvas = new TranscriptCanvas(this::openLink);
    private final JScrollPane scroll = new JScrollPane(canvas);
    private final JButton earlier = new JButton();
    private final JButton latest = new JButton("↓ Latest");
    private final JPanel root = new JPanel(new BorderLayout());
    private final Overlay overlay = new Overlay(root);
    private Transcript transcript;
    private String session = "";
    private int windowStart;
    private int built;
    /** Follow the end on the next change; set from the scroll bar only while the pane is showing. */
    private boolean following = true;
    private int barMaximum = -1;
    private int barExtent = -1;
    private List<Approval> waiting = List.of();

    public TranscriptWidget(WidgetContext context) {
        this.context = context;
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        earlier.putClientProperty("JButton.buttonType", "borderless");
        earlier.addActionListener(e -> showEarlier());
        earlier.setVisible(false);
        latest.setToolTipText("Scroll to the latest block and follow it");
        latest.setFocusable(false);
        latest.addActionListener(e -> followLatest());
        root.add(earlier, BorderLayout.NORTH);
        root.add(new Layered(scroll, latest), BorderLayout.CENTER);
        scroll.getVerticalScrollBar().getModel().addChangeListener(e -> barChanged());
        scroll.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && scroll.isShowing() && following) {
                SwingUtilities.invokeLater(this::scrollToEnd);
            }
        });
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
    public void setWaiting(List<Approval> waiting) {
        if (waiting.equals(this.waiting)) {
            return;
        }
        this.waiting = List.copyOf(waiting);
        canvas.setWaiting(this.waiting);
    }

    /** The painter, for measurement and tests. */
    public TranscriptCanvas canvas() {
        return canvas;
    }

    @Override
    public void render(TranscriptView view) {
        if (view == null || view.transcript() == null) {
            transcript = null;
            session = "";
            reset(0);
            overlay.showEmpty("No session bound to this panel. Bind one from the sidebar, then type a prompt below.");
            return;
        }
        if (view.transcript() != transcript || !view.session().equals(session) || view.transcript().size() < built) {
            transcript = view.transcript();
            session = view.session();
            reset(Math.max(0, transcript.size() - WINDOW));
        }
        if (transcript.size() == 0) {
            overlay.showEmpty("Session " + session + " has no conversation yet. Type a prompt below, or run text from the editor.");
            return;
        }
        overlay.showContent();
        sync();
    }

    private void reset(int start) {
        canvas.clear();
        canvas.setWaiting(waiting);
        following = true;
        windowStart = start;
        built = start;
        updateEarlierButton();
        updateLatest();
    }

    private void sync() {
        if (scroll.isShowing()) {
            following = atEnd();
        }
        boolean follow = following;
        int n = transcript.size();
        for (int i = windowStart; i < built; i++) {
            Block b = transcript.get(i);
            if (b != null && canvas.blockAt(i) != b) {
                canvas.update(i, b);
            }
        }
        for (int i = built; i < n; i++) {
            Block b = transcript.get(i);
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
        if (follow) {
            SwingUtilities.invokeLater(this::scrollToEnd);
        }
    }

    /** A change of the bar's value alone is the user scrolling; a change of its range is content or the viewport. */
    private void barChanged() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        boolean ranged = bar.getMaximum() != barMaximum || bar.getVisibleAmount() != barExtent;
        barMaximum = bar.getMaximum();
        barExtent = bar.getVisibleAmount();
        if (ranged || !scroll.isShowing()) {
            return;
        }
        following = atEnd();
        updateLatest();
    }

    private void updateLatest() {
        boolean show = !following && transcript != null && transcript.size() > 0;
        if (latest.isVisible() != show) {
            latest.setVisible(show);
        }
    }

    private void followLatest() {
        following = true;
        updateLatest();
        scrollToEnd();
    }

    private void showEarlier() {
        int from = Math.max(0, windowStart - WINDOW);
        int end = built;
        reset(from);
        for (int i = from; i < end; i++) {
            Block b = transcript.get(i);
            if (b != null) {
                canvas.append(i, b);
            }
        }
        built = end;
        following = false;
        updateLatest();
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
    }

    private void updateEarlierButton() {
        earlier.setVisible(windowStart > 0);
        earlier.setText("Show " + Math.min(WINDOW, windowStart) + " earlier blocks (" + windowStart + " hidden)");
    }

    private void openLink(String href) {
        if (href.startsWith(SourceLinks.SCHEME)) {
            context.intents().dispatch(new Intent.OpenFile(session, SourceLinks.path(href), SourceLinks.line(href)));
        } else {
            Browser.open(href);
        }
    }

    private boolean atEnd() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
    }

    /** Lays the pane out first, so the bar's maximum covers blocks appended since the last layout. */
    private void scrollToEnd() {
        scroll.validate();
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setValue(bar.getMaximum());
    }

    /** Stops following the end, as the reader scrolling up does; for tests, where nothing is shown to scroll. */
    void scrolledAway() {
        following = false;
        updateLatest();
    }

    // ---- capabilities

    @Override
    public int find(String text, boolean forward) {
        return canvas.find(text, forward);
    }

    @Override
    public void clearFind() {
        canvas.clearFind();
    }

    /** The selection when there is one, else the loaded blocks as Markdown. */
    @Override
    public String copyText() {
        String selected = canvas.selectionText();
        return selected.isEmpty() ? canvas.markdown() : selected;
    }

    @Override
    public void zoom(int steps) {
        canvas.setZoom(canvas.zoom() + steps);
    }

    @Override
    public void resetZoom() {
        canvas.setZoom(0);
    }

    /** The scroll pane with a button floating over its bottom right, clear of the scroll bar. */
    private static final class Layered extends JPanel {
        private final JScrollPane scroll;
        private final JButton button;

        Layered(JScrollPane scroll, JButton button) {
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
            Dimension d = button.getPreferredSize();
            JScrollBar bar = scroll.getVerticalScrollBar();
            int barWidth = bar.isVisible() ? bar.getPreferredSize().width : 0;
            button.setBounds(Math.max(0, getWidth() - d.width - barWidth - Theme.PAD), Math.max(0, getHeight() - d.height - Theme.PAD), d.width, d.height);
        }
    }
}
