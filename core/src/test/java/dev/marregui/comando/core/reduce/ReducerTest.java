package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.state.SessionState;
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
        // the late failure of the superseded run reaches no notice; the re-run's own notice stands
        assertThat(state.notice().text()).startsWith("Stopped the statement");
    }

    @Test
    void foldersAreReadAndWatchedByNameInTheirOrderAndShownWithoutTouchingTheState() {
        FolderRoot kb = new FolderRoot("KB", "/kb", "ALPHA.md", List.of());
        FolderRoot notes = new FolderRoot("Notes", "/n", "", List.of());
        state = state.withFolders(List.of(kb, notes));
        AppState before = state;
        apply(new Intent.BrowseFolders(List.of("Notes", "gone", "KB")));
        assertThat(effects).as("configured order; an unknown name is dropped").containsExactly(new Effect.ReadFolders(List.of(kb, notes)));
        apply(new Intent.WatchFolders(List.of("Notes")));
        assertThat(effects).containsExactly(new Effect.WatchFolders(List.of(notes)));
        apply(new Intent.WatchFolders(List.of()));
        assertThat(effects).as("none open: none watched").containsExactly(new Effect.WatchFolders(List.of()));
        ViewSpec.TreeNode tree = new ViewSpec.TreeNode("KB", "/kb", List.of());
        apply(new Intent.FolderLoaded(kb, tree));
        assertThat(effects).containsExactly(new Effect.ShowFolder(kb, tree));
        apply(new Intent.FolderLoaded(new FolderRoot("KB", "/old", "ALPHA.md", List.of()), tree));
        assertThat(effects).as("read with settings since edited: dropped").isEmpty();
        apply(new Intent.FolderLoaded(new FolderRoot("Gone", "/g", "", List.of()), tree));
        assertThat(effects).as("a folder since removed: dropped").isEmpty();
        PlanUsage usage = new PlanUsage(List.of(new PlanUsage.Window("Session", 5, "Oct 14 at 7:19pm")), 1L, "");
        apply(new Intent.PlanUsageRead(usage));
        assertThat(effects).containsExactly(new Effect.ShowPlanUsage(usage));
        assertThat(state).isSameAs(before);
    }

    @Test
    void aFolderIsAddedAfterTheOthersAndSavedUnlessOneHasItsName() {
        FolderRoot kb = new FolderRoot("KB", "/kb", "", List.of());
        FolderRoot notes = new FolderRoot("Notes", "/n", "", List.of());
        apply(new Intent.AddFolder(kb));
        apply(new Intent.AddFolder(notes));
        assertThat(state.folders()).containsExactly(kb, notes);
        assertThat(effects).containsExactly(new Effect.SaveFolders(List.of(kb, notes)), new Effect.WatchRepos(List.of(kb, notes), false));

        apply(new Intent.AddFolder(notes.withName("KB")));
        assertThat(state.folders()).containsExactly(kb, notes);
        assertThat(effects).as("refused: nothing saved").doesNotHaveAnyElementsOfTypes(Effect.SaveFolders.class);
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
        assertThat(state.notice().text()).isEqualTo("A folder called KB exists");
    }

    @Test
    void entriesGoToTheTrashOnlyFromADeletableFolderAndNeverTheFolderItselfOrAPathOutside() {
        FolderRoot pad = new FolderRoot("Scratchpad", "/s", "", List.of(), "scratchpad").withTrash("ALPHA.md");
        FolderRoot kb = new FolderRoot("KB", "/kb", "ALPHA.md", List.of());
        state = state.withFolders(List.of(pad, kb));
        apply(new Intent.DeleteFiles("Scratchpad", List.of("/s/2026-10-18/a.md", "/s/2026-10-17")));
        assertThat(effects).containsExactly(new Effect.DeleteFiles(pad, List.of("/s/2026-10-18/a.md", "/s/2026-10-17")));

        apply(new Intent.DeleteFiles("KB", List.of("/kb/x.md")));
        assertThat(effects).as("a folder not marked deletable keeps its files").doesNotHaveAnyElementsOfTypes(Effect.DeleteFiles.class);
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
        assertThat(state.notice().text()).startsWith("Folder KB keeps its files");

        apply(new Intent.DeleteFiles("Scratchpad", List.of("/s/a.md", "/s")));
        assertThat(effects).as("the folder itself: nothing moves").doesNotHaveAnyElementsOfTypes(Effect.DeleteFiles.class);
        assertThat(state.notice().text()).isEqualTo("Folder Scratchpad itself stays; nothing was moved to the Trash");

        apply(new Intent.DeleteFiles("Scratchpad", List.of("/s/../kb/x.md")));
        assertThat(effects).as("a path leading outside").doesNotHaveAnyElementsOfTypes(Effect.DeleteFiles.class);
        assertThat(state.notice().text()).isEqualTo("x.md is not in folder Scratchpad; nothing was moved to the Trash");

        apply(new Intent.DeleteFiles("Gone", List.of("/g/a.md")));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.DeleteFiles.class);
        assertThat(state.notice().text()).isEqualTo("No folder called Gone");
    }

    @Test
    void foldersJsonReadAgainReplacesTheFoldersAndWritesNothing() {
        FolderRoot kb = new FolderRoot("KB", "/kb", "ALPHA.md", List.of());
        apply(new Intent.FoldersRead(List.of(kb)));
        assertThat(state.folders()).containsExactly(kb);
        assertThat(effects).as("the file is the user's: not written back; its repositories are read")
                .containsExactly(new Effect.WatchRepos(List.of(kb), false));
        AppState before = state;
        apply(new Intent.FoldersRead(List.of(kb)));
        assertThat(state).as("the same folders change nothing").isSameAs(before);
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
        assertThat(effects).noneMatch(e -> e instanceof Effect.ExecuteSql || e instanceof Effect.OpenDb);
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
    void aPromptRunInAPanelTheUserIsNotLookingAtLeavesTheLowerPanelAlone() {
        apply(new Intent.SelectLowerTab(LowerTab.RESULTS));
        apply(new Intent.RunText(P2, "review the diff", Target.CLAUDE)); // P1 has the focus

        assertThat(effects).contains(new Effect.SendToClaude("work", "review the diff", "two"));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.RESULTS);
    }

    @Test
    void aStartReportedAfterTheStopLeavesTheSessionStopped() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.StopSession("work"));
        Status stopped = state.session("work").orElseThrow().status();
        apply(new Intent.SessionStarted("work"));
        assertThat(state.session("work").orElseThrow().status()).isEqualTo(stopped).isNotEqualTo(Status.OPEN);
    }

    @Test
    void anAddedPanelNeverSharesATitleInAnyCase() {
        PanelId readme = new PanelId("readme");
        apply(new Intent.AddPanel(readme, "One"));
        assertThat(state.panel(readme).orElseThrow().title()).isEqualTo("One 2");
        apply(new Intent.RenamePanel(P2, "ONE"));
        assertThat(state.panel(P2).orElseThrow().title()).isEqualTo("two");
        assertThat(state.titleTaken("one 2")).as("ignoring case").isTrue();
        assertThat(state.titleTaken("three")).isFalse();
        apply(new Intent.ClosePanel(P2));
        assertThat(state.titleTaken("TWO")).as("a closed panel keeps its title").isTrue();
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
    void aSessionWaitsOnBackgroundWorkUntilItEndsAndTheTurnItStartsCountsAsRunning() {
        BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        apply(new Intent.RunText(P1, "review it in the background", Target.CLAUDE));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionTasksChanged("work", List.of(agent)));
        apply(new Intent.SessionTurnEnded("work", 0, 0.12, 40_000L, 200_000L, false, ""));
        SessionState waiting = state.session("work").orElseThrow();
        assertThat(waiting.isBusy()).isFalse();
        assertThat(waiting.isWaiting()).isTrue();
        assertThat(waiting.waitingFor()).isEqualTo("review agent");
        assertThat(effects).as("handing work to the background finishes nothing").noneMatch(Effect.DesktopNotify.class::isInstance);

        // the agent ends; Claude Code starts a turn of its own to read its report
        clock.advance(60_000);
        apply(new Intent.SessionTasksChanged("work", List.of()));
        SessionState returned = state.session("work").orElseThrow();
        assertThat(returned.isWaiting()).isFalse();
        assertThat(returned.isReturning(clock.millis() + 1_000, 15_000)).as("returned, the reading turn not started yet").isTrue();
        apply(new Intent.SessionTurnRunning("work"));
        SessionState reading = state.session("work").orElseThrow();
        assertThat(reading.isBusy()).as("a turn comando did not send runs too").isTrue();
        assertThat(reading.isReturning(clock.millis(), 15_000)).isFalse();
        assertThat(reading.turnStartedMillis()).isEqualTo(clock.millis());

        apply(new Intent.SessionTurnEnded("work", 0, 0.20, 50_000L, 200_000L, false, ""));
        assertThat(state.session("work").orElseThrow().isBusy()).isFalse();
        assertThat(effects).containsExactly(new Effect.DesktopNotify("work", "Turn finished"));

        // Claude Code sends one more result after a background agent's report, with no model call: it finished nothing
        apply(new Intent.SessionTurnEnded("work", 0, 0.20, 50_000L, 200_000L, false, ""));
        assertThat(effects).noneMatch(Effect.DesktopNotify.class::isInstance);
    }

    @Test
    void aLaterTurnThatEndsWhileOlderBackgroundWorkRunsOnIsFinished() {
        BackgroundTask build = new BackgroundTask("b1", "local_bash", "build in the background", 0L);
        apply(new Intent.RunText(P1, "start the build in the background", Target.CLAUDE));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionTasksChanged("work", List.of(build)));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 10_000L, 200_000L, false, ""));
        assertThat(effects).as("the hand-off turn").noneMatch(Effect.DesktopNotify.class::isInstance);

        // a question while the build runs on: its answer is an outcome of its own
        apply(new Intent.RunText(P1, "what does the build do?", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.2, 12_000L, 200_000L, false, ""));
        assertThat(state.session("work").orElseThrow().isWaiting()).as("the build still runs").isTrue();
        assertThat(state.session("work").orElseThrow().isAwaitingHandOff()).isFalse();
        assertThat(effects).containsExactly(new Effect.DesktopNotify("work", "Turn finished"));
    }

    @Test
    void aCallInARunningTurnLeavesItsCountAndAnExitEndsTheBackgroundWork() {
        apply(new Intent.RunText(P1, "first", Target.CLAUDE));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.RunText(P1, "second", Target.CLAUDE));
        apply(new Intent.SessionTurnRunning("work"));
        assertThat(state.session("work").orElseThrow().pendingTurns()).isEqualTo(2);

        BackgroundTask build = new BackgroundTask("b1", "local_bash", "build in the background", 0L);
        long first = clock.millis();
        apply(new Intent.SessionTasksChanged("work", List.of(build)));
        clock.advance(5_000);
        apply(new Intent.SessionTasksChanged("work", List.of(build, new BackgroundTask("b2", "local_bash", "", 0L))));
        SessionState two = state.session("work").orElseThrow();
        assertThat(two.tasks()).extracting(BackgroundTask::startedMillis).as("a task keeps the time it was first heard of")
                .containsExactly(first, first + 5_000);
        assertThat(two.waitingFor()).isEqualTo("2 background tasks");

        apply(new Intent.SessionExited("work", 0, ""));
        assertThat(state.session("work").orElseThrow().tasks()).as("the process's tasks end with it").isEmpty();
        assertThat(state.session("work").orElseThrow().isWaiting()).isFalse();
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
        assertThat(effects).containsExactly(new Effect.ResolveApproval(a, new ApprovalDecision.Deny("session ended")),
                new Effect.DesktopNotify("comando", "Session work exited [code=1] boom"));
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
    void aTurnThatEndsWhileBackgroundWorkRunsKeepsEveryCard() {
        apply(new Intent.RunText(P1, "review the change in the background", Target.CLAUDE));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionTasksChanged("work", List.of(new BackgroundTask("a1", "local_agent", "review agent", 0L))));
        // the background agent asks to write while the turn that started it ends
        Approval write = new Approval(9L, "work", ApprovalKind.SQL_WRITE, "db_run_sql", "{}", "DELETE", 1L);
        apply(new Intent.ApprovalRequested(write));
        apply(new Intent.SessionTurnEnded("work", 0, 0.40, 30_000L, 200_000L, false, ""));
        assertThat(state.approvals()).as("the work that may have asked still runs: no card is missed").containsExactly(write);
        assertThat(effects).noneMatch(Effect.ResolveApproval.class::isInstance);
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

        // interrupted: the result arrives with nothing queued while the cards still wait; the statement card goes, a
        // tool card may belong to a background agent and stays until Claude Code cancels its call
        apply(new Intent.SessionTurnEnded("work", 0, 0.55, 31_000L, 200_000L, true, "interrupted"));
        assertThat(state.approvals()).containsExactly(background);
        assertThat(effects).contains(new Effect.ResolveApproval(a, new ApprovalDecision.Deny("the turn ended")));

        // a turn end with unknown usage (the process was gone) keeps what was known
        apply(new Intent.SessionTurnEnded("work", 0, -1.0, -1L, 0L, true, "session not running"));
        assertThat(state.session("work").orElseThrow().costUsd()).isEqualTo(0.55);
        assertThat(state.session("work").orElseThrow().contextTokens()).isEqualTo(31_000L);
    }

    @Test
    void aFreshStartLeavesTheConversationBehindAndSavesThatFirst() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionInit("work", new SessionInfo("conv-1", "opus", "/w", "2.1", "", List.of())));
        assertThat(state.session("work").orElseThrow().spec().claudeSessionId()).isEqualTo("conv-1");

        apply(new Intent.StartFresh("work"));
        assertThat(effects).as("a running session keeps its conversation; the button is hidden then").isEmpty();

        apply(new Intent.StopSession("work"));
        apply(new Intent.SessionExited("work", 0, ""));
        apply(new Intent.StartFresh("work"));
        SessionSpec spec = state.session("work").orElseThrow().spec();
        assertThat(spec.claudeSessionId()).isEmpty();
        assertThat(spec.startMode()).isEqualTo(StartMode.NEW);
        assertThat(state.session("work").orElseThrow().status()).isEqualTo(Status.OPENING);
        assertThat(effects).containsExactly(new Effect.SaveSessions(List.of(spec)), new Effect.StartClaude(spec));
        assertThat(state.notice().text()).isEqualTo("Session work: new conversation; the previous one (conv-1) can be imported again");

        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionInit("work", new SessionInfo("conv-2", "opus", "/w", "2.1", "", List.of())));
        assertThat(state.session("work").orElseThrow().spec().claudeSessionId()).isEqualTo("conv-2");
        assertThat(state.session("work").orElseThrow().spec().startMode()).isEqualTo(StartMode.RESUME);
    }

    @Test
    void aCheckpointRunsTheSessionsCommandAsAPromptFromItsPanel() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.Checkpoint("work"));
        assertThat(effects).contains(new Effect.SendToClaude("work", "/reflect", state.panel(P1).orElseThrow().title()));
        assertThat(state.session("work").orElseThrow().pendingTurns()).isEqualTo(1);

        apply(new Intent.BindSession(P1, ""));
        apply(new Intent.BindSession(P2, ""));
        apply(new Intent.Checkpoint("work"));
        assertThat(effects).as("no panel: nowhere to run it").doesNotHaveAnyElementsOfTypes(Effect.SendToClaude.class);
        assertThat(state.notice().text()).isEqualTo("Session work has no panel; open it to send /reflect");

        apply(new Intent.BindSession(P1, "work"));
        apply(new Intent.SaveSession("work", state.session("work").orElseThrow().spec().restarted()));
        SessionSpec silent = new SessionSpec("work", state.session("work").orElseThrow().spec().workDir(), "", "", "", List.of(), 0.0, "",
                StartMode.NEW, "");
        apply(new Intent.SaveSession("work", silent));
        apply(new Intent.Checkpoint("work"));
        assertThat(effects).as("an emptied command sends nothing").isEmpty();
    }

    @Test
    void aSessionIsDueItsCheckpointPastTheThresholdUntilTheCommandIsSent() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.RunText(P1, "go", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 500_000L, 1_000_000L, false, ""));
        assertThat(state.session("work").orElseThrow().reflection().due()).as("50 % of the window").isFalse();

        apply(new Intent.RunText(P1, "more", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 610_000L, 1_000_000L, false, ""));
        assertThat(state.session("work").orElseThrow().reflection().due()).as("61 %").isTrue();

        // typed in the panel, it counts as the button would
        apply(new Intent.RunText(P1, "/reflect", Target.AUTO));
        assertThat(state.session("work").orElseThrow().reflection().due()).isFalse();
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 620_000L, 1_000_000L, false, ""));
        assertThat(state.session("work").orElseThrow().reflection().due()).as("already past it at the checkpoint").isFalse();

        apply(new Intent.RunText(P1, "/compact", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 30_000L, 1_000_000L, false, ""));
        apply(new Intent.RunText(P1, "a long task", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 650_000L, 1_000_000L, false, ""));
        assertThat(state.session("work").orElseThrow().reflection().due()).as("crossed again after the compaction").isTrue();

        apply(new Intent.Checkpoint("work"));
        assertThat(state.session("work").orElseThrow().reflection().due()).isFalse();
    }

    @Test
    void aCheckpointIsTheCommandAloneOrWithArguments() {
        assertThat(Reducer.isCheckpoint("/reflect", "/reflect")).isTrue();
        assertThat(Reducer.isCheckpoint("  /reflect now\n", "/reflect")).isTrue();
        assertThat(Reducer.isCheckpoint("/reflections", "/reflect")).isFalse();
        assertThat(Reducer.isCheckpoint("please /reflect", "/reflect")).isFalse();
        assertThat(Reducer.isCheckpoint("/reflect", "")).isFalse();
    }

    @Test
    void aSessionCommandRunsAsAPromptFromTheSessionsPanel() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionCommand("work", "/compact"));
        assertThat(effects).contains(new Effect.SendToClaude("work", "/compact", state.panel(P1).orElseThrow().title()));
        assertThat(state.session("work").orElseThrow().pendingTurns()).isEqualTo(1);

        apply(new Intent.BindSession(P1, ""));
        apply(new Intent.BindSession(P2, ""));
        apply(new Intent.SessionCommand("work", "/clear"));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.SendToClaude.class);
        assertThat(state.notice().text()).isEqualTo("Session work has no panel; open it to send /clear");
    }

    @Test
    void aClearedConversationIsFollowedSavedAndNamedAsImportable() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionInit("work", new SessionInfo("conv-1", "opus", "/w", "2.1", "", List.of())));
        apply(new Intent.RunText(P1, "go", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 700_000L, 1_000_000L, false, ""));
        assertThat(state.session("work").orElseThrow().reflection().due()).isTrue();

        // /clear: the same process reports a new conversation
        apply(new Intent.SessionInit("work", new SessionInfo("conv-2", "opus", "/w", "2.1", "", List.of())));
        SessionState x = state.session("work").orElseThrow();
        assertThat(x.spec().claudeSessionId()).isEqualTo("conv-2");
        assertThat(x.spec().startMode()).isEqualTo(StartMode.RESUME);
        assertThat(x.reflection()).as("a new conversation has nothing to reflect on").isEqualTo(Reflection.FRESH);
        assertThat(effects).singleElement().isInstanceOf(Effect.SaveSessions.class);
        assertThat(state.notice().text()).isEqualTo("Session work: new conversation; the previous one (conv-1) can be imported again");
    }

    @Test
    void sessionsAreOrderedAsToldTheRestKeepingTheirPlaceAfterThem() {
        apply(new Intent.SaveSession("", SessionSpec.fresh("alpha", "/a")));
        apply(new Intent.SaveSession("", SessionSpec.fresh("beta", "/b")));
        assertThat(state.sessions()).extracting(SessionState::name).containsExactly("work", "alpha", "beta");

        apply(new Intent.OrderSessions(List.of("beta", "nobody", "work")));
        assertThat(state.sessions()).extracting(SessionState::name).containsExactly("beta", "work", "alpha");
        assertThat(effects).singleElement().isInstanceOfSatisfying(Effect.SaveSessions.class,
                e -> assertThat(e.specs()).extracting(SessionSpec::name).containsExactly("beta", "work", "alpha"));

        apply(new Intent.OrderSessions(List.of("beta", "work", "alpha")));
        assertThat(effects).as("the same order saves nothing").isEmpty();
    }

    @Test
    void aFreshStartLeavesNothingDue() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.RunText(P1, "go", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 700_000L, 1_000_000L, false, ""));
        apply(new Intent.StopSession("work"));
        apply(new Intent.SessionExited("work", 0, ""));
        assertThat(state.session("work").orElseThrow().reflection().due()).as("a stopped session stays due").isTrue();
        apply(new Intent.StartFresh("work"));
        assertThat(state.session("work").orElseThrow().reflection()).isEqualTo(Reflection.FRESH);
    }

    @Test
    void aRunningSessionKnowsWhenItStarted() {
        clock.advance(10_000);
        apply(new Intent.StartSession("work"));
        assertThat(state.session("work").orElseThrow().startedMillis()).as("not yet").isZero();
        apply(new Intent.SessionStarted("work"));
        long started = clock.millis();
        assertThat(state.session("work").orElseThrow().startedMillis()).isEqualTo(started);
        clock.advance(5_000);
        apply(new Intent.SessionStarted("work"));
        assertThat(state.session("work").orElseThrow().startedMillis()).as("reported again for the same process").isEqualTo(started);
        apply(new Intent.StopSession("work"));
        assertThat(state.session("work").orElseThrow().startedMillis()).as("still stopping").isEqualTo(started);
        apply(new Intent.SessionExited("work", 0, ""));
        assertThat(state.session("work").orElseThrow().startedMillis()).isZero();
    }

    @Test
    void theSessionTabIsClearedOnlyBetweenTurns() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.RunText(P1, "go", Target.CLAUDE));
        apply(new Intent.ClearTranscript("work"));
        assertThat(effects).isEmpty();
        assertThat(state.notice().text()).isEqualTo("Session work is working; clear it once the turn has ended");

        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 1_000L, 200_000L, false, ""));
        apply(new Intent.ClearTranscript("work"));
        assertThat(effects).containsExactly(new Effect.ClearTranscript("work"));
        apply(new Intent.ClearTranscript("nobody"));
        assertThat(effects).isEmpty();
    }

    @Test
    void aFailedTurnIsRememberedOnTheSessionUntilTheNextOneStarts() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.RunText(P1, "go", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 1_000L, 200_000L, true, "rate limited"));
        assertThat(state.session("work").orElseThrow().lastTurnError()).isEqualTo("rate limited");

        apply(new Intent.RunText(P1, "again", Target.CLAUDE));
        assertThat(state.session("work").orElseThrow().lastTurnError()).as("a new turn supersedes the failure").isEmpty();
        apply(new Intent.SessionTurnEnded("work", 0, 0.2, 1_000L, 200_000L, true, "interrupted"));
        assertThat(state.session("work").orElseThrow().lastTurnError()).as("the user's interrupt is not a failure").isEmpty();
        apply(new Intent.RunText(P1, "once more", Target.CLAUDE));
        apply(new Intent.SessionTurnEnded("work", 0, 0.3, 1_000L, 200_000L, false, ""));
        assertThat(state.session("work").orElseThrow().lastTurnError()).isEmpty();
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
    void aConnectionRemovedRenamedOrPointedElsewhereHasItsSchemaForgottenAndAnUnchangedSaveKeepsIt() {
        apply(new Intent.SaveConnection("local", States.LOCAL));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.ForgetSchema.class);
        apply(new Intent.SaveConnection("local", new ConnectionSpec("local", "lab-host", 8812, "qdb", "admin", "quest", false)));
        assertThat(effects).contains(new Effect.ForgetSchema("local"));
        apply(new Intent.SaveConnection("local", state.connection("local").orElseThrow().spec().withName("lab")));
        assertThat(effects).contains(new Effect.ForgetSchema("local"));
        apply(new Intent.RemoveConnection("lab"));
        assertThat(effects).contains(new Effect.ForgetSchema("lab"));
    }

    @Test
    void removingAConnectionUnbindsPanels() {
        apply(new Intent.RemoveConnection("local"));
        assertThat(state.connections()).isEmpty();
        assertThat(state.panels()).allSatisfy(p -> assertThat(p.connection()).isEmpty());
    }

    // ---- panels

    @Test
    void newPanelTakesTheFocusedConnectionNoSessionAndFocus() {
        PanelId p3 = new PanelId("p3");
        apply(new Intent.AddPanel(p3, ""));
        assertThat(state.focused()).isEqualTo(p3);
        assertThat(state.panel(p3).orElseThrow()).satisfies(p -> {
            assertThat(p.title()).isEqualTo("panel 3");
            assertThat(p.connection()).isEqualTo("local");
            assertThat(p.session()).as("a session has one panel").isEmpty();
        });
    }

    // ---- a session has one panel

    private static final PanelId P3 = new PanelId("p3");

    /** p1 "one" on work, p2 "two" with no session, a headless session edge. */
    private static AppState onePanelPerSession() {
        return AppState.initial(List.of(new PanelState(P1, "one", "local", "work"), new PanelState(P2, "two", "local", "")),
                List.of(ConnectionState.closed(States.LOCAL)),
                List.of(SessionState.closed(States.WORK), SessionState.closed(SessionSpec.fresh("edge", "/w/edge"))));
    }

    @Test
    void openingASessionThatHasAPanelBringsThatPanelForward() {
        state = onePanelPerSession();
        apply(new Intent.OpenSession("work", P2, P3));
        assertThat(state.focused()).isEqualTo(P1);
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P1, P2);
        assertThat(effects).isEmpty();
    }

    @Test
    void openingAHeadlessSessionOnAPanelWithoutOneBindsItThere() {
        state = onePanelPerSession();
        apply(new Intent.OpenSession("edge", P2, P3));
        assertThat(state.panel(P2).orElseThrow().session()).isEqualTo("edge");
        assertThat(state.focused()).isEqualTo(P2);
        assertThat(state.panels()).hasSize(2);
        assertThat(effects).containsExactly(new Effect.SavePanels(state.panels(), state.closed()));
    }

    @Test
    void aMovedPanelTakesItsNewPlaceTheOthersKeepTheirOrderAndTheOrderIsSaved() {
        PanelId p3 = new PanelId("p3");
        apply(new Intent.AddPanel(p3, "three"));
        apply(new Intent.FocusPanel(P2));
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P1, P2, p3);

        apply(new Intent.MovePanel(P1, 2));
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P2, p3, P1);
        assertThat(state.focused()).as("a move keeps the focus").isEqualTo(P2);
        assertThat(effects).containsExactly(new Effect.SavePanels(state.panels(), state.closed()));

        apply(new Intent.MovePanel(P1, -4));
        assertThat(state.panels()).as("an index before the start is the start").extracting(PanelState::id).containsExactly(P1, P2, p3);
        apply(new Intent.MovePanel(P1, 99));
        assertThat(state.panels()).as("an index past the end is the end").extracting(PanelState::id).containsExactly(P2, p3, P1);

        apply(new Intent.MovePanel(P1, 2));
        assertThat(effects).as("a panel already in place saves nothing").isEmpty();
        apply(new Intent.MovePanel(new PanelId("gone"), 0));
        assertThat(effects).as("an unknown panel moves nothing").isEmpty();
    }

    @Test
    void aPanelIsNotRenamedToAnotherPanelsTitle() {
        String taken = state.panel(P2).orElseThrow().title();
        apply(new Intent.RenamePanel(P1, taken));
        assertThat(state.panel(P1).orElseThrow().title()).isNotEqualTo(taken);
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.SavePanels.class);
        assertThat(state.notice().text()).isEqualTo("A panel called " + taken + " exists");
        apply(new Intent.RenamePanel(P2, " " + taken + " "));
        assertThat(state.panel(P2).orElseThrow().title()).as("its own title is not taken").isEqualTo(taken);
    }

    @Test
    void openingAHeadlessSessionOnAPanelWithAnotherOpensANewPanelNamedAfterIt() {
        state = onePanelPerSession();
        apply(new Intent.RenamePanel(P2, "edge"));
        apply(new Intent.OpenSession("edge", P1, P3));

        assertThat(state.panel(P1).orElseThrow().session()).as("the panel keeps its session").isEqualTo("work");
        assertThat(state.panel(P3).orElseThrow()).isEqualTo(new PanelState(P3, "edge 2", "local", "edge"));
        assertThat(state.focused()).isEqualTo(P3);
        assertThat(state.notice().text()).isEqualTo("Opened edge in a new panel; one keeps work");
        assertThat(effects).containsExactly(new Effect.SavePanels(state.panels(), state.closed()));

        apply(new Intent.OpenSession("gone", P1, new PanelId("p4")));
        assertThat(state.notice().text()).isEqualTo("No session called gone");
        assertThat(state.panels()).hasSize(3);
    }

    @Test
    void bindingASessionTakesItFromItsOtherPanelAndAnEmptyNameDetaches() {
        state = onePanelPerSession();
        apply(new Intent.BindSession(P2, "work"));
        assertThat(state.panels()).extracting(PanelState::session).containsExactly("", "work");

        apply(new Intent.BindSession(P2, ""));
        assertThat(state.panels()).extracting(PanelState::session).containsExactly("", "");
        assertThat(state.session("work")).as("a detached session stays").isPresent();
    }

    @Test
    void aClosedPanelKeepsItsSessionAndOpeningTheSessionReopensIt() {
        state = onePanelPerSession();
        apply(new Intent.ClosePanel(P1));
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P2);
        assertThat(state.closed()).containsExactly(new PanelState(P1, "one", "local", "work"));
        assertThat(effects).containsExactly(new Effect.ForgetPanel(P1), new Effect.SavePanels(state.panels(), state.closed()));

        apply(new Intent.OpenSession("work", P2, P3));
        assertThat(state.panels()).as("its own panel, not a new one").extracting(PanelState::id).containsExactly(P2, P1);
        assertThat(state.focused()).isEqualTo(P1);
        assertThat(state.closed()).isEmpty();

        apply(new Intent.ClosePanel(P1));
        apply(new Intent.BindSession(P2, "work"));
        assertThat(state.closed()).as("a session has one panel, open or closed").containsExactly(new PanelState(P1, "one", "local", ""));
    }

    @Test
    void removingTheLastOpenPanelLeavesAnEmptyOneInItsPlace() {
        PanelId doc = new PanelId("doc");
        state = AppState.initial(List.of(new PanelState(doc, "PLAN", "local", "", true)), List.of(ConnectionState.closed(States.LOCAL)), List.of());
        apply(new Intent.RemovePanel(doc));
        assertThat(state.panels()).singleElement().satisfies(p -> {
            assertThat(p.id()).isNotEqualTo(doc);
            assertThat(p.connection()).isEqualTo("local");
            assertThat(p.document()).isFalse();
        });
        assertThat(state.focused()).isEqualTo(state.panels().getFirst().id());
        assertThat(effects).contains(new Effect.DeletePanel(doc));
    }

    @Test
    void theEmptyPanelThatReplacesTheLastNeverTakesAClosedPanelsId() {
        // nextId starts at 1 every run, while closed panels from an earlier run keep their ids
        PanelId open = new PanelId("o");
        state = AppState.initial(List.of(new PanelState(open, "open", "", "")), List.of(), List.of())
                .withClosed(List.of(new PanelState(new PanelId("p-1"), "kept", "", "")));
        apply(new Intent.RemovePanel(open));
        assertThat(state.panels()).singleElement().satisfies(p -> assertThat(p.id()).isNotEqualTo(new PanelId("p-1")));
        assertThat(state.closed()).extracting(PanelState::id).containsExactly(new PanelId("p-1"));
    }

    @Test
    void aNewPanelsDefaultTitleIsNotAClosedPanelsTitle() {
        apply(new Intent.AddPanel(P3, ""));
        apply(new Intent.ClosePanel(P3));
        apply(new Intent.AddPanel(new PanelId("p4"), ""));
        assertThat(state.panel(new PanelId("p4")).orElseThrow().title()).isNotEqualTo("panel 3");
    }

    @Test
    void aDocumentClosesForGoodAndARemovedPanelLosesItsFile() {
        state = onePanelPerSession();
        PanelId doc = new PanelId("doc");
        apply(new Intent.AddPanel(doc, "PLAN.md", true));
        apply(new Intent.ClosePanel(doc));
        assertThat(state.closed()).as("a document is not kept").isEmpty();
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.DeletePanel.class);

        apply(new Intent.ClosePanel(P1));
        apply(new Intent.RemovePanel(P1));
        assertThat(state.closed()).isEmpty();
        assertThat(state.notice().text()).isEqualTo("Removed one");
        assertThat(effects).containsExactly(new Effect.DeletePanel(P1), new Effect.SavePanels(state.panels(), state.closed()));

        apply(new Intent.AddPanel(P3, "three"));
        apply(new Intent.RemovePanel(P2));
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P3);
        assertThat(effects).containsExactly(new Effect.DeletePanel(P2), new Effect.SavePanels(state.panels(), state.closed()));
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

    @Test
    void aRequestBringsTheApprovalsTabForwardAndAnsweringTheLastBringsTheSessionTabOnThePanelThatAsked() {
        // both panels are bound to work; the user looks at another panel's place: the second
        apply(new Intent.SelectLowerTab(LowerTab.RESULTS));
        apply(new Intent.ApprovalRequested(new Approval(3L, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)));
        assertThat(state.lowerTab()).as("a session asks: its card in front").isEqualTo(LowerTab.APPROVALS);
        apply(new Intent.ApprovalRequested(new Approval(4L, "work", ApprovalKind.TOOL, "Bash", "{}", "pwd", 1L)));
        apply(new Intent.FocusPanel(P2));

        apply(new Intent.ApprovalAnswered(3L, ApprovalDecision.allowOnce()));
        assertThat(state.lowerTab()).as("a card still waits").isEqualTo(LowerTab.APPROVALS);
        apply(new Intent.ApprovalAnswered(4L, new ApprovalDecision.Deny("no")));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);
        assertThat(state.focused()).as("the session's first panel").isEqualTo(P1);
    }

    @Test
    void theLastCardAnsweredOrWithdrawnElsewhereLeavesTheTabsAlone() {
        apply(new Intent.ApprovalRequested(new Approval(3L, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)));
        // the user went to another tab while the card waited
        apply(new Intent.SelectLowerTab(LowerTab.TERMINAL));
        apply(new Intent.ApprovalAnswered(3L, ApprovalDecision.allowOnce()));
        assertThat(state.lowerTab()).as("the user was not on the Approvals tab").isEqualTo(LowerTab.TERMINAL);

        apply(new Intent.ApprovalRequested(new Approval(4L, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)));
        apply(new Intent.ApprovalWithdrawn(4L, "the session ended"));
        assertThat(state.lowerTab()).as("withdrawn, not answered").isEqualTo(LowerTab.APPROVALS);
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
    void closingTheSelectedViewSelectsTheFirstTabTheSession() {
        apply(new Intent.ShowView("work", "t", new ViewSpec.Markdown("x"), Placement.LOWER));
        apply(new Intent.CloseView(state.views().getFirst().id()));
        assertThat(state.views()).isEmpty();
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);
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
        apply(new Intent.RunGradle(P1, "build"));
        assertThat(effects).singleElement().isInstanceOf(Effect.RunGradle.class);
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
