package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;

/// `DELETE`, `TRUNCATE`, `ALTER TABLE ... DROP PARTITION` and `DROP TABLE` race line-protocol writers and readers of
/// an in-process `Cdb`: every read sees one whole snapshot, the state after some prefix of the changes that ran; no row
/// goes missing that no statement removed; nothing fails but a statement on a table that is not there. The schedules
/// come from seeds `cdb.destructive.seed0` and `cdb.destructive.seed1`, printed on every run (`CDB_DESTRUCTIVE_SEED0`
/// and `CDB_DESTRUCTIVE_SEED1` pass them to Gradle's test JVM). The schedules, the model and the race are public: the
/// HTTP twin in `serve` runs them over `CdbServer`.
public class DestructiveStressTest {

    /// The table every schedule writes.
    public static final String TABLE = "m";
    /// Rows per appended batch, `n` 0 to 2: enough that a torn commit or a torn rewrite shows as a partial batch.
    public static final int ROWS = 3;
    /// Daily partitions the batches spread over, 2026-11-01 to 2026-11-04.
    public static final int DAYS = 4;
    /// The serial schedule's changes: the 200 iterations the task asks for, and a margin.
    public static final int SERIAL_OPS = 240;
    /// Four readers keep reading while the changes run: more readers than the two writers, so reads overlap commits.
    public static final int READERS = 4;
    /// Bounds a hung run; a whole schedule takes seconds on a laptop.
    public static final long TIMEOUT_SECONDS = 120L;

    /// A thread interrupted after a timeout leaves within a store wait; ten seconds only bounds one that does not.
    private static final long JOIN_SECONDS = 10L;
    private static final long BASE_MICROS = StoreRows.micros("2026-11-01T00:00:00Z");
    private static final long DAY_MICROS = TimeUnit.DAYS.toMicros(1L);
    /// The contended schedule's writers and their batches: 200 appends against 60 changes keep the writer contended.
    private static final int APPENDERS = 2;
    private static final int APPENDS_EACH = 100;
    private static final int DESTROYS = 60;
    /// A protected batch written before the race, so the first reads already have one to find.
    private static final long PRE_BATCH = 9_000_000L;
    /// The dropping schedule's destroys: 40 against 200 appends, one let go per 5 answered appends, so the destroys
    /// spread over the whole run and land between commits of both writers; unpaced, all 40 ran before the first commit.
    private static final int DROPPING_DESTROYS = 40;
    /// A reader holds at most this many unsettled reads, then waits for the change to end: a slow change (a DELETE
    /// rewrite on a CI runner) otherwise piled up reads past the 1 GiB test heap.
    private static final int MAX_PENDING_READS = 64;
    private static final int DROPPING_PACE = APPENDERS * APPENDS_EACH / DROPPING_DESTROYS;
    /// Anomalies kept per schedule: a broken store would otherwise add one per read until the heap is gone.
    private static final int MAX_ANOMALIES = 50;

    /// A serial schedule of 240 changes, drawn from the seeds, runs on one thread while four readers count each
    /// batch's rows: each read equals the model after some change between the last one finished before it and the
    /// last one started after it, each change answers the model's update count or is refused exactly when the table
    /// is absent, and the table holds the model's rows at the end and after a reopen.
    @Test
    void readersSeeWholeSnapshotsAcrossEverySerialChange(@TempDir final Path root) throws Exception {
        final SplittableRandom random = random("readersSeeWholeSnapshotsAcrossEverySerialChange");
        final Model end;
        try (Cdb cdb = Cdb.open(root)) {
            end = serial(new LocalTarget(cdb), random, SERIAL_OPS);
            assertEnd(cdb, end);
        }
        try (Cdb cdb = Cdb.open(root)) {
            assertEnd(cdb, end);
        }
    }

    /// Two line-protocol writers append 100 batches each while a third thread deletes the other class and drops the
    /// last two days' partitions 60 times, all three contending for the table's writer, and four readers read: no
    /// statement fails, every protected batch (class 0, days 1 and 2) appended before a read is in it whole, no read
    /// holds a batch nobody appended, and every protected batch is there at the end.
    @Test
    void contendedChangesLoseNoProtectedRow(@TempDir final Path root) throws Exception {
        final SplittableRandom random = random("contendedChangesLoseNoProtectedRow");
        try (Cdb cdb = Cdb.open(root)) {
            contended(new LocalTarget(cdb), random);
        }
    }

    /// Two line-protocol writers append 100 batches each while a third thread runs 40 `TRUNCATE`, `DROP TABLE` and
    /// `DROP TABLE IF EXISTS`, all contending for the table's writer, and four readers read: every read holds whole
    /// batches only, a suffix of the commits that no destroy split; every batch acknowledged after the last destroy
    /// ended is in every later read; a write after a drop recreates the table; a statement, a read or a write is
    /// refused only when a drop can have left the table absent.
    @Test
    void truncatesAndDropsRaceWritersAndReaders(@TempDir final Path root) throws Exception {
        final SplittableRandom random = random("truncatesAndDropsRaceWritersAndReaders");
        try (Cdb cdb = Cdb.open(root)) {
            dropping(new LocalTarget(cdb), random);
        }
    }

    /// The seeded generator of `test`: seeds `cdb.destructive.seed0` and `cdb.destructive.seed1`, from the clock when
    /// unset, printed with the test's name.
    public static SplittableRandom random(final String test) {
        final long seed0 = Long.getLong("cdb.destructive.seed0", System.nanoTime());
        final long seed1 = Long.getLong("cdb.destructive.seed1", System.currentTimeMillis() * 31L + System.nanoTime());
        System.out.println(test + " seeds [cdb.destructive.seed0=" + seed0 + ", cdb.destructive.seed1=" + seed1 + ']');
        return new SplittableRandom(seed0 ^ Long.rotateLeft(seed1, 31) * 0x9E3779B97F4A7C15L);
    }

    /// Runs `ops` changes drawn from `random` on one thread against `target`, `READERS` readers reading beside it,
    /// and checks every change's answer and every read against the model; the model after the last change.
    public static Model serial(final Target target, final SplittableRandom random, final int ops) throws Exception {
        final Model model = new Model();
        // state k is written before published reaches k, and read only once it has
        final AtomicReferenceArray<Snapshot> states = new AtomicReferenceArray<>(ops + 1);
        states.set(0, model.snapshot());
        // a line-protocol write that creates the table shows it, empty, before its rows commit: while change k runs a
        // reader may see transients[k]
        final AtomicReferenceArray<Snapshot> transients = new AtomicReferenceArray<>(ops + 1);
        // the changing thread raises started before change k runs and published after it ends; a reader reads
        // published before its read and started after it, so it saw a state from lo to hi
        final AtomicLong started = new AtomicLong();
        final AtomicLong published = new AtomicLong();
        final AtomicBoolean stop = new AtomicBoolean();
        final Queue<String> anomalies = new ConcurrentLinkedQueue<>();
        final AtomicLong reads = new AtomicLong();
        final AtomicLong whileAbsent = new AtomicLong();
        // each reader checks a read once its window is published, so it holds only the reads of the running change
        final List<Queue<Observed>> pending = new ArrayList<>();
        final List<Task> tasks = new ArrayList<>();
        tasks.add(() -> {
            try {
                for (int k = 1; k <= ops; k++) {
                    final boolean absent = !model.present();
                    final Change change = model.next(random);
                    started.set(k);
                    final String bad = change.run(target);
                    if (bad != null) {
                        note(anomalies, "change " + k + ' ' + bad);
                    }
                    states.set(k, model.snapshot());
                    transients.set(k, absent && change.sql() == null ? Snapshot.EMPTY : null);
                    published.set(k);
                }
            } finally {
                stop.set(true);
            }
        });
        for (int r = 0; r < READERS; r++) {
            final Queue<Observed> waiting = new ArrayDeque<>();
            pending.add(waiting);
            tasks.add(() -> {
                while (!stop.get()) {
                    if (waiting.size() >= MAX_PENDING_READS) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
                    } else {
                        final long lo = published.get();
                        waiting.add(read(target, lo, started));
                    }
                    settle(waiting, published.get(), states, transients, anomalies, reads, whileAbsent);
                }
            });
        }
        for (final String bad : race("destructive-serial", tasks)) {
            note(anomalies, bad);
        }
        for (final Queue<Observed> waiting : pending) {
            settle(waiting, Long.MAX_VALUE, states, transients, anomalies, reads, whileAbsent);
        }
        System.out.println("serial schedule [ops=" + ops + ", reads=" + reads.get() + ", readsWhileAbsent="
                + whileAbsent.get() + ", tableDrops=" + model.tableDrops + ']');
        assertThat(anomalies).isEmpty();
        assertThat(reads.get()).as("reads checked").isPositive();
        return model;
    }

    /// Two writers append 100 batches each through line protocol while one thread runs 40 `TRUNCATE`, `DROP TABLE`
    /// and `DROP TABLE IF EXISTS`, and `READERS` readers read; every read is checked as taken by `Dropping`: a whole
    /// suffix of the commits, the state before or after each destroy and never between; a write after a drop
    /// recreates the table; no write refused; a read or destroy refused only where the table can be absent.
    public static void dropping(final Target target, final SplittableRandom random) throws Exception {
        final Dropping run = new Dropping();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicInteger changersLeft = new AtomicInteger(APPENDERS + 1);
        final Queue<String> anomalies = new ConcurrentLinkedQueue<>();
        final AtomicLong reads = new AtomicLong();
        final AtomicLong refusedReads = new AtomicLong();
        final AtomicLong refusedAppends = new AtomicLong();
        final AtomicLong truncated = new AtomicLong();
        // one permit per destroy: the first at once, the next after each DROPPING_PACE answered appends
        final Semaphore paced = new Semaphore(1);
        final AtomicInteger answered = new AtomicInteger();
        final List<Task> tasks = new ArrayList<>();
        for (int t = 0; t < APPENDERS; t++) {
            final int writer = t;
            final SplittableRandom own = random.split();
            tasks.add(() -> {
                try {
                    for (int i = 0; i < APPENDS_EACH; i++) {
                        final int slot = writer * APPENDS_EACH + i;
                        run.start.set(slot, run.clock.incrementAndGet());
                        Refused refused = null;
                        try {
                            target.append(contendedBatch(writer, i), 0L, own.nextInt(DAYS));
                        } catch (final Refused e) {
                            refused = e;
                        }
                        final long end = run.clock.incrementAndGet();
                        if (refused != null) {
                            // no serial order refuses a write: before a drop the drop takes it, after one it
                            // recreates the table
                            run.refused.set(slot, 1);
                            refusedAppends.incrementAndGet();
                            note(anomalies, "append refused [batch=" + contendedBatch(writer, i) + ", error="
                                    + refused.getMessage() + ']');
                        }
                        run.end.set(slot, end);
                        if (answered.incrementAndGet() % DROPPING_PACE == 0) {
                            paced.release();
                        }
                    }
                } finally {
                    // a writer that stops early must not hold the destroyer back
                    paced.release(DROPPING_DESTROYS);
                    if (changersLeft.decrementAndGet() == 0) {
                        stop.set(true);
                    }
                }
            });
        }
        final SplittableRandom destroyer = random.split();
        tasks.add(() -> {
            try {
                for (int j = 0; j < DROPPING_DESTROYS; j++) {
                    if (!paced.tryAcquire(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        note(anomalies, "writers stalled [destroy=" + j + ']');
                        return;
                    }
                    final int pick = destroyer.nextInt(4);
                    final String sql = pick < 2 ? "TRUNCATE TABLE " + TABLE
                            : pick == 2 ? "DROP TABLE " + TABLE : "DROP TABLE IF EXISTS " + TABLE;
                    // counted before its start ticket, so a reader that counts it after its own end ticket sees it
                    run.destroys.set(j + 1);
                    final long begin = run.clock.incrementAndGet();
                    run.destroyStart.set(j, begin);
                    try {
                        final long count = target.execute(sql);
                        if (count < 0L || count % ROWS != 0L || pick > 1 && count != 0L) {
                            note(anomalies, "torn or wrong update count [sql=" + sql + ", count=" + count + ']');
                        }
                        truncated.addAndGet(pick < 2 ? count : 0L);
                    } catch (final Refused e) {
                        // a refusal says the table was absent: no batch may have been bound to be there
                        final String bound = run.check(null, begin, j);
                        if (pick == 3 || bound != null) {
                            note(anomalies, "refused [sql=" + sql + ", error=" + e.getMessage() + ", " + bound + ']');
                        }
                    }
                    run.destroyEnd.set(j, run.clock.incrementAndGet());
                }
            } finally {
                if (changersLeft.decrementAndGet() == 0) {
                    stop.set(true);
                }
            }
        });
        for (int r = 0; r < READERS; r++) {
            tasks.add(() -> {
                while (!stop.get()) {
                    final long begin = run.clock.incrementAndGet();
                    Map<Long, Long> counts;
                    try {
                        counts = target.counts();
                    } catch (final Refused _) {
                        counts = null;
                        refusedReads.incrementAndGet();
                    }
                    reads.incrementAndGet();
                    final String bad = run.check(counts, begin, run.destroys.get());
                    if (bad != null) {
                        note(anomalies, bad);
                    }
                }
            });
        }
        for (final String bad : race("destructive-dropping", tasks)) {
            note(anomalies, bad);
        }
        final long begin = run.clock.incrementAndGet();
        Map<Long, Long> end;
        try {
            end = target.counts();
        } catch (final Refused _) {
            end = null;
        }
        final String bad = run.check(end, begin, run.destroys.get());
        if (bad != null) {
            note(anomalies, "at the end: " + bad);
        }
        System.out.println("dropping schedule [reads=" + reads.get() + ", refusedReads=" + refusedReads.get()
                + ", refusedAppends=" + refusedAppends.get() + ", rowsTruncated=" + truncated.get() + ", batchesAtEnd="
                + (end == null ? 0 : end.size()) + ']');
        assertThat(anomalies).isEmpty();
        assertThat(reads.get()).as("reads checked").isPositive();
    }

    /// Runs the contended schedule against `target`: two writers of 100 batches each, one thread of 60 changes that
    /// spare the protected batches, `READERS` readers; checks every read and the end state.
    public static void contended(final Target target, final SplittableRandom random) throws Exception {
        final AtomicInteger[] done = new AtomicInteger[APPENDERS];
        for (int t = 0; t < APPENDERS; t++) {
            done[t] = new AtomicInteger();
        }
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicInteger changersLeft = new AtomicInteger(APPENDERS + 1);
        final Queue<String> anomalies = new ConcurrentLinkedQueue<>();
        final AtomicLong reads = new AtomicLong();
        target.append(PRE_BATCH, 0L, 0);
        final List<Task> tasks = new ArrayList<>();
        for (int t = 0; t < APPENDERS; t++) {
            final int writer = t;
            final SplittableRandom own = random.split();
            tasks.add(() -> {
                try {
                    for (int i = 0; i < APPENDS_EACH; i++) {
                        final long cls = i % 2;
                        final int day = cls == 0L ? i / 2 % 2 : own.nextInt(DAYS);
                        target.append(contendedBatch(writer, i), cls, day);
                        done[writer].set(i + 1);
                    }
                } finally {
                    if (changersLeft.decrementAndGet() == 0) {
                        stop.set(true);
                    }
                }
            });
        }
        final SplittableRandom destroyer = random.split();
        tasks.add(() -> {
            try {
                for (int j = 0; j < DESTROYS; j++) {
                    final String sql = destroy(destroyer);
                    try {
                        final long count = target.execute(sql);
                        if (count < 0L) {
                            note(anomalies, "negative update count [sql=" + sql + ", count=" + count + ']');
                        }
                    } catch (final Refused e) {
                        note(anomalies, "refused [sql=" + sql + ", error=" + e.getMessage() + ']');
                    }
                }
            } finally {
                if (changersLeft.decrementAndGet() == 0) {
                    stop.set(true);
                }
            }
        });
        for (int r = 0; r < READERS; r++) {
            tasks.add(() -> {
                while (!stop.get()) {
                    final int[] before = new int[APPENDERS];
                    for (int t = 0; t < APPENDERS; t++) {
                        before[t] = done[t].get();
                    }
                    try {
                        // checked as read: keeping every read held hundreds of MB under a loaded suite
                        final String bad = new Contended(before, target.counts()).check();
                        reads.incrementAndGet();
                        if (bad != null) {
                            note(anomalies, bad);
                        }
                    } catch (final Refused e) {
                        note(anomalies, "read refused [error=" + e.getMessage() + ']');
                    }
                }
            });
        }
        anomalies.addAll(race("destructive-contended", tasks));

        final Map<Long, Long> end = target.counts();
        for (int t = 0; t < APPENDERS; t++) {
            for (int i = 0; i < APPENDS_EACH; i += 2) {
                if (end.getOrDefault(contendedBatch(t, i), 0L) != ROWS) {
                    note(anomalies, "protected batch not whole at the end [batch=" + contendedBatch(t, i) + ", rows="
                            + end.get(contendedBatch(t, i)) + ']');
                }
            }
        }
        System.out.println("contended schedule [reads=" + reads.get() + ", batchesAtEnd=" + end.size() + ']');
        assertThat(anomalies).isEmpty();
        assertThat(reads.get()).as("reads checked").isPositive();
    }

    /// Runs `tasks` on named virtual threads released by one barrier, waits for all with a bound, and returns what
    /// went wrong: a task's exception, a timeout, a thread that would not stop.
    public static List<String> race(final String name, final List<Task> tasks) throws InterruptedException {
        final CyclicBarrier start = new CyclicBarrier(tasks.size());
        final CountDownLatch done = new CountDownLatch(tasks.size());
        final Queue<String> anomalies = new ConcurrentLinkedQueue<>();
        final List<Thread> threads = new ArrayList<>(tasks.size());
        boolean finished = false;
        try {
            for (int i = 0; i < tasks.size(); i++) {
                final Task task = tasks.get(i);
                threads.add(Thread.ofVirtual().name(name + "-test-" + i).start(() -> {
                    try {
                        start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        task.run();
                    } catch (final InterruptedException _) {
                        // the timeout below interrupted the run: stop, keeping the status
                        Thread.currentThread().interrupt();
                    } catch (final Exception | Error e) {
                        anomalies.add(Thread.currentThread().getName() + " failed: " + e);
                    } finally {
                        done.countDown();
                    }
                }));
            }
            finished = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            // an interrupt closes a FileChannel for every user, so only a run already timed out is interrupted
            if (!finished) {
                for (final Thread thread : threads) {
                    thread.interrupt();
                }
            }
            for (final Thread thread : threads) {
                if (!thread.join(Duration.ofSeconds(JOIN_SECONDS))) {
                    anomalies.add("thread did not stop [thread=" + thread.getName() + ']');
                }
            }
        }
        if (!finished) {
            anomalies.add("run timed out [seconds=" + TIMEOUT_SECONDS + ']');
        }
        return List.copyOf(anomalies);
    }

    /// The line-protocol timestamp of row `n` of `batch` on `day`: each batch in one day's partition, apart from
    /// every other batch.
    public static long micros(final long batch, final int day, final int n) {
        return BASE_MICROS + day * DAY_MICROS + batch % 1_000_000_000L * 16L + n;
    }

    // the table holds the model's rows, or is gone when the model dropped it
    private static void assertEnd(final Cdb cdb, final Model end) throws Exception {
        if (end.present()) {
            assertThat(new LocalTarget(cdb).rows()).as("rows at the end").isEqualTo(end.rows());
        } else {
            assertThat(cdb.tables()).as("tables at the end").doesNotContain(TABLE);
        }
    }

    // one serial read: the counts, or null with the refusal when the table is absent
    private static Observed read(final Target target, final long lo, final AtomicLong started) throws Exception {
        try {
            final Map<Long, Long> counts = target.counts();
            return new Observed(lo, started.get(), counts, null);
        } catch (final Refused e) {
            return new Observed(lo, started.get(), null, e.getMessage());
        }
    }

    // checks and drops the reads at the head of waiting whose window ends at or before published; a reader's windows
    // only move forward, so the head is always the first to settle
    private static void settle(
            final Queue<Observed> waiting,
            final long published,
            final AtomicReferenceArray<Snapshot> states,
            final AtomicReferenceArray<Snapshot> transients,
            final Queue<String> anomalies,
            final AtomicLong reads,
            final AtomicLong whileAbsent) {
        while (!waiting.isEmpty() && waiting.peek().hi() <= published) {
            final Observed read = waiting.remove();
            reads.incrementAndGet();
            if (read.counts() == null) {
                whileAbsent.incrementAndGet();
            }
            if (!read.matchesOneOf(states, transients)) {
                note(anomalies, "read matches no state in its window " + read);
            }
        }
    }

    // keeps the first MAX_ANOMALIES; a few more when writers race the size check, which bounds them all the same
    private static void note(final Queue<String> anomalies, final String anomaly) {
        if (anomalies.size() < MAX_ANOMALIES) {
            anomalies.add(anomaly);
        }
    }

    private static long contendedBatch(final int writer, final int i) {
        return (writer + 1) * 1_000_000L + i;
    }

    // a change the contended schedule may run: it touches class 1 and days 3 and 4 only, where no protected row lives
    private static String destroy(final SplittableRandom random) {
        final int pick = random.nextInt(100);
        if (pick < 35) {
            return "DELETE FROM " + TABLE + " WHERE c = 1";
        }
        if (pick < 55) {
            return "DELETE FROM " + TABLE + " WHERE c = 1 AND n = " + random.nextInt(ROWS);
        }
        if (pick < 70) {
            final long from = contendedBatch(random.nextInt(APPENDERS), random.nextInt(APPENDS_EACH));
            return "DELETE FROM " + TABLE + " WHERE c = 1 AND b >= " + from + " AND b < " + (from + 10);
        }
        return "ALTER TABLE " + TABLE + " DROP PARTITION WHERE timestamp >= '" + day(2 + random.nextInt(2)) + "'";
    }

    // the ISO timestamp of the start of day d
    private static String day(final int d) {
        return Instant.ofEpochSecond(TimeUnit.MICROSECONDS.toSeconds(BASE_MICROS + d * DAY_MICROS)).toString();
    }

    // the name of day d's partition, as a DAY partition is named
    private static String partition(final int d) {
        return day(d).substring(0, 10);
    }

    /// What a schedule drives: a `Cdb` in process, or a `CdbServer` over HTTP.
    public interface Target {

        /// Appends `batch`'s `ROWS` rows (`b`, `c`, `n` and the designated `timestamp`) to `TABLE` in one line-protocol
        /// commit, creating the table when it is absent.
        void append(long batch, long cls, int day) throws Exception;

        /// Runs a change; its update count. A refusal by the database is a `Refused`.
        long execute(String sql) throws Exception;

        /// `SELECT b, count() FROM TABLE`: each batch's rows in one snapshot.
        Map<Long, Long> counts() throws Exception;

        /// Every row, as `b * 16 + n`.
        Set<Long> rows() throws Exception;
    }

    /// A task of a race.
    @FunctionalInterface
    public interface Task {

        /// Runs the task.
        void run() throws Exception;
    }

    /// A statement the database refused, with its message as it came.
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        /// A refusal saying `message`, caused by `cause` (null over HTTP).
        public Refused(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /// The table as the serial schedule left it: absent, or its batches by id with their day and the mask of the
    /// rows left. Confined to the changing thread while the schedule runs.
    public static final class Model {

        private final TreeMap<Long, int[]> batches = new TreeMap<>();
        private boolean present;
        private long nextBatch;
        private int tableDrops;

        /// True while the table exists.
        public boolean present() {
            return present;
        }

        /// The rows, as `b * 16 + n`.
        public Set<Long> rows() {
            final Set<Long> rows = new HashSet<>();
            for (final Map.Entry<Long, int[]> batch : batches.entrySet()) {
                for (int n = 0; n < ROWS; n++) {
                    if ((batch.getValue()[1] & 1 << n) != 0) {
                        rows.add(batch.getKey() * 16L + n);
                    }
                }
            }
            return rows;
        }

        Snapshot snapshot() {
            if (!present) {
                return Snapshot.ABSENT;
            }
            final Map<Long, Long> counts = new HashMap<>();
            for (final Map.Entry<Long, int[]> batch : batches.entrySet()) {
                counts.put(batch.getKey(), (long) Integer.bitCount(batch.getValue()[1]));
            }
            return new Snapshot(true, Map.copyOf(counts));
        }

        // the next change, drawn from random and applied to the model; its run checks the target's answer
        Change next(final SplittableRandom random) {
            final int pick = random.nextInt(100);
            if (!present) {
                if (pick < 70) {
                    return append(random);
                }
                final String sql = switch (pick % 5) {
                    case 0 -> "DELETE FROM " + TABLE;
                    case 1 -> "TRUNCATE TABLE " + TABLE;
                    case 2 -> "ALTER TABLE " + TABLE + " DROP PARTITION LIST '" + partition(0) + "'";
                    case 3 -> "DROP TABLE " + TABLE;
                    default -> "DROP TABLE IF EXISTS " + TABLE;
                };
                return new Change(sql, sql.startsWith("DROP TABLE IF EXISTS") ? 0L : -1L);
            }
            if (pick < 50) {
                return append(random);
            }
            if (pick < 60) {
                final long from = random.nextLong(nextBatch + 1);
                final long to = from + 1 + random.nextInt(8);
                return new Change("DELETE FROM " + TABLE + " WHERE b >= " + from + " AND b < " + to,
                        remove(b -> b >= from && b < to, ~0));
            }
            if (pick < 66) {
                final int n = random.nextInt(ROWS);
                return new Change("DELETE FROM " + TABLE + " WHERE n = " + n, remove(_ -> true, 1 << n));
            }
            if (pick < 69) {
                return new Change("DELETE FROM " + TABLE, remove(_ -> true, ~0));
            }
            if (pick < 74) {
                return new Change("TRUNCATE TABLE " + TABLE, remove(_ -> true, ~0));
            }
            final boolean[] days = days();
            if (pick < 84) {
                return dropListed(random, days);
            }
            if (pick < 90) {
                final int before = 1 + random.nextInt(DAYS);
                long dropped = 0L;
                for (int d = 0; d < before; d++) {
                    dropped += days[d] ? 1 : 0;
                }
                batches.values().removeIf(batch -> batch[0] < before);
                return new Change("ALTER TABLE " + TABLE + " DROP PARTITION WHERE timestamp < '" + day(before) + "'",
                        dropped);
            }
            batches.clear();
            present = false;
            tableDrops++;
            return new Change(random.nextBoolean() ? "DROP TABLE " + TABLE : "DROP TABLE IF EXISTS " + TABLE, 0L);
        }

        private Change append(final SplittableRandom random) {
            final long batch = nextBatch++;
            final int day = random.nextInt(DAYS);
            batches.put(batch, new int[] {day, (1 << ROWS) - 1});
            present = true;
            return new Change(batch, day);
        }

        // DROP PARTITION LIST of one or two days that hold rows, from a random first day on; an append when none does
        private Change dropListed(final SplittableRandom random, final boolean[] days) {
            final List<Integer> listed = new ArrayList<>();
            final int first = random.nextInt(DAYS);
            for (int seen = 0; seen < DAYS && listed.size() < 2; seen++) {
                final int d = (first + seen) % DAYS;
                if (days[d] && (listed.isEmpty() || random.nextBoolean())) {
                    listed.add(d);
                }
            }
            if (listed.isEmpty()) {
                return append(random);
            }
            final StringBuilder sql = new StringBuilder("ALTER TABLE " + TABLE + " DROP PARTITION LIST ");
            for (int i = 0; i < listed.size(); i++) {
                sql.append(i == 0 ? "" : ", ").append('\'').append(partition(listed.get(i))).append('\'');
            }
            for (final int d : listed) {
                batches.values().removeIf(batch -> batch[0] == d);
            }
            return new Change(sql.toString(), listed.size());
        }

        // clears the rows in mask of every batch whose id passes, dropping the batches left empty; the rows removed
        private long remove(final LongPredicate passes, final int mask) {
            long removed = 0L;
            final List<Long> empty = new ArrayList<>();
            for (final Map.Entry<Long, int[]> batch : batches.entrySet()) {
                if (passes.test(batch.getKey())) {
                    final int[] value = batch.getValue();
                    removed += Integer.bitCount(value[1] & mask);
                    value[1] &= ~mask;
                    if (value[1] == 0) {
                        empty.add(batch.getKey());
                    }
                }
            }
            for (final Long batch : empty) {
                batches.remove(batch);
            }
            return removed;
        }

        // the days that hold a row, so the days that have a partition
        private boolean[] days() {
            final boolean[] days = new boolean[DAYS];
            for (final int[] batch : batches.values()) {
                days[batch[0]] = true;
            }
            return days;
        }
    }

    /// One change of the serial schedule: an append of `batch` on `day`, or `sql` expected to answer `expected` rows
    /// or partitions (-1: refused, the table being absent).
    record Change(String sql, long expected, long batch, int day) {

        Change(final String sql, final long expected) {
            this(sql, expected, -1L, -1);
        }

        Change(final long batch, final int day) {
            this(null, 0L, batch, day);
        }

        // runs the change; what went wrong, or null
        String run(final Target target) throws Exception {
            if (sql == null) {
                try {
                    target.append(batch, batch % 2, day);
                    return null;
                } catch (final Refused e) {
                    return "append refused [batch=" + batch + ", error=" + e.getMessage() + ']';
                }
            }
            try {
                final long count = target.execute(sql);
                return expected == count ? null
                        : "answered other than the model [sql=" + sql + ", expected=" + expected + ", count=" + count
                                + ']';
            } catch (final Refused e) {
                return expected < 0L ? null
                        : "refused [sql=" + sql + ", expected=" + expected + ", error=" + e.getMessage() + ']';
            }
        }
    }

    /// The table in one state of the model: absent, or each batch's row count.
    record Snapshot(boolean present, Map<Long, Long> counts) {

        static final Snapshot ABSENT = new Snapshot(false, Map.of());
        static final Snapshot EMPTY = new Snapshot(true, Map.of());

        boolean matches(final Map<Long, Long> seen) {
            return seen == null ? !present : present && counts.equals(seen);
        }
    }

    /// A serial read: the changes published before it, the changes started after it, and what it saw (null counts:
    /// refused with `error`).
    record Observed(long lo, long hi, Map<Long, Long> counts, String error) {

        // the state after a change from lo to hi, or what a change from lo + 1 to hi showed while it ran
        boolean matchesOneOf(final AtomicReferenceArray<Snapshot> states,
                final AtomicReferenceArray<Snapshot> transients) {
            for (long k = lo; k <= hi && k < states.length(); k++) {
                final Snapshot state = states.get((int) k);
                final Snapshot passing = transients.get((int) k);
                if (state != null && state.matches(counts) || k > lo && passing != null && passing.matches(counts)) {
                    return true;
                }
            }
            return false;
        }
    }

    /// The tickets of a dropping schedule, all from one clock: each batch's start (taken before its append) and end
    /// (after its answer), whether its append was refused, and each destroy's start and end. Destroys run one after
    /// another on one thread, counted in `destroys` before each takes its start ticket. A ticket of 0 is not taken yet.
    static final class Dropping {

        private final AtomicLong clock = new AtomicLong();
        private final AtomicLongArray start = new AtomicLongArray(APPENDERS * APPENDS_EACH);
        private final AtomicLongArray end = new AtomicLongArray(APPENDERS * APPENDS_EACH);
        private final AtomicIntegerArray refused = new AtomicIntegerArray(APPENDERS * APPENDS_EACH);
        private final AtomicInteger destroys = new AtomicInteger();
        private final AtomicLongArray destroyStart = new AtomicLongArray(DROPPING_DESTROYS);
        private final AtomicLongArray destroyEnd = new AtomicLongArray(DROPPING_DESTROYS);

        // what is wrong with a read that began at ticket begin and saw counts (null: refused), destroys 0 to n - 1
        // counted after it ended; null when nothing is
        String check(final Map<Long, Long> counts, final long begin, final int n) {
            // a batch that started after the last counted destroy had ended is bound to be in the read
            final long lastEnd = n == 0 ? 0L : destroyEnd.get(n - 1);
            // the newest destroy that ended before the read began removed every batch answered before it started
            long removedBefore = 0L;
            for (int j = n - 1; j >= 0; j--) {
                final long e = destroyEnd.get(j);
                if (e != 0L && e < begin) {
                    removedBefore = destroyStart.get(j);
                    break;
                }
            }
            // a batch that started after a batch in the read was answered committed after it, so no destroy between
            // them can have removed it alone
            long oldestSeen = Long.MAX_VALUE;
            if (counts != null) {
                for (final Map.Entry<Long, Long> batch : counts.entrySet()) {
                    final long id = batch.getKey();
                    final int writer = (int) (id / 1_000_000L) - 1;
                    final long i = id % 1_000_000L;
                    if (writer < 0 || writer >= APPENDERS || i >= APPENDS_EACH || batch.getValue() != ROWS) {
                        return "batch nobody wrote so [batch=" + id + ", rows=" + batch.getValue() + ']';
                    }
                    final long e = end.get(writer * APPENDS_EACH + (int) i);
                    if (e != 0L && e < removedBefore) {
                        return "batch survived a destroy [batch=" + id + ", destroyStart=" + removedBefore + ']';
                    }
                    if (e != 0L) {
                        oldestSeen = Math.min(oldestSeen, e);
                    }
                }
            }
            for (int slot = 0; slot < start.length(); slot++) {
                final long s = start.get(slot);
                final long e = end.get(slot);
                if (e == 0L || e > begin || refused.get(slot) != 0) {
                    continue;
                }
                final boolean bound = n == 0 || lastEnd != 0L && lastEnd < s;
                final long id = contendedBatch(slot / APPENDS_EACH, slot % APPENDS_EACH);
                if ((bound || s > oldestSeen) && (counts == null || !counts.containsKey(id))) {
                    final String what = counts == null ? "refused while a batch is bound to be there"
                            : bound ? "batch answered after the last destroy is missing"
                            : "read is no suffix of the commits";
                    return what + " [batch=" + id + ", destroys=" + n + ']';
                }
            }
            return null;
        }
    }

    /// A contended read: how many batches each writer had appended before it, and what it saw.
    record Contended(int[] before, Map<Long, Long> counts) {

        // what is wrong with the read, or null
        String check() {
            for (int t = 0; t < before.length; t++) {
                for (int i = 0; i < before[t]; i += 2) {
                    if (counts.getOrDefault(contendedBatch(t, i), 0L) != ROWS) {
                        return "protected batch not whole [batch=" + contendedBatch(t, i) + ", rows="
                                + counts.get(contendedBatch(t, i)) + ']';
                    }
                }
            }
            for (final Map.Entry<Long, Long> batch : counts.entrySet()) {
                final long id = batch.getKey();
                final long rows = batch.getValue();
                final int writer = (int) (id / 1_000_000L) - 1;
                final boolean known = id == PRE_BATCH
                        || writer >= 0 && writer < APPENDERS && id % 1_000_000L < APPENDS_EACH;
                if (!known || rows < 1L || rows > ROWS || id % 2 == 0L && rows != ROWS) {
                    return "batch nobody wrote so [batch=" + id + ", rows=" + rows + ']';
                }
            }
            return null;
        }
    }

    /// The in-process target: `Cdb.appender()` and `Cdb.run`, a `CdbException` taken as the refusal.
    static final class LocalTarget implements Target {

        private final Cdb cdb;

        LocalTarget(final Cdb cdb) {
            this.cdb = cdb;
        }

        @Override
        public void append(final long batch, final long cls, final int day) throws Refused {
            try (CdbAppender appender = cdb.appender()) {
                for (int n = 0; n < ROWS; n++) {
                    appender.table(TABLE).longColumn("b", batch).longColumn("c", cls).longColumn("n", n)
                            .at(micros(batch, day, n));
                }
                appender.flush();
            } catch (final CdbException e) {
                throw new Refused(e.getMessage(), e);
            }
        }

        @Override
        public long execute(final String sql) throws Refused {
            return query(sql).updateCount();
        }

        @Override
        public Map<Long, Long> counts() throws Refused {
            final CdbResult result = query("SELECT b, count() FROM " + TABLE);
            final Map<Long, Long> counts = new HashMap<>();
            for (int r = 0; r < result.rowCount(); r++) {
                counts.put(result.getLong(r, 0), result.getLong(r, 1));
            }
            return counts;
        }

        @Override
        public Set<Long> rows() throws Refused {
            final CdbResult result = query("SELECT b, n FROM " + TABLE);
            final Set<Long> rows = new HashSet<>();
            for (int r = 0; r < result.rowCount(); r++) {
                rows.add(result.getLong(r, 0) * 16L + result.getLong(r, 1));
            }
            return rows;
        }

        private CdbResult query(final String sql) throws Refused {
            try {
                return cdb.run(sql);
            } catch (final CdbException e) {
                throw new Refused(e.getMessage(), e);
            }
        }
    }
}
