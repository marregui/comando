package dev.marregui.comando.core.reduce;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The decisions sessions held back (records 131, 142): an automatic checkpoint asks the user nothing, so what it would
 * have asked waits in the Approvals tab's Later group, as does a report's decision and a question no one answered in
 * time, kept in {@code decisions.json} until the user answers it, a checkpoint the user starts settles it, or the user
 * dismisses it. A decision held back brings nothing forward and sends no desktop
 * notification: it is for when the user has time.
 */
final class DecisionRules {

    /** At most this many decisions are kept; the oldest go first. */
    static final int MAX_KEPT = 200;

    /** The first line of the prompt that carries a session's answers. */
    private static final String ANSWERS = "Answers to the decisions you held for me:";

    private DecisionRules() {
    }

    /**
     * A session's checkpoint held a decision back: it joins the Later group, newest last, with its options stripped and
     * the blank ones dropped. A blank title is dropped. Its id comes from the state's counter, which never goes back,
     * so an id read earlier never names a newer decision.
     */
    static Transition heldBack(final Clock clock, final AppState s, final Intent.DecisionHeldBack i) {
        if (i.title() == null || i.title().isBlank()) {
            return Transition.of(s);
        }
        // the counter starts above every id a kept decision holds, here and across restarts (read)
        AppState counted = s;
        for (final Decision d : s.later()) {
            counted = counted.withIdsFrom(d.id() + 1);
        }
        final long id = counted.nextId();
        final List<Decision> next = new ArrayList<>(s.later());
        final List<String> options = i.options().stream().filter(o -> !o.isBlank()).map(String::strip).toList();
        final String detail = i.detail() == null ? "" : i.detail().strip();
        next.add(new Decision(id, i.session(), i.title().strip(), detail, clock.millis(), options));
        while (next.size() > MAX_KEPT) {
            next.removeFirst();
        }
        return saved(counted.withIdTaken().withLater(next));
    }

    /** A checkpoint the user started settled these decisions; ids no longer kept change nothing. */
    static Transition settled(final AppState s, final Intent.DecisionsSettled i) {
        final Set<Long> ids = new HashSet<>(i.ids());
        final List<Decision> rest = s.later().stream().filter(d -> !ids.contains(d.id())).toList();
        return rest.size() == s.later().size() ? Transition.of(s) : saved(s.withLater(rest));
    }

    /**
     * The decisions {@code decisions.json} holds, read at start; nothing is written back. Ids given from then on start
     * above the highest one given before.
     */
    static Transition read(final AppState s, final Intent.DecisionsRead i) {
        AppState next = s.withLater(i.decisions()).withIdsFrom(i.lastId() + 1);
        for (final Decision d : i.decisions()) {
            next = next.withIdsFrom(d.id() + 1);
        }
        return Transition.of(next);
    }

    /**
     * The user answered a batch of decisions: each session gets one prompt, from its panel, with a line per decision
     * it held, in the group's order; a decision whose session is gone goes to the focused panel's session. The
     * decisions whose prompt went out leave the group, saved once. Those with no session to go to, or whose session
     * runs its checkpoint (record 130) or does not take the prompt (stopping), stay with their answers on their cards,
     * and a notice says so. Ids no longer kept and blank answers change nothing.
     */
    static Transition answered(final Clock clock, final AppState s, final Intent.DecisionsAnswered i) {
        // the decisions answered, by the session they go to, in the order its first answer came in
        final Map<String, List<Decision>> bySession = new LinkedHashMap<>();
        boolean unsent = false;
        for (final Map.Entry<Long, String> answer : i.answers().entrySet()) {
            final Optional<Decision> d = s.decision(answer.getKey());
            if (d.isEmpty() || answer.getValue() == null || answer.getValue().isBlank()) {
                continue;
            }
            final Optional<String> target = answerTarget(s, d.get());
            if (target.isEmpty()) {
                unsent = true;
                continue;
            }
            bySession.computeIfAbsent(target.get(), _ -> new ArrayList<>()).add(d.get());
        }
        AppState next = s;
        final List<Effect> effects = new ArrayList<>();
        final Set<Long> sent = new HashSet<>();
        final List<String> kept = new ArrayList<>();
        for (final Map.Entry<String, List<Decision>> session : bySession.entrySet()) {
            // a prompt now would cancel the checkpoint's compaction and its carry-on, as Settle would
            if (TurnRules.locked(next, session.getKey())) {
                kept.add(session.getKey());
                continue;
            }
            final StringBuilder prompt = new StringBuilder(ANSWERS);
            for (final Decision d : session.getValue()) {
                prompt.append("\n- ").append(d.title()).append(": ").append(i.answers().get(d.id()).strip());
            }
            final Transition t = TurnRules.sessionCommand(clock, next, session.getKey(), prompt.toString());
            next = t.state();
            effects.addAll(t.effects());
            // a session that refuses the prompt (stopping, no panel) keeps its decisions for another try
            if (t.effects().stream().anyMatch(e -> e instanceof Effect.SendToClaude)) {
                session.getValue().forEach(d -> sent.add(d.id()));
            } else {
                kept.add(session.getKey());
            }
        }
        // one notice for every answer kept, so none hides another
        final List<String> why = new ArrayList<>();
        if (!kept.isEmpty()) {
            why.add("Kept the answers for " + String.join(", ", kept) + ": a session running its checkpoint or "
                    + "stopping cannot take them now; send them again once it can");
        }
        if (unsent) {
            why.add("No session to send some answers to: open a session for them");
        }
        if (!why.isEmpty()) {
            next = notice(clock, next, Notice.Level.WARN, String.join(". ", why));
        }
        if (sent.isEmpty()) {
            return new Transition(next, effects);
        }
        final AppState answered = next.withLater(next.later().stream().filter(d -> !sent.contains(d.id())).toList());
        effects.add(new Effect.SaveDecisions(answered.later(), answered.nextId() - 1));
        return new Transition(answered, effects);
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
        return TurnRules.sessionCommand(clock, s, target.get(), command, i.from());
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

    /** The session an answer goes to: the decision's own while it exists, else the focused panel's session. */
    private static Optional<String> answerTarget(final AppState s, final Decision d) {
        return s.session(d.session()).or(() -> s.session(s.focusedPanel().session())).map(SessionState::name);
    }

    private static boolean canSettle(final SessionState x) {
        return !x.spec().checkpoint().isBlank();
    }

    private static Transition saved(final AppState next) {
        // every id given so far is below the counter
        return new Transition(next, List.of(new Effect.SaveDecisions(next.later(), next.nextId() - 1)));
    }
}
