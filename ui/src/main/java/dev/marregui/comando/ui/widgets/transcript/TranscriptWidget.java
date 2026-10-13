package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.kit.text.SourceLinks;
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
import java.awt.Desktop;
import java.net.URI;

/**
 * A session's transcript as structured blocks, painted by {@link TranscriptCanvas}. Holds the last
 * {@link #WINDOW} blocks, with a button for earlier ones; replaces items in place when the writer replaced
 * a block; follows the end while the user is at the end.
 */
public final class TranscriptWidget implements Widget<TranscriptView>, Searchable, Copyable, Zoomable {

    static final int WINDOW = 2000;

    private final WidgetContext context;
    private final TranscriptCanvas canvas = new TranscriptCanvas(this::openLink);
    private final JScrollPane scroll = new JScrollPane(canvas);
    private final JButton earlier = new JButton();
    private final JPanel root = new JPanel(new BorderLayout());
    private final Overlay overlay = new Overlay(root);
    private Transcript transcript;
    private String session = "";
    private int windowStart;
    private int built;

    public TranscriptWidget(WidgetContext context) {
        this.context = context;
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        earlier.putClientProperty("JButton.buttonType", "borderless");
        earlier.addActionListener(e -> showEarlier());
        earlier.setVisible(false);
        root.add(earlier, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        Capabilities.mark(overlay, this);
        overlay.showEmpty("No session bound to this panel. Bind one from the sidebar, then type a prompt below.");
    }

    @Override
    public JComponent component() {
        return overlay;
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
        windowStart = start;
        built = start;
        updateEarlierButton();
    }

    private void sync() {
        boolean follow = atEnd();
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
            }
        }
        built = n;
        if (follow) {
            SwingUtilities.invokeLater(this::scrollToEnd);
        }
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
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
    }

    private void updateEarlierButton() {
        earlier.setVisible(windowStart > 0);
        earlier.setText("Show " + Math.min(WINDOW, windowStart) + " earlier blocks (" + windowStart + " hidden)");
    }

    private void openLink(String href) {
        if (href.startsWith(SourceLinks.SCHEME)) {
            context.intents().dispatch(new Intent.OpenFile(session, SourceLinks.path(href), SourceLinks.line(href)));
        } else if ((href.startsWith("https://") || href.startsWith("http://")) && Desktop.isDesktopSupported()) {
            Thread.ofVirtual().name("open-link").start(() -> {
                try {
                    Desktop.getDesktop().browse(URI.create(href));
                } catch (Exception e) {
                    System.getLogger(TranscriptWidget.class.getName()).log(System.Logger.Level.WARNING,
                            "cannot open " + href + ": " + e.getMessage());
                }
            });
        }
    }

    private boolean atEnd() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
    }

    private void scrollToEnd() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setValue(bar.getMaximum());
    }

    /** Scrolls to the last prompt and highlights it; false when there is none. */
    public boolean jumpToLastPrompt() {
        return canvas.jumpToLastPrompt();
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
}
