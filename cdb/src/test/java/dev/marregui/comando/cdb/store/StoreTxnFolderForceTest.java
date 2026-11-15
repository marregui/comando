package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;

/// A direct commit (every commit a checkpoint) whose `_txn` was moved into place stands even when forcing the table
/// folder after the move fails: the table folder is made unreadable (write and search only) right before the move, so
/// the folder force cannot open it while the files in it can still be written and moved. Before, the commit threw and
/// its cleanup deleted the folder `_txn` named (a rewrite's rows then read back as NULLs) or left appended rows the
/// caller believed failed.
class StoreTxnFolderForceTest {

    private static final long DAY1 = StoreRows.micros("2026-11-01T00:00:00Z");

    private static TableDef table() {
        return new TableDef("m", List.of(
                new ColumnDef("value", CdbType.DOUBLE),
                new ColumnDef("note", CdbType.VARCHAR),
                new ColumnDef("ts", CdbType.TIMESTAMP)), 2, PartitionBy.DAY, List.of(), 0L);
    }

    private static void row(final TableWriter w, final long ts, final double value, final String note) {
        w.newRow(ts);
        w.putDouble(0, value);
        w.putText(1, note);
        w.endRow();
    }

    private static void permissions(final Path dir, final String perms) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(perms));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> rows(final Path root) {
        try (Store store = Store.open(root); TableReader r = store.reader("m")) {
            return StoreRows.rows(r);
        }
    }

    /// `rows` committed first, then `late` committed with the table folder unreadable: the second commit returns,
    /// and after a reopen every row of both reads back.
    private static List<String> commitWithUnreadableFolder(final Path root, final double[] late) {
        final Path table = root.resolve("m");
        try (Store store = Store.open(root)) {
            store.directRows = 0;
            store.create(table(), false);
            try (TableWriter w = store.writer("m", 1_000L)) {
                row(w, DAY1 + 10L, 1.0, "one");
                row(w, DAY1 + 20L, 2.0, "two");
                w.commit();
                // right before the move: the table folder's force ahead of it still holds, the one after cannot open
                store.beforeTxnReplace = () -> permissions(table, "-wx------");
                try {
                    for (final double v : late) {
                        row(w, DAY1 + (long) v, v, "late");
                    }
                    w.commit();
                } finally {
                    store.beforeTxnReplace = () -> {
                    };
                    permissions(table, "rwx------");
                }
            }
        }
        return rows(root);
    }

    /// A commit that rewrites the partition (a row before the committed ones) keeps every row.
    @Test
    void aRewriteCommitStandsWhenTheFolderForceFails(@TempDir final Path root) {
        final List<String> rows = commitWithUnreadableFolder(root, new double[] {5.0});
        assertThat(rows).hasSize(3).noneMatch(r -> r.startsWith("null|null"));
    }

    /// A commit that appends in place (a row after the committed ones) keeps every row, the appended one once.
    @Test
    void anAppendCommitStandsWhenTheFolderForceFails(@TempDir final Path root) {
        final List<String> rows = commitWithUnreadableFolder(root, new double[] {30.0});
        assertThat(rows).hasSize(3).noneMatch(r -> r.startsWith("null|null"));
    }
}
