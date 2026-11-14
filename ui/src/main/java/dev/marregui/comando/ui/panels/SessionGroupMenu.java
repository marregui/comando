package dev.marregui.comando.ui.panels;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.icon.IconMenus;

/**
 * The menu of a selection of several sessions in the Sessions tab. Each item acts on every selected session it applies
 * to and skips the rest, so choosing it twice changes nothing the second time; it says how many it applies to, and is
 * disabled when that is none. Each item applies where the session's own menu offers it, by the same rules: a session
 * running its checkpoint takes Open and Stop, and waits for the rest (record 130). The targets are read when the menu
 * opens, from the state it was built on.
 */
final class SessionGroupMenu {

    private SessionGroupMenu() {
    }

    /** The menu for {@code selected}, in the selection's order, from {@code state}, the state it was read on. */
    static JPopupMenu menu(final List<SessionState> selected, final AppState state, final IntentSink intents) {
        final JPopupMenu m = new JPopupMenu();
        final Predicate<SessionState> free = s -> !s.isCheckpointing();
        // a new panel for a session without its own panel, as the reducer counts one; a session with one is left where
        // it is. The target only lends the new panel its connection: opening a session never takes the target, so the
        // focused panel keeps its session
        m.add(item("Open in a panel", "claude", selected, intents, s -> state.homePanel(s.name()).isEmpty(),
                s -> new Intent.OpenSession(s.name(), state.focused(), PanelId.random())));
        m.addSeparator();
        m.add(item("Start", "play", selected, intents, SessionBar::canStart, s -> new Intent.StartSession(s.name())));
        m.add(item("Stop", "stop", selected, intents, s -> s.status().isUsable(),
                s -> new Intent.StopSession(s.name())));
        m.add(item("Start a new conversation", "fresh", selected, intents, SessionBar::canStartFresh,
                s -> new Intent.StartFresh(s.name())));
        m.addSeparator();
        final List<SessionState> unlocked = selected.stream().filter(free).toList();
        m.add(choices("Model", SessionsSidebar.MODEL_ICON, SessionRow::modelName, unlocked,
                SessionsSidebar.modelChoices(unlocked, intents)));
        m.add(choices("Effort", SessionsSidebar.EFFORT_ICON, SessionRow::effortName, unlocked,
                SessionsSidebar.effortChoices(unlocked, intents)));
        return m;
    }

    /** An item dispatching {@code intent} for each of {@code selected} that {@code applies} holds for. */
    private static JMenuItem item(
            final String label,
            final String icon,
            final List<SessionState> selected,
            final IntentSink intents,
            final Predicate<SessionState> applies,
            final Function<SessionState, Intent> intent
    ) {
        final List<SessionState> targets = selected.stream().filter(applies).toList();
        final JMenuItem i = IconMenus.item(counted(label, targets.size(), selected.size()), icon,
                () -> targets.forEach(s -> intents.dispatch(intent.apply(s))));
        i.setEnabled(!targets.isEmpty());
        return i;
    }

    /**
     * The submenu of a setting over {@code unlocked}, the selected sessions not running their checkpoint, its choices
     * the session menu's own ({@link SessionsSidebar#modelChoices}): a value is checked when every one of them has it,
     * and sets it on each whose own value differs. The submenu names the value when they all share it, as a session's
     * own menu names its own; it is off when every selected session runs its checkpoint.
     */
    private static JMenu choices(
            final String label,
            final String icon,
            final Function<SessionState, String> current,
            final List<SessionState> unlocked,
            final List<JMenuItem> items
    ) {
        final List<String> held = unlocked.stream().map(current).distinct().toList();
        final JMenu menu = IconMenus.submenu(held.size() == 1 ? label + ": " + held.getFirst() : label, icon);
        items.forEach(menu::add);
        menu.setEnabled(!unlocked.isEmpty());
        return menu;
    }

    /** "Start 2 of 3": what the item does, and to how many of the selected sessions when not to all of them. */
    private static String counted(final String label, final int applies, final int of) {
        return applies == of ? label : label + " " + applies + " of " + of;
    }
}
