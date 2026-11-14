package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static dev.marregui.comando.cdb.store.StoreRows.timestamps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [Store] keeps tables as versioned partition folders named by `_txn`.
///
/// - a commit merges the touched partitions, orders them by timestamp and drops the rows dedup keys replace
/// - a column added later reads NULL in the partitions written before it
/// - one writer per table; create, truncate, TTL and rollback behave as the table definition says
class StoreTest {

    private static final int SENSOR = 0;
    private static final int VALUE = 1;
    private static final int NOTE = 2;

    /// Rows for three days written out of order, with duplicates on (ts, sensor), read back in timestamp order with the
    /// last write kept; a second commit replaces a committed row in place, and a reopened store reads the same.
    @Test
    void dedupKeepsTheLastWriteAcrossThreePartitionsWrittenOutOfOrder(@TempDir final Path root) {
        final long d1 = micros("2026-11-01T10:00:00Z");
        final long d2 = micros("2026-11-02T10:00:00Z");
        final long d3 = micros("2026-11-03T10:00:00Z");
        try (final Store store = Store.open(root)) {
            store.create(sensors("m", List.of("ts", "sensor"), 0L), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, d3, "a", 3.0, "first");
                row(w, d1 + 5L, "a", 1.5, null);
                row(w, d2, "a", 2.0, null);
                row(w, d1, "a", 1.0, "first");
                row(w, d3, "a", 3.3, "last");
                row(w, d1, "b", 10.0, null);
                row(w, d1, "a", 1.1, "last");
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(r.partitions()).hasSize(3);
                assertThat(r.rowCount()).isEqualTo(5L);
                assertThat(timestamps(r)).isSorted();
                assertThat(rows(r)).containsExactly(
                        "\"b\"|10.0|null|" + d1,
                        "\"a\"|1.1|\"last\"|" + d1,
                        "\"a\"|1.5|null|" + (d1 + 5L),
                        "\"a\"|2.0|null|" + d2,
                        "\"a\"|3.3|\"last\"|" + d3);
            }
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, d2, "a", 2.2, "again");
                row(w, d2 - 1L, "c", 9.0, null);
                w.commit();
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(timestamps(r)).isSorted();
            assertThat(rows(r)).containsExactly(
                    "\"b\"|10.0|null|" + d1,
                    "\"a\"|1.1|\"last\"|" + d1,
                    "\"a\"|1.5|null|" + (d1 + 5L),
                    "\"c\"|9.0|null|" + (d2 - 1L),
                    "\"a\"|2.2|\"again\"|" + d2,
                    "\"a\"|3.3|\"last\"|" + d3);
        }
    }

    /// One store per root: a second open of a held root is refused, so two stores cannot each publish a `_txn` the
    /// other overwrites; once the first closes, the root opens again.
    @Test
    void aSecondStoreOnTheSameRootIsRefused(@TempDir final Path root) {
        try (final Store first = Store.open(root)) {
            assertThatThrownBy(() -> Store.open(root)).isInstanceOf(CdbException.class);
            assertThat(first.tables()).isEmpty();
        }
        try (final Store again = Store.open(root)) {
            assertThat(again.tables()).isEmpty();
        }
    }

    /// Without dedup keys every row is kept, rows of one timestamp in the order they were written.
    @Test
    void withoutDedupKeysEqualTimestampsKeepWriteOrder(@TempDir final Path root) {
        final long t = micros("2026-11-01T10:00:00Z");
        try (final Store store = Store.open(root)) {
            store.create(sensors("m", List.of(), 0L), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, t, "a", 1.0, null);
                row(w, t - 1L, "z", 0.0, null);
                row(w, t, "a", 2.0, null);
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(rows(r)).containsExactly(
                        "\"z\"|0.0|null|" + (t - 1L),
                        "\"a\"|1.0|null|" + t,
                        "\"a\"|2.0|null|" + t);
            }
        }
    }

    /// A column added after a partition was written reads NULL there, also when that partition later takes rows that
    /// carry the column, and after a reopen.
    @Test
    void aColumnAddedAfterAPartitionWasWrittenReadsNullThere(@TempDir final Path root) {
        final long d1 = micros("2026-11-01T10:00:00Z");
        final long d2 = micros("2026-11-02T10:00:00Z");
        try (final Store store = Store.open(root)) {
            store.create(sensors("m", List.of(), 0L), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, d1, "a", 1.0, null);
                w.commit();
                final int extra = w.addColumn(new ColumnDef("extra", CdbType.LONG));
                w.newRow(d2);
                w.putLong(extra, 5L);
                w.endRow();
                w.newRow(d1 + 1L);
                w.putLong(extra, 7L);
                w.endRow();
                w.commit();
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(r.def().columnIndex("extra")).isEqualTo(4);
            assertThat(rows(r)).containsExactly(
                    "\"a\"|1.0|null|" + d1 + "|null",
                    "null|null|null|" + (d1 + 1L) + "|7",
                    "null|null|null|" + d2 + "|5");
        }
    }

    /// Creating an existing table is false with ifNotExists and an error without; names match without regard to case.
    @Test
    void createOfAnExistingTableIsFalseOrAnError(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            assertThat(store.create(sensors("m", List.of(), 0L), false)).isTrue();
            assertThat(store.create(sensors("M", List.of(), 0L), true)).isFalse();
            assertThatThrownBy(() -> store.create(sensors("m", List.of(), 0L), false))
                    .isInstanceOf(CdbException.class);
            assertThat(store.tables()).containsExactly("m");
        }
    }

    /// A second writer waits for the first and fails when the wait runs out; once the first closes, it opens.
    @Test
    void aSecondWriterWaitsThenFails(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(sensors("m", List.of(), 0L), false);
            final TableWriter first = store.writer("m", 1_000L);
            final long start = System.nanoTime();
            assertThatThrownBy(() -> store.writer("m", 100L)).isInstanceOf(CdbException.class);
            assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(100_000_000L);
            first.close();
            store.writer("m", 1_000L).close();
        }
    }

    /// Truncate commits an empty table and deletes every version folder.
    @Test
    void truncateEmptiesTheTable(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.create(sensors("m", List.of(), 0L), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, micros("2026-11-01T10:00:00Z"), "a", 1.0, null);
                row(w, micros("2026-11-02T10:00:00Z"), "a", 1.0, null);
                w.commit();
                w.truncate();
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(r.rowCount()).isZero();
                assertThat(r.partitions()).isEmpty();
            }
            assertThat(folders(root.resolve("m"))).isEmpty();
        }
    }

    /// A TTL drops at commit the partitions that end before the newest row minus the TTL, and their folders.
    @Test
    void ttlDropsExpiredPartitionsAtCommit(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.create(sensors("m", List.of(), Retention.DAY_MICROS), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, micros("2026-11-01T10:00:00Z"), "a", 1.0, null);
                w.commit();
                row(w, micros("2026-11-04T10:00:00Z"), "a", 4.0, null);
                row(w, micros("2026-11-05T12:00:00Z"), "a", 5.0, null);
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(r.partitions()).extracting(PartitionReader::lowerBound)
                        .containsExactly(micros("2026-11-04T00:00:00Z"), micros("2026-11-05T00:00:00Z"));
            }
            assertThat(folders(root.resolve("m"))).containsExactlyInAnyOrder("2026-11-04.2", "2026-11-05.2");
        }
    }

    /// A NULL text and an empty one stay apart, for VARCHAR and SYMBOL, and non-ASCII text reads back whole.
    @Test
    void nullAndEmptyTextStayApart(@TempDir final Path root) {
        final long t = micros("2026-11-01T10:00:00Z");
        try (final Store store = Store.open(root)) {
            store.create(sensors("m", List.of(), 0L), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, t, null, 1.0, null);
                row(w, t + 1L, "", 2.0, "");
                row(w, t + 2L, "é", 3.0, "naïve");
                w.commit();
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(rows(r)).containsExactly(
                    "null|1.0|null|" + t,
                    "\"\"|2.0|\"\"|" + (t + 1L),
                    "\"é\"|3.0|\"naïve\"|" + (t + 2L));
        }
    }

    /// Rollback drops the pending rows and the columns added since the last commit.
    @Test
    void rollbackDropsPendingRowsAndColumns(@TempDir final Path root) {
        final long t = micros("2026-11-01T10:00:00Z");
        try (final Store store = Store.open(root)) {
            store.create(sensors("m", List.of(), 0L), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, t, "a", 1.0, null);
                w.commit();
                w.addColumn(new ColumnDef("extra", CdbType.SYMBOL));
                row(w, t + 1L, "b", 2.0, null);
                w.rollback();
                assertThat(w.def().columnIndex("extra")).isEqualTo(-1);
                row(w, t + 2L, "c", 3.0, null);
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(r.def().columns()).hasSize(4);
                assertThat(rows(r)).containsExactly("\"a\"|1.0|null|" + t, "\"c\"|3.0|null|" + (t + 2L));
            }
        }
    }

    /// A reader keeps its snapshot across later commits; a new reader sees them.
    @Test
    void aReaderKeepsItsSnapshot(@TempDir final Path root) {
        final long t = micros("2026-11-01T10:00:00Z");
        try (final Store store = Store.open(root)) {
            store.create(sensors("m", List.of(), 0L), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, t, "a", 1.0, null);
                w.commit();
                try (final TableReader before = store.reader("m")) {
                    row(w, t + 1L, "b", 2.0, null);
                    w.commit();
                    assertThat(rows(before)).containsExactly("\"a\"|1.0|null|" + t);
                    try (final TableReader after = store.reader("m")) {
                        assertThat(after.rowCount()).isEqualTo(2L);
                    }
                }
            }
        }
    }

    private static TableDef sensors(final String name, final List<String> dedupKeys, final long ttlMicros) {
        return new TableDef(name, List.of(
                new ColumnDef("sensor", CdbType.SYMBOL),
                new ColumnDef("value", CdbType.DOUBLE),
                new ColumnDef("note", CdbType.VARCHAR),
                new ColumnDef("ts", CdbType.TIMESTAMP)), 3, PartitionBy.DAY, dedupKeys, ttlMicros);
    }

    private static void row(final TableWriter w, final long ts, final String sensor, final double value,
            final String note) {
        w.newRow(ts);
        w.putText(SENSOR, sensor);
        w.putDouble(VALUE, value);
        w.putText(NOTE, note);
        w.endRow();
    }

    private static List<String> folders(final Path table) throws IOException {
        try (final Stream<Path> entries = Files.list(table)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
        }
    }
}
