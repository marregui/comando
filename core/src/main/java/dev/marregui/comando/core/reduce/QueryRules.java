package dev.marregui.comando.core.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

import dev.marregui.comando.core.dispatch.Classifier;
import dev.marregui.comando.core.dispatch.Statements;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.view.Placement;

import static dev.marregui.comando.core.reduce.Shared.LONG_RUN_MILLIS;
import static dev.marregui.comando.core.reduce.Shared.clear;
import static dev.marregui.comando.core.reduce.Shared.notice;
import static dev.marregui.comando.core.reduce.Shared.savePanels;

/**
 * The connections' and statements' rules: text run from a panel, SQL runs and their events, and saving, removing,
 * opening, closing and testing connections.
 */
final class QueryRules {

    private QueryRules() {
    }

    static Transition runText(final Clock clock, final AppState s, final Intent.RunText i) {
        // a panel closed since the text was sent (a console's origin) falls back to the focused one
        final Optional<PanelState> found = s.panel(i.panel() != null && s.panel(i.panel()).isPresent() ? i.panel() : s.focused());
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        final PanelState panel = found.get();
        final Classifier.Classification c = switch (i.target()) {
            case AUTO -> Classifier.classify(i.text());
            case SQL -> new Classifier.Classification(Target.SQL, Classifier.stripMarker(i.text()));
            case CLAUDE -> new Classifier.Classification(Target.CLAUDE, Classifier.stripMarker(i.text()));
        };
        // forced to SQL (⌥⏎), a block of comments would reach the database and report "0 rows changed"
        if (c.text().isBlank() || c.target() == Target.SQL && Classifier.isCommentOnly(c.text())) {
            return Transition.of(notice(clock, s, Notice.Level.WARN, "Nothing to run: the block holds only comments",
                    Notice.panel(panel.id().value())));
        }
        return c.target() == Target.SQL ? runSql(clock, s, panel, c.text()) : TurnRules.runPrompt(clock, s, panel, c.text());
    }

    /**
     * A live plot's re-run: only while the panel's last run is that same statement and no longer active, so a
     * refresh decided on an older state never cancels or replaces a statement the user ran since.
     */
    static Transition refreshSql(final Clock clock, final AppState s, final Intent.RefreshSql i) {
        final Optional<PanelState> panel = s.panel(i.panel());
        final Optional<SqlRun> last = panel.flatMap(p -> s.runOf(p.id()));
        if (panel.isEmpty() || last.isPresent() && (!last.get().sql().equals(i.sql()) || last.get().status().isActive())) {
            return Transition.of(s);
        }
        return runSql(clock, s, panel.get(), i.sql(), false);
    }

    private static Transition runSql(final Clock clock, final AppState s, final PanelState panel, final String sql) {
        return runSql(clock, s, panel, sql, true);
    }

    private static Transition runSql(final Clock clock, final AppState s, final PanelState panel, final String sql, final boolean showResults) {
        final String subject = Notice.panel(panel.id().value());
        if (panel.connection().isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Panel " + panel.title()
                    + " has no connection: pick one from its header, or add one with Connections… (⌘⇧O)", subject));
        }
        final Optional<ConnectionState> conn = s.connection(panel.connection());
        if (conn.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No connection called " + panel.connection(), subject));
        }
        final List<Effect> effects = new ArrayList<>();
        // a live plot's refresh leaves the status bar alone; the user's own run clears its panel's notice
        AppState next = showResults ? clear(s, subject) : s;
        final Optional<SqlRun> previous = s.runOf(panel.id());
        if (previous.isPresent()) {
            if (previous.get().status().isActive()) {
                effects.add(new Effect.CancelSql(previous.get().requestId()));
                if (showResults) {
                    next = notice(clock, next, Notice.Level.WARN, "Stopped the statement still running in " + panel.title(), subject);
                }
            }
            effects.add(new Effect.ReleaseResult(previous.get().requestId()));
        }
        final ConnectionState c = conn.get();
        if (!c.status().isUsable()) {
            effects.add(new Effect.OpenDb(c.spec()));
            next = next.withConnection(c.with(Status.OPENING, ""));
        }
        final long id = next.nextId();
        next = next.withIdTaken().withRun(SqlRun.queued(id, panel.id(), c.name(), sql, !showResults));
        if (showResults) {
            next = next.withLowerTab(LowerTab.RESULTS);
        }
        effects.add(new Effect.ExecuteSql(id, c.spec(), sql));
        return new Transition(next, effects);
    }

    static Transition cancelRun(final Clock clock, final AppState s, final PanelId id) {
        final Optional<SqlRun> run = s.runOf(id);
        if (run.isPresent() && run.get().status().isActive()) {
            final AppState next = s.withRun(run.get().withStatus(RunStatus.CANCELLED));
            return new Transition(next, List.of(new Effect.CancelSql(run.get().requestId())));
        }
        return s.panel(id)
                .flatMap(p -> s.session(p.session()))
                .filter(SessionState::isBusy)
                .map(sess -> TurnRules.interruptSession(clock, s, sess.name()))
                .orElse(Transition.of(s));
    }

    // ---- SQL events

    static Transition sqlStarted(final Clock clock, final AppState s, final Intent.SqlStarted i) {
        return onRun(s, i.requestId(), r -> r.started(clock.millis()));
    }

    static Transition sqlRows(final AppState s, final Intent.SqlRows i) {
        return onRun(s, i.requestId(), r -> r.withProgress(i.rows(), i.execMillis(), i.fetchMillis(), i.totalMillis()));
    }

    static Transition sqlCompleted(final Clock clock, final AppState s, final Intent.SqlCompleted i) {
        return notifyLongRun(s, completed(clock, s, i), i.requestId(), i.totalMillis(), i.rows() + " rows");
    }

    static Transition sqlFailed(final Clock clock, final AppState s, final Intent.SqlFailed i) {
        return notifyLongRun(s, failed(clock, s, i), i.requestId(), i.totalMillis(), "failed: " + i.message());
    }

    static Transition sqlCancelled(final AppState s, final Intent.SqlCancelled i) {
        return onRun(s, i.requestId(), r -> r.withStatus(RunStatus.CANCELLED));
    }

    private static Transition onRun(final AppState s, final long requestId, final UnaryOperator<SqlRun> change) {
        final Optional<SqlRun> run = s.run(requestId);
        if (run.isEmpty() || !run.get().status().isActive()) {
            return Transition.of(s); // superseded, cancelled or already finished: drop
        }
        return Transition.of(s.withRun(change.apply(run.get())));
    }

    /** Adds a schema read after a connection opened (for completion) or on request (to show it). */
    private static Transition withSchemaLoad(final Transition t, final String connection, final boolean show) {
        return t.state().connection(connection).map(c -> {
            final List<Effect> effects = new ArrayList<>(t.effects());
            effects.add(new Effect.LoadSchema(c.spec(), show));
            return new Transition(t.state(), effects);
        }).orElse(t);
    }

    /** A query that ran {@link Shared#LONG_RUN_MILLIS} or more ends with a desktop notification naming its panel. */
    private static Transition notifyLongRun(final AppState before, final Transition t, final long requestId, final long totalMillis, final String outcome) {
        // a live plot's refresh is a run nobody typed: no desktop notification for it
        if (totalMillis < LONG_RUN_MILLIS || t.state() == before || before.run(requestId).map(SqlRun::background).orElse(false)) {
            return t;
        }
        final String panel = before.run(requestId).flatMap(r -> before.panel(r.panel())).map(PanelState::title).orElse("query");
        final List<Effect> effects = new ArrayList<>(t.effects());
        effects.add(new Effect.DesktopNotify(panel + ": query finished", outcome + " in " + totalMillis / 1000 + " s"));
        return new Transition(t.state(), effects);
    }

    /**
     * A failed run keeps the database's error. In a block of several statements the driver stops at the failing one
     * and the ones before it may have committed, so the error says so, and a block holding a schema change reloads
     * the schema. A live plot's failed refresh is a warning, so a broken plot never raises a desktop notification
     * each period.
     */
    private static Transition failed(final Clock clock, final AppState s, final Intent.SqlFailed i) {
        final Optional<SqlRun> run = s.run(i.requestId());
        if (run.isEmpty() || !run.get().status().isActive()) {
            return Transition.of(s);
        }
        final SqlRun r = run.get();
        final List<String> statements = Statements.split(r.sql());
        final String message = statements.size() > 1 ? i.message() + "\n" + blockNote(statements.size()) : i.message();
        final String subject = Notice.panel(r.panel().value());
        AppState next = s.withRun(r.failed(message, i.totalMillis()));
        if (r.background()) {
            final String title = s.panel(r.panel()).map(PanelState::title).orElse("a closed panel");
            next = notice(clock, next, Notice.Level.WARN, "Live plot in " + title + " failed: " + i.message(), subject);
        } else {
            next = notice(clock, next, Notice.Level.ERROR, statements.size() > 1 ? i.message() + " — " + blockNote(statements.size()) : i.message(),
                    subject);
        }
        final Transition t = Transition.of(next);
        return statements.size() > 1 && statements.stream().anyMatch(Classifier::isDdl)
                ? withSchemaLoad(t, r.connection(), explorerShows(next, r.connection())) : t;
    }

    static String blockNote(final int statements) {
        return "The block holds " + statements + " statements; the ones before the failing statement may have run.";
    }

    /** True when the explorer shows the connection's schema, so a reload after a schema change shows it again. */
    private static boolean explorerShows(final AppState s, final String connection) {
        final String title = Effect.LoadSchema.viewTitle(connection);
        return s.views().stream().anyMatch(v -> !v.pinned() && v.session().isEmpty() && v.placement() == Placement.SIDE && v.title().equals(title));
    }

    /**
     * A finished statement clears its panel's earlier error; a block holding a statement that changes the schema
     * reloads the schema behind completion, and the explorer when it shows that connection. A live plot's refresh
     * finishes without a word.
     */
    private static Transition completed(final Clock clock, final AppState s, final Intent.SqlCompleted i) {
        final Optional<SqlRun> run = s.run(i.requestId());
        final Transition t = onRun(s, i.requestId(),
                r -> r.completed(i.rows(), i.updateCount(), i.truncated(), i.execMillis(), i.fetchMillis(), i.totalMillis()));
        if (run.isEmpty() || t.state() == s) {
            return t;
        }
        final String subject = Notice.panel(run.get().panel().value());
        AppState next = clear(t.state(), subject);
        final String title = s.panel(run.get().panel()).map(PanelState::title).orElse("a closed panel");
        if (i.truncated()) {
            next = notice(clock, next, Notice.Level.WARN, "Showing the first " + String.format(Locale.ROOT, "%,d", i.rows()) + " rows in " + title
                    + "; the query returned more (add a LIMIT, or export from the grid for what is shown)", subject);
        } else if (!run.get().background() && !run.get().panel().equals(s.focused())) {
            next = notice(clock, next, Notice.Level.INFO, "Query finished in " + title, subject);
        }
        if (Classifier.anyDdl(run.get().sql())) {
            return withSchemaLoad(new Transition(next, t.effects()), run.get().connection(), explorerShows(next, run.get().connection()));
        }
        return new Transition(next, t.effects());
    }

    /** Statements after the first one: the run keeps the note for its status line; a failure is also a notice. */
    static Transition sqlWarning(final Clock clock, final AppState s, final Intent.SqlWarning i) {
        final Optional<SqlRun> run = s.run(i.requestId());
        if (run.isEmpty()) {
            return Transition.of(s);
        }
        final AppState next = s.withRun(run.get().withNote(i.text(), i.failed()));
        return Transition.of(notice(clock, next, i.failed() ? Notice.Level.ERROR : Notice.Level.WARN, i.text(), Notice.panel(run.get().panel().value())));
    }

    // ---- connections

    static Transition saveConnection(final Clock clock, final AppState s, final Intent.SaveConnection i) {
        final ConnectionSpec spec = i.spec();
        final boolean isNew = i.previousName().isEmpty();
        if (isNew) {
            if (s.connection(spec.name()).isPresent()) {
                return Transition.of(notice(clock, s, Notice.Level.ERROR, "A connection called " + spec.name() + " exists"));
            }
            final List<ConnectionState> conns = new ArrayList<>(s.connections());
            conns.add(ConnectionState.closed(spec));
            final AppState next = s.withConnections(conns);
            return new Transition(next, List.of(new Effect.SaveConnections(specs(next))));
        }
        final Optional<ConnectionState> previous = s.connection(i.previousName());
        if (previous.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No connection called " + i.previousName()));
        }
        final boolean renamed = !i.previousName().equals(spec.name());
        if (renamed && s.connection(spec.name()).isPresent()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "A connection called " + spec.name() + " exists"));
        }
        final List<Effect> effects = new ArrayList<>();
        final ConnectionState old = previous.get();
        ConnectionState updated = new ConnectionState(spec, old.status(), old.error());
        if (old.status().isUsable() && !old.spec().equals(spec)) {
            effects.add(new Effect.CloseDb(old.name()));
            updated = ConnectionState.closed(spec);
        }
        if (!old.spec().equals(spec)) {
            effects.add(new Effect.ForgetSchema(old.name()));
        }
        final List<ConnectionState> conns = new ArrayList<>(s.connections().size());
        for (final ConnectionState c : s.connections()) {
            conns.add(c.name().equals(i.previousName()) ? updated : c);
        }
        AppState next = s.withConnections(conns);
        effects.add(new Effect.SaveConnections(specs(next)));
        if (renamed) {
            next = rebindConnection(next, i.previousName(), spec.name());
            effects.add(savePanels(next));
        }
        return new Transition(next, effects);
    }

    static Transition removeConnection(final AppState s, final String name) {
        final Optional<ConnectionState> conn = s.connection(name);
        if (conn.isEmpty()) {
            return Transition.of(s);
        }
        final List<Effect> effects = new ArrayList<>();
        if (conn.get().status() != Status.CLOSED) {
            effects.add(new Effect.CloseDb(name));
        }
        effects.add(new Effect.ForgetSchema(name));
        AppState next = clear(s, Notice.connection(name)).withConnections(s.connections().stream().filter(c -> !c.name().equals(name)).toList());
        next = rebindConnection(next, name, "");
        effects.add(new Effect.SaveConnections(specs(next)));
        effects.add(savePanels(next));
        return new Transition(next, effects);
    }

    static Transition openConnection(final AppState s, final String name) {
        return s.connection(name)
                .filter(c -> !c.status().isUsable())
                .map(c -> new Transition(s.withConnection(c.with(Status.OPENING, "")), List.of(new Effect.OpenDb(c.spec()))))
                .orElse(Transition.of(s));
    }

    static Transition closeConnection(final AppState s, final String name) {
        final Optional<ConnectionState> conn = s.connection(name);
        if (conn.isEmpty() || conn.get().status() == Status.CLOSED) {
            return Transition.of(s);
        }
        final List<Effect> effects = new ArrayList<>();
        AppState next = s;
        for (final SqlRun r : s.runs()) {
            if (r.connection().equals(name) && r.status().isActive()) {
                effects.add(new Effect.CancelSql(r.requestId()));
                next = next.withRun(r.withStatus(RunStatus.CANCELLED));
            }
        }
        effects.add(new Effect.CloseDb(name));
        next = next.withConnection(conn.get().with(Status.CLOSING, ""));
        return new Transition(next, effects);
    }

    static Transition connectionStatus(final AppState s, final String name, final Status status, final String error) {
        return Transition.of(s.connection(name).map(c -> s.withConnection(c.with(status, error))).orElse(s));
    }

    static Transition connectionLost(final Clock clock, final AppState s, final Intent.ConnectionLost i) {
        if (s.connection(i.name()).isEmpty()) {
            return Transition.of(s);
        }
        final AppState next = notice(clock, connectionStatus(s, i.name(), Status.FAILED, i.error()).state(), Notice.Level.WARN,
                "Connection " + i.name() + " lost: " + i.error(), Notice.connection(i.name()));
        return new Transition(next, List.of(new Effect.DesktopNotify("Connection lost", i.name() + ": " + i.error())));
    }

    private static AppState rebindConnection(final AppState s, final String from, final String to) {
        final List<PanelState> panels = new ArrayList<>(s.panels().size());
        for (final PanelState p : s.panels()) {
            panels.add(p.connection().equals(from) ? p.withConnection(to) : p);
        }
        // closed panels keep their bindings, so they follow renames and removals too
        final List<PanelState> closed = s.closed().stream().map(p -> p.connection().equals(from) ? p.withConnection(to) : p).toList();
        return s.withPanels(panels, s.focused()).withClosed(closed);
    }

    private static List<ConnectionSpec> specs(final AppState s) {
        return s.connections().stream().map(ConnectionState::spec).toList();
    }

    static Transition testConnection(final Clock clock, final AppState s, final Intent.TestConnection i) {
        return new Transition(notice(clock, s, Notice.Level.INFO, "Testing " + i.spec().label(), Notice.connection(i.spec().name())),
                List.of(new Effect.TestDb(i.spec())));
    }

    static Transition connectionOpened(final AppState s, final Intent.ConnectionOpened i) {
        // an open done after the user's Close is stale: the queued disconnect closes it; no schema load reopens it
        return s.connection(i.name()).map(c -> c.status() == Status.CLOSING).orElse(false)
                ? Transition.of(s)
                : withSchemaLoad(Transition.of(clear(connectionStatus(s, i.name(), Status.OPEN, "").state(), Notice.connection(i.name()))),
                i.name(), false);
    }

    static Transition exploreSchema(final Clock clock, final AppState s, final Intent.ExploreSchema i) {
        return s.connection(i.connection()).isEmpty()
                ? Transition.of(notice(clock, s, Notice.Level.WARN, "No connection " + i.connection(), Notice.connection(i.connection())))
                : withSchemaLoad(Transition.of(s), i.connection(), true);
    }

    static Transition connectionFailed(final Clock clock, final AppState s, final Intent.ConnectionFailed i) {
        return Transition.of(notice(clock, connectionStatus(s, i.name(), Status.FAILED, i.error()).state(),
                Notice.Level.ERROR, "Connection " + i.name() + " failed: " + i.error(), Notice.connection(i.name())));
    }

    static Transition schemaChanged(final AppState s, final Intent.SchemaChanged i) {
        return withSchemaLoad(Transition.of(s), i.connection(), explorerShows(s, i.connection()));
    }

    static Transition connectionTested(final Clock clock, final AppState s, final Intent.ConnectionTested i) {
        return Transition.of(notice(clock, s, i.ok() ? Notice.Level.INFO : Notice.Level.ERROR,
                "Test " + i.name() + ": " + i.message(), Notice.connection(i.name())));
    }
}
