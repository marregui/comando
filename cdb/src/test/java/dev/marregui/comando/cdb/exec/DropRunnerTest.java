package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [DropRunner] on a store in a temporary folder: TRUNCATE, DROP PARTITION and DROP TABLE planned from SQL and run
/// directly on the [Store]; the table set up and read back through [Cdb], each in its own open of the folder.
class DropRunnerTest {

    // a fixed now(): no test here compares with it
    private static final long NOW_MICROS = 0;

    private static final String THREE_DAYS = "INSERT INTO t VALUES ('2026-11-14T10:00:00.000000Z', 1),"
            + " ('2026-11-14T20:00:00.000000Z', 2), ('2026-11-15T10:00:00.000000Z', 3),"
            + " ('2026-11-16T10:00:00.000000Z', 4)";

    @TempDir
    private Path root;

    /// TRUNCATE removes every row and says how many; the table stays and takes rows again.
    @Test
    void truncateRemovesEveryRow() {
        sql("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY", THREE_DAYS);
        try (final Store store = Store.open(root)) {
            assertThat(DropRunner.truncate((Plan.Truncate) plan(store, "TRUNCATE TABLE t"), store)).isEqualTo(4);
            assertThat(DropRunner.truncate((Plan.Truncate) plan(store, "TRUNCATE TABLE t"), store)).isZero();
        }
        assertThat(select("SELECT count() FROM t")).containsExactly("0");
        sql("INSERT INTO t VALUES ('2026-11-17T10:00:00.000000Z', 5)");
        assertThat(select("SELECT * FROM t")).containsExactly("2026-11-17T10:00:00Z|5");
    }

    /// DROP PARTITION LIST drops the named partitions and counts them; a name with no partition drops nothing.
    @Test
    void dropPartitionListDropsTheNamedPartitions() {
        sql("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY", THREE_DAYS);
        assertThat(dropPartitions("ALTER TABLE t DROP PARTITION LIST '2026-11-14', '2026-11-16'")).isEqualTo(2);
        assertThat(select("SELECT * FROM t")).containsExactly("2026-11-15T10:00:00Z|3");
        assertThat(dropPartitions("ALTER TABLE t DROP PARTITION LIST '2026-10-01'")).isZero();
        assertThat(select("SELECT x FROM t")).containsExactly("3");
    }

    /// DROP PARTITION WHERE drops only the partitions whose whole day lies in the range: a day the range covers in
    /// part stays, all its rows with it.
    @Test
    void dropPartitionWhereDropsWholePartitionsOnly() {
        sql("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY", THREE_DAYS);
        assertThat(dropPartitions("ALTER TABLE t DROP PARTITION WHERE ts < '2026-11-15T12:00:00'")).isEqualTo(1);
        assertThat(select("SELECT x FROM t")).containsExactly("3", "4");
        assertThat(dropPartitions("ALTER TABLE t DROP PARTITION WHERE ts >= '2026-11-15'")).isEqualTo(2);
        assertThat(select("SELECT count() FROM t")).containsExactly("0");
    }

    /// DROP TABLE removes the table and returns 0; a missing table is refused, unless the plan says IF EXISTS.
    @Test
    void dropTableRemovesTheTable() {
        sql("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY", THREE_DAYS);
        try (final Store store = Store.open(root)) {
            assertThat(DropRunner.dropTable((Plan.DropTable) plan(store, "DROP TABLE t"), store)).isZero();
            assertThat(store.def("t")).isNull();
            assertThat(DropRunner.dropTable(new Plan.DropTable("t", true), store)).isZero();
            assertThatThrownBy(() -> DropRunner.dropTable(new Plan.DropTable("t", false), store))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot find table [table=t]");
        }
        try (final Store store = Store.open(root)) {
            assertThat(store.tables()).isEmpty();
        }
    }

    private void sql(final String... statements) {
        try (final Cdb cdb = Cdb.open(root)) {
            for (final String s : statements) {
                cdb.run(s);
            }
        }
    }

    private List<String> select(final String query) {
        try (final Cdb cdb = Cdb.open(root)) {
            return rows(cdb.run(query));
        }
    }

    private long dropPartitions(final String statement) {
        try (final Store store = Store.open(root)) {
            return DropRunner.dropPartitions((Plan.DropPartitions) plan(store, statement), store);
        }
    }

    private static Plan plan(final Store store, final String statement) {
        return Planner.plan(Parser.parse(statement), store::def, NOW_MICROS);
    }
}
