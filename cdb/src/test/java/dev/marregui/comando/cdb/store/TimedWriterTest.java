package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A writer of a store opened with a timed log force answers a logged commit before its record is forced, and its
/// ticket waits for that force; an appender waits on the tickets of every table it flushed; a store that forces each
/// commit answers after the force with tickets that are already done. The forces of the logs are counted by a
/// [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and each test
/// resets it.
class TimedWriterTest {

    private static final TableDef T = new TableDef("t", List.of(
            new ColumnDef("n", CdbType.LONG),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T10:00:00Z");
    // longer than any test here: only a durable waiter, a checkpoint or the store's close forces a log
    private static final StoreOptions TIMED = new StoreOptions(TimeUnit.MINUTES.toMillis(10L));
    // the bound of every wait here: a wait that has not answered by then is a hang
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long WAIT_MILLIS = TimeUnit.NANOSECONDS.toMillis(WAIT_NANOS);
    // a durable wait that a force held back by the test cannot meet; short, as each such wait runs to its end
    private static final long SHORT_WAIT_MILLIS = 50L;
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WRITER_WAIT_MILLIS = 1_000L;

    private final LogForces forces = new LogForces();

    /// Every test counts the forces of the logs from an empty count.
    @BeforeEach
    void installRecorder() {
        StoreFiles.recorder = forces;
    }

    /// The recorder a test installs is gone before the next.
    @AfterEach
    void removeRecorder() {
        StoreFiles.recorder = null;
    }

    /// In a timed store a logged commit is answered and visible to a reader with its record not forced; its ticket asks
    /// for the force at once, far ahead of the interval, and returns once the force held.
    @Test
    void aCommitIsAnsweredAndVisibleBeforeItsForce(@TempDir final Path root) throws Exception {
        try (final Store store = open(root)) {
            store.create(T, false);
            final int before = forces.of("t");
            final ForceTicket ticket;
            try (final TableWriter w = store.writer("t", WRITER_WAIT_MILLIS)) {
                row(w, 1L);
                w.commit();
                assertThat(forces.of("t")).as("log forces at the answer").isEqualTo(before);
                ticket = w.ticket();
            }
            assertThat(ticket).isNotSameAs(ForceTicket.DONE);
            try (final TableReader r = store.reader("t")) {
                assertThat(r.rowCount()).isEqualTo(1L);
            }
            assertThat(forces.of("t")).as("log forces after the read").isEqualTo(before);
            assertThat(ticket.await(WAIT_NANOS)).isTrue();
            assertThat(forces.of("t")).as("log forces after the wait").isGreaterThan(before);
            // a covered record answers again without another force
            final int after = forces.of("t");
            assertThat(ticket.await(0L)).isTrue();
            assertThat(forces.of("t")).isEqualTo(after);
        }
    }

    /// A direct commit's checkpoint makes it durable, and the logged commits it applied before it: its ticket is done,
    /// where the logged commit's before it was not.
    @Test
    void aDirectCommitsTicketIsDone(@TempDir final Path root) {
        try (final Store store = open(root)) {
            store.create(T, false);
            try (final TableWriter w = store.writer("t", WRITER_WAIT_MILLIS)) {
                row(w, 1L);
                w.commit();
                assertThat(w.ticket()).isNotSameAs(ForceTicket.DONE);
                store.directRows = 1;
                row(w, 2L);
                w.commit();
                assertThat(w.ticket()).isSameAs(ForceTicket.DONE);
            }
        }
    }

    /// A direct commit whose checkpoint's folder force failed after the rename of `_txn` is answered but in doubt:
    /// its ticket throws at once, and the table refuses later commits.
    @Test
    void anInDoubtCheckpointFailsItsTicket(@TempDir final Path root) {
        try (final Store store = open(root)) {
            store.create(T, false);
            try (final TableWriter w = store.writer("t", WRITER_WAIT_MILLIS)) {
                store.directRows = 1;
                forces.failTxnFolder.set(true);
                row(w, 1L);
                w.commit();
                assertThat(forces.failedFolders.get()).as("folder forces refused").isEqualTo(1);
                final ForceTicket ticket = w.ticket();
                assertThat(ticket).isNotSameAs(ForceTicket.DONE);
                assertThatThrownBy(() -> ticket.await(0L)).isInstanceOf(CdbException.class);
                row(w, 2L);
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            }
        }
    }

    /// An appender that flushed a logged commit and then a direct commit left in doubt waits on both: its durable wait
    /// throws, where dropping the logged commit's ticket for the direct one's would answer true.
    @Test
    void anAppenderKeepsATicketAnInDoubtCommitDoesNotCover(@TempDir final Path root) {
        try (final Store store = open(root);
             CdbAppender appender = store.appender()) {
            appender.table("t").longColumn("n", 1L).at(TS);
            appender.flush();
            store.directRows = 1;
            forces.failTxnFolder.set(true);
            appender.table("t").longColumn("n", 2L).at(TS + 1L);
            appender.flush();
            assertThat(forces.failedFolders.get()).as("folder forces refused").isEqualTo(1);
            assertThatThrownBy(() -> appender.awaitDurable(WAIT_MILLIS)).isInstanceOf(CdbException.class);
        }
    }

    /// A direct commit that held cuts the log, which covers the logged commit before it: the appender keeps that
    /// commit's ticket and its wait answers at once, with no force of the log.
    @Test
    void aCheckpointThatHeldSettlesTheKeptTicket(@TempDir final Path root) throws Exception {
        try (final Store store = open(root);
             CdbAppender appender = store.appender()) {
            appender.table("t").longColumn("n", 1L).at(TS);
            appender.flush();
            store.directRows = 1;
            appender.table("t").longColumn("n", 2L).at(TS + 1L);
            appender.flush();
            final int before = forces.of("t");
            assertThat(appender.awaitDurable(0L)).isTrue();
            assertThat(forces.of("t")).as("log forces of the wait").isEqualTo(before);
        }
    }

    /// An appender over two tables flushes both unforced; its durable wait forces both logs and answers true, and a
    /// second wait with nothing flushed since answers at once.
    @Test
    void anAppenderWaitsForEveryTableItFlushed(@TempDir final Path root) throws Exception {
        try (final Store store = open(root);
             CdbAppender appender = store.appender()) {
            appender.table("a").longColumn("n", 1L).at(TS);
            appender.table("b").longColumn("n", 2L).at(TS);
            appender.flush();
            final int a = forces.of("a");
            final int b = forces.of("b");
            appender.table("a").longColumn("n", 3L).at(TS + 1L);
            appender.table("b").longColumn("n", 4L).at(TS + 1L);
            appender.flush();
            assertThat(forces.of("a")).as("forces of a's log at the answer").isEqualTo(a);
            assertThat(forces.of("b")).as("forces of b's log at the answer").isEqualTo(b);
            assertThat(appender.awaitDurable(WAIT_MILLIS)).isTrue();
            assertThat(forces.of("a")).as("forces of a's log after the wait").isGreaterThan(a);
            assertThat(forces.of("b")).as("forces of b's log after the wait").isGreaterThan(b);
            final int all = forces.total();
            assertThat(appender.awaitDurable(0L)).isTrue();
            assertThat(forces.total()).isEqualTo(all);
        }
    }

    /// A durable wait that runs out of time while the force is held back answers false and keeps the ticket: the next
    /// short wait answers false again, and once the force goes on a wait answers true.
    @Test
    void aTimedOutWaitKeepsItsTicket(@TempDir final Path root) throws Exception {
        try (final Store store = open(root);
             CdbAppender appender = store.appender()) {
            appender.table("t").longColumn("n", 1L).at(TS);
            appender.flush();
            final int before = forces.of("t");
            forces.holding.set(true);
            try {
                assertThat(appender.awaitDurable(SHORT_WAIT_MILLIS)).isFalse();
                assertThat(appender.awaitDurable(SHORT_WAIT_MILLIS)).as("the kept ticket's second wait").isFalse();
            } finally {
                forces.holding.set(false);
                forces.release.countDown();
            }
            assertThat(appender.awaitDurable(WAIT_MILLIS)).isTrue();
            assertThat(forces.of("t")).as("log forces after the wait").isGreaterThan(before);
        }
    }

    /// A durable wait over two tables whose first table's force fails throws without waiting on the second, whose log
    /// stays unforced, and keeps the failed ticket: the next wait throws again.
    @Test
    void aFailedWaitKeepsTheRest(@TempDir final Path root) {
        try (final Store store = open(root);
             CdbAppender appender = store.appender()) {
            appender.table("a").longColumn("n", 1L).at(TS);
            appender.table("b").longColumn("n", 2L).at(TS);
            appender.flush();
            final int b = forces.of("b");
            forces.failing.set(true);
            try {
                assertThatThrownBy(() -> appender.awaitDurable(WAIT_MILLIS)).isInstanceOf(CdbException.class)
                        .cause().isSameAs(forces.firstFailure.get());
                assertThat(forces.of("b")).as("forces of b's log").isEqualTo(b);
                assertThatThrownBy(() -> appender.awaitDurable(0L)).isInstanceOf(CdbException.class);
            } finally {
                forces.failing.set(false);
            }
        }
    }

    /// A durable wait after the appender's close covers the commits of the flush in the close.
    @Test
    void aWaitAfterCloseCoversTheClosingFlush(@TempDir final Path root) throws Exception {
        try (final Store store = open(root)) {
            // closed in the test itself, as the wait must follow the close: the finally only covers a failure before it
            final CdbAppender appender = store.appender();
            try {
                appender.table("t").longColumn("n", 1L).at(TS);
                final int before = forces.of("t");
                appender.close();
                assertThat(forces.of("t")).as("log forces at the close").isEqualTo(before);
                assertThat(appender.awaitDurable(WAIT_MILLIS)).isTrue();
                assertThat(forces.of("t")).as("log forces after the wait").isGreaterThan(before);
            } finally {
                appender.close();
            }
        }
    }

    /// A force of the log that fails leaves the answered commit in doubt: the appender's durable wait throws the
    /// failure of that force, and so does the next.
    @Test
    void aFailedForcePutsTheCommitInDoubt(@TempDir final Path root) {
        try (final Store store = open(root);
             CdbAppender appender = store.appender()) {
            appender.table("t").longColumn("n", 1L).at(TS);
            appender.flush();
            forces.failing.set(true);
            try {
                assertThatThrownBy(() -> appender.awaitDurable(WAIT_MILLIS)).isInstanceOf(CdbException.class)
                        .cause().isSameAs(forces.firstFailure.get());
                assertThat(forces.failed.get()).as("forces of the log refused").isPositive();
                assertThatThrownBy(() -> appender.awaitDurable(0L)).isInstanceOf(CdbException.class);
            } finally {
                forces.failing.set(false);
            }
        }
    }

    /// A ticket of a commit whose force failed throws that failure too, waited on from the writer, and again on every
    /// later wait.
    @Test
    void aFailedForceFailsTheWritersTicket(@TempDir final Path root) {
        try (final Store store = open(root)) {
            store.create(T, false);
            try (final TableWriter w = store.writer("t", WRITER_WAIT_MILLIS)) {
                row(w, 1L);
                w.commit();
                forces.failing.set(true);
                try {
                    assertThatThrownBy(() -> w.ticket().await(WAIT_NANOS)).isInstanceOf(CdbException.class)
                            .cause().isSameAs(forces.firstFailure.get());
                } finally {
                    forces.failing.set(false);
                }
                assertThatThrownBy(() -> w.ticket().await(0L)).isInstanceOf(CdbException.class);
            }
        }
    }

    /// A commit written unforced that the closed table refuses is cut out of the log: the store opened next replays
    /// nothing, so a commit its caller saw fail never becomes visible; the commit answered before the close stays.
    @Test
    void aRefusedUnforcedCommitIsNotReplayed(@TempDir final Path root) {
        try (final Store store = open(root)) {
            store.closeWaitMillis = 0L;
            store.create(T, false);
            final TableWriter w = store.writer("t", 0L);
            try {
                row(w, 1L);
                w.commit();
                // the close runs between the commit's check of the table and its log write
                store.beforeWalAppend = () -> {
                    store.beforeWalAppend = () -> {
                    };
                    assertThatThrownBy(store::close).isInstanceOf(CdbException.class);
                };
                row(w, 2L);
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            } finally {
                w.close();
            }
        }
        try (final Store reopened = Store.open(root);
             TableReader r = reopened.reader("t")) {
            assertThat(r.rowCount()).isEqualTo(1L);
        }
    }

    /// A store that forces each commit has no forcer: a commit is answered after its log's force and its ticket is
    /// done, and an appender's durable wait answers at once without a force.
    @Test
    void theForcePerCommitIsUnchanged(@TempDir final Path root) throws Exception {
        try (final Store store = Store.open(root, StoreOptions.DEFAULT);
             CdbAppender appender = store.appender()) {
            assertThat(store.forcer()).isNull();
            store.create(T, false);
            final int before = forces.of("t");
            try (final TableWriter w = store.writer("t", WRITER_WAIT_MILLIS)) {
                row(w, 1L);
                w.commit();
                assertThat(forces.of("t")).as("log forces at the answer").isGreaterThan(before);
                assertThat(w.ticket()).isSameAs(ForceTicket.DONE);
            }
            appender.table("t").longColumn("n", 2L).at(TS + 2L);
            appender.flush();
            final int all = forces.total();
            assertThat(appender.awaitDurable(0L)).isTrue();
            assertThat(forces.total()).isEqualTo(all);
        }
    }

    // a timed store, its forcer checked: the tests above would otherwise pass on the force per commit
    private static Store open(final Path root) {
        final Store store = Store.open(root, TIMED);
        try {
            assertThat(store.forcer()).as("the forcer of a timed store").isNotNull();
        } catch (final AssertionError e) {
            store.close();
            throw e;
        }
        return store;
    }

    private static void row(final TableWriter w, final long n) {
        w.newRow(TS + n);
        w.putLong(0, n);
        w.endRow();
    }

    /// Counts the forces of each table's log, by its table folder's name; fails them while `failing` is set and holds
    /// them back while `holding` is set, until `release`; fails the folder force after the next rename of a `_txn`
    /// once `failTxnFolder` is set. Every other operation passes unseen.
    private static final class LogForces implements StoreFiles.Recorder {

        final AtomicBoolean failing = new AtomicBoolean();
        final AtomicInteger failed = new AtomicInteger();
        // the first refused force's failure: the cause a durable waiter's "commit in doubt" carries
        final AtomicReference<IOException> firstFailure = new AtomicReference<>();
        final AtomicBoolean holding = new AtomicBoolean();
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean failTxnFolder = new AtomicBoolean();
        final AtomicInteger failedFolders = new AtomicInteger();
        private final AtomicBoolean failNextFolder = new AtomicBoolean();
        private final Map<String, AtomicInteger> forced = new ConcurrentHashMap<>();

        /// The forces of table `table`'s log that held.
        int of(final String table) {
            final AtomicInteger n = forced.get(table);
            return n == null ? 0 : n.get();
        }

        /// The forces of every log that held.
        int total() {
            int n = 0;
            for (final AtomicInteger count : forced.values()) {
                n += count.get();
            }
            return n;
        }

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
            if (!file.getFileName().toString().equals(Wal.FILE)) {
                return;
            }
            if (failing.get()) {
                failed.incrementAndGet();
                final IOException failure = new IOException("refuse force of the log [file=" + file + ']');
                firstFailure.compareAndSet(null, failure);
                throw failure;
            }
            if (holding.get()) {
                hold(file);
            }
            forced.computeIfAbsent(file.getParent().getFileName().toString(), _ -> new AtomicInteger())
                    .incrementAndGet();
        }

        @Override
        public void folderForcing(final Path folder) throws IOException {
            if (failNextFolder.compareAndSet(true, false)) {
                failedFolders.incrementAndGet();
                throw new IOException("refuse force of the folder [folder=" + folder + ']');
            }
        }

        @Override
        public void moved(final Path from, final Path to) {
            if (to.getFileName().toString().equals(TableFiles.TXN) && failTxnFolder.compareAndSet(true, false)) {
                failNextFolder.set(true);
            }
        }

        @Override
        public void deleted(final Path path) {
        }

        // the forcing thread waits for the test's release, bounded: a test that never releases fails, not hangs
        private void hold(final Path file) throws IOException {
            try {
                if (!release.await(WAIT_NANOS, TimeUnit.NANOSECONDS)) {
                    throw new IOException("cannot force log, never released [file=" + file + ']');
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("cannot force log, interrupted while held [file=" + file + ']', e);
            }
        }
    }
}
