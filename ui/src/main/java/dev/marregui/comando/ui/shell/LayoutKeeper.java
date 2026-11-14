package dev.marregui.comando.ui.shell;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The window's panes and how they are laid out: the splits, the sidebar, the side pane and the lower area; their
 * sizes kept across restarts, and a session's arrangement applied once typing pauses. EDT only.
 */
final class LayoutKeeper {

    private static final System.Logger LOG = System.getLogger(LayoutKeeper.class.getName());
    static final long TYPING_PAUSE_MILLIS = 2_000;
    /**
     * The narrowest a stored partition divider leaves either side; one narrower is not put back. 200 px is a
     * judgement, not a measurement: room for a view's header, a short title beside its icon buttons.
     */
    private static final int MIN_SIDE_WIDTH = 200;
    /** The share of the width the panels take beside the partition when no divider is stored. */
    private static final double SIDE_SHARE = 0.55;
    /** The panels' share of a window's growth, the lower area taking the rest; set by hand with the first window. */
    private static final double PANELS_SHARE = 0.45;

    private record LayoutSnapshot(boolean sidebar, boolean sidePaneHidden, int lowerDivider, boolean transcriptAbove) {
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
    /** The partition's divider as the look and feel draws it; none while the partition is hidden. */
    private final int sideDividerSize;
    /** Stops the partition following the theme while out of a window. */
    private final Runnable stopSideTheme;
    private final JPanel top;
    /** Each panel's lower divider, as last set while it was in front; brought back when it comes to the front. */
    private final Map<PanelId, Integer> panelDividers = new HashMap<>();
    /** The panel whose divider the window shows; null before the first render. */
    private PanelId dividerPanel;
    /** The lower divider's width as the look and feel draws it; none while the lower area is hidden. */
    private final int verticalDividerSize;
    /** The lower area sits above the panels: a session panel is in front and the user keeps it there (record 144). */
    private boolean lowerAbove;
    /** The documents whose lower area the user asked for, while they were in front, in this window. */
    private final Set<PanelId> lowerAskedFor = new HashSet<>();
    /** The lower divider while the lower area last showed: where it comes back when a panel has none of its own. */
    private int shownDivider = -1;
    /**
     * The panels' height a flip set above before the split had a height to set it in, or -1: applied once the split is
     * laid out, as the lower area's height above them depends on the split's.
     */
    private int pendingDivider = -1;
    private boolean sidePaneShown;
    /** The partition's divider while it last showed, put back when it shows again; -1 before it first shows. */
    private int sideDivider = -1;
    /** The user or Claude hid the side pane; a change in the side views shows it again. */
    private boolean sidePaneHidden;
    /** The side views at the last render, by id, with their versions. */
    private Map<Long, Long> sideVersions = Map.of();
    /** The panels in the partition at the last render; null before the first, whose panels were there at the start. */
    private Set<PanelId> sidePanels;
    /** The types of the side views reopened at the start that have not shown yet: they leave a hidden pane hidden. */
    private final Set<String> reopening = new HashSet<>();
    /** The side panel focused at the last render; null when the focus was in the main tabs. */
    private PanelId sideFocused;
    /** The panes as they were before a session last arranged them; Restore layout puts them back. */
    private LayoutSnapshot beforeArrange;
    /** The layout a session asked for while the user typed, applied at the pause; null when none waits. */
    private Layout pendingLayout;
    /** Saves the window layout a second after the last resize or move. */
    private Timer layoutSaver;
    /** When the user last pressed a key in the window; a session's layout change waits until typing pauses. */
    private long lastKeyNanos;
    private final AWTEventListener keyClock;
    /** Puts each window opened in the mode of the theme chosen, as {@link #paintIn} puts those already open. */
    private final AWTEventListener windowLook = e -> {
        if (e.getID() == WindowEvent.WINDOW_OPENED && e.getSource() instanceof Window w) {
            appearance(w);
        }
    };
    /** The client property macOS reads for a window's title bar, its own appearance rather than the application's. */
    static final String WINDOW_APPEARANCE = "apple.awt.windowAppearance";

    LayoutKeeper(final ComandoUi.Dependencies deps, final WindowLayout windowLayout, final ShellParts parts, final Runnable render) {
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
        // the partition stays in the window while hidden, collapsed as the sidebar is, so every theme chosen reaches it
        editorAndSide = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, parts.workspace().component(),
                parts.side().component());
        editorAndSide.setResizeWeight(SIDE_SHARE);
        editorAndSide.setBorder(BorderFactory.createEmptyBorder());
        sideDividerSize = editorAndSide.getDividerSize();
        parts.side().component().setVisible(false);
        editorAndSide.setDividerSize(0);
        top.add(editorAndSide, BorderLayout.CENTER);
        // and while it is out of a window all the same, a theme chosen still reaches it
        stopSideTheme = Theme.followWithoutWindow(parts.side().component());
        vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, parts.lower().component());
        vertical.setResizeWeight(PANELS_SHARE);
        vertical.setBorder(BorderFactory.createEmptyBorder());
        verticalDividerSize = vertical.getDividerSize();
        // a move of the divider, by the user, an arrangement or the window's resize, is the panel in front's
        vertical.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            // a hidden lower area gives the panels the whole height: that is no height the user chose
            if (dividerPanel != null && vertical.getDividerLocation() > 0 && parts.lower().component().isVisible()) {
                panelDividers.put(dividerPanel, divider());
                shownDivider = divider();
            }
        });
        vertical.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                if (pendingDivider >= 0 && vertical.getHeight() > 0) {
                    final int panels = pendingDivider;
                    pendingDivider = -1;
                    divider(panels);
                }
            }
        });
        // the session bar closes the column of the panels and the lower panel; the window's status bar runs under all
        final JPanel column = new JPanel(new BorderLayout());
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
        Toolkit.getDefaultToolkit().addAWTEventListener(windowLook, AWTEvent.WINDOW_EVENT_MASK);
    }

    /** Saves the layout a second after the window last moved or changed size. */
    void saveOnMove() {
        layoutSaver = new Timer(1_000, e -> saveQuietly());
        layoutSaver.setRepeats(false);
        parts.frame().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                layoutSaver.restart();
            }

            @Override
            public void componentMoved(final ComponentEvent e) {
                layoutSaver.restart();
            }
        });
    }

    /** Stops the save timer, the key clock and the partition's theme. */
    void stop() {
        stopSideTheme.run();
        if (layoutSaver != null) {
            layoutSaver.stop();
        }
        Toolkit.getDefaultToolkit().removeAWTEventListener(keyClock);
        Toolkit.getDefaultToolkit().removeAWTEventListener(windowLook);
    }

    /**
     * How long a theme chosen fades in over the one before: the eye follows the change of ground rather than meeting
     * it at once, and the fade ends before the next action.
     */
    static final int THEME_FADE_MILLIS = 500;

    /**
     * Every window in {@code theme} now, its ground and tint applied in one pass: what listens to the tint (the
     * transcript, the tab card, rendered Markdown) lays itself out or recolours in it, and the rest (icons, the folder
     * tabs, About) read it as they repaint.
     */
    static void paintIn(final NamedTheme theme) {
        recolour(theme);
        titleBars();
    }

    /** Every window in {@code theme}, but for its title bar. */
    private static void recolour(final NamedTheme theme) {
        Theme.choose(theme.ground, theme.tint);
        for (final Window w : Window.getWindows()) {
            w.repaint();
        }
    }

    /** Every window's title bar in the mode of the theme chosen. */
    private static void titleBars() {
        for (final Window w : Window.getWindows()) {
            appearance(w);
        }
    }

    /**
     * {@code w}'s title bar in the mode of the theme chosen (record 127): light over a light theme, dark over a dark
     * one. The application starts dark, which its menus and the native file dialogs keep; each window takes its own.
     */
    static void appearance(final Window w) {
        if (w instanceof RootPaneContainer r) {
            r.getRootPane().putClientProperty(WINDOW_APPEARANCE, Theme.light() ? "NSAppearanceNameAqua" : "NSAppearanceNameDarkAqua");
        }
    }

    /**
     * Every window in {@code theme}, faded in over what they showed for {@link #THEME_FADE_MILLIS}: a picture of each
     * shown window is laid over it, the theme applied and {@code recolour} run underneath, and it fades into a picture
     * of the window in the new theme ({@link ThemeFade}). The title bars, which macOS draws and no picture covers,
     * change mode halfway, when the window is midway between the two themes, rather than at the start, when it still
     * shows the theme before. The theme chosen already is applied without a fade. EDT only.
     */
    static void fadeTo(final NamedTheme theme, final Runnable recolour) {
        final boolean change = Theme.groundChosen() != theme.ground || Theme.tint() != theme.tint;
        final Runnable apply = () -> {
            recolour(theme);
            recolour.run();
        };
        if (change) {
            ThemeFade.run(apply, THEME_FADE_MILLIS, LayoutKeeper::titleBars);
        } else {
            apply.run();
            titleBars();
        }
    }

    // ---- the side pane

    boolean sidePaneShown() {
        return sidePaneShown;
    }

    /** The user asked for a side view: the side pane shows it, even when the user hid the pane earlier. */
    void revealSidePane() {
        if (sidePaneHidden) {
            sidePaneHidden = false;
            render.run();
        }
    }

    /** Hides the side pane while it shows, else shows it. */
    void toggleSidePane() {
        sidePaneHidden = sidePaneShown;
        render.run();
    }

    /**
     * The side pane at a render, {@code hasSide} when it has panels or views: a hidden side pane comes back for a new
     * side view of the user's or of the focused panel's session, and for a panel moved there or focused there; another
     * session's view, an update of a view, or only a change of focus to the main tabs leaves it hidden; the user's own
     * commands that show a side view reveal the pane themselves.
     */
    void sidePane(final AppState s, final boolean hasSide) {
        final String mine = s.focusedPanel().session();
        final Map<Long, Long> versions = new HashMap<>();
        for (final var v : s.views()) {
            if (v.placement() == Placement.SIDE) {
                versions.put(v.id(), v.version());
                final Long before = sideVersions.get(v.id());
                // a new view only: an update of a view the user has (a schema reloaded after a DDL) leaves it hidden,
                // and so does a view the side held when comando last quit, open again
                final boolean reopened = before == null && v.session().isEmpty() && reopening.remove(v.spec().type());
                if (before == null && !reopened && (v.session().isEmpty() || v.session().equals(mine))) {
                    sidePaneHidden = false;
                }
            }
        }
        sideVersions = versions;
        final Set<PanelId> panels = new HashSet<>();
        for (final PanelState p : s.panels()) {
            if (p.side()) {
                panels.add(p.id());
                // a panel moved there, or focused there (⌘n, a session's focus), shows the partition
                final boolean focusedThere = p.id().equals(s.focused()) && !p.id().equals(sideFocused);
                if (sidePanels != null && (!sidePanels.contains(p.id()) || focusedThere)) {
                    sidePaneHidden = false;
                }
            }
        }
        sidePanels = panels;
        sideFocused = panels.contains(s.focused()) ? s.focused() : null;
        final boolean show = hasSide && !sidePaneHidden;
        if (show != sidePaneShown) {
            showSidePane(show);
        }
    }

    /**
     * Shows the partition at its saved width, or collapses it with no divider to drag; it never leaves the window, so
     * it is on the theme chosen while hidden too.
     */
    private void showSidePane(final boolean show) {
        if (sidePaneShown && !show) {
            sideDivider = editorAndSide.getDividerLocation();
        }
        sidePaneShown = show;
        parts.side().component().setVisible(show);
        editorAndSide.setDividerSize(show ? sideDividerSize : 0);
        if (show) {
            // place the divider once the split has a size; a stored position that would hide either side is ignored
            Edt.later(() -> {
                final int width = editorAndSide.getWidth();
                final int saved = sideDivider > 0 ? sideDivider : windowLayout.sideDivider();
                if (width > 0 && saved >= MIN_SIDE_WIDTH && saved <= width - MIN_SIDE_WIDTH) {
                    editorAndSide.setDividerLocation(saved);
                } else {
                    editorAndSide.setDividerLocation(SIDE_SHARE);
                }
            });
        }
        editorAndSide.revalidate();
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
    private void showSidebar(final boolean shown) {
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
     * life. Any other panel shows it, at the height it had when that panel was last in front. The panel in front is
     * the main tabs': a panel on the right side, focused or not, sits beside the lower area and leaves it as it is
     * (record 141).
     */
    void lowerFor(final AppState s, final boolean asked) {
        final PanelState focused = s.focusedPanel();
        final PanelState front = focused.side()
                ? parts.workspace().mainFront().flatMap(s::panel).orElse(focused)
                : focused;
        final boolean reading = front.document() && front.session().isEmpty();
        if (reading && asked) {
            lowerAskedFor.add(front.id());
        }
        final boolean shown = !reading || lowerAskedFor.contains(front.id());
        final Component lower = parts.lower().component();
        final boolean wasShown = lower.isVisible();
        if (shown != wasShown) {
            lower.setVisible(shown);
            vertical.setDividerSize(shown ? verticalDividerSize : 0);
            if (shown) {
                // placed again below, at the panel's own height or the last one shown
                dividerPanel = null;
            }
            vertical.revalidate();
        }
        // the transcript above the prompt while a session's panel is in front; no flip while the lower area is hidden,
        // whose divider then gives the panels the whole height: shown again, it flips from the last height shown
        if (shown) {
            place(windowLayout.transcriptAbove() && !front.document() && !front.session().isEmpty(),
                    wasShown ? divider() : shownDivider);
        }
        if (shown) {
            lowerDividerOf(front.id());
        } else {
            dividerPanel = front.id();
        }
    }

    /**
     * Puts the lower area {@code above} the panels or below them. A divider is kept as the panels' height when below,
     * whichever way it shows, so a panel's height comes back the same after a flip and in the files written before.
     */
    private void place(final boolean above, final int kept) {
        if (above == lowerAbove) {
            return;
        }
        // set while the components move, so the listener does not take the moves for the user's
        final PanelId was = dividerPanel;
        dividerPanel = null;
        lowerAbove = above;
        final Component lower = parts.lower().component();
        // taking the sides out moves the keyboard away from the component that has it, or had it last while the window
        // was in the background: it is given back after
        final Window window = SwingUtilities.getWindowAncestor(vertical);
        final Component owner = window == null ? null : window.getMostRecentFocusOwner();
        final boolean inside = owner != null && SwingUtilities.isDescendingFrom(owner, vertical);
        vertical.setTopComponent(null);
        vertical.setBottomComponent(null);
        vertical.setTopComponent(above ? lower : top);
        vertical.setBottomComponent(above ? top : lower);
        // the lower area takes the same, larger share of a window's growth either way
        vertical.setResizeWeight(above ? 1 - PANELS_SHARE : PANELS_SHARE);
        divider(kept);
        vertical.revalidate();
        if (inside) {
            owner.requestFocusInWindow();
        }
        dividerPanel = was;
    }

    /** The lower divider as the panels' height with the lower area below them, whichever way it shows. */
    private int divider() {
        if (pendingDivider >= 0) {
            return pendingDivider;
        }
        final int at = vertical.getDividerLocation();
        // above, a hidden lower area leaves the divider at 0: the panels' whole height, as it is at the bottom below
        return lowerAbove ? Math.max(0, height() - Math.max(0, at) - vertical.getDividerSize()) : at;
    }

    /** Puts the lower divider at {@code panels}, the panels' height with the lower area below them. */
    private void divider(final int panels) {
        if (lowerAbove && panels > 0 && height() <= 0) {
            pendingDivider = panels;
            return;
        }
        pendingDivider = -1;
        vertical.setDividerLocation(lowerAbove && panels > 0
                ? Math.max(0, height() - panels - vertical.getDividerSize()) : panels);
    }

    private int height() {
        // before the first layout the split has no height of its own yet: the window's content stands in for it
        return vertical.getHeight() > 0 ? vertical.getHeight() : parts.frame().getContentPane().getHeight();
    }

    /** Turns the transcript above the prompt on or off (record 144), kept for the next start. */
    void transcriptAbove(final boolean above) {
        windowLayout.transcriptAbove(above);
        render.run();
    }

    boolean transcriptAbove() {
        return windowLayout.transcriptAbove();
    }

    /**
     * The panel {@code front} came to the front: the lower divider goes where it was when that panel was last in
     * front; a panel never in front keeps the divider as it is, or takes the last one shown when the lower area was
     * hidden.
     */
    private void lowerDividerOf(final PanelId front) {
        if (front.equals(dividerPanel)) {
            return;
        }
        dividerPanel = front;
        Integer at = panelDividers.get(front);
        if (at == null && shownDivider > 0 && divider() > shownDivider) {
            at = shownDivider;
        }
        if (at != null && at != divider()) {
            divider(at);
        } else if (vertical.getDividerLocation() > 0) {
            panelDividers.put(front, divider());
        }
    }

    // ---- a session's arrangement

    /** Applies a session's layout once the user has not typed for two seconds, so panes never move under the keys. */
    void arrangeWhenIdle(final Layout layout) {
        final long idle = (System.nanoTime() - lastKeyNanos) / 1_000_000;
        if (lastKeyNanos != 0 && idle < TYPING_PAUSE_MILLIS) {
            // one layout waits for the pause: a newer one replaces it, so an older never lands after it
            final boolean waiting = pendingLayout != null;
            pendingLayout = layout;
            if (!waiting) {
                final Timer later = new Timer((int) (TYPING_PAUSE_MILLIS - idle) + 50, e -> {
                    final Layout next = pendingLayout;
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
            beforeArrange = new LayoutSnapshot(parts.sidebar().isShown(), sidePaneHidden, divider(),
                    windowLayout.transcriptAbove());
        }
        applyLayout(layout);
    }

    private void applyLayout(final Layout layout) {
        if (layout.sidebar() != Layout.Toggle.KEEP && parts.sidebar().isShown() != (layout.sidebar() == Layout.Toggle.SHOW)) {
            toggleSidebar();
        }
        if (layout.sidePane() != Layout.Toggle.KEEP) {
            sidePaneHidden = layout.sidePane() == Layout.Toggle.HIDE;
            render.run();
        }
        if (layout.transcript() != Layout.Toggle.KEEP) {
            transcriptAbove(layout.transcript() == Layout.Toggle.SHOW);
        }
        if (!Double.isNaN(layout.lowerShare())) {
            vertical.setDividerLocation(lowerAbove ? layout.lowerShare() : 1.0 - layout.lowerShare());
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
        final LayoutSnapshot b = beforeArrange;
        if (b == null) {
            return;
        }
        beforeArrange = null;
        if (parts.sidebar().isShown() != b.sidebar()) {
            toggleSidebar();
        }
        sidePaneHidden = b.sidePaneHidden();
        windowLayout.transcriptAbove(b.transcriptAbove());
        render.run();
        divider(b.lowerDivider());
        deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Restored the layout", false));
    }

    // ---- kept across restarts

    /**
     * Puts back the window's bounds, its dividers, the sidebar, the side pane and its Search and Changes views, the
     * theme and Linear's site.
     */
    void restore() {
        final int h = windowLayout.restoreBounds(parts.frame());
        parts.sidebar().selectTab(windowLayout.sidebarTab());
        parts.sidebar().manualOrder(windowLayout.manualOrder());
        final NamedTheme theme = windowLayout.theme();
        paintIn(theme);
        parts.workspace().linear(windowLayout.linearSite(), windowLayout::linearSite);
        showSidebar(windowLayout.sidebarShown());
        vertical.setDividerLocation(windowLayout.lowerDivider(h));
        shownDivider = windowLayout.lowerDivider(h);
        panelDividers.putAll(windowLayout.panelDividers());
        sidePaneHidden = windowLayout.sidePaneHidden();
        // the Search and the Changes views the right side held at the last quit open there again, the pane as it was
        for (final String type : windowLayout.sideViews()) {
            reopening.add(type);
            deps.intents().dispatch(type.equals("search")
                    ? new Intent.ShowView("", ViewSpec.Search.TITLE, new ViewSpec.Search(), Placement.SIDE)
                    : new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes(), Placement.SIDE));
        }
    }

    private void save() {
        // the dividers of panels that are open or can be reopened; a panel gone for good takes its own with it
        final AppState s = deps.state().get();
        final Map<PanelId, Integer> kept = new HashMap<>(panelDividers);
        kept.keySet().removeIf(id -> s.panel(id).isEmpty() && s.closedPanel(id).isEmpty());
        windowLayout.panelDividers(kept);
        windowLayout.sideViews(s.views().stream().filter(v -> v.placement() == Placement.SIDE).map(v -> v.spec().type())
                .filter(WindowLayout.KEPT_SIDE_VIEWS::contains).distinct().toList());
        windowLayout.save(parts.frame(), new WindowLayout.Saved(parts.sidebar().isShown(),
                parts.sidebar().isShown() ? outer.getDividerLocation() : -1, parts.sidebar().selectedTab(), parts.sidebar().manualOrder(),
                sidePaneHidden, divider(),
                sidePaneShown ? editorAndSide.getDividerLocation() : sideDivider));
    }

    /** Saves the layout; a failure is logged, not thrown. */
    void saveQuietly() {
        try {
            save();
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot save the window layout", e);
        }
    }
}
