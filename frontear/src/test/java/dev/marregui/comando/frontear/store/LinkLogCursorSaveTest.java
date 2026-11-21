package dev.marregui.comando.frontear.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// When the forcer saves the cursor, against a disk whose segment forces the test lets through one at a time: the
/// save waits while appends wait for their force, and runs once the log is idle or the last save is old enough.
class LinkLogCursorSaveTest {

    // how long a test waits for a force on the test machine's disk, or for the forcer's cursor save
    private static final long WAIT_SECONDS = 10;
    private static final LogOptions OPTIONS = new LogOptions(1L << 20, 1L << 30, Duration.ZERO);
    private static final byte[] RECORD = new byte[100];

    @TempDir
    Path dir;

    /// An ack that lands while appends keep the forcer busy: the round that could save it finds an append waiting
    /// for its force and lets the next round force it first, so no cursor save sits between the two; once the log is
    /// idle the cursor is saved.
    @Test
    void theCursorSaveWaitsForTheAppendsWaitingOnAForce() throws Exception {
        final GatedFiles files = new GatedFiles();
        final LinkLog log = LinkLog.open(files, dir, OPTIONS);
        try {
            // the segment exists before the gate, so every gated round forces records, not a fresh header
            assertThat(log.append(RECORD).get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            files.gate.set(true);
            final CompletableFuture<Long> r1 = log.append(RECORD);
            files.awaitForce();
            // round one forces r1; r2 waits for round two
            final CompletableFuture<Long> r2 = log.append(RECORD);
            files.letOneForce();
            assertThat(r1.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1L);
            files.awaitForce();
            // round two forces r2; the ack and r3 wait for round three, whose snapshot holds the cursor to save
            log.ack(1L);
            final CompletableFuture<Long> r3 = log.append(RECORD);
            files.letOneForce();
            assertThat(r2.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(2L);
            files.awaitForce();
            // round three forces r3 while r4 waits: its completion finds r4 pending
            final CompletableFuture<Long> r4 = log.append(RECORD);
            files.letOneForce();
            assertThat(r3.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(3L);
            files.awaitForce();
            // round four is in its force: round three ended without a cursor save
            assertThat(files.saves.get()).as("cursor saves before r4's force").isZero();
            files.gate.set(false);
            files.letOneForce();
            assertThat(r4.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(4L);
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(() -> files.saves.get() == 1);
        } finally {
            log.close();
        }
        try (LinkLog reopened = LinkLog.open(dir, OPTIONS)) {
            assertThat(reopened.cursor()).isEqualTo(2L);
        }
    }

    /// Appends that never let the forcer go idle still see the cursor saved, once the last save is
    /// `CURSOR_EVERY_NANOS` old, so a crash under load delivers again a bounded stretch of acknowledged records.
    @Test
    void appendsThatKeepTheForcerBusyDelayTheCursorSaveBoundedly() throws Exception {
        final GatedFiles files = new GatedFiles();
        final long opened = System.nanoTime();
        final LinkLog log = LinkLog.open(files, dir, OPTIONS);
        try {
            assertThat(log.append(RECORD).get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            files.gate.set(true);
            CompletableFuture<Long> waiting = log.append(RECORD);
            files.awaitForce();
            log.ack(0L);
            // every round finds the next append waiting on its completion, until the save is CURSOR_EVERY_NANOS old
            long next = 1L;
            while (files.saves.get() == 0) {
                assertThat(System.nanoTime() - opened).as("nanos without a cursor save under load")
                        .isLessThan(LinkLog.CURSOR_EVERY_NANOS + TimeUnit.SECONDS.toNanos(WAIT_SECONDS));
                final CompletableFuture<Long> after = log.append(RECORD);
                files.letOneForce();
                assertThat(waiting.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(next);
                files.awaitForce();
                waiting = after;
                next++;
            }
            assertThat(System.nanoTime() - opened).as("nanos to the first cursor save under load")
                    .isGreaterThanOrEqualTo(LinkLog.CURSOR_EVERY_NANOS);
            files.gate.set(false);
            files.letOneForce();
            assertThat(waiting.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(next);
        } finally {
            log.close();
        }
    }

    // the disk, whose segment forces wait for the test's permit while the gate is set; counts the cursor's moves
    private static final class GatedFiles implements LogFiles {

        private final AtomicBoolean gate = new AtomicBoolean();
        private final AtomicInteger saves = new AtomicInteger();
        private final Semaphore entered = new Semaphore(0);
        private final Semaphore permits = new Semaphore(0);

        // waits until a segment force waits at the gate
        void awaitForce() throws InterruptedException {
            assertThat(entered.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)).as("a segment force at the gate").isTrue();
        }

        // lets the force waiting at the gate run
        void letOneForce() {
            permits.release();
        }

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
            return gated(file, DiskFiles.INSTANCE.create(file));
        }

        @Override
        public Writer open(final Path file) throws IOException {
            return gated(file, DiskFiles.INSTANCE.open(file));
        }

        @Override
        public <T> T read(final Path file, final Body<T> body) throws IOException {
            return DiskFiles.INSTANCE.read(file, body);
        }

        @Override
        public Mapping map(final Path file) throws IOException {
            return DiskFiles.INSTANCE.map(file);
        }

        @Override
        public void delete(final Path file) throws IOException {
            DiskFiles.INSTANCE.delete(file);
        }

        @Override
        public void move(final Path from, final Path to) throws IOException {
            DiskFiles.INSTANCE.move(from, to);
            saves.incrementAndGet();
        }

        @Override
        public void forceFolder(final Path d) throws IOException {
            DiskFiles.INSTANCE.forceFolder(d);
        }

        private Writer gated(final Path file, final Writer w) {
            final boolean segment = file.getFileName().toString().endsWith(".log");
            return new Writer() {
                @Override
                public void write(final ByteBuffer src, final long position) throws IOException {
                    w.write(src, position);
                }

                @Override
                public void force() throws IOException {
                    if (segment && gate.get()) {
                        entered.release();
                        try {
                            if (!permits.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
                                throw new IOException("gated force not let through [waitSeconds=" + WAIT_SECONDS
                                        + ']');
                            }
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("gated force interrupted", e);
                        }
                    }
                    w.force();
                }

                @Override
                public void truncate(final long size) throws IOException {
                    w.truncate(size);
                }

                @Override
                public void close() throws IOException {
                    w.close();
                }
            };
        }
    }
}
