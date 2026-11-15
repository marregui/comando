package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A store's close with a writer left open, and every entry point after a close.
///
/// - a writer still open past the close's wait keeps the root's lock: no store opens the root under it
/// - a later close, once the writer closed, releases the lock
/// - after a close, every entry point and a writer left open answer a `CdbException`, never a `NullPointerException`
class StoreCloseTest {

    private static final TableDef T = new TableDef("t", List.of(
            new ColumnDef("n", CdbType.LONG),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T10:00:00Z");

    private static void row(final TableWriter w, final long n) {
        w.newRow(TS + n);
        w.putLong(0, n);
        w.endRow();
    }

    /// A close that cannot take an open writer's lease fails naming the table and keeps the root's lock, so a second
    /// store cannot open the root while that writer may still replace `_txn`; once the writer closed, a later close
    /// releases the lock and the root opens again.
    @Test
    void aCloseKeepsTheRootLockWhileAWriterIsOpen(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.closeWaitMillis = 50L;
            store.create(T, false);
            final TableWriter w = store.writer("t", 0L);
            try {
                row(w, 1L);
                assertThatThrownBy(store::close).isInstanceOf(CdbException.class).hasMessageContaining("tables=[t]");
                assertThatThrownBy(() -> Store.open(root)).isInstanceOf(CdbException.class);
                assertThatThrownBy(store::close).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> Store.open(root)).isInstanceOf(CdbException.class);
            } finally {
                w.close();
            }
            // the resource's close is the later close that releases the lock
        }
        try (final Store reopened = Store.open(root)) {
            assertThat(reopened.tables()).containsExactly("t");
        }
    }

    /// Every entry point of a closed store answers a `CdbException`.
    @Test
    void everyEntryPointOfAClosedStoreIsRefused(@TempDir final Path root) {
        final Store store = Store.open(root);
        store.create(T, false);
        store.close();
        store.close();
        assertThatThrownBy(store::tables).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> store.def("t")).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> store.create(T, true)).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> store.reader("t")).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> store.writer("t", 0L)).isInstanceOf(CdbException.class);
        assertThatThrownBy(store::appender).isInstanceOf(CdbException.class);
        assertThatThrownBy(store::walStatus).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> store.readMaxLagMillis(0L)).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> store.checkpoint("t")).isInstanceOf(CdbException.class);
    }

    /// A writer the close left open is refused a logged commit and a direct one (a truncate) with a `CdbException`;
    /// before, the direct commit threw a `NullPointerException`.
    @Test
    void aWriterLeftOpenPastTheCloseIsRefusedItsCommits(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.closeWaitMillis = 0L;
            store.create(T, false);
            final TableWriter w = store.writer("t", 0L);
            try {
                assertThatThrownBy(store::close).isInstanceOf(CdbException.class);
                row(w, 1L);
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
                w.rollback();
                w.truncate();
                row(w, 2L);
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            } finally {
                w.close();
            }
        }
    }

    /// A commit the closed table refuses after its record was logged is cut out of the log: the store opened next
    /// replays nothing, so a commit its caller saw fail never becomes visible; a commit answered before the close
    /// stays.
    @Test
    void aRefusedCommitIsNotReplayedAtTheNextOpen(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.closeWaitMillis = 0L;
            store.create(T, false);
            final TableWriter w = store.writer("t", 0L);
            try {
                row(w, 1L);
                w.commit();
                // the close runs between the commit's check of the table and its log append
                store.beforeWalAppend = () -> {
                    store.beforeWalAppend = () -> {
                    };
                    assertThatThrownBy(store::close).isInstanceOf(CdbException.class);
                };
                row(w, 2L);
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            } finally {
                w.close();
            }
        }
        try (final Store reopened = Store.open(root);
             TableReader r = reopened.reader("t")) {
            assertThat(r.rowCount()).isEqualTo(1L);
        }
    }

    /// The same refused commit from an interrupted thread: the cut still runs, and the interrupt is kept.
    @Test
    void anInterruptedRefusedCommitIsNotReplayed(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.closeWaitMillis = 0L;
            store.create(T, false);
            final TableWriter w = store.writer("t", 0L);
            try {
                store.beforeWalAppend = () -> {
                    store.beforeWalAppend = () -> {
                    };
                    assertThatThrownBy(store::close).isInstanceOf(CdbException.class);
                };
                row(w, 1L);
                Thread.currentThread().interrupt();
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
                assertThat(Thread.interrupted()).isTrue();
            } finally {
                w.close();
            }
        }
        try (final Store reopened = Store.open(root);
             TableReader r = reopened.reader("t")) {
            assertThat(r.rowCount()).isZero();
        }
    }

    /// An appender whose writer the close left open is refused its flush with a `CdbException`, and its close releases
    /// the writer so the store's next close releases the lock.
    @Test
    void anAppenderLeftOpenPastTheCloseIsRefusedItsFlush(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.closeWaitMillis = 0L;
            final CdbAppender appender = store.appender();
            try {
                appender.table("a").longColumn("n", 1L).at(TS);
                assertThatThrownBy(store::close).isInstanceOf(CdbException.class);
                appender.table("a").longColumn("n", 2L).at(TS + 1L);
                assertThatThrownBy(appender::flush).isInstanceOf(CdbException.class);
            } finally {
                appender.close();
            }
        }
    }
}
