package dev.marregui.comando.cdb.plan;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.catalog.Catalog;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Executor;
import dev.marregui.comando.cdb.exec.Frames;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.QueryContext;
import dev.marregui.comando.cdb.exec.QueryPool;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableWriter;

import static org.assertj.core.api.Assertions.assertThat;

/// ORDER BY the designated timestamp descending over a scan reads the table newest first, as QuestDB's backward scan
/// does: no window numbers the rows and no sort holds them, rows with equal timestamps still come last-written first,
/// and a small LIMIT stops the read early instead of holding every row.
class NewestFirstOrderTest {

    private static final int[] POOL_SIZES = {1, 2, 11};
    private static final long DAY_MICROS = 86_400_000_000L;
    private static final long START_MICROS = 1_793_491_200_000_000L;
    private static final long NOW_MICROS = START_MICROS + 10 * DAY_MICROS;
    private static final long WAIT_MILLIS = 1_000L;
    // three days, each of more than one frame, so ties and frames meet at frame and partition boundaries
    private static final int TIED_ROWS = 3 * (Frames.FRAME_ROWS + 7_001);
    // 32 frames in one partition: more than twice the frames the exchange reads ahead on the pool of 11
    private static final int BIG_ROWS = 32 * Frames.FRAME_ROWS;
    // a row of `big`: its two LONG-sized columns
    private static final long ROW_BYTES = 16L;

    @TempDir
    static Path root;

    private static Store store;
    private static List<QueryPool> pools;

    /// Writes `t`, every timestamp three times over three days, and `big`, one long day of distinct timestamps.
    @BeforeAll
    static void writeTables() {
        pools = new ArrayList<>();
        for (final int size : POOL_SIZES) {
            pools.add(QueryPool.of(size));
        }
        store = Store.open(root);
        store.create(new TableDef("t", List.of(new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("k", CdbType.LONG), new ColumnDef("s", CdbType.SYMBOL)), 0, PartitionBy.DAY,
                List.of(), 0L), false);
        final long stepMicros = 3 * DAY_MICROS / TIED_ROWS;
        try (TableWriter writer = store.writer("t", WAIT_MILLIS)) {
            for (int r = 0; r < TIED_ROWS; r++) {
                writer.newRow(START_MICROS + r / 3 * stepMicros);
                writer.putLong(1, r);
                writer.putText(2, "s" + r % 7);
                writer.endRow();
            }
            writer.commit();
        }
        store.create(new TableDef("big", List.of(new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("v", CdbType.LONG)), 0, PartitionBy.DAY, List.of(), 0L), false);
        try (TableWriter writer = store.writer("big", WAIT_MILLIS)) {
            for (int r = 0; r < BIG_ROWS; r++) {
                writer.newRow(START_MICROS + r * 50_000L);
                writer.putLong(1, r);
                writer.endRow();
            }
            writer.commit();
        }
    }

    /// Closes the pools and the store.
    @AfterAll
    static void close() {
        for (final QueryPool pool : pools) {
            pool.close();
        }
        store.close();
    }

    /// The plan reads the scan newest first, under the projection and the limit, with no window and no sort.
    @Test
    void planReadsNewestFirstWithNoWindowOrSort() {
        for (final String sql : new String[] {
            "SELECT k FROM t ORDER BY ts DESC LIMIT 10",
            "SELECT * FROM t WHERE s = 's3' ORDER BY ts DESC LIMIT 10",
            "SELECT ts, k FROM t WHERE k > 5 ORDER BY ts DESC"}) {
            final Plan plan = planned(sql);
            assertThat(contains(plan, Plan.Window.class)).as(sql).isFalse();
            assertThat(contains(plan, Plan.Sort.class)).as(sql).isFalse();
            assertThat(scanOf(plan).newestFirst()).as(sql).isTrue();
            assertThat(PlanText.of(plan)).as(sql).contains(" newest first");
        }
    }

    /// Rows with equal timestamps come last-written first, across frames and partitions, at pools of 1, 2 and 11: the
    /// rows the plan with the window and the position sort answers, for limits from the front, from the back and
    /// windows of rows, filtered or not.
    @Test
    void tiesComeLastWrittenFirstAsBefore() {
        for (final String sql : new String[] {
            "SELECT * FROM t ORDER BY ts DESC LIMIT 1",
            "SELECT * FROM t ORDER BY ts DESC LIMIT 5",
            "SELECT k FROM t ORDER BY ts DESC LIMIT 70000",
            "SELECT k, s FROM t ORDER BY ts DESC LIMIT -4",
            "SELECT k FROM t ORDER BY ts DESC LIMIT 3, 9",
            "SELECT k, s FROM t WHERE s = 's3' ORDER BY ts DESC LIMIT 100",
            "SELECT k FROM t WHERE k % 2 = 0 ORDER BY ts DESC",
            "SELECT ts, k FROM t WHERE ts < '2026-11-01T12:00:00' ORDER BY ts DESC LIMIT 7"}) {
            final Plan reference = Planner.plan(Parser.parse(sql), store::def, NOW_MICROS, Catalog::fields, false);
            assertThat(contains(reference, Plan.Window.class)).as(sql).isTrue();
            final Plan plan = planned(sql);
            final List<List<Object>> want = rows(reference, pools.getFirst());
            assertThat(want).as(sql).isNotEmpty();
            for (final QueryPool pool : pools) {
                assertThat(rows(plan, pool)).as("pool of %d: %s", pool.threads(), sql).isEqualTo(want);
            }
        }
    }

    /// LIMIT 1 over 32 frames allocates no more than the frames the pool reads ahead, its threads and three more,
    /// where numbering and sorting every row allocates more than every row of the table: what the read holds is
    /// bounded by the pool, not by the table.
    @Test
    void aSmallLimitDoesNotHoldEveryRow() {
        final String sql = "SELECT v FROM big ORDER BY ts DESC LIMIT 1";
        final Plan reference = Planner.plan(Parser.parse(sql), store::def, NOW_MICROS, Catalog::fields, false);
        final Plan plan = planned(sql);
        final long tableBytes = ROW_BYTES * BIG_ROWS;
        for (final QueryPool pool : pools) {
            assertThat(rows(plan, pool)).isEqualTo(List.of(List.of((long) BIG_ROWS - 1)));
            final long readAheadBytes = (pool.threads() + 3L) * ROW_BYTES * Frames.FRAME_ROWS;
            final long planBytes = allocated(plan, pool);
            final long referenceBytes = allocated(reference, pool);
            System.out.println("aSmallLimitDoesNotHoldEveryRow [pool=" + pool.threads() + ", planBytes=" + planBytes
                    + ", readAheadBytes=" + readAheadBytes + ", referenceBytes=" + referenceBytes + ", tableBytes="
                    + tableBytes + ']');
            assertThat(referenceBytes).as("pool of %d, the window plan", pool.threads()).isGreaterThan(tableBytes);
            assertThat(planBytes).as("pool of %d", pool.threads()).isLessThan(readAheadBytes);
        }
    }

    private static Plan planned(final String sql) {
        return Planner.plan(Parser.parse(sql), store::def, NOW_MICROS);
    }

    // bytes allocated by every thread while the plan runs, after a run to warm it up
    private static long allocated(final Plan plan, final QueryPool pool) {
        final ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        rows(plan, pool);
        final long before = mx.getTotalThreadAllocatedBytes();
        rows(plan, pool);
        return mx.getTotalThreadAllocatedBytes() - before;
    }

    private static boolean contains(final Plan plan, final Class<? extends Plan> type) {
        for (Plan p = plan; p != null; p = child(p)) {
            if (type.isInstance(p)) {
                return true;
            }
        }
        return false;
    }

    private static Plan.Scan scanOf(final Plan plan) {
        Plan p = plan;
        while (!(p instanceof Plan.Scan scan)) {
            p = child(p);
            assertThat(p).as("a scan under %s", plan).isNotNull();
        }
        return scan;
    }

    // the one input of the plans a select over one table makes, null for any other
    private static Plan child(final Plan plan) {
        return switch (plan) {
            case Plan.Filter(final Plan child, final Bound _) -> child;
            case Plan.Project(final Plan child, final List<Bound> _, final List<?> _) -> child;
            case Plan.Sort(final Plan child, final List<Plan.SortKey> _, final long _) -> child;
            case Plan.Limit(final Plan child, final long _, final long _) -> child;
            case Plan.Window(final Plan child, final List<Bound.WindowCall> _, final List<?> _) -> child;
            case Plan.Scan _, Plan.Source _, Plan.Aggregate _, Plan.SampleBy _, Plan.LatestOn _, Plan.Distinct _,
                 Plan.SetOp _, Plan.Join _, Plan.CreateTable _, Plan.Update _, Plan.Insert _, Plan.AddColumns _,
                 Plan.SetTtl _, Plan.Explain _, Plan.DropTable _, Plan.Truncate _, Plan.DropPartitions _,
                 Plan.Delete _, Plan.DropColumn _, Plan.RenameColumn _, Plan.Backup _ -> null;
        };
    }

    /// Every row of `plan` run on `pool`, each cell as its raw value (NULL as its sentinel).
    private static List<List<Object>> rows(final Plan plan, final QueryPool pool) {
        final List<List<Object>> out = new ArrayList<>();
        try (Cursor cursor = Executor.open(plan, store, NOW_MICROS, new QueryContext(null, pool))) {
            Page page;
            while ((page = cursor.next()) != null) {
                for (int r = 0; r < page.size; r++) {
                    final List<Object> row = new ArrayList<>(page.columns.length);
                    for (final Vector v : page.columns) {
                        row.add(switch (v.type) {
                            case LONG, TIMESTAMP -> v.getLong(r);
                            case DOUBLE -> v.getDouble(r);
                            case BOOLEAN -> v.getBoolean(r);
                            case SYMBOL, VARCHAR -> String.valueOf(v.textAt(r));
                        });
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }
}
