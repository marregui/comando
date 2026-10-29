package dev.marregui.comando.core.reduce;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static dev.marregui.comando.core.reduce.Shared.freshPanel;
import static dev.marregui.comando.core.reduce.Shared.notice;
import static dev.marregui.comando.core.reduce.Shared.savePanels;

/**
 * The panels' rules: adding, closing, removing, reopening, moving, renaming, focusing and binding panels, a session's
 * own panel, and edits to a panel's text.
 */
final class PanelRules {

    private PanelRules() {
    }

    static Transition addPanel(AppState s, Intent.AddPanel i) {
        if (s.panel(i.panel()).isPresent()) {
            return Transition.of(s);
        }
        PanelState current = s.focusedPanel();
        // a title names a panel to the tools and keys its plots: a title already taken gets a number, "README.md 2"; a
        // session's name is its own panel's, taken too
        String title = i.title().isBlank() ? defaultTitle(s) : uniqueTitle(s, i.title().strip());
        List<PanelState> panels = new ArrayList<>(s.panels());
        panels.add(new PanelState(i.panel(), title, current.connection(), "", i.document()));
        AppState next = s.withPanels(panels, i.panel());
        return new Transition(next, List.of(savePanels(next)));
    }

    /** A panel of comando's own closes into the closed list, keeping its file and bindings; a document is dropped. */
    static Transition closePanel(Clock clock, AppState s, PanelId id) {
        return dropPanel(clock, s, id, false);
    }

    /** Removes a panel: an open one closes first; either leaves the lists, and its own file is deleted. */
    static Transition removePanel(Clock clock, AppState s, PanelId id) {
        // a session's own panel and the session are one: removing either removes both (record 88)
        Optional<PanelState> own = s.panel(id).or(() -> s.closedPanel(id)).filter(p -> !p.document() && !p.session().isEmpty());
        if (own.isPresent() && s.session(own.get().session()).isPresent()) {
            return SessionRules.removeSession(clock, s, own.get().session());
        }
        Optional<PanelState> closed = s.closedPanel(id);
        if (closed.isPresent()) {
            AppState next = notice(clock, s.withClosed(s.closed().stream().filter(p -> !p.id().equals(id)).toList()), Notice.Level.INFO,
                    "Removed " + closed.get().title());
            return new Transition(next, List.of(new Effect.DeletePanel(id), savePanels(next)));
        }
        return dropPanel(clock, s, id, true);
    }

    static Transition dropPanel(Clock clock, AppState s, PanelId id, boolean remove) {
        Optional<PanelState> panel = s.panel(id);
        if (panel.isEmpty()) {
            return Transition.of(s);
        }
        if (s.panels().size() == 1) {
            if (!remove) {
                return Transition.of(notice(clock, s, Notice.Level.WARN, "The last panel cannot be closed"));
            }
            // removing the last open panel (by the user, or its file deleted outside) leaves an empty one in its place
            // nextId starts again at every run, while closed panels keep their ids: skip any id a panel holds
            PanelId fresh = new PanelId("p-" + s.nextId());
            s = s.withIdTaken();
            while (s.panel(fresh).isPresent() || s.closedPanel(fresh).isPresent()) {
                fresh = new PanelId("p-" + s.nextId());
                s = s.withIdTaken();
            }
            PanelState empty = new PanelState(fresh, defaultTitle(s), panel.get().connection(), "");
            s = s.withPanels(List.of(panel.get(), empty), s.focused());
        }
        List<Effect> effects = new ArrayList<>();
        AppState next = s;
        Optional<SqlRun> run = s.runOf(id);
        if (run.isPresent()) {
            if (run.get().status().isActive()) {
                effects.add(new Effect.CancelSql(run.get().requestId()));
            }
            effects.add(new Effect.ReleaseResult(run.get().requestId()));
            next = next.withRuns(s.runs().stream().filter(r -> !r.panel().equals(id)).toList());
        }
        int idx = s.panels().indexOf(panel.get());
        List<PanelState> panels = new ArrayList<>(s.panels());
        panels.remove(idx);
        PanelId focused = s.focused().equals(id) ? panels.get(Math.max(0, idx - 1)).id() : s.focused();
        next = next.withPanels(panels, focused);
        if (remove) {
            next = notice(clock, next, Notice.Level.INFO, "Removed " + panel.get().title());
            effects.add(new Effect.DeletePanel(id));
        } else {
            effects.add(new Effect.ForgetPanel(id));
            if (!panel.get().document()) {
                List<PanelState> closed = new ArrayList<>(next.closed());
                closed.add(panel.get());
                next = next.withClosed(closed);
            }
        }
        effects.add(savePanels(next));
        return new Transition(next, effects);
    }

    /** A closed panel of comando's own comes back after the focused one, with its title, bindings and text. */
    static Transition reopenPanel(AppState s, Intent.ReopenPanel i) {
        if (s.panel(i.panel()).isPresent()) {
            return Transition.of(s.withFocused(i.panel()));
        }
        return s.closedPanel(i.panel()).map(p -> reopen(s, p)).orElse(Transition.of(s));
    }

    private static Transition reopen(AppState s, PanelState p) {
        List<PanelState> panels = new ArrayList<>(s.panels());
        int at = s.panels().indexOf(s.focusedPanel()) + 1;
        panels.add(Math.min(at, panels.size()), p);
        AppState next = s.withPanels(panels, p.id()).withClosed(s.closed().stream().filter(c -> !c.id().equals(p.id())).toList());
        return new Transition(next, List.of(savePanels(next)));
    }

    /** An open panel moves among the open ones; saved, so a restart keeps the order. */
    static Transition movePanel(AppState s, Intent.MovePanel i) {
        List<PanelState> panels = new ArrayList<>(s.panels());
        int from = panels.stream().map(PanelState::id).toList().indexOf(i.panel());
        if (from < 0) {
            return Transition.of(s);
        }
        int to = Math.clamp(i.index(), 0, panels.size() - 1);
        if (from == to) {
            return Transition.of(s);
        }
        panels.add(to, panels.remove(from));
        AppState next = s.withPanels(panels, s.focused());
        return new Transition(next, List.of(savePanels(next)));
    }

    static Transition renamePanel(Clock clock, AppState s, Intent.RenamePanel i) {
        if (i.title().isBlank()) {
            return Transition.of(s);
        }
        String title = i.title().strip();
        Optional<PanelState> renamed = s.panel(i.panel()).or(() -> s.closedPanel(i.panel()));
        // a session's own panel is titled with its name: renaming one renames the other (record 88)
        Optional<SessionState> own = renamed.filter(p -> !p.document()).flatMap(p -> s.session(p.session()));
        if (own.isPresent()) {
            if (own.get().name().equals(title)) {
                return Transition.of(s);
            }
            SessionSpec spec;
            try {
                spec = own.get().spec().withName(title);
            } catch (IllegalArgumentException e) {
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
            AppState next = s.withClosed(s.closed().stream().map(p -> p.id().equals(i.panel()) ? p.withTitle(i.title().strip()) : p).toList());
            return new Transition(next, List.of(savePanels(next)));
        }
        return s.panel(i.panel())
                .map(p -> s.withPanel(p.withTitle(i.title().strip())))
                .map(next -> new Transition(next, List.of(savePanels(next))))
                .orElse(Transition.of(s));
    }

    static Transition bindConnection(Clock clock, AppState s, Intent.BindConnection i) {
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
    static Transition bindSession(Clock clock, AppState s, Intent.BindSession i) {
        if (!i.session().isEmpty() && s.session(i.session()).isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No session called " + i.session()));
        }
        Optional<PanelState> found = s.panel(i.panel());
        if (found.isEmpty() || found.get().session().equals(i.session())) {
            return Transition.of(s);
        }
        PanelState panel = found.get();
        if (panel.document()) {
            AppState next = s.withPanel(panel.withSession(i.session()));
            return new Transition(next, List.of(savePanels(next)));
        }
        if (i.session().isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.INFO, panel.title() + " is its session's own panel; removing the session removes both",
                    Notice.panel(panel.id().value())));
        }
        Shared.Fresh fresh = freshPanel(s);
        return openSession(clock, fresh.state(), new Intent.OpenSession(i.session(), i.panel(), fresh.id()));
    }

    /**
     * Opens a session's own panel: open, it comes forward; closed, it opens again; none yet, a panel of comando's own
     * with no session and the session's name becomes it, or a new one titled with the name, on {@code target}'s
     * connection. Another panel is never taken (record 88).
     */
    static Transition openSession(Clock clock, AppState s, Intent.OpenSession i) {
        if (s.session(i.session()).isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No session called " + i.session()));
        }
        Optional<PanelState> own = s.homePanel(i.session());
        if (own.isPresent()) {
            return Transition.of(s.withFocused(own.get().id()));
        }
        Optional<PanelState> closed = s.closedPanelOfSession(i.session());
        if (closed.isPresent()) {
            return reopen(s, closed.get());
        }
        Optional<PanelState> named = Stream.concat(s.panels().stream(), s.closed().stream())
                .filter(p -> !p.document() && p.session().isEmpty() && p.title().equalsIgnoreCase(i.session())).findFirst();
        if (named.isPresent()) {
            PanelState adopted = named.get().withSession(i.session()).withTitle(i.session());
            if (s.panel(adopted.id()).isPresent()) {
                AppState next = s.withPanel(adopted).withFocused(adopted.id());
                return new Transition(next, List.of(savePanels(next)));
            }
            return reopen(s.withClosed(s.closed().stream().map(p -> p.id().equals(adopted.id()) ? adopted : p).toList()), adopted);
        }
        if (s.panel(i.newPanel()).isPresent() || s.closedPanel(i.newPanel()).isPresent()) {
            return Transition.of(s);
        }
        PanelState target = s.panel(i.target()).orElse(s.focusedPanel());
        // the name is free unless a document or another panel has it in another case: then the panel keeps a number
        String title = s.titleTaken(i.session()) ? uniqueTitle(s, i.session()) : i.session();
        List<PanelState> panels = new ArrayList<>(s.panels());
        panels.add(new PanelState(i.newPanel(), title, target.connection(), i.session()));
        AppState next = s.withPanels(panels, i.newPanel());
        return new Transition(next, List.of(savePanels(next)));
    }

    private static String defaultTitle(AppState s) {
        int n = s.panels().size() + 1;
        while (taken(s, "panel " + n)) {
            n++;
        }
        return "panel " + n;
    }

    /** {@code title}, or the first of "title 2", "title 3"… no panel has, open or closed, and no session is called. */
    private static String uniqueTitle(AppState s, String title) {
        String candidate = title;
        for (int n = 2; taken(s, candidate); n++) {
            candidate = title + " " + n;
        }
        return candidate;
    }

    /** A panel has the title, or a session the name: a session's name is its own panel's title. */
    private static boolean taken(AppState s, String title) {
        return s.titleTaken(title) || s.sessions().stream().anyMatch(x -> x.name().equalsIgnoreCase(title));
    }

    static Transition focusPanel(AppState s, Intent.FocusPanel i) {
        return s.panel(i.panel()).isPresent() ? Transition.of(s.withFocused(i.panel())) : Transition.of(s);
    }

    static Transition editPanel(Clock clock, AppState s, Intent.EditPanel i) {
        PanelId target = i.panel() != null ? i.panel() : s.focused();
        Optional<PanelState> panel = s.panel(target);
        if (panel.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No panel " + target));
        }
        AppState next = s;
        if (!i.session().isEmpty()) {
            // a session's write into the user's editor is announced, so it never goes unnoticed
            long lines = i.text().isEmpty() ? 0 : i.text().lines().count();
            String what = switch (i.mode()) {
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
