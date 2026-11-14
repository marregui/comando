package dev.marregui.comando.cdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The facade end to end, parser to store: the conventions the planner and the operators must share, checked where
/// they meet rather than on each side alone.
class CdbTest {

    @TempDir
    private Path root;

    /// `LIMIT -n` keeps the last n rows, as QuestDB's `select x from long_sequence(5) limit -2` gives 4 and 5.
    @Test
    void negativeLimitKeepsTheLastRows() {
        try (Cdb cdb = Cdb.open(root)) {
            final CdbResult result = cdb.run("SELECT x FROM long_sequence(5) LIMIT -2");
            assertThat(result.rowCount()).isEqualTo(2);
            assertThat(result.getLong(0, 0)).isEqualTo(4);
            assertThat(result.getLong(1, 0)).isEqualTo(5);
        }
    }

    /// A query over a table whose expression fails to compile leaves no reader open: the table's writer can still
    /// rewrite it, and the old version folder goes.
    @Test
    void aQueryThatFailsToBuildReleasesItsReader() throws IOException {
        try (Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
            try (CdbAppender appender = cdb.appender()) {
                appender.table("t").longColumn("n", 1).at(1_700_000_000_000_000L);
                appender.flush();
            }
            assertThatThrownBy(() -> cdb.run("SELECT date_trunc('fortnight', ts) FROM t"))
                    .isInstanceOf(CdbException.class);
            assertThat(cdb.run("UPDATE t SET n = 2").updateCount()).isEqualTo(1);
            // a version folder no reader holds is deleted once the next one is published: one folder remains
            assertThat(partitionFolders()).containsExactly("2023-11-14.2");
        }
    }

    private List<String> partitionFolders() throws IOException {
        try (Stream<Path> entries = Files.list(root.resolve("t"))) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
