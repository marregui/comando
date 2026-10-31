package dev.marregui.comando.core.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;

/**
 * The whole UI state as one immutable value. It holds metadata and ids only: result rows, transcripts and
 * editor text live in owned stores outside it, referenced by id. Lists are small (tens of entries), so
 * lookups scan.
 *
 * <p>Invariants the reducer keeps: at least one panel; {@code focused} names a panel; names are unique within
 * connections and within sessions; at most one run per panel; a session has at most one panel of comando's own, open
 * or closed, and any number of documents attached (record 88).</p>
 *
 * @param folders the folders the Files tab shows at its top level, in the user's order
 * @param closed  comando's own panels that are closed, most recently closed last: each keeps its file, title and
 *                bindings until it is removed; documents are not kept here
 * @param notice  the notice the status bar shows: an error stands until the user dismisses it, a newer error
 *                replaces it, or an event about the same subject clears it
 * @param notices the most recent notices, oldest first, at most {@link #NOTICE_HISTORY}, including those that did
 *                not reach the status bar because an error was standing
 * @param instances the other comando instances on the comando Edge and the questions between them (record 124)
 */
public record AppState(
        List<PanelState> panels,
        List<PanelState> closed,
        PanelId focused,
        List<ConnectionState> connections,
        List<SessionState> sessions,
        List<FolderRoot> folders,
        List<SqlRun> runs,
        List<Approval> approvals,
        List<ViewState> views,
        LowerTab lowerTab,
        Notice notice,
        List<Notice> notices,
        InstancesState instances,
        long nextId) {

    public AppState {
        panels = List.copyOf(panels);
        closed = closed == null ? List.of() : List.copyOf(closed);
        Objects.requireNonNull(focused, "focused");
        connections = List.copyOf(connections);
        sessions = List.copyOf(sessions);
        folders = folders == null ? List.of() : List.copyOf(folders);
        runs = List.copyOf(runs);
        approvals = List.copyOf(approvals);
        views = List.copyOf(views);
        Objects.requireNonNull(lowerTab, "lowerTab");
        Objects.requireNonNull(notice, "notice");
        notices = notices == null ? List.of() : List.copyOf(notices);
        instances = instances == null ? InstancesState.NONE : instances;
        if (panels.isEmpty()) {
            throw new IllegalArgumentException("state needs at least one panel");
        }
        if (panels.stream().noneMatch(p -> p.id().equals(focused))) {
            throw new IllegalArgumentException("focused panel is not in the panel list [focused=" + focused + ']');
        }
    }

    /** Notices kept for the history the status bar opens. */
    public static final int NOTICE_HISTORY = 50;

    public static AppState initial(final List<PanelState> panels, final List<ConnectionState> connections, final List<SessionState> sessions) {
        return initial(panels, connections, sessions, List.of());
    }

    public static AppState initial(final List<PanelState> panels, final List<ConnectionState> connections, final List<SessionState> sessions,
                                   final List<FolderRoot> folders) {
        return new AppState(panels, List.of(), panels.getFirst().id(), connections, sessions, folders, List.of(), List.of(), List.of(),
                LowerTab.SESSION, Notice.NONE, List.of(), InstancesState.NONE, 1L);
    }

    // ---- lookups

    public Optional<PanelState> panel(final PanelId id) {
        return find(panels, p -> p.id().equals(id));
    }

    public PanelState focusedPanel() {
        return panel(focused).orElseThrow();
    }

    public Optional<ConnectionState> connection(final String name) {
        return find(connections, c -> c.name().equals(name));
    }

    public Optional<SessionState> session(final String name) {
        return find(sessions, s -> s.name().equals(name));
    }

    public Optional<SqlRun> runOf(final PanelId panel) {
        return find(runs, r -> r.panel().equals(panel));
    }

    public Optional<SqlRun> run(final long requestId) {
        return find(runs, r -> r.requestId() == requestId);
    }

    public Optional<Approval> approval(final long id) {
        return find(approvals, a -> a.id() == id);
    }

    public Optional<ViewState> view(final long id) {
        return find(views, v -> v.id() == id);
    }

    /** The open panels bound to the session: its own panel first, then the documents attached to it. */
    public List<PanelState> panelsBoundToSession(final String session) {
        return panels.stream().filter(p -> p.session().equals(session))
                .sorted((a, b) -> Boolean.compare(a.document(), b.document())).toList();
    }

    /**
     * The session's own panel, open: the one panel of comando's own bound to it, titled with its name. A session has
     * one; documents attached to it are not its panel.
     */
    public Optional<PanelState> homePanel(final String session) {
        return session.isEmpty() ? Optional.empty() : find(panels, p -> !p.document() && p.session().equals(session));
    }

    public int pendingApprovals(final String session) {
        int n = 0;
        for (final Approval a : approvals) {
            if (a.session().equals(session)) {
                n++;
            }
        }
        return n;
    }

    // ---- copies

    public AppState withPanels(final List<PanelState> newPanels, final PanelId newFocused) {
        return new AppState(newPanels, closed, newFocused, connections, sessions, folders, runs, approvals, views, lowerTab, notice, notices, instances, nextId);
    }

    public AppState withClosed(final List<PanelState> newClosed) {
        return new AppState(panels, newClosed, focused, connections, sessions, folders, runs, approvals, views, lowerTab, notice, notices, instances, nextId);
    }

    /** A closed panel of comando's own, by id. */
    public Optional<PanelState> closedPanel(final PanelId id) {
        return find(closed, p -> p.id().equals(id));
    }

    /** Whether a panel, open or closed, has this title, ignoring case: a title names one panel to the tools. */
    public boolean titleTaken(final String title) {
        return panels.stream().anyMatch(p -> p.title().equalsIgnoreCase(title)) || closed.stream().anyMatch(p -> p.title().equalsIgnoreCase(title));
    }

    /** The closed panel bound to the session, if any: its own panel, closed (a document is dropped when closed). */
    public Optional<PanelState> closedPanelOfSession(final String session) {
        return session.isEmpty() ? Optional.empty() : find(closed, p -> p.session().equals(session));
    }

    public AppState withPanel(final PanelState panel) {
        return withPanels(replace(panels, p -> p.id().equals(panel.id()), panel), focused);
    }

    /** The state with {@code id} focused; this one when it already is. */
    public AppState withFocused(final PanelId id) {
        if (id.equals(focused)) {
            return this;
        }
        return new AppState(panels, closed, id, connections, sessions, folders, runs, approvals, views, lowerTab, notice, notices, instances, nextId);
    }

    public AppState withConnections(final List<ConnectionState> newConnections) {
        return new AppState(panels, closed, focused, newConnections, sessions, folders, runs, approvals, views, lowerTab, notice, notices, instances, nextId);
    }

    public AppState withConnection(final ConnectionState connection) {
        return withConnections(replace(connections, c -> c.name().equals(connection.name()), connection));
    }

    public AppState withFolders(final List<FolderRoot> newFolders) {
        return new AppState(panels, closed, focused, connections, sessions, newFolders, runs, approvals, views, lowerTab, notice, notices, instances, nextId);
    }

    public Optional<FolderRoot> folder(final String name) {
        return find(folders, f -> f.name().equals(name));
    }

    public AppState withSessions(final List<SessionState> newSessions) {
        return new AppState(panels, closed, focused, connections, newSessions, folders, runs, approvals, views, lowerTab, notice, notices, instances, nextId);
    }

    public AppState withSession(final SessionState session) {
        return withSessions(replace(sessions, s -> s.name().equals(session.name()), session));
    }

    public AppState withSession(final String name, final UnaryOperator<SessionState> change) {
        return session(name).map(s -> withSession(change.apply(s))).orElse(this);
    }

    public AppState withRuns(final List<SqlRun> newRuns) {
        return new AppState(panels, closed, focused, connections, sessions, folders, newRuns, approvals, views, lowerTab, notice, notices, instances, nextId);
    }

    /** Puts {@code run} as the panel's run, replacing the previous one. */
    public AppState withRun(final SqlRun run) {
        final List<SqlRun> next = new ArrayList<>(runs.size() + 1);
        for (final SqlRun r : runs) {
            if (!r.panel().equals(run.panel())) {
                next.add(r);
            }
        }
        next.add(run);
        return withRuns(next);
    }

    public AppState withApprovals(final List<Approval> newApprovals) {
        return new AppState(panels, closed, focused, connections, sessions, folders, runs, newApprovals, views, lowerTab, notice, notices, instances, nextId);
    }

    public AppState withViews(final List<ViewState> newViews) {
        return new AppState(panels, closed, focused, connections, sessions, folders, runs, approvals, newViews, lowerTab, notice, notices, instances, nextId);
    }

    /** The state with {@code tab} selected; this one when it already is. */
    public AppState withLowerTab(final LowerTab tab) {
        if (tab.equals(lowerTab)) {
            return this;
        }
        return new AppState(panels, closed, focused, connections, sessions, folders, runs, approvals, views, tab, notice, notices, instances, nextId);
    }

    /** The standing notice only; the history is unchanged. */
    public AppState withNotice(final Notice newNotice) {
        return new AppState(panels, closed, focused, connections, sessions, folders, runs, approvals, views, lowerTab, newNotice, notices, instances, nextId);
    }

    /** The standing notice and the history. */
    public AppState withNotices(final Notice newNotice, final List<Notice> newNotices) {
        return new AppState(panels, closed, focused, connections, sessions, folders, runs, approvals, views, lowerTab, newNotice, newNotices, instances, nextId);
    }

    /** Returns this state with the id counter advanced; the id handed out is {@link #nextId()} of the receiver. */
    public AppState withIdTaken() {
        return new AppState(panels, closed, focused, connections, sessions, folders, runs, approvals, views, lowerTab, notice, notices, instances, nextId + 1);
    }

    /** The instances on the comando Edge as last read. */
    public AppState withInstances(final InstancesState newInstances) {
        return new AppState(panels, closed, focused, connections, sessions, folders, runs, approvals, views, lowerTab, notice, notices, newInstances,
                nextId);
    }

    // ---- list helpers

    static <T> Optional<T> find(final List<T> list, final Predicate<T> match) {
        for (final T t : list) {
            if (match.test(t)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    static <T> List<T> replace(final List<T> list, final Predicate<T> match, final T value) {
        final List<T> next = new ArrayList<>(list.size());
        for (final T t : list) {
            next.add(match.test(t) ? value : t);
        }
        return next;
    }
}
