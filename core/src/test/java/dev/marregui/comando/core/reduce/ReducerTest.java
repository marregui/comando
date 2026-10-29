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
import dev.marregui.comando.core.resource.SessionKind;
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

/// [Reducer] turns each intent into the next state and the effects to run: SQL runs, prompts and sessions, folders, notices.
/// - SQL on a closed connection opens it first; a rerun cancels the previous run and drops its late events.
/// - Runs of two panels are independent; cancel, progress, completion and failure set the run's status and notice.
/// - Folders are read and watched by name in their order, added unless one has the name, and replaced by `folders.json` without writing it.
/// - Entries go to the Trash only from a deletable folder, never the folder itself or a path outside it.
/// - A prompt on a closed session starts the session, then sends.
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

    /// SQL on a closed connection emits OpenDb then ExecuteSql with request id 1, queues the run, marks the connection opening,
    /// brings the results tab forward, and advances the next id to 2.
    @Test
    void sqlOnClosedConnectionOpensItThenExecutesWithAFreshId() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));

        assertThat(effects).containsExactly(new Effect.OpenDb(States.LOCAL), new Effect.ExecuteSql(1L, States.LOCAL, "SELECT 1"));
        assertThat(run(P1).status()).isEqualTo(RunStatus.QUEUED);
        assertThat(state.connection("local").orElseThrow().status()).isEqualTo(Status.OPENING);
        assertThat(state.lowerTab()).isEqualTo(LowerTab.RESULTS);
        assertThat(state.nextId()).isEqualTo(2L);
    }

    /// Running again in a panel cancels and releases the previous run, takes a new id, ignores the superseded run's late events,
    /// and keeps the rerun's own notice instead of the late failure.
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

    /// Reading and watching folders by name emits them in configured order, dropping unknown names; none open watches none; a
    /// loaded tree is shown unless it was read with since-edited settings or its folder was removed; the state is unchanged.
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

    /// A group of folders under one parent reads as a folder of its own, its group's name in capitals (record 118): watched
    /// and browsed by that name, its tree shown; it is no configured folder, so the state is unchanged.
    @Test
    void aGroupsRowIsReadAsAFolderOfItsOwn() {
        FolderRoot memory = new FolderRoot("Memory", "/b/MEMORY", "", List.of(), "memory", false, "", "Brain");
        FolderRoot kb = new FolderRoot("KB", "/b/KB", "", List.of(), "knowledge", false, "", "Brain");
        state = state.withFolders(List.of(memory, kb));
        FolderRoot brain = new FolderRoot("BRAIN", "/b", "", List.of("MEMORY", "KB"), "folder", false, "", "Brain");
        AppState before = state;
        apply(new Intent.WatchFolders(List.of("BRAIN", "Memory")));
        assertThat(effects).containsExactly(new Effect.WatchFolders(List.of(memory, brain)));
        ViewSpec.TreeNode tree = new ViewSpec.TreeNode("BRAIN", "/b", List.of());
        apply(new Intent.FolderLoaded(brain, tree));
        assertThat(effects).containsExactly(new Effect.ShowFolder(brain, tree));
        assertThat(state).isSameAs(before);
    }

    /// A new folder goes after the others and is saved and watched; one whose name is taken is refused with an error notice
    /// and nothing saved.
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

    /// Entries of a deletable folder are sent to the Trash; a folder not marked deletable, the folder itself, a path outside it
    /// and an unknown folder each move nothing and say why.
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

    /// A `folders.json` read again replaces the folders and reads their repositories without writing the file back; the same
    /// folders change nothing.
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

    /// A completion in one panel finishes its run and leaves the other panel's run queued.
    @Test
    void runsOfTwoPanelsAreIndependent() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        apply(new Intent.RunText(P2, "SELECT 2", Target.AUTO));
        apply(new Intent.SqlCompleted(1L, 1, -1L, false, 1, 1, 2));

        assertThat(run(P1).status()).isEqualTo(RunStatus.DONE);
        assertThat(run(P2).status()).isEqualTo(RunStatus.QUEUED);
    }

    /// Cancel emits CancelSql, marks the run cancelled, and a completion arriving after it leaves the run cancelled.
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

    /// Progress updates the run's row count, and completion sets its final status, row count and truncation.
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

    /// A query that ran long notifies the desktop with the panel, row count and duration when it ends; a short one does not.
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

    /// A failed run is marked failed and its server message shows as an error notice.
    @Test
    void failureIsShownAsAnErrorNotice() {
        apply(new Intent.RunText(P1, "SELECT nope FROM t", Target.AUTO));
        apply(new Intent.SqlFailed(1L, "table does not exist [table=t]", 4));
        assertThat(run(P1).status()).isEqualTo(RunStatus.FAILED);
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
        assertThat(state.notice().text()).contains("table does not exist");
    }

    /// SQL in a panel with no connection opens and runs nothing and says the panel has no connection.
    @Test
    void sqlWithoutAConnectionIsRefused() {
        apply(new Intent.BindConnection(P1, ""));
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        assertThat(effects).noneMatch(e -> e instanceof Effect.ExecuteSql || e instanceof Effect.OpenDb);
        assertThat(state.notice().text()).contains("has no connection");
    }

    /// Text forced to SQL runs as SQL even when the classifier would read it otherwise.
    @Test
    void forcedTargetOverridesTheClassifier() {
        apply(new Intent.RunText(P1, "trades WHERE price > 1", Target.SQL));
        assertThat(effects).contains(new Effect.ExecuteSql(1L, States.LOCAL, "trades WHERE price > 1"));
    }

    /// Blank text emits nothing and raises a warning notice.
    @Test
    void blankTextRunsNothing() {
        apply(new Intent.RunText(P1, "   \n ", Target.AUTO));
        assertThat(effects).isEmpty();
        assertThat(state.notice().level()).isEqualTo(Notice.Level.WARN);
    }

    // ---- prompts and sessions

    /// A prompt on a closed session starts the session, then sends the prompt with the panel's title.
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

    /// A prompt run from a document panel attached to the session, while another panel has the focus, is sent but leaves the
    /// lower tab as it was.
    @Test
    void aPromptRunInAPanelTheUserIsNotLookingAtLeavesTheLowerPanelAlone() {
        // a document attached to the session
        state = state.withPanel(new PanelState(P2, "two", "local", "work", true));
        apply(new Intent.SelectLowerTab(LowerTab.RESULTS));
        apply(new Intent.RunText(P2, "review the diff", Target.CLAUDE)); // P1 has the focus

        assertThat(effects).contains(new Effect.SendToClaude("work", "review the diff", "two"));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.RESULTS);
    }

    /// A start reported after the user stopped the session leaves it stopped, never open.
    @Test
    void aStartReportedAfterTheStopLeavesTheSessionStopped() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.StopSession("work"));
        Status stopped = state.session("work").orElseThrow().status();
        apply(new Intent.SessionStarted("work"));
        assertThat(state.session("work").orElseThrow().status()).isEqualTo(stopped).isNotEqualTo(Status.OPEN);
    }

    /// A panel added with a title another holds in another case is numbered (`One 2`); title checks ignore case, and a closed
    /// panel keeps its title taken.
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

    /// Prompts from a session's own panel and from a document attached to it go to one session queue; the turn result clears
    /// busy, records the context window and notifies the desktop that the turn finished.
    @Test
    void twoPanelsSharingASessionFeedOneQueue() {
        // its own panel and a document attached to it
        state = state.withPanel(new PanelState(P2, "two", "local", "work", true));
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

    /// A turn that hands work to a background agent finishes nothing and leaves the session waiting on it; when the agent
    /// ends the session is returning, the reading turn it starts counts as busy, its end notifies once, and the extra result
    /// after the report notifies nothing.
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

    /// A question answered while an older background build runs on is a finished turn of its own, notified, while the session
    /// still waits on the build.
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

    /// A tool call inside a running turn leaves the pending count as it was, background tasks keep the time first heard of
    /// and are named by count, and the process's exit ends them all.
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

    /// The init event records the conversation id with start mode RESUME, opens the session and saves sessions once; the same
    /// A turn's first message gives the session's own model and effort (a `/model` or `/effort` typed in the prompt shows);
    /// its later messages give what the turn runs at (a skill's `effort` lasts the rest of the turn), which the turn's end
    /// drops, leaving the session's own; the last activity's time is left as it was.
    @Test
    void whatATurnRunsAtShowsUntilItEndsAndItsFirstMessageGivesTheSessionsOwn() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionInit("work", new SessionInfo("abc", "claude-opus-5-5", "/tmp", "2.1.310", "default", List.of(), "medium")));
        long activity = state.session("work").orElseThrow().lastActivityMillis();
        clock.advance(5_000);

        apply(new Intent.SessionRanAt("work", "claude-sonnet-5-5", "low", true));
        SessionInfo first = state.session("work").orElseThrow().info();
        assertThat(List.of(first.model(), first.reportedEffort(), first.turnModel(), first.turnEffort()))
                .containsExactly("claude-sonnet-5-5", "low", "claude-sonnet-5-5", "low");
        assertThat(first.effort()).as("the settings' base stays").isEqualTo("medium");
        assertThat(state.session("work").orElseThrow().lastActivityMillis()).isEqualTo(activity);

        apply(new Intent.SessionRanAt("work", "claude-sonnet-5-5", "high", false));
        SessionInfo raised = state.session("work").orElseThrow().info();
        assertThat(List.of(raised.reportedEffort(), raised.turnEffort())).as("raised for the turn only").containsExactly("low", "high");

        apply(new Intent.SessionTurnEnded("work", 0, 0.12, 40_000L, 200_000L, false, ""));
        SessionInfo ended = state.session("work").orElseThrow().info();
        assertThat(List.of(ended.model(), ended.reportedEffort(), ended.turnModel(), ended.turnEffort()))
                .containsExactly("claude-sonnet-5-5", "low", "", "");
    }

    /// init again saves nothing.
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

    /// A prompt to a session goes to its own panel, the one created with it, even when the user has focused another panel
    /// (record 121: an agent's session_new with a first prompt); a session without an open panel gets an error notice.
    @Test
    void aPromptToASessionGoesToItsOwnPanelWhateverIsFocused() {
        apply(new Intent.SaveSession("", SessionSpec.fresh("alpha", "/tmp").withCreatedBy("lead")));
        PanelId own = state.homePanel("alpha").orElseThrow().id();
        apply(new Intent.FocusPanel(P1));
        apply(new Intent.PromptSession("alpha", "[from session lead]\nwrite it", "lead"));
        assertThat(effects).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(Effect.EditPanelText.class,
                t -> assertThat(t.panel()).isEqualTo(own)));
        assertThat(effects).noneSatisfy(e -> assertThat(e).isInstanceOfSatisfying(Effect.EditPanelText.class,
                t -> assertThat(t.panel()).isEqualTo(P1)));
        assertThat(effects).anySatisfy(e -> assertThat(e).isInstanceOf(Effect.StartClaude.class));
        assertThat(state.notice().level()).isNotEqualTo(Notice.Level.ERROR);

        apply(new Intent.PromptSession("nobody", "hi", "lead"));
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
        assertThat(state.notice().text()).contains("no open panel");
    }

    /// An imported conversation starts in FORK mode, and its first init records the fork's id with RESUME.
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

    /// Cancel in a panel with no SQL running interrupts its busy session.
    @Test
    void cancelInterruptsABusySessionWhenNoSqlRuns() {
        apply(new Intent.RunText(P1, "a long task", Target.CLAUDE));
        apply(new Intent.CancelRun(P1));
        assertThat(effects).containsExactly(new Effect.InterruptClaude("work"));
    }

    /// An unexpected exit marks the session failed and denies each waiting approval with `session ended`.
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

    /// A connection opened after the user asked to close it stays closing, emits nothing, and ends closed.
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

    /// A prompt to a session that is stopping emits nothing and says the session is stopping, so the text is not silently lost.
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

    /// Removing a session clears its waiting approvals, denying them.
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

    /// A turn that ends while background work still runs keeps every approval card, since that work may still be asking.
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

    /// A turn that ends with nothing queued denies its own cards with `the turn ended`, keeps a background task's card, and
    /// keeps the cost and context tokens already known.
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

        // a model call mid-turn, or a stored conversation loaded, reports the context at once; an unknown window keeps the
        // known one (record 120)
        apply(new Intent.SessionContext("work", 45_000L, 0L));
        assertThat(state.session("work").orElseThrow().contextTokens()).isEqualTo(45_000L);
        assertThat(state.session("work").orElseThrow().contextWindow()).isEqualTo(200_000L);
        apply(new Intent.SessionContext("work", -1L, 1_000_000L));
        assertThat(state.session("work").orElseThrow().contextTokens()).isEqualTo(45_000L);
        assertThat(state.session("work").orElseThrow().contextWindow()).isEqualTo(1_000_000L);
        apply(new Intent.SessionContext("nobody", 1L, 1L));
        assertThat(effects).isEmpty();
    }

    /// A fresh start of a stopped session clears its conversation id, saves the spec with mode NEW before starting, and says
    /// the previous conversation can be imported; a running session keeps its conversation; the next init resumes the new one.
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

    /// A checkpoint sends the session's command as a prompt from its panel, counting a turn; a session with no panel says to
    /// open one, a closed own panel still runs it, and an emptied command sends nothing.
    @Test
    void aCheckpointRunsTheSessionsCommandAsAPromptFromItsPanel() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.Checkpoint("work"));
        assertThat(effects).contains(new Effect.SendToClaude("work", "/reflect", state.panel(P1).orElseThrow().title()));
        assertThat(state.session("work").orElseThrow().pendingTurns()).isEqualTo(1);

        // an imported session never opened has no panel
        state = state.withSessions(List.of(state.session("work").orElseThrow(), SessionState.closed(SessionSpec.imported("old", "/tmp", "abc"))));
        apply(new Intent.Checkpoint("old"));
        assertThat(effects).as("no panel: nowhere to run it").doesNotHaveAnyElementsOfTypes(Effect.SendToClaude.class);
        assertThat(state.notice().text()).isEqualTo("Session old has no panel; open it to send /reflect");

        apply(new Intent.ClosePanel(P1));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 1_000L, 200_000L, false, ""));
        apply(new Intent.Checkpoint("work"));
        assertThat(effects).as("its own panel closed: it runs from there").contains(new Effect.SendToClaude("work", "/reflect", "one"));
        apply(new Intent.ReopenPanel(P1));
        apply(new Intent.SaveSession("work", state.session("work").orElseThrow().spec().restarted()));
        SessionSpec silent = new SessionSpec("work", state.session("work").orElseThrow().spec().workDir(), "", "", "", List.of(), 0.0, "",
                StartMode.NEW, "", "");
        apply(new Intent.SaveSession("work", silent));
        apply(new Intent.Checkpoint("work"));
        assertThat(effects).as("an emptied command sends nothing").isEmpty();
    }

    /// A session becomes due its checkpoint once its context passes the threshold (61 %, not 50 %), stops being due when the
    /// command is sent or it was already past at the checkpoint, and is due again when it crosses after a compaction.
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

    /// [Reducer#isCheckpoint] matches the command alone or with arguments, padded; a longer word, the command inside text, and
    /// an empty command do not match.
    @Test
    void aCheckpointIsTheCommandAloneOrWithArguments() {
        assertThat(Reducer.isCheckpoint("/reflect", "/reflect")).isTrue();
        assertThat(Reducer.isCheckpoint("  /reflect now\n", "/reflect")).isTrue();
        assertThat(Reducer.isCheckpoint("/reflections", "/reflect")).isFalse();
        assertThat(Reducer.isCheckpoint("please /reflect", "/reflect")).isFalse();
        assertThat(Reducer.isCheckpoint("/reflect", "")).isFalse();
    }

    /// A session command such as `/compact` is sent as a prompt from the session's panel, counting a turn; a session with no
    /// panel sends nothing and says to open one.
    @Test
    void aSessionCommandRunsAsAPromptFromTheSessionsPanel() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionCommand("work", "/compact"));
        assertThat(effects).contains(new Effect.SendToClaude("work", "/compact", state.panel(P1).orElseThrow().title()));
        assertThat(state.session("work").orElseThrow().pendingTurns()).isEqualTo(1);

        state = state.withSessions(List.of(state.session("work").orElseThrow(), SessionState.closed(SessionSpec.imported("old", "/tmp", "abc"))));
        apply(new Intent.SessionCommand("old", "/compact"));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.SendToClaude.class);
        assertThat(state.notice().text()).isEqualTo("Session old has no panel; open it to send /compact");
    }

    /// After the conversation is cleared, the new conversation id is followed with RESUME, its reflection starts fresh, the
    /// sessions are saved once, and the notice names the previous conversation as importable.
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

    /// The checkpoint button clears the conversation after its turn, carrying the summary (the clear counts as a turn); the
    /// summary survives into the new conversation, a command leaves it for the next prompt, and the next prompt sends it as a
    /// preface once, after which prompts go plain.
    @Test
    void theCheckpointButtonClearsTheConversationOnceItsTurnHasFinishedAndCarriesItsSummaryIntoTheNextPrompt() {
        String title = state.panel(P1).orElseThrow().title();
        String summary = "## Reflected\nSession log and memory written.";
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionInit("work", new SessionInfo("conv-1", "opus", "/w", "2.1", "", List.of())));
        apply(new Intent.Checkpoint("work"));
        assertThat(state.session("work").orElseThrow().checkpointClears()).isTrue();

        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 700_000L, 1_000_000L, false, "", "  " + summary + "\n"));
        SessionState x = state.session("work").orElseThrow();
        assertThat(x.checkpointClears()).isFalse();
        assertThat(x.spec().carried()).isEqualTo(summary);
        assertThat(x.pendingTurns()).as("the clear is a turn").isEqualTo(1);
        assertThat(effects).contains(new Effect.ClearConversation("work", summary), new Effect.SaveSessions(List.of(x.spec())))
                .as("the turn that clears notifies, once").doesNotHaveAnyElementsOfTypes(Effect.DesktopNotify.class);

        apply(new Intent.SessionInit("work", new SessionInfo("conv-2", "opus", "/w", "2.1", "", List.of())));
        assertThat(state.session("work").orElseThrow().spec().carried()).as("kept with the new conversation").isEqualTo(summary);
        assertThat(state.notice().text()).isEqualTo("Session work: new conversation; the previous one (conv-1) can be imported again;"
                + " the checkpoint's summary goes in front of the next prompt");
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 0L, 1_000_000L, false, "", ""));
        assertThat(effects).contains(new Effect.DesktopNotify("work", "Turn finished"));

        apply(new Intent.RunText(P1, "/compact", Target.CLAUDE));
        assertThat(effects).as("a command leaves the summary for the next prompt").contains(new Effect.SendToClaude("work", "/compact", title));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 0L, 1_000_000L, false, "", ""));

        apply(new Intent.RunText(P1, "go on", Target.CLAUDE));
        SessionSpec spec = state.session("work").orElseThrow().spec();
        assertThat(spec.carried()).isEmpty();
        assertThat(effects).containsExactly(new Effect.SendToClaude("work", "go on", title, Reducer.preface(summary)),
                new Effect.SaveSessions(List.of(spec)));
        assertThat(Reducer.preface(summary)).contains(summary).endsWith("\n\n---\n\n");
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 9_000L, 1_000_000L, false, "", "on it"));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.ClearConversation.class);

        apply(new Intent.RunText(P1, "next", Target.CLAUDE));
        assertThat(effects).contains(new Effect.SendToClaude("work", "next", title));
    }

    /// The conversation is kept when the user prompts after the checkpoint, when its turn fails or is interrupted, when it
    /// gives no summary (saying so), when the command was typed, and when the process ends during it.
    @Test
    void theCheckpointKeepsTheConversationWhenTheUserGoesOnItFailsOrGivesNoSummary() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));

        apply(new Intent.Checkpoint("work"));
        apply(new Intent.RunText(P1, "wait, one more thing", Target.CLAUDE));
        assertThat(state.session("work").orElseThrow().checkpointClears()).as("a prompt after it: the user goes on").isFalse();
        apply(new Intent.SessionTurnEnded("work", 1, 0.1, 9_000L, 1_000_000L, false, "", "summary"));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 9_000L, 1_000_000L, false, "", "done"));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.ClearConversation.class);

        apply(new Intent.Checkpoint("work"));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 9_000L, 1_000_000L, true, "rate limited"));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.ClearConversation.class);
        assertThat(state.session("work").orElseThrow().checkpointClears()).isFalse();

        apply(new Intent.Checkpoint("work"));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 9_000L, 1_000_000L, true, "interrupted"));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.ClearConversation.class);
        assertThat(state.notice().text()).isEqualTo("work: interrupted");

        apply(new Intent.Checkpoint("work"));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 9_000L, 1_000_000L, false, "", " "));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.ClearConversation.class);
        assertThat(state.notice().text()).isEqualTo("work: the checkpoint ended without a summary; the conversation is kept");

        apply(new Intent.RunText(P1, "/reflect", Target.CLAUDE));
        assertThat(state.session("work").orElseThrow().checkpointClears()).as("typed, it is a prompt like any other").isFalse();
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 9_000L, 1_000_000L, false, "", "summary"));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.ClearConversation.class);

        apply(new Intent.Checkpoint("work"));
        apply(new Intent.SessionExited("work", 1, "killed"));
        assertThat(state.session("work").orElseThrow().checkpointClears()).as("its turn ended with the process").isFalse();
    }

    /// A checkpoint queued behind a prompt does not clear when the prompt's turn ends, only after its own.
    @Test
    void aCheckpointQueuedBehindAPromptClearsAfterItsOwnTurn() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.RunText(P1, "go", Target.CLAUDE));
        apply(new Intent.Checkpoint("work"));
        apply(new Intent.SessionTurnEnded("work", 1, 0.1, 9_000L, 1_000_000L, false, "", "gone"));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.ClearConversation.class);
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 9_000L, 1_000_000L, false, "", "the summary"));
        assertThat(effects).contains(new Effect.ClearConversation("work", "the summary"));
    }

    /// Saving the session form, which has no carried-summary field, keeps the carried summary while applying the edit; a
    /// checkpoint before the next prompt has nothing new to clear and keeps the summary.
    @Test
    void aCarriedSummaryOutlivesTheSessionFormAndACheckpointBeforeTheNextPrompt() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.Checkpoint("work"));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 9_000L, 1_000_000L, false, "", "the summary"));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 0L, 1_000_000L, false, "", ""));

        SessionSpec edited = SessionKind.INSTANCE.fromAttributes(SessionKind.INSTANCE.toAttributes(state.session("work").orElseThrow().spec()
                .withModel("opus")));
        assertThat(edited.carried()).as("the form has no such field").isEmpty();
        apply(new Intent.SaveSession("work", edited));
        assertThat(state.session("work").orElseThrow().spec().carried()).isEqualTo("the summary");
        assertThat(state.session("work").orElseThrow().spec().model()).isEqualTo("opus");

        apply(new Intent.Checkpoint("work"));
        assertThat(state.session("work").orElseThrow().checkpointClears()).as("nothing new to clear").isFalse();
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 9_000L, 1_000_000L, false, "", "nothing to reflect on"));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.ClearConversation.class);
        assertThat(state.session("work").orElseThrow().spec().carried()).isEqualTo("the summary");
    }

    /// Ordering sessions puts the named ones first in the given order and the rest after them in their place, saving once;
    /// the same order again saves nothing.
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

    /// A stopped session stays due its checkpoint, and a fresh start resets its reflection to fresh.
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

    /// A session's start time is zero until it is reported, kept when reported again for the same process and while
    /// stopping, and zero again once closed.
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

    /// Clearing a working session's transcript is refused with a notice; between turns it emits ClearTranscript, once.
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

    /// A failed turn's error stays on the session until a new turn starts; the user's interrupt is not a failure.
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

    /// Stopping a session emits StopClaude, and its exit then marks it closed.
    @Test
    void stoppedSessionClosesCleanly() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.StopSession("work"));
        assertThat(effects).containsExactly(new Effect.StopClaude("work"));
        apply(new Intent.SessionExited("work", 143, ""));
        assertThat(state.session("work").orElseThrow().status()).isEqualTo(Status.CLOSED);
    }

    /// Changing the model of a running session sends `/model <name>` as a prompt and records the new model.
    @Test
    void modelChangeOnARunningSessionSendsTheSlashCommand() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SetSessionModel("work", "sonnet"));
        assertThat(effects).first().isEqualTo(new Effect.SendToClaude("work", "/model sonnet", "settings"));
        assertThat(state.session("work").orElseThrow().spec().model()).isEqualTo("sonnet");
    }

    /// Importing conversations skips one whose name or conversation id is known, adds the rest, and loads their history.
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

    /// Renaming a running session is refused, keeping it under its name, with a notice to stop it before renaming.
    @Test
    void renamingARunningSessionIsRefused() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SaveSession("work", States.WORK.withName("renamed")));
        assertThat(state.session("work")).isPresent();
        assertThat(state.notice().text()).contains("before renaming");
    }

    // ---- connections

    /// Renaming a connection rebinds every panel that used it to the new name and saves the panels.
    @Test
    void renamingAConnectionRebindsPanels() {
        apply(new Intent.SaveConnection("local", States.LOCAL.withName("lab")));
        assertThat(state.panels()).allSatisfy(p -> assertThat(p.connection()).isEqualTo("lab"));
        assertThat(effects).hasAtLeastOneElementOfType(Effect.SavePanels.class);
    }

    /// Editing an open connection closes it first, leaving it closed.
    @Test
    void changingAnOpenConnectionClosesIt() {
        apply(new Intent.OpenConnection("local"));
        apply(new Intent.ConnectionOpened("local"));
        ConnectionSpec moved = new ConnectionSpec("local", "lab-host", 8812, "qdb", "admin", "quest", false);
        apply(new Intent.SaveConnection("local", moved));
        assertThat(effects).first().isEqualTo(new Effect.CloseDb("local"));
        assertThat(state.connection("local").orElseThrow().status()).isEqualTo(Status.CLOSED);
    }

    /// An opened connection loads its schema quietly; exploring loads it to show; exploring with no connection emits nothing
    /// and warns.
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

    /// Adding a connection under a taken name is refused with an error notice, the list unchanged.
    @Test
    void duplicateConnectionNameIsRefused() {
        apply(new Intent.SaveConnection("", ConnectionSpec.questDbDefaults("local")));
        assertThat(state.connections()).hasSize(1);
        assertThat(state.notice().level()).isEqualTo(Notice.Level.ERROR);
    }

    /// Closing a connection cancels its active runs before closing it, marking them cancelled.
    @Test
    void closingAConnectionCancelsItsActiveRuns() {
        apply(new Intent.RunText(P1, "SELECT 1", Target.AUTO));
        apply(new Intent.ConnectionOpened("local"));
        apply(new Intent.CloseConnection("local"));
        assertThat(effects).containsExactly(new Effect.CancelSql(1L), new Effect.CloseDb("local"));
        assertThat(run(P1).status()).isEqualTo(RunStatus.CANCELLED);
    }

    /// An unchanged save keeps a connection's schema; removing it, pointing it elsewhere, or renaming it forgets the schema
    /// under its name.
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

    /// Removing a connection leaves no connection and unbinds every panel that used it.
    @Test
    void removingAConnectionUnbindsPanels() {
        apply(new Intent.RemoveConnection("local"));
        assertThat(state.connections()).isEmpty();
        assertThat(state.panels()).allSatisfy(p -> assertThat(p.connection()).isEmpty());
    }

    // ---- panels

    /// A new panel is titled by its number, takes the focused panel's connection but no session, and takes the focus.
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
        return AppState.initial(List.of(new PanelState(P1, "work", "local", "work"), new PanelState(P2, "two", "local", "")),
                List.of(ConnectionState.closed(States.LOCAL)),
                List.of(SessionState.closed(States.WORK), SessionState.closed(SessionSpec.fresh("edge", "/w/edge"))));
    }

    /// Renaming a stopped session's own panel renames the session and forgets the old name, and renaming the session renames
    /// its panel; a running session refuses with a notice, and a panel that is not a session's own keeps its title.
    @Test
    void renamingASessionsOwnPanelRenamesTheSessionAndTheOtherWayRoundOnlyWhileItIsStopped() {
        state = onePanelPerSession();
        apply(new Intent.RenamePanel(P1, "build"));
        assertThat(state.session("build")).as("the tab renames the session").isPresent();
        assertThat(state.session("work")).isEmpty();
        assertThat(state.panel(P1).orElseThrow()).isEqualTo(new PanelState(P1, "build", "local", "build"));
        assertThat(effects).contains(new Effect.ForgetSession("work"));

        apply(new Intent.SaveSession("build", state.session("build").orElseThrow().spec().withName("deploy")));
        assertThat(state.panel(P1).orElseThrow().title()).as("the session renames its panel").isEqualTo("deploy");

        apply(new Intent.StartSession("deploy"));
        apply(new Intent.SessionStarted("deploy"));
        apply(new Intent.RenamePanel(P1, "ship"));
        assertThat(state.panel(P1).orElseThrow().title()).isEqualTo("deploy");
        assertThat(state.notice().text()).isEqualTo("Stop session deploy before renaming it");

        apply(new Intent.RenamePanel(P2, "deploy"));
        assertThat(state.panel(P2).orElseThrow().title()).as("a session's name is its own panel's").isEqualTo("two");
    }

    /// Opening a session that has a panel focuses that panel, adds none, and emits nothing.
    @Test
    void openingASessionThatHasAPanelBringsThatPanelForward() {
        state = onePanelPerSession();
        apply(new Intent.OpenSession("work", P2, P3));
        assertThat(state.focused()).isEqualTo(P1);
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P1, P2);
        assertThat(effects).isEmpty();
    }

    /// Opening a session without a panel adds its own panel named after it with the focused connection, focuses it and
    /// saves the panels, never giving another panel to it; an unknown session says so and adds none.
    @Test
    void openingASessionWithoutAPanelOpensItsOwnNamedAfterItAndTakesNoOtherPanel() {
        state = onePanelPerSession();
        apply(new Intent.OpenSession("edge", P2, P3));
        assertThat(state.panel(P2).orElseThrow().session()).as("a panel is never given another session").isEmpty();
        assertThat(state.panel(P3).orElseThrow()).isEqualTo(new PanelState(P3, "edge", "local", "edge"));
        assertThat(state.homePanel("edge")).map(PanelState::id).contains(P3);
        assertThat(state.focused()).isEqualTo(P3);
        assertThat(effects).containsExactly(new Effect.SavePanels(state.panels(), state.closed()));

        apply(new Intent.OpenSession("gone", P1, new PanelId("p4")));
        assertThat(state.notice().text()).isEqualTo("No session called gone");
        assertThat(state.panels()).hasSize(3);
    }

    /// A moved panel takes its new index, the others keep their order, the focus stays and the order is saved; an index before
    /// the start or past the end clamps, a panel already in place or an unknown panel saves nothing.
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

    /// Renaming a panel to another panel's title is refused with a notice and nothing saved; a panel may keep its own title.
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

    /// A session created under the name of a panel without a session makes that panel its own; a new session otherwise comes
    /// with its own focused panel; a panel named like an existing session in another case is numbered, and a document's title
    /// does not become a session.
    @Test
    void aSessionCreatedWithAPanelsNameMakesThatPanelItsOwnAndNoPanelTakesASessionsName() {
        state = onePanelPerSession();
        apply(new Intent.SaveSession("", SessionSpec.fresh("two", "/w/two")));
        assertThat(state.panels()).as("no new panel: two, without a session, is its own").hasSize(2);
        assertThat(state.panel(P2).orElseThrow().session()).isEqualTo("two");
        assertThat(state.focused()).isEqualTo(P2);

        apply(new Intent.SaveSession("", SessionSpec.fresh("fresh", "/w/f")));
        PanelState own = state.homePanel("fresh").orElseThrow();
        assertThat(own.title()).as("a new session comes with its own panel").isEqualTo("fresh");
        assertThat(state.focused()).isEqualTo(own.id());

        apply(new Intent.AddPanel(P3, "edge"));
        assertThat(state.panel(P3).orElseThrow().title()).as("a session's name is its own panel's").isEqualTo("edge 2");
        apply(new Intent.RenamePanel(P3, "Edge"));
        assertThat(state.notice().text()).isEqualTo("A session called Edge exists; its panel is its own");

        PanelId doc = new PanelId("doc");
        apply(new Intent.AddPanel(doc, "PLAN.md", true));
        apply(new Intent.SaveSession("", SessionSpec.fresh("plan.md", "/w")));
        assertThat(state.notice().text()).as("a document's title is not a session's panel")
                .isEqualTo("A panel called plan.md exists; a session's name is its own panel's title");
        assertThat(state.session("plan.md")).isEmpty();
    }

    /// Binding a panel of comando's own to a session opens the session's own panel instead; a session's own panel is never
    /// detached; documents attach after the own panel and detach, the session staying.
    @Test
    void bindingAPanelOfComandosOwnOpensTheSessionsOwnPanelAndDocumentsAttachAndDetach() {
        state = onePanelPerSession();
        apply(new Intent.BindSession(P2, "work"));
        assertThat(state.panels()).as("the session keeps its panel; two keeps none").extracting(PanelState::session).containsExactly("work", "");
        assertThat(state.focused()).isEqualTo(P1);

        apply(new Intent.BindSession(P1, ""));
        assertThat(state.panel(P1).orElseThrow().session()).as("its own panel is never detached").isEqualTo("work");
        assertThat(state.notice().text()).isEqualTo("work is its session's own panel; removing the session removes both");

        PanelId doc = new PanelId("doc");
        PanelId spec = new PanelId("spec");
        apply(new Intent.AddPanel(doc, "PLAN.md", true));
        apply(new Intent.AddPanel(spec, "SPEC.md", true));
        apply(new Intent.BindSession(doc, "work"));
        apply(new Intent.BindSession(spec, "work"));
        assertThat(state.panelsBoundToSession("work")).as("its own panel first, then its documents").extracting(PanelState::id)
                .containsExactly(P1, doc, spec);
        assertThat(state.homePanel("work")).map(PanelState::id).contains(P1);

        apply(new Intent.BindSession(doc, ""));
        assertThat(state.panel(doc).orElseThrow().session()).isEmpty();
        assertThat(state.session("work")).isPresent();
    }

    /// Closing a session's panel keeps it among the closed ones with its session; opening or binding the session reopens that
    /// same panel and focuses it, giving no other panel the session.
    @Test
    void aClosedPanelKeepsItsSessionAndOpeningTheSessionReopensIt() {
        state = onePanelPerSession();
        apply(new Intent.ClosePanel(P1));
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P2);
        assertThat(state.closed()).containsExactly(new PanelState(P1, "work", "local", "work"));
        assertThat(effects).containsExactly(new Effect.ForgetPanel(P1), new Effect.SavePanels(state.panels(), state.closed()));

        apply(new Intent.OpenSession("work", P2, P3));
        assertThat(state.panels()).as("its own panel, not a new one").extracting(PanelState::id).containsExactly(P2, P1);
        assertThat(state.focused()).isEqualTo(P1);
        assertThat(state.closed()).isEmpty();

        apply(new Intent.ClosePanel(P1));
        apply(new Intent.BindSession(P2, "work"));
        assertThat(state.closed()).as("a session has one panel, open or closed: binding opens it again").isEmpty();
        assertThat(state.panel(P2).orElseThrow().session()).isEmpty();
        assertThat(state.focused()).isEqualTo(P1);
    }

    /// Removing the last open panel, a document, deletes it and leaves a new empty panel with the same connection, focused.
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

    /// The empty panel that replaces the last open one gets a new id, never the id of a panel kept among the closed.
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

    /// A new panel's default `panel N` title skips a title a closed panel keeps.
    @Test
    void aNewPanelsDefaultTitleIsNotAClosedPanelsTitle() {
        apply(new Intent.AddPanel(P3, ""));
        apply(new Intent.ClosePanel(P3));
        apply(new Intent.AddPanel(new PanelId("p4"), ""));
        assertThat(state.panel(new PanelId("p4")).orElseThrow().title()).isNotEqualTo("panel 3");
    }

    /// A closed document is not kept and its file stays; removing a session's own panel removes the session and the panel's
    /// file, saying so; removing a plain panel deletes its file and saves the rest.
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
        assertThat(state.session("work")).as("a session's own panel goes with it").isEmpty();
        assertThat(state.notice().text()).isEqualTo("Removed session work and its panel");
        assertThat(effects).contains(new Effect.DeletePanel(P1), new Effect.ForgetSession("work"));

        apply(new Intent.AddPanel(P3, "three"));
        apply(new Intent.RemovePanel(P2));
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P3);
        assertThat(effects).containsExactly(new Effect.DeletePanel(P2), new Effect.SavePanels(state.panels(), state.closed()));
    }

    /// Closing the focused panel cancels and releases its run, forgets the panel and moves the focus to the remaining one.
    @Test
    void closingAPanelCancelsItsRunAndMovesFocus() {
        apply(new Intent.FocusPanel(P2));
        apply(new Intent.RunText(P2, "SELECT 1", Target.AUTO));
        apply(new Intent.ClosePanel(P2));
        assertThat(state.focused()).isEqualTo(P1);
        assertThat(state.runs()).isEmpty();
        assertThat(effects).startsWith(new Effect.CancelSql(1L), new Effect.ReleaseResult(1L), new Effect.ForgetPanel(P2));
    }

    /// Closing the only panel is refused with a warning, one panel kept.
    @Test
    void theLastPanelStays() {
        apply(new Intent.ClosePanel(P2));
        apply(new Intent.ClosePanel(P1));
        assertThat(state.panels()).hasSize(1);
        assertThat(state.notice().level()).isEqualTo(Notice.Level.WARN);
    }

    // ---- approvals

    /// An answered approval leaves the pending list and is resolved with the answer once; answering it again emits nothing.
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

    /// An approval request brings the Approvals tab forward; while a card still waits it stays; answering the last brings the
    /// Session tab forward on the asking session's first panel.
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

    /// The last card answered while the user is on another tab leaves that tab, and a withdrawn card does not switch tabs.
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

    /// A view with the same title from the same session replaces the earlier one in place, keeping the user's tab and raising
    /// its version; a pinned view is not replaced, so a second one is added.
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

    /// A view the user asked for is brought forward again, while a refresh of it updates its content without moving the user.
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

    /// A refresh arriving after the user ran a newer statement in the panel emits nothing and keeps the newer run.
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

    /// Closing the selected view brings the Session tab forward.
    @Test
    void closingTheSelectedViewSelectsTheFirstTabTheSession() {
        apply(new Intent.ShowView("work", "t", new ViewSpec.Markdown("x"), Placement.LOWER));
        apply(new Intent.CloseView(state.views().getFirst().id()));
        assertThat(state.views()).isEmpty();
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);
    }

    // ---- editor

    /// A panel edit that names no panel appends to the focused panel.
    @Test
    void editWithoutPanelTargetsTheFocusedOne() {
        apply(new Intent.EditPanel(null, "SELECT 42", EditMode.APPEND));
        assertThat(effects).containsExactly(new Effect.EditPanelText(P1, "SELECT 42", EditMode.APPEND));
    }

    // ---- development tools

    private ViewSpec.Console console(String title) {
        return (ViewSpec.Console) state.views().stream().filter(v -> v.title().equals(title)).findFirst().orElseThrow().spec();
    }

    /// A Gradle run starts in the session's folder and shows a running console view in front, which a failed finish marks failed.
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

    /// Running the same command again cancels and forgets the running one, and one console view, under a new run id for the
    /// same panel, stays in front.
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

    /// Closing a running console's view cancels its command and forgets its output, leaving no view.
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

    /// Text sent for a panel closed since goes to the focused panel, starting that panel's session.
    @Test
    void textForAPanelClosedSinceGoesToTheFocusedPanel() {
        apply(new Intent.RunText(new PanelId("closed-since"), "these tests failed", Target.CLAUDE));
        assertThat(effects).contains(new Effect.StartClaude(state.session("work").orElseThrow().spec()));
    }

    /// A prompt that names no panel goes to the focused panel's session, starting it.
    @Test
    void promptWithoutPanelGoesToTheFocusedPanelsSession() {
        apply(new Intent.RunText(null, "explain this failure", Target.CLAUDE));
        assertThat(effects).contains(new Effect.StartClaude(state.session("work").orElseThrow().spec()));
    }
}
