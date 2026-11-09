package dev.marregui.comando.core.model;

import java.util.List;
import java.util.Objects;

/**
 * A decision waiting for the user in the Approvals tab's Later group (records 131 and 142): one an automatic checkpoint
 * or a report held back, or a question whose card had no answer in time. The user answers a batch of them at once, each
 * with one of its options or a line typed; each answer goes to the decision's session.
 *
 * @param id            comando's id, unique among the decisions kept, across restarts
 * @param session       the session that held it back: its checkpoint, a report or a question no one answered in time
 * @param title         one line: what is to be decided
 * @param detail        what the user needs to decide it, a few lines at most; may be empty
 * @param createdMillis when it was held back
 * @param options       the answers offered, in order; empty when the answer is a line the user types
 */
public record Decision(long id, String session, String title, String detail, long createdMillis, List<String> options) {

    public Decision {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(title, "title");
        detail = detail == null ? "" : detail;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** A decision answered by a line the user types; for tests, the application builds the canonical form. */
    public Decision(
            final long id,
            final String session,
            final String title,
            final String detail,
            final long createdMillis
    ) {
        this(id, session, title, detail, createdMillis, List.of());
    }
}
