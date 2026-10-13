package dev.marregui.comando.core.model;

import java.util.Map;
import java.util.Objects;

/** The user's answer to an approval card. */
public sealed interface ApprovalDecision {

    /**
     * Scope of an allow: this call only, the rest of the session, or a rule saved in local settings; the rule is the
     * one {@link PermissionRules} derives. On {@code ExitPlanMode}, SESSION or ALWAYS means "approve the plan and
     * switch the session to accept edits".
     */
    enum Scope { ONCE, SESSION, ALWAYS }

    record Allow(Scope scope) implements ApprovalDecision {
        public Allow {
            Objects.requireNonNull(scope, "scope");
        }
    }

    record Deny(String message) implements ApprovalDecision {
        public Deny {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Answers to AskUserQuestion: question text to the chosen option label (or free text). */
    record Answer(Map<String, String> answers) implements ApprovalDecision {
        public Answer {
            answers = Map.copyOf(answers);
        }
    }

    static ApprovalDecision allowOnce() {
        return new Allow(Scope.ONCE);
    }
}
