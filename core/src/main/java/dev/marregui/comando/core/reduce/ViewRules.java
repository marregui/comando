package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;

import static dev.marregui.comando.core.reduce.Shared.LONG_RUN_MILLIS;
import static dev.marregui.comando.core.reduce.Shared.notice;

/**
 * The window's rules: views, the lower tab, a session's layout requests, console commands and notices.
 */
final class ViewRules {

    private ViewRules() {
    }

    /** Longest view title kept; a session's title past it is clipped, so a tab never fills the window. */
    static final int VIEW_TITLE_CHARS = 80;

    static Transition showView(Clock clock, AppState s, Intent.ShowView shown) {
        Intent.ShowView i = shown.title().length() <= VIEW_TITLE_CHARS ? shown
                : new Intent.ShowView(shown.session(), Clip.keepStart(shown.title(), VIEW_TITLE_CHARS), shown.spec(), shown.placement());
        for (ViewState v : s.views()) {
            if (!v.pinned() && v.title().equals(i.title()) && v.session().equals(i.session()) && v.placement() == i.placement()) {
                // the user's own command (session "") shows its view again; a session's update in place stays put
                AppState next = s.withViews(replaceView(s.views(), v.replaced(i.spec())));
                return Transition.of(i.session().isEmpty() && i.placement() == Placement.LOWER ? next.withLowerTab(LowerTab.view(v.id())) : next);
            }
        }
        long id = s.nextId();
        List<ViewState> views = new ArrayList<>(s.views());
        views.add(new ViewState(id, i.title(), i.spec(), i.placement(), false, i.session(), 1L));
        AppState next = s.withIdTaken().withViews(views);
        // a new view takes the lower panel for the user's own command or the session the user is looking at; a
        // session working in another panel adds its tab without moving the user
        boolean lookingAt = i.session().isEmpty() || i.session().equals(s.focusedPanel().session());
        if (i.placement() == Placement.LOWER && lookingAt) {
            return Transition.of(next.withLowerTab(LowerTab.view(id)));
        }
        return Transition.of(lookingAt ? next : notice(clock, next, Notice.Level.INFO, i.session() + " opened " + i.title()));
    }

    /**
     * What a session may change in the window, so it never moves the user away from another session's panel. Focus
     * is not changed here: the panel to focus rides on the {@link Effect.Arrange} effect, which the shell applies once
     * the user pauses typing, so keystrokes never land in a panel the user did not pick.
     */
    static Transition sessionLayout(Clock clock, AppState s, Intent.SessionLayout i) {
        AppState next = s;
        List<String> done = new ArrayList<>();
        PanelId focus = null;
        if (i.panel() != null) {
            Optional<PanelState> target = s.panel(i.panel());
            if (target.isPresent() && target.get().session().equals(i.session()) && !s.focused().equals(i.panel())) {
                focus = i.panel();
                done.add("will focus " + target.get().title() + " once you pause typing");
            }
        }
        LowerTab laterTab = null;
        if (i.tab() != null && focus != null) {
            laterTab = i.tab(); // selected with the focus, once it is the session's panel the user looks at
        } else if (i.tab() != null && s.focusedPanel().session().equals(i.session()) && !s.lowerTab().equals(i.tab())) {
            next = next.withLowerTab(i.tab());
            done.add("selected a tab");
        }
        boolean panes = i.layout() != null && !i.layout().changesNothing();
        if (panes) {
            done.add("arranged the panes");
        }
        List<Effect> effects = new ArrayList<>();
        if (panes || focus != null) {
            Layout base = panes ? i.layout() : Layout.KEEP;
            effects.add(new Effect.Arrange(new Layout(base.sidebar(), base.sidePane(), base.lowerShare(), focus, laterTab, focus == null ? "" : i.session())));
        }
        if (done.isEmpty()) {
            return Transition.of(s);
        }
        String undo = panes ? " (Restore layout in the palette undoes it)" : "";
        return new Transition(notice(clock, next, Notice.Level.INFO, i.session() + " " + String.join(", ", done) + undo), effects);
    }

    static Transition closeView(AppState s, long id) {
        Optional<ViewState> view = s.view(id);
        if (view.isEmpty()) {
            return Transition.of(s);
        }
        AppState next = s.withViews(s.views().stream().filter(v -> v.id() != id).toList());
        if (s.lowerTab().kind() == LowerTab.Kind.VIEW && s.lowerTab().viewId() == id) {
            next = next.withLowerTab(LowerTab.SESSION); // the first tab
        }
        List<Effect> effects = new ArrayList<>();
        if (view.get().spec() instanceof ViewSpec.Console c) {
            if (c.state() == ViewSpec.RunState.RUNNING) {
                effects.add(new Effect.CancelCommand(c.runId()));
            }
            effects.add(new Effect.ForgetCommand(c.runId()));
        }
        return new Transition(next, effects);
    }

    private static List<ViewState> replaceView(List<ViewState> views, ViewState view) {
        List<ViewState> next = new ArrayList<>(views.size());
        for (ViewState v : views) {
            next.add(v.id() == view.id() ? view : v);
        }
        return next;
    }

    static Transition sessionFocus(AppState s, Intent.SessionFocus i) {
        return Transition.of(s.panel(i.panel())
                .filter(p -> p.session().equals(i.session()))
                .map(p -> i.tab() == null ? s.withFocused(i.panel()) : s.withFocused(i.panel()).withLowerTab(i.tab()))
                .orElse(s)); // rebound or closed while the user typed: the request no longer holds
    }

    static Transition refreshView(AppState s, Intent.RefreshView i) {
        return Transition.of(s.views().stream()
                .filter(v -> !v.pinned() && v.session().isEmpty() && v.title().equals(i.title()) && v.placement() == Placement.LOWER)
                .findFirst().map(v -> s.withViews(replaceView(s.views(), v.replaced(i.spec())))).orElse(s));
    }

    static Transition pinView(AppState s, Intent.PinView i) {
        return Transition.of(s.view(i.id())
                .map(v -> s.withViews(replaceView(s.views(), v.withPinned(i.pinned())))).orElse(s));
    }

    static Transition selectLowerTab(AppState s, Intent.SelectLowerTab i) {
        return Transition.of(s.withLowerTab(i.tab()));
    }

    static Transition arrange(AppState s, Intent.Arrange i) {
        return new Transition(s, i.layout().changesNothing() ? List.of() : List.of(new Effect.Arrange(i.layout())));
    }

    static Transition planUsageRead(AppState s, Intent.PlanUsageRead i) {
        return new Transition(s, List.of(new Effect.ShowPlanUsage(i.usage())));
    }

    // ---- development tools

    static Transition runGradle(Clock clock, AppState s, Intent.RunGradle i) {
        PanelId panel = i.panel() != null ? i.panel() : s.focused();
        String tasks = i.tasks().strip();
        if (tasks.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.WARN, "No Gradle tasks given"));
        }
        String dir = s.panel(panel).flatMap(p -> s.session(p.session())).map(x -> x.spec().workDir())
                .orElse(HomeDir.home());
        return runCommand(clock, s, "gradle " + tasks, panel == null ? "" : panel.value(), id -> new Effect.RunGradle(id, dir, tasks));
    }

    /**
     * Opens a console view for a new run and starts it. Running the same command again replaces the
     * previous run's view, unless it is pinned, and stops that run when it still runs.
     */
    private static Transition runCommand(Clock clock, AppState s, String title, String panel, LongFunction<Effect> start) {
        long runId = s.nextId();
        List<Effect> effects = new ArrayList<>();
        for (ViewState v : s.views()) {
            if (!v.pinned() && v.title().equals(title) && v.placement() == Placement.LOWER && v.spec() instanceof ViewSpec.Console c) {
                if (c.state() == ViewSpec.RunState.RUNNING) {
                    effects.add(new Effect.CancelCommand(c.runId()));
                }
                effects.add(new Effect.ForgetCommand(c.runId()));
            }
        }
        Transition shown = showView(clock, s.withIdTaken(), new Intent.ShowView("", title, ViewSpec.Console.running(runId, title, panel), Placement.LOWER));
        // a re-run replaces its view in place, which keeps the tab; the user asked for this run, so show it
        AppState placed = shown.state();
        AppState withTab = placed.views().stream()
                .filter(v -> v.spec() instanceof ViewSpec.Console c && c.runId() == runId).findFirst()
                .map(v -> placed.withLowerTab(LowerTab.view(v.id()))).orElse(placed);
        shown = new Transition(withTab, shown.effects());
        effects.add(start.apply(runId));
        return new Transition(shown.state(), effects);
    }

    static Transition commandFinished(AppState s, Intent.CommandFinished i) {
        for (ViewState v : s.views()) {
            if (v.spec() instanceof ViewSpec.Console c && c.runId() == i.runId()) {
                ViewSpec.RunState state = i.cancelled() ? ViewSpec.RunState.CANCELLED
                        : i.exitCode() == 0 ? ViewSpec.RunState.PASSED : ViewSpec.RunState.FAILED;
                ViewSpec.Console done = new ViewSpec.Console(c.runId(), c.command(), state, i.exitCode(), i.millis(), i.tests(), i.failures(), c.panel());
                AppState next = s.withViews(replaceView(s.views(), v.replaced(done)));
                List<Effect> effects = i.millis() >= LONG_RUN_MILLIS && !i.cancelled()
                        ? List.of(new Effect.DesktopNotify(c.command(), (state == ViewSpec.RunState.PASSED ? "passed" : "failed")
                        + " in " + i.millis() / 1000 + " s")) : List.of();
                return new Transition(next, effects);
            }
        }
        return Transition.of(s); // the view was closed; nothing shows the result
    }

    static Transition cancelCommand(AppState s, Intent.CancelCommand i) {
        return new Transition(s, List.of(new Effect.CancelCommand(i.runId())));
    }

    // ---- notices

    static Transition showNotice(Clock clock, AppState s, Intent.Notify i) {
        return new Transition(notice(clock, s, i.level(), i.text(), i.subject()),
                i.desktop() ? List.of(new Effect.DesktopNotify("comando", i.text())) : List.of());
    }

    static Transition dismissNotice(AppState s, Intent.DismissNotice i) {
        return Transition.of(s.withNotice(Notice.NONE));
    }
}
