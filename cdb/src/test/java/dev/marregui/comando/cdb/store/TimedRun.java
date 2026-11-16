package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static dev.marregui.comando.cdb.store.StoreRows.rows;

/// What one workload on a store under a [DiskModel] answered, and when each answer became durable, for the power-cut
/// tests of a store that forces its logs on a timer. Per table, a commit is durable once its table's log was forced
/// after the commit's last write to it; at its answer when it wrote nothing to the log (a direct commit, a checkpoint
/// before its answer); or once a checkpoint, a durable wait or the store's close covering it returned ([#held]). The
/// bound a cut after operation `k` must read is, per table, a state from the last durable one to the one in flight
/// ([#allowed]). One thread records; the checks read once it is done.
final class TimedRun {

    /// A table's state where the table does not exist.
    static final List<String> ABSENT = List.of();

    private final DiskModel model;
    private final Path root;
    // answered.get(j): the store's state once answer j returned; answeredAt.get(j): the operations recorded by then
    private final List<Map<String, List<String>>> answered = new ArrayList<>();
    private final List<Integer> answeredAt = new ArrayList<>();
    private final List<Held> held = new ArrayList<>();
    // per table, per answer j: the operations after which the table's state at answer j is durable; built on first use
    private Map<String, int[]> heldAt;

    /// A run of `model`, the disk under `root`, whose first answer is `start`, the state after the operations
    /// recorded so far.
    TimedRun(final DiskModel model, final Path root, final Map<String, List<String>> start) {
        this.model = model;
        this.root = root;
        answered.add(start);
        answeredAt.add(model.operations());
    }

    /// Each table: its definition, then its rows sorted.
    static Map<String, List<String>> state(final Store store) {
        final Map<String, List<String>> state = new TreeMap<>();
        for (final String table : store.tables()) {
            try (final TableReader reader = store.reader(table)) {
                final TableDef def = reader.def();
                final StringBuilder header = new StringBuilder();
                for (final ColumnDef column : def.columns()) {
                    header.append(column.name()).append(':').append(column.type()).append(' ');
                }
                header.append("ts=").append(def.timestampIndex()).append(" ttl=").append(def.ttlMicros());
                final List<String> sorted = new ArrayList<>(rows(reader));
                sorted.sort(null);
                final List<String> lines = new ArrayList<>(sorted.size() + 1);
                lines.add(header.toString());
                lines.addAll(sorted);
                state.put(table, lines);
            }
        }
        return state;
    }

    /// The first table whose state in `actual` is none of those `allowed` gives it, described; null when there is
    /// none.
    static String mismatch(final Map<String, List<String>> actual, final Map<String, Set<List<String>>> allowed) {
        final Set<String> tables = new TreeSet<>(allowed.keySet());
        tables.addAll(actual.keySet());
        for (final String table : tables) {
            final List<String> found = actual.getOrDefault(table, ABSENT);
            final Set<List<String>> states = allowed.getOrDefault(table, Set.of(ABSENT));
            if (!states.contains(found)) {
                return "table " + table + " reads " + describe(found) + ", expected one of " + states.size()
                        + " states: " + String.join(" or ", states.stream().map(TimedRun::describe).toList());
            }
        }
        return null;
    }

    /// Records the store's state as an answer.
    void answer(final Store store) {
        answeredAt.add(model.operations());
        answered.add(state(store));
        heldAt = null;
    }

    /// Every commit of `table` answered so far is durable from now on: a checkpoint of it or a durable wait returned.
    void held(final String table) {
        held.add(new Held(model.operations(), table, answered.size() - 1));
        heldAt = null;
    }

    /// Every commit answered so far is durable from now on: the store closed.
    void heldAll() {
        held(null);
    }

    /// How many answers were recorded, the first included.
    int answers() {
        return answered.size();
    }

    /// The operations recorded once answer `j` returned.
    int answeredAt(final int j) {
        return answeredAt.get(j);
    }

    /// The last of the first `k` operations of the run's disk, for a failure message.
    String describe(final int k) {
        return model.describe(Math.min(k, model.operations()) - 1);
    }

    /// Per table, the states a power cut after the first `k` operations may read: from the last durable one, or with
    /// `kill` (a process kill, which loses no answered commit) the last answered one, to the one in flight.
    Map<String, Set<List<String>>> allowed(final int k, final boolean kill) {
        final int j = answered(k);
        final int last = Math.min(j + 1, answered.size() - 1);
        final Map<String, Set<List<String>>> allowed = new TreeMap<>();
        for (final Map.Entry<String, int[]> table : heldAt().entrySet()) {
            final Set<List<String>> states = new LinkedHashSet<>();
            for (int i = kill ? j : durable(table.getValue(), j, k); i <= last; i++) {
                states.add(answered.get(i).getOrDefault(table.getKey(), ABSENT));
            }
            allowed.put(table.getKey(), states);
        }
        return allowed;
    }

    /// The bounds of [#allowed] as text: equal for two cut points whose allowed states are the same.
    String bounds(final int k, final boolean kill) {
        final int j = answered(k);
        final StringBuilder bounds = new StringBuilder().append(j);
        for (final int[] at : heldAt().values()) {
            bounds.append(',').append(kill ? j : durable(at, j, k));
        }
        return bounds.toString();
    }

    // the last answer given by operation k
    private int answered(final int k) {
        int j = 0;
        while (j + 1 < answeredAt.size() && answeredAt.get(j + 1) <= k) {
            j++;
        }
        return j;
    }

    // the last answer at or before j whose state is durable after k operations; answer 0's is durable from the start
    private static int durable(final int[] at, final int j, final int k) {
        int i = j;
        while (i > 0 && at[i] > k) {
            i--;
        }
        return i;
    }

    private Map<String, int[]> heldAt() {
        if (heldAt == null) {
            final Set<String> tables = new TreeSet<>();
            for (final Map<String, List<String>> state : answered) {
                tables.addAll(state.keySet());
            }
            final Map<String, int[]> built = new TreeMap<>();
            for (final String table : tables) {
                final int[] at = new int[answered.size()];
                at[0] = answeredAt.getFirst();
                for (int j = 1; j < at.length; j++) {
                    final boolean changed = !answered.get(j - 1).getOrDefault(table, ABSENT)
                            .equals(answered.get(j).getOrDefault(table, ABSENT));
                    at[j] = changed ? Math.max(at[j - 1], commitHeld(table, j)) : at[j - 1];
                }
                built.put(table, at);
            }
            heldAt = built;
        }
        return heldAt;
    }

    // the operations after which answer j's commit of table is durable; Integer.MAX_VALUE when the run never made it
    private int commitHeld(final String table, final int j) {
        final Path log = root.resolve(table).resolve(Wal.FILE);
        final int write = model.lastWrite(log, answeredAt.get(j - 1), answeredAt.get(j));
        int at;
        if (write < 0) {
            at = answeredAt.get(j);
        } else {
            final int force = model.firstForce(log, write + 1);
            at = force < 0 ? Integer.MAX_VALUE : force + 1;
        }
        for (final Held mark : held) {
            if (mark.answer() >= j && (mark.table() == null || mark.table().equals(table))) {
                at = Math.min(at, mark.at());
            }
        }
        return at;
    }

    private static String describe(final List<String> state) {
        return state.isEmpty() ? "[absent]" : "[" + state.getFirst() + "; " + (state.size() - 1) + " rows]";
    }

    /// From operation `at` on, every commit of `table` (every table when null) answered by answer `answer` is durable.
    private record Held(int at, String table, int answer) {
    }
}
