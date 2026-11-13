package dev.marregui.comando.core.logbook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        final String owner = owner();
        return tasks.stream().filter(t -> t.isWaitingOnUser(owner)).toList();
    }

    /** Whether {@code t} waits on the user, this logbook's {@link #owner()}. */
    public boolean waitsOnUser(final LogbookTask t) {
        return t.isWaitingOnUser(owner());
    }

    /**
     * Who the logbook is for: who asked for most of its tasks (a heading's {@code (Name, date)}), the first of them on
     * a tie; empty when no task names anyone.
     */
    public String owner() {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        tasks.forEach(t -> {
            if (!t.by().isEmpty()) {
                counts.merge(t.by(), 1, Integer::sum);
            }
        });
        String owner = "";
        int most = 0;
        for (final Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > most) {
                owner = e.getKey();
                most = e.getValue();
            }
        }
        return owner;
    }
}
