package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// TTL counts back from the newest row or from now, whichever is earlier, as QuestDB 9.2.3 and later do
/// (`cairo.ttl.use.wall.clock`), so one row stamped in the future does not expire the history before it.
class TtlWallClockTest {

    @TempDir
    private Path root;

    /// Rows at now and an hour ago, then one a year ahead: all three stay, where counting from the future row would
    /// drop the two before it.
    @Test
    void aFutureRowDoesNotExpireThePresent() {
        final Instant now = Instant.now();
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY TTL 1 DAY");
            cdb.run("INSERT INTO t VALUES ('" + now.minus(Duration.ofHours(1)) + "', 1)");
            cdb.run("INSERT INTO t VALUES ('" + now + "', 2)");
            cdb.run("INSERT INTO t VALUES ('" + now.plus(Duration.ofDays(365)) + "', 3)");
            assertThat(rows(cdb.run("SELECT n FROM t ORDER BY n"))).containsExactly("1", "2", "3");
        }
    }
}
