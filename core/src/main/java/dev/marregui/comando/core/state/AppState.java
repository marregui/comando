package dev.marregui.comando.core.state;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.PanelId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The whole UI state as one immutable value. It holds metadata and ids only: result rows, transcripts and
 * editor text live in owned stores outside it, referenced by id. Lists are small (tens of entries), so
 * lookups scan.
 *
 * <p>Invariants the reducer keeps: at least one panel; {@code focused} names a panel; names are unique
 * within connections and within sessions; at most one run per panel.</p>
 */
public record AppState(
        List<PanelState> panels,
        PanelId focused,
        List<ConnectionState> connections,
        List<SessionState> sessions,
        List<SqlRun> runs,
        List<Approval> approvals,
        List<ViewState> views,
        LowerTab lowerTab,
        Notice notice,
        long nextId) {

    public AppState {
        panels = List.copyOf(panels);
        Objects.requireNonNull(focused, "focused");
        connections = List.copyOf(connections);
        sessions = List.copyOf(sessions);
        runs = List.copyOf(runs);
        approvals = List.copyOf(approvals);
        views = List.copyOf(views);
        Objects.requireNonNull(lowerTab, "lowerTab");
        Objects.requireNonNull(notice, "notice");
        if (panels.isEmpty()) {
            throw new IllegalArgumentException("state needs at least one panel");
        }
        if (panels.stream().noneMatch(p -> p.id().equals(focused))) {
            throw new IllegalArgumentException("focused panel is not in the panel list [focused=" + focused + ']');
        }
    }

    public static AppState initial(List<PanelState> panels, List<ConnectionState> connections, List<SessionState> sessions) {
        return new AppState(panels, panels.getFirst().id(), connections, sessions, List.of(), List.of(), List.of(),
                LowerTab.RESULTS, Notice.NONE, 1L);
    }

    // ---- lookups

    public Optional<PanelState> panel(PanelId id) {
        return find(panels, p -> p.id().equals(id));
    }

    public PanelState focusedPanel() {
        return panel(focused).orElseThrow();
    }

    public Optional<ConnectionState> connection(String name) {
        return find(connections, c -> c.name().equals(name));
    }

    public Optional<SessionState> session(String name) {
        return find(sessions, s -> s.name().equals(name));
    }

    public Optional<SqlRun> runOf(PanelId panel) {
        return find(runs, r -> r.panel().equals(panel));
    }

    public Optional<SqlRun> run(long requestId) {
        return find(runs, r -> r.requestId() == requestId);
    }

    public Optional<Approval> approval(long id) {
        return find(approvals, a -> a.id() == id);
    }

    public Optional<ViewState> view(long id) {
        return find(views, v -> v.id() == id);
    }

    public List<PanelState> panelsBoundToSession(String session) {
        return panels.stream().filter(p -> p.session().equals(session)).toList();
    }

    public int pendingApprovals(String session) {
        int n = 0;
        for (Approval a : approvals) {
            if (a.session().equals(session)) {
                n++;
            }
        }
        return n;
    }

    // ---- copies

    public AppState withPanels(List<PanelState> newPanels, PanelId newFocused) {
        return new AppState(newPanels, newFocused, connections, sessions, runs, approvals, views, lowerTab, notice, nextId);
    }

    public AppState withPanel(PanelState panel) {
        return withPanels(replace(panels, p -> p.id().equals(panel.id()), panel), focused);
    }

    /** This state when {@code id} is already focused. */
    public AppState withFocused(PanelId id) {
        if (id.equals(focused)) {
            return this;
        }
        return new AppState(panels, id, connections, sessions, runs, approvals, views, lowerTab, notice, nextId);
    }

    public AppState withConnections(List<ConnectionState> newConnections) {
        return new AppState(panels, focused, newConnections, sessions, runs, approvals, views, lowerTab, notice, nextId);
    }

    public AppState withConnection(ConnectionState connection) {
        return withConnections(replace(connections, c -> c.name().equals(connection.name()), connection));
    }

    public AppState withSessions(List<SessionState> newSessions) {
        return new AppState(panels, focused, connections, newSessions, runs, approvals, views, lowerTab, notice, nextId);
    }

    public AppState withSession(SessionState session) {
        return withSessions(replace(sessions, s -> s.name().equals(session.name()), session));
    }

    public AppState withSession(String name, UnaryOperator<SessionState> change) {
        return session(name).map(s -> withSession(change.apply(s))).orElse(this);
    }

    public AppState withRuns(List<SqlRun> newRuns) {
        return new AppState(panels, focused, connections, sessions, newRuns, approvals, views, lowerTab, notice, nextId);
    }

    /** Puts {@code run} as the panel's run, replacing the previous one. */
    public AppState withRun(SqlRun run) {
        List<SqlRun> next = new ArrayList<>(runs.size() + 1);
        for (SqlRun r : runs) {
            if (!r.panel().equals(run.panel())) {
                next.add(r);
            }
        }
        next.add(run);
        return withRuns(next);
    }

    public AppState withApprovals(List<Approval> newApprovals) {
        return new AppState(panels, focused, connections, sessions, runs, newApprovals, views, lowerTab, notice, nextId);
    }

    public AppState withViews(List<ViewState> newViews) {
        return new AppState(panels, focused, connections, sessions, runs, approvals, newViews, lowerTab, notice, nextId);
    }

    /** This state when {@code tab} is already selected. */
    public AppState withLowerTab(LowerTab tab) {
        if (tab.equals(lowerTab)) {
            return this;
        }
        return new AppState(panels, focused, connections, sessions, runs, approvals, views, tab, notice, nextId);
    }

    public AppState withNotice(Notice newNotice) {
        return new AppState(panels, focused, connections, sessions, runs, approvals, views, lowerTab, newNotice, nextId);
    }

    /** Returns this state with the id counter advanced; the id handed out is {@link #nextId()} of the receiver. */
    public AppState withIdTaken() {
        return new AppState(panels, focused, connections, sessions, runs, approvals, views, lowerTab, notice, nextId + 1);
    }

    // ---- list helpers

    static <T> Optional<T> find(List<T> list, Predicate<T> match) {
        for (T t : list) {
            if (match.test(t)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    static <T> List<T> replace(List<T> list, Predicate<T> match, T value) {
        List<T> next = new ArrayList<>(list.size());
        for (T t : list) {
            next.add(match.test(t) ? value : t);
        }
        return next;
    }
}
