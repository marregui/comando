package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.marregui.comando.testkit.States.P1;
import static dev.marregui.comando.testkit.States.P2;
import static org.assertj.core.api.Assertions.assertThat;

class ReducerTest {

    private final FakeClock clock = new FakeClock();
    private final Reducer reducer = new Reducer(clock);
    private AppState state = States.twoPanels();
    private List<Effect> effects = List.of();

    private void apply(Intent intent) {
        Transition t = reducer.apply(state, intent);
        state = t.state();
        effects = t.effects();
    }

    private SqlRun run(PanelId panel) {
        return state.runOf(panel).orElseThrow();
    }

    // ---- SQL runs

    @Test
    void sqlOnClosedConnectionOpensItThenExecutesWithAFreshId() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));

        assertThat(effects).containsExactly(new Effect.OpenDb(States.LOCAL), new Effect.ExecuteSql(1L, States.LOCAL, "SELECT 1"));
        assertThat(run(P1).status()).isEqualTo(RunStatus.QUEUED);
        assertThat(state.connection("local").orElseThrow().status()).isEqualTo(Status.OPENING);
        assertThat(state.lowerTab()).isEqualTo(LowerTab.RESULTS);
        assertThat(state.nextId()).isEqualTo(2L);
    }

    @Test
    void rerunCancelsAndReleasesThePreviousRunAndDropsItsLateEvents() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.RunText(P1, "SELECT 2", Target.AUTO));

        assertThat(effects).containsExactly(new Effect.CancelSql(1L), new Effect.ReleaseResult(1L),
                new Effect.ExecuteSql(2L, States.LOCAL, "SELECT 2"));
        assertThat(run(P1).requestId()).isEqualTo(2L);

        // the first run's events arrive after it was superseded: dropped
        apply(new Intent.SqlCompleted(1L, 10, -1L, false, 1, 1, 2));
        apply(new Intent.SqlFailed(1L, "canceling statement due to user request", 3));
        assertThat(run(P1).requestId()).isEqualTo(2L);
        assertThat(run(P1).status()).isEqualTo(RunStatus.QUEUED);
        assertThat(state.notice()).isEqualTo(Notice.NONE);
    }

    @Test
    void runsOfTwoPanelsAreIndependent() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        apply(new Intent.RunText(P2, "SELECT 2", Target.AUTO));
        apply(new Intent.SqlCompleted(1L, 1, -1L, false, 1, 1, 2));

        assertThat(run(P1).status()).isEqualTo(RunStatus.DONE);
        assertThat(run(P2).status()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void cancelMarksTheRunCancelledAndIgnoresALateCompletion() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.CancelRun(P1));

        assertThat(effects).containsExactly(new Effect.CancelSql(1L));
        assertThat(run(P1).status()).isEqualTo(RunStatus.CANCELLED);

        apply(new Intent.SqlCompleted(1L, 5, -1L, false, 1, 1, 2));
        assertThat(run(P1).status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void progressThenCompletion() {
        apply(new Intent.RunText(P1, "SELECT x FROM long_sequence(10)", Target.AUTO));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.SqlRows(1L, 100, 5, 7, 12));
        assertThat(run(P1).rowCount()).isEqualTo(100);
        apply(new Intent.SqlCompleted(1L, 250, -1L, true, 5, 9, 14));
        assertThat(run(P1)).extracting(SqlRun::status, SqlRun::rowCount, SqlRun::truncated)
                .containsExactly(RunStatus.DONE, 250, true);
    }

    @Test
    void aLongQueryNotifiesTheDesktopWhenItEnds() {
        apply(new Intent.RunText(P1, "SELECT x FROM long_sequence(10)", Target.AUTO));
        apply(new Intent.SqlStarted(1L));
        apply(new Intent.SqlCompleted(1L, 10, -1L, false, 9_000, 3_000, 12_000));
        assertThat(effects).containsExactly(new Effect.DesktopNotify("one: query finished", "10 rows in 12 s"));

        apply(new Intent.RunText(P2, "SELECT 1", Target.AUTO));
        apply(new Intent.SqlStarted(2L));
        apply(new Intent.SqlCompleted(2L, 1, -1L, false, 1, 1, 2));
        assertThat(effects).isEmpty();
    }

    @Test
    void failureIsShownAsAnErrorNotice() {
        apply(new Intent.RunText(P1, "SELECT nope FROM t", Target.AUTO));
        apply(new Intent.SqlFailed(1L, "table does not exist [table=t]", 4));
        assertThat(run(P1).status()).isEqualTo(RunStatus.FAILED);
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
        assertThat(state.notice().text()).contains("table does not exist");
    }

    @Test
    void sqlWithoutAConnectionIsRefused() {
        apply(new Intent.BindConnection(P1, ""));
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        assertThat(effects).isEmpty();
        assertThat(state.notice().text()).contains("has no connection");
    }

    @Test
    void forcedTargetOverridesTheClassifier() {
        apply(new Intent.RunText(P1, "trades WHERE price > 1", Target.SQL));
        assertThat(effects).contains(new Effect.ExecuteSql(1L, States.LOCAL, "trades WHERE price > 1"));
    }

    @Test
    void blankTextRunsNothing() {
        apply(new Intent.RunText(P1, "   \n ", Target.AUTO));
        assertThat(effects).isEmpty();
        assertThat(state.notice().level()).isEqualTo(Notice.Level.WARN);
    }

    // ---- prompts and sessions

    @Test
    void promptOnClosedSessionStartsItThenSends() {
        apply(new Intent.RunText(P1, "why is it slow", Target.AUTO));

        assertThat(effects).containsExactly(new Effect.StartClaude(States.WORK), new Effect.SendToClaude("work", "why is it slow", "one"));
        assertThat(state.session("work").orElseThrow()).satisfies(s -> {
            assertThat(s.status()).isEqualTo(Status.OPENING);
            assertThat(s.pendingTurns()).isEqualTo(1);
            assertThat(s.isBusy()).isTrue();
        });
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);
    }

    @Test
    void twoPanelsSharingASessionFeedOneQueue() {
        apply(new Intent.RunText(P1, "first", Target.CLAUDE));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.RunText(P2, "second", Target.CLAUDE));
        assertThat(effects).containsExactly(new Effect.SendToClaude("work", "second", "two"));
        assertThat(state.session("work").orElseThrow().pendingTurns()).isEqualTo(2);

        // Claude Code folds a prompt sent mid-turn into the running turn and reports what stays queued
        apply(new Intent.SessionTurnEnded("work", 0, 0.12, 40_000L, 200_000L, false, ""));
        assertThat(state.session("work").orElseThrow().isBusy()).isFalse();
        assertThat(state.session("work").orElseThrow().contextWindow()).isEqualTo(200_000L);
        assertThat(effects).containsExactly(new Effect.DesktopNotify("work", "Turn finished"));
    }

    @Test
    void initRecordsTheConversationIdSoTheNextStartResumes() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionInit("work", new SessionInfo("abc-123", "claude-haiku-5-5", "/tmp", "2.1.296", "default",
                List.of("interrupt_receipt_v1"))));

        SessionSpec spec = state.session("work").orElseThrow().spec();
        assertThat(spec.claudeSessionId()).isEqualTo("abc-123");
        assertThat(spec.startMode()).isEqualTo(StartMode.RESUME);
        assertThat(state.session("work").orElseThrow().status()).isEqualTo(Status.OPEN);
        assertThat(effects).singleElement().isInstanceOf(Effect.SaveSessions.class);

        // the same init again changes nothing that needs saving
        apply(new Intent.SessionInit("work", state.session("work").orElseThrow().info()));
        assertThat(effects).isEmpty();
    }

    @Test
    void anImportedConversationForksOnceThenResumesTheFork() {
        apply(new Intent.SaveSession("", SessionSpec.imported("old", "/tmp", "terminal-1")));
        assertThat(state.session("old").orElseThrow().spec().startMode()).isEqualTo(StartMode.FORK);

        apply(new Intent.StartSession("old"));
        apply(new Intent.SessionInit("old", new SessionInfo("fork-2", "claude-haiku-5-5", "/tmp", "2.1.296", "default", List.of())));

        SessionSpec spec = state.session("old").orElseThrow().spec();
        assertThat(spec.claudeSessionId()).isEqualTo("fork-2");
        assertThat(spec.startMode()).isEqualTo(StartMode.RESUME);
    }

    @Test
    void cancelInterruptsABusySessionWhenNoSqlRuns() {
        apply(new Intent.RunText(P1, "a long task", Target.CLAUDE));
        apply(new Intent.CancelRun(P1));
        assertThat(effects).containsExactly(new Effect.InterruptClaude("work"));
    }

    @Test
    void unexpectedExitFailsTheSessionAndDeniesItsApprovals() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        Approval a = new Approval(7L, "work", ApprovalKind.TOOL, "Bash", "{}", "rm -rf build", 1L);
        apply(new Intent.ApprovalRequested(a));
        apply(new Intent.SessionExited("work", 1, "boom"));

        assertThat(state.session("work").orElseThrow().status()).isEqualTo(Status.FAILED);
        assertThat(state.approvals()).isEmpty();
        assertThat(effects).containsExactly(new Effect.ResolveApproval(a, new ApprovalDecision.Deny("session ended")));
    }

    @Test
    void anOpenThatCompletesAfterCloseDoesNotReopen() {
        apply(new Intent.OpenConnection("local"));
        apply(new Intent.CloseConnection("local"));
        assertThat(state.connection("local").orElseThrow().status()).isEqualTo(Status.CLOSING);
        apply(new Intent.ConnectionOpened("local"));
        assertThat(effects).isEmpty();
        apply(new Intent.ConnectionClosed("local"));
        assertThat(state.connection("local").orElseThrow().status()).isEqualTo(Status.CLOSED);
    }

    @Test
    void aPromptToAStoppingSessionIsRefusedNotLost() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.StopSession("work"));
        apply(new Intent.RunText(P1, "go on", Target.CLAUDE));
        assertThat(effects).isEmpty();
        assertThat(state.session("work").orElseThrow().status()).isEqualTo(Status.CLOSING);
        assertThat(state.notice().text()).contains("is stopping");
    }

    @Test
    void removingASessionDeniesItsWaitingApprovals() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        Approval a = new Approval(8L, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L);
        apply(new Intent.ApprovalRequested(a));
        apply(new Intent.RemoveSession("work"));
        assertThat(state.approvals()).isEmpty();
        assertThat(effects).contains(new Effect.ResolveApproval(a, new ApprovalDecision.Deny("session removed")));
    }

    @Test
    void aTurnThatEndsWithNothingQueuedWithdrawsItsCardsAndKeepsKnownUsage() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.RunText(P1, "delete the rows", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.40, 30_000L, 200_000L, false, ""));
        apply(new Intent.RunText(P1, "again", Target.CLAUDE));
        Approval a = new Approval(9L, "work", ApprovalKind.SQL_WRITE, "db_run_sql", "{}", "DELETE", 1L);
        apply(new Intent.ApprovalRequested(a));
        Approval background = new Approval(10L, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L);
        apply(new Intent.ApprovalRequested(background));

        // interrupted: the result arrives with nothing queued while the cards still wait; the statement card goes,
        // a tool card may belong to a background agent and stays until Claude Code cancels its call
        apply(new Intent.SessionTurnEnded("work", 0, 0.55, 31_000L, 200_000L, true, "interrupted"));
        assertThat(state.approvals()).containsExactly(background);
        assertThat(effects).contains(new Effect.ResolveApproval(a, new ApprovalDecision.Deny("the turn ended")));

        // a turn end with unknown usage (the process was gone) keeps what was known
        apply(new Intent.SessionTurnEnded("work", 0, -1.0, -1L, 0L, true, "session not running"));
        assertThat(state.session("work").orElseThrow().costUsd()).isEqualTo(0.55);
        assertThat(state.session("work").orElseThrow().contextTokens()).isEqualTo(31_000L);
    }

    @Test
    void stoppedSessionClosesCleanly() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.StopSession("work"));
        assertThat(effects).containsExactly(new Effect.StopClaude("work"));
        apply(new Intent.SessionExited("work", 143, ""));
        assertThat(state.session("work").orElseThrow().status()).isEqualTo(Status.CLOSED);
    }

    @Test
    void modelChangeOnARunningSessionSendsTheSlashCommand() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SetSessionModel("work", "sonnet"));
        assertThat(effects).first().isEqualTo(new Effect.SendToClaude("work", "/model sonnet", "settings"));
        assertThat(state.session("work").orElseThrow().spec().model()).isEqualTo("sonnet");
    }

    @Test
    void importSkipsKnownNamesAndConversationIds() {
        apply(new Intent.SaveSession("", SessionSpec.imported("old", "/tmp", "id-1")));
        apply(new Intent.ImportSessions(List.of(
                SessionSpec.imported("old", "/x", "id-9"),
                SessionSpec.imported("dup", "/x", "id-1"),
                SessionSpec.imported("fresh", "/x", "id-2"))));
        assertThat(state.sessions()).extracting(s -> s.name()).containsExactly("work", "old", "fresh");
        assertThat(effects).contains(new Effect.LoadHistory(SessionSpec.imported("fresh", "/x", "id-2")));
    }

    @Test
    void renamingARunningSessionIsRefused() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SaveSession("work", States.WORK.withName("renamed")));
        assertThat(state.session("work")).isPresent();
        assertThat(state.notice().text()).contains("before renaming");
    }

    // ---- connections

    @Test
    void renamingAConnectionRebindsPanels() {
        apply(new Intent.SaveConnection("local", States.LOCAL.withName("lab")));
        assertThat(state.panels()).allSatisfy(p -> assertThat(p.connection()).isEqualTo("lab"));
        assertThat(effects).hasAtLeastOneElementOfType(Effect.SavePanels.class);
    }

    @Test
    void changingAnOpenConnectionClosesIt() {
        apply(new Intent.OpenConnection("local"));
        apply(new Intent.ConnectionOpened("local"));
        ConnectionSpec moved = new ConnectionSpec("local", "lab-host", 8812, "qdb", "admin", "quest", false);
        apply(new Intent.SaveConnection("local", moved));
        assertThat(effects).first().isEqualTo(new Effect.CloseDb("local"));
        assertThat(state.connection("local").orElseThrow().status()).isEqualTo(Status.CLOSED);
    }

    @Test
    void anOpenedConnectionReadsItsSchemaAndExploringShowsIt() {
        apply(new Intent.OpenConnection("local"));
        apply(new Intent.ConnectionOpened("local"));
        assertThat(effects).containsExactly(new Effect.LoadSchema(States.LOCAL, false));
        apply(new Intent.ExploreSchema("local"));
        assertThat(effects).containsExactly(new Effect.LoadSchema(States.LOCAL, true));
        apply(new Intent.ExploreSchema("nope"));
        assertThat(effects).isEmpty();
        assertThat(state.notice().level()).isEqualTo(Notice.Level.WARN);
    }

    @Test
    void duplicateConnectionNameIsRefused() {
        apply(new Intent.SaveConnection("", ConnectionSpec.questDbDefaults("local")));
        assertThat(state.connections()).hasSize(1);
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
    }

    @Test
    void closingAConnectionCancelsItsActiveRuns() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        apply(new Intent.ConnectionOpened("local"));
        apply(new Intent.CloseConnection("local"));
        assertThat(effects).containsExactly(new Effect.CancelSql(1L), new Effect.CloseDb("local"));
        assertThat(run(P1).status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void removingAConnectionUnbindsPanels() {
        apply(new Intent.RemoveConnection("local"));
        assertThat(state.connections()).isEmpty();
        assertThat(state.panels()).allSatisfy(p -> assertThat(p.connection()).isEmpty());
    }

    // ---- panels

    @Test
    void newPanelInheritsTheFocusedBindingsAndTakesFocus() {
        PanelId p3 = new PanelId("p3");
        apply(new Intent.AddPanel(p3, ""));
        assertThat(state.focused()).isEqualTo(p3);
        assertThat(state.panel(p3).orElseThrow()).satisfies(p -> {
            assertThat(p.title()).isEqualTo("panel 3");
            assertThat(p.connection()).isEqualTo("local");
            assertThat(p.session()).isEqualTo("work");
        });
    }

    @Test
    void closingAPanelCancelsItsRunAndMovesFocus() {
        apply(new Intent.FocusPanel(P2));
        apply(new Intent.RunText(P2, "SELECT 1", Target.AUTO));
        apply(new Intent.ClosePanel(P2));
        assertThat(state.focused()).isEqualTo(P1);
        assertThat(state.runs()).isEmpty();
        assertThat(effects).startsWith(new Effect.CancelSql(1L), new Effect.ReleaseResult(1L), new Effect.ForgetPanel(P2));
    }

    @Test
    void theLastPanelStays() {
        apply(new Intent.ClosePanel(P2));
        apply(new Intent.ClosePanel(P1));
        assertThat(state.panels()).hasSize(1);
        assertThat(state.notice().level()).isEqualTo(Notice.Level.WARN);
    }

    // ---- approvals

    @Test
    void answeredApprovalIsRemovedAndResolved() {
        Approval a = new Approval(3L, "work", ApprovalKind.QUESTION, "AskUserQuestion", "{}", "colour?", 1L);
        apply(new Intent.ApprovalRequested(a));
        assertThat(state.pendingApprovals("work")).isEqualTo(1);
        ApprovalDecision answer = new ApprovalDecision.Answer(Map.of("colour?", "Red"));
        apply(new Intent.ApprovalAnswered(3L, answer));
        assertThat(state.approvals()).isEmpty();
        assertThat(effects).containsExactly(new Effect.ResolveApproval(a, answer));

        apply(new Intent.ApprovalAnswered(3L, answer)); // a second answer to the same card does nothing
        assertThat(effects).isEmpty();
    }

    // ---- views

    @Test
    void sameTitleFromSameSessionReplacesUnlessPinned() {
        ViewSpec first = new ViewSpec.Markdown("# one");
        ViewSpec second = new ViewSpec.Markdown("# two");
        apply(new Intent.ShowView("work", "notes", first, Placement.LOWER));
        long id = state.views().getFirst().id();
        assertThat(state.lowerTab()).isEqualTo(LowerTab.view(id));

        apply(new Intent.SelectLowerTab(LowerTab.SESSION));
        apply(new Intent.ShowView("work", "notes", second, Placement.LOWER));
        assertThat(state.lowerTab()).as("an update in place keeps the user's tab").isEqualTo(LowerTab.SESSION);
        assertThat(state.views()).singleElement().satisfies(v -> {
            assertThat(v.spec()).isEqualTo(second);
            assertThat(v.version()).isEqualTo(2L);
        });

        apply(new Intent.PinView(id, true));
        apply(new Intent.ShowView("work", "notes", first, Placement.LOWER));
        assertThat(state.views()).hasSize(2);
    }

    @Test
    void theUsersOwnViewShowsAgainButARefreshDoesNotMoveTheUser() {
        apply(new Intent.ShowView("", "plot · one", new ViewSpec.Markdown("a"), Placement.LOWER));
        long id = state.views().getFirst().id();
        apply(new Intent.SelectLowerTab(LowerTab.SESSION));
        apply(new Intent.ShowView("", "plot · one", new ViewSpec.Markdown("b"), Placement.LOWER)); // ⌘J again
        assertThat(state.lowerTab()).isEqualTo(LowerTab.view(id));

        apply(new Intent.SelectLowerTab(LowerTab.SESSION));
        apply(new Intent.RefreshView("plot · one", new ViewSpec.Markdown("c"))); // a live plot's new data
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);
        assertThat(state.views().getFirst().spec()).isEqualTo(new ViewSpec.Markdown("c"));
    }

    @Test
    void aLateRefreshNeverReplacesTheUsersNewerStatement() {
        apply(new Intent.RunText(P1, "SELECT * FROM t", Target.SQL));
        long first = run(P1).requestId();
        apply(new Intent.SqlCompleted(first, 1, -1, false, 1, 1, 2));
        apply(new Intent.RunText(P1, "SELECT 2", Target.SQL));
        apply(new Intent.RefreshSql(P1, "SELECT * FROM t"));
        assertThat(effects).isEmpty();
        assertThat(run(P1).sql()).isEqualTo("SELECT 2");
    }

    @Test
    void closingTheSelectedViewSelectsResults() {
        apply(new Intent.ShowView("work", "t", new ViewSpec.Markdown("x"), Placement.LOWER));
        apply(new Intent.CloseView(state.views().getFirst().id()));
        assertThat(state.views()).isEmpty();
        assertThat(state.lowerTab()).isEqualTo(LowerTab.RESULTS);
    }

    // ---- editor

    @Test
    void editWithoutPanelTargetsTheFocusedOne() {
        apply(new Intent.EditPanel(null, "SELECT 42", EditMode.APPEND));
        assertThat(effects).containsExactly(new Effect.EditPanelText(P1, "SELECT 42", EditMode.APPEND));
    }

    // ---- development tools

    private ViewSpec.Console console(String title) {
        return (ViewSpec.Console) state.views().stream().filter(v -> v.title().equals(title)).findFirst().orElseThrow().spec();
    }

    @Test
    void gradleRunsInTheSessionDirectoryAndShowsAConsole() {
        apply(new Intent.RunGradle(null, " :db:test "));
        String dir = state.session("work").orElseThrow().spec().workDir();
        assertThat(effects).containsExactly(new Effect.RunGradle(1L, dir, ":db:test"));
        assertThat(console("gradle :db:test").state()).isEqualTo(ViewSpec.RunState.RUNNING);
        assertThat(state.lowerTab().kind()).isEqualTo(LowerTab.Kind.VIEW);

        clock.advance(1);
        apply(new Intent.CommandFinished(1L, 1, 20_000L, false,
                new ViewSpec.TreeNode("tests", "1 failed", List.of()), Map.of("LaneTest.cancel", "expected 1")));
        ViewSpec.Console done = console("gradle :db:test");
        assertThat(done.state()).isEqualTo(ViewSpec.RunState.FAILED);
        assertThat(done.failures()).containsKey("LaneTest.cancel");
        assertThat(effects).containsExactly(new Effect.DesktopNotify("gradle :db:test", "failed in 20 s"));
    }

    @Test
    void rerunningACommandStopsAndReplacesTheRunningOne() {
        apply(new Intent.RunGradle(P1, "build"));
        apply(new Intent.SelectLowerTab(LowerTab.SESSION));
        apply(new Intent.RunGradle(P1, "build"));
        assertThat(effects).contains(new Effect.CancelCommand(1L), new Effect.ForgetCommand(1L));
        assertThat(state.views()).hasSize(1);
        assertThat(console("gradle build").runId()).isNotEqualTo(1L);
        assertThat(console("gradle build").panel()).isEqualTo(P1.value());
        assertThat(state.lowerTab()).isEqualTo(LowerTab.view(state.views().getFirst().id()));
    }

    @Test
    void closingARunningConsoleStopsItAndFreesItsOutput() {
        apply(new Intent.RunJfrq("/r/app.jfr", "health"));
        assertThat(effects).containsExactly(new Effect.RunJfrq(1L, "/r/app.jfr", "health"));
        long view = state.views().getFirst().id();
        apply(new Intent.CloseView(view));
        assertThat(effects).containsExactly(new Effect.CancelCommand(1L), new Effect.ForgetCommand(1L));
        apply(new Intent.CommandFinished(1L, 143, 10L, true, ViewSpec.TreeNode.NONE, Map.of()));
        assertThat(state.views()).isEmpty();
    }

    @Test
    void textForAPanelClosedSinceGoesToTheFocusedPanel() {
        apply(new Intent.RunText(new PanelId("closed-since"), "these tests failed", Target.CLAUDE));
        assertThat(effects).contains(new Effect.StartClaude(state.session("work").orElseThrow().spec()));
    }

    @Test
    void promptWithoutPanelGoesToTheFocusedPanelsSession() {
        apply(new Intent.RunText(null, "explain this failure", Target.CLAUDE));
        assertThat(effects).contains(new Effect.StartClaude(state.session("work").orElseThrow().spec()));
    }
}
