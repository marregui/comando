package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `INSERT INTO t [(columns)] VALUES (...)[, (...)]` and `INSERT INTO t SELECT ...` end to end: values coerced to the
/// column types as QuestDB coerces them, left-out columns NULL, the designated timestamp required, the rows committed
/// through the table's writer before the statement returns.
class InsertTest {

    private static final String TABLE = "CREATE TABLE t (ts TIMESTAMP, s SYMBOL, v VARCHAR, n LONG, d DOUBLE,"
            + " b BOOLEAN) TIMESTAMP(ts) PARTITION BY DAY";

    @TempDir
    private Path root;

    /// Several rows in one statement: the update count is the rows inserted, and every literal lands in its column.
    @Test
    void valuesRowsAreInsertedAndCounted() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            assertThat(cdb.run("INSERT INTO t VALUES ('2026-11-15T10:07:00.000000Z', 'a', 'x', 1, 1.5, true),"
                    + " ('2026-11-15T10:08:00.000000Z', 'b', 'y', -2, 2.5, false)").updateCount()).isEqualTo(2);
            assertThat(rows(cdb.run("SELECT * FROM t"))).containsExactly(
                    "2026-11-15T10:07:00Z|a|x|1|1.5|true",
                    "2026-11-15T10:08:00Z|b|y|-2|2.5|false"
            );
        }
    }

    /// A column list in another order than the table's maps each value by name, and the columns it leaves out read
    /// NULL (BOOLEAN, which has no NULL, reads false).
    @Test
    void columnsLeftOutAreNull() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run("INSERT INTO t (n, ts) VALUES (7, '2026-11-15T10:07:00.000000Z')");
            assertThat(rows(cdb.run("SELECT * FROM t"))).containsExactly("2026-11-15T10:07:00Z|||7||false");
        }
    }

    /// An integer goes into DOUBLE, a quoted ISO string into TIMESTAMP, SYMBOL and VARCHAR, and expressions (casts,
    /// arithmetic, `CAST(null AS TIMESTAMP)`) are evaluated before they land.
    @Test
    void valuesAreCoercedToTheColumnTypes() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE c (ts TIMESTAMP, d DOUBLE, at TIMESTAMP, s SYMBOL, v VARCHAR, n LONG)"
                    + " TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("INSERT INTO c VALUES (CAST('2026-11-15T10:07:00.000000Z' AS TIMESTAMP), 3,"
                    + " '2026-11-15T10:00:00.000000Z', '2026-11-15T10:00:00.000000Z', 'it''s', 2 * 21 + 1)");
            cdb.run("INSERT INTO c (ts, at, n) VALUES ('2026-11-15T10:08:00.000000Z', CAST(null AS TIMESTAMP), null)");
            assertThat(rows(cdb.run("SELECT * FROM c"))).containsExactly(
                    "2026-11-15T10:07:00Z|3.0|2026-11-15T10:00:00Z|2026-11-15T10:00:00.000000Z|it's|43",
                    "2026-11-15T10:08:00Z|||||"
            );
        }
    }

    /// `now()` as the designated timestamp gives the statement's time.
    @Test
    void nowIsTheStatementsTime() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            final long before = System.currentTimeMillis() * 1_000;
            cdb.run("INSERT INTO t (ts, s) VALUES (now(), 'a')");
            final long ts = cdb.run("SELECT ts FROM t").getLong(0, 0);
            assertThat(ts).isBetween(before, System.currentTimeMillis() * 1_000);
        }
    }

    /// A statement without the designated timestamp, with a NULL one, with a value count other than the column count,
    /// or with a value of a type the column cannot take is refused, and nothing is written.
    @Test
    void invalidInsertsAreRefused() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            assertThatThrownBy(() -> cdb.run("INSERT INTO t (s) VALUES ('a')"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot insert without designated timestamp [table=t, column=ts]");
            assertThatThrownBy(() -> cdb.run("INSERT INTO t (ts, s) VALUES (null, 'a')"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot insert NULL designated timestamp [column=ts]");
            assertThatThrownBy(() -> cdb.run("INSERT INTO t (ts, s) VALUES (now())"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot insert row of other value count [table=t, expected=2, actual=1]");
            assertThatThrownBy(() -> cdb.run("INSERT INTO t (ts, n) VALUES (now(), 'x')"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot insert value of other type [column=n, type=VARCHAR, target=LONG]");
            assertThatThrownBy(() -> cdb.run("INSERT INTO t (ts, nope) VALUES (now(), 1)"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot find column [column=nope]");
            assertThatThrownBy(() -> cdb.run("INSERT INTO t (ts, s, s) VALUES (now(), 'a', 'b')"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot insert column twice [column=s]");
            assertThatThrownBy(() -> cdb.run("INSERT INTO nope VALUES (now())"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot find table [table=nope]");
            assertThat(rows(cdb.run("SELECT count() FROM t"))).containsExactly("0");
        }
    }

    /// The rows go through the writer as an append does, so a DEDUP table keeps the last row of each key.
    @Test
    void insertDeduplicatesAsAnAppend() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE k (ts TIMESTAMP, name SYMBOL, n LONG) TIMESTAMP(ts) PARTITION BY DAY"
                    + " DEDUP UPSERT KEYS(ts, name)");
            cdb.run("INSERT INTO k VALUES ('2026-11-15T10:07:00.000000Z', 'a', 1)");
            cdb.run("INSERT INTO k VALUES ('2026-11-15T10:07:00.000000Z', 'a', 2),"
                    + " ('2026-11-15T10:07:00.000000Z', 'b', 3)");
            assertThat(rows(cdb.run("SELECT name, n FROM k ORDER BY name"))).containsExactly("a|2", "b|3");
        }
    }

    /// `INSERT INTO t SELECT ...` writes every row of the query, as a bulk load from `long_sequence` does; the query's
    /// column count must match.
    @Test
    void insertSelectWritesTheQuerysRows() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE bench (ts TIMESTAMP, n LONG, d DOUBLE) TIMESTAMP(ts) PARTITION BY HOUR");
            assertThat(cdb.run("INSERT INTO bench SELECT CAST(x * 60000000 AS TIMESTAMP), x, x / 2.0"
                    + " FROM long_sequence(5000)").updateCount()).isEqualTo(5000);
            assertThat(rows(cdb.run("SELECT count(), sum(n), max(d), min(ts), max(ts) FROM bench")))
                    .containsExactly("5000|12502500|2500.0|1970-01-01T00:01:00Z|1970-01-04T11:20:00Z");
            assertThatThrownBy(() -> cdb.run("INSERT INTO bench SELECT CAST(x AS TIMESTAMP) FROM long_sequence(1)"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot insert query of other column count [table=bench, expected=3, actual=1]");
        }
    }

    /// The rows survive a reopen: the statement committed them.
    @Test
    void insertedRowsSurviveAReopen() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(TABLE);
            cdb.run("INSERT INTO t (ts, s) VALUES ('2026-11-15T10:07:00.000000Z', 'a')");
        }
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(rows(cdb.run("SELECT ts, s FROM t"))).containsExactly("2026-11-15T10:07:00Z|a");
        }
    }
}
