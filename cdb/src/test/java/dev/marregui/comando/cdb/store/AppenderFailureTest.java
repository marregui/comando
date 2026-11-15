package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The appender and the writer on a commit that fails with other than a `CdbException`.
///
/// - a close whose flush fails that way still releases every writer
/// - a failed flush rolls back the tables from the failed one on: their rows do not ride on a later flush
/// - an `Error` in a commit deletes the folders it wrote, refuses the table's later commits and closes the appender
class AppenderFailureTest {

    private static final long TS = micros("2026-11-01T10:00:00Z");

    /// A close whose flush throws an `IllegalStateException` (the hook after the log's force) throws it and still
    /// releases the table's writer; before, the writer stayed held and the next writer waited out its timeout.
    @Test
    void aCloseReleasesTheWritersWhenItsFlushFailsWithARuntimeException(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            final CdbAppender appender = store.appender();
            appender.table("a").longColumn("n", 1L).at(TS);
            store.afterWalForce = () -> {
                throw new IllegalStateException("injected");
            };
            assertThatThrownBy(appender::close).isInstanceOf(IllegalStateException.class);
            store.afterWalForce = () -> {
            };
            store.writer("a", 0L).close();
            assertThatThrownBy(() -> appender.table("a")).isInstanceOf(CdbException.class);
        }
    }

    /// A flush whose first table's commit fails rolls back the second table's rows: the next flush of that table
    /// commits only the row written after the failure.
    @Test
    void aFailedFlushRollsBackTheTablesAfterTheFailedOne(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            try (final CdbAppender appender = store.appender()) {
                appender.table("a").longColumn("n", 1L).at(TS);
                appender.table("b").longColumn("n", 2L).at(TS);
                final AtomicBoolean failed = new AtomicBoolean();
                store.afterWalForce = () -> {
                    if (failed.compareAndSet(false, true)) {
                        throw new IllegalStateException("injected");
                    }
                };
                assertThatThrownBy(appender::flush).isInstanceOf(IllegalStateException.class);
                appender.table("b").longColumn("n", 3L).at(TS + 1L);
                appender.flush();
            }
            assertThat(rowCount(store, "b")).isEqualTo(1L);
        }
    }

    /// An `Error` before a direct commit replaces `_txn`: the partition folder it wrote is deleted (its mapping let go
    /// first), the table refuses later commits, and the appender is closed with its writer released.
    @Test
    void anErrorInACommitDropsItsFoldersAndReleasesTheWriters(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.directRows = 0;
            store.create(new TableDef("a", List.of(
                    new ColumnDef("n", CdbType.LONG),
                    new ColumnDef("timestamp", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L), false);
            final CdbAppender appender = store.appender();
            appender.table("a").longColumn("n", 1L).at(TS);
            store.beforeTxnReplace = () -> {
                throw new InjectedError();
            };
            assertThatThrownBy(appender::flush).isInstanceOf(InjectedError.class);
            store.beforeTxnReplace = () -> {
            };
            assertThat(partitionFolders(root.resolve("a"))).isEmpty();
            assertThatThrownBy(() -> appender.table("a")).isInstanceOf(CdbException.class);
            appender.close();
            try (final TableWriter w = store.writer("a", 0L)) {
                w.newRow(TS + 1L);
                w.putLong(0, 2L);
                w.endRow();
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            }
            assertThat(rowCount(store, "a")).isZero();
        }
    }

    private static List<Path> partitionFolders(final Path table) throws IOException {
        try (final Stream<Path> entries = Files.list(table)) {
            return entries.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("_"))
                    .toList();
        }
    }

    private static long rowCount(final Store store, final String table) {
        try (final TableReader r = store.reader(table)) {
            return r.rowCount();
        }
    }

    /// An `Error` thrown by a test hook, standing for an `OutOfMemoryError` or an `InternalError` of a mapping.
    private static final class InjectedError extends Error {

        @Serial
        private static final long serialVersionUID = 1L;

        InjectedError() {
            super("injected");
        }
    }
}
