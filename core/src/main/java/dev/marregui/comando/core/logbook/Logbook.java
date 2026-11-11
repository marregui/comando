package dev.marregui.comando.core.logbook;

import java.util.List;
import java.util.Objects;

/**
 * The scratchpad's open tasks, read from the newest day folder's {@code INDEX.md}: what the Logbook tab shows.
 *
 * @param index   the file read, as an absolute path; empty when there is none
 * @param tasks   the open tasks in the order the index lists them
 * @param problem why the index could not be read, for the tab to say; empty when it was read
 */
public record Logbook(String index, List<LogbookTask> tasks, String problem) {

    /** No kit, no brain or no day folder yet: nothing to show. */
    public static final Logbook NONE = new Logbook("", List.of(), "");

    public Logbook {
        Objects.requireNonNull(index, "index");
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        problem = problem == null ? "" : problem;
    }

    /** The tasks that wait on the user, in the index's order. */
    public List<LogbookTask> needingUser() {
        return tasks.stream().filter(LogbookTask::isWaitingOnUser).toList();
    }
}
