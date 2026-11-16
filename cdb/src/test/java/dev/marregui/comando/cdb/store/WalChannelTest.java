package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A [Wal] forces its file through one channel, opened with the log and closed with it: no force opens the file, a
/// cut forces through the same channel, an interrupt that closed it during a failed force leaves a later cut a channel
/// opened again, and a closed log refuses a force and a cut without recording a failed force.
///
/// The tests set [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and
/// each test resets it.
class WalChannelTest {

    private static final TableDef DEF = new TableDef("t", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("l", CdbType.LONG)), 0, PartitionBy.DAY, List.of(), 0L);
    // forces enough that an open per force shows as a count, not a coincidence
    private static final int FORCES = 16;

    /// The recorder a test installs is gone before the next.
    @AfterEach
    void removeRecorder() {
        StoreFiles.recorder = null;
    }

    /// `FORCES` rounds of a write and a force: every force reaches the device through the log's channel, and none of
    /// them opens the log's file; the records read back after a reopen.
    @Test
    void forcesDoNotOpenTheLog(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        final Counts counts = install(file);
        for (int i = 1; i <= FORCES; i++) {
            wal.write(i, false, null, rows(2, 10L * i));
            counts.inForce.set(true);
            wal.force();
            counts.inForce.set(false);
            assertThat(wal.forcedSeq()).isEqualTo(i);
        }
        assertThat(counts.forces).as("forces of the log").hasValue(FORCES);
        assertThat(counts.opensInForce).as("opens of the log during its forces").hasValue(0);
        wal.close();
        StoreFiles.recorder = null;
        assertThat(replay(dir, 0L)).hasSize(FORCES);
    }

    /// A cut truncates the log in place and forces it through the same channel; the records written after it are
    /// forced there too, with no open, and are the only ones a reopen replays.
    @Test
    void aCutKeepsForcingThroughTheChannel(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.write(1L, false, null, rows(3, 10L));
        wal.force();
        final Counts counts = install(file);
        wal.reset();
        assertThat(counts.forces).as("the cut's forces of the log").hasValue(2);
        assertThat(wal.records()).isZero();
        final long seq = wal.write(2L, false, null, rows(1, 20L));
        counts.inForce.set(true);
        wal.force();
        counts.inForce.set(false);
        assertThat(counts.forces).hasValue(3);
        assertThat(counts.opensInForce).as("opens of the log during the force after the cut").hasValue(0);
        assertThat(wal.forcedSeq()).isEqualTo(seq);
        wal.close();
        StoreFiles.recorder = null;
        final List<Wal.Entry> entries = replay(dir, 1L);
        assertThat(entries).extracting(Wal.Entry::txn).containsExactly(2L);
        assertThat(entries.getFirst().rows().getLong(1, 0)).isEqualTo(20L);
    }

    /// An interrupt that lands during a force closes the log's channel: the force fails and is recorded, a durable
    /// wait past it throws, and a later cut, which a failed force does not refuse, opens the channel again and
    /// forces through it.
    @Test
    void aCutAfterAnInterruptedForceOpensTheChannelAgain(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        final long seq = wal.write(1L, false, null, rows(1, 10L));
        final AtomicBoolean interruptNext = new AtomicBoolean(true);
        final AtomicInteger opens = new AtomicInteger();
        StoreFiles.recorder = new Silent() {
            @Override
            public void forcing(final Path forced) {
                if (forced.equals(file) && interruptNext.getAndSet(false)) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void opening(final Path path) {
                if (path.equals(file)) {
                    opens.incrementAndGet();
                }
            }
        };
        try {
            assertThatThrownBy(wal::force).isInstanceOf(ClosedByInterruptException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(wal.forcedSeq()).isZero();
        assertThatThrownBy(() -> wal.awaitForced(seq, 0L)).isInstanceOf(CdbException.class);
        assertThat(opens).as("a failed force opens nothing").hasValue(0);
        final int before = opens.get();
        wal.reset();
        // the truncate and the header write open the file on their own; the channel is the third
        assertThat(opens.get() - before).as("opens of the log by the cut").isEqualTo(3);
        assertThat(wal.forcedSeq()).as("the cut covers the records it dropped").isEqualTo(seq);
        wal.close();
    }

    /// A second close does nothing, and a closed log with nothing written since its last force answers a force at
    /// once, opening nothing.
    @Test
    void closeTwiceIsQuiet(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.write(1L, false, null, rows(1, 10L));
        wal.force();
        final Counts counts = install(file);
        wal.close();
        wal.close();
        wal.force();
        assertThat(counts.forces).hasValue(0);
        assertThat(counts.opens).hasValue(0);
    }

    /// A force after close with a record to cover throws, forces nothing and opens nothing; no failure is recorded, so
    /// a durable wait times out rather than throwing "in doubt"; a cut is refused with the log left whole, and the
    /// record reads back after a reopen.
    @Test
    void aForceAfterCloseFailsCleanly(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        final long seq = wal.write(1L, false, null, rows(2, 10L));
        final Counts counts = install(file);
        wal.close();
        assertThatThrownBy(wal::force).isInstanceOf(IOException.class);
        assertThat(wal.forcedSeq()).isZero();
        assertThat(wal.awaitForced(seq, 0L)).as("not in doubt, only unforced").isFalse();
        assertThatThrownBy(wal::reset).isInstanceOf(IOException.class);
        assertThat(wal.records()).isEqualTo(1);
        assertThat(counts.forces).hasValue(0);
        assertThat(counts.opens).hasValue(0);
        StoreFiles.recorder = null;
        assertThat(replay(dir, 0L)).extracting(Wal.Entry::txn).containsExactly(1L);
    }

    /// Installs a recorder that counts the forces and the opens of `file`, and the opens while a test's force runs.
    private static Counts install(final Path file) {
        final Counts counts = new Counts();
        StoreFiles.recorder = new Silent() {
            @Override
            public void forcing(final Path forced) {
                if (forced.equals(file)) {
                    counts.forces.incrementAndGet();
                }
            }

            @Override
            public void opening(final Path path) {
                if (path.equals(file)) {
                    counts.opens.incrementAndGet();
                    if (counts.inForce.get()) {
                        counts.opensInForce.incrementAndGet();
                    }
                }
            }
        };
        return counts;
    }

    /// The entries after `afterTxn` that the log of `dir`, opened again, replays; the reopened log is closed.
    private static List<Wal.Entry> replay(final Path dir, final long afterTxn) throws IOException {
        final Wal reopened = Wal.open(dir);
        try {
            return reopened.scan(afterTxn, DEF);
        } finally {
            reopened.close();
        }
    }

    /// `n` rows of [#DEF]: timestamps 0 to n - 1 and values `first`, `first + 1`, and so on.
    private static RowBlock rows(final int n, final long first) {
        final RowBlock rows = new RowBlock(DEF.columns(), n);
        rows.size = n;
        for (int r = 0; r < n; r++) {
            rows.setLong(0, r, r);
            rows.setLong(1, r, first + r);
        }
        return rows;
    }

    /// What a test's recorder counted for the log's file.
    private static final class Counts {

        final AtomicInteger forces = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger opensInForce = new AtomicInteger();
        final AtomicBoolean inForce = new AtomicBoolean();
    }

    /// A recorder that ignores every operation; a test overrides the ones it watches.
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
