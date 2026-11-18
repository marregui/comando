package dev.marregui.comando.cdb;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `Cdb.explain(sql)`: the plan tree, one node per line, each with the executor's choices after ` -- `: the columns
/// a scan reads once unused ones are pruned and the ones its pre-filter terms select on, whether an operator runs per
/// frame on the query pool or serially, a sort that reads its other columns late, and a join's build side. The
/// statement is planned, never run.
class CdbExplainTest {

    @TempDir
    private Path root;

    /// A cdb over `root` with `t` (one row) and `u` (empty).
    private Cdb open() {
        final Cdb cdb = Cdb.open(root);
        try {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, s SYMBOL, v LONG, d DOUBLE, x VARCHAR) TIMESTAMP(ts)"
                    + " PARTITION BY DAY");
            cdb.run("CREATE TABLE u (ts TIMESTAMP, s SYMBOL, w LONG) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("INSERT INTO t VALUES ('2026-11-15T10:00:00.000000Z', 'a', 1, 1.5, 'q')");
            return cdb;
        } catch (final RuntimeException e) {
            cdb.close();
            throw e;
        }
    }

    /// A scan reads only the columns above it use; a text term selects on its column in the partition, and the
    /// projection over the scan runs per frame.
    @Test
    void aScanReadsOnlyWhatIsUsed() {
        try (Cdb cdb = open()) {
            assertThat(cdb.explain("SELECT v FROM t WHERE s = 'a'")).isEqualTo(
                    "Project [v:LONG=$2] -- per frame on the pool\n"
                            + "  Scan t [0, 1, 2, 3, 4] [-inf, +inf) terms [$1 = 'a'] -- reads [v], selects on [s],"
                            + " frames on the pool\n");
        }
    }

    /// A grouped aggregate over a scan runs per frame; one over a random generator's projection runs serially, so the
    /// generator's values keep their serial order.
    @Test
    void parallelOrSerial() {
        try (Cdb cdb = open()) {
            assertThat(cdb.explain("SELECT s, sum(v) FROM t")).isEqualTo("""
                    Aggregate keys=[s:SYMBOL=$1] aggs=[sum(v):LONG=sum($2)] -- parallel over frames
                      Scan t [0, 1, 2, 3, 4] [-inf, +inf) -- reads [s, v], frames on the pool
                    """);
            assertThat(cdb.explain("SELECT v, rnd_double() FROM t")).isEqualTo("""
                    Project [v:LONG=$2, rnd_double():DOUBLE=rnd_double()] -- serial
                      Scan t [0, 1, 2, 3, 4] [-inf, +inf) -- reads [v], frames on the pool
                    """);
        }
    }

    /// A sort with a limit over every column reads its key column first and the others for the kept rows only.
    @Test
    void aSortReadsLate() {
        try (Cdb cdb = open()) {
            assertThat(cdb.explain("SELECT * FROM t ORDER BY v LIMIT 3").lines()).element(1).isEqualTo(
                    "  Sort [$2] -- parallel over frames, read late: the columns the keys and the filter do not"
                            + " need are read for the kept rows only");
        }
    }

    /// A join reads its right side whole first and streams its left side.
    @Test
    void aJoinBuildsItsRightSide() {
        try (Cdb cdb = open()) {
            assertThat(cdb.explain("SELECT t.v, u.w FROM t JOIN u ON t.s = u.s").lines()).element(1).isEqualTo(
                    "  Join inner on =($1, $6) -- parallel over frames, build side right: read whole first, the left"
                            + " streams");
        }
    }

    /// Explaining a change plans it and leaves the table as it was; the change itself carries no note.
    @Test
    void explainingAChangeDoesNotRunIt() {
        try (Cdb cdb = open()) {
            assertThat(cdb.explain("UPDATE t SET v = 2")).isEqualTo("Update t set $2=2\n");
            assertThat(cdb.explain("INSERT INTO u SELECT ts, s, v FROM t").lines()).first()
                    .isEqualTo("Insert u [0, 1, 2]");
            final CdbResult result = cdb.run("SELECT v FROM t");
            assertThat(result.rowCount()).isEqualTo(1);
            assertThat(result.getLong(0, 0)).isEqualTo(1);
            assertThat(cdb.run("SELECT count() FROM u").getLong(0, 0)).isZero();
        }
    }
}
