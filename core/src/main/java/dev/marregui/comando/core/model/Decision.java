package dev.marregui.comando.core.model;

import java.util.Objects;

/**
 * A decision an automatic checkpoint held back for the user (record 131): it asked nothing, so the session goes on, and
 * the decision waits in the Approvals tab's Later group until a checkpoint the user starts settles it.
 *
 * @param id            comando's id, unique among the decisions kept, across restarts
 * @param session       the session whose checkpoint held it back
 * @param title         one line: what is to be decided
 * @param detail        what the user needs to decide it, a few lines at most; may be empty
 * @param createdMillis when it was held back
 */
public record Decision(long id, String session, String title, String detail, long createdMillis) {

    public Decision {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(title, "title");
        detail = detail == null ? "" : detail;
    }
}
