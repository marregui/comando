package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.dispatch.Classifier;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.marregui.comando.testkit.States.P1;
import static dev.marregui.comando.testkit.States.P2;
import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] maintains notices, runs, sessions, panels, and layout while translating intents into state transitions and
/// effects.
///
/// - error notices remain visible until their subject settles
/// - completed schema changes reload the affected connection
/// - session layout changes respect the currently focused panel
/// - removed resources clear notices bound to them
class ReducerNoticeAndLayoutTest {

    private final FakeClock clock = new FakeClock();
    private final Reducer reducer = new Reducer(clock);
    private AppState state = States.twoPanels();
    private List<Effect> effects = List.of();

    private void apply(Intent intent) {
        Transition t = reducer.apply(state, intent);
        state = t.state();
        effects = t.effects();
    }

    // ---- notices

    /** A second session, "other", whose own panel is p2: set as stored, as the reducer keeps it. */
    private void otherOnP2() {
        state = state.withSessions(List.of(state.session("work").orElseThrow(), SessionState.closed(SessionSpec.fresh("other", "/tmp"))))
                .withPanel(state.panel(P2).orElseThrow().withSession("other"));
    }

    /// An error for panel `p1` stays standing across unrelated information, while both notices remain in chronological
    /// history.
    @Test
    void anErrorStandsOverALaterInfoAboutSomethingElseAndTheHistoryKeepsBoth() {
        apply(new Intent.Notify(Notice.Level.ERROR, "boom", false, Notice.panel("p1")));
        apply(new Intent.Notify(Notice.Level.INFO, "QuestDB is up", false));

        assertThat(state.notice().text()).isEqualTo("boom");
        assertThat(state.notices()).extracting(Notice::text).containsExactly("boom", "QuestDB is up");
    }

    /// An update for the same connection replaces its standing error, and a newer error replaces an earlier global
    /// error.
    @Test
    void aNoticeAboutTheSameSubjectOrANewerErrorReplacesAStandingError() {
        apply(new Intent.Notify(Notice.Level.ERROR, "first", false, Notice.connection("local")));
        apply(new Intent.Notify(Notice.Level.INFO, "Testing local", false, Notice.connection("local")));
        assertThat(state.notice().text()).isEqualTo("Testing local");

        apply(new Intent.Notify(Notice.Level.ERROR, "one", false));
        apply(new Intent.Notify(Notice.Level.ERROR, "two", false));
        assertThat(state.notice().text()).isEqualTo("two");
    }

    /// More than `NOTICE_HISTORY` information notices discard the seven oldest entries while retaining the newest
    /// fifty.
    @Test
    void theHistoryKeepsTheLastFiftyNotices() {
        for (int i = 0; i < AppState.NOTICE_HISTORY + 7; i++) {
            apply(new Intent.Notify(Notice.Level.INFO, "n" + i, false));
        }
        assertThat(state.notices()).hasSize(AppState.NOTICE_HISTORY);
        assertThat(state.notices().getFirst().text()).isEqualTo("n7");
        assertThat(state.notices().getLast().text()).isEqualTo("n56");
    }

    /// Starting a replacement SQL run clears its panel error, and explicit dismissal clears even an unrelated error
    /// notice.
    @Test
    void aSuccessfulRunClearsItsPanelsErrorAndDismissClearsAnyNotice() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.SqlFailed(1L, "syntax error", 3));
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
        assertThat(state.notice().subject()).isEqualTo(Notice.panel("p1"));

        apply(new Intent.RunText(P1, "SELECT 2", Target.AUTO));
        assertThat(state.notice()).isEqualTo(Notice.NONE);

        apply(new Intent.Notify(Notice.Level.ERROR, "other", false));
        apply(new Intent.DismissNotice());
        assertThat(state.notice()).isEqualTo(Notice.NONE);
    }

    /// A new error notice emits one desktop notification, whereas a following informational notice emits none.
    @Test
    void aNewErrorNoticeGoesToTheDesktopOnce() {
        apply(new Intent.Notify(Notice.Level.ERROR, "lost", false));
        assertThat(effects).containsExactly(new Effect.DesktopNotify("comando", "lost"));

        apply(new Intent.Notify(Notice.Level.INFO, "fine", false));
        assertThat(effects).isEmpty();
    }

    /// Answering approval `7` removes the approval-bound standing notice.
    @Test
    void anAnsweredApprovalClearsItsRequestNotice() {
        Approval a = new Approval(7L, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L);
        apply(new Intent.ApprovalRequested(a));
        assertThat(state.notice().subject()).isEqualTo(Notice.approval(7L));

        apply(new Intent.ApprovalAnswered(7L, new ApprovalDecision.Allow(ApprovalDecision.Scope.ONCE)));
        assertThat(state.notice()).isEqualTo(Notice.NONE);
    }

    /// Starting a second SQL block while the first is active records a warning that the earlier statement stopped.
    @Test
    void reRunningWhileAStatementRunsSaysTheOldOneStopped() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.RunText(P1, "SELECT 2", Target.AUTO));

        assertThat(state.notice().level()).isEqualTo(Notice.Level.WARN);
        assertThat(state.notice().text()).contains("Stopped the statement");
    }

    // ---- runs

    /// `SqlStarted` stores the reducer clock's current time on the matching run.
    @Test
    void aStartedStatementCarriesItsStartTime() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        clock.advance(50);
        apply(new Intent.SqlStarted(1L));

        assertThat(state.runOf(P1).orElseThrow().startedMillis()).isEqualTo(clock.millis());
    }

    /// Completing `CREATE TABLE` emits a schema load, matching DDL detection but excluding identifiers and SELECT text.
    @Test
    void aSchemaChangeReloadsTheSchemaWhenItCompletes() {
        apply(new Intent.RunText(P1, "create table t (x int)", Target.SQL));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.SqlCompleted(1L, 0, 0L, false, 1, 0, 1));

        assertThat(effects).contains(new Effect.LoadSchema(States.LOCAL, false));
        assertThat(Classifier.isDdl("  DROP TABLE t")).isTrue();
        assertThat(Classifier.isDdl("created_at")).isFalse();
        assertThat(Classifier.isDdl("SELECT 1")).isFalse();
    }

    /// A warning for a later failed statement preserves the completed run and records its note as an error.
    @Test
    void aLaterStatementsFailureStaysWithTheRunAfterItCompleted() {
        apply(new Intent.RunText(P1, "SELECT 1; SELECT x", Target.SQL));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.SqlCompleted(1L, 1, -1L, false, 1, 0, 1));
        apply(new Intent.SqlWarning(1L, true, "A later statement in the block failed: no column x"));

        assertThat(state.runOf(P1).orElseThrow().note()).contains("no column x");
        assertThat(state.runOf(P1).orElseThrow().noteFailed()).isTrue();
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
    }

    /// An automatic target containing only line comments creates no run or effect and explains that nothing is
    /// runnable.
    @Test
    void aBlockOfCommentsOnlyRunsNothing() {
        apply(new Intent.RunText(P1, "-- a note\n-- another", Target.AUTO));

        assertThat(effects).isEmpty();
        assertThat(state.notice().text()).startsWith("Nothing to run");
    }

    // ---- sessions

    /// An interrupted session turn ends with an informational outcome and no desktop failure notification.
    @Test
    void anInterruptedTurnIsAnOutcomeNotAFailure() {
        apply(new Intent.RunText(P1, "explain", Target.CLAUDE));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.InterruptSession("work"));
        assertThat(state.session("work").orElseThrow().activity()).isEqualTo("interrupting");

        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 10L, 100L, true, "interrupted"));

        assertThat(state.notice().level()).isEqualTo(Notice.Level.INFO);
        assertThat(state.notice().text()).isEqualTo("work: interrupted");
        assertThat(effects).noneMatch(e -> e instanceof Effect.DesktopNotify);
    }

    /// A busy session keeps the first turn-start time across queued prompts and resets it when the turn ends.
    @Test
    void aTurnClockRunsWhileTheSessionIsBusy() {
        clock.advance(1000);
        apply(new Intent.RunText(P1, "explain", Target.CLAUDE));
        long started = state.session("work").orElseThrow().turnStartedMillis();
        assertThat(started).isEqualTo(clock.millis());

        clock.advance(500);
        apply(new Intent.RunText(P1, "and more", Target.CLAUDE));
        assertThat(state.session("work").orElseThrow().turnStartedMillis()).isEqualTo(started);

        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 10L, 100L, false, ""));
        assertThat(state.session("work").orElseThrow().turnStartedMillis()).isZero();
    }

    /// Importing `old` opens a newly owned panel without taking `p2`, focuses it, and announces the import.
    @Test
    void importingOneConversationOpensItInItsOwnPanel() {
        apply(new Intent.ImportSessions(List.of(SessionSpec.imported("old", "/tmp", "abc"))));

        assertThat(state.panel(P2).orElseThrow().session()).as("no other panel is taken").isEmpty();
        PanelState own = state.homePanel("old").orElseThrow();
        assertThat(own.title()).isEqualTo("old");
        assertThat(state.focused()).isEqualTo(own.id());
        assertThat(state.notice().text()).isEqualTo("Imported old and opened it in its own panel");
    }

    /// An attributed two-line editor write emits `EditPanelText` and announces its source, while an unattributed write
    /// leaves that notice intact.
    @Test
    void aSessionsWriteIntoTheEditorIsAnnounced() {
        apply(new Intent.EditPanel(P1, "SELECT 1\nFROM t", EditMode.APPEND, "work"));

        assertThat(effects).containsExactly(new Effect.EditPanelText(P1, "SELECT 1\nFROM t", EditMode.APPEND));
        assertThat(state.notice().text()).isEqualTo("work added 2 lines to one");

        apply(new Intent.EditPanel(P1, "x", EditMode.APPEND));
        assertThat(state.notice().text()).isEqualTo("work added 2 lines to one");
    }

    // ---- panels

    /// Reopening a closed panel inserts it after the focused panel, preserves its title, and drops a connection removed
    /// while it was closed.
    @Test
    void aClosedPanelReopensAfterTheFocusedOneWithTheBindingsThatStillExist() {
        PanelId back = new PanelId("p3");
        apply(new Intent.AddPanel(back, "old"));
        apply(new Intent.FocusPanel(P1));
        apply(new Intent.ClosePanel(back));
        apply(new Intent.RemoveConnection("local"));
        apply(new Intent.ReopenPanel(back));

        assertThat(state.focused()).isEqualTo(back);
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P1, back, P2);
        PanelState p = state.panel(back).orElseThrow();
        assertThat(p.connection()).as("removed while it was closed").isEmpty();
        assertThat(p.title()).isEqualTo("old");
        assertThat(effects).hasSize(1);
    }

    // ---- what a session may change

    /// A lower view from an unfocused session is added without selecting its tab, whereas the focused session's view
    /// becomes the lower tab.
    @Test
    void aViewFromASessionTheUserIsNotLookingAtDoesNotTakeTheLowerPanel() {
        state = state.withSessions(List.of(state.session("work").orElseThrow(), SessionState.closed(SessionSpec.fresh("other", "/tmp"))));
        apply(new Intent.ShowView("other", "plot", new ViewSpec.Markdown("x"), Placement.LOWER));

        assertThat(state.lowerTab()).as("the tab it had: the first, Session").isEqualTo(LowerTab.SESSION);
        assertThat(state.views()).hasSize(1);
        assertThat(state.notice().text()).isEqualTo("other opened plot");

        apply(new Intent.ShowView("work", "mine", new ViewSpec.Markdown("y"), Placement.LOWER));
        assertThat(state.lowerTab().kind()).isEqualTo(LowerTab.Kind.VIEW);
    }

    /// A session layout request for another panel defers the requested lower tab to an arrange effect instead of
    /// changing the current tab.
    @Test
    void aTabAskedWithAFocusWaitsForTheFocus() {
        otherOnP2();
        LowerTab before = state.lowerTab();

        apply(new Intent.SessionLayout("other", P2, LowerTab.TERMINAL, null));

        assertThat(state.lowerTab()).as("the user still looks at another session's panel").isEqualTo(before);
        assertThat(effects).containsExactly(new Effect.Arrange(
                new Layout(Layout.Toggle.KEEP, Layout.Toggle.KEEP, Double.NaN, P2, LowerTab.TERMINAL, "other")));
    }

    /// A delayed focus request is ignored after its panel is rebound to another session, then selects the panel and
    /// terminal tab when ownership matches.
    @Test
    void aFocusRequestAppliedAfterThePanelWasReboundChangesNothing() {
        otherOnP2();
        apply(new Intent.SessionLayout("other", P2, LowerTab.TERMINAL, null));
        LowerTab before = state.lowerTab();

        state = state.withPanel(state.panel(P2).orElseThrow().withSession("work")); // the panel was rebound meanwhile
        apply(new Intent.SessionFocus("other", P2, LowerTab.TERMINAL));

        assertThat(state.focused()).isEqualTo(P1);
        assertThat(state.lowerTab()).isEqualTo(before);

        state = state.withPanel(state.panel(P2).orElseThrow().withSession("other"));
        apply(new Intent.SessionFocus("other", P2, LowerTab.TERMINAL));
        assertThat(state.focused()).isEqualTo(P2);
        assertThat(state.lowerTab()).isEqualTo(LowerTab.TERMINAL);
    }

    /// Session layout affects only its own focused panel: focus requests wait for shell timing, tab requests wait for
    /// focus, and pane changes still arrange.
    @Test
    void aSessionFocusesOnlyItsOwnPanelAndSelectsTabsOnlyWhileTheUserLooksAtIt() {
        otherOnP2();

        apply(new Intent.SessionLayout("work", P2, LowerTab.SESSION, Layout.KEEP));
        assertThat(state.focused()).isEqualTo(P1);
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);

        // the reducer does not focus the panel: the shell does, once the user pauses typing
        apply(new Intent.SessionLayout("other", P2, null, null));
        assertThat(state.focused()).isEqualTo(P1);
        assertThat(effects).containsExactly(new Effect.Arrange(new Layout(Layout.Toggle.KEEP, Layout.Toggle.KEEP, Double.NaN, P2, null, "other")));
        assertThat(state.notice().text()).isEqualTo("other will focus two once you pause typing");
        apply(new Intent.FocusPanel(P2));

        apply(new Intent.SessionLayout("work", null, LowerTab.TERMINAL, null));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);

        apply(new Intent.SessionLayout("other", null, null, new Layout(Layout.Toggle.HIDE, Layout.Toggle.KEEP, Double.NaN)));
        assertThat(effects).containsExactly(new Effect.Arrange(new Layout(Layout.Toggle.HIDE, Layout.Toggle.KEEP, Double.NaN)));
        assertThat(state.notice().text()).isEqualTo("other arranged the panes (Restore layout in the palette undoes it)");
    }

    /// A session focus request remains pending for the shell and combines its panel focus and pane toggles in one
    /// arrange effect.
    @Test
    void aSessionsFocusRequestWaitsForTheShellAndItsPanesComeInTheSameArrange() {
        apply(new Intent.FocusPanel(P2));
        Layout hide = new Layout(Layout.Toggle.HIDE, Layout.Toggle.KEEP, Double.NaN);

        apply(new Intent.SessionLayout("work", P1, null, hide));

        assertThat(state.focused()).isEqualTo(P2);
        assertThat(effects).containsExactly(new Effect.Arrange(new Layout(Layout.Toggle.HIDE, Layout.Toggle.KEEP, Double.NaN, P1, null, "work")));
        assertThat(state.notice().text()).isEqualTo("work will focus one once you pause typing, arranged the panes (Restore layout in the palette undoes it)");
    }

    // ---- one error per failed start, background runs, subjects cleared by removal and withdrawal

    /// A failed session start creates one desktop error; its later synthetic turn end neither replaces the error nor
    /// creates another notice.
    @Test
    void aPromptToASessionWhoseStartFailedRaisesOneErrorOnly() {
        apply(new Intent.RunText(P1, "explain", Target.CLAUDE));
        assertThat(effects).contains(new Effect.StartClaude(States.WORK));
        apply(new Intent.SessionExited("work", -1, "cannot start claude: not found; install Claude Code, or set COMANDO_CLAUDE to its path"));
        Notice failed = state.notice();
        assertThat(failed.text()).startsWith("Session work could not start").contains("COMANDO_CLAUDE");
        assertThat(effects).containsExactly(new Effect.DesktopNotify("comando", failed.text()));

        apply(new Intent.SessionTurnEnded("work", 0, -1.0, -1L, 0L, true, "session could not start"));

        assertThat(state.notice()).isEqualTo(failed);
        assertThat(effects).isEmpty();
        assertThat(state.notices()).extracting(Notice::text).doesNotContain("work: session could not start");
        assertThat(state.session("work").orElseThrow().pendingTurns()).isZero();
    }

    /// A completed background plot refresh leaves the existing approval notice standing and adds no second
    /// query-completed history notice.
    @Test
    void aLivePlotsRefreshFinishesWithoutANotice() {
        apply(new Intent.RunText(P2, "SELECT 1", Target.SQL));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.SqlCompleted(1L, 1, -1L, false, 1, 0, 1));
        apply(new Intent.ApprovalRequested(new Approval(7L, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)));
        Notice asks = state.notice();

        apply(new Intent.RefreshSql(P2, "SELECT 1"));
        apply(new Intent.SqlStarted(2L));
        apply(new Intent.SqlCompleted(2L, 1, -1L, false, 1, 0, 1));

        assertThat(state.notice()).isEqualTo(asks);
        assertThat(state.notices()).extracting(Notice::text).filteredOn(t -> t.startsWith("Query finished")).hasSize(1);
    }

    /// Repeated live-plot failures are panel warnings without desktop alerts, unlike an otherwise identical foreground
    /// SQL failure.
    @Test
    void aLivePlotsFailedRefreshIsAWarningWithoutADesktopNotification() {
        apply(new Intent.RunText(P2, "SELECT * FROM trades", Target.SQL));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.SqlCompleted(1L, 1, -1L, false, 1, 0, 1));
        for (long id = 2; id <= 4; id++) {
            apply(new Intent.RefreshSql(P2, "SELECT * FROM trades"));
            apply(new Intent.SqlStarted(id));
            apply(new Intent.SqlFailed(id, "table does not exist [table=trades]", 20_000));

            assertThat(state.notice().level()).isEqualTo(Notice.Level.WARN);
            assertThat(state.notice().text()).isEqualTo("Live plot in two failed: table does not exist [table=trades]");
            assertThat(state.notice().subject()).isEqualTo(Notice.panel("p2"));
            assertThat(effects).noneMatch(e -> e instanceof Effect.DesktopNotify);
        }

        apply(new Intent.RunText(P2, "SELECT * FROM trades", Target.SQL));
        apply(new Intent.SqlStarted(5L));
        apply(new Intent.SqlFailed(5L, "table does not exist [table=trades]", 3));
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
        assertThat(effects).contains(new Effect.DesktopNotify("comando", "table does not exist [table=trades]"));
    }

    /// Reopening `local` clears the connection-lost notice attached to that connection.
    @Test
    void aReopenedConnectionClearsItsLostNotice() {
        apply(new Intent.ConnectionOpened("local"));
        apply(new Intent.ConnectionLost("local", "connection reset"));
        assertThat(state.notice().subject()).isEqualTo(Notice.connection("local"));

        apply(new Intent.ConnectionOpened("local"));

        assertThat(state.notice()).isEqualTo(Notice.NONE);
    }

    /// A session's exit withdraws all its waiting approvals, and a turn's end its waiting SQL card; each withdrawn
    /// card's notice is cleared.
    @Test
    void cardsWithdrawnInBulkClearTheirRequestNotice() {
        apply(new Intent.ApprovalRequested(new Approval(8L, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)));
        apply(new Intent.SessionStarted("work"));
        assertThat(state.notice().subject()).isEqualTo(Notice.approval(8L));

        apply(new Intent.StopSession("work"));
        apply(new Intent.SessionExited("work", 0, ""));

        assertThat(state.approvals()).isEmpty();
        assertThat(state.notice()).isEqualTo(Notice.NONE);

        apply(new Intent.RunText(P1, "go", Target.CLAUDE));
        apply(new Intent.ApprovalRequested(new Approval(9L, "work", ApprovalKind.SQL_WRITE, "db_run_sql", "{}", "Run on local: DROP", 2L)));
        assertThat(state.notice().subject()).isEqualTo(Notice.approval(9L));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 10L, 100L, false, ""));
        assertThat(state.approvals()).isEmpty();
        assertThat(state.notice()).isEqualTo(Notice.NONE);
    }

    /// Removing a failed connection clears its notice; removing a failed session replaces its session notice with the
    /// removal notice.
    @Test
    void removingAConnectionOrASessionClearsItsNotice() {
        apply(new Intent.ConnectionFailed("local", "Connection refused"));
        assertThat(state.notice().subject()).isEqualTo(Notice.connection("local"));
        apply(new Intent.RemoveConnection("local"));
        assertThat(state.notice()).isEqualTo(Notice.NONE);

        apply(new Intent.SessionExited("work", 1, "boom"));
        assertThat(state.notice().subject()).isEqualTo(Notice.session("work"));
        apply(new Intent.RemoveSession("work"));
        assertThat(state.notice().subject()).as("its notice gone; what was removed said").isEmpty();
        assertThat(state.notice().text()).isEqualTo("Removed session work and its panel");
    }

    /// Any DDL within a completed SQL block reloads its schema, with the explorer flag enabled only after that explorer
    /// is open.
    @Test
    void aSchemaChangeAnywhereInTheBlockReloadsTheSchemaAndAnOpenExplorer() {
        apply(new Intent.RunText(P1, "INSERT INTO a VALUES (1); CREATE TABLE b (x INT)", Target.SQL));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.SqlCompleted(1L, 0, 1L, false, 1, 0, 1));
        assertThat(effects).contains(new Effect.LoadSchema(States.LOCAL, false));

        apply(new Intent.ShowView("", Effect.LoadSchema.viewTitle("local"), new ViewSpec.Markdown("tree"), Placement.SIDE));
        apply(new Intent.RunText(P1, "-- new\nDROP TABLE b", Target.SQL));
        apply(new Intent.SqlStarted(3L));
        apply(new Intent.SqlCompleted(3L, 0, 0L, false, 1, 0, 1));
        assertThat(effects).contains(new Effect.LoadSchema(States.LOCAL, true));
    }

    /// A session schema-change signal reloads an existing connection quietly or refreshes its open explorer, and
    /// ignores an unknown connection.
    @Test
    void aSessionsSchemaChangeReloadsTheSchemaQuietlyOrShowsAnOpenExplorer() {
        apply(new Intent.SchemaChanged("local"));
        assertThat(effects).containsExactly(new Effect.LoadSchema(States.LOCAL, false));

        apply(new Intent.ShowView("", Effect.LoadSchema.viewTitle("local"), new ViewSpec.Markdown("tree"), Placement.SIDE));
        apply(new Intent.SchemaChanged("local"));
        assertThat(effects).containsExactly(new Effect.LoadSchema(States.LOCAL, true));

        apply(new Intent.SchemaChanged("nowhere"));
        assertThat(effects).isEmpty();
    }

    /// Failure after `CREATE TABLE` warns that preceding statements may have run and reloads schema; a failed single
    /// SELECT does neither.
    @Test
    void aFailedBlockSaysTheEarlierStatementsMayHaveRunAndReloadsAfterASchemaChange() {
        apply(new Intent.RunText(P1, "CREATE TABLE t1 (x INT); INSERT INTO nosuch VALUES (1);", Target.SQL));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.SqlFailed(1L, "table does not exist [table=nosuch]", 3));

        assertThat(state.runOf(P1).orElseThrow().error()).isEqualTo("table does not exist [table=nosuch]\n"
                + "The block holds 2 statements; the ones before the failing statement may have run.");
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
        assertThat(state.notice().text()).contains("The block holds 2 statements");
        assertThat(effects).contains(new Effect.LoadSchema(States.LOCAL, false));

        apply(new Intent.RunText(P1, "SELECT x FROM nosuch", Target.SQL));
        apply(new Intent.SqlStarted(2L));
        apply(new Intent.SqlFailed(2L, "table does not exist [table=nosuch]", 3));
        assertThat(state.runOf(P1).orElseThrow().error()).isEqualTo("table does not exist [table=nosuch]");
        assertThat(effects).noneMatch(e -> e instanceof Effect.LoadSchema);
    }

    /// A comment-only block forced to SQL creates no run or effects and returns the explicit nothing-to-run notice.
    @Test
    void aBlockOfCommentsForcedToSqlRunsNothing() {
        apply(new Intent.RunText(P1, "-- only a comment", Target.SQL));

        assertThat(effects).isEmpty();
        assertThat(state.runOf(P1)).isEmpty();
        assertThat(state.notice().text()).isEqualTo("Nothing to run: the block holds only comments");
    }
}
