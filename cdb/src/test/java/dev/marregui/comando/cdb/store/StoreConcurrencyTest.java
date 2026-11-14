package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static org.assertj.core.api.Assertions.assertThat;

/// Snapshot readers race the writer: every snapshot is one whole transaction, and a version folder lives exactly as
/// long as a snapshot or the committed state holds it.
class StoreConcurrencyTest {

    private static final int READERS = 4;
    private static final int COMMITS = 200;
    private static final int ROWS_PER_COMMIT = 2;
    private static final int DAYS = 5;

    /// Four readers open snapshots in a loop while the writer commits 200 times, each commit rewriting two partitions;
    /// every other snapshot is held across the next commit and checked again. Each snapshot holds exactly its
    /// transaction's rows, sorted, its folders present and readable; after every reader closes, the table folder holds
    /// only the folders `_txn` names.
    @Test
    void snapshotsStayWholeWhileTheWriterCommits(@TempDir final Path root) throws Exception {
        final long base = micros("2026-11-01T00:00:00Z");
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong snapshots = new AtomicLong();
        final AtomicLong behind = new AtomicLong();
        final AtomicBoolean stop = new AtomicBoolean();
        // counted down after each commit and replaced, so a reader can hold its snapshot across the next commit
        final AtomicReference<CountDownLatch> committed = new AtomicReference<>(new CountDownLatch(1));
        try (final Store store = Store.open(root)) {
            store.create(new TableDef("m", List.of(
                    new ColumnDef("n", CdbType.LONG),
                    new ColumnDef("s", CdbType.SYMBOL),
                    new ColumnDef("ts", CdbType.TIMESTAMP)), 2, PartitionBy.DAY, List.of(), 0L), false);
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch reading = new CountDownLatch(READERS);
            final CountDownLatch done = new CountDownLatch(READERS);
            final List<Thread> readers = new ArrayList<>(READERS);
            for (int i = 0; i < READERS; i++) {
                readers.add(Thread.ofVirtual().name("store-concurrency-test-reader-" + i).start(() -> {
                    try {
                        if (start.await(10L, TimeUnit.SECONDS)) {
                            boolean hold = false;
                            while (!stop.get()) {
                                final CountDownLatch nextCommit = committed.get();
                                try (final TableReader r = store.reader("m")) {
                                    check(r);
                                    if (hold && nextCommit.await(10L, TimeUnit.SECONDS)) {
                                        // the snapshot is now superseded: its folders must outlive the commit
                                        check(r);
                                    }
                                    snapshots.incrementAndGet();
                                    if (((DefaultReader) r).txn() < COMMITS) {
                                        behind.incrementAndGet();
                                    }
                                }
                                hold = !hold;
                                reading.countDown();
                            }
                        }
                    } catch (final Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                }));
            }
            start.countDown();
            // every reader has a snapshot before the first commit, so the race covers the whole run
            final boolean allReading = reading.await(10L, TimeUnit.SECONDS);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                for (int i = 1; i <= COMMITS; i++) {
                    w.newRow(base + (i % DAYS) * Retention.DAY_MICROS + i);
                    w.putLong(0, i);
                    w.putText(1, "s" + (i % 7));
                    w.endRow();
                    w.newRow(base + DAYS * Retention.DAY_MICROS + i);
                    w.putLong(0, -i);
                    w.putText(1, "t" + (i % 3));
                    w.endRow();
                    w.commit();
                    committed.getAndSet(new CountDownLatch(1)).countDown();
                }
            } finally {
                stop.set(true);
                committed.get().countDown();
            }
            final boolean joined = done.await(30L, TimeUnit.SECONDS);
            assertThat(allReading).isTrue();
            assertThat(joined).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(snapshots.get()).isPositive();
            assertThat(behind.get()).as("snapshots of an earlier transaction").isGreaterThanOrEqualTo(READERS);
            for (final Thread t : readers) {
                assertThat(t.join(Duration.ofSeconds(5L))).isTrue();
            }
            final Path table = root.resolve("m");
            final TableFiles.Txn txn = TableFiles.decodeTxn(StoreFiles.readText(table.resolve(TableFiles.TXN)), "_txn");
            assertThat(txn.number()).isEqualTo(COMMITS);
            final Set<String> named = new HashSet<>();
            for (final TableFiles.Entry entry : txn.partitions()) {
                named.add(entry.folder());
            }
            assertThat(folders(table)).containsExactlyInAnyOrderElementsOf(named);
        }
    }

    /// One snapshot: its row count is its transaction's, the sum of its partitions, timestamps sorted across them, and
    /// every partition's folder present and its columns readable.
    private static void check(final TableReader r) {
        final long txn = ((DefaultReader) r).txn();
        long sum = 0L;
        long last = Long.MIN_VALUE;
        for (final PartitionReader p : r.partitions()) {
            if (!Files.isDirectory(((Version) p).dir)) {
                throw new AssertionError("version folder of a held snapshot missing [dir=" + ((Version) p).dir + ']');
            }
            final int n = (int) p.rowCount();
            final long[] ts = new long[n];
            p.longs(2, 0L, n, ts);
            for (final long t : ts) {
                if (t < last) {
                    throw new AssertionError("timestamps out of order [txn=" + txn + ", ts=" + t + ", previous=" + last
                            + ']');
                }
                last = t;
            }
            final String[] s = new String[n];
            p.texts(1, 0L, n, s);
            for (final String value : s) {
                if (value == null) {
                    throw new AssertionError("symbol unresolved [txn=" + txn + ']');
                }
            }
            sum += n;
        }
        if (sum != r.rowCount() || sum != txn * ROWS_PER_COMMIT) {
            throw new AssertionError("snapshot not whole [txn=" + txn + ", rows=" + sum + ", rowCount=" + r.rowCount()
                    + ']');
        }
    }

    private static List<String> folders(final Path table) throws IOException {
        try (final Stream<Path> entries = Files.list(table)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
        }
    }
}
