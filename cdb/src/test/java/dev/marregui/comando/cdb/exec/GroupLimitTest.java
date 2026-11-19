package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// An operator that holds every group or distinct row before its first output row holds at most
/// [AggKeys#MAX_GROUPS] of them, then fails the query with a [CdbException] rather than run the heap out: GROUP BY,
/// on the calling thread and merged from the query pool's frames, DISTINCT, and the right side of EXCEPT and
/// INTERSECT.
class GroupLimitTest {

    private static final int LIMIT = 1_000_000;

    @TempDir
    private Path root;

    /// The bound is the IN subquery's, which is the server's row limit.
    @Test
    void theBoundIsTheInSubquerys() {
        assertThat(AggKeys.MAX_GROUPS).isEqualTo(LIMIT).isEqualTo(Executor.IN_SUBQUERY_MAX_ROWS);
    }

    /// A GROUP BY of exactly the bound's groups runs; one more group fails.
    @Test
    void groupByHoldsAtMostTheBound() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(cdb.run("SELECT x, count() FROM long_sequence(" + LIMIT + ") GROUP BY x").rowCount())
                    .isEqualTo(LIMIT);
            assertThatThrownBy(() -> cdb.run("SELECT x, count() FROM long_sequence(" + (LIMIT + 1) + ") GROUP BY x"))
                    .isInstanceOf(CdbException.class);
        }
    }

    /// A GROUP BY over a table runs per frame on the query pool; the merged table is bounded as the serial one is.
    @Test
    void aParallelGroupByIsBoundedWhenMerged() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, v LONG) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("INSERT INTO t SELECT CAST(x AS TIMESTAMP), x FROM long_sequence(" + (LIMIT + 1) + ")");
            assertThat(cdb.run("SELECT v, count() FROM t WHERE v <= " + LIMIT + " GROUP BY v").rowCount())
                    .isEqualTo(LIMIT);
            assertThatThrownBy(() -> cdb.run("SELECT v, count() FROM t GROUP BY v")).isInstanceOf(CdbException.class);
        }
    }

    /// DISTINCT keeps its rows in a key table, bounded alike.
    @Test
    void distinctHoldsAtMostTheBound() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThatThrownBy(() -> cdb.run("SELECT DISTINCT x FROM long_sequence(" + (LIMIT + 1) + ")"))
                    .isInstanceOf(CdbException.class);
        }
    }

    /// EXCEPT and INTERSECT read their right side whole into a set: exactly the bound's rows run, one more fails.
    @Test
    void theRightSideOfExceptAndIntersectHoldsAtMostTheBound() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(cdb.run("SELECT x FROM long_sequence(3) EXCEPT SELECT x FROM long_sequence(" + LIMIT + ")")
                    .rowCount()).isZero();
            assertThatThrownBy(() -> cdb.run("SELECT x FROM long_sequence(3) EXCEPT SELECT x FROM long_sequence("
                    + (LIMIT + 1) + ")")).isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("SELECT x FROM long_sequence(3) INTERSECT SELECT x FROM long_sequence("
                    + (LIMIT + 1) + ")")).isInstanceOf(CdbException.class);
        }
    }
}
