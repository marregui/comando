package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static org.assertj.core.api.Assertions.assertThat;

/// The cursor `Executor.open` builds for a `Plan.Explain`: one VARCHAR row per line of `Executor.explain` of the
/// statement inside, in pages of at most `Page.CAPACITY` rows, and nothing run, a change included.
class ExecutorExplainTest {

    private static final long NOW = 1_794_830_400_000_000L;
    private static final List<Field> QUERY_PLAN = List.of(new Field("QUERY PLAN", CdbType.VARCHAR));

    @TempDir
    private Path root;

    /// A query's EXPLAIN rows are the lines of its explained text, in order, under the one column `QUERY PLAN`.
    @Test
    void explainRowsAreTheLinesOfTheText() {
        try (Store store = Store.open(root)) {
            run(store, "CREATE TABLE t (ts TIMESTAMP, s SYMBOL, v LONG) TIMESTAMP(ts) PARTITION BY DAY");
            run(store, "INSERT INTO t VALUES ('2026-11-15T10:00:00.000000Z', 'a', 1)");
            final Plan inner = plan(store, "SELECT s, sum(v) FROM t WHERE v > 0");
            final List<String> lines = lines(new Plan.Explain(inner, QUERY_PLAN), store);
            assertThat(lines).containsExactly(Executor.explain(inner, store, NOW).split("\n"));
            assertThat(lines).containsExactly(
                    "Aggregate keys=[s:SYMBOL=$1] aggs=[sum(v):LONG=sum($2)] -- parallel over frames",
                    "  Filter >($2, 0) -- per frame on the pool",
                    "    Scan t [0, 1, 2] [-inf, +inf) terms [$2 > 0] -- reads [s, v], selects on [v], frames on the"
                            + " pool"
            );
        }
    }

    /// The cursor's one field is the plan's `QUERY PLAN` VARCHAR.
    @Test
    void explainCursorHasTheQueryPlanField() {
        try (Store store = Store.open(root)) {
            run(store, "CREATE TABLE t (ts TIMESTAMP, v LONG) TIMESTAMP(ts) PARTITION BY DAY");
            try (Cursor cursor = Executor.open(new Plan.Explain(plan(store, "SELECT v FROM t"), QUERY_PLAN), store,
                    NOW)) {
                assertThat(cursor.fields()).isEqualTo(QUERY_PLAN);
            }
        }
    }

    /// A text of more lines than a page holds comes back over several pages, every line once and in order.
    @Test
    void aLongPlanSpansPages() {
        try (Store store = Store.open(root)) {
            run(store, "CREATE TABLE t (ts TIMESTAMP, v LONG) TIMESTAMP(ts) PARTITION BY DAY");
            // a balanced tree of UNION ALLs, so the text is long and the plan shallow, as a plan from SQL is
            final Plan inner = unions(plan(store, "SELECT v FROM t"), Page.CAPACITY);
            final Plan explain = new Plan.Explain(inner, QUERY_PLAN);
            final List<Integer> sizes = new ArrayList<>();
            final List<String> lines = new ArrayList<>();
            try (Cursor cursor = Executor.open(explain, store, NOW)) {
                Page page;
                while ((page = cursor.next()) != null) {
                    sizes.add(page.size);
                    for (int r = 0; r < page.size; r++) {
                        lines.add(page.columns[0].textAt(r));
                    }
                }
            }
            assertThat(sizes).hasSizeGreaterThan(1).allMatch(size -> size > 0).first().isEqualTo(Page.CAPACITY);
            assertThat(lines).containsExactly(Executor.explain(inner, store, NOW).split("\n"));
        }
    }

    // a balanced UNION ALL of `leaves` copies of leaf, about two lines a leaf
    private static Plan unions(final Plan leaf, final int leaves) {
        return leaves == 1 ? leaf
                : new Plan.SetOp("UNION ALL", unions(leaf, leaves / 2), unions(leaf, leaves - leaves / 2));
    }

    /// EXPLAIN of an UPDATE shows the change and leaves the table as it was.
    @Test
    void explainOfAChangeDoesNotRunIt() {
        try (Store store = Store.open(root)) {
            run(store, "CREATE TABLE t (ts TIMESTAMP, v LONG) TIMESTAMP(ts) PARTITION BY DAY");
            run(store, "INSERT INTO t VALUES ('2026-11-15T10:00:00.000000Z', 1)");
            final Plan update = plan(store, "UPDATE t SET v = 2");
            assertThat(lines(new Plan.Explain(update, QUERY_PLAN), store)).containsExactly("Update t set $1=2");
            assertThat(lines(plan(store, "SELECT v FROM t"), store)).containsExactly("1");
        }
    }

    private static void run(final Store store, final String sql) {
        Executor.execute((Plan.Change) plan(store, sql), store, NOW);
    }

    private static Plan plan(final Store store, final String sql) {
        return Planner.plan(Parser.parse(sql), store::def, NOW);
    }

    /// The rows of `plan`'s one column, each as its text (a LONG as its decimal).
    private static List<String> lines(final Plan plan, final Store store) {
        final List<String> out = new ArrayList<>();
        try (Cursor cursor = Executor.open(plan, store, NOW)) {
            Page page;
            while ((page = cursor.next()) != null) {
                for (int r = 0; r < page.size; r++) {
                    final Vector column = page.columns[0];
                    out.add(column.type == CdbType.LONG ? Long.toString(column.getLong(r)) : column.textAt(r));
                }
            }
        }
        return out;
    }
}
