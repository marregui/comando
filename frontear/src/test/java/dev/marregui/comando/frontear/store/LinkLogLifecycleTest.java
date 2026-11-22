package dev.marregui.comando.frontear.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// The link log's lifecycle against a disk the test holds still: the appending thread writes no file, the forcer
/// writes the round and an append's future waits for the force that covers it, a full round refuses appends at once,
/// the last round is read from memory, a segment the cursor save deletes has its writer closed, the force runs on one
/// named platform thread the close ends, and a close whose wait runs out under a force in flight fails what is
/// pending and interrupts nothing.
class LinkLogLifecycleTest {

    // how long a test waits for a force on the test machine's disk, or for the forcer's cursor save and deletion
    private static final long WAIT_SECONDS = 10;
    // past the close's wait for its forcer, so a held force outlasts it
    private static final long HOLD_SECONDS = 30;
    private static final byte[] RECORD = new byte[100];
    // a 16-byte header and two frames of 108 bytes fill 232 of 256: the third record rolls, when it is stored whole
    // (distinct) and not as a delta of the one before
    private static final LogOptions ROLLING = new LogOptions(256, 1L << 20, Duration.ZERO);
    // as ROLLING, the round bounded at two frames
    private static final LogOptions TWO_FRAME_ROUND = new LogOptions(256, 1L << 20, Duration.ZERO,
            2L * (LinkLog.FRAME + RECORD.length));

    @TempDir
    Path dir;

    /// An append returns while the forcer is held inside the write of its frame: the write runs on the forcer, not
    /// on the appending thread, and the future completes only once that write and its force are done.
    @Test
    void anAppendReturnsWhileTheForcerWritesItsFrame() throws Exception {
        final HoldingFiles files = new HoldingFiles();
        final LinkLog log = LinkLog.open(files, dir, ROLLING);
        try {
            assertThat(log.append(RECORD).get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            files.holdWrite.set(true);
            final CompletableFuture<Long> appended = log.append(RECORD);
            assertThat(files.held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(files.writer.get()).as("the thread that wrote the frame")
                    .isEqualTo("frontear-log-force-" + dir.getFileName());
            assertThat(appended).as("the future while its write is held").isNotDone();
            files.release.countDown();
            assertThat(appended.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1L);
        } finally {
            log.close();
        }
    }

    /// An append's future completes only after the force that covers it: while the forcer is held in that force,
    /// with the frame written, the future is still open; once the force returns it completes, after the write and
    /// the force.
    @Test
    void anAppendsFutureCompletesOnlyAfterTheForceThatCoversIt() throws Exception {
        final HoldingFiles files = new HoldingFiles();
        final LinkLog log = LinkLog.open(files, dir, ROLLING);
        try {
            assertThat(log.append(RECORD).get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            files.events.clear();
            files.holdForce.set(true);
            final CompletableFuture<Long> appended = log.append(RECORD);
            appended.whenComplete((_, _) -> files.events.add("complete"));
            assertThat(files.held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(appended).as("the future while its force is held").isNotDone();
            files.release.countDown();
            assertThat(appended.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1L);
            assertThat(files.events).containsExactly("write", "force", "complete");
        } finally {
            log.close();
        }
    }

    /// While the forcer is held in a force, appends fill the round up to its bound, the usage read beside it; the
    /// next fails at once as over quota, with no stack trace and the bound named, and assigns no offset; once the force
    /// returns the round drains and appends go on.
    @Test
    void aFullRoundRefusesAppendsAtOnceUntilTheForcerCatchesUp() throws Exception {
        final HoldingFiles files = new HoldingFiles();
        final LinkLog log = LinkLog.open(files, dir, TWO_FRAME_ROUND);
        try {
            files.holdForce.set(true);
            final CompletableFuture<Long> first = log.append(RECORD);
            assertThat(files.held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final CompletableFuture<Long> second = log.append(RECORD);
            assertThat(log.roundBytes()).isEqualTo(TWO_FRAME_ROUND.maxRoundBytes());
            final CompletableFuture<Long> refused = log.append(RECORD);
            assertThat(refused).isCompletedExceptionally();
            assertThatThrownBy(refused::join).hasCauseInstanceOf(QuotaExceededException.class);
            assertThat(refused.exceptionNow().getStackTrace()).isEmpty();
            assertThat(log.tail()).isEqualTo(2L);
            files.release.countDown();
            assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            assertThat(second.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1L);
            assertThat(log.roundBytes()).isZero();
            assertThat(log.append(RECORD).get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(2L);
        } finally {
            log.close();
        }
    }

    /// The records of the last round forced are read from memory and older ones from their segment, the same bytes
    /// either way; the round holds a copy, so an array the caller reuses after its append changes nothing.
    @Test
    void theLastRoundIsReadFromMemoryAndOlderRecordsFromTheirSegment() throws Exception {
        final HoldingFiles files = new HoldingFiles();
        final LinkLog log = LinkLog.open(files, dir, ROLLING);
        try {
            final byte[] older = {1, 2, 3};
            final byte[] last = {4, 5};
            assertThat(log.append(older).get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            final CompletableFuture<Long> appended = log.append(last);
            last[0] = 9;
            assertThat(appended.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1L);
            final int reads = files.reads.get();
            final List<Record> fromMemory = log.read(1L, 10);
            assertThat(files.reads.get()).as("file reads for the last round").isEqualTo(reads);
            assertThat(fromMemory).extracting(Record::offset).containsExactly(1L);
            assertThat(fromMemory.getFirst().bytes()).containsExactly(4, 5);
            final List<Record> both = log.read(0L, 10);
            assertThat(files.reads.get()).as("file reads for both rounds").isEqualTo(reads + 1);
            assertThat(both).extracting(Record::offset).containsExactly(0L, 1L);
            assertThat(both.getFirst().bytes()).containsExactly(1, 2, 3);
            assertThat(both.getLast().bytes()).containsExactly(4, 5);
        } finally {
            log.close();
        }
    }

    /// A roll that lands between a flush's completion step and its cursor save: the save deletes the sealed segment,
    /// and closes its writer, so after the log's close no writer is left open. The rolled segment's file waits for the
    /// forcer, held in the save, to write its first frame.
    @Test
    void aSegmentDeletedByTheCursorSaveHasItsWriterClosed() throws Exception {
        final HoldingFiles files = new HoldingFiles();
        final LinkLog log = LinkLog.open(files, dir, ROLLING);
        try {
            final CompletableFuture<Long> first = log.append(distinct(1));
            final CompletableFuture<Long> second = log.append(distinct(2));
            assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            assertThat(second.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1L);
            files.holdMove.set(true);
            log.ack(1L);
            // the forcer is now in the cursor save, its completion step done while segment 0 was still active
            assertThat(files.held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final CompletableFuture<Long> third = log.append(distinct(3));
            assertThat(files.open.get()).as("writers open while the rolled segment waits for the forcer").isEqualTo(1);
            files.release.countDown();
            assertThat(third.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(2L);
            // segment 0 is gone from the log: only segment 2, its header and one frame, is retained
            assertThat(log.bytes()).isEqualTo(LinkLog.HEADER + LinkLog.FRAME + RECORD.length);
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(() -> files.open.get() == 1);
        } finally {
            log.close();
        }
        assertThat(files.open.get()).as("writers still open after close").isZero();
    }

    /// The force runs on one platform daemon thread named for the log's folder, alive while the log is open and
    /// gone once it is closed.
    @Test
    void theForceRunsOnANamedPlatformThreadTheCloseEnds() throws Exception {
        final LinkLog log = LinkLog.open(new HoldingFiles(), dir, ROLLING);
        try {
            assertThat(log.append(RECORD).get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            final List<Thread> forcers = forcers(dir);
            assertThat(forcers).hasSize(1);
            assertThat(forcers.getFirst().isVirtual()).isFalse();
            assertThat(forcers.getFirst().isDaemon()).isTrue();
        } finally {
            log.close();
        }
        assertThat(forcers(dir)).isEmpty();
    }

    /// A force in flight past the close's wait: the close returns, the append it covered fails in doubt, the forcing
    /// thread is not interrupted (an interrupt would close the segment's channel), and once the force returns the
    /// forcer closes the files and ends.
    @Test
    void aCloseUnderAForceInFlightFailsWhatIsPendingAndInterruptsNothing() throws Exception {
        final HoldingFiles files = new HoldingFiles();
        final LinkLog log = LinkLog.open(files, dir, ROLLING);
        final CompletableFuture<Long> pending;
        try {
            assertThat(log.append(RECORD).get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            files.holdForce.set(true);
            pending = log.append(RECORD);
            assertThat(files.held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            log.close();
        }
        assertThat(pending).isCompletedExceptionally();
        assertThat(forcers(dir)).as("the forcer, its force still held").hasSize(1);
        files.release.countDown();
        await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(() -> forcers(dir).isEmpty());
        assertThat(files.interrupted).as("the forcing thread was interrupted").isFalse();
        assertThat(files.open.get()).as("writers still open after the forcer ended").isZero();
    }

    /// The live platform threads that force the log in `folder`.
    private static List<Thread> forcers(final Path folder) {
        final String name = "frontear-log-force-" + folder.getFileName();
        final List<Thread> found = new ArrayList<>();
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(name) && t.isAlive()) {
                found.add(t);
            }
        }
        return found;
    }

    /// A record of RECORD's size, every byte `n`: two records of different `n` share no byte, so the log stores
    /// each whole.
    private static byte[] distinct(final int n) {
        final byte[] record = new byte[RECORD.length];
        Arrays.fill(record, (byte) n);
        return record;
    }

    /// The disk, counting the writers open and the file reads, recording each write and finished force in order and
    /// the thread of the last write, and holding the first cursor move, segment force or segment write after its flag
    /// is set until the test releases it; it records whether the held thread was interrupted.
    private static final class HoldingFiles implements LogFiles {

        private final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> writer = new AtomicReference<>();
        private final AtomicBoolean holdMove = new AtomicBoolean();
        private final AtomicBoolean holdForce = new AtomicBoolean();
        private final AtomicBoolean holdWrite = new AtomicBoolean();
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void createDirectories(final Path d) throws IOException {
            DiskFiles.INSTANCE.createDirectories(d);
        }

        @Override
        public List<Path> list(final Path d) throws IOException {
            return DiskFiles.INSTANCE.list(d);
        }

        @Override
        public Writer create(final Path file) throws IOException {
            return counted(DiskFiles.INSTANCE.create(file));
        }

        @Override
        public Writer open(final Path file) throws IOException {
            return counted(DiskFiles.INSTANCE.open(file));
        }

        @Override
        public <T> T read(final Path file, final Body<T> body) throws IOException {
            reads.incrementAndGet();
            return DiskFiles.INSTANCE.read(file, body);
        }

        @Override
        public Mapping map(final Path file) throws IOException {
            reads.incrementAndGet();
            return DiskFiles.INSTANCE.map(file);
        }

        @Override
        public void delete(final Path file) throws IOException {
            DiskFiles.INSTANCE.delete(file);
        }

        @Override
        public void move(final Path from, final Path to) throws IOException {
            hold(holdMove);
            DiskFiles.INSTANCE.move(from, to);
        }

        @Override
        public void forceFolder(final Path d) throws IOException {
            DiskFiles.INSTANCE.forceFolder(d);
        }

        // holds the caller once when its flag is set, until the release or HOLD_SECONDS
        private void hold(final AtomicBoolean flag) throws IOException {
            if (!flag.compareAndSet(true, false)) {
                return;
            }
            held.countDown();
            try {
                if (!release.await(HOLD_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException("held call not released [waitSeconds=" + HOLD_SECONDS + ']');
                }
            } catch (final InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IOException("held call interrupted", e);
            }
        }

        private Writer counted(final Writer w) {
            open.incrementAndGet();
            final AtomicBoolean closed = new AtomicBoolean();
            return new Writer() {
                @Override
                public void write(final ByteBuffer src, final long position) throws IOException {
                    w.write(src, position);
                    writer.set(Thread.currentThread().getName());
                    events.add("write");
                    // held after the bytes are written, so the frame is in the file while the forcer waits
                    hold(holdWrite);
                }

                @Override
                public void force() throws IOException {
                    hold(holdForce);
                    w.force();
                    events.add("force");
                }

                @Override
                public void truncate(final long size) throws IOException {
                    w.truncate(size);
                }

                @Override
                public void close() throws IOException {
                    if (closed.compareAndSet(false, true)) {
                        open.decrementAndGet();
                    }
                    w.close();
                }
            };
        }
    }
}
