package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A small commit is durable once its record is forced in the table's log, and visible only then; a checkpoint names
/// the logged commits in `_txn` and empties the log; opening the store replays what the log holds.
///
/// - a commit is not visible while its record is forced, and is once `commit` returns
/// - a store that logs reads the same rows as one that checkpoints every commit, live, after a close and after a
///   reopen of its files copied while it was open
/// - a folder outlives its last reader only while the checkpointed `_txn` names it
/// - a checkpoint cuts the log back to its header
/// - a failed log force fails the commit, leaves the snapshot, refuses the table's commits until it reopens, and the
///   commit is not there after
class StoreWalTest {

    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    private static final long HOUR_MICROS = Retention.DAY_MICROS / 24L;
    // the log's header: magic and format version
    private static final long HEADER_BYTES = Wal.HEADER;
    // commits of the differential, a few checkpoints at three records each and the scheduled changes among them
    private static final int STEPS = 60;
    private static final String[] SENSORS = {"a", "b", "c", null};

    /// Right before a commit's record is appended and forced, a reader on another thread reads the rows before it;
    /// once `commit` returns, a reader reads them.
    @Test
    void aCommitIsVisibleOnlyOnceItsRecordIsForced(@TempDir final Path root) throws InterruptedException {
        try (final Store store = Store.open(root)) {
            store.create(table("m", List.of()), false);
            commit(store, "m", List.of(row(DAY1, "a")));
            final AtomicInteger seenInForce = new AtomicInteger(-1);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean joined = new AtomicBoolean();
            store.beforeWalAppend = () -> {
                final CountDownLatch read = new CountDownLatch(1);
                Thread.ofVirtual().name("cdb-wal-test-reader").start(() -> {
                    try (final TableReader reader = store.reader("m")) {
                        seenInForce.set(rows(reader).size());
                    } catch (final Throwable t) {
                        failure.set(t);
                    } finally {
                        read.countDown();
                    }
                });
                try {
                    joined.set(read.await(10L, TimeUnit.SECONDS));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            commit(store, "m", List.of(row(DAY1 + 1L, "b")));
            assertThat(joined).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(seenInForce.get()).isEqualTo(1);
            final AtomicInteger seenAfter = new AtomicInteger(-1);
            final CountDownLatch read = new CountDownLatch(1);
            Thread.ofVirtual().name("cdb-wal-test-reader").start(() -> {
                try (final TableReader reader = store.reader("m")) {
                    seenAfter.set(rows(reader).size());
                } finally {
                    read.countDown();
                }
            });
            assertThat(read.await(10L, TimeUnit.SECONDS)).isTrue();
            assertThat(seenAfter.get()).isEqualTo(2);
        }
    }

    /// Sixty seeded commits to a table without dedup keys and to one with them, each table's writer held open across
    /// them: in-order and out-of-order rows across days, symbols, NULLs, an added column, a TTL and a truncate. A
    /// store that logs and checkpoints every three records reads the same rows as a reference that checkpoints every
    /// commit and always rewrites: after each commit, after both close and open again, and when the logging store's
    /// files, copied while its writers were open after their last commit, are opened, which replays their log.
    /// Replay with `CDB_WAL_SEED=<seed>` in the environment of the Gradle run.
    @Test
    void aLoggingStoreReadsAsOneThatCheckpointsEveryCommit(@TempDir final Path root) throws IOException {
        final long seed = Long.getLong("cdb.wal.seed", System.nanoTime());
        System.out.println("aLoggingStoreReadsAsOneThatCheckpointsEveryCommit seed=" + seed);
        final Random random = new Random(seed);
        final List<String> tables = List.of("m", "k");
        final Path copy = root.resolve("copy");
        Store subject = logging(root.resolve("subject"));
        Store reference = checkpointing(root.resolve("reference"));
        try {
            subject.create(table("m", List.of()), false);
            reference.create(table("m", List.of()), false);
            subject.create(table("k", List.of("ts", "sensor")), false);
            reference.create(table("k", List.of("ts", "sensor")), false);
            final List<TableWriter> writers = new ArrayList<>();
            try {
                for (final String t : tables) {
                    writers.add(subject.writer(t, 1_000L));
                    writers.add(reference.writer(t, 1_000L));
                }
                long clock = DAY1;
                for (int step = 0; step < STEPS; step++) {
                    clock += random.nextInt(4) * HOUR_MICROS + 1L;
                    final List<Row> rows = randomRows(random, clock, 1 + random.nextInt(6));
                    for (int i = 0; i < writers.size(); i++) {
                        final TableWriter w = writers.get(i);
                        if (step == 15) {
                            w.addColumn(new ColumnDef("extra", CdbType.LONG));
                        } else if (step == 30) {
                            w.setTtl(2L * Retention.DAY_MICROS);
                        } else if (step == 40) {
                            w.truncate();
                        }
                        write(w, rows);
                        w.commit();
                        if (i % 2 == 1) {
                            assertSameRows(subject, reference, tables.get(i / 2), "seed=" + seed + " step=" + step);
                        }
                    }
                }
                // two last commits left in the log, so the copy has records to replay
                subject.checkpointRecords = Integer.MAX_VALUE;
                for (int last = 0; last < 2; last++) {
                    clock += HOUR_MICROS;
                    final List<Row> rows = randomRows(random, clock, 3);
                    for (final TableWriter w : writers) {
                        write(w, rows);
                        w.commit();
                    }
                }
                for (final String t : tables) {
                    assertThat(Files.size(root.resolve("subject").resolve(t).resolve(Wal.FILE)))
                            .as("seed=%d", seed).isGreaterThan(HEADER_BYTES);
                }
                // every table's apply lock held for the copy, so no apply replaces a folder under it: a crash's files
                // are one instant's
                final List<TableState> held = new ArrayList<>();
                try {
                    for (final TableState state : subject.states()) {
                        state.applyLock.lock();
                        held.add(state);
                    }
                    copyTree(root.resolve("subject"), copy);
                } finally {
                    for (final TableState state : held) {
                        state.applyLock.unlock();
                    }
                }
            } finally {
                for (final TableWriter w : writers) {
                    w.close();
                }
            }
            // a writer's close keeps the log; the store's close checkpoints each table
            for (final String t : tables) {
                assertThat(Files.size(root.resolve("subject").resolve(t).resolve(Wal.FILE)))
                        .as("seed=%d", seed).isGreaterThan(HEADER_BYTES);
            }
            subject.close();
            reference.close();
            for (final String t : tables) {
                assertThat(Files.size(root.resolve("subject").resolve(t).resolve(Wal.FILE)))
                        .as("seed=%d", seed).isEqualTo(HEADER_BYTES);
            }
            subject = logging(root.resolve("subject"));
            reference = checkpointing(root.resolve("reference"));
            for (final String t : tables) {
                assertSameRows(subject, reference, t, "seed=" + seed + " reopened");
            }
            subject.close();
            subject = logging(copy);
            for (final String t : tables) {
                assertSameRows(subject, reference, t, "seed=" + seed + " copied open");
                assertThat(Files.size(copy.resolve(t).resolve(Wal.FILE))).as("seed=%d", seed)
                        .isEqualTo(HEADER_BYTES);
            }
        } finally {
            // Store.close is idempotent: the stores closed above close again as a no-op
            final Store lastReference = reference;
            try (lastReference) {
                subject.close();
            }
        }
    }

    /// A folder no reader holds stays while the checkpointed `_txn` names it, though the published snapshot does not;
    /// the next checkpoint deletes it, and keeps one a reader holds until that reader closes. Each earlier row rewrites
    /// the partition as a new folder; a checkpoint every two records.
    @Test
    void aFolderGoesOnlyWhenUnheldAndNotCheckpointed(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.applyOnCommit = true;
            store.checkpointRecords = 2;
            store.create(table("m", List.of()), false);
            final Path dir = root.resolve("m");
            try (final TableWriter w = store.writer("m", 1_000L)) {
                write(w, List.of(row(DAY1 + 10L, "a")));
                w.commit();
                write(w, List.of(row(DAY1 + 5L, "b")));
                w.commit();
                assertThat(folders(dir)).containsExactly(folder(2L));
                write(w, List.of(row(DAY1 + 2L, "c")));
                w.commit();
                assertThat(folders(dir)).containsExactlyInAnyOrder(folder(2L), folder(3L));
                try (final TableReader held = store.reader("m")) {
                    write(w, List.of(row(DAY1 + 1L, "d")));
                    w.commit();
                    assertThat(folders(dir)).containsExactlyInAnyOrder(folder(3L), folder(4L));
                    assertThat(rows(held)).hasSize(3);
                }
                assertThat(folders(dir)).containsExactly(folder(4L));
            }
        }
    }

    /// The log grows by a record per commit until a checkpoint cuts it back to its header: at three records, and by
    /// `Store.checkpoint` after one that failed kept the log; a writer's close keeps it.
    @Test
    void aCheckpointCutsTheLogToItsHeader(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.applyOnCommit = true;
            store.checkpointRecords = 3;
            store.create(table("m", List.of()), false);
            final Path wal = root.resolve("m").resolve(Wal.FILE);
            assertThat(Files.size(wal)).isEqualTo(HEADER_BYTES);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                write(w, List.of(row(DAY1, "a")));
                w.commit();
                final long one = Files.size(wal);
                write(w, List.of(row(DAY1 + 1L, "a")));
                w.commit();
                assertThat(Files.size(wal)).isGreaterThan(one).isGreaterThan(HEADER_BYTES);
                write(w, List.of(row(DAY1 + 2L, "a")));
                w.commit();
                assertThat(Files.size(wal)).isEqualTo(HEADER_BYTES);
                write(w, List.of(row(DAY1 + 3L, "a")));
                w.commit();
                assertThat(Files.size(wal)).isGreaterThan(HEADER_BYTES);
            }
            final long four = Files.size(wal);
            assertThat(four).isGreaterThan(HEADER_BYTES);
            final AtomicBoolean armed = new AtomicBoolean(true);
            StoreFiles.recorder = new Quiet() {
                @Override
                public void forcing(final Path file) throws IOException {
                    if (file.getFileName().toString().endsWith(".d") && armed.getAndSet(false)) {
                        throw new IOException("injected force failure");
                    }
                }
            };
            try {
                assertThatThrownBy(() -> store.checkpoint("m")).isInstanceOf(CdbException.class);
            } finally {
                StoreFiles.recorder = null;
            }
            assertThat(armed).isFalse();
            assertThat(Files.size(wal)).isEqualTo(four);
            store.checkpoint("m");
            assertThat(Files.size(wal)).isEqualTo(HEADER_BYTES);
            assertThat(read(store, "m")).hasSize(4);
        }
    }

    /// Small commits each through a writer of its own, opened and closed around the commit as a group commit or a
    /// statement does, leave a record each in the log and `_txn` as the table's creation wrote it: no commit pays a
    /// checkpoint. The store's close checkpoints them.
    @Test
    void aWriterPerCommitLeavesEveryCommitInTheLog(@TempDir final Path root) throws IOException {
        final Path dir = root.resolve("m");
        final Path copy = root.resolve("copy");
        try (final Store store = Store.open(root)) {
            store.create(table("m", List.of()), false);
            final byte[] created = Files.readAllBytes(dir.resolve(TableFiles.TXN));
            for (int c = 0; c < 5; c++) {
                commit(store, "m", List.of(row(DAY1 + c, "a")));
            }
            assertThat(Files.readAllBytes(dir.resolve(TableFiles.TXN))).isEqualTo(created);
            copyTree(dir, copy);
        }
        assertThat(Wal.open(copy).scan(0L, table("m", List.of()))).extracting(Wal.Entry::txn)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(Files.size(dir.resolve(Wal.FILE))).isEqualTo(HEADER_BYTES);
        try (final Store store = Store.open(root)) {
            assertThat(read(store, "m")).hasSize(5);
        }
    }

    /// A commit whose log force fails throws and publishes nothing; the table refuses every later commit, of that
    /// writer or the next, until the store opens again, when the logged commit before it is replayed and it is not
    /// there.
    @Test
    void aFailedLogForceRefusesTheTableUntilReopen(@TempDir final Path root) throws IOException {
        final List<String> before;
        try (final Store store = Store.open(root)) {
            store.create(table("m", List.of()), false);
            final AtomicBoolean armed = new AtomicBoolean(true);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                write(w, List.of(row(DAY1, "a")));
                w.commit();
                before = read(store, "m");
                StoreFiles.recorder = new Quiet() {
                    @Override
                    public void forcing(final Path file) throws IOException {
                        if (file.getFileName().toString().equals(Wal.FILE) && armed.getAndSet(false)) {
                            throw new IOException("injected force failure");
                        }
                    }
                };
                write(w, List.of(row(DAY1 + 1L, "b")));
                try {
                    assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
                } finally {
                    StoreFiles.recorder = null;
                }
                assertThat(armed).isFalse();
                assertThat(read(store, "m")).isEqualTo(before);
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            }
            assertThat(Files.size(root.resolve("m").resolve(Wal.FILE))).isGreaterThan(HEADER_BYTES);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                write(w, List.of(row(DAY1 + 2L, "c")));
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            }
            assertThat(read(store, "m")).isEqualTo(before);
        }
        try (final Store store = Store.open(root)) {
            assertThat(read(store, "m")).isEqualTo(before);
            commit(store, "m", List.of(row(DAY1 + 3L, "d")));
            assertThat(read(store, "m")).hasSize(2);
        }
    }

    /// An interrupt that closes the log's channel during its force fails the commit, and the log is cut back all the
    /// same: the cut runs with the interrupt cleared, so the commit does not come back when the store opens again.
    @Test
    void anInterruptDuringTheLogForceLeavesNoRecord(@TempDir final Path root) throws IOException {
        final List<String> before;
        try (final Store store = Store.open(root)) {
            store.create(table("m", List.of()), false);
            final AtomicBoolean armed = new AtomicBoolean(true);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                write(w, List.of(row(DAY1, "a")));
                w.commit();
                before = read(store, "m");
                StoreFiles.recorder = new Quiet() {
                    @Override
                    public void forcing(final Path file) throws IOException {
                        if (file.getFileName().toString().equals(Wal.FILE) && armed.getAndSet(false)) {
                            Thread.currentThread().interrupt();
                            throw new ClosedByInterruptException();
                        }
                    }
                };
                write(w, List.of(row(DAY1 + 1L, "b")));
                try {
                    assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
                } finally {
                    StoreFiles.recorder = null;
                    assertThat(Thread.interrupted()).isTrue();
                }
            }
        }
        try (final Store store = Store.open(root)) {
            assertThat(read(store, "m")).isEqualTo(before);
        }
    }

    /// A checkpoint whose force fails leaves the commit that ran it answered and in the log, and refuses the table's
    /// later commits until the store opens again, which replays the log: a retry could take a force for good after a
    /// failed one lost the data, and cut the log that holds it.
    @Test
    void aFailedCheckpointRefusesTheTableAndKeepsTheLog(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.applyOnCommit = true;
            store.checkpointRecords = 2;
            store.create(table("m", List.of()), false);
            final AtomicBoolean armed = new AtomicBoolean(true);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                write(w, List.of(row(DAY1, "a")));
                w.commit();
                StoreFiles.recorder = new Quiet() {
                    @Override
                    public void forcing(final Path file) throws IOException {
                        if (file.getFileName().toString().endsWith(".d") && armed.getAndSet(false)) {
                            throw new IOException("injected force failure");
                        }
                    }
                };
                write(w, List.of(row(DAY1 + 1L, "b")));
                try {
                    w.commit();
                } finally {
                    StoreFiles.recorder = null;
                }
                assertThat(armed).isFalse();
                assertThat(read(store, "m")).hasSize(2);
                write(w, List.of(row(DAY1 + 2L, "c")));
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            }
            assertThat(Files.size(root.resolve("m").resolve(Wal.FILE))).isGreaterThan(HEADER_BYTES);
        }
        try (final Store store = Store.open(root)) {
            assertThat(read(store, "m")).hasSize(2);
        }
    }

    /// A commit of fewer rows than `directRows` whose log record would still take `checkpointBytes` or more, counting
    /// three bytes per text char, is written as a checkpoint: its text is not copied twice more into a record.
    @Test
    void aCommitWhoseRecordWouldPassTheByteLimitIsCheckpointed(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.checkpointBytes = 1_000L;
            store.create(table("m", List.of()), false);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                write(w, List.of(new Row(DAY1, "a", 1.0, "x".repeat(400), 1L, true, 7L)));
                w.commit();
            }
            assertThat(Files.size(root.resolve("m").resolve(Wal.FILE))).isEqualTo(HEADER_BYTES);
            assertThat(TableFiles.decodeTxn(StoreFiles.readText(root.resolve("m").resolve(TableFiles.TXN)), "_txn")
                    .number()).isEqualTo(1L);
        }
    }

    /// A kill right after a direct commit moved `_txn` into place, before its checkpoint cut the log, leaves records
    /// at or before `_txn`'s transaction: the open cuts them, so the next logged commit follows `_txn` and a second
    /// kill opens with every commit.
    @Test
    void recordsBeforeADirectCommitDoNotGapTheLog(@TempDir final Path root) throws IOException {
        final Path image = root.resolve("image");
        final Path again = root.resolve("again");
        try (final Store store = Store.open(root.resolve("store"))) {
            store.create(table("m", List.of()), false);
            commit(store, "m", List.of(row(DAY1, "a")));
            commit(store, "m", List.of(row(DAY1 + 1L, "b")));
            final AtomicBoolean armed = new AtomicBoolean(true);
            StoreFiles.recorder = new Quiet() {
                @Override
                public void moved(final Path from, final Path to) {
                    if (to.getFileName().toString().equals(TableFiles.TXN) && armed.getAndSet(false)) {
                        try {
                            copyTree(root.resolve("store"), image);
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                }
            };
            store.directRows = 1;
            try {
                commit(store, "m", List.of(row(DAY1 + 2L, "c")));
            } finally {
                StoreFiles.recorder = null;
            }
            assertThat(armed).isFalse();
        }
        try (final Store store = Store.open(image)) {
            assertThat(read(store, "m")).hasSize(3);
            commit(store, "m", List.of(row(DAY1 + 3L, "d")));
            copyTree(image, again);
        }
        try (final Store store = Store.open(again)) {
            assertThat(read(store, "m")).hasSize(4);
        }
    }

    /// A direct commit whose checkpoint fails throws and refuses the table's later commits until the store opens
    /// again: a retry could take a failed force for good.
    @Test
    void aFailedDirectCheckpointRefusesTheTable(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.directRows = 1;
            store.create(table("m", List.of()), false);
            final AtomicBoolean armed = new AtomicBoolean(true);
            try (final TableWriter w = store.writer("m", 1_000L)) {
                StoreFiles.recorder = new Quiet() {
                    @Override
                    public void forcing(final Path file) throws IOException {
                        if (file.getFileName().toString().endsWith(".d") && armed.getAndSet(false)) {
                            throw new IOException("injected force failure");
                        }
                    }
                };
                write(w, List.of(row(DAY1, "a")));
                try {
                    assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
                } finally {
                    StoreFiles.recorder = null;
                }
                assertThat(armed).isFalse();
                write(w, List.of(row(DAY1 + 1L, "b")));
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class)
                        .hasMessageStartingWith("refuse commit to failed table");
            }
        }
    }

    /// A symbol dictionary's first keys, appended to a partition that already holds rows, create its file at the
    /// checkpoint: the table folder is forced before `_txn` names them, so a power cut that keeps `_txn` keeps the
    /// file, and the rows read their values, not NULL.
    @Test
    void aDictionaryCreatedByACheckpointSurvivesAPowerCut(@TempDir final Path root) throws IOException {
        final Path store = root.resolve("store");
        Files.createDirectories(store);
        final DiskModel model = new DiskModel(store);
        StoreFiles.recorder = model;
        try (final Store s = Store.open(store)) {
            s.create(table("m", List.of()), false);
            commit(s, "m", List.of(row(DAY1, null)));
            s.checkpoint("m");
            commit(s, "m", List.of(row(DAY1 + 1L, "a")));
            s.checkpoint("m");
        } finally {
            StoreFiles.recorder = null;
        }
        // the last checkpoint's move of _txn: cut right after it, before the folder force that follows
        int moved = -1;
        for (int k = 0; k < model.operations(); k++) {
            if (model.describe(k).startsWith("moved ") && model.describe(k).endsWith(TableFiles.TXN)) {
                moved = k;
            }
        }
        assertThat(moved).isNotNegative();
        final DiskModel cut = model.at(moved + 1);
        for (int seed = 0; seed < 64; seed++) {
            final Path image = root.resolve("image-" + seed);
            DiskModel.write(image, cut.adversarialImage(0x9E3779B97F4A7C15L * (seed + 1)));
            try (final Store s = Store.open(image)) {
                final List<String> rows = read(s, "m");
                if (rows.size() == 2) {
                    assertThat(rows).as("seed " + seed).anyMatch(r -> r.startsWith("\"a\"|"));
                }
            }
        }
    }

    /// A thread interrupted right before its commit's log append commits all the same, and is still interrupted after:
    /// the log's force does not run with the interrupt set, which would close its channel.
    @Test
    void anInterruptBeforeTheLogAppendNeitherFailsTheCommitNorIsLost(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m", List.of()), false);
            store.beforeWalAppend = () -> Thread.currentThread().interrupt();
            final boolean interrupted;
            try (final TableWriter w = store.writer("m", 1_000L)) {
                write(w, List.of(row(DAY1, "a")));
                try {
                    w.commit();
                } finally {
                    interrupted = Thread.interrupted();
                }
                assertThat(read(store, "m")).hasSize(1);
            }
            assertThat(interrupted).isTrue();
        }
        try (final Store store = Store.open(root)) {
            assertThat(read(store, "m")).hasSize(1);
        }
    }

    /// Creating a table forces the root folder, so the table's entry survives a power cut.
    @Test
    void creatingATableForcesTheRoot(@TempDir final Path root) {
        final List<Path> forced = new ArrayList<>();
        try (final Store store = Store.open(root)) {
            StoreFiles.recorder = new Quiet() {
                @Override
                public void folderForcing(final Path folder) {
                    forced.add(folder);
                }
            };
            try {
                store.create(table("m", List.of()), false);
            } finally {
                StoreFiles.recorder = null;
            }
        }
        assertThat(forced).contains(root);
    }

    /// A checkpoint whose `_txn` names a new partition folder forces the table folder before it moves `_txn` into
    /// place: entries of one folder reach the device in any order unless forced, so `_txn` could otherwise survive a
    /// power cut that loses the folder it names.
    @Test
    void aCheckpointForcesTheNewFoldersEntriesBeforeItsTxn(@TempDir final Path root) {
        final List<String> events = new ArrayList<>();
        final Path dir = root.resolve("m");
        try (final Store store = Store.open(root)) {
            store.create(table("m", List.of()), false);
            commit(store, "m", List.of(row(DAY1, "a")));
            StoreFiles.recorder = new Quiet() {
                @Override
                public void folderForcing(final Path folder) {
                    events.add("force " + folder);
                }

                @Override
                public void moved(final Path from, final Path to) {
                    events.add("move " + to);
                }
            };
            try {
                store.checkpoint("m");
            } finally {
                StoreFiles.recorder = null;
            }
        }
        assertThat(events).containsSubsequence("force " + dir, "move " + dir.resolve(TableFiles.TXN));
    }

    private static Store logging(final Path root) {
        final Store store = Store.open(root);
        store.checkpointRecords = 3;
        return store;
    }

    private static Store checkpointing(final Path root) {
        final Store store = Store.open(root);
        store.directRows = 0;
        store.appendInPlace = false;
        return store;
    }

    private static TableDef table(final String name, final List<String> dedupKeys) {
        return new TableDef(name, List.of(
                new ColumnDef("sensor", CdbType.SYMBOL),
                new ColumnDef("value", CdbType.DOUBLE),
                new ColumnDef("note", CdbType.VARCHAR),
                new ColumnDef("n", CdbType.LONG),
                new ColumnDef("flag", CdbType.BOOLEAN),
                new ColumnDef("ts", CdbType.TIMESTAMP)), 5, PartitionBy.DAY, dedupKeys, 0L);
    }

    private static String folder(final long txn) {
        return TableFiles.folder(DAY1, PartitionBy.DAY, txn);
    }

    private static Row row(final long ts, final String sensor) {
        return new Row(ts, sensor, 1.0, "note", 1L, true, 7L);
    }

    /// `count` rows around `clock`: most after it, a quarter up to half a day before it, NULLs in every nullable
    /// column.
    private static List<Row> randomRows(final Random random, final long clock, final int count) {
        final List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final long ts = random.nextInt(4) == 0
                    ? Math.max(DAY1, clock - random.nextLong(12L * HOUR_MICROS))
                    : clock + random.nextInt(1_000);
            rows.add(new Row(ts, SENSORS[random.nextInt(SENSORS.length)],
                    random.nextInt(5) == 0 ? Double.NaN : random.nextInt(100) * 0.5,
                    random.nextInt(5) == 0 ? null : "n" + random.nextInt(50),
                    random.nextInt(5) == 0 ? Long.MIN_VALUE : random.nextInt(1_000),
                    random.nextBoolean(),
                    random.nextInt(5) == 0 ? Long.MIN_VALUE : random.nextInt(1_000)));
        }
        return rows;
    }

    private static void write(final TableWriter w, final List<Row> rows) {
        final int extra = w.def().columnIndex("extra");
        for (final Row r : rows) {
            w.newRow(r.ts);
            w.putText(0, r.sensor);
            w.putDouble(1, r.value);
            w.putText(2, r.note);
            w.putLong(3, r.n);
            w.putBoolean(4, r.flag);
            if (extra >= 0) {
                w.putLong(extra, r.extra);
            }
            w.endRow();
        }
    }

    private static void commit(final Store store, final String table, final List<Row> rows) {
        try (final TableWriter w = store.writer(table, 1_000L)) {
            write(w, rows);
            w.commit();
        }
    }

    private static List<String> read(final Store store, final String table) {
        try (final TableReader reader = store.reader(table)) {
            return rows(reader);
        }
    }

    private static void assertSameRows(final Store subject, final Store reference, final String table,
            final String context) {
        assertThat(subject.def(table)).as("%s table=%s", context, table).isEqualTo(reference.def(table));
        assertThat(read(subject, table)).as("%s table=%s", context, table).isEqualTo(read(reference, table));
    }

    private static List<String> folders(final Path table) {
        try (final Stream<Path> entries = Files.list(table)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // a store's files as a crash leaves them in the page cache: the open store's lock file left out
    private static void copyTree(final Path from, final Path to) throws IOException {
        try (final Stream<Path> paths = Files.walk(from)) {
            for (final Path path : paths.toList()) {
                if (path.getFileName().toString().equals("_lock")) {
                    continue;
                }
                final Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    /// One row of the tests' tables; `extra` goes to the column the differential adds.
    private record Row(long ts, String sensor, double value, String note, long n, boolean flag, long extra) {
    }

    /// A recorder that sees nothing; a test overrides the operation it fails or watches.
    private static class Quiet implements StoreFiles.Recorder {

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
