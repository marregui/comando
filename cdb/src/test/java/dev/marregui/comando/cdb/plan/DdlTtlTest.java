package dev.marregui.comando.cdb.plan;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// CREATE and ALTER refuse the TTLs QuestDB's `validateTtlGranularity` refuses, and a dedup key named twice, as
/// QuestDB's parser does (`duplicate dedup column`); what QuestDB accepts, cdb accepts.
class DdlTtlTest {

    @TempDir
    private Path root;

    /// A TTL needs partitions to drop, and must be a whole number of them: a month TTL is whole on DAY and WEEK
    /// partitions, an hour or week one is not on MONTH partitions, and a YEAR partition takes whole years only.
    @Test
    void aTtlMustBeAWholeNumberOfPartitions() {
        try (final Cdb cdb = Cdb.open(root)) {
            for (final String refused : new String[] {
                    "PARTITION BY NONE TTL 1 DAY",
                    "PARTITION BY NONE TTL 0 HOURS",
                    "PARTITION BY DAY TTL 36 HOURS",
                    "PARTITION BY WEEK TTL 10 DAYS",
                    "PARTITION BY MONTH TTL 2 WEEKS",
                    "PARTITION BY YEAR TTL 6 MONTHS"}) {
                assertThatThrownBy(() -> cdb.run("CREATE TABLE r (ts TIMESTAMP) TIMESTAMP(ts) " + refused))
                        .as(refused).isInstanceOf(CdbException.class);
            }
            int n = 0;
            for (final String accepted : new String[] {
                    "PARTITION BY HOUR TTL 3 HOURS",
                    "PARTITION BY DAY TTL 48 HOURS",
                    "PARTITION BY DAY TTL 1 MONTH",
                    "PARTITION BY WEEK TTL 2 WEEKS",
                    "PARTITION BY WEEK TTL 1 MONTH",
                    "PARTITION BY MONTH TTL 3 MONTHS",
                    "PARTITION BY MONTH TTL 0 HOURS",
                    "PARTITION BY YEAR TTL 24 MONTHS"}) {
                cdb.run("CREATE TABLE a" + n++ + " (ts TIMESTAMP) TIMESTAMP(ts) " + accepted);
            }
            assertThat(n).isEqualTo(8);
        }
    }

    /// ALTER TABLE SET TTL is checked against the table's partitioning, as QuestDB checks it.
    @Test
    void alterChecksTheTablesPartitioning() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE m (ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY MONTH");
            cdb.run("CREATE TABLE plain (ts TIMESTAMP)");
            assertThatThrownBy(() -> cdb.run("ALTER TABLE m SET TTL 1 DAY")).isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("ALTER TABLE plain SET TTL 1 MONTH")).isInstanceOf(CdbException.class);
            assertThat(cdb.run("ALTER TABLE m SET TTL 3 MONTHS").updateCount()).isZero();
        }
    }

    /// A dedup key named twice, in any case, is refused; each named once is kept.
    @Test
    void aDedupKeyNamedTwiceIsRefused() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThatThrownBy(() -> cdb.run("CREATE TABLE d (ts TIMESTAMP, k SYMBOL) TIMESTAMP(ts) PARTITION BY DAY"
                    + " DEDUP UPSERT KEYS(ts, k, ts)")).isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("CREATE TABLE d (ts TIMESTAMP, k SYMBOL) TIMESTAMP(ts) PARTITION BY DAY"
                    + " DEDUP UPSERT KEYS(ts, k, K)")).isInstanceOf(CdbException.class);
            cdb.run("CREATE TABLE d (ts TIMESTAMP, k SYMBOL) TIMESTAMP(ts) PARTITION BY DAY DEDUP UPSERT KEYS(ts, k)");
        }
    }

    /// A `dateadd` folded at plan time past the calendar's range, and a TTL past a long, are refused as user errors.
    @Test
    void outOfRangeConstantsAreUserErrors() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY");
            assertThatThrownBy(() -> cdb.run("SELECT count() FROM t WHERE ts < dateadd('y', 1000000000, now())"))
                    .isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("CREATE TABLE u (ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY"
                    + " TTL 99999999999999 YEARS")).isInstanceOf(CdbException.class);
        }
    }
}
