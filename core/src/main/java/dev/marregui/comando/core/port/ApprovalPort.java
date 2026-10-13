package dev.marregui.comando.core.port;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;

/** Answers the MCP call waiting on an approval card. */
@FunctionalInterface
public interface ApprovalPort {

    void resolve(Approval approval, ApprovalDecision decision);
}
