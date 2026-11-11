package dev.marregui.comando.core.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * Applies one intent to the state and lists the effects that follow. Pure apart from the clock, which only stamps
 * notices and activity, the home folder, where a file link or a Gradle run without a session starts, and the checkpoint
 * threshold, a setting of the window in percent of a session's context window: the same state, intent, time, home and
 * threshold always give the same transition.
 *
 * <p>Request identity drops stale results: every SQL run gets an id from the state's counter, a panel holds one run,
 * and an event for an id that is no longer a panel's run is dropped.</p>
 *
 * <p>Each intent goes to the rules of its domain in this package; what follows any intent (the repository watch, the
 * desktop error) is added here.</p>
 */
public final class Reducer {

    /**
     * The prompt an automatic checkpoint sends from the session's panel once its compaction has ended normally, so the
     * session carries on with the work the checkpoint paused (Miguel, 2026-11-01: after an automatic checkpoint, not the
     * button's). Generic: comando knows no kit.
     */
    public static final String CONTINUE_PROMPT = "Continue the work the checkpoint paused, from the summary above: its next step first.";

    private final Clock clock;
    /** The threshold of a reducer whose sessions never fall due: tests and tools only. */
    private static final IntSupplier NEVER = () -> 0;

    /**
     * The checkpoint threshold in force, in percent of each session's context window, read at each turn's end, where
     * the session falls due and its automatic checkpoint runs, and at each model call's context (record 129). Read
     * through {@link Reflection#clamp}, so the window's setting is never below 10 % (D3: no off); only {@link #NEVER}
     * makes neither happen.
     */
    private final IntSupplier checkpointThreshold;

    /** A reducer whose sessions never fall due nor checkpoint by themselves: tests and tools. */
    public Reducer(final Clock clock) {
        this.clock = clock;
        this.checkpointThreshold = NEVER;
    }

    public Reducer(final Clock clock, final IntSupplier checkpointThreshold) {
        this.clock = clock;
        this.checkpointThreshold = checkpointThreshold;
    }

    /** The threshold this turn's end runs at: the window's setting within 10–100 %, or 0 for a reducer that never checkpoints. */
    private int threshold() {
        return checkpointThreshold == NEVER ? 0 : Reflection.clamp(checkpointThreshold.getAsInt());
    }

    public Transition apply(final AppState s, final Intent intent) {
        return withDesktopError(s, withRepoWatch(s, withUnread(s, reduce(s, intent))));
    }

    /**
     * Whichever intent led here, a session the focused panel is on has been looked at, and one with a turn running has
     * superseded what it finished: neither has anything to read (record 136). The mark lives in the session's spec, so
     * a change of it, set by the rules or cleared here, is saved as it stands now.
     */
    private static Transition withUnread(final AppState before, final Transition t) {
        final String focused = Shared.focusedSession(t.state());
        AppState next = t.state();
        for (final SessionState x : t.state().sessions()) {
            if (x.spec().unread() && (x.isBusy() || x.name().equals(focused))) {
                next = next.withSession(x.withSpec(x.spec().withUnread(0L)));
            }
        }
        if (next == t.state() && unread(before).equals(unread(next))) {
            return t;
        }
        final List<Effect> effects = new ArrayList<>(t.effects());
        effects.removeIf(e -> e instanceof Effect.SaveSessions);
        effects.add(new Effect.SaveSessions(Shared.sessionSpecs(next)));
        return new Transition(next, effects);
    }

    private static List<SessionSpec> unread(final AppState s) {
        return s.sessions().stream().map(SessionState::spec).filter(SessionSpec::unread).toList();
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

    /** Where {@code shown} lands once reduced: where the view it replaces is now, else where it asks (record 141). */
    public static Placement shownAt(final AppState s, final Intent.ShowView shown) {
        return ViewRules.shownAt(s, shown);
    }

    /**
     * Whether {@code intent} acts on a session whose checkpoint runs and is therefore refused (record 130): the
     * reducer drops it, so a caller that is not the window, an agent's action tool, asks first and answers "locked". A
     * checkpoint can begin between the tool's question and the intent's turn in the queue: the reducer then posts a
     * notice naming the agent the intent came from, where the intent names one.
     */
    public static boolean refusedWhileCheckpointing(final AppState s, final Intent intent) {
        return TurnRules.refused(s, intent);
    }

    /**
     * Why the reducer would refuse {@code intent} on {@code s}, in the words of its notice; empty when it would apply
     * it. An agent's action tool asks before it sends, so a refusal is the tool's answer and not only a notice the agent
     * never reads: the checkpoint lock (record 130), a session or panel name taken (record 88), a stopped session that
     * another session would start (record 44), a command with nothing to send, the last panel of the main tabs
     * sent to the side (record 141).
     * The answer holds for {@code s}; intents queued before this one may still change the outcome.
     */
    public static Optional<String> refusal(final AppState s, final Intent intent) {
        if (TurnRules.refused(s, intent)) {
            return Optional.of("the session is running its checkpoint (record 130); only session_interrupt or session_stop act on it until "
                    + "the checkpoint and its compaction end");
        }
        return switch (intent) {
            case Intent.SaveSession i -> SessionRules.saveRefusal(s, i).map(SessionRules.Refusal::reason);
            case Intent.Checkpoint i -> TurnRules.commandRefusal(s, i.name(), s.session(i.name()).map(x -> x.spec().checkpoint()).orElse(""), i.from());
            case Intent.SessionCommand i -> TurnRules.commandRefusal(s, i.name(), i.command(), i.from());
            case Intent.RunText i -> QueryRules.promptRefusal(s, i);
            case Intent.PlacePanel i -> PanelRules.placeRefusal(s, i);
            case Intent.ClosePanel i -> PanelRules.closeRefusal(s, i);
            case Intent.AddPanel _, Intent.PinPanel _, Intent.PlaceView _, Intent.RemovePanel _, Intent.RenamePanel _,
                    Intent.MovePanel _, Intent.ReopenPanel _, Intent.FocusPanel _, Intent.BindConnection _,
                    Intent.BindSession _, Intent.OpenSession _, Intent.RefreshSql _, Intent.RefreshView _,
                    Intent.CancelRun _, Intent.SqlStarted _, Intent.SqlRows _, Intent.SqlCompleted _,
                    Intent.SqlFailed _, Intent.SqlCancelled _, Intent.SqlWarning _, Intent.SaveConnection _,
                    Intent.RemoveConnection _, Intent.AddFolder _, Intent.SaveFolders _, Intent.FoldersRead _,
                    Intent.LogbookRead _, Intent.OpenConnection _, Intent.CloseConnection _, Intent.TestConnection _,
                    Intent.ConnectionOpened _, Intent.ConnectionClosed _, Intent.ConnectionFailed _,
                    Intent.ExploreSchema _, Intent.SchemaChanged _, Intent.ConnectionLost _, Intent.ConnectionTested _,
                    Intent.RemoveSession _, Intent.ImportSessions _, Intent.StartSession _, Intent.StopSession _,
                    Intent.StartFresh _, Intent.OrderSessions _, Intent.InterruptSession _, Intent.ClearTranscript _,
                    Intent.SetSessionModel _, Intent.SetSessionEffort _, Intent.SessionStarted _, Intent.SessionRanAt _,
                    Intent.SessionInit _, Intent.SessionActivity _, Intent.SessionTasksChanged _,
                    Intent.SessionTurnRunning _, Intent.SessionTurnEnded _, Intent.SessionExited _,
                    Intent.SessionBranch _, Intent.SessionSubjectRead _, Intent.SessionContext _,
                    Intent.ApprovalRequested _, Intent.ApprovalAnswered _, Intent.ApprovalWithdrawn _,
                    Intent.ShowView _, Intent.CloseView _,
                    Intent.PinView _, Intent.SelectLowerTab _, Intent.Arrange _, Intent.SessionLayout _,
                    Intent.SessionFocus _, Intent.PromptSession _, Intent.EditPanel _, Intent.OpenFile _,
                    Intent.OpenSubagent _, Intent.RunGradle _, Intent.CommandFinished _, Intent.CancelCommand _,
                    Intent.BrowseFolders _, Intent.WatchFolders _, Intent.FolderLoaded _, Intent.ReposLoaded _,
                    Intent.OpenChange _, Intent.ChangeRead _, Intent.DeleteFiles _, Intent.RollBack _,
                    Intent.RemoveFiles _, Intent.Unstage _, Intent.InstancesChanged _, Intent.PlanUsageRead _,
                    Intent.Notify _, Intent.DismissNotice _ -> Optional.empty();
        };
    }

    private Transition reduce(final AppState s, final Intent intent) {
        // a session whose checkpoint runs takes only an interrupt or a stop from the window (record 130); an agent's
        // intent that its tool let through before the lock began is told, as the agent was answered "sent"
        if (TurnRules.refused(s, intent)) {
            final String from = TurnRules.from(intent);
            return Transition.of(from.isEmpty() ? s : Shared.notice(clock, s, Notice.Level.WARN, from + ": "
                    + intent.getClass().getSimpleName() + " not applied: the session is running its checkpoint (record 130)", ""));
        }
        return switch (intent) {
            case Intent.AddPanel i -> PanelRules.addPanel(s, i);
            case Intent.ClosePanel i -> PanelRules.closePanel(clock, s, i.panel());
            case Intent.RemovePanel i -> PanelRules.removePanel(clock, s, i.panel());
            case Intent.RenamePanel i -> PanelRules.renamePanel(clock, s, i);
            case Intent.MovePanel i -> PanelRules.movePanel(s, i);
            case Intent.PlacePanel i -> PanelRules.placePanel(clock, s, i);
            case Intent.PinPanel i -> PanelRules.pinPanel(s, i);
            case Intent.PlaceView i -> ViewRules.placeView(s, i);
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
            case Intent.SessionSubjectRead i -> SessionRules.sessionSubject(s, i);
            case Intent.SessionContext i -> SessionRules.sessionContext(s, i, threshold());
            case Intent.OpenSubagent i -> SessionRules.openSubagent(clock, s, i);

            case Intent.PromptSession i -> TurnRules.promptSession(clock, s, i);
            case Intent.Checkpoint i -> TurnRules.checkpoint(clock, s, i.name(), false, i.from());
            case Intent.SessionCommand i -> TurnRules.sessionCommand(clock, s, i.name(), i.command(), i.from());
            case Intent.InterruptSession i -> TurnRules.interruptSession(clock, s, i.name());
            case Intent.ClearTranscript i -> TurnRules.clearTranscript(clock, s, i.name());
            case Intent.SessionActivity i -> TurnRules.sessionActivity(clock, s, i);
            case Intent.SessionTasksChanged i -> TurnRules.sessionTasksChanged(clock, s, i);
            case Intent.SessionTurnRunning i -> TurnRules.sessionTurnRunning(clock, s, i);
            case Intent.SessionTurnEnded i -> TurnRules.turnEnded(clock, s, i, threshold());

            case Intent.ApprovalRequested i -> ApprovalRules.approvalRequested(clock, s, i.approval());
            case Intent.ApprovalAnswered i -> ApprovalRules.approvalAnswered(s, i);
            case Intent.ApprovalWithdrawn i -> ApprovalRules.approvalWithdrawn(clock, s, i);

            case Intent.AddFolder i -> FolderRules.addFolder(clock, s, i.spec());
            case Intent.SaveFolders i -> FolderRules.saveFolders(clock, s, i);
            case Intent.FoldersRead i -> FolderRules.foldersRead(s, i);
            case Intent.LogbookRead i -> Transition.of(s.withLogbook(i.logbook()));
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
