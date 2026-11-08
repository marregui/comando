package dev.marregui.comando.core.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

import static dev.marregui.comando.core.reduce.Shared.clear;
import static dev.marregui.comando.core.reduce.Shared.finished;
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

    /** The user's own prompt from {@code panel}. */
    static Transition runPrompt(final Clock clock, final AppState s, final PanelState panel, final String prompt) {
        return runPrompt(clock, s, panel, prompt, "");
    }

    /**
     * A prompt from {@code panel}, sent by {@code from} (empty for the user). A session that is not running starts for
     * it; a stopped session starts only for the user or one of its creators (record 44: stopping is the user's circuit
     * breaker, whichever tool reaches the session).
     */
    static Transition runPrompt(final Clock clock, final AppState s, final PanelState panel, final String prompt, final String from) {
        final String subject = Notice.panel(panel.id().value());
        if (panel.session().isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Panel " + panel.title()
                    + " has no session: pick one from its header, or add one with Session settings… (⌘⇧K)", subject));
        }
        final Optional<SessionState> found = s.session(panel.session());
        if (found.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No session called " + panel.session(), subject));
        }
        SessionState session = found.get();
        if (session.status() == Status.CLOSING) {
            // the old process still reads stdin until it exits; a start now would reuse it and lose the prompt
            return Transition.of(notice(clock, s, Notice.Level.WARN, "Session " + session.name() + " is stopping; send the prompt again once it has stopped",
                    subject));
        }
        final List<Effect> effects = new ArrayList<>();
        if (!session.status().isUsable()) {
            final Optional<String> breaker = startRefusal(s, session, from);
            if (breaker.isPresent()) {
                return Transition.of(notice(clock, s, Notice.Level.WARN, breaker.get(), subject));
            }
            effects.add(new Effect.StartClaude(session.spec()));
            session = session.withStatus(Status.OPENING, "");
        }
        final boolean checkpoint = Reducer.isCheckpoint(prompt, session.spec().checkpoint());
        final String carried = session.spec().carried();
        // a command (the checkpoint, /compact) leaves the carried summary for the next prompt that is not one
        final boolean carries = !carried.isEmpty() && !prompt.strip().startsWith("/");
        effects.add(new Effect.SendToClaude(session.name(), prompt, panel.title(), carries ? Reducer.preface(carried) : ""));
        final long now = clock.millis();
        session = session.withTurns(session.pendingTurns() + 1, now).withActivity("sending", now);
        if (checkpoint) {
            // typed, sent by the button or by another session alike: the session is no longer due, and the automatic
            // checkpoint waits for the context to drop below its threshold again; the session is locked until the
            // command's turn, and every turn queued before it, has ended (record 130)
            session = session.withReflection(Reflection.checkpointed())
                    .withCheckpointArmed(false)
                    .withCheckpointTurns(session.pendingTurns());
        } else {
            // a prompt after the checkpoint: the user goes on in this conversation, which is not compacted
            session = session.withCheckpointCompacts(false);
        }
        // a prompt sent or queued meanwhile runs in place of the carry-on prompt; the automatic checkpoint, and its
        // /compact, set it again (see checkpoint and turnEnded)
        session = session.withCheckpointContinues(false);
        if (!prompt.equals(Reducer.CONTINUE_PROMPT)) {
            // someone's prompt, not the carry-on: the automatic checkpoint may follow a turn again
            session = session.withCarriedOn(false);
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
    static Transition checkpoint(final Clock clock, final AppState s, final String name) {
        return checkpoint(clock, s, name, false, "");
    }

    /**
     * The checkpoint; {@code automatic} adds {@link SessionSpec#AUTO_ARGUMENT} to the command, so a command that knows it
     * asks the user nothing and holds its decisions back (record 131), and, once the compaction that follows has ended
     * normally, the session is prompted to carry on ({@link Reducer#CONTINUE_PROMPT}, record 129's amendment).
     */
    static Transition checkpoint(final Clock clock, final AppState s, final String name, final boolean automatic, final String from) {
        final Optional<SessionState> found = s.session(name);
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        final String command = found.get().spec().checkpoint();
        final Transition t = sessionCommand(clock, s, name, automatic && !command.isBlank() ? command + ' ' + SessionSpec.AUTO_ARGUMENT : command, from);
        final boolean sent = t.effects().stream().anyMatch(e -> e instanceof Effect.SendToClaude);
        if (!sent || !found.get().spec().carried().isEmpty()) {
            return t;
        }
        return t.state().session(name)
                .map(x -> new Transition(t.state().withSession(x.withCheckpointCompacts(true).withCheckpointContinues(automatic)), t.effects()))
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
            // Settle sends the checkpoint command to a session: refused while that session's checkpoint runs
            case Intent.SettleDecision d -> DecisionRules.target(s, d.id()).map(n -> locked(s, n)).orElse(false);
            default -> false;
        };
    }

    /**
     * Why a prompt from {@code from} must not start {@code session}, which is not running: only the user or one of its
     * creators starts a session again (record 44); empty when it may.
     */
    private static Optional<String> startRefusal(final AppState s, final SessionState session, final String from) {
        if (from.isEmpty() || session.status().isUsable() || s.creators(session.name()).contains(from)) {
            return Optional.empty();
        }
        return Optional.of(from + " did not create session " + session.name() + ", which is not running; only the user or its creator starts it "
                + "(record 44)");
    }

    /**
     * Why a prompt from {@code from} in {@code panel} is not sent, as {@link #runPrompt} says it; empty when it would be.
     */
    static Optional<String> promptRefusal(final AppState s, final PanelState panel, final String from) {
        if (panel.session().isEmpty()) {
            return Optional.of("Panel " + panel.title() + " has no session");
        }
        final Optional<SessionState> found = s.session(panel.session());
        if (found.isEmpty()) {
            return Optional.of("No session called " + panel.session());
        }
        if (found.get().status() == Status.CLOSING) {
            return Optional.of("Session " + found.get().name() + " is stopping");
        }
        return startRefusal(s, found.get(), from);
    }

    /**
     * Why {@code command} from {@code from} does not reach session {@code name}, as {@link #sessionCommand} decides it;
     * empty when it would be sent.
     */
    static Optional<String> commandRefusal(final AppState s, final String name, final String command, final String from) {
        final Optional<SessionState> found = s.session(name);
        if (found.isEmpty()) {
            return Optional.of("No session called " + name);
        }
        if (command.isBlank()) {
            return Optional.of("Session " + name + " has no command to send: its checkpoint command is empty");
        }
        if (found.get().status() == Status.CLOSING) {
            return Optional.of("Session " + name + " is stopping");
        }
        final Optional<PanelState> panel = s.panelsBoundToSession(name).stream().findFirst().or(() -> s.closedPanelOfSession(name));
        if (panel.isEmpty()) {
            return Optional.of("Session " + name + " has no panel; open it to send " + command);
        }
        return promptRefusal(s, panel.get(), from);
    }

    /** The agent an intent came from through an action tool; empty for the user's gestures and every other intent. */
    static String from(final Intent i) {
        return switch (i) {
            case Intent.RunText r -> r.from();
            case Intent.Checkpoint c -> c.from();
            case Intent.SessionCommand c -> c.from();
            case Intent.SettleDecision d -> d.from();
            case Intent.StartFresh f -> f.from();
            case Intent.SaveSession v -> v.from();
            case Intent.SetSessionModel m -> m.from();
            case Intent.SetSessionEffort e -> e.from();
            case Intent.RemoveSession r -> r.from();
            case Intent.RemovePanel r -> r.from();
            case Intent.ClearTranscript c -> c.from();
            default -> "";
        };
    }

    private static boolean locked(final AppState s, final String name) {
        return !name.isEmpty() && s.session(name).map(SessionState::isCheckpointing).orElse(false);
    }

    /**
     * A command (the checkpoint, {@code /compact}) runs as a prompt from the session's panel, as if the user had typed
     * it there.
     */
    static Transition sessionCommand(final Clock clock, final AppState s, final String name, final String command) {
        return sessionCommand(clock, s, name, command, "");
    }

    /** As {@link #sessionCommand(Clock, AppState, String, String)}, sent by {@code from}: see {@link #runPrompt}. */
    static Transition sessionCommand(final Clock clock, final AppState s, final String name, final String command, final String from) {
        final Optional<SessionState> found = s.session(name);
        if (found.isEmpty() || command.isBlank() || found.get().status() == Status.CLOSING) {
            return Transition.of(s);
        }
        // its own panel, else a document attached to it, else its own panel closed: the command shows in a panel
        final Optional<PanelState> panel = s.panelsBoundToSession(name).stream().findFirst().or(() -> s.closedPanelOfSession(name));
        if (panel.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Session " + name + " has no panel; open it to send "
                    + command, Notice.session(name)));
        }
        return runPrompt(clock, s, panel.get(), command, from);
    }

    /**
     * Empties the session's Session tab; the conversation itself is untouched. Refused while a turn runs: its blocks
     * are still being written.
     */
    static Transition clearTranscript(final Clock clock, final AppState s, final String name) {
        final Optional<SessionState> found = s.session(name);
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
    static Transition interruptSession(final Clock clock, final AppState s, final String name) {
        return s.session(name)
                .filter(SessionState::isBusy)
                .map(x -> new Transition(s.withSession(x.withActivity("interrupting", clock.millis())),
                        List.<Effect>of(new Effect.InterruptClaude(name))))
                .orElse(Transition.of(s));
    }

    /**
     * A turn ended. The checkpoint's turn, once finished, sends {@code /compact}; the automatic checkpoint's compaction,
     * once it has ended normally with nothing queued, sends {@link Reducer#CONTINUE_PROMPT}; a turn that leaves the
     * context at or above {@code percent} of the session's context window (0 or below: never) runs the checkpoint, once
     * per crossing, at the turn the session falls due (records 129, 131). A crossing starts at a turn whose last model
     * call read less than the threshold, and the carry-on is followed by no automatic checkpoint until someone prompts
     * the session again.
     */
    static Transition turnEnded(final Clock clock, AppState s, final Intent.SessionTurnEnded i, final int percent) {
        Optional<SessionState> found = s.session(i.name());
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        // what the turn ran at no longer holds: the next turn starts at the session's own model and effort
        found = Optional.of(found.get().withInfo(found.get().info().turnEnded()));
        s = s.withSession(found.get());
        final long now = clock.millis();
        // a result while no turn ran made no model call (Claude Code sends one after a background agent's report): it
        // finished nothing
        final boolean ran = found.get().isBusy();
        if (i.error() && found.get().status() == Status.FAILED) {
            // a prompt to a session whose start failed: the start's error already stands, once
            final SessionState ended = found.get().withTurns(Math.max(0, i.queued()), now).withActivity("", now);
            return Transition.of(s.withSession(ran && !ended.isBusy() ? finished(s, ended, now) : ended));
        }
        SessionState x = found.get()
                .withTurns(Math.max(0, i.queued()), now)
                .withUsage(i.costUsd() >= 0 ? i.costUsd() : found.get().costUsd(),
                        i.contextTokens() >= 0 ? i.contextTokens() : found.get().contextTokens(),
                        i.contextWindow() > 0 ? i.contextWindow() : found.get().contextWindow(), now)
                .withActivity("", now);
        // a context a model call read: a compaction's own count leaves out the system prompt and the tools, which the
        // next call reads again, so neither the session falling due nor the automatic checkpoint goes by it (record 129)
        final boolean read = i.contextTokens() > 0 && !i.estimated();
        x = x.withReflection(x.reflection().afterTurn(read ? x.contextTokens() : -1L, x.contextWindow(), percent));
        AppState next = s.withSession(x);
        final List<Effect> effects = new ArrayList<>();
        // the user's own interrupt is an outcome, not a failure
        final boolean interrupted = i.error() && INTERRUPTED.equals(i.message());
        x = x.withLastTurnError(i.error() && !interrupted ? i.message() : "");
        // the checkpoint has finished once nothing runs, is queued or was handed to the background; a result that made
        // no model call and carries no text finished nothing
        final boolean checkpointDone = x.checkpointCompacts() && !x.isBusy() && !x.isAwaitingHandOff() && (ran || !i.result().isBlank());
        final boolean compacts = checkpointDone && !i.error() && !i.result().isBlank();
        if (checkpointDone) {
            x = x.withCheckpointCompacts(false);
        }
        // the automatic checkpoint's /compact has ended once nothing runs: the session carries on when it ended
        // normally; an error or an interrupt keeps the session where it stopped (record 129's amendment)
        final boolean compaction = found.get().checkpointContinues() && !found.get().checkpointCompacts() && !x.isBusy();
        final boolean continues = compaction && ran && !i.error() && !x.isAwaitingHandOff() && x.status().isUsable();
        // the automatic origin goes on to the compaction only when it is sent; every other end of the checkpoint ends it
        final boolean compactionContinues = compacts && x.checkpointContinues();
        if (compaction || checkpointDone) {
            x = x.withCheckpointContinues(false);
        }
        // the turns that ended count down to the checkpoint's own, interrupted or failed alike; with nothing left to
        // run, none is under way (record 130)
        final int ended = Math.max(0, found.get().pendingTurns() - x.pendingTurns());
        x = x.withCheckpointTurns(x.isBusy() ? Math.max(0, x.checkpointTurns() - ended) : 0);
        // the context a model call read fell below the threshold (after a compaction, in a new conversation): the next
        // crossing checkpoints again; a compaction's own count never re-arms, or the checkpoint would follow its own
        // carry-on without end
        final boolean above = Reflection.above(x.contextTokens(), x.contextWindow(), percent);
        if (read && Reflection.below(x.contextTokens(), x.contextWindow(), percent)) {
            x = x.withCheckpointArmed(true);
        }
        // at the threshold, the session's own turn finished: nothing queued, no background work out, no checkpoint
        // under way, a checkpoint command to run, and since the last carry-on someone's prompt or a call below the
        // threshold
        final boolean automatic = above && x.checkpointArmed() && !x.carriedOn() && !checkpointDone && !x.checkpointCompacts() && !i.error()
                && !x.isBusy() && !x.isAwaitingHandOff() && x.status().isUsable() && !x.spec().checkpoint().isBlank();
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
        final Transition follow = compacts ? sessionCommand(clock, next, i.name(), SessionSpec.COMPACT_COMMAND)
                : continues ? sessionCommand(clock, next, i.name(), Reducer.CONTINUE_PROMPT)
                : automatic ? checkpoint(clock, next, i.name(), true, "") : null;
        if (follow != null) {
            next = follow.state();
            effects.addAll(follow.effects());
            x = next.session(i.name()).orElse(x);
            if (automatic) {
                // sent or refused (no panel to send it from), this crossing has had its checkpoint
                x = x.withCheckpointArmed(false);
                next = next.withSession(x);
            }
            if (continues) {
                // sent or refused, the next automatic checkpoint waits for a prompt of the user's or another session's
                x = x.withCarriedOn(true);
                next = next.withSession(x);
            }
            if (compacts) {
                // the compaction is the checkpoint's last turn: the session stays locked until it ends; the automatic
                // checkpoint's carries on after it
                x = x.withCheckpointTurns(x.pendingTurns()).withCheckpointContinues(compactionContinues);
                next = next.withSession(x);
            }
        }
        if (!x.isBusy()) {
            // a turn that hands work to the background has not finished it: the turn Claude Code starts when the work
            // ends notifies; a later turn that ends while older work runs on has finished; a failure always notifies
            if (!interrupted && (i.error() || ran && !x.isAwaitingHandOff())) {
                effects.add(new Effect.DesktopNotify(i.name(), i.error() ? "Turn failed: " + i.message() : "Turn finished"));
            }
            // the same outcome for the user to read; an interrupt is one too, though nothing tells the desktop of it
            if (i.error() && !interrupted || ran && !x.isAwaitingHandOff()) {
                next = next.withSession(finished(next, x, now));
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
    static Transition promptSession(final Clock clock, final AppState s, final Intent.PromptSession i) {
        final Optional<PanelState> home = s.homePanel(i.session());
        if (home.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Session " + i.session() + " has no open panel for the prompt from " + i.from(),
                    Notice.session(i.session())));
        }
        final PanelId panel = home.get().id();
        final Transition edited = PanelRules.editPanel(clock, s, new Intent.EditPanel(panel, i.text(), EditMode.APPEND, i.from()));
        final Transition ran = QueryRules.runText(clock, edited.state(), new Intent.RunText(panel, i.text(), Target.CLAUDE));
        final List<Effect> effects = new ArrayList<>(edited.effects());
        effects.addAll(ran.effects());
        return new Transition(ran.state(), effects);
    }

    static Transition sessionActivity(final Clock clock, final AppState s, final Intent.SessionActivity i) {
        return Transition.of(s.withSession(i.name(), x -> x.withActivity(i.activity(), clock.millis())));
    }

    static Transition sessionTasksChanged(final Clock clock, final AppState s, final Intent.SessionTasksChanged i) {
        return Transition.of(s.withSession(i.name(), x -> {
            final SessionState y = x.withTasks(i.open(), clock.millis());
            // the session's background work has returned, whichever turn left it: an outcome, once no reading turn
            // takes over (see Attention)
            return x.isWaiting() && !y.isWaiting() && !y.isBusy() && y.lastTurnError().isEmpty()
                    ? finished(s, y, clock.millis()) : y;
        }));
    }

    static Transition sessionTurnRunning(final Clock clock, final AppState s, final Intent.SessionTurnRunning i) {
        return Transition.of(s.withSession(i.name(), x -> x.isBusy() ? x : x.withTurns(1, clock.millis())));
    }
}
