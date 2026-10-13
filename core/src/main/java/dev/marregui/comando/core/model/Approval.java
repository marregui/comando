package dev.marregui.comando.core.model;

import java.util.Objects;

/**
 * A pending approval card.
 *
 * @param id            comando's id; the MCP call waiting for the answer is keyed by it
 * @param session       the session that asked
 * @param kind          what the card asks
 * @param toolName      the tool the request concerns
 * @param inputJson     the tool input as JSON text, rendered by the card
 * @param summary       one line for lists and notifications
 * @param createdMillis when the request arrived
 */
public record Approval(long id, String session, ApprovalKind kind, String toolName, String inputJson, String summary, long createdMillis) {

    public Approval {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(inputJson, "inputJson");
        Objects.requireNonNull(summary, "summary");
    }
}
