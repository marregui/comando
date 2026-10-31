package dev.marregui.comando.ui.shell;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.HashSet;
import java.util.Set;
import javax.swing.SwingUtilities;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.panels.EditorPanel;
import dev.marregui.comando.ui.panels.LowerPanel;
import dev.marregui.comando.ui.widget.capability.Capabilities;

/**
 * Where the keyboard is and where it goes: the view a command means, and the keyboard given back to where it was when
 * a panel or view that held it closes (record 112). EDT only.
 */
final class FocusRouter {

    /**
     * What gives the keyboard away once the lower tab holding {@code shown} shows, after the render that selects it.
     */
    private record FocusAfterRender(Component shown, Runnable focus) {
    }

    private final ComandoUi.Dependencies deps;
    private final ShellParts parts;
    private final LayoutKeeper layout;
    /** Where the keyboard was when each panel and view opened, to give it back when it closes. */
    private final FocusOrigins focusOrigins = new FocusOrigins();
    private FocusAfterRender focusAfterRender;
    /** The panel in front at the last render. */
    private String renderedFront;
    /** The component the keyboard goes back to once it shows, and how many renders it may wait; null when none. */
    private Component focusBack;
    private int focusBackRenders;
    /** The lower tab the last render showed. */
    private LowerTab renderedLowerTab = LowerTab.SESSION;
    /** Where the keyboard goes back to after this render; null when nothing closed. */
    private FocusOrigins.Origin back;

    FocusRouter(final ComandoUi.Dependencies deps, final ShellParts parts, final LayoutKeeper layout) {
        this.deps = deps;
        this.parts = parts;
        this.layout = layout;
    }

    /** The lower tab the last render showed. */
    LowerTab renderedLowerTab() {
        return renderedLowerTab;
    }

    /**
     * Before the panes change at a render: what just opened remembers where the keyboard was, what just closed gives it
     * back after the render ({@link #afterRender}).
     */
    void beforeRender(final AppState s) {
        final Component keyboard = focusOwnerInFrame();
        final Set<String> openNow = openKeys(s);
        back = focusOrigins.onRender(openNow, originAt(keyboard, renderedFront), placeOf(s, keyboard),
                o -> o.owner().isDisplayable() && (o.ownerKey() == null ? !inLower(o.owner()) || o.front() == null || openNow.contains(o.front())
                        : openNow.contains(o.ownerKey())));
        renderedFront = "panel:" + s.focused().value();
    }

    /** After the panes changed at a render: the keyboard goes where the render left it to go. */
    void afterRender(final AppState s) {
        final LowerPanel lower = parts.lower();
        // the last card answered: the Session tab came forward, and the keyboard the inbox held goes with it
        final Component owner = focusOwnerInFrame();
        if (renderedLowerTab.equals(LowerTab.APPROVALS) && s.lowerTab().equals(LowerTab.SESSION)
                && owner != null && SwingUtilities.isDescendingFrom(owner, lower.inbox().component())) {
            final Component canvas = lower.transcript().canvas();
            focusAfterRender = new FocusAfterRender(canvas, canvas::requestFocusInWindow);
        }
        renderedLowerTab = s.lowerTab();
        if (back != null) {
            giveKeyboardBack(back, s);
            back = null;
        }
        if (focusBack != null) {
            if (focusBack.isShowing()) {
                final Component c = focusBack;
                focusBack = null;
                Edt.later(c::requestFocusInWindow);
            } else if (--focusBackRenders <= 0) {
                focusBack = null; // its tab was not brought forward: the keyboard does not jump there later
            }
        }
        if (focusAfterRender != null && focusAfterRender.shown().isShowing()) {
            final Runnable focus = focusAfterRender.focus();
            focusAfterRender = null;
            Edt.later(focus);
        }
    }

    /** Remembers that the panel or view {@code key} opened from {@code from}. */
    void opened(final String key, final FocusOrigins.Origin from) {
        focusOrigins.opened(key, from);
    }

    /**
     * Where an opening made now is opened from: where the keyboard is; but a keyboard left in a panel that is not in
     * front, or nowhere, counts as the panel in front, whose text or page takes it back.
     */
    FocusOrigins.Origin originNow() {
        return originAt(focusOwnerInFrame(), deps.state().get() == null ? null : "panel:" + deps.state().get().focused().value());
    }

    /**
     * Where the keyboard at {@code c} is, with {@code front} the panel in front: a keyboard left in a panel that is not
     * in front, or nowhere, counts as that panel, whose text or page takes it back.
     */
    private FocusOrigins.Origin originAt(Component c, final String front) {
        String key = keyOf(c);
        if (c == null || key != null && key.startsWith("panel:") && !key.equals(front)) {
            final EditorPanel p = front == null ? null : parts.workspace().panel(new PanelId(front.substring("panel:".length())));
            if (p == null) {
                return null;
            }
            c = p.focusTarget();
            key = front;
        }
        return new FocusOrigins.Origin(c, key, renderedLowerTab, front);
    }

    /** The place the user is at: a view the keyboard is in, else the panel in front. */
    private String placeOf(final AppState s, final Component keyboard) {
        final String key = keyOf(keyboard);
        return key != null && key.startsWith("view:") ? key : "panel:" + s.focused().value();
    }

    private boolean inLower(final Component c) {
        return SwingUtilities.isDescendingFrom(c, parts.lower().component());
    }

    /** The panels and views open, as {@link FocusOrigins} keys them. */
    private static Set<String> openKeys(final AppState s) {
        final Set<String> keys = new HashSet<>();
        s.panels().forEach(p -> keys.add("panel:" + p.id().value()));
        s.views().forEach(v -> keys.add("view:" + v.id()));
        return keys;
    }

    /** The panel or view that holds {@code c}, as {@link FocusOrigins} keys it; null outside both. */
    private String keyOf(final Component c) {
        if (c == null) {
            return null;
        }
        if (SwingUtilities.getAncestorOfClass(EditorPanel.class, c) instanceof EditorPanel p) {
            return "panel:" + p.panel().id().value();
        }
        long view = parts.lower().viewHolding(c);
        if (view < 0) {
            view = parts.side().viewHolding(c);
        }
        return view < 0 ? null : "view:" + view;
    }

    /**
     * Gives the keyboard back to where it was when the panel or view that held it opened: the panel the keyboard was
     * in, else the panel in front then, whose session the lower tabs (Session, Approvals, Terminal, Results) showed,
     * comes forward; then that lower tab or view; and the component takes the keyboard once it shows.
     */
    private void giveKeyboardBack(final FocusOrigins.Origin back, final AppState s) {
        final String key = back.ownerKey();
        final String panel = key != null && key.startsWith("panel:") ? key : back.front();
        if (panel != null && panel.startsWith("panel:")) {
            final PanelId id = new PanelId(panel.substring("panel:".length()));
            if (!s.focused().equals(id) && s.panel(id).isPresent()) {
                deps.intents().dispatch(new Intent.FocusPanel(id));
            }
        }
        if (key != null && key.startsWith("view:")) {
            final long id = Long.parseLong(key.substring("view:".length()));
            final boolean lowerView = s.views().stream().anyMatch(v -> v.id() == id && v.placement() == Placement.LOWER);
            if (lowerView && !s.lowerTab().equals(LowerTab.view(id))) {
                deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.view(id)));
            }
        } else if (SwingUtilities.isDescendingFrom(back.owner(), parts.lower().component()) && !s.lowerTab().equals(back.lowerTab())) {
            deps.intents().dispatch(new Intent.SelectLowerTab(back.lowerTab()));
        }
        focusBack = back.owner();
        focusBackRenders = 3;
    }

    // ---- the view a command means

    /** The component with the keyboard in the main window; while a popup or the palette has it, the last one it had. */
    Component focusOwnerInFrame() {
        final Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (c != null && SwingUtilities.getWindowAncestor(c) == parts.frame()) {
            return c;
        }
        return parts.frame().getMostRecentFocusOwner();
    }

    boolean editorFocused() {
        final EditorPanel p = parts.workspace().focused();
        final Component c = focusOwnerInFrame();
        return p != null && c != null && SwingUtilities.isDescendingFrom(c, p.editor().area());
    }

    Component editorArea() {
        final EditorPanel p = parts.workspace().focused();
        return p == null ? null : p.editor().area();
    }

    /**
     * The capability of the view the user means: the one holding the focus, else the lower tab in front, so ⌘F, ⌘⇧C
     * and zoom work on the results or transcript while the editor has the keyboard.
     */
    <T> T capability(final Class<T> type) {
        final T t = Capabilities.at(focusOwnerInFrame(), type);
        return t != null ? t : Capabilities.within(parts.lower().selected(), type);
    }

    /** The placed view that holds the focus, in the lower panel or the side pane; -1 when the focus is elsewhere. */
    long focusedView() {
        final Component c = focusOwnerInFrame();
        if (c == null) {
            return -1;
        }
        final long id = parts.lower().viewHolding(c);
        return id >= 0 ? id : parts.side().viewHolding(c);
    }

    /**
     * Selects a lower tab and gives {@code target} the keyboard once the tab shows: a component in a hidden tab
     * refuses focus, so the request waits for the render that switches the tab.
     */
    void showLowerAndFocus(final LowerTab tab, final Component target) {
        showLowerAndFocus(tab, target, target::requestFocusInWindow);
    }

    /**
     * Selects {@code tab} and runs {@code focus} once {@code shown} is on screen: a component in a tab that is not
     * showing yet refuses the keyboard, so the focus waits for the render that shows the tab.
     */
    void showLowerAndFocus(final LowerTab tab, final Component shown, final Runnable focus) {
        layout.lowerFor(deps.state().get(), true);
        if (deps.state().get().lowerTab().equals(tab) && shown.isShowing()) {
            focus.run();
            return;
        }
        focusAfterRender = new FocusAfterRender(shown, focus);
        deps.intents().dispatch(new Intent.SelectLowerTab(tab));
    }
}
