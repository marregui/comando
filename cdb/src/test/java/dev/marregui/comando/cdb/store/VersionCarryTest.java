package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static org.assertj.core.api.Assertions.assertThat;

/// A commit that appends in place opens a new version of the same folder; the rows below the old row count are the old
/// version's, so the new one takes over what the old one derived from them instead of reading them again.
///
/// - the zone map blocks the old version completed are carried; only its last, partial block and the new blocks are
///   built
/// - a SYMBOL column the old version found free of NULLs is read only from the old row count on
/// - what a carrying version answers equals what a version opened afresh over the same folder answers
class VersionCarryTest {

    private static final int N = 0;
    private static final int D = 1;
    private static final int S = 2;
    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    // three whole blocks and a partial fourth: the partial one changes when rows are appended
    private static final int FIRST = 3 * ZoneMap.BLOCK_ROWS + 100;
    private static final int SECOND = 5_000;

    /// After a full scan of the first version and an append in place, a full scan of the new version builds two
    /// blocks of each 8-byte column, the old partial one and the new one, and keeps the same rows a fresh version
    /// keeps.
    @Test
    void anAppendInPlaceBuildsOnlyTheBlocksItChanged(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.applyOnCommit = true;
            store.create(table(), false);
            commit(store, 0, FIRST, false);
            final Path folder;
            try (final TableReader reader = store.reader("m")) {
                final Version first = only(reader);
                folder = first.dir;
                scan(first);
                assertThat(first.zoneBlocksBuilt(N)).isEqualTo(4);
                assertThat(first.zoneBlocksBuilt(D)).isEqualTo(4);
            }
            commit(store, FIRST, SECOND, false);
            try (final TableReader reader = store.reader("m")) {
                final Version second = only(reader);
                assertThat(second.dir).isEqualTo(folder);
                assertThat(second.rowCount()).isEqualTo(FIRST + SECOND);
                final int[] kept = scan(second);
                assertThat(second.zoneBlocksBuilt(N)).isEqualTo(2);
                assertThat(second.zoneBlocksBuilt(D)).isEqualTo(2);
                final Version fresh = Version.open(second.dir, table(), second.rowCount(),
                        Collections.nCopies(4, null));
                try {
                    assertThat(scan(fresh)).containsExactly(kept);
                } finally {
                    fresh.unmap();
                }
            }
        }
    }

    /// A SYMBOL column the first version found free of NULLs is read from the old row count on after an append in
    /// place, which finds the NULL the append brought; the next append keeps that answer without a read.
    @Test
    void anAppendInPlaceReadsTheNullsOfTheNewRowsOnly(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.applyOnCommit = true;
            store.create(table(), false);
            commit(store, 0, FIRST, false);
            try (final TableReader reader = store.reader("m")) {
                assertThat(only(reader).mayHoldNull(S)).isFalse();
            }
            commit(store, FIRST, SECOND, true);
            try (final TableReader reader = store.reader("m")) {
                final Version second = only(reader);
                assertThat(second.nullFreeRows(S)).isEqualTo(FIRST);
                assertThat(second.mayHoldNull(S)).isTrue();
            }
            commit(store, FIRST + SECOND, 10, false);
            try (final TableReader reader = store.reader("m")) {
                final Version third = only(reader);
                assertThat(third.nullFreeRows(S)).isZero();
                assertThat(third.mayHoldNull(S)).isTrue();
            }
        }
    }

    /// A filter over every row of each 8-byte column; returns the rows the LONG one keeps, then those the DOUBLE one
    /// keeps.
    private static int[] scan(final Version version) {
        final int rows = (int) version.rowCount();
        final int[] longs = new int[rows];
        final int[] doubles = new int[rows];
        final int keptLongs = version.selectLong(N, 0L, rows, Compare.GT, rows / 2L, longs);
        final int keptDoubles = version.selectDouble(D, 0L, rows, Compare.LE, rows / 3.0, doubles);
        final int[] kept = new int[keptLongs + keptDoubles];
        System.arraycopy(longs, 0, kept, 0, keptLongs);
        System.arraycopy(doubles, 0, kept, keptLongs, keptDoubles);
        return kept;
    }

    private static Version only(final TableReader reader) {
        final List<PartitionReader> partitions = reader.partitions();
        assertThat(partitions).hasSize(1);
        return (Version) partitions.getFirst();
    }

    /// Commits `count` rows from row `from`, one a millisecond; a SYMBOL NULL on the last row when `withNull`.
    private static void commit(final Store store, final int from, final int count, final boolean withNull) {
        try (final TableWriter w = store.writer("m", 1_000L)) {
            for (int r = from; r < from + count; r++) {
                w.newRow(DAY1 + r * 1_000L);
                w.putLong(N, r % 7 == 0 ? Long.MIN_VALUE : r);
                w.putDouble(D, r % 11 == 0 ? Double.NaN : r * 0.5);
                w.putText(S, withNull && r == from + count - 1 ? null : "s" + r % 5);
                w.endRow();
            }
            w.commit();
        }
    }

    private static TableDef table() {
        return new TableDef("m", List.of(
                new ColumnDef("n", CdbType.LONG),
                new ColumnDef("d", CdbType.DOUBLE),
                new ColumnDef("s", CdbType.SYMBOL),
                new ColumnDef("ts", CdbType.TIMESTAMP)), 3, PartitionBy.DAY, List.of(), 0L);
    }
}
