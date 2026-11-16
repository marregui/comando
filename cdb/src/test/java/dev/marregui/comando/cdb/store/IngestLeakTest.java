package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Ingest leaks no native row memory: every row block a write builds (pending rows, queued commits, a commit's
/// partition rows and merges) is closed once its rows are applied or dropped, failed inserts included.
class IngestLeakTest {

    private static final int INSERTS = 1_000;
    private static final long DAY_MICROS = 86_400_000_000L;
    private static final long BASE_MICROS = 1_762_992_000_000_000L;
    private static final DateTimeFormatter MICROS_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    /// A thousand ILP rows flushed in groups and a thousand SQL inserts, out of order and across partitions so commits
    /// append, rewrite and merge, with failed inserts between them: once every commit is applied and read, and the
    /// store closed, the live native bytes of row blocks are back where they started.
    @Test
    void liveRowBlockBytesReturnToZeroOnceApplied(@TempDir final Path root) {
        final long before = RowBlock.liveBytes();
        try (Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE q (ts TIMESTAMP, s SYMBOL, v VARCHAR, n LONG, d DOUBLE, b BOOLEAN) TIMESTAMP(ts)"
                    + " PARTITION BY DAY");
            try (CdbAppender appender = cdb.appender()) {
                for (int i = 0; i < INSERTS; i++) {
                    appender.table("ilp").symbol("s", "s" + i % 7).stringColumn("v", i % 5 == 0 ? "é" + i : "v" + i)
                            .longColumn("n", i).doubleColumn("d", i * 0.25).booleanColumn("b", i % 2 == 0)
                            .at(micros(i));
                    if (i % 50 == 49) {
                        appender.flush();
                    }
                }
                appender.flush();
                assertThatThrownBy(() -> {
                    appender.table("ilp").stringColumn("n", "not a long").at(micros(1));
                    appender.flush();
                }).isInstanceOf(CdbException.class);
            }
            for (int i = 0; i < INSERTS; i++) {
                cdb.run("INSERT INTO q VALUES ('" + timestamp(i) + "', 's" + i % 3 + "', 'ü" + i + "', " + i + ", "
                        + i * 0.5 + ", " + (i % 2 == 0) + ')');
                if (i % 250 == 0) {
                    final int bad = i;
                    assertThatThrownBy(() -> cdb.run("INSERT INTO q VALUES ('" + timestamp(bad)
                            + "', 's', 'v', 'not a long', 1.0, true)")).isInstanceOf(CdbException.class);
                }
            }
            final CdbResult ilp = cdb.run("SELECT count() FROM ilp");
            final CdbResult sql = cdb.run("SELECT count() FROM q");
            assertThat(new long[] {ilp.getLong(0, 0), sql.getLong(0, 0)}).containsExactly(INSERTS, INSERTS);
        }
        // the close joins the applier, which closes a batch's blocks after it publishes them
        assertThat(RowBlock.liveBytes() - before).isZero();
    }

    // row i's designated timestamp: three days, every seventh row back in an earlier one
    private static long micros(final int i) {
        final long day = i % 7 == 0 ? 0L : i % 3;
        return BASE_MICROS + day * DAY_MICROS + i * 1_000L;
    }

    private static String timestamp(final int i) {
        return MICROS_UTC.format(Instant.EPOCH.plus(micros(i), ChronoUnit.MICROS));
    }
}
