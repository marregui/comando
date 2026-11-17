package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// `=`, `!=` and `in` of a SYMBOL column with text constants, in filters the scan does not take whole (an `or`), match
/// by dictionary key what they match by text: NULL rows, a constant the dictionary does not hold, and a value added to
/// the dictionary after an earlier query.
class SymbolEqualityTest {

    @TempDir
    private Path root;

    /// Each form counts the rows its text comparison counts, NULL rows matching neither `=` nor `in`.
    @Test
    void equalityByKeyMatchesEqualityByText() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("create table t (sym symbol, price double, ts timestamp) timestamp(ts) partition by day");
            cdb.run("insert into t values ('A', 1.0, '2026-01-01T00:00:00Z'), ('B', 2.0, '2026-01-01T01:00:00Z'),"
                    + " (null, 3.0, '2026-01-02T00:00:00Z'), ('A', 4.0, '2026-01-02T01:00:00Z')");
            assertThat(count(cdb, "sym = 'A' or price > 100")).isEqualTo(2);
            assertThat(count(cdb, "'A' = sym or price > 100")).isEqualTo(2);
            assertThat(count(cdb, "sym != 'A' or price > 100")).isEqualTo(2);
            assertThat(count(cdb, "sym in ('A', 'B') or price > 100")).isEqualTo(3);
            assertThat(count(cdb, "sym not in ('A') or price > 100")).isEqualTo(2);
            assertThat(count(cdb, "sym = 'Z' or price > 100")).isZero();
            assertThat(count(cdb, "sym in ('Z') or price > 100")).isZero();
            cdb.run("insert into t values ('Z', 5.0, '2026-01-03T00:00:00Z')");
            assertThat(count(cdb, "sym = 'Z' or price > 100")).isEqualTo(1);
            assertThat(rows(cdb.run("select price from t where sym = 'Z' or sym = 'B' order by price")))
                    .containsExactly("2.0", "5.0");
        }
    }

    private static long count(final Cdb cdb, final String where) {
        return cdb.run("select count() from t where " + where).getLong(0, 0);
    }
}
