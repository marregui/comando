package dev.marregui.comando.core.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;

import static dev.marregui.comando.core.reduce.Shared.freshPanel;
import static dev.marregui.comando.core.reduce.Shared.notice;
import static dev.marregui.comando.core.reduce.Shared.savePanels;

/**
 * The panels' rules: adding, closing, removing, reopening, moving, placing, pinning, renaming, focusing and binding
 * panels, a session's own panel, and edits to a panel's text.
 */
final class PanelRules {

    private PanelRules() {
    }

    /**
     * Adds a panel at the end of the main tabs and focuses it. A preview takes the place of the preview before it,
     * which is dropped as a closed document is; the preview's file opened again brings it forward, and opened to stay
     * pins it (record 141).
     */
    static Transition addPanel(final AppState s, final Intent.AddPanel i) {
        final Optional<PanelState> open = s.panel(i.panel());
        if (open.isPresent()) {
            if (!open.get().preview()) {
                return Transition.of(s);
            }
            if (i.preview()) {
                return Transition.of(s.withFocused(i.panel()));
            }
            final AppState next = s.withPanel(open.get().withPreview(false)).withFocused(i.panel());
            return new Transition(next, List.of(savePanels(next)));
        }
        final PanelState current = s.focusedPanel();
        final List<Effect> effects = new ArrayList<>();
        AppState base = s;
        boolean side = false;
        final Optional<PanelState> before = i.preview()
                ? s.panels().stream().filter(PanelState::preview).findFirst()
                : Optional.empty();
        if (before.isPresent()) {
            side = before.get().side();
            effects.add(new Effect.ForgetPanel(before.get().id()));
            // the preview replaced stays in place, untitled, until the new one takes its slot: its title is free, and
            // the state keeps a panel and its focus meanwhile
            base = withoutRun(s, before.get().id(), effects).withPanel(before.get().withTitle(""));
        }
        // a title names a panel to the tools and keys its plots: a title already taken gets a number, "README.md 2"; a
        // session's name is its own panel's, taken too
        final String title = i.title().isBlank() ? defaultTitle(base) : uniqueTitle(base, i.title().strip());
        final List<PanelState> panels = new ArrayList<>(base.panels());
        final PanelState added = new PanelState(i.panel(), title, current.connection(), "", i.document(), side,
                i.preview());
        if (before.isPresent()) {
            panels.set(s.panels().indexOf(before.get()), added);
        } else {
            panels.add(added);
        }
        final AppState next = base.withPanels(panels, i.panel());
        effects.add(savePanels(next));
        return new Transition(next, effects);
    }

    /** A panel of comando's own closes into the closed list, keeping its file and bindings; a document is dropped. */
    static Transition closePanel(final Clock clock, final AppState s, final PanelId id) {
        return dropPanel(clock, s, id, false);
    }

    /** Removes a panel: an open one closes first; either leaves the lists, and its own file is deleted. */
    static Transition removePanel(final Clock clock, final AppState s, final PanelId id) {
        // a session's own panel and the session are one: removing either removes both (record 88)
        final Optional<PanelState> own = s.panel(id).or(() -> s.closedPanel(id)).filter(p -> !p.document() && !p.session().isEmpty());
        if (own.isPresent() && s.session(own.get().session()).isPresent()) {
            return SessionRules.removeSession(clock, s, own.get().session());
        }
        final Optional<PanelState> closed = s.closedPanel(id);
        if (closed.isPresent()) {
            final AppState next = notice(clock, s.withClosed(s.closed().stream().filter(p -> !p.id().equals(id)).toList()), Notice.Level.INFO,
                    "Removed " + closed.get().title());
            return new Transition(next, List.of(new Effect.DeletePanel(id), savePanels(next)));
        }
        return dropPanel(clock, s, id, true);
    }

    /**
     * The main tabs keep one panel at least: the last one there is not closed, and removed it leaves an empty one in
     * its place. A panel keeps its side when closed, so it reopens where it was; a preview is never kept closed.
     */
    static Transition dropPanel(final Clock clock, AppState s, final PanelId id, final boolean remove) {
        final Optional<PanelState> panel = s.panel(id);
        if (panel.isEmpty()) {
            return Transition.of(s);
        }
        if (!panel.get().side() && mainTabs(s) == 1) {
            if (!remove) {
                final String why = s.panels().size() == 1 ? "The last panel cannot be closed"
                        : "The last panel of the main tabs cannot be closed";
                return Transition.of(notice(clock, s, Notice.Level.WARN, why));
            }
            // removing the last open panel (by the user, or its file deleted outside) leaves an empty one in its place
            // nextId starts again at every run, while closed panels keep their ids: skip any id a panel holds
            final Shared.Fresh fresh = freshPanel(s);
            s = fresh.state();
            final PanelState empty = new PanelState(fresh.id(), defaultTitle(s), panel.get().connection(), "");
            final List<PanelState> withEmpty = new ArrayList<>(s.panels());
            withEmpty.add(withEmpty.indexOf(panel.get()) + 1, empty);
            s = s.withPanels(withEmpty, s.focused());
        }
        final List<Effect> effects = new ArrayList<>();
        AppState next = withoutRun(s, id, effects);
        final int idx = s.panels().indexOf(panel.get());
        final List<PanelState> panels = new ArrayList<>(s.panels());
        panels.remove(idx);
        final PanelId focused = s.focused().equals(id) ? neighbour(panels, idx, panel.get().side()).id() : s.focused();
        next = next.withPanels(panels, focused);
        if (remove) {
            next = notice(clock, next, Notice.Level.INFO, "Removed " + panel.get().title());
            effects.add(new Effect.DeletePanel(id));
        } else {
            effects.add(new Effect.ForgetPanel(id));
            if (!panel.get().document() && !panel.get().preview()) {
                final List<PanelState> closed = new ArrayList<>(next.closed());
                closed.add(panel.get());
                next = next.withClosed(closed);
            }
        }
        effects.add(savePanels(next));
        return new Transition(next, effects);
    }

    /** The state without {@code id}'s SQL run: a run still going is cancelled, and its result let go. */
    private static AppState withoutRun(final AppState s, final PanelId id, final List<Effect> effects) {
        final Optional<SqlRun> run = s.runOf(id);
        if (run.isEmpty()) {
            return s;
        }
        if (run.get().status().isActive()) {
            effects.add(new Effect.CancelSql(run.get().requestId()));
        }
        effects.add(new Effect.ReleaseResult(run.get().requestId()));
        return s.withRuns(s.runs().stream().filter(r -> !r.panel().equals(id)).toList());
    }

    /**
     * The panel to focus once the one at {@code idx} has gone: the nearest before it in the same tabs, else after it,
     * else the one before it in the other tabs.
     */
    private static PanelState neighbour(final List<PanelState> panels, final int idx, final boolean side) {
        for (int j = idx - 1; j >= 0; j--) {
            if (panels.get(j).side() == side) {
                return panels.get(j);
            }
        }
        for (int j = idx; j < panels.size(); j++) {
            if (panels.get(j).side() == side) {
                return panels.get(j);
            }
        }
        return panels.get(Math.max(0, idx - 1));
    }

    /** How many open panels the main tabs hold. */
    private static long mainTabs(final AppState s) {
        return s.panels().stream().filter(p -> !p.side()).count();
    }

    /**
     * A closed panel of comando's own comes back after the focused one, with its title, bindings and text, in the tabs
     * it was closed in.
     */
    static Transition reopenPanel(final AppState s, final Intent.ReopenPanel i) {
        if (s.panel(i.panel()).isPresent()) {
            return Transition.of(s.withFocused(i.panel()));
        }
        return s.closedPanel(i.panel()).map(p -> reopen(s, p)).orElse(Transition.of(s));
    }

    private static Transition reopen(final AppState s, final PanelState p) {
        final List<PanelState> panels = new ArrayList<>(s.panels());
        // after the focused panel when both are in the same tabs, else at the end of its own
        final PanelState focused = s.focusedPanel();
        final int at = focused.side() == p.side() ? s.panels().indexOf(focused) + 1 : panels.size();
        panels.add(Math.min(at, panels.size()), p);
        final AppState next = s.withPanels(panels, p.id()).withClosed(s.closed().stream().filter(c -> !c.id().equals(p.id())).toList());
        return new Transition(next, List.of(savePanels(next)));
    }

    /** An open panel moves among the open ones; saved, so a restart keeps the order. */
    static Transition movePanel(final AppState s, final Intent.MovePanel i) {
        final List<PanelState> panels = new ArrayList<>(s.panels());
        final int from = panels.stream().map(PanelState::id).toList().indexOf(i.panel());
        if (from < 0) {
            return Transition.of(s);
        }
        final int to = Math.clamp(i.index(), 0, panels.size() - 1);
        if (from == to) {
            return Transition.of(s);
        }
        panels.add(to, panels.remove(from));
        final AppState next = s.withPanels(panels, s.focused());
        return new Transition(next, List.of(savePanels(next)));
    }

    /**
     * An open panel moves to the end of the right-hand partition ({@code side}) or of the main tabs, and is focused;
     * the last panel of the main tabs stays there (record 141).
     */
    static Transition placePanel(final Clock clock, final AppState s, final Intent.PlacePanel i) {
        final Optional<PanelState> panel = s.panel(i.panel());
        if (panel.isEmpty()) {
            return Transition.of(s);
        }
        final Optional<String> refused = placeRefusal(s, i);
        if (refused.isPresent()) {
            return Transition.of(notice(clock, s, Notice.Level.WARN, refused.get()));
        }
        if (panel.get().side() == i.side()) {
            return Transition.of(s.withFocused(i.panel()));
        }
        final List<PanelState> panels = new ArrayList<>(s.panels());
        panels.remove(panel.get());
        panels.add(panel.get().withSide(i.side()));
        final AppState next = s.withPanels(panels, i.panel());
        return new Transition(next, List.of(savePanels(next)));
    }

    /** Why {@link #placePanel} would refuse: the last panel of the main tabs sent to the side. */
    static Optional<String> placeRefusal(final AppState s, final Intent.PlacePanel i) {
        return s.panel(i.panel()).filter(p -> i.side() && !p.side() && mainTabs(s) == 1)
                .map(_ -> "The last panel of the main tabs cannot move to the right side");
    }

    /** What an agent's close of the main tabs' last panel is told: the close does nothing (record 141). */
    static Optional<String> closeRefusal(final AppState s, final Intent.ClosePanel i) {
        return s.panel(i.panel()).filter(p -> !p.side() && mainTabs(s) == 1)
                .map(_ -> s.panels().size() == 1 ? "The last panel cannot be closed"
                        : "The last panel of the main tabs cannot be closed");
    }

    /** The preview tab stays as a tab of its own; saved from now on (record 141). */
    static Transition pinPanel(final AppState s, final Intent.PinPanel i) {
        return s.panel(i.panel())
                .filter(PanelState::preview)
                .map(p -> s.withPanel(p.withPreview(false)))
                .map(next -> new Transition(next, List.of(savePanels(next))))
                .orElse(Transition.of(s));
    }

    static Transition renamePanel(final Clock clock, final AppState s, final Intent.RenamePanel i) {
        if (i.title().isBlank()) {
            return Transition.of(s);
        }
        final String title = i.title().strip();
        final Optional<PanelState> renamed = s.panel(i.panel()).or(() -> s.closedPanel(i.panel()));
        // a session's own panel is titled with its name: renaming one renames the other (record 88)
        final Optional<SessionState> own = renamed.filter(p -> !p.document()).flatMap(p -> s.session(p.session()));
        if (own.isPresent()) {
            if (own.get().name().equals(title)) {
                return Transition.of(s);
            }
            SessionSpec spec;
            try {
                spec = own.get().spec().withName(title);
            } catch (final IllegalArgumentException e) {
                return Transition.of(notice(clock, s, Notice.Level.ERROR, e.getMessage(), Notice.panel(i.panel().value())));
            }
            return SessionRules.saveSession(clock, s, new Intent.SaveSession(own.get().name(), spec));
        }
        // a title names a panel to the tools and keys its plots: two panels never share one
        if (Stream.concat(s.panels().stream(), s.closed().stream()).anyMatch(p -> !p.id().equals(i.panel()) && p.title().equalsIgnoreCase(title))) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "A panel called " + title + " exists", Notice.panel(i.panel().value())));
        }
        if (renamed.filter(p -> !p.document()).isPresent() && s.sessions().stream().anyMatch(x -> x.name().equalsIgnoreCase(title))) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "A session called " + title + " exists; its panel is its own",
                    Notice.panel(i.panel().value())));
        }
        if (s.closedPanel(i.panel()).isPresent()) {
            final AppState next = s.withClosed(s.closed().stream().map(p -> p.id().equals(i.panel()) ? p.withTitle(i.title().strip()) : p).toList());
            return new Transition(next, List.of(savePanels(next)));
        }
        return s.panel(i.panel())
                .map(p -> s.withPanel(p.withTitle(i.title().strip())))
                .map(next -> new Transition(next, List.of(savePanels(next))))
                .orElse(Transition.of(s));
    }

    static Transition bindConnection(final Clock clock, final AppState s, final Intent.BindConnection i) {
        if (!i.connection().isEmpty() && s.connection(i.connection()).isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No connection called " + i.connection()));
        }
        return s.panel(i.panel())
                .map(p -> s.withPanel(p.withConnection(i.connection())))
                .map(next -> new Transition(next, List.of(savePanels(next))))
                .orElse(Transition.of(s));
    }

    /**
     * A document is attached to a session, or detached; any number of documents may be attached to one session. A panel
     * of comando's own is never rebound: a session has one, titled with its name (record 88), so binding one to a
     * session opens that session's own panel, and its own panel is never detached.
     */
    static Transition bindSession(final Clock clock, final AppState s, final Intent.BindSession i) {
        if (!i.session().isEmpty() && s.session(i.session()).isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No session called " + i.session()));
        }
        final Optional<PanelState> found = s.panel(i.panel());
        if (found.isEmpty() || found.get().session().equals(i.session())) {
            return Transition.of(s);
        }
        final PanelState panel = found.get();
        if (panel.document()) {
            final AppState next = s.withPanel(panel.withSession(i.session()));
            return new Transition(next, List.of(savePanels(next)));
        }
        if (i.session().isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.INFO, panel.title() + " is its session's own panel; removing the session removes both",
                    Notice.panel(panel.id().value())));
        }
        final Shared.Fresh fresh = freshPanel(s);
        return openSession(clock, fresh.state(), new Intent.OpenSession(i.session(), i.panel(), fresh.id()));
    }

    /**
     * Opens a session's own panel: open, it comes forward; closed, it opens again; none yet, a panel of comando's own
     * with no session and the session's name becomes it, or a new one titled with the name, on {@code target}'s
     * connection. Another panel is never taken (record 88).
     */
    static Transition openSession(final Clock clock, final AppState s, final Intent.OpenSession i) {
        if (s.session(i.session()).isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No session called " + i.session()));
        }
        final Optional<PanelState> own = s.homePanel(i.session());
        if (own.isPresent()) {
            return Transition.of(s.withFocused(own.get().id()));
        }
        final Optional<PanelState> closed = s.closedPanelOfSession(i.session());
        if (closed.isPresent()) {
            return reopen(s, closed.get());
        }
        final Optional<PanelState> named = Stream.concat(s.panels().stream(), s.closed().stream())
                .filter(p -> !p.document() && p.session().isEmpty() && p.title().equalsIgnoreCase(i.session())).findFirst();
        if (named.isPresent()) {
            final PanelState adopted = named.get().withSession(i.session()).withTitle(i.session());
            if (s.panel(adopted.id()).isPresent()) {
                final AppState next = s.withPanel(adopted).withFocused(adopted.id());
                return new Transition(next, List.of(savePanels(next)));
            }
            return reopen(s.withClosed(s.closed().stream().map(p -> p.id().equals(adopted.id()) ? adopted : p).toList()), adopted);
        }
        if (s.panel(i.newPanel()).isPresent() || s.closedPanel(i.newPanel()).isPresent()) {
            return Transition.of(s);
        }
        final PanelState target = s.panel(i.target()).orElse(s.focusedPanel());
        // the name is free unless a document or another panel has it in another case: then the panel keeps a number
        final String title = s.titleTaken(i.session()) ? uniqueTitle(s, i.session()) : i.session();
        final List<PanelState> panels = new ArrayList<>(s.panels());
        panels.add(new PanelState(i.newPanel(), title, target.connection(), i.session()));
        final AppState next = s.withPanels(panels, i.newPanel());
        return new Transition(next, List.of(savePanels(next)));
    }

    private static String defaultTitle(final AppState s) {
        int n = s.panels().size() + 1;
        while (taken(s, "panel " + n)) {
            n++;
        }
        return "panel " + n;
    }

    /** {@code title}, or the first of "title 2", "title 3"… no panel has, open or closed, and no session is called. */
    private static String uniqueTitle(final AppState s, final String title) {
        String candidate = title;
        for (int n = 2; taken(s, candidate); n++) {
            candidate = title + " " + n;
        }
        return candidate;
    }

    /** A panel has the title, or a session the name: a session's name is its own panel's title. */
    private static boolean taken(final AppState s, final String title) {
        return s.titleTaken(title) || s.sessions().stream().anyMatch(x -> x.name().equalsIgnoreCase(title));
    }

    static Transition focusPanel(final AppState s, final Intent.FocusPanel i) {
        return s.panel(i.panel()).isPresent() ? Transition.of(s.withFocused(i.panel())) : Transition.of(s);
    }

    static Transition editPanel(final Clock clock, final AppState s, final Intent.EditPanel i) {
        final PanelId target = i.panel() != null ? i.panel() : s.focused();
        final Optional<PanelState> panel = s.panel(target);
        if (panel.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No panel " + target));
        }
        AppState next = s;
        if (!i.session().isEmpty()) {
            // a session's write into the user's editor is announced, so it never goes unnoticed
            final long lines = i.text().isEmpty() ? 0 : i.text().lines().count();
            final String what = switch (i.mode()) {
                case REPLACE -> "replaced the text of " + panel.get().title() + " (⌘Z undoes)";
                case INSERT -> "inserted " + lines + (lines == 1 ? " line" : " lines") + " in " + panel.get().title();
                case APPEND -> "added " + lines + (lines == 1 ? " line" : " lines") + " to " + panel.get().title();
                case CARET -> "typed " + lines + (lines == 1 ? " line" : " lines") + " at the caret in " + panel.get().title();
            };
            next = notice(clock, s, Notice.Level.INFO, i.session() + " " + what);
        }
        return new Transition(next, List.of(new Effect.EditPanelText(target, i.text(), i.mode())));
    }
}
