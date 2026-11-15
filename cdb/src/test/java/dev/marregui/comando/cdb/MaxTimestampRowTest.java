package dev.marregui.comando.cdb;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// A row whose designated timestamp is `Long.MAX_VALUE` stays visible to a full scan and survives an UPDATE.
class MaxTimestampRowTest {

    @TempDir
    private Path root;

    /// Two rows in one partition, the second at `Long.MAX_VALUE`: a full scan sees both, an UPDATE keeps both.
    @Test
    void aRowAtTheLargestTimestampIsKept() {
        try (Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY NONE");
            try (CdbAppender appender = cdb.appender()) {
                appender.table("t").longColumn("n", 1).at(1_700_000_000_000_000L);
                appender.table("t").longColumn("n", 2).at(Long.MAX_VALUE);
                appender.flush();
            }
            final CdbResult before = cdb.run("SELECT count() FROM t");
            final CdbResult rows = cdb.run("SELECT n FROM t");
            final long updated = cdb.run("UPDATE t SET n = n + 10").updateCount();
            final CdbResult after = cdb.run("SELECT n FROM t");
            assertThat(new long[] {before.getLong(0, 0), rows.rowCount(), updated, after.rowCount()})
                    .containsExactly(2, 2, 2, 2);
        }
    }
}
