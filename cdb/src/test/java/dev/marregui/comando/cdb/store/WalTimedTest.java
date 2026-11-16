package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/// [Wal#write] logs a commit without forcing it and gives it a sequence number; [Wal#force] covers every record
/// written before it, outside the log's monitor; [Wal#awaitForced] waits, bounded, for the force that covers one. A
/// failed force leaves its records in doubt. The log's format 2 salts every frame's CRC with a value drawn at each
/// cut, so a frame of an earlier generation never reads as a commit; a format 1 log still reads.
///
/// The tests set [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and
/// each test resets it.
class WalTimedTest {

    private static final TableDef DEF = new TableDef("t", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("l", CdbType.LONG)), 0, PartitionBy.DAY, List.of(), 0L);
    // the bound of every wait for another thread here: one that has not answered by then is stuck
    private static final long WAIT_SECONDS = 10L;
    // a durable wait the test expects to end by a wake-up long before it, never by its timeout
    private static final long LONG_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30L);
    // the CWAL magic and format 1's version, as the previous format wrote its header
    private static final int MAGIC = 0x4C415743;
    private static final int FORMAT_1 = 1;
    private static final int FORMAT_1_HEADER = 8;

    /// The recorder a test installs is gone before the next.
    @AfterEach
    void removeRecorder() {
        StoreFiles.recorder = null;
    }

    /// A written record is not forced: no force of the log runs and no wait sees it covered, until a force does. A
    /// force with nothing new written forces nothing; the record reads back after a reopen.
    @Test
    void aWriteIsCoveredOnlyByAForce(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        final AtomicInteger forces = countForces(file);
        final long seq = wal.write(1L, false, null, rows(2, 10L));
        assertThat(seq).isEqualTo(1L);
        assertThat(forces).hasValue(0);
        assertThat(wal.forcedSeq()).isZero();
        assertThat(wal.awaitForced(seq, 0L)).isFalse();
        wal.force();
        assertThat(forces).hasValue(1);
        assertThat(wal.forcedSeq()).isEqualTo(seq);
        assertThat(wal.awaitForced(seq, 0L)).isTrue();
        wal.force();
        assertThat(forces).as("nothing written since the last force").hasValue(1);
        assertThat(Wal.open(dir).scan(0L, DEF)).extracting(Wal.Entry::txn).containsExactly(1L);
    }

    /// With a force held inside its device force, another thread's write goes through and its record is not
    /// covered when the force ends: the force covers what was written when it began, and holds no monitor a write
    /// needs.
    @Test
    void aWriteGoesOnWhileAForceRuns(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        assertThat(wal.write(1L, false, null, rows(1, 10L))).isEqualTo(1L);
        final CountDownLatch forcing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        StoreFiles.recorder = new Silent() {
            @Override
            public void forcing(final Path forced) {
                if (forced.equals(file)) {
                    forcing.countDown();
                    try {
                        release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (final InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        final AtomicReference<Throwable> forceFailure = new AtomicReference<>();
        final CountDownLatch forced = new CountDownLatch(1);
        final Thread forcer = Thread.ofPlatform().name("wal-timed-test-forcer").start(() -> {
            try {
                wal.force();
            } catch (final IOException | RuntimeException e) {
                forceFailure.set(e);
            } finally {
                forced.countDown();
            }
        });
        final AtomicLong writtenSeq = new AtomicLong();
        final AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        final CountDownLatch written = new CountDownLatch(1);
        try {
            assertThat(forcing.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("force reached the device force").isTrue();
            Thread.ofPlatform().name("wal-timed-test-writer").start(() -> {
                try {
                    writtenSeq.set(wal.write(2L, false, null, rows(1, 20L)));
                } catch (final IOException | RuntimeException e) {
                    writeFailure.set(e);
                } finally {
                    written.countDown();
                }
            });
            assertThat(written.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("write during the force").isTrue();
            assertThat(writeFailure).hasNullValue();
            assertThat(writtenSeq).hasValue(2L);
            assertThat(wal.forcedSeq()).as("forced during the force").isZero();
        } finally {
            release.countDown();
        }
        assertThat(forced.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("force ended").isTrue();
        forcer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(forceFailure).hasNullValue();
        assertThat(wal.forcedSeq()).as("the force covers what was written when it began").isEqualTo(1L);
        assertThat(wal.awaitForced(2L, 0L)).isFalse();
        StoreFiles.recorder = null;
        wal.force();
        assertThat(wal.awaitForced(2L, 0L)).isTrue();
    }

    /// A retract gives its transaction back but not its sequence number: the next write of the same transaction gets
    /// a new one, so a wait on the retracted record never takes the new one's force for its own.
    @Test
    void aWriteAfterARetractGetsANewSequenceNumber(@TempDir final Path dir) throws Exception {
        final Wal wal = Wal.open(dir);
        assertThat(wal.write(1L, false, null, rows(1, 10L))).isEqualTo(1L);
        assertThat(wal.write(2L, false, null, rows(1, 20L))).isEqualTo(2L);
        assertThat(wal.retract(2L)).isTrue();
        assertThat(wal.write(2L, false, null, rows(1, 30L))).isEqualTo(3L);
        wal.force();
        assertThat(wal.forcedSeq()).isEqualTo(3L);
        final List<Wal.Entry> entries = Wal.open(dir).scan(0L, DEF);
        assertThat(entries).extracting(Wal.Entry::txn).containsExactly(1L, 2L);
        assertThat(entries.get(1).rows().longs[1][0]).isEqualTo(30L);
    }

    /// A failed force leaves the forced sequence where it was, wakes a waiter past it with "commit in doubt", and
    /// fails every later wait past it and every later force without forcing again; a record it covered before stays
    /// covered. A cut, whose records the checkpoint made durable, covers every record written.
    @Test
    void aFailedForcePutsItsRecordsInDoubt(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.write(1L, false, null, rows(1, 10L));
        wal.force();
        wal.write(2L, false, null, rows(1, 20L));
        wal.write(3L, false, null, rows(1, 30L));
        final AtomicReference<Throwable> waited = new AtomicReference<>();
        final CountDownLatch woken = new CountDownLatch(1);
        final Thread waiter = Thread.ofPlatform().name("wal-timed-test-waiter").start(() -> {
            try {
                waited.set(new AssertionError("covered [result=" + wal.awaitForced(3L, LONG_WAIT_NANOS) + ']'));
            } catch (final InterruptedException | RuntimeException e) {
                waited.set(e);
            } finally {
                woken.countDown();
            }
        });
        assertEventually(() -> waiter.getState() == Thread.State.TIMED_WAITING, "waiter parked");
        final AtomicInteger forces = new AtomicInteger();
        StoreFiles.recorder = new Silent() {
            @Override
            public void forcing(final Path forced) throws IOException {
                if (forced.equals(file)) {
                    forces.incrementAndGet();
                    throw new IOException("injected force failure");
                }
            }
        };
        assertThatThrownBy(wal::force).isInstanceOf(IOException.class).hasMessage("injected force failure");
        assertThat(woken.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("waiter woken by the failure").isTrue();
        final String inDoubt = "refuse durable commit, commit in doubt after failed log force [table="
                + dir.getFileName() + ", seq=3, fromSeq=2, toSeq=3]";
        assertThat(waited.get()).isInstanceOf(CdbException.class).hasMessage(inDoubt)
                .hasCauseInstanceOf(IOException.class);
        assertThat(wal.forcedSeq()).isEqualTo(1L);
        assertThat(wal.awaitForced(1L, 0L)).isTrue();
        assertThatThrownBy(() -> wal.awaitForced(2L, 0L)).isInstanceOf(CdbException.class);
        wal.write(4L, false, null, rows(1, 40L));
        assertThatThrownBy(() -> wal.awaitForced(4L, 0L)).isInstanceOf(CdbException.class);
        assertThatThrownBy(wal::force).isInstanceOf(IOException.class)
                .hasMessage("refuse force of log after failed force [file=" + file + ", forcedSeq=1, writtenSeq=4]");
        assertThat(forces).as("no force after the failed one").hasValue(1);
        StoreFiles.recorder = null;
        wal.reset();
        assertThat(wal.forcedSeq()).isEqualTo(4L);
        assertThat(wal.awaitForced(4L, 0L)).isTrue();
    }

    /// A cut whose new header's force fails covers the records the checkpoint holds, and is the log's failed force:
    /// the disk may hold the old salt, so a later force and a later append are refused, not answered.
    @Test
    void aFailedHeaderForceAtACutRefusesLaterForcesAndAppends(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.write(1L, false, null, rows(1, 10L));
        final AtomicInteger forces = new AtomicInteger();
        StoreFiles.recorder = new Silent() {
            @Override
            public void forcing(final Path forced) throws IOException {
                // the cut's truncate is forced first, its header second
                if (forced.equals(file) && forces.incrementAndGet() == 2) {
                    throw new IOException("injected header force failure");
                }
            }
        };
        assertThatThrownBy(wal::reset).isInstanceOf(IOException.class).hasMessage("injected header force failure");
        StoreFiles.recorder = null;
        assertThat(wal.forcedSeq()).as("the checkpoint covers the record cut").isEqualTo(1L);
        wal.write(2L, false, null, rows(1, 20L));
        assertThatThrownBy(wal::force).isInstanceOf(IOException.class)
                .hasMessageStartingWith("refuse force of log after failed force");
        assertThatThrownBy(() -> wal.awaitForced(2L, 0L)).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> wal.append(3L, false, null, rows(1, 30L))).isInstanceOf(IOException.class)
                .hasMessageStartingWith("refuse append to log after failed force");
        assertThat(wal.forcedSeq()).isEqualTo(1L);
    }

    /// A force does not wait for a drain holding the log's monitor: it reads the last record's number without it.
    @Test
    void aForceDoesNotWaitForADrain(@TempDir final Path dir) throws Exception {
        final Wal wal = Wal.open(dir);
        wal.write(1L, false, null, rows(1, 10L));
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread drain = Thread.ofPlatform().name("wal-timed-test-drain").start(() -> wal.holdingAppends(() -> {
            holding.countDown();
            try {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(holding.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final AtomicReference<Throwable> failed = new AtomicReference<>();
            final Thread forcer = Thread.ofPlatform().name("wal-timed-test-forcer").start(() -> {
                try {
                    wal.force();
                } catch (final IOException | RuntimeException e) {
                    failed.set(e);
                }
            });
            assertThat(forcer.join(Duration.ofSeconds(WAIT_SECONDS))).as("force ended during the drain")
                    .isTrue();
            assertThat(failed.get()).isNull();
            assertThat(wal.forcedSeq()).isEqualTo(1L);
        } finally {
            release.countDown();
            drain.join();
        }
    }

    /// A wait that no force answers returns false once its time is up, not before; a waiting thread that is
    /// interrupted ends with [InterruptedException]; a force keeps the interrupt of its thread, cleared only while
    /// it forces (an interrupt would fail the force by closing its channel).
    @Test
    void aWaitEndsAtItsTimeoutOrInterrupt(@TempDir final Path dir) throws Exception {
        final Wal wal = Wal.open(dir);
        final long seq = wal.write(1L, false, null, rows(1, 10L));
        final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(50L);
        final long start = System.nanoTime();
        assertThat(wal.awaitForced(seq, timeoutNanos)).isFalse();
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(timeoutNanos);

        final AtomicReference<Throwable> waited = new AtomicReference<>();
        final CountDownLatch ended = new CountDownLatch(1);
        final Thread waiter = Thread.ofPlatform().name("wal-timed-test-waiter").start(() -> {
            try {
                waited.set(new AssertionError("returned [result=" + wal.awaitForced(seq, LONG_WAIT_NANOS) + ']'));
            } catch (final InterruptedException | RuntimeException e) {
                waited.set(e);
            } finally {
                ended.countDown();
            }
        });
        assertEventually(() -> waiter.getState() == Thread.State.TIMED_WAITING, "waiter parked");
        waiter.interrupt();
        assertThat(ended.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("waiter ended by its interrupt").isTrue();
        assertThat(waited.get()).isInstanceOf(InterruptedException.class);

        Thread.currentThread().interrupt();
        try {
            wal.force();
        } finally {
            assertThat(Thread.interrupted()).as("interrupt kept across the force").isTrue();
        }
        assertThat(wal.forcedSeq()).isEqualTo(seq);
    }

    /// A format 1 log, an 8-byte header and unsalted CRCs, reads and takes appends as it is; its next cut writes it as
    /// format 2 with a salt, and what is appended after reads back.
    @Test
    void aFormatOneLogReadsAndBecomesFormatTwoAtItsCut(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Path source = Files.createDirectory(dir.resolve("source"));
        final Wal written = Wal.open(source);
        written.append(1L, false, null, rows(2, 10L));
        written.append(2L, false, null, rows(3, 20L));
        Files.write(file, formatOne(Files.readAllBytes(source.resolve(Wal.FILE))));
        final long size = Files.size(file);

        final Wal wal = Wal.open(dir);
        final List<Wal.Entry> entries = wal.scan(0L, DEF);
        assertThat(entries).extracting(Wal.Entry::txn).containsExactly(1L, 2L);
        assertThat(entries.get(1).rows().longs[1]).startsWith(20L, 21L, 22L);
        assertThat(wal.bytes()).isEqualTo(size);
        wal.append(3L, false, null, rows(1, 30L));
        assertThat(version(file)).isEqualTo(FORMAT_1);
        assertThat(Wal.open(dir).scan(0L, DEF)).extracting(Wal.Entry::txn).containsExactly(1L, 2L, 3L);

        wal.reset();
        assertThat(Files.size(file)).isEqualTo(Wal.HEADER);
        assertThat(wal.bytes()).isEqualTo(Wal.HEADER);
        assertThat(version(file)).isEqualTo(2);
        wal.append(4L, false, null, rows(2, 40L));
        final List<Wal.Entry> after = Wal.open(dir).scan(3L, DEF);
        assertThat(after).extracting(Wal.Entry::txn).containsExactly(4L);
        assertThat(after.getFirst().rows().longs[1]).startsWith(40L, 41L);
    }

    /// After a cut, a power cut can leave a frame of the previous generation past the new frames, whole and with
    /// the CRC it had: the new salt fails it, so the scan cuts there rather than meeting a commit out of order.
    /// Each cut draws a new salt, and the CRC a frame carries is not its body's alone.
    @Test
    void aFrameOfThePreviousGenerationIsCutAtScan(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.append(1L, false, null, rows(2, 10L));
        wal.append(2L, false, null, rows(2, 20L));
        final byte[] previous = Files.readAllBytes(file);
        assertThat(Wal.open(dir).scan(0L, DEF)).as("the old frames are whole").extracting(Wal.Entry::txn)
                .containsExactly(1L, 2L);
        wal.reset();
        wal.append(3L, false, null, rows(2, 30L));
        final byte[] current = Files.readAllBytes(file);
        assertThat(Arrays.copyOfRange(current, FORMAT_1_HEADER, Wal.HEADER)).as("salt after the cut")
                .isNotEqualTo(Arrays.copyOfRange(previous, FORMAT_1_HEADER, Wal.HEADER));
        final ByteBuffer frame = ByteBuffer.wrap(current, Wal.HEADER, current.length - Wal.HEADER)
                .order(ByteOrder.LITTLE_ENDIAN);
        final int length = frame.getInt(Wal.HEADER);
        final CRC32C bodyAlone = new CRC32C();
        bodyAlone.update(current, Wal.HEADER + Wal.FRAME, length);
        assertThat(frame.getInt(Wal.HEADER + Integer.BYTES)).as("salted CRC").isNotEqualTo((int) bodyAlone.getValue());

        // the third commit's frame is as long as the first's, so the second's follows it whole
        final byte[] stale = Arrays.copyOf(current, previous.length);
        System.arraycopy(previous, current.length, stale, current.length, previous.length - current.length);
        Files.write(file, stale);
        final List<Wal.Entry> entries = Wal.open(dir).scan(2L, DEF);
        assertThat(entries).extracting(Wal.Entry::txn).containsExactly(3L);
        assertThat(entries.getFirst().rows().longs[1]).startsWith(30L, 31L);
        assertThat(Files.size(file)).isEqualTo(current.length);
    }

    /// A format 2 header cut short of its salt, as a crash during its write leaves it, is a log with no commit in it:
    /// created again, with a whole header.
    @Test
    void aFormatTwoHeaderCutShortIsCreatedAgain(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(Wal.FILE);
        Files.write(file, ByteBuffer.allocate(FORMAT_1_HEADER + 3).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC)
                .putInt(2).array());
        final Wal wal = Wal.open(dir);
        assertThat(Files.size(file)).isEqualTo(Wal.HEADER);
        assertThat(wal.scan(0L, DEF)).isEmpty();
        wal.append(1L, false, null, rows(1, 10L));
        assertThat(Wal.open(dir).scan(0L, DEF)).extracting(Wal.Entry::txn).containsExactly(1L);
    }

    /// `n` rows of [#DEF]: timestamps 0 to n - 1 and values `first`, `first + 1`, and so on.
    private static RowBlock rows(final int n, final long first) {
        final RowBlock rows = new RowBlock(DEF.columns(), n);
        rows.size = n;
        for (int r = 0; r < n; r++) {
            rows.longs[0][r] = r;
            rows.longs[1][r] = first + r;
        }
        return rows;
    }

    /// The format 1 image of the format 2 log `image`: its 8-byte header, and each frame's CRC of the body alone.
    private static byte[] formatOne(final byte[] image) {
        final ByteBuffer in = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer out = ByteBuffer.allocate(image.length - Wal.HEADER + FORMAT_1_HEADER)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC).putInt(FORMAT_1);
        int at = Wal.HEADER;
        while (at < image.length) {
            final int length = in.getInt(at);
            final CRC32C crc = new CRC32C();
            crc.update(image, at + Wal.FRAME, length);
            out.putInt(length).putInt((int) crc.getValue()).put(image, at + Wal.FRAME, length);
            at += Wal.FRAME + length;
        }
        return out.array();
    }

    private static int version(final Path file) throws IOException {
        return (int) (StoreFiles.readLong(file, 0L) >>> Integer.SIZE);
    }

    /// Installs a recorder that counts the forces of `file`.
    private static AtomicInteger countForces(final Path file) {
        final AtomicInteger forces = new AtomicInteger();
        StoreFiles.recorder = new Silent() {
            @Override
            public void forcing(final Path forced) {
                if (forced.equals(file)) {
                    forces.incrementAndGet();
                }
            }
        };
        return forces;
    }

    private static void assertEventually(final BooleanSupplier condition, final String what) {
        final long start = System.nanoTime();
        final long boundNanos = TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        long backoffNanos = TimeUnit.MICROSECONDS.toNanos(100L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - start - boundNanos > 0L) {
                fail("condition not reached [what=" + what + ", waitSeconds=" + WAIT_SECONDS + ']');
            }
            LockSupport.parkNanos(backoffNanos);
            backoffNanos = Math.min(backoffNanos * 2L, TimeUnit.MILLISECONDS.toNanos(10L));
        }
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
