package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// `lead`, `first_value` and `last_value` over a window, end to end in SQL, as QuestDB answers them: rows come back
/// in input order; with ORDER BY the frame runs from the partition's first row to the current one, without it the
/// frame is the whole partition; `first_value` and `last_value` keep a NULL they meet. A window with no PARTITION BY
/// runs serially, one with PARTITION BY partition by partition on the query pool: both are covered. Each query
/// holds no two calls of one function on the same arguments, which the planner today takes for one call.
class WindowValueFunctionsTest {

    private static final String TABLE = "CREATE TABLE w (ts TIMESTAMP, s SYMBOL, v LONG) TIMESTAMP(ts)"
            + " PARTITION BY DAY";

    /// Partition `a` holds 1, 3, 5 and partition `b` holds 2, 4, interleaved in time.
    private static final String ROWS = "INSERT INTO w VALUES ('2026-11-15T10:00:00.000000Z', 'a', 1),"
            + " ('2026-11-15T10:01:00.000000Z', 'b', 2), ('2026-11-15T10:02:00.000000Z', 'a', 3),"
            + " ('2026-11-15T10:03:00.000000Z', 'b', 4), ('2026-11-15T10:04:00.000000Z', 'a', 5)";

    @TempDir
    private Path root;

    /// Without PARTITION BY: `first_value` with ORDER BY is the first row's value on every row, `last_value` with
    /// ORDER BY is the current row's, and `last_value` over the whole input is the last row's.
    @Test
    void firstAndLastValueOverTheWholeInput() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run(ROWS);
            assertThat(rows(cdb.run("SELECT v, first_value(v) OVER (ORDER BY ts), last_value(v) OVER (ORDER BY ts)"
                    + " FROM w"))).containsExactly(
                    "1|1|1",
                    "2|1|2",
                    "3|1|3",
                    "4|1|4",
                    "5|1|5"
            );
            assertThat(rows(cdb.run("SELECT v, last_value(v) OVER () FROM w"))).containsExactly(
                    "1|5",
                    "2|5",
                    "3|5",
                    "4|5",
                    "5|5"
            );
        }
    }

    /// With PARTITION BY: `lead` stays inside the partition, `first_value` is the partition's first row, and
    /// `last_value` without ORDER BY the partition's last.
    @Test
    void leadFirstAndLastValueByPartition() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run(ROWS);
            assertThat(rows(cdb.run("SELECT v, lead(v) OVER (PARTITION BY s ORDER BY ts),"
                    + " lead(v, 1, 0) OVER (PARTITION BY s ORDER BY ts DESC),"
                    + " first_value(v) OVER (PARTITION BY s ORDER BY ts), last_value(v) OVER (PARTITION BY s)"
                    + " FROM w"))).containsExactly(
                    "1|3|0|1|5",
                    "2|4|0|2|4",
                    "3|5|1|1|5",
                    "4||2|2|4",
                    "5||3|1|5"
            );
        }
    }

    /// A NULL in the frame is a value to `first_value` and `last_value`: the first row's NULL is every row's
    /// `first_value`, and the last row's NULL is `last_value` over the whole input.
    @Test
    void firstAndLastValueKeepNull() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run("INSERT INTO w VALUES ('2026-11-15T10:00:00.000000Z', 'a', NULL),"
                    + " ('2026-11-15T10:01:00.000000Z', 'a', 2), ('2026-11-15T10:02:00.000000Z', 'a', NULL)");
            assertThat(rows(cdb.run("SELECT first_value(v) OVER (ORDER BY ts), last_value(v) OVER () FROM w")))
                    .containsExactly("|", "|", "|");
            assertThat(rows(cdb.run("SELECT first_value(v) OVER (PARTITION BY s), last_value(v) OVER (PARTITION BY s"
                    + " ORDER BY ts) FROM w"))).containsExactly("|", "|2", "|");
        }
    }

    /// `lead` past the end of a partition with a default of the column's type gives the default, and a lead of 0 is
    /// the row itself.
    @Test
    void leadOffsetZeroAndDefault() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run(ROWS);
            assertThat(rows(cdb.run("SELECT v, lead(v, 0) OVER (ORDER BY ts), lead(v, 4, -1) OVER (ORDER BY ts)"
                    + " FROM w"))).containsExactly(
                    "1|1|5",
                    "2|2|-1",
                    "3|3|-1",
                    "4|4|-1",
                    "5|5|-1"
            );
        }
    }
}
