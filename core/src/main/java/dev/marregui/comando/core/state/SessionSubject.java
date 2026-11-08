package dev.marregui.comando.core.state;

import java.util.List;
import java.util.Objects;

/**
 * What a session actually works on, read from its own writes (record 138): its working folder says little, since every
 * session of a kit may start in the same one.
 *
 * @param repository the top folder of the repository last written to, as an absolute path; empty when there is none
 * @param branch     that repository's branch, empty when unknown
 * @param ticket     the ticket the branch names, upper case, empty when it names none
 * @param others     the other repositories the session wrote to, the latest first
 */
public record SessionSubject(String repository, String branch, String ticket, List<String> others) {

    /** A session that has written to no repository yet. */
    public static final SessionSubject NONE = new SessionSubject("", "", "", List.of());

    public SessionSubject {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(ticket, "ticket");
        others = List.copyOf(others);
    }

    public boolean isKnown() {
        return !repository.isEmpty();
    }
}
