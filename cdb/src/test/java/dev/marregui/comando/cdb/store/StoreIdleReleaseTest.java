package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// The store's idle-mapping sweep and the mapping count its tables share.
///
/// - a sweep past the idle time with no reader open unmaps every version; the next read maps it again
/// - every table, created or loaded, counts its mappings in the store's one `Mappings`
/// - a sweep driven by a clock past the idle time, with a reader open, changes no answer, and the store closes clean
class StoreIdleReleaseTest {

    private static final TableDef T = new TableDef("t", List.of(
            new ColumnDef("n", CdbType.LONG),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final TableDef U = new TableDef("u", List.of(
            new ColumnDef("s", CdbType.VARCHAR),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T10:00:00Z");

    /// Rows read once map their columns; a sweep 61 s past now with no reader open unmaps them all (the store's
    /// count back to 0); the next read maps them again and gives the same rows.
    @Test
    void aSweepReleasesIdleMappingsAndAReadMapsThemAgain(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(T, false);
            try (final TableWriter w = store.writer("t", 0L)) {
                for (long n = 0L; n < 10L; n++) {
                    w.newRow(TS + n * TimeUnit.HOURS.toMicros(5L));
                    w.putLong(0, n);
                    w.endRow();
                }
                w.commit();
            }
            final List<String> before;
            try (final TableReader reader = store.reader("t")) {
                before = rows(reader);
            }
            assertThat(store.mappings().count()).isPositive();
            store.sweepIdle(System.nanoTime() + TimeUnit.SECONDS.toNanos(61L));
            assertThat(store.mappings().count()).isZero();
            try (final TableReader reader = store.reader("t")) {
                assertThat(rows(reader)).isEqualTo(before);
            }
            assertThat(store.mappings().count()).isPositive();
        }
    }

    /// Two tables created in one store, and the same two loaded by a reopen, count in that store's `Mappings`.
    @Test
    void everyTableSharesTheStoresMappings(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(T, false);
            store.create(U, false);
            assertShared(store);
        }
        try (final Store reopened = Store.open(root)) {
            assertShared(reopened);
        }
    }

    /// Rows committed to two days, a reader holding the snapshot, then a sweep at a clock 61 s past now (beyond the
    /// 60 s idle time): the open reader and a new one read the same rows as before, and the close frees everything.
    @Test
    void aSweepPastTheIdleTimeKeepsEveryAnswer(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(T, false);
            try (final TableWriter w = store.writer("t", 0L)) {
                for (long n = 0L; n < 10L; n++) {
                    w.newRow(TS + n * TimeUnit.HOURS.toMicros(5L));
                    w.putLong(0, n);
                    w.endRow();
                }
                w.commit();
            }
            final List<String> before;
            try (final TableReader reader = store.reader("t")) {
                before = rows(reader);
                store.sweepIdle(System.nanoTime() + TimeUnit.SECONDS.toNanos(61L));
                assertThat(rows(reader)).isEqualTo(before);
            }
            assertThat(before).hasSize(10);
            store.sweepIdle(System.nanoTime() + TimeUnit.SECONDS.toNanos(61L));
            try (final TableReader reader = store.reader("t")) {
                assertThat(rows(reader)).isEqualTo(before);
            }
        }
        try (final Store reopened = Store.open(root); final TableReader reader = reopened.reader("t")) {
            assertThat(rows(reader)).hasSize(10);
        }
    }

    private static void assertShared(final Store store) {
        int tables = 0;
        for (final TableState state : store.states()) {
            assertThat(state.mappings).isSameAs(store.mappings());
            tables++;
        }
        assertThat(tables).isEqualTo(2);
    }
}
