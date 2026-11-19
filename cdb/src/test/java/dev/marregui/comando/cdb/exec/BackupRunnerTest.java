package dev.marregui.comando.cdb.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.DestructiveStressTest;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableWriter;
import dev.marregui.comando.cdb.store.WalStatus;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [BackupRunner] on stores in a temporary folder: every table copied whole, definition and rows, into a new store at
/// the folder, through `BACKUP TO` and directly on the [Store]; a folder that exists refused, a failure leaving nothing
/// behind, and each table copied at one commit while writes, drops and another backup go on. Each source is set up
/// and each copy read back through [Cdb], in its own open of the folder.
class BackupRunnerTest {

    // the row timestamps of the tables the tests write through a TableWriter: 2026-11-01T00:00:00Z
    private static final long BASE_MICROS = 1_793_491_200_000_000L;
    // a writer's wait for another to close: the runners' own
    private static final long WAIT_MILLIS = UpdateRunner.WAIT_MILLIS;
    // the concurrent schedules: rounds per test, the rows each dropped table holds, the tables a round can drop, the
    // rows the written table holds before the race and the most commits its writer makes during one
    private static final int ROUNDS = 4;
    private static final int DROPPED_ROWS = 20_000;
    private static final int DROPPED_TABLES = 6;
    private static final int PRE_ROWS = 50_000;
    private static final int MAX_TXNS = 5_000;

    @TempDir
    private Path root;

    /// `BACKUP TO` copies every table, a TTL, dedup keys, no designated timestamp, BYPASS WAL, symbols, text, NULLs and
    /// an empty table among them: the copy has the same tables with the same definitions and rows, and the answer is
    /// the tables copied.
    @Test
    void backupCopiesEveryTableWhole() {
        final Path source = root.resolve("source");
        final Path folder = root.resolve("backup");
        try (final Cdb cdb = Cdb.open(source)) {
            cdb.run("CREATE TABLE metrics (ts TIMESTAMP, host SYMBOL, v DOUBLE, ok BOOLEAN, note VARCHAR, n LONG)"
                    + " TIMESTAMP(ts) PARTITION BY DAY TTL 2 DAYS DEDUP UPSERT KEYS(ts, host)");
            cdb.run("INSERT INTO metrics VALUES ('2026-11-14T10:00:00.000000Z', 'a', 1.5, true, 'x', 1),"
                    + " ('2026-11-14T10:00:00.000000Z', 'b', NULL, false, NULL, NULL),"
                    + " ('2026-11-15T10:00:00.000000Z', NULL, -2.25, true, 'y', 3),"
                    + " ('2026-11-15T11:00:00.000000Z', 'a', 4.0, false, '', 4)");
            cdb.run("INSERT INTO metrics VALUES ('2026-11-15T10:00:00.000000Z', NULL, 9.0, false, 'z', 5)");
            cdb.run("CREATE TABLE plain (ts TIMESTAMP, v DOUBLE) TIMESTAMP(ts) PARTITION BY NONE BYPASS WAL");
            cdb.run("INSERT INTO plain VALUES ('2026-11-14T10:00:00.000000Z', 1.0),"
                    + " ('2026-11-13T10:00:00.000000Z', 2.0)");
            cdb.run("CREATE TABLE loose (x LONG, s VARCHAR)");
            cdb.run("INSERT INTO loose VALUES (3, 'c'), (1, NULL), (2, 'b')");
            cdb.run("CREATE TABLE empty (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
            assertThat(cdb.run("BACKUP TO '" + folder + "'").updateCount()).isEqualTo(4);
        }
        assertSameStore(source, folder);
        assertThat(select(folder, "SELECT * FROM metrics")).containsExactly(
                "2026-11-14T10:00:00Z|a|1.5|true|x|1", "2026-11-14T10:00:00Z|b||false||",
                "2026-11-15T10:00:00Z||9.0|false|z|5", "2026-11-15T11:00:00Z|a|4.0|false||4");
        assertThat(siblings()).containsExactlyInAnyOrder("backup", "source");
    }

    /// A store without tables backs up to a store without tables; the answer is 0.
    @Test
    void emptyStoreBacksUpToAnEmptyStore() {
        final Path source = root.resolve("source");
        final Path folder = root.resolve("backup");
        try (final Store store = Store.open(source)) {
            assertThat(BackupRunner.run(new Plan.Backup(folder.toString()), store)).isZero();
        }
        try (final Store copy = Store.open(folder)) {
            assertThat(copy.tables()).isEmpty();
        }
        assertThat(siblings()).containsExactlyInAnyOrder("backup", "source");
    }

    /// A table of more rows than a batch is copied in several commits, none of them unbounded, and arrives whole.
    @Test
    void largeTableIsCopiedInBoundedBatches() {
        final Path source = root.resolve("source");
        final Path folder = root.resolve("backup");
        final long rows = 2 * BackupRunner.BATCH_ROWS + 5;
        try (final Cdb cdb = Cdb.open(source)) {
            cdb.run("CREATE TABLE w (ts TIMESTAMP, txn LONG, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
        }
        try (final Store store = Store.open(source)) {
            fill(store, "w", 0, rows);
            assertThat(BackupRunner.run(new Plan.Backup(folder.toString()), store)).isEqualTo(1);
        }
        assertThat(select(folder, "SELECT count(), sum(n), min(ts), max(ts) FROM w"))
                .isEqualTo(select(source, "SELECT count(), sum(n), min(ts), max(ts) FROM w"))
                .containsExactly(rows + "|" + rows * (rows - 1) / 2 + "|2026-11-01T00:00:00Z|2026-11-01T00:00:00."
                        + (rows - 1) + 'Z');
        try (final Store copy = Store.open(folder)) {
            final WalStatus status = copy.walStatus().getFirst();
            // one commit per full batch, and the last for the rest
            assertThat(status.appliedTxn()).isEqualTo(3);
        }
    }

    /// The copy of a table with a TTL holds every row the source holds and the same TTL, which applies to it once it
    /// is open: a newer row expires the old days in the copy as it would in the source.
    @Test
    void ttlTableKeepsEveryRowAndItsTtl() {
        final Path source = root.resolve("source");
        final Path folder = root.resolve("backup");
        try (final Cdb cdb = Cdb.open(source)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY TTL 2 DAYS");
            cdb.run("INSERT INTO t VALUES ('2026-11-13T10:00:00.000000Z', 1), ('2026-11-14T10:00:00.000000Z', 2),"
                    + " ('2026-11-15T23:00:00.000000Z', 3)");
            cdb.run("BACKUP TO '" + folder + "'");
        }
        assertSameStore(source, folder);
        assertThat(select(folder, "SELECT n FROM t")).containsExactly("1", "2", "3");
        try (final Cdb copy = Cdb.open(folder)) {
            copy.run("INSERT INTO t VALUES ('2026-11-17T10:00:00.000000Z', 4)");
            assertThat(rows(copy.run("SELECT n FROM t"))).containsExactly("3", "4");
        }
    }

    /// A folder that exists, empty or a file, is refused before anything is copied, and stays as it was; no temporary
    /// folder is left. A second backup into the folder of the first is refused the same way.
    @Test
    void existingFolderIsRefused() throws IOException {
        final Path source = root.resolve("source");
        try (final Cdb cdb = Cdb.open(source)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("INSERT INTO t VALUES ('2026-11-14T10:00:00.000000Z', 1)");
        }
        final Path dir = Files.createDirectory(root.resolve("dir"));
        final Path file = Files.writeString(root.resolve("file"), "kept");
        final Path folder = root.resolve("backup");
        try (final Store store = Store.open(source)) {
            for (final Path existing : List.of(dir, file)) {
                assertThatThrownBy(() -> BackupRunner.run(new Plan.Backup(existing.toString()), store))
                        .isInstanceOf(CdbException.class)
                        .hasMessage("cannot back up into a folder that exists [folder=" + existing + ']');
            }
            assertThat(BackupRunner.run(new Plan.Backup(folder.toString()), store)).isEqualTo(1);
            assertThatThrownBy(() -> BackupRunner.run(new Plan.Backup(folder.toString()), store))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot back up into a folder that exists [folder=" + folder + ']');
        }
        try (final Stream<Path> entries = Files.list(dir)) {
            assertThat(entries).isEmpty();
        }
        assertThat(Files.readAttributes(file, "size")).containsEntry("size", 4L);
        assertSameStore(source, folder);
        assertThat(siblings()).containsExactlyInAnyOrder("backup", "dir", "file", "source");
    }

    /// A relative folder, which would depend on the server's working folder, and a folder inside the store's root,
    /// which the next open would read as a table, are refused before anything is written.
    @Test
    void relativeOrInsideTheRootFolderIsRefused() throws IOException {
        final Path source = root.resolve("source");
        try (final Cdb cdb = Cdb.open(source)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
        }
        final Path inside = source.resolve("backup");
        try (final Store store = Store.open(source)) {
            assertThatThrownBy(() -> BackupRunner.run(new Plan.Backup("backup"), store))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot back up into a relative folder [folder=backup]");
            assertThatThrownBy(() -> BackupRunner.run(new Plan.Backup(inside.toString()), store))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot back up into the store's own folder [folder=" + inside + ", root=" + source
                            + ']');
            assertThat(store.tables()).containsExactly("t");
        }
        assertThat(Files.exists(inside)).isFalse();
        assertThat(Files.exists(Path.of("backup"))).isFalse();
        assertThat(siblings()).containsExactly("source");
    }

    /// A backup that fails once its temporary folder exists, here of a store closed under it, removes that folder and
    /// leaves nothing at the backup's folder.
    @Test
    void failureLeavesNothingBehind() {
        final Path source = root.resolve("source");
        final Path folder = root.resolve("backup");
        try (final Cdb cdb = Cdb.open(source)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
        }
        final Store store;
        try (final Store open = Store.open(source)) {
            store = open;
        }
        assertThatThrownBy(() -> BackupRunner.run(new Plan.Backup(folder.toString()), store))
                .isInstanceOf(CdbException.class);
        assertThat(Files.exists(folder)).isFalse();
        assertThat(siblings()).containsExactly("source");
    }

    /// Two backups into one folder at once: exactly one answers, the other is refused as a folder that exists, and the
    /// folder holds a whole backup; no temporary folder is left.
    @Test
    void concurrentBackupsIntoOneFolderLeaveOneWholeBackup() throws Exception {
        final Path source = root.resolve("source");
        try (final Cdb cdb = Cdb.open(source)) {
            cdb.run("CREATE TABLE w (ts TIMESTAMP, txn LONG, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
        }
        try (final Store store = Store.open(source)) {
            fill(store, "w", 0, PRE_ROWS);
            for (int round = 0; round < ROUNDS; round++) {
                final Path folder = root.resolve("backup-" + round);
                final Queue<Long> answers = new ConcurrentLinkedQueue<>();
                final Queue<CdbException> refusals = new ConcurrentLinkedQueue<>();
                final DestructiveStressTest.Task backup = () -> {
                    try {
                        answers.add(BackupRunner.run(new Plan.Backup(folder.toString()), store));
                    } catch (final CdbException e) {
                        refusals.add(e);
                    }
                };
                assertThat(DestructiveStressTest.race("backup", List.of(backup, backup))).isEmpty();
                assertThat(answers).containsExactly(1L);
                assertThat(refusals).singleElement()
                        .extracting(Throwable::getMessage)
                        .isEqualTo("cannot back up into a folder that exists [folder=" + folder + ']');
                assertThat(select(folder, "SELECT count(), sum(n) FROM w"))
                        .containsExactly(PRE_ROWS + "|" + (long) PRE_ROWS * (PRE_ROWS - 1) / 2);
            }
        }
        assertThat(siblings()).hasSize(ROUNDS + 1).allMatch(name -> !name.contains(".tmp-"));
    }

    /// A backup while a writer commits and a dropper drops tables: each table in the copy is the source's state at one
    /// commit, the written one a whole prefix of its commits; a dropped table is either absent from the copy or whole,
    /// one not dropped is there whole; the answer is the copy's tables. Seeds printed, replayed through
    /// `cdb.backup.seed0` and `cdb.backup.seed1`.
    @Test
    void eachTableIsCopiedAtOneCommitWhileWritesAndDropsGoOn() throws Exception {
        final TwoSeedRandom random = TwoSeedRandom.of("cdb.backup",
                "eachTableIsCopiedAtOneCommitWhileWritesAndDropsGoOn");
        for (int round = 0; round < ROUNDS; round++) {
            final Path source = root.resolve("source-" + round);
            final Path folder = root.resolve("backup-" + round);
            final int perTxn = random.between(1, 64);
            final List<Integer> drops = new ArrayList<>();
            for (int i = 0; i < DROPPED_TABLES; i++) {
                if (random.nextBoolean()) {
                    drops.add(random.nextInt(drops.size() + 1), i);
                }
            }
            try (final Cdb cdb = Cdb.open(source)) {
                cdb.run("CREATE TABLE w (ts TIMESTAMP, txn LONG, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
                for (int i = 0; i < DROPPED_TABLES; i++) {
                    cdb.run("CREATE TABLE d" + i + " (ts TIMESTAMP, txn LONG, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
                }
            }
            final AtomicBoolean done = new AtomicBoolean();
            final AtomicLong answer = new AtomicLong(-1);
            final AtomicLong committed = new AtomicLong();
            final AtomicLong missedDrops = new AtomicLong();
            try (final Store store = Store.open(source)) {
                fill(store, "w", 0, PRE_ROWS);
                for (int i = 0; i < DROPPED_TABLES; i++) {
                    fill(store, "d" + i, 0, DROPPED_ROWS);
                }
                final DestructiveStressTest.Task backup = () -> {
                    try {
                        answer.set(BackupRunner.run(new Plan.Backup(folder.toString()), store));
                    } finally {
                        done.set(true);
                    }
                };
                final DestructiveStressTest.Task writes = () -> {
                    try (final TableWriter writer = store.writer("w", WAIT_MILLIS)) {
                        for (long txn = 1; !done.get() && txn <= MAX_TXNS; txn++) {
                            for (int n = 0; n < perTxn; n++) {
                                writer.newRow(BASE_MICROS + PRE_ROWS + txn * perTxn + n);
                                writer.putLong(1, txn);
                                writer.putLong(2, n);
                                writer.endRow();
                            }
                            writer.commit();
                            committed.set(txn);
                        }
                    }
                };
                final DestructiveStressTest.Task dropper = () -> {
                    for (final int i : drops) {
                        if (!store.dropTable("d" + i, WAIT_MILLIS)) {
                            missedDrops.incrementAndGet();
                        }
                    }
                };
                assertThat(DestructiveStressTest.race("backup", List.of(backup, writes, dropper))).isEmpty();
            }
            assertThat(missedDrops.get()).as("drops that found no table").isZero();
            final List<String> tables;
            try (final Store copy = Store.open(folder)) {
                tables = copy.tables();
            }
            System.out.println("backup round [round=" + round + ", perTxn=" + perTxn + ", drops=" + drops
                    + ", committed=" + committed.get() + ", copied=" + tables + ']');
            assertThat(answer.get()).isEqualTo(tables.size());
            assertThat(tables).contains("w");
            for (int i = 0; i < DROPPED_TABLES; i++) {
                if (!drops.contains(i) || tables.contains("d" + i)) {
                    assertThat(select(folder, "SELECT count(), sum(n) FROM d" + i)).as("d" + i)
                            .containsExactly(DROPPED_ROWS + "|" + (long) DROPPED_ROWS * (DROPPED_ROWS - 1) / 2);
                }
            }
            assertPrefixOfCommits(select(folder, "SELECT txn, n FROM w"), perTxn, committed.get());
            assertThat(siblings()).allMatch(name -> !name.contains(".tmp-"));
        }
    }

    // the rows of w, txn|n each: txn 0's PRE_ROWS rows, then perTxn rows of each commit 1..k, k at most committed
    private static void assertPrefixOfCommits(final List<String> rows, final int perTxn, final long committed) {
        assertThat(rows.size()).isGreaterThanOrEqualTo(PRE_ROWS);
        assertThat((rows.size() - PRE_ROWS) % perTxn).as("rows past the fill [perTxn=%d]", perTxn).isZero();
        final long last = (rows.size() - PRE_ROWS) / perTxn;
        assertThat(last).isLessThanOrEqualTo(committed);
        for (int r = 0; r < rows.size(); r++) {
            final long txn = r < PRE_ROWS ? 0 : 1 + (r - PRE_ROWS) / perTxn;
            final long n = r < PRE_ROWS ? r : (r - PRE_ROWS) % perTxn;
            assertThat(rows.get(r)).as("row %d", r).isEqualTo(txn + "|" + n);
        }
    }

    // appends rows rows of txn to table (ts, txn, n), n from 0, in one commit
    private static void fill(final Store store, final String table, final long txn, final long rows) {
        try (final TableWriter writer = store.writer(table, WAIT_MILLIS)) {
            for (long n = 0; n < rows; n++) {
                writer.newRow(BASE_MICROS + n);
                writer.putLong(1, txn);
                writer.putLong(2, n);
                writer.endRow();
            }
            writer.commit();
        }
    }

    private static void assertSameStore(final Path source, final Path copy) {
        final List<String> tables;
        try (final Store from = Store.open(source); final Store to = Store.open(copy)) {
            tables = from.tables();
            assertThat(to.tables()).isEqualTo(tables);
            for (final String table : tables) {
                assertThat(to.def(table)).as(table).isEqualTo(from.def(table));
            }
        }
        for (final String table : tables) {
            assertThat(select(copy, "SELECT * FROM " + table)).as(table)
                    .isEqualTo(select(source, "SELECT * FROM " + table));
        }
    }

    private static List<String> select(final Path folder, final String sql) {
        try (final Cdb cdb = Cdb.open(folder)) {
            return rows(cdb.run(sql));
        }
    }

    // the names in the temporary folder: the sources, the backups and anything a backup left beside them
    private List<String> siblings() {
        try (final Stream<Path> entries = Files.list(root)) {
            return entries.map(p -> p.getFileName().toString()).toList();
        } catch (final IOException e) {
            throw new AssertionError("cannot list the temporary folder [root=" + root + ']', e);
        }
    }
}
