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
 * @param toolUseId     the transcript's tool call the request holds up, empty when Claude Code did not say (a
 *                      statement from {@code db_run_sql} holds up that tool's own call)
 */
public record Approval(long id, String session, ApprovalKind kind, String toolName, String inputJson, String summary, long createdMillis,
                       String toolUseId) {

    public Approval {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(inputJson, "inputJson");
        Objects.requireNonNull(summary, "summary");
        toolUseId = toolUseId == null ? "" : toolUseId;
    }

    public Approval(final long id, final String session, final ApprovalKind kind, final String toolName, final String inputJson, final String summary, final long createdMillis) {
        this(id, session, kind, toolName, inputJson, summary, createdMillis, "");
    }
}
