package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// LATEST ON by one SYMBOL key read newest first stops once every value the column can hold is found, and answers what
/// the whole read answers: a NULL key in the oldest partition, a dictionary value no row holds any more and a filter
/// keep the read going as far as the answer needs.
class LatestOnEarlyStopTest {

    private static final String CREATE = "create table t (sym symbol, price double, ts timestamp) timestamp(ts)"
            + " partition by day";
    private static final String LATEST = "select sym, price, ts from t latest on ts partition by sym";

    @TempDir
    private Path root;

    /// Three symbols over four days, each latest row in another day: one row per symbol, in timestamp order.
    @Test
    void latestRowPerSymbolAcrossPartitions() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('A', 1.0, '2026-01-01T00:00:00Z'), ('B', 2.0, '2026-01-01T01:00:00Z'),"
                    + " ('C', 3.0, '2026-01-02T00:00:00Z'), ('A', 4.0, '2026-01-03T00:00:00Z'),"
                    + " ('B', 5.0, '2026-01-04T00:00:00Z'), ('A', 6.0, '2026-01-04T01:00:00Z')");
            assertThat(rows(cdb.run(LATEST))).containsExactly(
                    "C|3.0|2026-01-02T00:00:00Z",
                    "B|5.0|2026-01-04T00:00:00Z",
                    "A|6.0|2026-01-04T01:00:00Z");
        }
    }

    /// A NULL key held only by the oldest partition is still answered: every dictionary key is found in the newer
    /// partitions, and the read goes on for the NULL the oldest one holds.
    @Test
    void aNullKeyInTheOldestPartitionIsAnswered() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values (null, 1.0, '2026-01-01T00:00:00Z'), ('A', 2.0, '2026-01-02T00:00:00Z'),"
                    + " ('B', 3.0, '2026-01-03T00:00:00Z'), ('A', 4.0, '2026-01-03T01:00:00Z')");
            assertThat(rows(cdb.run(LATEST))).containsExactly(
                    "|1.0|2026-01-01T00:00:00Z",
                    "B|3.0|2026-01-03T00:00:00Z",
                    "A|4.0|2026-01-03T01:00:00Z");
        }
    }

    /// A dictionary value that no row holds after an update leaves the read to the end; the answer is unchanged.
    @Test
    void aDictionaryValueNoRowHoldsReadsToTheEnd() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('A', 1.0, '2026-01-01T00:00:00Z'), ('GONE', 2.0, '2026-01-02T00:00:00Z'),"
                    + " ('B', 3.0, '2026-01-03T00:00:00Z')");
            cdb.run("update t set sym = 'A' where sym = 'GONE'");
            assertThat(rows(cdb.run(LATEST))).containsExactly(
                    "A|2.0|2026-01-02T00:00:00Z",
                    "B|3.0|2026-01-03T00:00:00Z");
        }
    }

    /// With a filter, a key's latest row is its latest row that passes, however old.
    @Test
    void aFilterKeepsEachKeysLatestPassingRow() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('A', 10.0, '2026-01-01T00:00:00Z'), ('B', 20.0, '2026-01-02T00:00:00Z'),"
                    + " ('A', 1.0, '2026-01-03T00:00:00Z'), ('B', 2.0, '2026-01-04T00:00:00Z')");
            assertThat(rows(cdb.run("select sym, price, ts from t where price > 5 latest on ts partition by sym")))
                    .containsExactly(
                            "A|10.0|2026-01-01T00:00:00Z",
                            "B|20.0|2026-01-02T00:00:00Z");
        }
    }

    /// Forty symbols over 70 daily partitions agree with the latest timestamp per symbol from GROUP BY.
    @Test
    void manySymbolsAgreeWithGroupBy() {
        final StringJoiner symbols = new StringJoiner(", ");
        for (int s = 0; s < 40; s++) {
            symbols.add("'S" + s + "'");
        }
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t select rnd_symbol(" + symbols + "), rnd_double(),"
                    + " timestamp_sequence('2026-01-01', 30000000L) from long_sequence(200000)");
            final List<String> latest = rows(cdb.run("select sym, ts from (" + LATEST + ") order by sym"));
            final List<String> grouped = rows(cdb.run("select sym, max(ts) from t group by sym order by sym"));
            assertThat(latest).hasSize(40).isEqualTo(grouped);
        }
    }

    /// A cancelled query fails instead of answering: the serial newest-first read checks the cancel at each frame and
    /// page, and the NULL check at each partition.
    @Test
    void aCancelledReadFails() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values (null, 1.0, '2026-01-01T00:00:00Z'), ('A', 2.0, '2026-01-02T00:00:00Z')");
            final CdbCancel cancel = new CdbCancel();
            cancel.cancel();
            assertThatThrownBy(() -> cdb.run(LATEST, 100, 0L, cancel)).isInstanceOf(CdbException.class);
        }
    }
}
