package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;

/// [Wal#records] and [Wal#bytes] answer while an append holds the log's monitor through its force: the applier's
/// checkpoint test never waits for a commit's fsync.
///
/// The test sets [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and
/// the test resets it.
class WalReadersTest {

    private static final TableDef DEF = new TableDef("t", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("l", CdbType.LONG)), 0, PartitionBy.DAY, List.of(), 0L);
    // the bound of every wait here: a read that has not answered by then waits for the force
    private static final long WAIT_SECONDS = 10L;

    /// The recorder a test installs is gone before the next.
    @AfterEach
    void removeRecorder() {
        StoreFiles.recorder = null;
    }

    /// With an append held inside its force, the log's size and record count are read at once, as before the append;
    /// once the force goes on they show the record.
    @Test
    void sizeAndRecordsAnswerWhileAnAppendForces(@TempDir final Path dir) throws Exception {
        final Wal wal = Wal.open(dir);
        final long empty = wal.bytes();
        final Path file = dir.resolve(Wal.FILE);
        final CountDownLatch forcing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        StoreFiles.recorder = new Silent() {
            @Override
            public void forcing(final Path forced) throws IOException {
                if (forced.equals(file)) {
                    forcing.countDown();
                    try {
                        release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        final RowBlock rows = new RowBlock(DEF.columns(), 1);
        rows.size = 1;
        final CompletableFuture<Void> append = CompletableFuture.runAsync(() -> {
            try {
                wal.append(1L, false, null, rows);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            assertThat(forcing.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("append reached its force").isTrue();
            final CompletableFuture<long[]> read = CompletableFuture.supplyAsync(
                    () -> new long[] {wal.records(), wal.bytes()});
            final long[] seen = read.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(seen[0]).as("records during the force").isZero();
            assertThat(seen[1]).as("bytes during the force").isEqualTo(empty);
        } finally {
            release.countDown();
        }
        append.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(wal.records()).isEqualTo(1);
        assertThat(wal.bytes()).isGreaterThan(empty);
    }

    /// A recorder that sees nothing; a test overrides what it watches.
    private static class Silent implements StoreFiles.Recorder {

        @Override
        public void created(final Path path) {
        }

        @Override
        public void wrote(final Path file, final long position, final ByteBuffer bytes) {
        }

        @Override
        public void truncated(final Path file, final long size) {
        }

        @Override
        public void forcing(final Path file) throws IOException {
        }

        @Override
        public void folderForcing(final Path folder) throws IOException {
        }

        @Override
        public void moved(final Path from, final Path to) {
        }

        @Override
        public void deleted(final Path path) {
        }
    }
}
