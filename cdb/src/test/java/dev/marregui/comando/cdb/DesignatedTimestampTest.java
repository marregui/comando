package dev.marregui.comando.cdb;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// A result names its designated timestamp as QuestDB's `/exec` does in `"timestamp"`: the table's designated column
/// where the query keeps it, not the first TIMESTAMP column, and -1 where the query has none.
class DesignatedTimestampTest {

    @TempDir
    private Path root;

    /// A table whose first TIMESTAMP column is not its designated one: `select *` and a projection that keeps `ts`
    /// name `ts`; a projection without it and an aggregate name none.
    @Test
    void theDesignatedColumnIsNamedNotTheFirstTimestamp() {
        try (Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE x (a TIMESTAMP, ts TIMESTAMP, v LONG) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("INSERT INTO x VALUES ('2026-11-15T00:00:00Z', '2026-11-15T01:00:00Z', 1)");

            assertThat(cdb.run("SELECT * FROM x").timestampIndex()).isEqualTo(1);
            assertThat(cdb.run("SELECT v, ts FROM x").timestampIndex()).isEqualTo(1);
            assertThat(cdb.run("SELECT a, v FROM x").timestampIndex()).isEqualTo(-1);
            assertThat(cdb.run("SELECT count() FROM x").timestampIndex()).isEqualTo(-1);
        }
    }

    /// ORDER BY as QuestDB's: the designated column stays named when it is the first key, in either direction; a
    /// first key that is another selected TIMESTAMP column becomes the named one; any other first key names none.
    @Test
    void orderByNamesTheFirstKeyWhenItIsATimestamp() {
        try (Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE x (a TIMESTAMP, ts TIMESTAMP, v LONG) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("INSERT INTO x VALUES ('2026-11-15T00:00:00Z', '2026-11-15T01:00:00Z', 1)");

            assertThat(cdb.run("SELECT * FROM x ORDER BY ts DESC").timestampIndex()).isEqualTo(1);
            assertThat(cdb.run("SELECT * FROM x ORDER BY ts, v").timestampIndex()).isEqualTo(1);
            assertThat(cdb.run("SELECT * FROM x ORDER BY a").timestampIndex()).isEqualTo(0);
            assertThat(cdb.run("SELECT * FROM x ORDER BY v").timestampIndex()).isEqualTo(-1);
            assertThat(cdb.run("SELECT * FROM x ORDER BY v, ts").timestampIndex()).isEqualTo(-1);
            assertThat(cdb.run("SELECT a, v FROM x ORDER BY a").timestampIndex()).isEqualTo(0);
            assertThat(cdb.run("SELECT a FROM x UNION ALL SELECT a FROM x ORDER BY a").timestampIndex()).isEqualTo(0);
        }
    }
}
