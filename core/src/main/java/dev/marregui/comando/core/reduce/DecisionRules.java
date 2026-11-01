package dev.marregui.comando.core.reduce;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;

import static dev.marregui.comando.core.reduce.Shared.notice;

/**
 * The decisions automatic checkpoints held back (record 131): an automatic checkpoint asks the user nothing, so what it
 * would have asked waits in the Approvals tab's Later group, kept in {@code decisions.json} until a checkpoint the user
 * starts settles it or the user dismisses it. A decision held back brings nothing forward and sends no desktop
 * notification: it is for when the user has time.
 */
final class DecisionRules {

    /** At most this many decisions are kept; the oldest go first. */
    static final int MAX_KEPT = 200;

    private DecisionRules() {
    }

    /** A session's checkpoint held a decision back: it joins the Later group, newest last. A blank title is dropped. */
    static Transition heldBack(final Clock clock, final AppState s, final Intent.DecisionHeldBack i) {
        if (i.title() == null || i.title().isBlank()) {
            return Transition.of(s);
        }
        long id = 1;
        for (final Decision d : s.later()) {
            id = Math.max(id, d.id() + 1);
        }
        final List<Decision> next = new ArrayList<>(s.later());
        next.add(new Decision(id, i.session(), i.title().strip(), i.detail() == null ? "" : i.detail().strip(), clock.millis()));
        while (next.size() > MAX_KEPT) {
            next.removeFirst();
        }
        return saved(s.withLater(next));
    }

    /** A checkpoint the user started settled these decisions; ids no longer kept change nothing. */
    static Transition settled(final AppState s, final Intent.DecisionsSettled i) {
        final Set<Long> ids = new HashSet<>(i.ids());
        final List<Decision> rest = s.later().stream().filter(d -> !ids.contains(d.id())).toList();
        return rest.size() == s.later().size() ? Transition.of(s) : saved(s.withLater(rest));
    }

    /** The decisions {@code decisions.json} holds, read at start; nothing is written back. */
    static Transition read(final AppState s, final Intent.DecisionsRead i) {
        return Transition.of(s.withLater(i.decisions()));
    }

    /** Dismiss: the decision leaves unasked. */
    static Transition dismissed(final AppState s, final Intent.DismissDecision i) {
        return settled(s, new Intent.DecisionsSettled(List.of(i.id())));
    }

    /**
     * Settle: the checkpoint command of {@link #target}, without the automatic argument, as a prompt from its panel. The
     * session asks the user what its checkpoints held back, and settles each through the decision tools; the decision
     * stays listed until then.
     */
    static Transition settle(final Clock clock, final AppState s, final Intent.SettleDecision i) {
        final Optional<String> target = target(s, i.id());
        if (target.isEmpty()) {
            return Transition.of(s.decision(i.id()).isEmpty() ? s
                    : notice(clock, s, Notice.Level.WARN, "No session to settle it in: open a session with a checkpoint command"));
        }
        final String command = s.session(target.get()).map(x -> x.spec().checkpoint()).orElse("");
        return TurnRules.sessionCommand(clock, s, target.get(), command);
    }

    /**
     * The session a Settle goes to: the decision's own while it exists with a checkpoint command, else the focused
     * panel's session when it has one; empty when the decision is gone or neither has.
     */
    static Optional<String> target(final AppState s, final long id) {
        final Optional<Decision> d = s.decision(id);
        if (d.isEmpty()) {
            return Optional.empty();
        }
        final Optional<String> own = s.session(d.get().session()).filter(DecisionRules::canSettle).map(SessionState::name);
        return own.or(() -> s.session(s.focusedPanel().session()).filter(DecisionRules::canSettle).map(SessionState::name));
    }

    private static boolean canSettle(final SessionState x) {
        return !x.spec().checkpoint().isBlank();
    }

    private static Transition saved(final AppState next) {
        return new Transition(next, List.of(new Effect.SaveDecisions(next.later())));
    }
}
