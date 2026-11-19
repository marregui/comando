package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.Store;

import static dev.marregui.comando.cdb.exec.SqlRows.columns;
import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [ColumnRunner] on a store in a temporary folder: the table set up and read back through [Cdb], the change run
/// directly on the [Store] from its plan, each in its own open of the folder.
class ColumnRunnerTest {

    @TempDir
    private Path root;

    /// A drop rewrites every row without the column, every other column's values kept, NULLs included, and the
    /// designated timestamp after it read as before.
    @Test
    void dropKeepsEveryOtherColumn() {
        sql("CREATE TABLE t (s SYMBOL, x LONG, d DOUBLE, ts TIMESTAMP, v VARCHAR, f BOOLEAN) TIMESTAMP(ts)"
                        + " PARTITION BY DAY",
                "INSERT INTO t VALUES ('a', 1, 1.5, '2026-11-14T10:00:00.000000Z', 'one', true),"
                        + " (NULL, NULL, NULL, '2026-11-15T10:00:00.000000Z', NULL, false),"
                        + " ('b', 3, 3.5, '2026-11-16T10:00:00.000000Z', 'three', true)");
        assertThat(drop("t", "x")).isZero();
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(columns(cdb.run("SELECT * FROM t"))).containsExactly("s:SYMBOL", "d:DOUBLE", "ts:TIMESTAMP",
                    "v:VARCHAR", "f:BOOLEAN");
            assertThat(rows(cdb.run("SELECT * FROM t"))).containsExactly(
                    "a|1.5|2026-11-14T10:00:00Z|one|true",
                    "||2026-11-15T10:00:00Z||false",
                    "b|3.5|2026-11-16T10:00:00Z|three|true");
        }
    }

    /// A rename keeps every value under the new name, a SYMBOL column's filterable by value after it, and the old name
    /// is gone.
    @Test
    void renameKeepsTheValuesUnderTheNewName() {
        sql("CREATE TABLE t (ts TIMESTAMP, s SYMBOL, x LONG) TIMESTAMP(ts) PARTITION BY DAY",
                "INSERT INTO t VALUES ('2026-11-14T10:00:00.000000Z', 'a', 1), ('2026-11-15T10:00:00.000000Z', 'b', 2),"
                        + " ('2026-11-16T10:00:00.000000Z', 'a', NULL)");
        assertThat(rename("t", "s", "sensor")).isZero();
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(columns(cdb.run("SELECT * FROM t"))).containsExactly("ts:TIMESTAMP", "sensor:SYMBOL",
                    "x:LONG");
            assertThat(rows(cdb.run("SELECT x FROM t WHERE sensor = 'a'"))).containsExactly("1", "");
            assertThatThrownBy(() -> cdb.run("SELECT s FROM t")).isInstanceOf(CdbException.class);
        }
    }

    /// A drop of a column the table lacks, as a plan under IF EXISTS carries it, answers 0 and leaves the table, its
    /// definition and its rows as they were.
    @Test
    void dropOfAnAbsentColumnLeavesTheTable() {
        sql("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY",
                "INSERT INTO t VALUES ('2026-11-14T10:00:00.000000Z', 1)");
        assertThat(drop("t", "nope")).isZero();
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(columns(cdb.run("SELECT * FROM t"))).containsExactly("ts:TIMESTAMP", "x:LONG");
            assertThat(rows(cdb.run("SELECT * FROM t"))).containsExactly("2026-11-14T10:00:00Z|1");
        }
    }

    /// The runner checks again what the planner refused: the designated timestamp is not renamed, a column the table
    /// lacks is not found, and the table stays as it was.
    @Test
    void refusedRenamesLeaveTheTable() {
        sql("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY",
                "INSERT INTO t VALUES ('2026-11-14T10:00:00.000000Z', 1)");
        assertThatThrownBy(() -> rename("t", "ts", "time")).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> rename("t", "nope", "y")).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> rename("t", "x", "TS")).isInstanceOf(CdbException.class);
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(columns(cdb.run("SELECT * FROM t"))).containsExactly("ts:TIMESTAMP", "x:LONG");
            assertThat(rows(cdb.run("SELECT * FROM t"))).containsExactly("2026-11-14T10:00:00Z|1");
        }
    }

    /// An empty table and one without designated timestamp take both changes.
    @Test
    void emptyTableAndTableWithoutTimestamp() {
        sql("CREATE TABLE e (ts TIMESTAMP, x LONG, y LONG) TIMESTAMP(ts) PARTITION BY DAY",
                "CREATE TABLE p (n LONG, s SYMBOL, f BOOLEAN)",
                "INSERT INTO p VALUES (1, 'a', true), (2, NULL, false), (3, 'c', true)");
        assertThat(drop("e", "x")).isZero();
        assertThat(rename("e", "y", "z")).isZero();
        assertThat(drop("p", "f")).isZero();
        assertThat(rename("p", "s", "t")).isZero();
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(columns(cdb.run("SELECT * FROM e"))).containsExactly("ts:TIMESTAMP", "z:LONG");
            assertThat(rows(cdb.run("SELECT count() FROM e"))).containsExactly("0");
            assertThat(columns(cdb.run("SELECT * FROM p"))).containsExactly("n:LONG", "t:SYMBOL");
            assertThat(rows(cdb.run("SELECT * FROM p"))).containsExactly("1|a", "2|", "3|c");
        }
    }

    /// Over many pages and partitions, a random column dropped and another renamed: every row stays, with the values
    /// it had. Seeds replay with `-Dcdb.column.seed0=… -Dcdb.column.seed1=…`.
    @Test
    void manyPagesKeepEveryRow() {
        final TwoSeedRandom random = TwoSeedRandom.of("cdb.column", "manyPagesKeepEveryRow");
        final int n = random.between(3_000, 6_000);
        final List<String> names = new ArrayList<>(List.of("a", "b", "c"));
        sql("CREATE TABLE t (ts TIMESTAMP, a LONG, b SYMBOL, c VARCHAR) TIMESTAMP(ts) PARTITION BY HOUR",
                "INSERT INTO t SELECT CAST(x * 60000000 AS TIMESTAMP), x, CAST(x % 7 AS VARCHAR), CAST(x AS VARCHAR)"
                        + " FROM long_sequence(" + n + ")");
        final String dropped = names.remove(random.nextInt(names.size()));
        final String renamed = names.get(random.nextInt(names.size()));
        assertThat(drop("t", dropped)).isZero();
        assertThat(rename("t", renamed, renamed + "2")).isZero();
        final List<String> expected = new ArrayList<>(n);
        for (int x = 1; x <= n; x++) {
            final StringBuilder row = new StringBuilder();
            for (final String name : List.of("a", "b", "c")) {
                if (!name.equals(dropped)) {
                    row.append('|').append(name.equals("b") ? Integer.toString(x % 7) : Integer.toString(x));
                }
            }
            expected.add(row.substring(1));
        }
        final String select = "SELECT " + String.join(", ", names).replace(renamed, renamed + "2") + " FROM t";
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(rows(cdb.run(select))).as("dropped=%s renamed=%s n=%d", dropped, renamed, n)
                    .containsExactlyElementsOf(expected);
        }
    }

    private void sql(final String... statements) {
        try (final Cdb cdb = Cdb.open(root)) {
            for (final String s : statements) {
                cdb.run(s);
            }
        }
    }

    private long drop(final String table, final String column) {
        try (final Store store = Store.open(root)) {
            return ColumnRunner.dropColumn(new Plan.DropColumn(table, column), store);
        }
    }

    private long rename(final String table, final String from, final String to) {
        try (final Store store = Store.open(root)) {
            return ColumnRunner.renameColumn(new Plan.RenameColumn(table, from, to), store);
        }
    }
}
