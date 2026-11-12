package dev.marregui.comando.core.port;

import dev.marregui.comando.core.model.Approval;

/**
 * Where each approval card's wait is kept once the card is closed: how long a session sat waiting on the user, and how
 * the wait ended. Called on the request thread that waited, after the card is gone; an implementation must not block.
 */
@FunctionalInterface
public interface WaitLog {

    /** Keeps nothing: tests, and the cards that close before the application wires its metrics log. */
    WaitLog NONE = (card, outcome, waitMs) -> {
    };

    /**
     * Card {@code card} closed with {@code outcome} after {@code waitMs} milliseconds, measured on a monotonic clock
     * from the moment it was shown.
     */
    void waited(Approval card, Outcome outcome, long waitMs);

    /** How a card's wait ended. */
    enum Outcome {
        /** The user, or an agent through {@code approval_answer}, allowed the call. */
        ALLOWED,
        /**
         * Denied: by the user or an agent, or by comando withdrawing the card when its turn ended. A card denied at
         * shutdown is not kept: the metrics log closes before the MCP server denies what still waits.
         */
        DENIED,
        /** A question card's answers came back. */
        ANSWERED,
        /** Nobody answered within the approval timeout. */
        NO_ANSWER,
        /** The session's process ended while the card waited. */
        SESSION_ENDED,
        /** Claude Code cancelled the call, on an interrupt. */
        CANCELLED,
        /** The waiting thread was interrupted: comando is shutting down; not kept, as a shutdown denial is not. */
        SHUTDOWN,
        /** The wait failed. */
        FAILED
    }
}
