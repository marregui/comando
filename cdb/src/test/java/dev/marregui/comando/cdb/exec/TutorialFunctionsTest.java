package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.plan.Bound;

import static dev.marregui.comando.cdb.exec.SqlRows.columns;
import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The tutorial's statements run unmodified: `rnd_symbol`, `rnd_double`, `timestamp_sequence` and the LONG literal
/// suffix `L`, as QuestDB runs them.
class TutorialFunctionsTest {

    private static final String CREATE = "create table trades (sym symbol, price double, ts timestamp) timestamp(ts)"
            + " partition by day";
    private static final String INSERT = "insert into trades select rnd_symbol('AAPL', 'MSFT', 'NVDA'),"
            + " 100 + rnd_double() * 50, timestamp_sequence('2026-01-01', 60000000L) from long_sequence(100000)";

    @TempDir
    private Path root;

    /// The tutorial's insert lands 100,000 rows a minute apart from 2026-01-01, each of the three symbols about a third
    /// of them, every price in [100, 150).
    @Test
    void tutorialInsertFillsTrades() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            assertThat(cdb.run(INSERT).updateCount()).isEqualTo(100_000);
            final CdbResult span = cdb.run("select count(), min(ts), max(ts) from trades");
            assertThat(span.getLong(0, 0)).isEqualTo(100_000);
            assertThat(span.getLong(0, 1)).isEqualTo(micros("2026-01-01T00:00:00.000000Z"));
            assertThat(span.getLong(0, 2)).isEqualTo(micros("2026-03-11T10:39:00.000000Z"));
            final CdbResult second = cdb.run("select ts from trades limit 2");
            assertThat(second.getLong(1, 0)).isEqualTo(micros("2026-01-01T00:01:00.000000Z"));
            final CdbResult symbols = cdb.run("select sym, count() from trades order by sym");
            assertThat(symbols.rowCount()).isEqualTo(3);
            for (int row = 0; row < 3; row++) {
                assertThat(symbols.getText(row, 0)).isEqualTo(List.of("AAPL", "MSFT", "NVDA").get(row));
                assertThat(symbols.getLong(row, 1)).isBetween(32_000L, 34_700L);
            }
            final CdbResult prices = cdb.run("select min(price), max(price) from trades");
            assertThat(prices.getDouble(0, 0)).isGreaterThanOrEqualTo(100.0).isLessThan(150.0);
            assertThat(prices.getDouble(0, 1)).isGreaterThanOrEqualTo(100.0).isLessThan(150.0);
        }
    }

    /// `rnd_double()` is a DOUBLE in [0, 1) per row, so the sum of 1,000 of them lies strictly between 0 and 1,000.
    @Test
    void rndDoubleSums() {
        try (final Cdb cdb = Cdb.open(root)) {
            final CdbResult sum = cdb.run("select sum(rnd_double()) from long_sequence(1000)");
            assertThat(sum.type(0)).isEqualTo(CdbType.DOUBLE);
            assertThat(sum.getDouble(0, 0)).isGreaterThan(0.0).isLessThan(1000.0);
        }
    }

    /// `60000000L` is the LONG 60000000; a letter after the suffix is an error.
    @Test
    void longSuffix() {
        try (final Cdb cdb = Cdb.open(root)) {
            final CdbResult one = cdb.run("select 60000000L");
            assertThat(columns(one).getFirst()).endsWith(":LONG");
            assertThat(rows(one)).containsExactly("60000000");
            assertThatThrownBy(() -> cdb.run("select 60000000Lx")).isInstanceOf(CdbException.class);
        }
    }

    /// `rnd_symbol` takes constants only: a column is refused.
    @Test
    void rndSymbolRefusesAColumn() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run(INSERT);
            assertThatThrownBy(() -> cdb.run("select rnd_symbol(sym) from trades")).isInstanceOf(CdbException.class);
        }
    }

    /// A `timestamp_sequence` over a TIMESTAMP start counts the rows it produced: row k is `start + k * step`.
    @Test
    void timestampSequenceCountsRows() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(rows(cdb.run("select timestamp_sequence(cast(0 as timestamp), 1000000) from long_sequence(3)")))
                    .containsExactly("1970-01-01T00:00:00Z", "1970-01-01T00:00:01Z", "1970-01-01T00:00:02Z");
        }
    }

    /// A generator inside an aggregate over a table of 70 day partitions, which an aggregate of plain columns reads
    /// in parallel frames, still counts the rows in one serial order: `timestamp_sequence(0, 1)` reaches 99,999 once
    /// and no lower maximum, as a counter restarted per frame would give, and `count()` beside it is unchanged.
    @Test
    void aGeneratorInsideAnAggregateIsNotSplitAcrossFrames() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run(INSERT);
            final CdbResult answer = cdb.run("select max(timestamp_sequence(0, 1)), count() from trades");
            assertThat(answer.getLong(0, 0)).isEqualTo(99_999L);
            assertThat(answer.getLong(0, 1)).isEqualTo(100_000L);
            final CdbResult first = cdb.run("select min(timestamp_sequence(0, 1)) from trades");
            assertThat(first.getLong(0, 0)).isZero();
        }
    }

    /// The three generators are not row-local, so no query splits them across parallel frames.
    @Test
    void generatorsAreNotRowLocal() {
        final Bound text = new Bound.Constant("a", CdbType.VARCHAR);
        final Bound zero = new Bound.Constant(0L, CdbType.LONG);
        assertThat(Functions.rowLocal(new Bound.Call("rnd_symbol", List.of(text), CdbType.SYMBOL))).isFalse();
        assertThat(Functions.rowLocal(new Bound.Call("rnd_double", List.of(), CdbType.DOUBLE))).isFalse();
        assertThat(Functions.rowLocal(new Bound.Call("timestamp_sequence", List.of(zero, zero), CdbType.TIMESTAMP)))
                .isFalse();
        assertThat(Functions.rowLocal(new Bound.Call("now", List.of(), CdbType.TIMESTAMP))).isTrue();
    }

    private static long micros(final String iso) {
        final Instant instant = Instant.parse(iso);
        return instant.getEpochSecond() * 1_000_000 + instant.getNano() / 1_000;
    }
}
