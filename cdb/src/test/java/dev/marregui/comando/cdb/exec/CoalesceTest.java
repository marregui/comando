package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;

import static dev.marregui.comando.cdb.exec.SqlRows.columns;
import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `coalesce(a, b, ...)` per type family, against what QuestDB 10.2 answers: the first non-NULL argument, a bare NULL
/// typed as the others, numbers widened to DOUBLE, text to VARCHAR, and no mix of families.
class CoalesceTest {

    @TempDir
    private Path root;

    /// Each family gives its own type, and the first non-NULL value; QuestDB answers the same values (its INT and
    /// STRING are cdb's LONG and VARCHAR).
    @Test
    void eachFamilyKeepsItsType() {
        try (final Cdb cdb = Cdb.open(root)) {
            final CdbResult result = cdb.run("SELECT coalesce(null, 0) l, coalesce(CAST(null AS LONG), 2.5) d,"
                    + " coalesce(CAST(null AS SYMBOL), CAST('z' AS VARCHAR)) v, coalesce(CAST(null AS SYMBOL), 'a') s,"
                    + " coalesce(null, CAST('2026-01-01T00:00:00.000000Z' AS TIMESTAMP)) t,"
                    + " coalesce(null, null, 3, 4) third FROM long_sequence(1)");
            assertThat(columns(result))
                    .containsExactly("l:LONG", "d:DOUBLE", "v:VARCHAR", "s:VARCHAR", "t:TIMESTAMP", "third:LONG");
            assertThat(rows(result)).containsExactly("0|2.5|z|a|2026-01-01T00:00:00Z|3");
        }
    }

    /// Over a column, NULL cells take the fallback and the others keep their value; over an empty aggregate the
    /// fallback is the answer, as `coalesce(max(seq), 0)` gives on QuestDB.
    @Test
    void columnsAndAggregatesFallBack() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG, d DOUBLE, v VARCHAR) TIMESTAMP(ts) PARTITION BY DAY");
            assertThat(rows(cdb.run("SELECT coalesce(max(n), 0) FROM t"))).containsExactly("0");
            cdb.run("INSERT INTO t VALUES ('2026-11-15T10:00:00.000000Z', null, null, null),"
                    + " ('2026-11-15T10:01:00.000000Z', 5, 1.5, 'x')");
            assertThat(rows(cdb.run("SELECT coalesce(n, -1), coalesce(d, n, 0), coalesce(v, 'none') FROM t")))
                    .containsExactly("-1|0.0|none", "5|1.5|x");
            assertThat(rows(cdb.run("SELECT coalesce(max(n), 0) FROM t"))).containsExactly("5");
        }
    }

    /// One argument, or families that do not mix (a number with text, a TIMESTAMP with a string or a number), are
    /// refused as QuestDB refuses them.
    @Test
    void mismatchedArgumentsAreRefused() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThatThrownBy(() -> cdb.run("SELECT coalesce(x) FROM long_sequence(1)"))
                    .isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("SELECT coalesce(x, 'a') FROM long_sequence(1)"))
                    .isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("SELECT coalesce(CAST(null AS TIMESTAMP), 5) FROM long_sequence(1)"))
                    .isInstanceOf(CdbException.class);
        }
    }
}
