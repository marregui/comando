package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;

/// The store's paths that build row blocks outside a plain apply leave no row-block memory: commits still queued when
/// the store closes (freed by the table's close), the log replayed on reopen (freed by the replay), and a writer
/// closed with rows it never committed (its pending block). Each ends in the process-wide leak check.
class StoreBlockLeakTest {

    private static final long DAY_MICROS = 86_400_000_000L;
    private static final long BASE_MICROS = 1_762_992_000_000_000L;
    private static final int COMMITS = 40;

    /// Commits queued behind a paused applier and the store closed: their blocks are freed, and a reopen replays the
    /// log into the table, freeing the replay's blocks too; the rows are all there.
    @Test
    void queuedCommitsAndTheirReplayLeaveNoBlockMemory(@TempDir final Path root) {
        final long before = RowBlock.liveBytes();
        try (Store store = Store.open(root)) {
            create(store);
            store.applierPaused = true;
            try (TableWriter w = store.writer("m", 1_000L)) {
                for (int c = 0; c < COMMITS; c++) {
                    write(w, c);
                    w.commit();
                }
            }
        }
        BlockLeaks.assertNoBlockMemoryHeld(before);
        try (Store store = Store.open(root); TableReader r = store.reader("m")) {
            assertThat(r.rowCount()).isEqualTo(COMMITS * 3L);
        }
        BlockLeaks.assertNoBlockMemoryHeld(before);
    }

    /// A writer that adds rows and closes without a commit frees what it held, and the table keeps none of them.
    @Test
    void aWriterClosedWithoutACommitLeavesNoBlockMemory(@TempDir final Path root) {
        final long before = RowBlock.liveBytes();
        try (Store store = Store.open(root)) {
            create(store);
            try (TableWriter w = store.writer("m", 1_000L)) {
                write(w, 0);
                w.commit();
                write(w, 1);
            }
            try (TableReader r = store.reader("m")) {
                assertThat(r.rowCount()).isEqualTo(3L);
            }
        }
        BlockLeaks.assertNoBlockMemoryHeld(before);
    }

    private static void create(final Store store) {
        store.create(new TableDef("m", List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("n", CdbType.LONG),
                new ColumnDef("v", CdbType.VARCHAR)), 0, PartitionBy.DAY, List.of(), 0L), false);
    }

    // three rows of commit c, over two days, the last one back in the first day so the commit merges
    private static void write(final TableWriter w, final int c) {
        final long[] at = {BASE_MICROS + c * 10L, BASE_MICROS + DAY_MICROS + c * 10L, BASE_MICROS + c * 10L + 1L};
        for (int i = 0; i < at.length; i++) {
            w.newRow(at[i]);
            w.putLong(1, c * 3L + i);
            w.putText(2, "v" + c + "-" + i);
            w.endRow();
        }
    }
}
