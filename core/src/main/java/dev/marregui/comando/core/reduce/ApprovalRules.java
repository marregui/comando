package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static dev.marregui.comando.core.reduce.Shared.clear;
import static dev.marregui.comando.core.reduce.Shared.notice;

/**
 * The approvals' rules: a card asked for, answered or withdrawn, and the cards withdrawn when their session or turn
 * ends.
 */
final class ApprovalRules {

    private ApprovalRules() {
    }

    /**
     * A session asks: its card joins the Approvals tab, which comes forward; the keyboard stays where it is, so typing
     * in the editor goes on. A card asked for again (the same id) changes nothing.
     */
    static Transition approvalRequested(Clock clock, AppState s, Approval a) {
        if (s.approval(a.id()).isPresent()) {
            return Transition.of(s);
        }
        List<Approval> approvals = new ArrayList<>(s.approvals());
        approvals.add(a);
        AppState next = notice(clock, s.withApprovals(approvals).withLowerTab(LowerTab.APPROVALS), Notice.Level.INFO,
                a.session() + " asks: " + a.summary() + " (⌃2)", Notice.approval(a.id()));
        return new Transition(next, List.of(new Effect.DesktopNotify(a.session() + " needs approval", a.summary())));
    }

    /**
     * The user answered a card. When it was the last one and the Approvals tab is in front, the Session tab comes
     * forward on the panel of the session that asked, where its turn goes on.
     */
    static Transition approvalAnswered(AppState s, Intent.ApprovalAnswered i) {
        Optional<Approval> a = s.approval(i.id());
        if (a.isEmpty()) {
            return Transition.of(s);
        }
        List<Approval> rest = s.approvals().stream().filter(x -> x.id() != i.id()).toList();
        AppState next = clear(s.withApprovals(rest), Notice.approval(i.id()));
        if (rest.isEmpty() && s.lowerTab().equals(LowerTab.APPROVALS)) {
            List<PanelState> asking = next.panelsBoundToSession(a.get().session());
            next = (asking.isEmpty() ? next : next.withFocused(asking.getFirst().id())).withLowerTab(LowerTab.SESSION);
        }
        return new Transition(next, List.of(new Effect.ResolveApproval(a.get(), i.decision())));
    }

    static Transition approvalWithdrawn(Clock clock, AppState s, Intent.ApprovalWithdrawn i) {
        if (s.approval(i.id()).isEmpty()) {
            return Transition.of(s);
        }
        AppState next = s.withApprovals(s.approvals().stream().filter(x -> x.id() != i.id()).toList());
        return Transition.of(notice(clock, next, Notice.Level.WARN, "Approval withdrawn: " + i.reason(), Notice.approval(i.id())));
    }

    /**
     * Denies and drops every waiting approval of {@code session}, so no MCP call waits for an answer nobody can give.
     */
    static AppState withdrawApprovals(AppState s, String session, String reason, List<Effect> effects) {
        return withdrawApprovals(s, session, reason, effects, a -> true);
    }

    static AppState withdrawApprovals(AppState s, String session, String reason, List<Effect> effects,
                                              Predicate<Approval> which) {
        List<Approval> kept = new ArrayList<>();
        AppState next = s;
        for (Approval a : s.approvals()) {
            if (a.session().equals(session) && which.test(a)) {
                effects.add(new Effect.ResolveApproval(a, new ApprovalDecision.Deny(reason)));
                next = clear(next, Notice.approval(a.id())); // its "asks" notice would point at an empty Approvals tab
            } else {
                kept.add(a);
            }
        }
        return kept.size() == s.approvals().size() ? s : next.withApprovals(kept);
    }
}
