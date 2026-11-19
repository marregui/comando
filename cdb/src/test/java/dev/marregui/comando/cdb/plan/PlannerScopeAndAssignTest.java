package dev.marregui.comando.cdb.plan;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Calls that differ only in DISTINCT or OVER stay apart, CTEs see only the CTEs before them, UPDATE and INSERT take an
/// explicit cast as written and UPDATE refuses at plan time what it cannot run, and a TIMESTAMP compares with a LONG
/// as microseconds, as QuestDB compares them.
class PlannerScopeAndAssignTest {

    // 2026-11-01T00:00:00Z
    private static final long DAY_ONE_MICROS = 1_793_491_200_000_000L;
    private static final long DAY_MICROS = 86_400_000_000L;

    @TempDir
    private Path root;

    /// `sum(DISTINCT x)` beside `sum(x)` is its own call, refused as it is alone, where it used to answer `sum(x)`.
    @Test
    void distinctKeepsACallApart() {
        try (final Cdb cdb = table()) {
            assertThat(cdb.run("SELECT sum(x) FROM t").getLong(0, 0)).isEqualTo(4L);
            for (final String sql : new String[] {
                    "SELECT sum(DISTINCT x) FROM t",
                    "SELECT sum(x), sum(DISTINCT x) FROM t",
                    "SELECT sum(DISTINCT x), sum(x) FROM t"}) {
                assertThatThrownBy(() -> cdb.run(sql)).as(sql).isInstanceOf(CdbException.class)
                        .hasMessage("cannot run DISTINCT in this aggregate [function=sum]");
            }
        }
    }

    /// Two window calls that differ only in their OVER clause answer each its own window.
    @Test
    void overKeepsAWindowCallApart() {
        try (final Cdb cdb = table()) {
            final CdbResult r = cdb.run("SELECT ts, row_number() OVER (ORDER BY ts) a, row_number() OVER (ORDER BY ts"
                    + " DESC) b, sum(x) OVER (PARTITION BY x) c, sum(x) OVER () d FROM t ORDER BY ts");
            assertThat(r.columnName(3)).isEqualTo("c");
            assertThat(r.rowCount()).isEqualTo(3);
            for (int row = 0; row < 3; row++) {
                assertThat(r.getLong(row, 1)).as("a, row %d", row).isEqualTo(row + 1L);
                assertThat(r.getLong(row, 2)).as("b, row %d", row).isEqualTo(3L - row);
                assertThat(r.getDouble(row, 3)).as("c, row %d", row).isEqualTo(2.0);
                assertThat(r.getDouble(row, 4)).as("d, row %d", row).isEqualTo(4.0);
            }
        }
    }

    /// A CTE that reads itself, or two that read each other, is refused as an unknown table, not a stack overflow; a
    /// CTE reads the ones before it, and a name it shadows reads the table of that name.
    @Test
    void aCteSeesOnlyTheCtesBeforeIt() {
        try (final Cdb cdb = table()) {
            for (final String sql : new String[] {
                    "WITH a AS (SELECT * FROM a) SELECT * FROM a",
                    "WITH a AS (SELECT * FROM b), b AS (SELECT * FROM a) SELECT * FROM b",
                    "WITH a AS (SELECT x FROM b), b AS (SELECT x FROM t) SELECT * FROM a"}) {
                assertThatThrownBy(() -> cdb.run(sql)).as(sql).isInstanceOf(CdbException.class)
                        .hasMessageContaining("cannot find table");
            }
            assertThat(cdb.run("WITH a AS (SELECT x FROM t), b AS (SELECT x FROM a WHERE x > 1) SELECT count() FROM b")
                    .getLong(0, 0)).isEqualTo(1L);
            assertThat(cdb.run("WITH t AS (SELECT x FROM t WHERE x > 1) SELECT count() FROM t").getLong(0, 0))
                    .isEqualTo(1L);
        }
    }

    /// An explicit cast to the column's type is taken in UPDATE SET and INSERT VALUES; a value the column's type
    /// would have to convert implicitly is still refused.
    @Test
    void anExplicitCastIsTakenAsWritten() {
        try (final Cdb cdb = table()) {
            assertThat(cdb.run("UPDATE t SET x = '7'::LONG WHERE x = 2").updateCount()).isEqualTo(1L);
            assertThat(cdb.run("UPDATE t SET x = cast('8' AS LONG) WHERE x = 7").updateCount()).isEqualTo(1L);
            cdb.run("INSERT INTO t VALUES ('2026-11-04T00:00:00.000000Z', '9'::LONG)");
            cdb.run("INSERT INTO t (ts, x) VALUES ('2026-11-05T00:00:00.000000Z', cast(2.0 AS LONG))");
            assertThat(cdb.run("SELECT sum(x) FROM t").getLong(0, 0)).isEqualTo(1L + 1L + 8L + 9L + 2L);
            assertThatThrownBy(() -> cdb.run("UPDATE t SET x = 'abc'")).isInstanceOf(CdbException.class)
                    .hasMessageContaining("cannot assign value of other type");
            assertThatThrownBy(() -> cdb.run("INSERT INTO t VALUES ('2026-11-06T00:00:00.000000Z', 'abc')"))
                    .isInstanceOf(CdbException.class).hasMessageContaining("cannot insert value of other type");
        }
    }

    /// A column set twice and a condition other than BOOLEAN are refused when UPDATE is planned, so EXPLAIN refuses
    /// them too.
    @Test
    void updateRefusesAtPlanTime() {
        try (final Cdb cdb = table()) {
            assertThatThrownBy(() -> cdb.explain("UPDATE t SET x = 1, X = 2")).isInstanceOf(CdbException.class)
                    .hasMessage("cannot assign column twice [table=t, column=X]");
            assertThatThrownBy(() -> cdb.explain("UPDATE t SET x = 1 WHERE x + 1")).isInstanceOf(CdbException.class)
                    .hasMessage("cannot update by non-boolean condition [table=t, type=LONG]");
            assertThat(cdb.run("SELECT sum(x) FROM t").getLong(0, 0)).isEqualTo(4L);
        }
    }

    /// A WITH list that names one CTE twice is refused, as QuestDB refuses it; a CTE may still shadow an outer one.
    @Test
    void aCteNamedTwiceIsRefused() {
        try (final Cdb cdb = table()) {
            assertThatThrownBy(() -> cdb.run("WITH a AS (SELECT 1 x), A AS (SELECT 2 x) SELECT * FROM a"))
                    .isInstanceOf(CdbException.class).hasMessage("cannot name CTE twice [name=A]");
            assertThat(cdb.run("WITH a AS (SELECT 1 x) SELECT * FROM (WITH a AS (SELECT 2 x) SELECT * FROM a)")
                    .getLong(0, 0)).isEqualTo(2L);
        }
    }

    /// An UPDATE of a deduplication key is refused at plan time, the rows kept: the rewrite deduplicates, so rows its
    /// new keys make equal would merge, where QuestDB keeps every one.
    @Test
    void updateRefusesADeduplicationKey() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE d (ts TIMESTAMP, k LONG, v LONG) TIMESTAMP(ts) PARTITION BY DAY"
                    + " DEDUP UPSERT KEYS(ts, k)");
            cdb.run("INSERT INTO d VALUES ('2026-11-01T00:00:00.000000Z', 1, 10),"
                    + " ('2026-11-01T00:00:00.000000Z', 2, 20)");
            assertThatThrownBy(() -> cdb.run("UPDATE d SET k = 1")).isInstanceOf(CdbException.class)
                    .hasMessage("cannot update deduplication key [table=d, column=k]");
            assertThat(cdb.run("SELECT count() FROM d").getLong(0, 0)).isEqualTo(2L);
            cdb.run("UPDATE d SET v = 0");
            assertThat(cdb.run("SELECT sum(v) FROM d").getLong(0, 0)).isZero();
        }
    }

    /// The designated timestamp compares with a LONG as microseconds, in either order, in BETWEEN and in IN.
    @Test
    void aTimestampComparesWithALong() {
        try (final Cdb cdb = table()) {
            final long second = DAY_ONE_MICROS + DAY_MICROS;
            for (final String[] c : new String[][] {
                    {"ts > 0", "3"},
                    {"ts = " + second, "1"},
                    {second + " <= ts", "2"},
                    {"ts BETWEEN 0 AND " + second, "2"},
                    {"ts IN (" + DAY_ONE_MICROS + ", " + second + ")", "2"},
                    {"ts < x", "0"}}) {
                assertThat(cdb.run("SELECT count() FROM t WHERE " + c[0]).getLong(0, 0)).as(c[0])
                        .isEqualTo(Long.parseLong(c[1]));
            }
        }
    }

    // t: one row a day from 2026-11-01, x = 1, 1, 2
    private Cdb table() {
        final Cdb cdb = Cdb.open(root);
        cdb.run("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY");
        cdb.run("INSERT INTO t VALUES ('2026-11-01T00:00:00.000000Z', 1), ('2026-11-02T00:00:00.000000Z', 1),"
                + " ('2026-11-03T00:00:00.000000Z', 2)");
        return cdb;
    }
}
