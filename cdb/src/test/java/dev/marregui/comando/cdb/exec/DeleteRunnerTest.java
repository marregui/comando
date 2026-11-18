package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// [DeleteRunner] on a store in a temporary folder: the table set up and read back through [Cdb], the DELETE planned
/// from SQL and run directly on the [Store], each in its own open of the folder.
class DeleteRunnerTest {

    // a fixed now(): no test here compares with it
    private static final long NOW_MICROS = 0;

    @TempDir
    private Path root;

    /// The rows where the condition is TRUE go and the others stay: the row whose x is NULL stays, as `x > 3` is
    /// FALSE there.
    @Test
    void removesRowsWhereTheConditionIsTrue() {
        sql("CREATE TABLE t (ts TIMESTAMP, s SYMBOL, x LONG) TIMESTAMP(ts) PARTITION BY DAY",
                "INSERT INTO t VALUES ('2026-11-14T10:00:00.000000Z', 'a', 1), ('2026-11-14T11:00:00.000000Z', 'b', 4),"
                        + " ('2026-11-15T10:00:00.000000Z', 'c', NULL), ('2026-11-16T10:00:00.000000Z', 'd', 5)");
        assertThat(delete("DELETE FROM t WHERE x > 3")).isEqualTo(2);
        assertThat(select("SELECT * FROM t")).containsExactly(
                "2026-11-14T10:00:00Z|a|1",
                "2026-11-15T10:00:00Z|c|");
    }

    /// A condition is never NULL: where x is NULL, `x != 3` is TRUE, so the rows whose x is NULL go with the others
    /// unlike 3.
    @Test
    void notEqualRemovesTheRowsWhereTheValueIsNull() {
        sql("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY",
                "INSERT INTO t VALUES ('2026-11-14T10:00:00.000000Z', 3), ('2026-11-14T11:00:00.000000Z', NULL),"
                        + " ('2026-11-15T10:00:00.000000Z', 4), ('2026-11-16T10:00:00.000000Z', NULL)");
        assertThat(delete("DELETE FROM t WHERE x != 3")).isEqualTo(3);
        assertThat(select("SELECT * FROM t")).containsExactly("2026-11-14T10:00:00Z|3");
    }

    /// Without a condition every row goes and the table stays, empty, taking rows again.
    @Test
    void withoutConditionRemovesEveryRow() {
        sql("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY",
                "INSERT INTO t VALUES ('2026-11-14T10:00:00.000000Z', 1), ('2026-11-15T10:00:00.000000Z', 2)");
        assertThat(delete("DELETE FROM t")).isEqualTo(2);
        assertThat(select("SELECT count() FROM t")).containsExactly("0");
        sql("INSERT INTO t VALUES ('2026-11-16T10:00:00.000000Z', 3)");
        assertThat(select("SELECT * FROM t")).containsExactly("2026-11-16T10:00:00Z|3");
        assertThat(delete("DELETE FROM t WHERE x = 3")).isEqualTo(1);
        assertThat(delete("DELETE FROM t")).isZero();
    }

    /// A condition that matches no row removes nothing and leaves every row as it was.
    @Test
    void noMatchRemovesNothing() {
        sql("CREATE TABLE t (ts TIMESTAMP, x LONG, v VARCHAR) TIMESTAMP(ts) PARTITION BY DAY",
                "INSERT INTO t VALUES ('2026-11-14T10:00:00.000000Z', 1, 'one'), ('2026-11-15T10:00:00.000000Z', 2,"
                        + " NULL)");
        assertThat(delete("DELETE FROM t WHERE x > 10")).isZero();
        assertThat(select("SELECT * FROM t")).containsExactly(
                "2026-11-14T10:00:00Z|1|one",
                "2026-11-15T10:00:00Z|2|");
    }

    /// A table without designated timestamp keeps its other rows in their order.
    @Test
    void tableWithoutDesignatedTimestamp() {
        sql("CREATE TABLE p (n LONG, d DOUBLE, f BOOLEAN)",
                "INSERT INTO p VALUES (1, 1.5, true), (2, NULL, false), (3, 3.5, true)");
        assertThat(delete("DELETE FROM p WHERE n = 2 OR f = false")).isEqualTo(1);
        assertThat(select("SELECT * FROM p")).containsExactly("1|1.5|true", "3|3.5|true");
    }

    /// Over many pages and partitions, a random modulus: the rows left are exactly those the condition rejects. Seeds
    /// replay with `-Dcdb.delete.seed0=… -Dcdb.delete.seed1=…`.
    @Test
    void manyPagesKeepExactlyTheRejectedRows() {
        final TwoSeedRandom random = TwoSeedRandom.of("cdb.delete", "manyPagesKeepExactlyTheRejectedRows");
        final int n = 5_000;
        final int k = random.between(2, 9);
        sql("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY HOUR",
                "INSERT INTO t SELECT CAST(x * 60000000 AS TIMESTAMP), x FROM long_sequence(" + n + ")");
        final long deleted = n / k;
        long sum = 0;
        for (int i = 1; i <= n; i++) {
            sum += i % k == 0 ? 0 : i;
        }
        assertThat(delete("DELETE FROM t WHERE n % " + k + " = 0")).as("k=%d", k).isEqualTo(deleted);
        assertThat(select("SELECT count(), sum(n) FROM t WHERE n % " + k + " = 0")).containsExactly("0|");
        assertThat(select("SELECT count(), sum(n) FROM t")).containsExactly((n - deleted) + "|" + sum);
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

    private long delete(final String statement) {
        try (final Store store = Store.open(root)) {
            if (!(Planner.plan(Parser.parse(statement), store::def, NOW_MICROS) instanceof final Plan.Delete d)) {
                throw new AssertionError("planned other than a delete [sql=" + statement + "]");
            }
            return DeleteRunner.run(d, store, NOW_MICROS);
        }
    }
}
