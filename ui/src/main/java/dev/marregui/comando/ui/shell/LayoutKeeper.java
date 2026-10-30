package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The window's panes and how they are laid out: the splits, the sidebar, the side pane and the lower area; their
 * sizes kept across restarts, and a session's arrangement applied once typing pauses. EDT only.
 */
final class LayoutKeeper {

    private static final System.Logger LOG = System.getLogger(LayoutKeeper.class.getName());
    static final long TYPING_PAUSE_MILLIS = 2_000;

    private record LayoutSnapshot(boolean sidebar, boolean sidePaneHidden, int lowerDivider) {
    }

    private final ComandoUi.Dependencies deps;
    private final WindowLayout windowLayout;
    private final ShellParts parts;
    /** A full render of the state, run at once. */
    private final Runnable render;
    private final JSplitPane outer;
    private final int outerDividerSize;
    private final JSplitPane vertical;
    private final JSplitPane editorAndSide;
    private final JPanel top;
    /** Each panel's lower divider, as last set while it was in front; brought back when it comes to the front. */
    private final Map<PanelId, Integer> panelDividers = new HashMap<>();
    /** The panel whose divider the window shows; null before the first render. */
    private PanelId dividerPanel;
    /** The lower divider's width as the look and feel draws it; none while the lower area is hidden. */
    private final int verticalDividerSize;
    /** The documents whose lower area the user asked for, while they were in front, in this window. */
    private final Set<PanelId> lowerAskedFor = new HashSet<>();
    /** The lower divider while the lower area last showed: where it comes back when a panel has none of its own. */
    private int shownDivider = -1;
    private boolean sidePaneShown;
    /** The user or Claude hid the side pane; a change in the side views shows it again. */
    private boolean sidePaneHidden;
    /** The side views at the last render, by id, with their versions. */
    private Map<Long, Long> sideVersions = Map.of();
    /** The panes as they were before a session last arranged them; Restore layout puts them back. */
    private LayoutSnapshot beforeArrange;
    /** The layout a session asked for while the user typed, applied at the pause; null when none waits. */
    private Layout pendingLayout;
    /** Saves the window layout a second after the last resize or move. */
    private Timer layoutSaver;
    /** When the user last pressed a key in the window; a session's layout change waits until typing pauses. */
    private long lastKeyNanos;
    private final AWTEventListener keyClock;

    LayoutKeeper(ComandoUi.Dependencies deps, WindowLayout windowLayout, ShellParts parts, Runnable render) {
        this.deps = deps;
        this.windowLayout = windowLayout;
        this.parts = parts;
        this.render = render;
        this.keyClock = e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getSource() instanceof Component c && SwingUtilities.getWindowAncestor(c) == parts.frame()) {
                lastKeyNanos = System.nanoTime();
            }
        };
        top = new JPanel(new BorderLayout());
        top.add(parts.workspace().component(), BorderLayout.CENTER);
        editorAndSide = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        editorAndSide.setResizeWeight(0.55);
        editorAndSide.setBorder(BorderFactory.createEmptyBorder());
        vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, parts.lower().component());
        vertical.setResizeWeight(0.45);
        vertical.setBorder(BorderFactory.createEmptyBorder());
        verticalDividerSize = vertical.getDividerSize();
        // a move of the divider, by the user, an arrangement or the window's resize, is the panel in front's
        vertical.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            // a hidden lower area gives the panels the whole height: that is no height the user chose
            if (dividerPanel != null && vertical.getDividerLocation() > 0 && parts.lower().component().isVisible()) {
                panelDividers.put(dividerPanel, vertical.getDividerLocation());
                shownDivider = vertical.getDividerLocation();
            }
        });
        // the session bar closes the column of the panels and the lower panel; the window's status bar runs under all
        JPanel column = new JPanel(new BorderLayout());
        column.add(vertical, BorderLayout.CENTER);
        column.add(parts.sessionBar().component(), BorderLayout.SOUTH);
        outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, parts.sidebar().component(), column);
        outer.setBorder(BorderFactory.createEmptyBorder());
        outerDividerSize = outer.getDividerSize();
    }

    /** The window's panes, the sidebar beside the rest. */
    Component component() {
        return outer;
    }

    /** Starts the clock of the user's keys in the window, which a session's arrangement waits on. */
    void watchKeys() {
        Toolkit.getDefaultToolkit().addAWTEventListener(keyClock, AWTEvent.KEY_EVENT_MASK);
    }

    /** Saves the layout a second after the window last moved or changed size. */
    void saveOnMove() {
        layoutSaver = new Timer(1_000, e -> saveQuietly());
        layoutSaver.setRepeats(false);
        parts.frame().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutSaver.restart();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                layoutSaver.restart();
            }
        });
    }

    /** Stops the save timer and the key clock. */
    void stop() {
        layoutSaver.stop();
        Toolkit.getDefaultToolkit().removeAWTEventListener(keyClock);
    }

    /**
     * Every window in {@code tint} now: what listens to the tint (the transcript, the tab card, rendered Markdown) lays
     * itself out or recolours in it, and the rest (icons, the Files tab, About) read it as they repaint.
     */
    static void paintIn(Tint tint) {
        Theme.tint(tint);
        for (Window w : Window.getWindows()) {
            w.repaint();
        }
    }

    // ---- the side pane

    boolean sidePaneShown() {
        return sidePaneShown;
    }

    /** The user asked for a side view: the side pane shows it, even when the user hid the pane earlier. */
    void revealSidePane() {
        sidePaneHidden = false;
    }

    /** Hides the side pane while it shows, else shows it. */
    void toggleSidePane() {
        sidePaneHidden = sidePaneShown;
        render.run();
    }

    /**
     * The side pane at a render, {@code hasSide} when it has views: a hidden side pane comes back for a new side view
     * of the user's or of the focused panel's session; another session's view, an update of a view, or only a change
     * of focus leaves it hidden; the user's own commands that show a side view reveal the pane themselves.
     */
    void sidePane(AppState s, boolean hasSide) {
        String mine = s.focusedPanel().session();
        Map<Long, Long> versions = new HashMap<>();
        for (var v : s.views()) {
            if (v.placement() == Placement.SIDE) {
                versions.put(v.id(), v.version());
                Long before = sideVersions.get(v.id());
                // a new view only: an update of a view the user has (a schema reloaded after a DDL) leaves it hidden
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
    }

    private void showSidePane(boolean show) {
        sidePaneShown = show;
        top.removeAll();
        if (show) {
            editorAndSide.setLeftComponent(parts.workspace().component());
            editorAndSide.setRightComponent(parts.side().component());
            top.add(editorAndSide, BorderLayout.CENTER);
            // place the divider once the split has a size; a stored position that would hide either side is ignored
            Edt.later(() -> {
                int width = editorAndSide.getWidth();
                int saved = windowLayout.sideDivider();
                if (width > 0 && saved >= 200 && saved <= width - 200) {
                    editorAndSide.setDividerLocation(saved);
                } else {
                    editorAndSide.setDividerLocation(0.55);
                }
            });
        } else {
            top.add(parts.workspace().component(), BorderLayout.CENTER);
        }
        top.revalidate();
        top.repaint();
    }

    // ---- the sidebar

    void toggleSidebar() {
        if (parts.sidebar().isShown()) {
            windowLayout.sidebarDivider(outer.getDividerLocation());
        }
        showSidebar(!parts.sidebar().isShown());
    }

    /**
     * Shows the sidebar at its saved width, or collapses it to its rail with no divider to drag. The split keeps
     * both sides visible: a hidden side keeps its old width when the split's size changes in the same layout.
     */
    private void showSidebar(boolean shown) {
        parts.sidebar().show(shown);
        outer.setDividerSize(shown ? outerDividerSize : 0);
        outer.setDividerLocation(shown ? windowLayout.sidebarDivider() : parts.sidebar().railWidth());
        outer.revalidate();
    }

    // ---- the lower area

    /**
     * The lower area for the panel in front. A document no session works in, a file opened to read, takes the whole
     * height and the lower area hides, until it is {@code asked} for while that document is in front (a lower tab
     * chosen, ⌃1–⌃4, a request waiting in Approvals); then it shows for that document for the rest of the window's
     * life. Any other panel shows it, at the height it had when that panel was last in front.
     */
    void lowerFor(AppState s, boolean asked) {
        PanelState front = s.focusedPanel();
        boolean reading = front.document() && front.session().isEmpty();
        if (reading && asked) {
            lowerAskedFor.add(front.id());
        }
        boolean shown = !reading || lowerAskedFor.contains(front.id());
        Component lower = parts.lower().component();
        if (shown != lower.isVisible()) {
            lower.setVisible(shown);
            vertical.setDividerSize(shown ? verticalDividerSize : 0);
            if (shown) {
                // placed again below, at the panel's own height or the last one shown
                dividerPanel = null;
            }
            vertical.revalidate();
        }
        if (shown) {
            lowerDividerOf(front.id());
        } else {
            dividerPanel = front.id();
        }
    }

    /**
     * The panel {@code front} came to the front: the lower divider goes where it was when that panel was last in
     * front; a panel never in front keeps the divider as it is, or takes the last one shown when the lower area was
     * hidden.
     */
    private void lowerDividerOf(PanelId front) {
        if (front.equals(dividerPanel)) {
            return;
        }
        dividerPanel = front;
        Integer at = panelDividers.get(front);
        if (at == null && shownDivider > 0 && vertical.getDividerLocation() > shownDivider) {
            at = shownDivider;
        }
        if (at != null && at != vertical.getDividerLocation()) {
            vertical.setDividerLocation(at);
        } else if (vertical.getDividerLocation() > 0) {
            panelDividers.put(front, vertical.getDividerLocation());
        }
    }

    // ---- a session's arrangement

    /** Applies a session's layout once the user has not typed for two seconds, so panes never move under the keys. */
    void arrangeWhenIdle(Layout layout) {
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
            beforeArrange = new LayoutSnapshot(parts.sidebar().isShown(), sidePaneHidden, vertical.getDividerLocation());
        }
        applyLayout(layout);
    }

    private void applyLayout(Layout layout) {
        if (layout.sidebar() != Layout.Toggle.KEEP && parts.sidebar().isShown() != (layout.sidebar() == Layout.Toggle.SHOW)) {
            toggleSidebar();
        }
        if (layout.sidePane() != Layout.Toggle.KEEP) {
            sidePaneHidden = layout.sidePane() == Layout.Toggle.HIDE;
            render.run();
        }
        if (!Double.isNaN(layout.lowerShare())) {
            vertical.setDividerLocation(1.0 - layout.lowerShare());
        }
        if (layout.focus() != null) {
            // the reducer checks again that the panel is still the session's: it may have been rebound meanwhile
            deps.intents().dispatch(new Intent.SessionFocus(layout.session(), layout.focus(), layout.tab()));
        }
    }

    boolean arranged() {
        return beforeArrange != null;
    }

    /** Puts the panes back as they were before a session last arranged them. */
    void restoreArranged() {
        LayoutSnapshot b = beforeArrange;
        if (b == null) {
            return;
        }
        beforeArrange = null;
        if (parts.sidebar().isShown() != b.sidebar()) {
            toggleSidebar();
        }
        sidePaneHidden = b.sidePaneHidden();
        render.run();
        vertical.setDividerLocation(b.lowerDivider());
        deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Restored the layout", false));
    }

    // ---- kept across restarts

    /** Puts back the window's bounds, its dividers, the sidebar, the side pane, the theme and Linear's site. */
    void restore() {
        int h = windowLayout.restoreBounds(parts.frame());
        parts.sidebar().selectTab(windowLayout.sidebarTab());
        parts.sidebar().manualOrder(windowLayout.manualOrder());
        NamedTheme theme = windowLayout.theme();
        Theme.ground(theme.ground);
        paintIn(theme.tint);
        parts.workspace().linear(windowLayout.linearSite(), windowLayout::linearSite);
        showSidebar(windowLayout.sidebarShown());
        vertical.setDividerLocation(windowLayout.lowerDivider(h));
        shownDivider = windowLayout.lowerDivider(h);
        panelDividers.putAll(windowLayout.panelDividers());
        sidePaneHidden = windowLayout.sidePaneHidden();
    }

    private void save() {
        // the dividers of panels that are open or can be reopened; a panel gone for good takes its own with it
        AppState s = deps.state().get();
        Map<PanelId, Integer> kept = new HashMap<>(panelDividers);
        kept.keySet().removeIf(id -> s.panel(id).isEmpty() && s.closedPanel(id).isEmpty());
        windowLayout.panelDividers(kept);
        windowLayout.save(parts.frame(), new WindowLayout.Saved(parts.sidebar().isShown(),
                parts.sidebar().isShown() ? outer.getDividerLocation() : -1, parts.sidebar().selectedTab(), parts.sidebar().manualOrder(),
                sidePaneHidden, vertical.getDividerLocation(), sidePaneShown ? editorAndSide.getDividerLocation() : -1));
    }

    /** Saves the layout; a failure is logged, not thrown. */
    void saveQuietly() {
        try {
            save();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot save the window layout", e);
        }
    }
}
