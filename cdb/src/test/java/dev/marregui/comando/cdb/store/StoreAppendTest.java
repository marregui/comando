package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A commit appends in place to a partition whose committed rows all come before the new ones, and rewrites the
/// partition otherwise.
///
/// - every case is checked against a store that always rewrites (`appendInPlace` off): both read the same rows
/// - an append keeps the partition's folder; a rewrite names a new one
/// - a snapshot reads only its own rows of a folder later commits append to
/// - bytes a failed or crashed commit appended past the committed ends are written over, or cut when the store opens
class StoreAppendTest {

    private static final int SENSOR = 0;
    private static final int VALUE = 1;
    private static final int NOTE = 2;
    private static final int N = 3;
    private static final int FLAG = 4;
    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    // a day of rows ten a second, the bench's partition
    private static final int DAY_ROWS = 864_000;
    private static final long TENTH_MICROS = 100_000L;

    /// Twelve commits of rows after the committed ones, NULLs, empty and non-ASCII text among them, read the same as
    /// the rewrites, after each commit and after a reopen; the partition keeps its first folder throughout.
    @Test
    void inOrderAppendsReadEqualToRewrites(@TempDir final Path root) {
        try (final Pair pair = Pair.open(root, List.of())) {
            for (int commit = 0; commit < 12; commit++) {
                final int base = commit * 5;
                pair.commit(w -> {
                    for (int i = 0; i < 5; i++) {
                        final int k = base + i;
                        row(w, DAY1 + k * 1_000L, k % 3 == 0 ? null : "s" + k % 4, k * 0.5,
                                k % 4 == 0 ? null : k % 4 == 1 ? "" : "é" + k, k % 5 == 0 ? Long.MIN_VALUE : k,
                                k % 2 == 0);
                    }
                });
                assertThat(folders(pair.root())).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
            }
            assertThat(pair.rows()).hasSize(60);
        }
        try (final Pair pair = Pair.reopen(root)) {
            assertThat(pair.rows()).hasSize(60);
        }
    }

    /// A row before the committed maximum rewrites the partition as a new folder, ordered as the rewrite orders it;
    /// the next in-order commit appends to that folder.
    @Test
    void anOutOfOrderRowRewritesThePartition(@TempDir final Path root) {
        try (final Pair pair = Pair.open(root, List.of())) {
            pair.commit(w -> {
                row(w, DAY1 + 10L, "a", 1.0, "x", 1L, true);
                row(w, DAY1 + 20L, "b", 2.0, "y", 2L, false);
            });
            pair.commit(w -> {
                row(w, DAY1 + 30L, "c", 3.0, "z", 3L, true);
                row(w, DAY1 + 15L, "d", 4.0, null, 4L, true);
            });
            assertThat(folders(pair.root())).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 2L));
            pair.commit(w -> row(w, DAY1 + 40L, "e", 5.0, "w", 5L, false));
            assertThat(folders(pair.root())).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 2L));
            assertThat(pair.rows()).extracting(row -> row.split("\\|")[SENSOR])
                    .containsExactly("\"a\"", "\"d\"", "\"b\"", "\"c\"", "\"e\"");
        }
    }

    /// Without dedup keys a row at the committed maximum appends after the committed rows of that timestamp.
    @Test
    void withoutKeysARowAtTheMaximumAppends(@TempDir final Path root) {
        try (final Pair pair = Pair.open(root, List.of())) {
            pair.commit(w -> row(w, DAY1 + 10L, "a", 1.0, "first", 1L, true));
            pair.commit(w -> {
                row(w, DAY1 + 10L, "a", 2.0, "second", 2L, true);
                row(w, DAY1 + 10L, "a", 3.0, "third", 3L, true);
            });
            assertThat(folders(pair.root())).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
            assertThat(pair.rows()).extracting(row -> row.split("\\|")[NOTE])
                    .containsExactly("\"first\"", "\"second\"", "\"third\"");
        }
    }

    /// With dedup keys a row at the committed maximum rewrites the partition: of equal keys the new row replaces the
    /// committed one, a row of other keys is kept beside it.
    @Test
    void withKeysARowAtTheMaximumRewrites(@TempDir final Path root) {
        try (final Pair pair = Pair.open(root, List.of("ts", "sensor"))) {
            pair.commit(w -> {
                row(w, DAY1 + 5L, "a", 0.5, "kept", 0L, true);
                row(w, DAY1 + 10L, "a", 1.0, "replaced", 1L, true);
            });
            pair.commit(w -> {
                row(w, DAY1 + 10L, "b", 2.0, "other key", 2L, true);
                row(w, DAY1 + 10L, "a", 3.0, "replaces", 3L, true);
            });
            assertThat(folders(pair.root())).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 2L));
            assertThat(pair.rows()).extracting(row -> row.split("\\|")[NOTE])
                    .containsExactlyInAnyOrder("\"kept\"", "\"other key\"", "\"replaces\"");
        }
    }

    /// With dedup keys, rows after the committed maximum that repeat their keys among themselves keep only the last
    /// written, and append.
    @Test
    void withKeysDuplicatesAmongNewRowsAreDroppedBeforeTheAppend(@TempDir final Path root) {
        try (final Pair pair = Pair.open(root, List.of("ts", "sensor"))) {
            pair.commit(w -> row(w, DAY1 + 10L, "a", 1.0, "committed", 1L, true));
            pair.commit(w -> {
                row(w, DAY1 + 20L, "a", 2.0, "dropped", 2L, true);
                row(w, DAY1 + 30L, "b", 3.0, "kept", 3L, true);
                row(w, DAY1 + 20L, "a", 4.0, "last", 4L, true);
            });
            assertThat(folders(pair.root())).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
            assertThat(pair.rows()).extracting(row -> row.split("\\|")[NOTE])
                    .containsExactly("\"committed\"", "\"last\"", "\"kept\"");
        }
    }

    /// A snapshot taken before three appends to its partition reads only its own rows while they land and after,
    /// though the newer snapshots map the same folder; the folder outlives every snapshot, as `_txn` names it.
    @Test
    void aHeldSnapshotReadsOnlyItsRowsDuringLaterAppends(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table(List.of()), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, DAY1 + 1L, "a", 1.0, "one", 1L, true);
                w.commit();
                try (final TableReader first = store.reader("m")) {
                    final List<String> before = rows(first);
                    row(w, DAY1 + 2L, "b", 2.0, "two", 2L, true);
                    w.commit();
                    try (final TableReader second = store.reader("m")) {
                        row(w, DAY1 + 3L, "c", 3.0, "three", 3L, true);
                        w.commit();
                        row(w, DAY1 + 4L, "d", 4.0, "four", 4L, true);
                        w.commit();
                        assertThat(rows(first)).isEqualTo(before).hasSize(1);
                        assertThat(rows(second)).hasSize(2);
                    }
                    assertThat(rows(first)).isEqualTo(before);
                }
                try (final TableReader last = store.reader("m")) {
                    assertThat(rows(last)).hasSize(4);
                }
            }
            assertThat(folders(root.resolve("m"))).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(rows(r)).hasSize(4);
        }
    }

    /// A snapshot holding a folder that a later commit rewrote keeps it until it closes; then the folder goes.
    @Test
    void aRewrittenFolderGoesWithItsLastSnapshot(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table(List.of()), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, DAY1 + 10L, "a", 1.0, "one", 1L, true);
                w.commit();
                try (final TableReader held = store.reader("m")) {
                    row(w, DAY1 + 20L, "b", 2.0, "two", 2L, true);
                    w.commit();
                    row(w, DAY1 + 5L, "c", 3.0, "three", 3L, true);
                    w.commit();
                    assertThat(folders(root.resolve("m"))).containsExactlyInAnyOrder(
                            TableFiles.folder(DAY1, PartitionBy.DAY, 1L),
                            TableFiles.folder(DAY1, PartitionBy.DAY, 3L));
                    assertThat(rows(held)).hasSize(1);
                }
            }
            assertThat(folders(root.resolve("m"))).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 3L));
        }
    }

    /// A commit that fails after its column writes, before `_txn`, leaves the previous transaction readable; the next
    /// commit, shorter than the failed one, rewrites the partition the failure left bytes in, and the one after it
    /// appends again.
    @Test
    void aShorterCommitAfterAFailedOneRewritesThenAppends(@TempDir final Path root) {
        try (final Pair pair = Pair.open(root, List.of())) {
            pair.commit(w -> row(w, DAY1 + 1L, "a", 1.0, "one", 1L, true));
            pair.subject().beforeTxnReplace = () -> {
                throw new UncheckedIOException(new IOException("injected failure before the txn replace"));
            };
            try (final TableWriter w = pair.subject().writer("m", 1_000L)) {
                for (int i = 0; i < 5; i++) {
                    row(w, DAY1 + 2L + i, "b", 2.0, "a long failed note", 2L, false);
                }
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            }
            pair.subject().beforeTxnReplace = () -> {
            };
            pair.commit(w -> row(w, DAY1 + 3L, "c", 3.0, "two", 3L, true));
            final List<String> rewritten = folders(pair.root());
            assertThat(rewritten).hasSize(1).doesNotContain(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
            pair.commit(w -> row(w, DAY1 + 4L, "d", 4.0, "three", 4L, true));
            assertThat(folders(pair.root())).isEqualTo(rewritten);
            assertThat(pair.rows()).hasSize(3);
        }
        try (final Pair pair = Pair.reopen(root)) {
            assertThat(pair.rows()).hasSize(3);
        }
    }

    /// A commit that fails after its column writes, before `_txn`, leaves the previous transaction readable, before
    /// and after a reopen; the next commit of as many rows and bytes reads correct.
    @Test
    void aFailedCommitLeavesThePreviousTransactionAndTheNextIsCorrect(@TempDir final Path root) {
        try (final Pair pair = Pair.open(root, List.of())) {
            pair.commit(w -> row(w, DAY1 + 1L, "a", 1.0, "one", 1L, true));
            pair.subject().beforeTxnReplace = () -> {
                throw new UncheckedIOException(new IOException("injected failure before the txn replace"));
            };
            try (final TableWriter w = pair.subject().writer("m", 1_000L)) {
                row(w, DAY1 + 2L, "b", 2.0, "failed", 2L, false);
                row(w, DAY1 + 3L, "c", 3.0, "failed", 3L, false);
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            }
            try (final TableReader r = pair.subject().reader("m")) {
                assertThat(rows(r)).containsExactly("\"a\"|1.0|\"one\"|1|true|" + (DAY1 + 1L));
            }
            pair.subject().beforeTxnReplace = () -> {
            };
            pair.commit(w -> {
                row(w, DAY1 + 4L, "d", 4.0, "written over", 4L, true);
                row(w, DAY1 + 5L, null, 5.0, null, 5L, false);
            });
            assertThat(pair.rows()).hasSize(3);
        }
        try (final Pair pair = Pair.reopen(root)) {
            assertThat(pair.rows()).hasSize(3);
        }
    }

    /// A store closed after a commit failed before `_txn` (as a crash leaves it: rows appended past the committed
    /// ends, `_txn` not replaced) reopens at the committed rows, its files cut to them; the next, shorter, commit
    /// appends from there.
    @Test
    void aCommitCutShortBeforeTheTxnRecoversToTheCommittedRows(@TempDir final Path root) throws IOException {
        final Path partition = root.resolve("m").resolve(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
        final long noteBytes;
        try (final Store store = Store.open(root)) {
            store.create(table(List.of()), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, DAY1 + 1L, "a", 1.0, "one", 1L, true);
                w.commit();
                noteBytes = Files.size(partition.resolve("note.d"));
                store.beforeTxnReplace = () -> {
                    throw new UncheckedIOException(new IOException("injected crash before the txn replace"));
                };
                for (int i = 0; i < 10; i++) {
                    row(w, DAY1 + 2L + i, "b", 2.0, "a longer note than the committed", 2L, false);
                }
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            }
        }
        assertThat(Files.size(partition.resolve("value.d"))).isEqualTo(11L * Double.BYTES);
        assertThat(Files.size(partition.resolve("note.d"))).isGreaterThan(noteBytes);
        try (final Store store = Store.open(root)) {
            assertThat(Files.size(partition.resolve("value.d"))).isEqualTo(Double.BYTES);
            assertThat(Files.size(partition.resolve("flag.d"))).isEqualTo(1L);
            assertThat(Files.size(partition.resolve("sensor.d"))).isEqualTo(Integer.BYTES);
            assertThat(Files.size(partition.resolve("note.i"))).isEqualTo(Long.BYTES);
            assertThat(Files.size(partition.resolve("note.d"))).isEqualTo(noteBytes);
            try (final TableReader r = store.reader("m")) {
                assertThat(rows(r)).containsExactly("\"a\"|1.0|\"one\"|1|true|" + (DAY1 + 1L));
            }
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, DAY1 + 3L, "c", 3.0, "three", 3L, false);
                w.commit();
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(rows(r)).containsExactly(
                    "\"a\"|1.0|\"one\"|1|true|" + (DAY1 + 1L),
                    "\"c\"|3.0|\"three\"|3|false|" + (DAY1 + 3L));
        }
        assertThat(folders(root.resolve("m"))).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
    }

    /// Bytes past the committed ends of every column file, as a crash mid-append leaves them torn, are cut when the
    /// store opens, before the partition is mapped.
    @Test
    void tornBytesPastTheCommittedEndsAreCutOnOpen(@TempDir final Path root) throws IOException {
        final Path partition = root.resolve("m").resolve(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
        try (final Store store = Store.open(root)) {
            store.create(table(List.of()), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, DAY1 + 1L, "a", 1.0, "one", 1L, true);
                row(w, DAY1 + 2L, "b", 2.0, null, 2L, false);
                w.commit();
            }
        }
        final List<String> files = List.of("sensor.d", "value.d", "note.i", "note.d", "n.d", "flag.d", "ts.d");
        final long[] sizes = new long[files.size()];
        for (int i = 0; i < sizes.length; i++) {
            final Path file = partition.resolve(files.get(i));
            sizes[i] = Files.size(file);
            StoreFiles.writeAt(file, sizes[i], ByteBuffer.wrap(new byte[]{1, 2, 3}));
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(rows(r)).containsExactly(
                    "\"a\"|1.0|\"one\"|1|true|" + (DAY1 + 1L),
                    "\"b\"|2.0|null|2|false|" + (DAY1 + 2L));
            for (int i = 0; i < sizes.length; i++) {
                assertThat(Files.size(partition.resolve(files.get(i)))).as(files.get(i)).isEqualTo(sizes[i]);
            }
        }
    }

    /// A column added between appends rewrites the partition that lacks its file once, its committed rows NULL
    /// there; the next commit appends to the new folder.
    @Test
    void aColumnAddedBetweenAppendsRewritesOnceThenAppends(@TempDir final Path root) {
        try (final Pair pair = Pair.open(root, List.of())) {
            pair.commit(w -> row(w, DAY1 + 1L, "a", 1.0, "one", 1L, true));
            pair.commit(w -> {
                final int extra = w.addColumn(new ColumnDef("extra", CdbType.VARCHAR));
                row(w, DAY1 + 2L, "b", 2.0, "two", 2L, false);
                w.newRow(DAY1 + 3L);
                w.putText(extra, "x");
                w.endRow();
            });
            assertThat(folders(pair.root())).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 2L));
            pair.commit(w -> {
                w.newRow(DAY1 + 4L);
                w.putText(w.def().columnIndex("extra"), "y");
                w.endRow();
            });
            assertThat(folders(pair.root())).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 2L));
            assertThat(pair.rows()).extracting(row -> row.substring(row.lastIndexOf('|') + 1))
                    .containsExactly("null", "null", "\"x\"", "\"y\"");
        }
    }

    /// A TTL that expires a partition the same commit appended to drops it, and its folder once nothing holds it.
    @Test
    void anExpiredPartitionAppendedToIsDropped(@TempDir final Path root) {
        final long later = DAY1 + 3 * Retention.DAY_MICROS;
        try (final Store store = Store.open(root)) {
            store.create(new TableDef("m", columns(), 5, PartitionBy.DAY, List.of(), Retention.DAY_MICROS), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                row(w, DAY1 + 1L, "a", 1.0, "one", 1L, true);
                w.commit();
                row(w, DAY1 + 2L, "b", 2.0, "two", 2L, true);
                row(w, later, "c", 3.0, "three", 3L, true);
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(rows(r)).containsExactly("\"c\"|3.0|\"three\"|3|true|" + later);
            }
            assertThat(folders(root.resolve("m"))).containsExactly(TableFiles.folder(later, PartitionBy.DAY, 2L));
        }
    }

    /// One row into a partition of a day's rows appends it: the folder stays, each column file grows by one row.
    @Test
    void oneRowIntoALargePartitionAppendsOneRow(@TempDir final Path root) throws IOException {
        final Path partition = root.resolve("m").resolve(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
        try (final Store store = Store.open(root)) {
            store.create(table(List.of()), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                for (int i = 0; i < DAY_ROWS; i++) {
                    row(w, DAY1 + i * TENTH_MICROS, i % 2 == 0 ? "ok" : "warning", i, "n" + i % 10, i, i % 3 == 0);
                }
                w.commit();
                final long noteBytes = Files.size(partition.resolve("note.d"));
                row(w, DAY1 + (DAY_ROWS - 1L) * TENTH_MICROS + 1L, "ok", 1.0, "last", 1L, true);
                w.commit();
                assertThat(folders(root.resolve("m"))).containsExactly(TableFiles.folder(DAY1, PartitionBy.DAY, 1L));
                assertThat(Files.size(partition.resolve("value.d"))).isEqualTo((DAY_ROWS + 1L) * Double.BYTES);
                assertThat(Files.size(partition.resolve("note.i"))).isEqualTo((DAY_ROWS + 1L) * Long.BYTES);
                assertThat(Files.size(partition.resolve("note.d"))).isEqualTo(noteBytes + "last".length());
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(r.rowCount()).isEqualTo(DAY_ROWS + 1L);
                final String[] last = new String[1];
                r.partitions().getFirst().texts(NOTE, DAY_ROWS, 1, last);
                assertThat(last[0]).isEqualTo("last");
            }
        }
    }

    private static List<ColumnDef> columns() {
        return List.of(
                new ColumnDef("sensor", CdbType.SYMBOL),
                new ColumnDef("value", CdbType.DOUBLE),
                new ColumnDef("note", CdbType.VARCHAR),
                new ColumnDef("n", CdbType.LONG),
                new ColumnDef("flag", CdbType.BOOLEAN),
                new ColumnDef("ts", CdbType.TIMESTAMP));
    }

    private static TableDef table(final List<String> dedupKeys) {
        return new TableDef("m", columns(), 5, PartitionBy.DAY, dedupKeys, 0L);
    }

    private static void row(
            final TableWriter w,
            final long ts,
            final String sensor,
            final double value,
            final String note,
            final long n,
            final boolean flag
    ) {
        w.newRow(ts);
        w.putText(SENSOR, sensor);
        w.putDouble(VALUE, value);
        w.putText(NOTE, note);
        w.putLong(N, n);
        w.putBoolean(FLAG, flag);
        w.endRow();
    }

    private static List<String> folders(final Path table) {
        try (final Stream<Path> entries = Files.list(table)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The store under test, which appends in place, beside a reference store over a folder of its own that always
    /// rewrites; every commit goes to both, and [#rows] checks they read the same. [#root] is the subject's table.
    private record Pair(Path root, Store subject, Store reference) implements AutoCloseable {

        static Pair open(final Path root, final List<String> dedupKeys) {
            final Pair pair = reopen(root);
            pair.subject.create(table(dedupKeys), false);
            pair.reference.create(table(dedupKeys), false);
            return pair;
        }

        static Pair reopen(final Path root) {
            final Store subject = Store.open(root.resolve("subject"));
            final Store reference = Store.open(root.resolve("reference"));
            reference.appendInPlace = false;
            return new Pair(root.resolve("subject").resolve("m"), subject, reference);
        }

        void commit(final Consumer<TableWriter> writes) {
            for (final Store store : List.of(subject, reference)) {
                try (final TableWriter w = store.writer("m", 1_000L)) {
                    writes.accept(w);
                    w.commit();
                }
            }
            rows();
        }

        List<String> rows() {
            try (final TableReader s = subject.reader("m"); final TableReader r = reference.reader("m")) {
                final List<String> read = StoreRows.rows(s);
                assertThat(read).isEqualTo(StoreRows.rows(r));
                return read;
            }
        }

        @Override
        public void close() {
            try (reference) {
                subject.close();
            }
        }
    }
}
