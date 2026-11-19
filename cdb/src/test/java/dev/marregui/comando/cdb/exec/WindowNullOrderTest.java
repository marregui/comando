package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// A window's ORDER BY orders a DOUBLE NULL as the query's ORDER BY does and as QuestDB does, above every number:
/// last ascending, first descending. A window with no PARTITION BY runs serially, one with PARTITION BY partition by
/// partition on the query pool: both are covered.
class WindowNullOrderTest {

    private static final String TABLE = "CREATE TABLE w (ts TIMESTAMP, s SYMBOL, d DOUBLE) TIMESTAMP(ts)"
            + " PARTITION BY DAY";

    /// The NULL comes first in time, so input order alone would rank it first.
    private static final String ROWS = "INSERT INTO w VALUES ('2026-11-15T10:00:00.000000Z', 'a', NULL),"
            + " ('2026-11-15T10:01:00.000000Z', 'a', 2.0), ('2026-11-15T10:02:00.000000Z', 'a', 1.0)";

    @TempDir
    private Path root;

    /// `row_number` and `lag` over `ORDER BY d` see 1.0, 2.0, NULL, the order `SELECT d FROM w ORDER BY d` returns.
    @Test
    void ascendingPutsTheNullLast() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run(ROWS);
            assertThat(rows(cdb.run("SELECT d FROM w ORDER BY d"))).containsExactly("1.0", "2.0", "");
            assertThat(rows(cdb.run("SELECT d, row_number() OVER (ORDER BY d), lag(d) OVER (ORDER BY d) FROM w")))
                    .containsExactly(
                            "|3|2.0",
                            "2.0|2|1.0",
                            "1.0|1|"
                    );
            assertThat(rows(cdb.run("SELECT d, row_number() OVER (PARTITION BY s ORDER BY d) FROM w")))
                    .containsExactly(
                            "|3",
                            "2.0|2",
                            "1.0|1"
                    );
        }
    }

    /// Descending, the NULL comes first, as `ORDER BY d DESC` returns it.
    @Test
    void descendingPutsTheNullFirst() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run(ROWS);
            assertThat(rows(cdb.run("SELECT d FROM w ORDER BY d DESC"))).containsExactly("", "2.0", "1.0");
            assertThat(rows(cdb.run("SELECT d, row_number() OVER (ORDER BY d DESC) FROM w")))
                    .containsExactly(
                            "|1",
                            "2.0|2",
                            "1.0|3"
                    );
            assertThat(rows(cdb.run("SELECT d, row_number() OVER (PARTITION BY s ORDER BY d DESC) FROM w")))
                    .containsExactly(
                            "|1",
                            "2.0|2",
                            "1.0|3"
                    );
        }
    }
}
