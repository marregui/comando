package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbResult;

import static dev.marregui.comando.cdb.exec.SqlRows.columns;
import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// `upper`, `lower`, `length`, `abs` and `lead(v) OVER (ORDER BY ts)` end to end in SQL: the planner types them and
/// the executor returns their values, NULL in and out as QuestDB has it (`length` of NULL is -1).
class TextFunctionsAndLeadTest {

    private static final String TABLE = "CREATE TABLE t (ts TIMESTAMP, s VARCHAR, v LONG, d DOUBLE) TIMESTAMP(ts)"
            + " PARTITION BY DAY";

    private static final String ROWS = "INSERT INTO t VALUES ('2026-11-15T10:00:00.000000Z', 'MiXed', -3, -1.5),"
            + " ('2026-11-15T10:01:00.000000Z', 'abc', 4, 2.0), ('2026-11-15T10:02:00.000000Z', NULL, NULL, NULL)";

    @TempDir
    private Path root;

    /// `upper(s)`, `lower(s)`, `length(s)` and `abs(v)`, `abs(d)` per row, with their QuestDB types.
    @Test
    void textFunctionsAndAbsReturnValues() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run(ROWS);
            final CdbResult result = cdb.run("SELECT upper(s) u, lower(s) l, length(s) n, abs(v) a, abs(d) b FROM t");
            assertThat(columns(result)).containsExactly("u:VARCHAR", "l:VARCHAR", "n:LONG", "a:LONG", "b:DOUBLE");
            assertThat(rows(result)).containsExactly(
                    "MIXED|mixed|5|3|1.5",
                    "ABC|abc|3|4|2.0",
                    "||-1||"
            );
        }
    }

    /// `lead(v) OVER (ORDER BY ts)` is the next row's value and NULL on the last row; with an offset and a default,
    /// the row that many ahead and the default past the end.
    @Test
    void leadReturnsTheValueAhead() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run(ROWS);
            cdb.run("INSERT INTO t VALUES ('2026-11-15T10:03:00.000000Z', 'd', 7, 0.0)");
            final CdbResult result = cdb.run("SELECT v, lead(v) OVER (ORDER BY ts) n,"
                    + " lead(v, 2, -9) OVER (ORDER BY ts) m FROM t");
            assertThat(columns(result)).containsExactly("v:LONG", "n:LONG", "m:LONG");
            assertThat(rows(result)).containsExactly(
                    "-3|4|",
                    "4||7",
                    "|7|-9",
                    "7||-9"
            );
        }
    }
}
