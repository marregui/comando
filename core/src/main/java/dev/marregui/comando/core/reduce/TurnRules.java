package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.marregui.comando.core.reduce.Shared.clear;
import static dev.marregui.comando.core.reduce.Shared.notice;
import static dev.marregui.comando.core.reduce.Shared.sessionSpecs;

/**
 * A session's turns: prompts, commands and the checkpoint (the button's, and the automatic one at the threshold),
 * interrupts, a turn's progress and end, and clearing the transcript.
 */
final class TurnRules {

    private TurnRules() {
    }

    /** The message of a turn the user interrupted, as the stream mapper reports it. */
    static final String INTERRUPTED = "interrupted";

    static Transition runPrompt(Clock clock, AppState s, PanelState panel, String prompt) {
        String subject = Notice.panel(panel.id().value());
        if (panel.session().isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Panel " + panel.title()
                    + " has no session: pick one from its header, or add one with Session settings… (⌘⇧K)", subject));
        }
        Optional<SessionState> found = s.session(panel.session());
        if (found.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No session called " + panel.session(), subject));
        }
        SessionState session = found.get();
        if (session.status() == Status.CLOSING) {
            // the old process still reads stdin until it exits; a start now would reuse it and lose the prompt
            return Transition.of(notice(clock, s, Notice.Level.WARN, "Session " + session.name() + " is stopping; send the prompt again once it has stopped",
                    subject));
        }
        List<Effect> effects = new ArrayList<>();
        if (!session.status().isUsable()) {
            effects.add(new Effect.StartClaude(session.spec()));
            session = session.withStatus(Status.OPENING, "");
        }
        boolean checkpoint = Reducer.isCheckpoint(prompt, session.spec().checkpoint());
        String carried = session.spec().carried();
        // a command (the checkpoint, /compact) leaves the carried summary for the next prompt that is not one
        boolean carries = !carried.isEmpty() && !prompt.strip().startsWith("/");
        effects.add(new Effect.SendToClaude(session.name(), prompt, panel.title(), carries ? Reducer.preface(carried) : ""));
        long now = clock.millis();
        session = session.withTurns(session.pendingTurns() + 1, now).withActivity("sending", now);
        if (checkpoint) {
            // typed, sent by the button or by another session alike: the session is no longer due, and the automatic
            // checkpoint waits for the context to drop below its threshold again; the session is locked until the
            // command's turn, and every turn queued before it, has ended (record 130)
            session = session.withReflection(Reflection.checkpointed(session.contextTokens(), session.contextWindow()))
                    .withCheckpointArmed(false)
                    .withCheckpointTurns(session.pendingTurns());
        } else {
            // a prompt after the checkpoint: the user goes on in this conversation, which is not compacted
            session = session.withCheckpointCompacts(false);
        }
        if (carries) {
            session = session.withSpec(session.spec().withCarried(""));
        }
        AppState next = clear(s.withSession(session), subject);
        if (carries) {
            effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        }
        // the Session tab follows a prompt run from the panel the user looks at; one another session sends to a panel
        // in the background leaves the user's lower panel alone
        if (panel.id().equals(s.focused())) {
            next = next.withLowerTab(LowerTab.SESSION);
        }
        return new Transition(next, effects);
    }

    /**
     * The Checkpoint button, and the automatic checkpoint at the threshold: the checkpoint command runs from the
     * session's panel and, once its turn has finished, the conversation is compacted (see {@link #turnEnded}). A summary
     * still carried, from a checkpoint that cleared the conversation (record 86), means the conversation has had no
     * prompt since its clear: there is nothing new to compact, and the command runs alone.
     */
    static Transition checkpoint(Clock clock, AppState s, String name) {
        Optional<SessionState> found = s.session(name);
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        Transition t = sessionCommand(clock, s, name, found.get().spec().checkpoint());
        boolean sent = t.effects().stream().anyMatch(e -> e instanceof Effect.SendToClaude);
        if (!sent || !found.get().spec().carried().isEmpty()) {
            return t;
        }
        return t.state().session(name)
                .map(x -> new Transition(t.state().withSession(x.withCheckpointCompacts(true)), t.effects()))
                .orElse(t);
    }

    /**
     * Whether {@code i} acts on a session whose checkpoint runs (record 130): a run from one of its panels, the
     * checkpoint or another command, a fresh start, its settings, model or effort, removing it or its own panel,
     * clearing its Session tab. Refused without a notice: the window offers none of them meanwhile. An interrupt, a
     * stop and a prompt another session sends ({@link Intent.PromptSession}, which queues) go through.
     */
    static boolean refused(final AppState s, final Intent i) {
        return switch (i) {
            case Intent.RunText r -> s.panel(r.panel() != null && s.panel(r.panel()).isPresent() ? r.panel()
                    : s.focused()).map(p -> locked(s, p.session())).orElse(false);
            case Intent.Checkpoint c -> locked(s, c.name());
            case Intent.SessionCommand c -> locked(s, c.name());
            case Intent.StartFresh f -> locked(s, f.name());
            case Intent.SaveSession v -> locked(s, v.previousName());
            case Intent.SetSessionModel m -> locked(s, m.name());
            case Intent.SetSessionEffort e -> locked(s, e.name());
            case Intent.RemoveSession r -> locked(s, r.name());
            // a session's own panel goes with the session (record 88); a document attached to it goes alone
            case Intent.RemovePanel r -> s.panel(r.panel()).or(() -> s.closedPanel(r.panel()))
                    .filter(p -> !p.document()).map(p -> locked(s, p.session())).orElse(false);
            case Intent.ClearTranscript c -> locked(s, c.name());
            default -> false;
        };
    }

    private static boolean locked(final AppState s, final String name) {
        return !name.isEmpty() && s.session(name).map(SessionState::isCheckpointing).orElse(false);
    }

    /**
     * A command (the checkpoint, {@code /compact}) runs as a prompt from the session's panel, as if the user had typed
     * it there.
     */
    static Transition sessionCommand(Clock clock, AppState s, String name, String command) {
        Optional<SessionState> found = s.session(name);
        if (found.isEmpty() || command.isBlank() || found.get().status() == Status.CLOSING) {
            return Transition.of(s);
        }
        // its own panel, else a document attached to it, else its own panel closed: the command shows in a panel
        Optional<PanelState> panel = s.panelsBoundToSession(name).stream().findFirst().or(() -> s.closedPanelOfSession(name));
        if (panel.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Session " + name + " has no panel; open it to send "
                    + command, Notice.session(name)));
        }
        return runPrompt(clock, s, panel.get(), command);
    }

    /**
     * Empties the session's Session tab; the conversation itself is untouched. Refused while a turn runs: its blocks
     * are still being written.
     */
    static Transition clearTranscript(Clock clock, AppState s, String name) {
        Optional<SessionState> found = s.session(name);
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        if (found.get().isBusy()) {
            return Transition.of(notice(clock, s, Notice.Level.WARN, "Session " + name + " is working; clear it once the turn has ended",
                    Notice.session(name)));
        }
        return new Transition(s, List.of(new Effect.ClearTranscript(name)));
    }

    /** The chip and the sidebar say "interrupting" at once; the next activity or the turn's end replaces it. */
    static Transition interruptSession(Clock clock, AppState s, String name) {
        return s.session(name)
                .filter(SessionState::isBusy)
                .map(x -> new Transition(s.withSession(x.withActivity("interrupting", clock.millis())),
                        List.<Effect>of(new Effect.InterruptClaude(name))))
                .orElse(Transition.of(s));
    }

    /**
     * A turn ended. The checkpoint's turn, once finished, sends {@code /compact}; a turn that leaves the context at or
     * above {@code threshold} (0 or below: never) runs the checkpoint, once per crossing (record 129).
     */
    static Transition turnEnded(Clock clock, AppState s, Intent.SessionTurnEnded i, long threshold) {
        Optional<SessionState> found = s.session(i.name());
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        // what the turn ran at no longer holds: the next turn starts at the session's own model and effort
        found = Optional.of(found.get().withInfo(found.get().info().turnEnded()));
        s = s.withSession(found.get());
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
        // the checkpoint has finished once nothing runs, is queued or was handed to the background; a result that made
        // no model call and carries no text finished nothing
        boolean checkpointDone = x.checkpointCompacts() && !x.isBusy() && !x.isAwaitingHandOff() && (ran || !i.result().isBlank());
        boolean compacts = checkpointDone && !i.error() && !i.result().isBlank();
        if (checkpointDone) {
            x = x.withCheckpointCompacts(false);
        }
        // the turns that ended count down to the checkpoint's own, interrupted or failed alike; with nothing left to
        // run, none is under way (record 130)
        final int ended = Math.max(0, found.get().pendingTurns() - x.pendingTurns());
        x = x.withCheckpointTurns(x.isBusy() ? Math.max(0, x.checkpointTurns() - ended) : 0);
        // the context fell below the threshold (a compaction, a new conversation): the next crossing checkpoints again
        boolean above = threshold > 0 && x.contextTokens() >= threshold;
        if (threshold > 0 && !above) {
            x = x.withCheckpointArmed(true);
        }
        // at the threshold, the session's own turn finished: nothing queued, no background work out, no checkpoint
        // under way, and a checkpoint command to run
        boolean automatic = above && x.checkpointArmed() && !checkpointDone && !x.checkpointCompacts() && !i.error() && !x.isBusy()
                && !x.isAwaitingHandOff() && x.status().isUsable() && !x.spec().checkpoint().isBlank();
        next = next.withSession(x);
        if (interrupted) {
            next = notice(clock, next, Notice.Level.INFO, i.name() + ": interrupted", Notice.session(i.name()));
        } else if (i.error()) {
            next = notice(clock, next, Notice.Level.ERROR, i.name() + ": " + i.message(), Notice.session(i.name()));
        } else if (checkpointDone && !compacts) {
            next = notice(clock, next, Notice.Level.WARN, i.name() + ": the checkpoint ended without a summary; the conversation is kept",
                    Notice.session(i.name()));
        } else {
            next = clear(next, Notice.session(i.name()));
        }
        // the compaction, or the automatic checkpoint, runs from the session's panel as the Compact and Checkpoint
        // buttons do: the Session tab shows it
        Transition follow = compacts ? sessionCommand(clock, next, i.name(), SessionSpec.COMPACT_COMMAND)
                : automatic ? checkpoint(clock, next, i.name()) : null;
        if (follow != null) {
            next = follow.state();
            effects.addAll(follow.effects());
            x = next.session(i.name()).orElse(x);
            if (automatic) {
                // sent or refused (no panel to send it from), this crossing has had its checkpoint
                x = x.withCheckpointArmed(false);
                next = next.withSession(x);
            }
            if (compacts) {
                // the compaction is the checkpoint's last turn: the session stays locked until it ends
                x = x.withCheckpointTurns(x.pendingTurns());
                next = next.withSession(x);
            }
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
                next = ApprovalRules.withdrawApprovals(next, i.name(), "the turn ended", effects,
                        a -> a.kind() == ApprovalKind.SQL_WRITE);
            }
        }
        return new Transition(next, effects);
    }

    /**
     * A prompt to a session in its own panel: appended there and run, as {@link Intent.EditPanel} and
     * {@link Intent.RunText} would.
     */
    static Transition promptSession(Clock clock, AppState s, Intent.PromptSession i) {
        Optional<PanelState> home = s.homePanel(i.session());
        if (home.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Session " + i.session() + " has no open panel for the prompt from " + i.from(),
                    Notice.session(i.session())));
        }
        PanelId panel = home.get().id();
        Transition edited = PanelRules.editPanel(clock, s, new Intent.EditPanel(panel, i.text(), EditMode.APPEND, i.from()));
        Transition ran = QueryRules.runText(clock, edited.state(), new Intent.RunText(panel, i.text(), Target.CLAUDE));
        List<Effect> effects = new ArrayList<>(edited.effects());
        effects.addAll(ran.effects());
        return new Transition(ran.state(), effects);
    }

    static Transition sessionActivity(Clock clock, AppState s, Intent.SessionActivity i) {
        return Transition.of(s.withSession(i.name(), x -> x.withActivity(i.activity(), clock.millis())));
    }

    static Transition sessionTasksChanged(Clock clock, AppState s, Intent.SessionTasksChanged i) {
        return Transition.of(s.withSession(i.name(), x -> x.withTasks(i.open(), clock.millis())));
    }

    static Transition sessionTurnRunning(Clock clock, AppState s, Intent.SessionTurnRunning i) {
        return Transition.of(s.withSession(i.name(), x -> x.isBusy() ? x : x.withTurns(1, clock.millis())));
    }
}
