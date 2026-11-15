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

/// `percentile_cont(x, p)` and `percentile_disc(x, p)` against the answers QuestDB 10.2 gives for the same data:
/// linear interpolation at `p * (n - 1)` over the sorted non-NULL values, as a DOUBLE; also per group and per bucket.
class PercentileContTest {

    @TempDir
    private Path root;

    /// Over 1 to 10, each fraction gives QuestDB's value to the last bit; `percentile_disc` gives the value at the
    /// rank, in the input's type.
    @Test
    void interpolatesAsQuestDb() {
        try (final Cdb cdb = Cdb.open(root)) {
            final CdbResult result = cdb.run("SELECT percentile_cont(x, 0.5), percentile_cont(x, 0.25),"
                    + " percentile_cont(x, 0.1), percentile_cont(x, 0), percentile_cont(x, 1), percentile_cont(x, 0.33),"
                    + " percentile_cont(x, 0.95), percentile_cont(x * 1.5, 0.95), percentile_disc(x, 0.95)"
                    + " FROM long_sequence(10)");
            assertThat(result.type(0)).hasToString("DOUBLE");
            assertThat(rows(result))
                    .containsExactly("5.5|3.25|1.9|1.0|10.0|3.97|9.549999999999999|14.325|10");
            assertThat(rows(cdb.run("SELECT percentile_cont(x, 0.95), percentile_cont(x, 0.7) FROM long_sequence(4)")))
                    .containsExactly("3.8499999999999996|3.0999999999999996");
            assertThat(rows(cdb.run("SELECT percentile_cont(1.0 / x, 0.13), percentile_cont(1.0 / x, 0.61),"
                    + " percentile_cont(x * x * 0.37, 0.95), percentile_cont(x * x * 0.37, 0.07)"
                    + " FROM long_sequence(13)")))
                    .containsExactly("0.08757575757575757|0.17733333333333334|56.97999999999999|1.3024");
        }
    }

    /// One value is its own percentile, no value is NULL, NULL cells are skipped, and a TIMESTAMP is read as its
    /// micros, a DOUBLE answer.
    @Test
    void edgeCases() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(rows(cdb.run("SELECT percentile_cont(x, 0.5) FROM long_sequence(1)"))).containsExactly("1.0");
            assertThat(rows(cdb.run("SELECT percentile_cont(x, 0.5) FROM long_sequence(10) WHERE x > 10")))
                    .containsExactly("");
            assertThat(rows(cdb.run("SELECT percentile_cont(CASE WHEN x % 2 = 1 THEN x END, 0.5)"
                    + " FROM long_sequence(10)"))).containsExactly("5.0");
            final CdbResult timestamps = cdb.run("SELECT percentile_cont(CAST(x AS TIMESTAMP), 0.5) p"
                    + " FROM long_sequence(4)");
            assertThat(columns(timestamps)).containsExactly("p:DOUBLE");
            assertThat(rows(timestamps)).containsExactly("2.5");
        }
    }

    /// Per GROUP BY key, as QuestDB's `x % 3` groups over 1 to 10 give, and per SAMPLE BY bucket.
    @Test
    void worksPerGroupAndPerBucket() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(rows(cdb.run("SELECT x % 3 k, percentile_cont(x, 0.5) FROM long_sequence(10) ORDER BY k")))
                    .containsExactly("0|6.0", "1|5.5", "2|5.0");
            cdb.run("CREATE TABLE t (ts TIMESTAMP, v DOUBLE) TIMESTAMP(ts) PARTITION BY HOUR");
            cdb.run("INSERT INTO t SELECT CAST(x * 10000000 AS TIMESTAMP), x FROM long_sequence(12)");
            assertThat(rows(cdb.run("SELECT ts, percentile_cont(v, 0.5), percentile_disc(v, 0.5) FROM t SAMPLE BY 1m")))
                    .containsExactly("1970-01-01T00:00:00Z|3.0|3.0", "1970-01-01T00:01:00Z|8.5|8.0",
                            "1970-01-01T00:02:00Z|12.0|12.0");
        }
    }

    /// A fraction outside 0 to 1, or a missing fraction, is refused.
    @Test
    void invalidArgumentsAreRefused() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThatThrownBy(() -> cdb.run("SELECT percentile_cont(x, 1.5) FROM long_sequence(10)"))
                    .isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("SELECT percentile_cont(x) FROM long_sequence(10)"))
                    .isInstanceOf(CdbException.class);
        }
    }
}
