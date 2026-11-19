package dev.marregui.comando.cdb.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;
import dev.marregui.comando.cdb.CdbServe;
import dev.marregui.comando.cdb.store.DestructiveStressTest;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `BACKUP TO` under concurrent line-protocol writers and a table dropped and created again: each table of a backup
/// is one committed state of its source, judged by commit tickets as the destructive stress test judges its reads
/// (whole batches, each writer's batches a prefix holding every batch answered before the backup began and none
/// started after it ended); the table dropped meanwhile is absent or one committed state of one of its lives. The
/// statement answers the tables it copied and refuses a folder that exists. `CdbServe --restore` then makes a data
/// root of the last backup whose tables equal it, definition and rows, and refuses a root that is not empty. The
/// schedule comes from seeds `cdb.destructive.seed0` and `cdb.destructive.seed1`, printed on every run.
class BackupRestoreTest {

    // the tables written all along: a plain one, one with upsert keys, one with a TTL far beyond the rows' age
    private static final List<String> KEPT = List.of("plain", "keyed", "aged");
    private static final List<String> CREATE = List.of(
            "CREATE TABLE plain (b LONG, n LONG, s SYMBOL, ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY",
            "CREATE TABLE keyed (b LONG, n LONG, s SYMBOL, ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY"
                    + " DEDUP UPSERT KEYS(ts, s)",
            "CREATE TABLE aged (b LONG, n LONG, s SYMBOL, ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY TTL 520 WEEKS");
    // the table the dropper drops and the line protocol creates again
    private static final String DROPPED = "dropped";
    private static final long BASE_MICROS = 1_793_491_200_000_000L;
    private static final long DAY_MICROS = TimeUnit.DAYS.toMicros(1L);
    // rows per batch: enough that a torn copy shows as a partial batch
    private static final int ROWS = 3;
    // 80 batches per kept table, 60 for the dropped one: the backups overlap many commits and a run stays in seconds
    private static final int BATCHES = 80;
    private static final int DROPPED_BATCHES = 60;
    // a drop before batch i of the dropped table when i mod 12 is the seed's draw of 0 or 1: about five lives
    private static final int DROP_EVERY = 12;
    // six backups, one each time the kept writers have written another 40 batches between them
    private static final int BACKUPS = 6;
    private static final int PACE = KEPT.size() * BATCHES / BACKUPS;
    // a restore is one copy of a small store: seconds; a minute only bounds a hung child
    private static final long CHILD_SECONDS = 60L;
    private static final int MAX_ANOMALIES = 50;

    @TempDir
    private Path dir;

    /// Six backups taken while three writers append and a fourth table is dropped and created again: each backup's
    /// tables are committed states of their sources, the answer counts them, a folder that exists is refused; the last
    /// backup restores into an empty root, table for table, and a second restore into that root is refused.
    @Test
    void backupsAreCommittedStatesAndRestoreReproducesThem() throws Exception {
        final SplittableRandom random = DestructiveStressTest.random("BackupRestoreTest");
        final Path source = dir.resolve("source");
        final Run run;
        final Map<String, TableDef> sourceDefs = new HashMap<>();
        try (final Cdb cdb = Cdb.open(source)) {
            for (final String sql : CREATE) {
                cdb.run(sql);
            }
            run = new Run(cdb, dir);
            final List<DestructiveStressTest.Task> tasks = new ArrayList<>();
            for (int t = 0; t < KEPT.size(); t++) {
                final int table = t;
                tasks.add(() -> run.write(table));
            }
            tasks.add(() -> run.dropAndWrite(random));
            tasks.add(run::backUp);
            final List<String> raced = DestructiveStressTest.race("backup", tasks);
            System.out.println("BackupRestoreTest [backups=" + run.backups.size() + ']');
            assertThat(raced).isEmpty();
            assertThat(run.anomalies).isEmpty();
            assertThat(run.backups).hasSize(BACKUPS);
            assertThatAFolderThatExistsIsRefused(cdb, run.backups.getLast().folder());
        }
        try (final Store store = Store.open(source)) {
            for (final String table : KEPT) {
                sourceDefs.put(table, store.def(table));
            }
        }
        for (final Backup backup : run.backups) {
            judge(backup, run, sourceDefs);
        }
        assertThat(run.anomalies).isEmpty();
        assertThatTheLastBackupRestores(run.backups.getLast().folder());
    }

    // BACKUP TO a folder that exists refuses, and leaves the folder as it was
    private static void assertThatAFolderThatExistsIsRefused(final Cdb cdb, final Path folder) throws IOException {
        final List<Path> before = list(folder);
        assertThatThrownBy(() -> cdb.run("BACKUP TO '" + folder + "'")).isInstanceOf(CdbException.class);
        assertThat(list(folder)).isEqualTo(before);
    }

    // one backup opened as a store: its answer, each kept table's definition and batches, the dropped table's
    private static void judge(final Backup backup, final Run run, final Map<String, TableDef> sourceDefs) {
        try (final Store store = Store.open(backup.folder())) {
            if (store.tables().size() != backup.answer()) {
                run.note("a backup answered other than the tables it copied [folder=" + backup.folder() + ", answer="
                        + backup.answer() + ", tables=" + store.tables() + ']');
            }
            for (final String table : KEPT) {
                if (store.def(table) == null) {
                    run.note("a backup misses a table [table=" + table + ", folder=" + backup.folder() + ']');
                } else if (!store.def(table).equals(sourceDefs.get(table))) {
                    run.note("a backup's definition differs from its source's [table=" + table + ", backup="
                            + store.def(table) + ", source=" + sourceDefs.get(table) + ']');
                }
            }
        }
        try (final Cdb cdb = Cdb.open(backup.folder())) {
            for (int t = 0; t < KEPT.size(); t++) {
                run.judgePrefix(KEPT.get(t), t, counts(cdb, KEPT.get(t)), backup);
            }
            if (cdb.tables().contains(DROPPED)) {
                run.judgeDropped(counts(cdb, DROPPED), backup);
            }
        }
    }

    // the restore of a backup in a JVM of its own, as an operator runs it: exits 0 into an absent root, which then
    // holds every table of the backup with its definition and rows; exits other than 0 into a root that is not empty
    private static void assertThatTheLastBackupRestores(final Path backup) throws Exception {
        final Path restored = backup.resolveSibling("restored");
        assertThat(restore(backup, restored)).as("the restore's exit").isZero();
        final Map<String, TableDef> backupDefs = defs(backup);
        assertThat(defs(restored)).isEqualTo(backupDefs);
        try (final Cdb from = Cdb.open(backup);
                  final Cdb to = Cdb.open(restored)) {
            for (final TableDef def : backupDefs.values()) {
                final String select = "SELECT * FROM " + def.name() + " ORDER BY "
                        + def.columns().get(def.timestampIndex()).name();
                assertThat(SqlRows.rows(to.run(select))).as(def.name()).isEqualTo(SqlRows.rows(from.run(select)));
            }
        }
        final List<Path> before = list(restored);
        assertThat(restore(backup, restored)).as("the exit of a restore into a root that is not empty").isNotZero();
        assertThat(list(restored)).isEqualTo(before);
    }

    // runs CdbServe --restore in a child JVM, its output in a file beside the backup; its exit status
    private static int restore(final Path backup, final Path root) throws IOException, InterruptedException {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final Path log = backup.resolveSibling("restore-" + root.getFileName() + "-" + System.nanoTime() + ".log");
        final Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                CdbServe.class.getName(), "--restore", backup.toString(), root.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!child.waitFor(CHILD_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("the restore did not end [seconds=" + CHILD_SECONDS + ", log="
                    + Files.readString(log) + ']');
        }
        System.out.println("restore [root=" + root + ", exit=" + child.exitValue() + "]: " + Files.readString(log));
        return child.exitValue();
    }

    private static Map<String, TableDef> defs(final Path root) {
        final Map<String, TableDef> defs = new TreeMap<>();
        try (final Store store = Store.open(root)) {
            for (final String table : store.tables()) {
                defs.put(table, store.def(table));
            }
        }
        return defs;
    }

    // each batch's rows in the table
    private static Map<Long, Long> counts(final Cdb cdb, final String table) {
        final CdbResult result = cdb.run("SELECT b, count() FROM " + table);
        final Map<Long, Long> counts = new HashMap<>();
        for (int r = 0; r < result.rowCount(); r++) {
            counts.put(result.getLong(r, 0), result.getLong(r, 1));
        }
        return counts;
    }

    private static List<Path> list(final Path folder) throws IOException {
        try (final Stream<Path> entries = Files.walk(folder)) {
            return entries.sorted().toList();
        }
    }

    private static long micros(final long batch, final int n) {
        return BASE_MICROS + (batch % 2L) * DAY_MICROS + (batch / 2L) * 10L + n;
    }

    /// One backup: its folder, its answer, and the ticks before it began and after it returned.
    record Backup(Path folder, long answer, long started, long ended) {
    }

    /// One run's shared state: the writers' tickets, the backups' pace and the anomalies.
    private static final class Run {

        private final Cdb cdb;
        private final Path dir;
        // one clock orders every batch, drop and backup
        private final AtomicLong clock = new AtomicLong();
        // per kept table and batch: the tick before its append, the tick after it returned; 0 not yet
        private final AtomicLongArray started = new AtomicLongArray(KEPT.size() * BATCHES);
        private final AtomicLongArray answered = new AtomicLongArray(KEPT.size() * BATCHES);
        private final Semaphore pace = new Semaphore(0);
        // the kept writers still running: the backups stop waiting for pace at 0
        private final AtomicInteger writing = new AtomicInteger(KEPT.size());
        private final List<Backup> backups = new ArrayList<>();
        private final Queue<String> anomalies = new ConcurrentLinkedQueue<>();

        Run(final Cdb cdb, final Path dir) {
            this.cdb = cdb;
            this.dir = dir;
        }

        void write(final int table) {
            try {
                for (int i = 0; i < BATCHES; i++) {
                    started.set(table * BATCHES + i, clock.incrementAndGet());
                    append(KEPT.get(table), i);
                    answered.set(table * BATCHES + i, clock.incrementAndGet());
                    if ((table * BATCHES + i + 1) % PACE == 0) {
                        pace.release();
                    }
                }
            } finally {
                writing.decrementAndGet();
                pace.release(BACKUPS);
            }
        }

        // the dropped table's batches with drops between them: each life of it holds a contiguous run of batches
        void dropAndWrite(final SplittableRandom random) {
            for (int i = 0; i < DROPPED_BATCHES; i++) {
                if (i > 0 && i % DROP_EVERY == random.nextInt(2)) {
                    cdb.run("DROP TABLE IF EXISTS " + DROPPED);
                }
                append(DROPPED, i);
            }
        }

        // BACKUPS backups into new folders, each after the writers have moved on PACE batches
        void backUp() throws InterruptedException {
            for (int i = 0; i < BACKUPS; i++) {
                if (!pace.tryAcquire(DestructiveStressTest.TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    note("the writers stopped pacing the backups [backup=" + i + ']');
                    return;
                }
                final Path folder = dir.resolve("backup-" + i);
                final long begin = clock.incrementAndGet();
                final long answer = cdb.run((i % 2 == 0 ? "BACKUP TO '" : "BACKUP DATABASE TO '") + folder + "'")
                        .updateCount();
                backups.add(new Backup(folder, answer, begin, clock.incrementAndGet()));
            }
        }

        // a kept table in a backup: whole batches, a prefix, every batch answered before the backup and none started
        // after it
        void judgePrefix(final String name, final int table, final Map<Long, Long> counts, final Backup backup) {
            for (final Map.Entry<Long, Long> count : counts.entrySet()) {
                if (count.getValue() != ROWS) {
                    note("a backup holds part of a batch [table=" + name + ", batch=" + count.getKey() + ", rows="
                            + count.getValue() + ']');
                }
            }
            int prefix = 0;
            while (prefix < BATCHES && counts.containsKey((long) prefix)) {
                prefix++;
            }
            if (counts.size() != prefix) {
                note("a backup holds a batch after a missing one [table=" + name + ", missing=" + prefix + ']');
            }
            final long missingAnswered = prefix < BATCHES ? answered.get(table * BATCHES + prefix) : 0L;
            if (missingAnswered != 0L && missingAnswered < backup.started()) {
                note("a backup misses a batch answered before it began [table=" + name + ", batch=" + prefix + ']');
            }
            final long lastStarted = prefix > 0 ? started.get(table * BATCHES + prefix - 1) : 0L;
            if (prefix > 0 && (lastStarted == 0L || lastStarted > backup.ended())) {
                note("a backup holds a batch started after it ended [table=" + name + ", batch=" + (prefix - 1)
                        + ']');
            }
        }

        // the dropped table in a backup: whole batches, one contiguous run of them, as one life of the table holds
        void judgeDropped(final Map<Long, Long> counts, final Backup backup) {
            if (counts.isEmpty()) {
                return;
            }
            final long low = counts.keySet().stream().mapToLong(Long::longValue).min().orElseThrow();
            final long high = counts.keySet().stream().mapToLong(Long::longValue).max().orElseThrow();
            if (high - low + 1 != counts.size() || counts.values().stream().anyMatch(rows -> rows != ROWS)) {
                note("a backup holds no one state of the dropped table [folder=" + backup.folder() + ", counts="
                        + new TreeMap<>(counts) + ']');
            }
        }

        // one batch, ROWS lines in one commit; a refusal is an anomaly: the line protocol creates what is not there
        private void append(final String table, final long batch) {
            try (final CdbAppender appender = cdb.appender()) {
                for (int n = 0; n < ROWS; n++) {
                    appender.table(table).symbol("s", "s" + n).longColumn("b", batch).longColumn("n", n)
                            .at(micros(batch, n));
                }
                appender.flush();
            } catch (final CdbException e) {
                note("the line protocol refused a batch [table=" + table + ", batch=" + batch + ", error=" + e + ']');
            }
        }

        private void note(final String anomaly) {
            if (anomalies.size() < MAX_ANOMALIES) {
                anomalies.add(anomaly);
            }
        }
    }
}
