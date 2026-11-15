package dev.marregui.comando.cdb.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A commit whose log record cannot be forced is not answered: the commit throws, readers keep the previous snapshot,
/// the table refuses commits until it reopens, and the reopened table is at a commit boundary. The class sets
/// [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and each test resets
/// it.
class StoreWalFailureTest {

    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T00:00:00Z");
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;

    /// The first force of `_wal` fails, the log's cut back succeeds: the commit is refused and a reopen does not show
    /// it.
    @Test
    void aFailedLogForceRefusesTheCommitAndTheTable(@TempDir final Path dir) throws Exception {
        final Path root = dir.resolve("store");
        Files.createDirectories(root);
        final DiskModel model = new DiskModel(root);
        final AtomicInteger failed = new AtomicInteger();
        StoreFiles.recorder = model;
        try {
            final List<String> before;
            try (final Store store = Store.open(root)) {
                store.create(M, false);
                commit(store, 1.0, TS);
                before = read(store);
                model.failForce(file -> isLog(file) && failed.getAndIncrement() == 0);
                try (final TableWriter writer = store.writer(M.name(), WAIT_MILLIS)) {
                    row(writer, 2.0, TS + 1L);
                    assertThatThrownBy(writer::commit).isInstanceOf(CdbException.class);
                }
                assertThat(read(store)).isEqualTo(before);
                assertThatThrownBy(() -> commit(store, 3.0, TS + 2L)).isInstanceOf(CdbException.class);
                assertThat(read(store)).isEqualTo(before);
            }
            assertThat(failed).as("forces of the log refused").hasPositiveValue();
            try (final Store store = Store.open(root)) {
                assertThat(read(store)).isEqualTo(before);
                commit(store, 4.0, TS + 3L);
                assertThat(read(store)).hasSize(before.size() + 1);
            }
        } finally {
            StoreFiles.recorder = null;
        }
    }

    /// Every force of `_wal` fails, the log's cut back included: the commit is refused, and after a reopen the table
    /// holds the previous commit or the refused one whole, never a part.
    @Test
    void aFailedLogForceAndCutLeaveACommitBoundary(@TempDir final Path dir) throws Exception {
        final Path root = dir.resolve("store");
        Files.createDirectories(root);
        final DiskModel model = new DiskModel(root);
        StoreFiles.recorder = model;
        try {
            final List<String> before;
            final List<String> after;
            try (final Store store = Store.open(root)) {
                store.create(M, false);
                commit(store, 1.0, TS);
                before = read(store);
                after = new ArrayList<>(before);
                after.add("2.0|" + (TS + 1L));
                model.failForce(StoreWalFailureTest::isLog);
                try (final TableWriter writer = store.writer(M.name(), WAIT_MILLIS)) {
                    row(writer, 2.0, TS + 1L);
                    assertThatThrownBy(writer::commit).isInstanceOf(CdbException.class);
                }
                assertThat(read(store)).isEqualTo(before);
                assertThatThrownBy(() -> commit(store, 3.0, TS + 2L)).isInstanceOf(CdbException.class);
            }
            model.failForce(_ -> false);
            try (final Store store = Store.open(root)) {
                assertThat(read(store)).isIn(before, after);
            }
        } finally {
            StoreFiles.recorder = null;
        }
    }

    private static boolean isLog(final Path file) {
        return file.getFileName().toString().equals(Wal.FILE);
    }

    private static void commit(final Store store, final double value, final long ts) {
        try (final TableWriter writer = store.writer(M.name(), WAIT_MILLIS)) {
            row(writer, value, ts);
            writer.commit();
        }
    }

    private static void row(final TableWriter writer, final double value, final long ts) {
        writer.newRow(ts);
        writer.putDouble(0, value);
        writer.endRow();
    }

    private static List<String> read(final Store store) {
        try (final TableReader reader = store.reader(M.name())) {
            return rows(reader);
        }
    }
}
