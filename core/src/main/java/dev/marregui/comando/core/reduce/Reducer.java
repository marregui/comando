package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.dispatch.Classifier;
import dev.marregui.comando.core.dispatch.Statements;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Applies one intent to the state and lists the effects that follow. Pure apart from the clock, which
 * only stamps notices and activity, and the home folder, where a file link or a Gradle run without a session starts: the same state,
 * intent, time and home always give the same transition.
 *
 * <p>Request identity closes the stale-result class of defects: every SQL run gets an id from the state's
 * counter, a panel holds one run, and an event for an id that is no longer a panel's run is dropped.</p>
 */
public final class Reducer {

    /** Queries running this long or longer notify the desktop when they end. */
    static final long LONG_RUN_MILLIS = 10_000L;

    /** The message of a turn the user interrupted, as the stream mapper reports it. */
    static final String INTERRUPTED = "interrupted";

    private final Clock clock;

    public Reducer(Clock clock) {
        this.clock = clock;
    }

    public Transition apply(AppState s, Intent intent) {
        return withDesktopError(s, reduce(s, intent));
    }

    /**
     * A new error notice also goes to the desktop, which shows it only while the window is in the background, so
     * an error that arrives while the user works elsewhere is not lost.
     */
    private static Transition withDesktopError(AppState before, Transition t) {
        Notice n = t.state().notice();
        if (n.level() != Notice.Level.ERROR || n.isEmpty() || n.equals(before.notice())
                || t.effects().stream().anyMatch(e -> e instanceof Effect.DesktopNotify)) {
            return t;
        }
        List<Effect> effects = new ArrayList<>(t.effects());
        effects.add(new Effect.DesktopNotify("comando", n.text()));
        return new Transition(t.state(), effects);
    }

    private Transition reduce(AppState s, Intent intent) {
        return switch (intent) {
            case Intent.AddPanel i -> addPanel(s, i);
            case Intent.ClosePanel i -> closePanel(s, i.panel());
            case Intent.RemovePanel i -> removePanel(s, i.panel());
            case Intent.RenamePanel i -> renamePanel(s, i);
            case Intent.MovePanel i -> movePanel(s, i);
            case Intent.ReopenPanel i -> reopenPanel(s, i);
            case Intent.FocusPanel i -> s.panel(i.panel()).isPresent() ? Transition.of(s.withFocused(i.panel())) : Transition.of(s);
            case Intent.BindConnection i -> bindConnection(s, i);
            case Intent.BindSession i -> bindSession(s, i);
            case Intent.OpenSession i -> openSession(s, i);

            case Intent.RunText i -> runText(s, i);
            case Intent.RefreshSql i -> refreshSql(s, i);
            case Intent.CancelRun i -> cancelRun(s, i.panel());

            case Intent.SqlStarted i -> onRun(s, i.requestId(), r -> r.started(clock.millis()));
            case Intent.SqlRows i -> onRun(s, i.requestId(), r -> r.withProgress(i.rows(), i.execMillis(), i.fetchMillis(), i.totalMillis()));
            case Intent.SqlCompleted i -> notifyLongRun(s, sqlCompleted(s, i), i.requestId(), i.totalMillis(), i.rows() + " rows");
            case Intent.SqlFailed i -> notifyLongRun(s, sqlFailed(s, i), i.requestId(), i.totalMillis(), "failed: " + i.message());
            case Intent.SqlCancelled i -> onRun(s, i.requestId(), r -> r.withStatus(RunStatus.CANCELLED));
            case Intent.SqlWarning i -> sqlWarning(s, i);

            case Intent.SaveConnection i -> saveConnection(s, i);
            case Intent.RemoveConnection i -> removeConnection(s, i.name());
            case Intent.AddFolder i -> addFolder(s, i.spec());
            case Intent.FoldersRead i -> i.folders().equals(s.folders()) ? Transition.of(s) : Transition.of(s.withFolders(i.folders()));
            case Intent.OpenConnection i -> openConnection(s, i.name());
            case Intent.CloseConnection i -> closeConnection(s, i.name());
            case Intent.TestConnection i -> new Transition(notice(s, Notice.Level.INFO, "Testing " + i.spec().label(), Notice.connection(i.spec().name())),
                    List.of(new Effect.TestDb(i.spec())));
            // an open that completes after the user's Close is stale: the queued disconnect closes it; no schema load reopens it
            case Intent.ConnectionOpened i -> s.connection(i.name()).map(c -> c.status() == Status.CLOSING).orElse(false)
                    ? Transition.of(s)
                    : withSchemaLoad(Transition.of(clear(connectionStatus(s, i.name(), Status.OPEN, "").state(), Notice.connection(i.name()))),
                    i.name(), false);
            case Intent.ExploreSchema i -> s.connection(i.connection()).isEmpty()
                    ? Transition.of(notice(s, Notice.Level.WARN, "No connection " + i.connection(), Notice.connection(i.connection())))
                    : withSchemaLoad(Transition.of(s), i.connection(), true);
            case Intent.ConnectionClosed i -> connectionStatus(s, i.name(), Status.CLOSED, "");
            case Intent.ConnectionFailed i -> Transition.of(notice(connectionStatus(s, i.name(), Status.FAILED, i.error()).state(),
                    Notice.Level.ERROR, "Connection " + i.name() + " failed: " + i.error(), Notice.connection(i.name())));
            case Intent.ConnectionLost i -> connectionLost(s, i);
            case Intent.SchemaChanged i -> withSchemaLoad(Transition.of(s), i.connection(), explorerShows(s, i.connection()));
            case Intent.ConnectionTested i -> Transition.of(notice(s, i.ok() ? Notice.Level.INFO : Notice.Level.ERROR,
                    "Test " + i.name() + ": " + i.message(), Notice.connection(i.name())));

            case Intent.SaveSession i -> saveSession(s, i);
            case Intent.RemoveSession i -> removeSession(s, i.name());
            case Intent.ImportSessions i -> importSessions(s, i.specs());
            case Intent.StartSession i -> startSession(s, i.name());
            case Intent.StopSession i -> stopSession(s, i.name());
            case Intent.StartFresh i -> startFresh(s, i.name());
            case Intent.Checkpoint i -> s.session(i.name()).map(x -> sessionCommand(s, i.name(), x.spec().checkpoint())).orElse(Transition.of(s));
            case Intent.SessionCommand i -> sessionCommand(s, i.name(), i.command());
            case Intent.OrderSessions i -> orderSessions(s, i.names());
            case Intent.InterruptSession i -> interruptSession(s, i.name());
            case Intent.ClearTranscript i -> clearTranscript(s, i.name());
            case Intent.SetSessionModel i -> sessionSetting(s, i.name(), i.model(), true);
            case Intent.SetSessionEffort i -> sessionSetting(s, i.name(), i.effort(), false);
            case Intent.SessionStarted i -> sessionStarted(s, i.name());
            case Intent.SessionInit i -> sessionInit(s, i);
            case Intent.SessionActivity i -> Transition.of(s.withSession(i.name(), x -> x.withActivity(i.activity(), clock.millis())));
            case Intent.SessionTasksChanged i -> Transition.of(s.withSession(i.name(), x -> x.withTasks(i.open(), clock.millis())));
            case Intent.SessionTurnRunning i -> Transition.of(s.withSession(i.name(),
                    x -> x.isBusy() ? x : x.withTurns(1, clock.millis())));
            case Intent.SessionTurnEnded i -> turnEnded(s, i);
            case Intent.SessionExited i -> sessionExited(s, i);
            case Intent.SessionBranch i -> Transition.of(s.withSession(i.name(), x -> x.withBranch(i.branch())));

            case Intent.ApprovalRequested i -> approvalRequested(s, i.approval());
            case Intent.ApprovalAnswered i -> approvalAnswered(s, i);
            case Intent.ApprovalWithdrawn i -> approvalWithdrawn(s, i);

            case Intent.ShowView i -> showView(s, i);
            case Intent.SessionLayout i -> sessionLayout(s, i);
            case Intent.SessionFocus i -> Transition.of(s.panel(i.panel())
                    .filter(p -> p.session().equals(i.session()))
                    .map(p -> i.tab() == null ? s.withFocused(i.panel()) : s.withFocused(i.panel()).withLowerTab(i.tab()))
                    .orElse(s)); // rebound or closed while the user typed: the request no longer holds
            case Intent.RefreshView i -> Transition.of(s.views().stream()
                    .filter(v -> !v.pinned() && v.session().isEmpty() && v.title().equals(i.title()) && v.placement() == Placement.LOWER)
                    .findFirst().map(v -> s.withViews(replaceView(s.views(), v.replaced(i.spec())))).orElse(s));
            case Intent.CloseView i -> closeView(s, i.id());
            case Intent.PinView i -> Transition.of(s.view(i.id())
                    .map(v -> s.withViews(replaceView(s.views(), v.withPinned(i.pinned())))).orElse(s));
            case Intent.SelectLowerTab i -> Transition.of(s.withLowerTab(i.tab()));

            case Intent.EditPanel i -> editPanel(s, i);
            case Intent.Arrange i -> new Transition(s, i.layout().changesNothing() ? List.of() : List.of(new Effect.Arrange(i.layout())));
            case Intent.RunGradle i -> runGradle(s, i);
            case Intent.CommandFinished i -> commandFinished(s, i);
            case Intent.CancelCommand i -> new Transition(s, List.of(new Effect.CancelCommand(i.runId())));
            case Intent.BrowseFolders i -> new Transition(s, List.of(new Effect.ReadFolders(folders(s, i.names()))));
            case Intent.WatchFolders i -> new Transition(s, List.of(new Effect.WatchFolders(folders(s, i.names()))));
            case Intent.DeleteFiles i -> deleteFiles(s, i);
            // a tree read with settings since edited, or of a folder since removed, is dropped: the tab shows only current folders
            case Intent.FolderLoaded i -> s.folders().contains(i.folder())
                    ? new Transition(s, List.of(new Effect.ShowFolder(i.folder(), i.tree()))) : Transition.of(s);
            case Intent.PlanUsageRead i -> new Transition(s, List.of(new Effect.ShowPlanUsage(i.usage())));
            case Intent.OpenFile i -> new Transition(s, List.of(new Effect.ShowFile(i.session(), i.path(),
                    s.session(i.session()).map(x -> x.spec().workDir()).orElse(HomeDir.home()), i.line())));
            case Intent.Notify i -> new Transition(notice(s, i.level(), i.text(), i.subject()),
                    i.desktop() ? List.of(new Effect.DesktopNotify("comando", i.text())) : List.of());
            case Intent.DismissNotice i -> Transition.of(s.withNotice(Notice.NONE));
        };
    }

    /**
     * The process is up. A start reported after the user asked to stop it (the session closing) leaves it closing, and
     * the stop's exit follows; one reported for a session already open changes nothing. A closed session takes it: the
     * adapter reports a start before the exit of the same process, so a closed session's start is a new one (and the
     * window replays drive sessions this way).
     */
    private Transition sessionStarted(AppState s, String name) {
        return s.session(name)
                .filter(x -> x.status() == Status.OPENING || x.status() == Status.CLOSED)
                .map(x -> Transition.of(clear(s.withSession(x.withStatus(Status.OPEN, "").withoutTasks().withStarted(clock.millis())),
                        Notice.session(name))))
                .orElse(Transition.of(s));
    }

    // ---- panels

    private Transition addPanel(AppState s, Intent.AddPanel i) {
        if (s.panel(i.panel()).isPresent()) {
            return Transition.of(s);
        }
        PanelState current = s.focusedPanel();
        // a title names a panel to the tools and keys its plots: a second file of the same name gets "README 2"
        String title = i.title().isBlank() ? defaultTitle(s) : uniqueTitle(s, i.title().strip());
        List<PanelState> panels = new ArrayList<>(s.panels());
        panels.add(new PanelState(i.panel(), title, current.connection(), "", i.document()));
        AppState next = s.withPanels(panels, i.panel());
        return new Transition(next, List.of(save(next)));
    }

    /** A panel of comando's own closes into the closed list, keeping its file and bindings; a document is dropped. */
    private Transition closePanel(AppState s, PanelId id) {
        return dropPanel(s, id, false);
    }

    /** Removes a panel: an open one closes first; either leaves the lists, and its own file is deleted. */
    private Transition removePanel(AppState s, PanelId id) {
        Optional<PanelState> closed = s.closedPanel(id);
        if (closed.isPresent()) {
            AppState next = notice(s.withClosed(s.closed().stream().filter(p -> !p.id().equals(id)).toList()), Notice.Level.INFO,
                    "Removed " + closed.get().title());
            return new Transition(next, List.of(new Effect.DeletePanel(id), save(next)));
        }
        return dropPanel(s, id, true);
    }

    private Transition dropPanel(AppState s, PanelId id, boolean remove) {
        Optional<PanelState> panel = s.panel(id);
        if (panel.isEmpty()) {
            return Transition.of(s);
        }
        if (s.panels().size() == 1) {
            if (!remove) {
                return Transition.of(notice(s, Notice.Level.WARN, "The last panel cannot be closed"));
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
            next = notice(next, Notice.Level.INFO, "Removed " + panel.get().title());
            effects.add(new Effect.DeletePanel(id));
        } else {
            effects.add(new Effect.ForgetPanel(id));
            if (!panel.get().document()) {
                List<PanelState> closed = new ArrayList<>(next.closed());
                closed.add(panel.get());
                next = next.withClosed(closed);
            }
        }
        effects.add(save(next));
        return new Transition(next, effects);
    }

    private static Effect save(AppState s) {
        return new Effect.SavePanels(s.panels(), s.closed());
    }

    /** A closed panel of comando's own comes back after the focused one, with its title, bindings and text. */
    private Transition reopenPanel(AppState s, Intent.ReopenPanel i) {
        if (s.panel(i.panel()).isPresent()) {
            return Transition.of(s.withFocused(i.panel()));
        }
        return s.closedPanel(i.panel()).map(p -> reopen(s, p)).orElse(Transition.of(s));
    }

    private Transition reopen(AppState s, PanelState p) {
        List<PanelState> panels = new ArrayList<>(s.panels());
        int at = s.panels().indexOf(s.focusedPanel()) + 1;
        panels.add(Math.min(at, panels.size()), p);
        AppState next = s.withPanels(panels, p.id()).withClosed(s.closed().stream().filter(c -> !c.id().equals(p.id())).toList());
        return new Transition(next, List.of(save(next)));
    }

    /** An open panel moves among the open ones; saved, so a restart keeps the order. */
    private Transition movePanel(AppState s, Intent.MovePanel i) {
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
        return new Transition(next, List.of(save(next)));
    }

    private Transition renamePanel(AppState s, Intent.RenamePanel i) {
        if (i.title().isBlank()) {
            return Transition.of(s);
        }
        String title = i.title().strip();
        // a title names a panel to the tools and keys its plots: two panels never share one
        if (Stream.concat(s.panels().stream(), s.closed().stream()).anyMatch(p -> !p.id().equals(i.panel()) && p.title().equalsIgnoreCase(title))) {
            return Transition.of(notice(s, Notice.Level.ERROR, "A panel called " + title + " exists", Notice.panel(i.panel().value())));
        }
        if (s.closedPanel(i.panel()).isPresent()) {
            AppState next = s.withClosed(s.closed().stream().map(p -> p.id().equals(i.panel()) ? p.withTitle(i.title().strip()) : p).toList());
            return new Transition(next, List.of(save(next)));
        }
        return s.panel(i.panel())
                .map(p -> s.withPanel(p.withTitle(i.title().strip())))
                .map(next -> new Transition(next, List.of(save(next))))
                .orElse(Transition.of(s));
    }

    private Transition bindConnection(AppState s, Intent.BindConnection i) {
        if (!i.connection().isEmpty() && s.connection(i.connection()).isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "No connection called " + i.connection()));
        }
        return s.panel(i.panel())
                .map(p -> s.withPanel(p.withConnection(i.connection())))
                .map(next -> new Transition(next, List.of(save(next))))
                .orElse(Transition.of(s));
    }

    private Transition bindSession(AppState s, Intent.BindSession i) {
        if (!i.session().isEmpty() && s.session(i.session()).isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "No session called " + i.session()));
        }
        if (s.panel(i.panel()).isEmpty()) {
            return Transition.of(s);
        }
        List<PanelState> panels = new ArrayList<>(s.panels().size());
        for (PanelState p : s.panels()) {
            if (p.id().equals(i.panel())) {
                panels.add(p.withSession(i.session()));
            } else {
                // a session has one panel: binding it here takes it from any other
                panels.add(!i.session().isEmpty() && p.session().equals(i.session()) ? p.withSession("") : p);
            }
        }
        List<PanelState> closed = s.closed().stream()
                .map(p -> !i.session().isEmpty() && p.session().equals(i.session()) ? p.withSession("") : p).toList();
        AppState next = s.withPanels(panels, s.focused()).withClosed(closed);
        return new Transition(next, List.of(save(next)));
    }

    private Transition openSession(AppState s, Intent.OpenSession i) {
        if (s.session(i.session()).isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "No session called " + i.session()));
        }
        List<PanelState> bound = s.panelsBoundToSession(i.session());
        if (!bound.isEmpty()) {
            return Transition.of(s.withFocused(bound.getFirst().id()));
        }
        Optional<PanelState> closed = s.closedPanelOfSession(i.session());
        if (closed.isPresent()) {
            return reopen(s, closed.get());
        }
        PanelState target = s.panel(i.target()).orElse(s.focusedPanel());
        if (target.session().isEmpty()) {
            AppState next = s.withPanel(target.withSession(i.session())).withFocused(target.id());
            return new Transition(next, List.of(save(next)));
        }
        if (s.panel(i.newPanel()).isPresent()) {
            return Transition.of(s);
        }
        String title = uniqueTitle(s, i.session());
        List<PanelState> panels = new ArrayList<>(s.panels());
        panels.add(new PanelState(i.newPanel(), title, target.connection(), i.session()));
        AppState next = notice(s.withPanels(panels, i.newPanel()), Notice.Level.INFO,
                "Opened " + i.session() + " in a new panel; " + target.title() + " keeps " + target.session());
        return new Transition(next, List.of(save(next)));
    }

    /** "panel N", N the open panels plus one, else the next number no panel, open or closed, has as its title. */
    private static String defaultTitle(AppState s) {
        int n = s.panels().size() + 1;
        while (titleTaken(s, "panel " + n)) {
            n++;
        }
        return "panel " + n;
    }

    /** {@code title}, or the first of "title 2", "title 3"… no panel has, open or closed. */
    private static String uniqueTitle(AppState s, String title) {
        String candidate = title;
        for (int n = 2; titleTaken(s, candidate); n++) {
            candidate = title + " " + n;
        }
        return candidate;
    }

    private static boolean titleTaken(AppState s, String title) {
        return Stream.concat(s.panels().stream(), s.closed().stream()).anyMatch(p -> p.title().equalsIgnoreCase(title));
    }

    // ---- running text

    private Transition runText(AppState s, Intent.RunText i) {
        // a panel closed since the text was sent (a console's origin) falls back to the focused one
        Optional<PanelState> found = s.panel(i.panel() != null && s.panel(i.panel()).isPresent() ? i.panel() : s.focused());
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        PanelState panel = found.get();
        Classifier.Classification c = switch (i.target()) {
            case AUTO -> Classifier.classify(i.text());
            case SQL -> new Classifier.Classification(Target.SQL, Classifier.stripMarker(i.text()));
            case CLAUDE -> new Classifier.Classification(Target.CLAUDE, Classifier.stripMarker(i.text()));
        };
        // forced to SQL (⌥⏎), a block of comments would reach the database and report "0 rows changed"
        if (c.text().isBlank() || c.target() == Target.SQL && Classifier.isCommentOnly(c.text())) {
            return Transition.of(notice(s, Notice.Level.WARN, "Nothing to run: the block holds only comments",
                    Notice.panel(panel.id().value())));
        }
        return c.target() == Target.SQL ? runSql(s, panel, c.text()) : runPrompt(s, panel, c.text());
    }

    /**
     * A live plot's re-run: only while the panel's last run is that same statement and no longer active, so a
     * refresh decided on an older state never cancels or replaces a statement the user ran since.
     */
    private Transition refreshSql(AppState s, Intent.RefreshSql i) {
        Optional<PanelState> panel = s.panel(i.panel());
        Optional<SqlRun> last = panel.flatMap(p -> s.runOf(p.id()));
        if (panel.isEmpty() || last.isPresent() && (!last.get().sql().equals(i.sql()) || last.get().status().isActive())) {
            return Transition.of(s);
        }
        return runSql(s, panel.get(), i.sql(), false);
    }

    private Transition runSql(AppState s, PanelState panel, String sql) {
        return runSql(s, panel, sql, true);
    }

    private Transition runSql(AppState s, PanelState panel, String sql, boolean showResults) {
        String subject = Notice.panel(panel.id().value());
        if (panel.connection().isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "Panel " + panel.title()
                    + " has no connection: pick one from its header, or add one with Connections… (⌘⇧O)", subject));
        }
        Optional<ConnectionState> conn = s.connection(panel.connection());
        if (conn.isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "No connection called " + panel.connection(), subject));
        }
        List<Effect> effects = new ArrayList<>();
        // a live plot's refresh leaves the status bar alone; the user's own run clears its panel's notice
        AppState next = showResults ? clear(s, subject) : s;
        Optional<SqlRun> previous = s.runOf(panel.id());
        if (previous.isPresent()) {
            if (previous.get().status().isActive()) {
                effects.add(new Effect.CancelSql(previous.get().requestId()));
                if (showResults) {
                    next = notice(next, Notice.Level.WARN, "Stopped the statement still running in " + panel.title(), subject);
                }
            }
            effects.add(new Effect.ReleaseResult(previous.get().requestId()));
        }
        ConnectionState c = conn.get();
        if (!c.status().isUsable()) {
            effects.add(new Effect.OpenDb(c.spec()));
            next = next.withConnection(c.with(Status.OPENING, ""));
        }
        long id = next.nextId();
        next = next.withIdTaken().withRun(SqlRun.queued(id, panel.id(), c.name(), sql, !showResults));
        if (showResults) {
            next = next.withLowerTab(LowerTab.RESULTS);
        }
        effects.add(new Effect.ExecuteSql(id, c.spec(), sql));
        return new Transition(next, effects);
    }

    private Transition runPrompt(AppState s, PanelState panel, String prompt) {
        String subject = Notice.panel(panel.id().value());
        if (panel.session().isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "Panel " + panel.title()
                    + " has no session: pick one from its header, or add one with Sessions… (⌘⇧K)", subject));
        }
        Optional<SessionState> found = s.session(panel.session());
        if (found.isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "No session called " + panel.session(), subject));
        }
        SessionState session = found.get();
        if (session.status() == Status.CLOSING) {
            // the old process still reads stdin until it exits; a start now would reuse it and lose the prompt
            return Transition.of(notice(s, Notice.Level.WARN, "Session " + session.name() + " is stopping; send the prompt again once it has stopped",
                    subject));
        }
        List<Effect> effects = new ArrayList<>();
        if (!session.status().isUsable()) {
            effects.add(new Effect.StartClaude(session.spec()));
            session = session.withStatus(Status.OPENING, "");
        }
        effects.add(new Effect.SendToClaude(session.name(), prompt, panel.title()));
        long now = clock.millis();
        session = session.withTurns(session.pendingTurns() + 1, now).withActivity("sending", now);
        if (isCheckpoint(prompt, session.spec().checkpoint())) {
            // typed, sent by the button or by another session alike: the session is no longer due
            session = session.withReflection(Reflection.checkpointed(session.contextTokens(), session.contextWindow()));
        }
        AppState next = clear(s.withSession(session), subject);
        // the Session tab follows a prompt run from the panel the user looks at; one another session sends to a panel
        // in the background leaves the user's lower panel alone
        if (panel.id().equals(s.focused())) {
            next = next.withLowerTab(LowerTab.SESSION);
        }
        return new Transition(next, effects);
    }

    /** The prompt is the checkpoint command, alone or followed by arguments ({@code /reflect now}). */
    static boolean isCheckpoint(String prompt, String checkpoint) {
        String p = prompt.strip();
        String c = checkpoint.strip();
        return !c.isEmpty() && p.startsWith(c) && (p.length() == c.length() || Character.isWhitespace(p.charAt(c.length())));
    }

    private Transition cancelRun(AppState s, PanelId id) {
        Optional<SqlRun> run = s.runOf(id);
        if (run.isPresent() && run.get().status().isActive()) {
            AppState next = s.withRun(run.get().withStatus(RunStatus.CANCELLED));
            return new Transition(next, List.of(new Effect.CancelSql(run.get().requestId())));
        }
        return s.panel(id)
                .flatMap(p -> s.session(p.session()))
                .filter(SessionState::isBusy)
                .map(sess -> interruptSession(s, sess.name()))
                .orElse(Transition.of(s));
    }

    // ---- SQL events

    private Transition onRun(AppState s, long requestId, UnaryOperator<SqlRun> change) {
        Optional<SqlRun> run = s.run(requestId);
        if (run.isEmpty() || !run.get().status().isActive()) {
            return Transition.of(s); // superseded, cancelled or already finished: drop
        }
        return Transition.of(s.withRun(change.apply(run.get())));
    }

    /** Adds a schema read after a connection opened (for completion) or on request (to show it). */
    private static Transition withSchemaLoad(Transition t, String connection, boolean show) {
        return t.state().connection(connection).map(c -> {
            List<Effect> effects = new ArrayList<>(t.effects());
            effects.add(new Effect.LoadSchema(c.spec(), show));
            return new Transition(t.state(), effects);
        }).orElse(t);
    }

    /** A query that ran {@link #LONG_RUN_MILLIS} or more ends with a desktop notification naming its panel. */
    private static Transition notifyLongRun(AppState before, Transition t, long requestId, long totalMillis, String outcome) {
        // a live plot's refresh is a run nobody typed: no desktop notification for it
        if (totalMillis < LONG_RUN_MILLIS || t.state() == before || before.run(requestId).map(SqlRun::background).orElse(false)) {
            return t;
        }
        String panel = before.run(requestId).flatMap(r -> before.panel(r.panel())).map(PanelState::title).orElse("query");
        List<Effect> effects = new ArrayList<>(t.effects());
        effects.add(new Effect.DesktopNotify(panel + ": query finished", outcome + " in " + totalMillis / 1000 + " s"));
        return new Transition(t.state(), effects);
    }

    /**
     * A failed run keeps the database's error. In a block of several statements the driver stops at the failing one
     * and the ones before it may have committed, so the error says so, and a block holding a schema change reloads
     * the schema. A live plot's failed refresh is a warning, so a broken plot never raises a desktop notification
     * each period.
     */
    private Transition sqlFailed(AppState s, Intent.SqlFailed i) {
        Optional<SqlRun> run = s.run(i.requestId());
        if (run.isEmpty() || !run.get().status().isActive()) {
            return Transition.of(s);
        }
        SqlRun r = run.get();
        List<String> statements = Statements.split(r.sql());
        String message = statements.size() > 1 ? i.message() + "\n" + blockNote(statements.size()) : i.message();
        String subject = Notice.panel(r.panel().value());
        AppState next = s.withRun(r.failed(message, i.totalMillis()));
        if (r.background()) {
            String title = s.panel(r.panel()).map(PanelState::title).orElse("a closed panel");
            next = notice(next, Notice.Level.WARN, "Live plot in " + title + " failed: " + i.message(), subject);
        } else {
            next = notice(next, Notice.Level.ERROR, statements.size() > 1 ? i.message() + " — " + blockNote(statements.size()) : i.message(),
                    subject);
        }
        Transition t = Transition.of(next);
        return statements.size() > 1 && statements.stream().anyMatch(Classifier::isDdl)
                ? withSchemaLoad(t, r.connection(), explorerShows(next, r.connection())) : t;
    }

    static String blockNote(int statements) {
        return "The block holds " + statements + " statements; the ones before the failing statement may have run.";
    }

    /** True when the explorer shows the connection's schema, so a reload after a schema change shows it again. */
    private static boolean explorerShows(AppState s, String connection) {
        String title = Effect.LoadSchema.viewTitle(connection);
        return s.views().stream().anyMatch(v -> !v.pinned() && v.session().isEmpty() && v.placement() == Placement.SIDE && v.title().equals(title));
    }

    /**
     * A finished statement clears its panel's earlier error; a block holding a statement that changes the schema
     * reloads the schema behind completion, and the explorer when it shows that connection. A live plot's refresh
     * finishes without a word.
     */
    private Transition sqlCompleted(AppState s, Intent.SqlCompleted i) {
        Optional<SqlRun> run = s.run(i.requestId());
        Transition t = onRun(s, i.requestId(),
                r -> r.completed(i.rows(), i.updateCount(), i.truncated(), i.execMillis(), i.fetchMillis(), i.totalMillis()));
        if (run.isEmpty() || t.state() == s) {
            return t;
        }
        String subject = Notice.panel(run.get().panel().value());
        AppState next = clear(t.state(), subject);
        String title = s.panel(run.get().panel()).map(PanelState::title).orElse("a closed panel");
        if (i.truncated()) {
            next = notice(next, Notice.Level.WARN, "Showing the first " + String.format(Locale.ROOT, "%,d", i.rows()) + " rows in " + title
                    + "; the query returned more (add a LIMIT, or export from the grid for what is shown)", subject);
        } else if (!run.get().background() && !run.get().panel().equals(s.focused())) {
            next = notice(next, Notice.Level.INFO, "Query finished in " + title, subject);
        }
        if (Classifier.anyDdl(run.get().sql())) {
            return withSchemaLoad(new Transition(next, t.effects()), run.get().connection(), explorerShows(next, run.get().connection()));
        }
        return new Transition(next, t.effects());
    }

    /** Statements after the first one: the run keeps the note for its status line; a failure is also a notice. */
    private Transition sqlWarning(AppState s, Intent.SqlWarning i) {
        Optional<SqlRun> run = s.run(i.requestId());
        if (run.isEmpty()) {
            return Transition.of(s);
        }
        AppState next = s.withRun(run.get().withNote(i.text(), i.failed()));
        return Transition.of(notice(next, i.failed() ? Notice.Level.ERROR : Notice.Level.WARN, i.text(), Notice.panel(run.get().panel().value())));
    }

    // ---- connections

    private Transition saveConnection(AppState s, Intent.SaveConnection i) {
        ConnectionSpec spec = i.spec();
        boolean isNew = i.previousName().isEmpty();
        if (isNew) {
            if (s.connection(spec.name()).isPresent()) {
                return Transition.of(notice(s, Notice.Level.ERROR, "A connection called " + spec.name() + " exists"));
            }
            List<ConnectionState> conns = new ArrayList<>(s.connections());
            conns.add(ConnectionState.closed(spec));
            AppState next = s.withConnections(conns);
            return new Transition(next, List.of(new Effect.SaveConnections(specs(next))));
        }
        Optional<ConnectionState> previous = s.connection(i.previousName());
        if (previous.isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "No connection called " + i.previousName()));
        }
        boolean renamed = !i.previousName().equals(spec.name());
        if (renamed && s.connection(spec.name()).isPresent()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "A connection called " + spec.name() + " exists"));
        }
        List<Effect> effects = new ArrayList<>();
        ConnectionState old = previous.get();
        ConnectionState updated = new ConnectionState(spec, old.status(), old.error());
        if (old.status().isUsable() && !old.spec().equals(spec)) {
            effects.add(new Effect.CloseDb(old.name()));
            updated = ConnectionState.closed(spec);
        }
        if (!old.spec().equals(spec)) {
            effects.add(new Effect.ForgetSchema(old.name()));
        }
        List<ConnectionState> conns = new ArrayList<>(s.connections().size());
        for (ConnectionState c : s.connections()) {
            conns.add(c.name().equals(i.previousName()) ? updated : c);
        }
        AppState next = s.withConnections(conns);
        effects.add(new Effect.SaveConnections(specs(next)));
        if (renamed) {
            next = rebindConnection(next, i.previousName(), spec.name());
            effects.add(save(next));
        }
        return new Transition(next, effects);
    }

    private Transition removeConnection(AppState s, String name) {
        Optional<ConnectionState> conn = s.connection(name);
        if (conn.isEmpty()) {
            return Transition.of(s);
        }
        List<Effect> effects = new ArrayList<>();
        if (conn.get().status() != Status.CLOSED) {
            effects.add(new Effect.CloseDb(name));
        }
        effects.add(new Effect.ForgetSchema(name));
        AppState next = clear(s, Notice.connection(name)).withConnections(s.connections().stream().filter(c -> !c.name().equals(name)).toList());
        next = rebindConnection(next, name, "");
        effects.add(new Effect.SaveConnections(specs(next)));
        effects.add(save(next));
        return new Transition(next, effects);
    }

    // ---- the Files tab's folders

    /** The configured folders named, in the configuration's order; a name no folder has is skipped. */
    private static List<FolderRoot> folders(AppState s, List<String> names) {
        return s.folders().stream().filter(f -> names.contains(f.name())).toList();
    }

    /** Entries of a deletable folder to the Trash: refused for any other folder, for the folder itself and for a path outside it. */
    private Transition deleteFiles(AppState s, Intent.DeleteFiles i) {
        Optional<FolderRoot> folder = s.folder(i.folder());
        if (folder.isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "No folder called " + i.folder(), Notice.folder(i.folder())));
        }
        FolderRoot f = folder.get();
        if (!f.deletable()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "Folder " + f.name() + " keeps its files: \"deletable\" in folders.json lets them go to the Trash",
                    Notice.folder(f.name())));
        }
        if (i.paths().isEmpty()) {
            return Transition.of(s);
        }
        Path root = Path.of(f.path()).normalize();
        for (String p : i.paths()) {
            Path path = Path.of(p).normalize();
            if (!path.isAbsolute() || !f.contains(path) || path.equals(root)) {
                return Transition.of(notice(s, Notice.Level.ERROR, (path.equals(root) ? "Folder " + f.name() + " itself stays"
                        : path.getFileName() + " is not in folder " + f.name()) + "; nothing was moved to the Trash", Notice.folder(f.name())));
            }
        }
        return new Transition(s, List.of(new Effect.DeleteFiles(f, i.paths())));
    }

    /** A folder after the others, saved to {@code folders.json}. */
    private Transition addFolder(AppState s, FolderRoot spec) {
        if (s.folder(spec.name()).isPresent()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "A folder called " + spec.name() + " exists", Notice.folder(spec.name())));
        }
        List<FolderRoot> folders = new ArrayList<>(s.folders());
        folders.add(spec);
        AppState next = s.withFolders(folders);
        return new Transition(next, List.of(new Effect.SaveFolders(next.folders())));
    }

    private Transition openConnection(AppState s, String name) {
        return s.connection(name)
                .filter(c -> !c.status().isUsable())
                .map(c -> new Transition(s.withConnection(c.with(Status.OPENING, "")), List.of(new Effect.OpenDb(c.spec()))))
                .orElse(Transition.of(s));
    }

    private Transition closeConnection(AppState s, String name) {
        Optional<ConnectionState> conn = s.connection(name);
        if (conn.isEmpty() || conn.get().status() == Status.CLOSED) {
            return Transition.of(s);
        }
        List<Effect> effects = new ArrayList<>();
        AppState next = s;
        for (SqlRun r : s.runs()) {
            if (r.connection().equals(name) && r.status().isActive()) {
                effects.add(new Effect.CancelSql(r.requestId()));
                next = next.withRun(r.withStatus(RunStatus.CANCELLED));
            }
        }
        effects.add(new Effect.CloseDb(name));
        next = next.withConnection(conn.get().with(Status.CLOSING, ""));
        return new Transition(next, effects);
    }

    private Transition connectionStatus(AppState s, String name, Status status, String error) {
        return Transition.of(s.connection(name).map(c -> s.withConnection(c.with(status, error))).orElse(s));
    }

    private Transition connectionLost(AppState s, Intent.ConnectionLost i) {
        if (s.connection(i.name()).isEmpty()) {
            return Transition.of(s);
        }
        AppState next = notice(connectionStatus(s, i.name(), Status.FAILED, i.error()).state(), Notice.Level.WARN,
                "Connection " + i.name() + " lost: " + i.error(), Notice.connection(i.name()));
        return new Transition(next, List.of(new Effect.DesktopNotify("Connection lost", i.name() + ": " + i.error())));
    }

    private static AppState rebindConnection(AppState s, String from, String to) {
        List<PanelState> panels = new ArrayList<>(s.panels().size());
        for (PanelState p : s.panels()) {
            panels.add(p.connection().equals(from) ? p.withConnection(to) : p);
        }
        // closed panels keep their bindings, so they follow renames and removals too
        List<PanelState> closed = s.closed().stream().map(p -> p.connection().equals(from) ? p.withConnection(to) : p).toList();
        return s.withPanels(panels, s.focused()).withClosed(closed);
    }

    private static List<ConnectionSpec> specs(AppState s) {
        return s.connections().stream().map(ConnectionState::spec).toList();
    }

    // ---- sessions

    private Transition saveSession(AppState s, Intent.SaveSession i) {
        SessionSpec spec = i.spec();
        if (i.previousName().isEmpty()) {
            if (s.session(spec.name()).isPresent()) {
                return Transition.of(notice(s, Notice.Level.ERROR, "A session called " + spec.name() + " exists", Notice.session(spec.name())));
            }
            List<SessionState> sessions = new ArrayList<>(s.sessions());
            sessions.add(SessionState.closed(spec));
            AppState next = s.withSessions(sessions);
            List<Effect> effects = new ArrayList<>();
            effects.add(new Effect.SaveSessions(sessionSpecs(next)));
            if (!spec.claudeSessionId().isEmpty()) {
                effects.add(new Effect.LoadHistory(spec));
            }
            return new Transition(next, effects);
        }
        Optional<SessionState> previous = s.session(i.previousName());
        if (previous.isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "No session called " + i.previousName()));
        }
        boolean renamed = !i.previousName().equals(spec.name());
        SessionState old = previous.get();
        if (renamed && old.status() != Status.CLOSED && old.status() != Status.FAILED) {
            return Transition.of(notice(s, Notice.Level.ERROR, "Stop session " + old.name() + " before renaming it", Notice.session(old.name())));
        }
        if (renamed && s.session(spec.name()).isPresent()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "A session called " + spec.name() + " exists"));
        }
        List<SessionState> sessions = new ArrayList<>(s.sessions().size());
        for (SessionState x : s.sessions()) {
            sessions.add(x.name().equals(i.previousName()) ? x.withSpec(spec) : x);
        }
        AppState next = s.withSessions(sessions);
        List<Effect> effects = new ArrayList<>();
        effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        if (renamed) {
            next = rebindSession(next, i.previousName(), spec.name());
            effects.add(new Effect.ForgetSession(i.previousName()));
            effects.add(save(next));
            if (!spec.claudeSessionId().isEmpty()) {
                effects.add(new Effect.LoadHistory(spec));
            }
        }
        if (old.status().isUsable() && !old.spec().equals(spec)) {
            next = notice(next, Notice.Level.INFO, "Session " + spec.name() + ": changes apply at the next start", Notice.session(spec.name()));
        }
        return new Transition(next, effects);
    }

    private Transition removeSession(AppState s, String name) {
        Optional<SessionState> session = s.session(name);
        if (session.isEmpty()) {
            return Transition.of(s);
        }
        List<Effect> effects = new ArrayList<>();
        if (session.get().status() != Status.CLOSED) {
            effects.add(new Effect.StopClaude(name));
        }
        effects.add(new Effect.ForgetSession(name));
        AppState next = clear(withdrawApprovals(s, name, "session removed", effects), Notice.session(name));
        next = next.withSessions(next.sessions().stream().filter(x -> !x.name().equals(name)).toList());
        next = rebindSession(next, name, "");
        effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        effects.add(save(next));
        return new Transition(next, effects);
    }

    private Transition importSessions(AppState s, List<SessionSpec> specs) {
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (SessionState x : s.sessions()) {
            names.add(x.name());
            ids.add(x.spec().claudeSessionId());
        }
        List<SessionState> sessions = new ArrayList<>(s.sessions());
        List<Effect> effects = new ArrayList<>();
        int added = 0;
        for (SessionSpec spec : specs) {
            if (names.contains(spec.name()) || (!spec.claudeSessionId().isEmpty() && ids.contains(spec.claudeSessionId()))) {
                continue;
            }
            names.add(spec.name());
            ids.add(spec.claudeSessionId());
            sessions.add(SessionState.closed(spec));
            effects.add(new Effect.LoadHistory(spec));
            added++;
        }
        AppState next = s.withSessions(sessions);
        if (added > 0) {
            effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        }
        PanelState focused = next.focusedPanel();
        if (added == 1 && focused.session().isEmpty()) {
            // the usual "import, then talk": one conversation and a panel waiting for one
            String name = next.sessions().getLast().name();
            next = next.withPanel(focused.withSession(name));
            effects.add(save(next));
            return new Transition(notice(next, Notice.Level.INFO, "Imported " + name + " and bound it to " + focused.title()), effects);
        }
        return new Transition(notice(next, Notice.Level.INFO, "Imported " + added + " of " + specs.size() + " sessions"), effects);
    }

    private Transition startSession(AppState s, String name) {
        return s.session(name)
                .filter(x -> !x.status().isUsable() && x.status() != Status.CLOSING)
                .map(x -> new Transition(s.withSession(x.withStatus(Status.OPENING, "")), List.of(new Effect.StartClaude(x.spec()))))
                .orElse(Transition.of(s));
    }

    /** A stopped session starts on a new conversation; the spec is saved first, so a crash mid-start resumes nothing old. */
    private Transition startFresh(AppState s, String name) {
        Optional<SessionState> found = s.session(name).filter(x -> !x.status().isUsable() && x.status() != Status.CLOSING);
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        SessionState x = found.get();
        String previous = x.spec().claudeSessionId();
        // a new conversation has nothing to reflect on yet
        SessionState fresh = x.withSpec(x.spec().restarted()).withStatus(Status.OPENING, "").withReflection(Reflection.FRESH);
        AppState next = s.withSession(fresh);
        if (!previous.isEmpty()) {
            next = notice(next, Notice.Level.INFO, "Session " + name + ": new conversation; the previous one (" + previous
                    + ") can be imported again", Notice.session(name));
        }
        return new Transition(next, List.of(new Effect.SaveSessions(sessionSpecs(next)), new Effect.StartClaude(fresh.spec())));
    }

    /**
     * A command (the checkpoint, {@code /compact}, {@code /clear}) runs as a prompt from the session's panel, as if the
     * user had typed it there.
     */
    private Transition sessionCommand(AppState s, String name, String command) {
        Optional<SessionState> found = s.session(name);
        if (found.isEmpty() || command.isBlank() || found.get().status() == Status.CLOSING) {
            return Transition.of(s);
        }
        List<PanelState> panels = s.panelsBoundToSession(name);
        if (panels.isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "Session " + name + " has no panel; open it to send "
                    + command, Notice.session(name)));
        }
        return runPrompt(s, panels.getFirst(), command);
    }

    /** The named sessions first, in the order given; the rest after them as they were. Saved, so a restart keeps it. */
    private Transition orderSessions(AppState s, List<String> names) {
        List<SessionState> ordered = new ArrayList<>(s.sessions().size());
        for (String name : names) {
            s.session(name).filter(x -> !ordered.contains(x)).ifPresent(ordered::add);
        }
        for (SessionState x : s.sessions()) {
            if (!ordered.contains(x)) {
                ordered.add(x);
            }
        }
        if (ordered.equals(s.sessions())) {
            return Transition.of(s);
        }
        AppState next = s.withSessions(ordered);
        return new Transition(next, List.of(new Effect.SaveSessions(sessionSpecs(next))));
    }

    /**
     * Empties the session's Session tab; the conversation itself is untouched. Refused while a turn runs: its blocks are
     * still being written.
     */
    private Transition clearTranscript(AppState s, String name) {
        Optional<SessionState> found = s.session(name);
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        if (found.get().isBusy()) {
            return Transition.of(notice(s, Notice.Level.WARN, "Session " + name + " is working; clear it once the turn has ended",
                    Notice.session(name)));
        }
        return new Transition(s, List.of(new Effect.ClearTranscript(name)));
    }

    private Transition stopSession(AppState s, String name) {
        return s.session(name)
                .filter(x -> x.status().isUsable())
                .map(x -> new Transition(s.withSession(x.withStatus(Status.CLOSING, "")), List.<Effect>of(new Effect.StopClaude(name))))
                .orElse(Transition.of(s));
    }

    /** The chip and the sidebar say "interrupting" at once; the next activity or the turn's end replaces it. */
    private Transition interruptSession(AppState s, String name) {
        return s.session(name)
                .filter(SessionState::isBusy)
                .map(x -> new Transition(s.withSession(x.withActivity("interrupting", clock.millis())),
                        List.<Effect>of(new Effect.InterruptClaude(name))))
                .orElse(Transition.of(s));
    }

    private Transition sessionSetting(AppState s, String name, String value, boolean model) {
        Optional<SessionState> found = s.session(name);
        if (found.isEmpty() || value.isBlank()) {
            return Transition.of(s);
        }
        SessionState x = found.get();
        SessionState updated = x.withSpec(model ? x.spec().withModel(value) : x.spec().withEffort(value));
        List<Effect> effects = new ArrayList<>();
        if (x.status() == Status.OPEN) {
            effects.add(new Effect.SendToClaude(name, (model ? "/model " : "/effort ") + value, "settings"));
            updated = updated.withTurns(updated.pendingTurns() + 1, clock.millis());
        }
        AppState next = s.withSession(updated);
        effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        return new Transition(next, effects);
    }

    private Transition sessionInit(AppState s, Intent.SessionInit i) {
        Optional<SessionState> found = s.session(i.name());
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        SessionState x = found.get().withInfo(i.info(), clock.millis());
        if (x.status() == Status.OPENING) {
            x = x.withStatus(Status.OPEN, "");
        }
        List<Effect> effects = new ArrayList<>();
        String id = i.info().claudeSessionId();
        String previous = x.spec().claudeSessionId();
        // a resumed conversation that reports another id was left for a new one: /clear, under -p too
        boolean cleared = !id.isEmpty() && !previous.isEmpty() && !id.equals(previous) && x.spec().startMode() == StartMode.RESUME;
        if (!id.isEmpty() && (!id.equals(previous) || x.spec().startMode() != StartMode.RESUME)) {
            x = x.withSpec(x.spec().resumable(id));
        }
        AppState next = s.withSession(cleared ? x.withReflection(Reflection.FRESH) : x);
        if (cleared) {
            next = notice(next, Notice.Level.INFO, "Session " + x.name() + ": new conversation; the previous one (" + previous
                    + ") can be imported again", Notice.session(x.name()));
        }
        if (!x.spec().equals(found.get().spec())) {
            effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        }
        return new Transition(next, effects);
    }

    private Transition turnEnded(AppState s, Intent.SessionTurnEnded i) {
        Optional<SessionState> found = s.session(i.name());
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        long now = clock.millis();
        // a result while no turn ran made no model call (Claude Code sends one after a background agent's report): it
        // finished nothing
        boolean ran = found.get().isBusy();
        if (i.error() && found.get().status() == Status.FAILED) {
            // a prompt to a session whose start failed: the start's error already stands, once
            return Transition.of(s.withSession(found.get().withTurns(Math.max(0, i.queued()), now).withActivity("", now)));
        }
        SessionState x = found.get()
                .withTurns(Math.max(0, i.queued()), now)
                .withUsage(i.costUsd() >= 0 ? i.costUsd() : found.get().costUsd(),
                        i.contextTokens() >= 0 ? i.contextTokens() : found.get().contextTokens(),
                        i.contextWindow() > 0 ? i.contextWindow() : found.get().contextWindow(), now)
                .withActivity("", now);
        x = x.withReflection(x.reflection().afterTurn(x.contextTokens(), x.contextWindow()));
        AppState next = s.withSession(x);
        List<Effect> effects = new ArrayList<>();
        // the user's own interrupt is an outcome, not a failure
        boolean interrupted = i.error() && INTERRUPTED.equals(i.message());
        x = x.withLastTurnError(i.error() && !interrupted ? i.message() : "");
        next = next.withSession(x);
        if (interrupted) {
            next = notice(next, Notice.Level.INFO, i.name() + ": interrupted", Notice.session(i.name()));
        } else if (i.error()) {
            next = notice(next, Notice.Level.ERROR, i.name() + ": " + i.message(), Notice.session(i.name()));
        } else {
            next = clear(next, Notice.session(i.name()));
        }
        if (!x.isBusy()) {
            // a turn that hands work to the background has not finished it: the turn Claude Code starts when the work
            // ends notifies; a later turn that ends while older work runs on has finished; a failure always notifies
            if (!interrupted && (i.error() || ran && !x.isAwaitingHandOff())) {
                effects.add(new Effect.DesktopNotify(i.name(), i.error() ? "Turn failed: " + i.message() : "Turn finished"));
            }
            // nothing runs or is queued: a statement card still waiting belongs to a call the turn abandoned (an
            // interrupt), and answering Run would act after the fact. Other cards may belong to a background
            // agent that outlives the turn; Claude Code cancels abandoned calls itself, which withdraws them. While
            // background work runs, any card may be its own: none is withdrawn, an approval is never missed.
            if (!x.isWaiting()) {
                next = withdrawApprovals(next, i.name(), "the turn ended", effects,
                        a -> a.kind() == ApprovalKind.SQL_WRITE);
            }
        }
        return new Transition(next, effects);
    }

    /** Denies and drops every waiting approval of {@code session}, so no MCP call waits for an answer nobody can give. */
    private static AppState withdrawApprovals(AppState s, String session, String reason, List<Effect> effects) {
        return withdrawApprovals(s, session, reason, effects, a -> true);
    }

    private static AppState withdrawApprovals(AppState s, String session, String reason, List<Effect> effects,
                                              Predicate<Approval> which) {
        List<Approval> kept = new ArrayList<>();
        AppState next = s;
        for (Approval a : s.approvals()) {
            if (a.session().equals(session) && which.test(a)) {
                effects.add(new Effect.ResolveApproval(a, new ApprovalDecision.Deny(reason)));
                next = clear(next, Notice.approval(a.id())); // its "asks" notice would point at an empty Approvals tab
            } else {
                kept.add(a);
            }
        }
        return kept.size() == s.approvals().size() ? s : next.withApprovals(kept);
    }

    private Transition sessionExited(AppState s, Intent.SessionExited i) {
        Optional<SessionState> found = s.session(i.name());
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        SessionState x = found.get();
        boolean expected = x.status() == Status.CLOSING || i.exitCode() == 0;
        long now = clock.millis();
        x = x.withStatus(expected ? Status.CLOSED : Status.FAILED, expected ? "" : i.error())
                .withTurns(0, now)
                .withActivity("", now)
                .withoutTasks();
        List<Effect> effects = new ArrayList<>();
        AppState next = withdrawApprovals(s.withSession(x), i.name(), "session ended", effects);
        if (!expected) {
            // -1: the process never started; the transcript holds the same text, so the notice says only what happened
            String text = i.exitCode() == -1 ? "Session " + i.name() + " could not start: " + i.error()
                    : "Session " + i.name() + " exited [code=" + i.exitCode() + "] " + i.error();
            next = notice(next, Notice.Level.ERROR, text, Notice.session(i.name()));
        }
        return new Transition(next, effects);
    }

    private static AppState rebindSession(AppState s, String from, String to) {
        List<PanelState> panels = new ArrayList<>(s.panels().size());
        for (PanelState p : s.panels()) {
            panels.add(p.session().equals(from) ? p.withSession(to) : p);
        }
        // closed panels keep their bindings, so they follow renames and removals too
        List<PanelState> closed = s.closed().stream().map(p -> p.session().equals(from) ? p.withSession(to) : p).toList();
        return s.withPanels(panels, s.focused()).withClosed(closed);
    }

    private static List<SessionSpec> sessionSpecs(AppState s) {
        return s.sessions().stream().map(SessionState::spec).toList();
    }

    // ---- approvals

    /**
     * A session asks: its card joins the Approvals tab, which comes forward; the keyboard stays where it is, so typing in
     * the editor goes on. A card asked for again (the same id) changes nothing.
     */
    private Transition approvalRequested(AppState s, Approval a) {
        if (s.approval(a.id()).isPresent()) {
            return Transition.of(s);
        }
        List<Approval> approvals = new ArrayList<>(s.approvals());
        approvals.add(a);
        AppState next = notice(s.withApprovals(approvals).withLowerTab(LowerTab.APPROVALS), Notice.Level.INFO,
                a.session() + " asks: " + a.summary() + " (⌃2)", Notice.approval(a.id()));
        return new Transition(next, List.of(new Effect.DesktopNotify(a.session() + " needs approval", a.summary())));
    }

    /**
     * The user answered a card. When it was the last one and the Approvals tab is in front, the Session tab comes
     * forward on the panel of the session that asked, where its turn goes on.
     */
    private Transition approvalAnswered(AppState s, Intent.ApprovalAnswered i) {
        Optional<Approval> a = s.approval(i.id());
        if (a.isEmpty()) {
            return Transition.of(s);
        }
        List<Approval> rest = s.approvals().stream().filter(x -> x.id() != i.id()).toList();
        AppState next = clear(s.withApprovals(rest), Notice.approval(i.id()));
        if (rest.isEmpty() && s.lowerTab().equals(LowerTab.APPROVALS)) {
            List<PanelState> asking = next.panelsBoundToSession(a.get().session());
            next = (asking.isEmpty() ? next : next.withFocused(asking.getFirst().id())).withLowerTab(LowerTab.SESSION);
        }
        return new Transition(next, List.of(new Effect.ResolveApproval(a.get(), i.decision())));
    }

    private Transition approvalWithdrawn(AppState s, Intent.ApprovalWithdrawn i) {
        if (s.approval(i.id()).isEmpty()) {
            return Transition.of(s);
        }
        AppState next = s.withApprovals(s.approvals().stream().filter(x -> x.id() != i.id()).toList());
        return Transition.of(notice(next, Notice.Level.WARN, "Approval withdrawn: " + i.reason(), Notice.approval(i.id())));
    }

    // ---- views

    /** Longest view title kept; a session's title past it is clipped, so a tab never fills the window. */
    static final int VIEW_TITLE_CHARS = 80;

    private Transition showView(AppState s, Intent.ShowView shown) {
        Intent.ShowView i = shown.title().length() <= VIEW_TITLE_CHARS ? shown
                : new Intent.ShowView(shown.session(), Clip.keepStart(shown.title(), VIEW_TITLE_CHARS), shown.spec(), shown.placement());
        for (ViewState v : s.views()) {
            if (!v.pinned() && v.title().equals(i.title()) && v.session().equals(i.session()) && v.placement() == i.placement()) {
                // the user's own command (session "") shows its view again; a session's update in place does not move the user
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
        return Transition.of(lookingAt ? next : notice(next, Notice.Level.INFO, i.session() + " opened " + i.title()));
    }

    /**
     * What a session may change in the window, so it never moves the user away from another session's panel. Focus
     * is not changed here: the panel to focus rides on the {@link Effect.Arrange} effect, which the shell applies once
     * the user pauses typing, so keystrokes never land in a panel the user did not pick.
     */
    private Transition sessionLayout(AppState s, Intent.SessionLayout i) {
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
        return new Transition(notice(next, Notice.Level.INFO, i.session() + " " + String.join(", ", done) + undo), effects);
    }

    private Transition closeView(AppState s, long id) {
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

    // ---- development tools

    private Transition runGradle(AppState s, Intent.RunGradle i) {
        PanelId panel = i.panel() != null ? i.panel() : s.focused();
        String tasks = i.tasks().strip();
        if (tasks.isEmpty()) {
            return Transition.of(notice(s, Notice.Level.WARN, "No Gradle tasks given"));
        }
        String dir = s.panel(panel).flatMap(p -> s.session(p.session())).map(x -> x.spec().workDir())
                .orElse(HomeDir.home());
        return runCommand(s, "gradle " + tasks, panel == null ? "" : panel.value(), id -> new Effect.RunGradle(id, dir, tasks));
    }

    /**
     * Opens a console view for a new run and starts it. Running the same command again replaces the
     * previous run's view, unless it is pinned, and stops that run when it still runs.
     */
    private Transition runCommand(AppState s, String title, String panel, LongFunction<Effect> start) {
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
        Transition shown = showView(s.withIdTaken(), new Intent.ShowView("", title, ViewSpec.Console.running(runId, title, panel), Placement.LOWER));
        // a re-run replaces its view in place, which keeps the tab; the user asked for this run, so show it
        AppState placed = shown.state();
        AppState withTab = placed.views().stream()
                .filter(v -> v.spec() instanceof ViewSpec.Console c && c.runId() == runId).findFirst()
                .map(v -> placed.withLowerTab(LowerTab.view(v.id()))).orElse(placed);
        shown = new Transition(withTab, shown.effects());
        effects.add(start.apply(runId));
        return new Transition(shown.state(), effects);
    }

    private Transition commandFinished(AppState s, Intent.CommandFinished i) {
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

    // ---- editor

    private Transition editPanel(AppState s, Intent.EditPanel i) {
        PanelId target = i.panel() != null ? i.panel() : s.focused();
        Optional<PanelState> panel = s.panel(target);
        if (panel.isEmpty()) {
            return Transition.of(notice(s, Notice.Level.ERROR, "No panel " + target));
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
            next = notice(s, Notice.Level.INFO, i.session() + " " + what);
        }
        return new Transition(next, List.of(new Effect.EditPanelText(target, i.text(), i.mode())));
    }

    // ---- notices

    private AppState notice(AppState s, Notice.Level level, String text) {
        return notice(s, level, text, "");
    }

    /**
     * Records a notice in the history and shows it unless an error stands: an error stays on the status bar until
     * the user dismisses it, a newer error replaces it, or a notice about the same subject replaces it.
     */
    private AppState notice(AppState s, Notice.Level level, String text, String subject) {
        Notice n = new Notice(level, text, clock.millis(), subject);
        List<Notice> history = new ArrayList<>(s.notices().size() + 1);
        int from = Math.max(0, s.notices().size() + 1 - AppState.NOTICE_HISTORY);
        history.addAll(s.notices().subList(from, s.notices().size()));
        history.add(n);
        Notice standing = s.notice();
        boolean replaces = standing.isEmpty() || standing.level() != Notice.Level.ERROR || level == Notice.Level.ERROR
                || !subject.isEmpty() && subject.equals(standing.subject());
        return s.withNotices(replaces ? n : standing, history);
    }

    /** Clears the status bar's notice when it is about {@code subject}: the thing it reported on has moved on. */
    private static AppState clear(AppState s, String subject) {
        Notice n = s.notice();
        return !n.isEmpty() && !subject.isEmpty() && subject.equals(n.subject()) ? s.withNotice(Notice.NONE) : s;
    }
}
