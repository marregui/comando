package dev.marregui.comando.core.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * Applies one intent to the state and lists the effects that follow. Pure apart from the clock, which only stamps
 * notices and activity, the home folder, where a file link or a Gradle run without a session starts, and the checkpoint
 * threshold, a setting of the window: the same state, intent, time, home and threshold always give the same
 * transition.
 *
 * <p>Request identity drops stale results: every SQL run gets an id from the state's counter, a panel holds one run,
 * and an event for an id that is no longer a panel's run is dropped.</p>
 *
 * <p>Each intent goes to the rules of its domain in this package; what follows any intent (the repository watch, the
 * desktop error) is added here.</p>
 */
public final class Reducer {

    /**
     * The context, in tokens, at or above which a turn's end runs the session's checkpoint, then {@code /compact}
     * (record 129), unless the setting says otherwise.
     */
    public static final long CHECKPOINT_THRESHOLD_TOKENS = 400_000L;

    private final Clock clock;
    /** The checkpoint threshold in force, read at each turn's end; 0 or below runs no automatic checkpoint. */
    private final LongSupplier checkpointThreshold;

    /** A reducer that runs no automatic checkpoint: tests and tools. */
    public Reducer(final Clock clock) {
        this(clock, () -> 0L);
    }

    public Reducer(final Clock clock, final LongSupplier checkpointThreshold) {
        this.clock = clock;
        this.checkpointThreshold = checkpointThreshold;
    }

    public Transition apply(final AppState s, final Intent intent) {
        return withDesktopError(s, withRepoWatch(s, reduce(s, intent)));
    }

    /**
     * The git repositories of every folder are read, often while the Changes view is open: opening it, closing it, or
     * changing the folders tells the tools which to read and how often.
     */
    private static Transition withRepoWatch(final AppState before, final Transition t) {
        final boolean was = showsChanges(before);
        final boolean is = showsChanges(t.state());
        if (was == is && before.folders().equals(t.state().folders())) {
            return t;
        }
        final List<Effect> effects = new ArrayList<>(t.effects());
        effects.add(new Effect.WatchRepos(t.state().folders(), is));
        return new Transition(t.state(), effects);
    }

    private static boolean showsChanges(final AppState s) {
        for (final ViewState v : s.views()) {
            if (v.spec() instanceof ViewSpec.Changes) {
                return true;
            }
        }
        return false;
    }

    /**
     * A new error notice also goes to the desktop, which shows it only while the window is in the background, so
     * an error that arrives while the user works elsewhere is not lost.
     */
    private static Transition withDesktopError(final AppState before, final Transition t) {
        final Notice n = t.state().notice();
        if (n.level() != Notice.Level.ERROR || n.isEmpty() || n.equals(before.notice())
                || t.effects().stream().anyMatch(e -> e instanceof Effect.DesktopNotify)) {
            return t;
        }
        final List<Effect> effects = new ArrayList<>(t.effects());
        effects.add(new Effect.DesktopNotify("comando", n.text()));
        return new Transition(t.state(), effects);
    }

    /**
     * Whether {@code intent} acts on a session whose checkpoint runs and is therefore refused (record 130): the
     * reducer drops it silently, so a caller that is not the window, an agent's action tool, asks first and answers
     * "locked".
     */
    public static boolean refusedWhileCheckpointing(final AppState s, final Intent intent) {
        return TurnRules.refused(s, intent);
    }

    private Transition reduce(final AppState s, final Intent intent) {
        // a session whose checkpoint runs takes only an interrupt or a stop from the window (record 130)
        if (TurnRules.refused(s, intent)) {
            return Transition.of(s);
        }
        return switch (intent) {
            case Intent.AddPanel i -> PanelRules.addPanel(s, i);
            case Intent.ClosePanel i -> PanelRules.closePanel(clock, s, i.panel());
            case Intent.RemovePanel i -> PanelRules.removePanel(clock, s, i.panel());
            case Intent.RenamePanel i -> PanelRules.renamePanel(clock, s, i);
            case Intent.MovePanel i -> PanelRules.movePanel(s, i);
            case Intent.ReopenPanel i -> PanelRules.reopenPanel(s, i);
            case Intent.FocusPanel i -> PanelRules.focusPanel(s, i);
            case Intent.BindConnection i -> PanelRules.bindConnection(clock, s, i);
            case Intent.BindSession i -> PanelRules.bindSession(clock, s, i);
            case Intent.OpenSession i -> PanelRules.openSession(clock, s, i);
            case Intent.EditPanel i -> PanelRules.editPanel(clock, s, i);

            case Intent.RunText i -> QueryRules.runText(clock, s, i);
            case Intent.RefreshSql i -> QueryRules.refreshSql(clock, s, i);
            case Intent.CancelRun i -> QueryRules.cancelRun(clock, s, i.panel());

            case Intent.SqlStarted i -> QueryRules.sqlStarted(clock, s, i);
            case Intent.SqlRows i -> QueryRules.sqlRows(s, i);
            case Intent.SqlCompleted i -> QueryRules.sqlCompleted(clock, s, i);
            case Intent.SqlFailed i -> QueryRules.sqlFailed(clock, s, i);
            case Intent.SqlCancelled i -> QueryRules.sqlCancelled(s, i);
            case Intent.SqlWarning i -> QueryRules.sqlWarning(clock, s, i);

            case Intent.SaveConnection i -> QueryRules.saveConnection(clock, s, i);
            case Intent.RemoveConnection i -> QueryRules.removeConnection(s, i.name());
            case Intent.OpenConnection i -> QueryRules.openConnection(s, i.name());
            case Intent.CloseConnection i -> QueryRules.closeConnection(s, i.name());
            case Intent.TestConnection i -> QueryRules.testConnection(clock, s, i);
            case Intent.ConnectionOpened i -> QueryRules.connectionOpened(s, i);
            case Intent.ExploreSchema i -> QueryRules.exploreSchema(clock, s, i);
            case Intent.ConnectionClosed i -> QueryRules.connectionStatus(s, i.name(), Status.CLOSED, "");
            case Intent.ConnectionFailed i -> QueryRules.connectionFailed(clock, s, i);
            case Intent.ConnectionLost i -> QueryRules.connectionLost(clock, s, i);
            case Intent.SchemaChanged i -> QueryRules.schemaChanged(s, i);
            case Intent.ConnectionTested i -> QueryRules.connectionTested(clock, s, i);

            case Intent.SaveSession i -> SessionRules.saveSession(clock, s, i);
            case Intent.RemoveSession i -> SessionRules.removeSession(clock, s, i.name());
            case Intent.ImportSessions i -> SessionRules.importSessions(clock, s, i.specs());
            case Intent.StartSession i -> SessionRules.startSession(s, i.name());
            case Intent.StopSession i -> SessionRules.stopSession(s, i.name());
            case Intent.StartFresh i -> SessionRules.startFresh(clock, s, i.name());
            case Intent.OrderSessions i -> SessionRules.orderSessions(s, i.names());
            case Intent.SetSessionModel i -> SessionRules.sessionSetting(clock, s, i.name(), i.model(), true);
            case Intent.SetSessionEffort i -> SessionRules.sessionSetting(clock, s, i.name(), i.effort(), false);
            case Intent.SessionStarted i -> SessionRules.sessionStarted(clock, s, i.name());
            case Intent.SessionInit i -> SessionRules.sessionInit(clock, s, i);
            case Intent.SessionRanAt i -> SessionRules.sessionRanAt(s, i);
            case Intent.SessionExited i -> SessionRules.sessionExited(clock, s, i);
            case Intent.SessionBranch i -> SessionRules.sessionBranch(s, i);
            case Intent.SessionContext i -> SessionRules.sessionContext(s, i);
            case Intent.OpenSubagent i -> SessionRules.openSubagent(clock, s, i);

            case Intent.PromptSession i -> TurnRules.promptSession(clock, s, i);
            case Intent.Checkpoint i -> TurnRules.checkpoint(clock, s, i.name());
            case Intent.SessionCommand i -> TurnRules.sessionCommand(clock, s, i.name(), i.command());
            case Intent.InterruptSession i -> TurnRules.interruptSession(clock, s, i.name());
            case Intent.ClearTranscript i -> TurnRules.clearTranscript(clock, s, i.name());
            case Intent.SessionActivity i -> TurnRules.sessionActivity(clock, s, i);
            case Intent.SessionTasksChanged i -> TurnRules.sessionTasksChanged(clock, s, i);
            case Intent.SessionTurnRunning i -> TurnRules.sessionTurnRunning(clock, s, i);
            case Intent.SessionTurnEnded i -> TurnRules.turnEnded(clock, s, i, checkpointThreshold.getAsLong());

            case Intent.ApprovalRequested i -> ApprovalRules.approvalRequested(clock, s, i.approval());
            case Intent.ApprovalAnswered i -> ApprovalRules.approvalAnswered(s, i);
            case Intent.ApprovalWithdrawn i -> ApprovalRules.approvalWithdrawn(clock, s, i);

            case Intent.AddFolder i -> FolderRules.addFolder(clock, s, i.spec());
            case Intent.SaveFolders i -> FolderRules.saveFolders(clock, s, i);
            case Intent.FoldersRead i -> FolderRules.foldersRead(s, i);
            case Intent.BrowseFolders i -> FolderRules.browseFolders(s, i);
            case Intent.WatchFolders i -> FolderRules.watchFolders(s, i);
            case Intent.DeleteFiles i -> FolderRules.deleteFiles(clock, s, i);
            case Intent.FolderLoaded i -> FolderRules.folderLoaded(s, i);
            case Intent.ReposLoaded i -> FolderRules.reposLoaded(s, i);
            case Intent.OpenChange i -> FolderRules.openChange(s, i);
            case Intent.ChangeRead i -> FolderRules.changeRead(s, i);
            case Intent.RollBack i -> FolderRules.rollBack(clock, s, i);
            case Intent.RemoveFiles i -> FolderRules.removeFiles(clock, s, i);
            case Intent.Unstage i -> FolderRules.unstage(clock, s, i);
            case Intent.OpenFile i -> FolderRules.openFile(s, i);

            case Intent.ShowView i -> ViewRules.showView(clock, s, i);
            case Intent.SessionLayout i -> ViewRules.sessionLayout(clock, s, i);
            case Intent.SessionFocus i -> ViewRules.sessionFocus(s, i);
            case Intent.RefreshView i -> ViewRules.refreshView(s, i);
            case Intent.CloseView i -> ViewRules.closeView(s, i.id());
            case Intent.PinView i -> ViewRules.pinView(s, i);
            case Intent.SelectLowerTab i -> ViewRules.selectLowerTab(s, i);
            case Intent.Arrange i -> ViewRules.arrange(s, i);
            case Intent.PlanUsageRead i -> ViewRules.planUsageRead(s, i);
            case Intent.InstancesChanged i -> Transition.of(s.withInstances(i.instances()));
            case Intent.RunGradle i -> ViewRules.runGradle(clock, s, i);
            case Intent.CommandFinished i -> ViewRules.commandFinished(s, i);
            case Intent.CancelCommand i -> ViewRules.cancelCommand(s, i);
            case Intent.Notify i -> ViewRules.showNotice(clock, s, i);
            case Intent.DismissNotice i -> ViewRules.dismissNotice(s, i);
        };
    }

    /** What goes to Claude in front of the first prompt after a checkpoint cleared the conversation. */
    static String preface(final String carried) {
        return "Context carried over from the previous conversation of this session: the summary its checkpoint wrote before "
                + "the conversation was cleared.\n\n" + carried.strip() + "\n\n---\n\n";
    }

    /** The prompt is the checkpoint command, alone or followed by arguments ({@code /reflect now}). */
    static boolean isCheckpoint(final String prompt, final String checkpoint) {
        final String p = prompt.strip();
        final String c = checkpoint.strip();
        return !c.isEmpty() && p.startsWith(c) && (p.length() == c.length() || Character.isWhitespace(p.charAt(c.length())));
    }
}
