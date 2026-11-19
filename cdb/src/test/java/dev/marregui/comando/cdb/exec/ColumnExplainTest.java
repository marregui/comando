package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;

/// `EXPLAIN` of `ALTER TABLE DROP COLUMN`, `ALTER TABLE RENAME COLUMN` and `BACKUP TO` against a real store answers
/// the change's plan rows and runs nothing: the table keeps its definition and rows, and no backup folder appears.
class ColumnExplainTest {

    @TempDir
    private Path root;
    // where a backup that ran would go: outside the data root
    @TempDir
    private Path scratch;

    /// The explained drop answers its plan and leaves the column and its values.
    @Test
    void explainOfADropColumnDoesNotRunIt() {
        try (final Cdb cdb = Cdb.open(root)) {
            fill(cdb);
            final List<String> before = table(cdb);
            final List<String> plan = explain(cdb, "ALTER TABLE t DROP COLUMN c");
            assertThat(plan.getFirst()).startsWith("DropColumn t c");
            assertThat(table(cdb)).isEqualTo(before);
        }
    }

    /// The explained rename answers its plan and leaves the column under its name.
    @Test
    void explainOfARenameColumnDoesNotRunIt() {
        try (final Cdb cdb = Cdb.open(root)) {
            fill(cdb);
            final List<String> before = table(cdb);
            final List<String> plan = explain(cdb, "ALTER TABLE t RENAME COLUMN a TO b");
            assertThat(plan.getFirst()).startsWith("RenameColumn t a to b");
            assertThat(table(cdb)).isEqualTo(before);
        }
    }

    /// The explained backup answers its plan and makes no folder.
    @Test
    void explainOfABackupDoesNotRunIt() {
        final Path folder = scratch.resolve("backup");
        try (final Cdb cdb = Cdb.open(root)) {
            fill(cdb);
            final List<String> before = table(cdb);
            final List<String> plan = explain(cdb, "BACKUP TO '" + folder + "'");
            assertThat(plan.getFirst()).startsWith("Backup to " + folder);
            assertThat(table(cdb)).isEqualTo(before);
        }
        assertThat(folder).doesNotExist();
    }

    // a table of three columns and two rows
    private static void fill(final Cdb cdb) {
        cdb.run("CREATE TABLE t (a LONG, c VARCHAR, ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY");
        cdb.run("INSERT INTO t VALUES (1, 'one', '2026-11-15T10:00:00.000000Z')");
        cdb.run("INSERT INTO t VALUES (2, 'two', '2026-11-16T10:00:00.000000Z')");
    }

    // EXPLAIN's rows: the one column QUERY PLAN, the lines Cdb.explain gives for the statement
    private static List<String> explain(final Cdb cdb, final String sql) {
        final CdbResult result = cdb.run("EXPLAIN " + sql);
        assertThat(SqlRows.columns(result)).containsExactly("QUERY PLAN:VARCHAR");
        final List<String> rows = SqlRows.rows(result);
        assertThat(rows).containsExactly(cdb.explain(sql).split("\n"));
        return rows;
    }

    // the table's columns, then its rows
    private static List<String> table(final Cdb cdb) {
        final CdbResult result = cdb.run("SELECT * FROM t ORDER BY ts");
        final List<String> out = new ArrayList<>(SqlRows.columns(result));
        out.addAll(SqlRows.rows(result));
        return out;
    }
}
