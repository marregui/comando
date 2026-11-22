package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/// A rewrite that fails part way, after its folder holds a file, deletes the folder before the failure leaves the
/// commit: no half-written version stays on the disk while the store runs, and the reopened store holds only the
/// folders its `_txn` names. The class sets [StoreFiles#recorder], a static: safe because cdb's tests run no two
/// classes at once in one JVM, and each test resets it.
class PartitionRewriteFailureTest {

    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("note", CdbType.VARCHAR),
            new ColumnDef("n", CdbType.LONG)), 0, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T00:00:00Z");
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    // the applier takes a logged commit within its schedule's seconds; bounded well above
    private static final long EVENTUALLY_NANOS = TimeUnit.SECONDS.toNanos(30L);

    /// A commit written on its own thread (every commit a checkpoint) rewrites its partition, and the second file write
    /// of the rewrite fails: the commit throws, its folder is already gone, and the reopened store reads the previous
    /// transaction from the folders its `_txn` names, then takes the same commit.
    @ParameterizedTest
    @EnumSource(Failure.class)
    void aFailedRewriteOfADirectCommitLeavesNoFolder(final Failure failure, @TempDir final Path root)
            throws IOException {
        final Path table = root.resolve(M.name());
        final String first = TableFiles.folder(TS, PartitionBy.DAY, 1L);
        final FailSecondWrite recorder = new FailSecondWrite(table.resolve(TableFiles.folder(TS, PartitionBy.DAY, 2L)),
                failure);
        final List<String> before;
        try {
            try (final Store store = Store.open(root)) {
                store.directRows = 0;
                store.create(M, false);
                commit(store, TS + 10L, TS + 20L);
                before = read(store);
                assertThat(folders(table)).containsExactly(first);
                StoreFiles.recorder = recorder;
                try (final TableWriter w = store.writer(M.name(), WAIT_MILLIS)) {
                    row(w, TS + 15L);
                    assertThatThrownBy(w::commit).isInstanceOf(failure.thrown);
                }
                StoreFiles.recorder = null;
                assertThat(recorder.writes).as("writes into the rewrite's folder").hasValue(2);
                assertThat(folders(table)).containsExactly(first);
                assertThat(read(store)).isEqualTo(before);
            }
            try (final Store store = Store.open(root)) {
                assertThat(folders(table)).containsExactlyInAnyOrderElementsOf(named(table));
                assertThat(read(store)).isEqualTo(before);
                commit(store, TS + 15L);
                assertThat(read(store)).containsExactly(line(TS + 10L), line(TS + 15L), line(TS + 20L));
            }
        } finally {
            StoreFiles.recorder = null;
        }
    }

    /// The applier's batch of a logged commit rewrites the partition, and an `Error` stops it at the second file
    /// write, as running out of heap did: the table is suspended, its rewrite's folder is already gone, and the
    /// reopened store replays the commit into the folders its `_txn` names.
    @Test
    void aFailedRewriteOfTheApplierSuspendsTheTableAndLeavesNoFolder(@TempDir final Path root) throws IOException {
        final Path table = root.resolve(M.name());
        final FailSecondWrite recorder = new FailSecondWrite(table.resolve(TableFiles.folder(TS, PartitionBy.DAY, 2L)),
                Failure.ERROR);
        try {
            try (final Store store = Store.open(root)) {
                store.create(M, false);
                commit(store, TS + 10L, TS + 20L);
                store.checkpoint(M.name());
                final List<String> applied = folders(table);
                assertThat(applied).containsExactly(TableFiles.folder(TS, PartitionBy.DAY, 1L));
                StoreFiles.recorder = recorder;
                commit(store, TS + 15L);
                assertEventually(() -> suspended(store), "table m suspended");
                StoreFiles.recorder = null;
                assertThat(recorder.writes).as("writes into the rewrite's folder").hasValue(2);
                assertThat(folders(table)).isEqualTo(applied);
            }
            try (final Store store = Store.open(root)) {
                assertThat(read(store)).containsExactly(line(TS + 10L), line(TS + 15L), line(TS + 20L));
                assertThat(folders(table)).containsExactlyInAnyOrderElementsOf(named(table));
            }
        } finally {
            StoreFiles.recorder = null;
        }
    }

    private static void commit(final Store store, final long... timestamps) {
        try (final TableWriter w = store.writer(M.name(), WAIT_MILLIS)) {
            for (final long ts : timestamps) {
                row(w, ts);
            }
            w.commit();
        }
    }

    private static void row(final TableWriter w, final long ts) {
        w.newRow(ts);
        w.putText(1, "note-" + (ts - TS));
        w.putLong(2, ts - TS);
        w.endRow();
    }

    private static String line(final long ts) {
        return ts + "|\"note-" + (ts - TS) + "\"|" + (ts - TS);
    }

    private static List<String> read(final Store store) {
        try (final TableReader r = store.reader(M.name())) {
            return rows(r);
        }
    }

    private static boolean suspended(final Store store) {
        return store.walStatus().stream().anyMatch(s -> s.table().equals(M.name()) && s.suspended());
    }

    // the folders the table's _txn names
    private static List<String> named(final Path table) throws IOException {
        return TableFiles.decodeTxn(StoreFiles.readText(table.resolve(TableFiles.TXN)), table.toString())
                .partitions().stream().map(TableFiles.Entry::folder).toList();
    }

    private static List<String> folders(final Path table) throws IOException {
        try (final Stream<Path> entries = Files.list(table)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
        }
    }

    // polls condition with a doubling back-off until it holds or EVENTUALLY_NANOS pass
    private static void assertEventually(final BooleanSupplier condition, final String what) {
        final long start = System.nanoTime();
        long backoffNanos = TimeUnit.MICROSECONDS.toNanos(100L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - start - EVENTUALLY_NANOS > 0L) {
                fail("condition not reached [what=" + what + ", waitMillis="
                        + TimeUnit.NANOSECONDS.toMillis(EVENTUALLY_NANOS) + ']');
            }
            LockSupport.parkNanos(backoffNanos);
            backoffNanos = Math.min(2L * backoffNanos, TimeUnit.MILLISECONDS.toNanos(50L));
        }
    }

    /// What the injected write failure throws, and what the commit then throws.
    enum Failure {

        /// An I/O failure: the commit wraps it in a [CdbException].
        RUNTIME(CdbException.class),
        /// Running out of heap: the commit rethrows it, and the table refuses commits until the store reopens.
        ERROR(OutOfMemoryError.class);

        final Class<? extends Throwable> thrown;

        Failure(final Class<? extends Throwable> thrown) {
            this.thrown = thrown;
        }
    }

    /// A recorder that fails the second write into a file of `folder`, once a file of the rewrite is on the disk.
    private static final class FailSecondWrite implements StoreFiles.Recorder {

        final AtomicInteger writes = new AtomicInteger();
        private final Path folder;
        private final Failure failure;

        FailSecondWrite(final Path folder, final Failure failure) {
            this.folder = folder;
            this.failure = failure;
        }

        @Override
        public void created(final Path path) {
        }

        @Override
        public void wrote(final Path file, final long position, final ByteBuffer bytes) {
            if (folder.equals(file.getParent()) && writes.incrementAndGet() == 2) {
                switch (failure) {
                    case RUNTIME -> throw new UncheckedIOException(new IOException("injected rewrite write failure"
                            + " [file=" + file + ']'));
                    case ERROR -> throw new OutOfMemoryError("injected");
                }
            }
        }

        @Override
        public void truncated(final Path file, final long size) {
        }

        @Override
        public void forcing(final Path file) {
        }

        @Override
        public void folderForcing(final Path path) {
        }

        @Override
        public void moved(final Path from, final Path to) {
        }

        @Override
        public void deleted(final Path path) {
        }
    }
}
