package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// `TTL n MONTH` and `TTL n YEAR` expire by calendar months, as QuestDB's TTL does: a partition goes once the whole
/// months between its end and the newest row reach the TTL, counted as `datediff('M')` counts them. They are listed
/// as months and years, not as days.
class TtlCalendarTest {

    @TempDir
    private Path root;

    /// January 1st's partition ends on January 2nd; on February 1st at noon that is 30.5 days back but not yet one
    /// calendar month, so the partition stays where a 30-day month would drop it.
    @Test
    void aMonthTtlKeepsWhatThirtyDaysWouldDrop() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY TTL 1 MONTH");
            cdb.run("INSERT INTO t VALUES ('2026-01-01T06:00:00.000000Z', 1)");
            cdb.run("INSERT INTO t VALUES ('2026-02-01T12:00:00.000000Z', 2)");
            assertThat(rows(cdb.run("SELECT n FROM t"))).containsExactly("1", "2");
        }
    }

    /// January 31st's partition ends on February 1st; on February 28th plus one day, at noon, that is one calendar
    /// month back but 28.5 days, so the partition goes where a 30-day month would keep it.
    @Test
    void aMonthTtlDropsWhatThirtyDaysWouldKeep() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY TTL 1 MONTH");
            cdb.run("INSERT INTO t VALUES ('2026-01-31T06:00:00.000000Z', 1)");
            cdb.run("INSERT INTO t VALUES ('2026-03-01T12:00:00.000000Z', 2)");
            assertThat(rows(cdb.run("SELECT n FROM t"))).containsExactly("2");
        }
    }

    /// `tables()` and `SHOW CREATE TABLE` give a month TTL as months and twelve of them as a year, as QuestDB does,
    /// before and after a reopen.
    @Test
    void aMonthTtlIsListedAsMonthsAndYears() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE m (ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY TTL 1 MONTH");
            cdb.run("CREATE TABLE twelve (ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY MONTH TTL 12 MONTHS");
            cdb.run("CREATE TABLE y (ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY YEAR TTL 2 YEARS");
            assertListed(cdb);
        }
        try (final Cdb cdb = Cdb.open(root)) {
            assertListed(cdb);
        }
    }

    private static void assertListed(final Cdb cdb) {
        assertThat(rows(cdb.run("SELECT table_name, ttlValue, ttlUnit FROM tables()")))
                .containsExactly("m|1|MONTH", "twelve|1|YEAR", "y|2|YEAR");
        assertThat(rows(cdb.run("SHOW CREATE TABLE m")).getFirst()).endsWith("PARTITION BY DAY TTL 1 MONTH;");
        assertThat(rows(cdb.run("SHOW CREATE TABLE twelve")).getFirst()).endsWith("PARTITION BY MONTH TTL 1 YEAR;");
        assertThat(rows(cdb.run("SHOW CREATE TABLE y")).getFirst()).endsWith("PARTITION BY YEAR TTL 2 YEARS;");
    }
}
