package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Executor;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;

import static org.assertj.core.api.Assertions.assertThat;

/// `ALTER TABLE DROP COLUMN` and `RENAME COLUMN` race a line-protocol writer, a writer through the store's
/// `TableWriter`, and readers: every read sees one whole definition and the rows of a committed state under it; a
/// snapshot held across changes reads the same definition and rows again; a line naming a dropped column adds it back,
/// as the line protocol creates any column not there yet, and never fails. Columns `b` and `n` say which batch and row
/// a row is; every other column's value follows from them, under whichever name the changes left it: `x` and `y` hold
/// `b * 10 + n`, `s` and `t` hold `s<b mod 5>`, or NULL where the column did not exist when the row was written. The
/// schedule comes from seeds `cdb.destructive.seed0` and `cdb.destructive.seed1`, printed on every run.
class ColumnChangeStressTest {

    private static final String TABLE = "c";
    private static final TableDef DEF = new TableDef(TABLE, List.of(
            new ColumnDef("b", CdbType.LONG),
            new ColumnDef("n", CdbType.LONG),
            new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("x", CdbType.LONG),
            new ColumnDef("timestamp", CdbType.TIMESTAMP)), 4, PartitionBy.DAY, List.of(), 0L);
    // the type each name may have: the line protocol re-adds x as LONG and s as SYMBOL, as the writers name them
    private static final Map<String, CdbType> TYPES = Map.of(
            "b", CdbType.LONG, "n", CdbType.LONG, "x", CdbType.LONG, "y", CdbType.LONG,
            "s", CdbType.SYMBOL, "t", CdbType.SYMBOL, "timestamp", CdbType.TIMESTAMP);
    private static final long BASE_MICROS = StoreRows.micros("2026-11-01T00:00:00Z");
    // rows per batch: enough that a torn commit or a torn rewrite shows as a partial batch
    private static final int ROWS = 3;
    // writer 0 writes through the line protocol, writer 1 through a TableWriter
    private static final int WRITERS = 2;
    // 120 batches each, 720 rows: every read reads them all, and a run stays in seconds on a laptop
    private static final int BATCHES = 120;
    // 40 changes, one each time the writers have written another 5 batches between them; 8 permits to spare
    private static final int CHANGES = 40;
    private static final int PACE = WRITERS * BATCHES / (CHANGES + 8);
    private static final int SPINNING_READERS = 3;
    // the executor's wait for a table's writer, which a change takes: a held lease longer than this is a leak
    private static final long WAIT_MILLIS = 30_000L;
    // the bound on one held read's wait for a change to finish under it
    private static final long HOLD_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final int MAX_ANOMALIES = 50;
    private static final String[] CHANGE_SQL = {
        "ALTER TABLE c RENAME COLUMN x TO y",
        "ALTER TABLE c RENAME COLUMN y TO x",
        "ALTER TABLE c DROP COLUMN IF EXISTS y",
        "ALTER TABLE c DROP COLUMN x",
        "ALTER TABLE c RENAME COLUMN s TO t",
        "ALTER TABLE c RENAME COLUMN t TO s",
        "ALTER TABLE c DROP COLUMN IF EXISTS t",
        "ALTER TABLE c DROP COLUMN s",
    };

    @TempDir
    private Path root;

    /// Two writers append 120 batches each, a changer runs 40 column changes paced by them, three readers check
    /// every snapshot they read and a fourth holds snapshots across changes; afterwards every batch is there and a
    /// column dropped and named again by the line protocol comes back empty but for the rows that named it.
    @Test
    void columnChangesKeepEverySnapshotWhole() throws Exception {
        final SplittableRandom random = DestructiveStressTest.random("ColumnChangeStressTest");
        try (final Store store = Store.open(root)) {
            store.create(DEF, false);
            final Run run = new Run(store);
            final List<DestructiveStressTest.Task> tasks = new ArrayList<>();
            tasks.add(() -> run.write(0));
            tasks.add(() -> run.write(1));
            tasks.add(() -> run.change(random));
            for (int i = 0; i < SPINNING_READERS; i++) {
                tasks.add(run::read);
            }
            tasks.add(run::hold);
            final List<String> raced = DestructiveStressTest.race("column-change", tasks);
            System.out.println("ColumnChangeStressTest [reads=" + run.reads.get() + ", held=" + run.held.get()
                    + ", applied=" + run.applied.get() + ", refused=" + run.refused.get() + ']');
            assertThat(raced).isEmpty();
            assertThat(run.anomalies).isEmpty();
            assertThat(run.reads.get()).as("reads checked").isPositive();
            assertThat(run.applied.get()).as("changes applied").isPositive();
            assertThat(run.held.get()).as("snapshots held across a change").isPositive();
            run.readAll();
            assertThat(run.anomalies).as("the read after the race").isEmpty();
            assertTheLineProtocolAddsADroppedColumnBack(store);
        }
    }

    // a dropped LONG and a dropped SYMBOL named by one line-protocol batch: both back, NULL on every older row, the
    // symbol's dictionary new
    private static void assertTheLineProtocolAddsADroppedColumnBack(final Store store) {
        execute(store, "ALTER TABLE c DROP COLUMN IF EXISTS x");
        execute(store, "ALTER TABLE c DROP COLUMN IF EXISTS s");
        assertThat(store.def(TABLE).columnIndex("x")).isNegative();
        assertThat(store.def(TABLE).columnIndex("s")).isNegative();
        final long batch = 9_000_000L;
        try (final CdbAppender ilp = store.appender()) {
            for (int n = 0; n < ROWS; n++) {
                ilp.table(TABLE).symbol("s", "fresh").longColumn("b", batch).longColumn("n", n)
                        .longColumn("x", batch * 10L + n).at(micros(batch, n));
            }
            ilp.flush();
        }
        try (final TableReader reader = store.reader(TABLE)) {
            final TableDef def = reader.def();
            assertThat(def.columns().get(def.columnIndex("x")).type()).isEqualTo(CdbType.LONG);
            assertThat(def.columns().get(def.columnIndex("s")).type()).isEqualTo(CdbType.SYMBOL);
            final int b = def.columnIndex("b");
            final int x = def.columnIndex("x");
            final int s = def.columnIndex("s");
            final List<String> rows = StoreRows.rows(reader);
            assertThat(rows).hasSize((WRITERS * BATCHES + 1) * ROWS);
            for (final String row : rows) {
                final String[] values = row.split("\\|", -1);
                if (Long.parseLong(values[b]) == batch) {
                    assertThat(values[s]).as(row).isEqualTo("\"fresh\"");
                    assertThat(values[x]).as(row).isNotEqualTo("null");
                } else {
                    assertThat(values[s]).as("an old row reads the dropped symbol's values: " + row).isEqualTo("null");
                    assertThat(values[x]).as("an old row reads the dropped column's values: " + row)
                            .isEqualTo("null");
                }
            }
        }
    }

    // runs a change as a client's SQL runs it; its update count
    private static long execute(final Store store, final String sql) {
        final long nowMicros = BASE_MICROS;
        return Executor.execute((Plan.Change) Planner.plan(Parser.parse(sql), store::def, nowMicros), store,
                nowMicros);
    }

    private static long batch(final int writer, final int i) {
        return writer * 100_000L + i;
    }

    // every row its own timestamp, the batches spread over two days
    private static long micros(final long batch, final int n) {
        return BASE_MICROS + (batch % 2L) * Retention.DAY_MICROS + (batch / 2L) * 10L + n;
    }

    // whether the definition explains the refusal of the change: a rename from an absent column or to an existing
    // one, a drop without IF EXISTS of an absent column
    private static boolean explains(final TableDef def, final String sql) {
        final String[] words = sql.split(" ");
        if (sql.contains("RENAME")) {
            return def.columnIndex(words[5]) < 0 || def.columnIndex(words[7]) >= 0;
        }
        return !sql.contains("IF EXISTS") && def.columnIndex(words[5]) < 0;
    }

    private static String names(final TableDef def) {
        final StringBuilder names = new StringBuilder();
        for (final ColumnDef column : def.columns()) {
            names.append(names.isEmpty() ? "" : " ").append(column.name()).append(':').append(column.type());
        }
        return names.toString();
    }

    /// One run's shared state: the writers' tickets, the changer's pace and the outcomes.
    private static final class Run {

        private final Store store;
        // one clock orders every batch, change and read
        private final AtomicLong clock = new AtomicLong();
        // per writer and batch: the tick before its first row, and the tick after its commit returned; 0 not yet
        private final AtomicLongArray started = new AtomicLongArray(WRITERS * BATCHES);
        private final AtomicLongArray answered = new AtomicLongArray(WRITERS * BATCHES);
        private final Semaphore pace = new Semaphore(0);
        // the writers and the changer still running; the readers stop at 0
        private final AtomicInteger running = new AtomicInteger(WRITERS + 1);
        private final AtomicLong changesDone = new AtomicLong();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger held = new AtomicInteger();
        private final AtomicInteger applied = new AtomicInteger();
        private final AtomicInteger refused = new AtomicInteger();
        private final Queue<String> anomalies = new ConcurrentLinkedQueue<>();

        Run(final Store store) {
            this.store = store;
        }

        void write(final int writer) {
            try {
                for (int i = 0; i < BATCHES; i++) {
                    final long batch = batch(writer, i);
                    started.set(writer * BATCHES + i, clock.incrementAndGet());
                    if (writer == 0) {
                        appendByLine(batch);
                    } else {
                        appendByWriter(batch);
                    }
                    answered.set(writer * BATCHES + i, clock.incrementAndGet());
                    if ((i + 1) % PACE == 0) {
                        pace.release();
                    }
                }
            } finally {
                running.decrementAndGet();
            }
        }

        // a refusal must be one the definition just before or just after explains: the line protocol may add a
        // column between the two
        void change(final SplittableRandom random) throws InterruptedException {
            try {
                for (int i = 0; i < CHANGES; i++) {
                    if (!pace.tryAcquire(DestructiveStressTest.TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        note("the writers stopped pacing the changes [change=" + i + ']');
                        return;
                    }
                    final String sql = CHANGE_SQL[random.nextInt(CHANGE_SQL.length)];
                    final TableDef before = store.def(TABLE);
                    try {
                        final long count = execute(store, sql);
                        applied.incrementAndGet();
                        if (count != 0L) {
                            note("a column change answered other than 0 [sql=" + sql + ", count=" + count + ']');
                        }
                    } catch (final CdbException e) {
                        refused.incrementAndGet();
                        if (!explains(before, sql) && !explains(store.def(TABLE), sql)) {
                            note("a column change was refused [sql=" + sql + ", before=" + names(before) + ", error="
                                    + e + ']');
                        }
                    } finally {
                        changesDone.incrementAndGet();
                    }
                }
            } finally {
                running.decrementAndGet();
            }
        }

        void read() {
            while (running.get() > 0) {
                readAll();
            }
        }

        // reads one snapshot, waits for a change to finish under it, and reads the same snapshot again
        void hold() {
            while (running.get() > 0) {
                try (final TableReader reader = store.reader(TABLE)) {
                    final TableDef def = reader.def();
                    final List<String> rows = StoreRows.rows(reader);
                    final long done = changesDone.get();
                    final long deadline = System.nanoTime() + HOLD_NANOS;
                    while (changesDone.get() == done && running.get() > 0 && System.nanoTime() - deadline < 0L) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
                    }
                    if (changesDone.get() == done) {
                        continue;
                    }
                    if (!reader.def().equals(def)) {
                        note("a held snapshot's definition changed [before=" + names(def) + ", after="
                                + names(reader.def()) + ']');
                    }
                    if (!StoreRows.rows(reader).equals(rows)) {
                        note("a held snapshot's rows changed [definition=" + names(def) + ']');
                    }
                    held.incrementAndGet();
                }
            }
        }

        // one snapshot judged: a definition the changes can give, whole batches, each writer's batches a prefix that
        // holds every batch answered before the read and none started after it, every value the one its name implies
        void readAll() {
            final long start = clock.incrementAndGet();
            final TableDef def;
            final List<String> rows;
            try (final TableReader reader = store.reader(TABLE)) {
                def = reader.def();
                rows = StoreRows.rows(reader);
            }
            final long end = clock.incrementAndGet();
            reads.incrementAndGet();
            final Set<String> names = new HashSet<>();
            for (final ColumnDef column : def.columns()) {
                if (!names.add(column.name().toLowerCase(Locale.ROOT)) || TYPES.get(column.name()) != column.type()) {
                    note("a read sees a definition no change gives [definition=" + names(def) + ']');
                    return;
                }
            }
            if (def.timestampIndex() < 0 || !"timestamp".equals(def.columns().get(def.timestampIndex()).name())
                    || def.columnIndex("b") < 0 || def.columnIndex("n") < 0) {
                note("a read lost a column no change touches [definition=" + names(def) + ']');
                return;
            }
            final Map<Long, Integer> counts = new HashMap<>();
            for (final String row : rows) {
                final String anomaly = judge(def, row);
                if (anomaly != null) {
                    note(anomaly);
                    return;
                }
                counts.merge(Long.parseLong(row.split("\\|", -1)[def.columnIndex("b")]), 1, Integer::sum);
            }
            for (final Map.Entry<Long, Integer> count : counts.entrySet()) {
                if (count.getValue() != ROWS) {
                    note("a read sees part of a batch [batch=" + count.getKey() + ", rows=" + count.getValue() + ']');
                }
            }
            for (int writer = 0; writer < WRITERS; writer++) {
                int prefix = 0;
                while (prefix < BATCHES && counts.containsKey(batch(writer, prefix))) {
                    prefix++;
                }
                for (int i = prefix; i < BATCHES; i++) {
                    if (counts.containsKey(batch(writer, i))) {
                        note("a read sees a batch after a missing one [writer=" + writer + ", batch=" + i
                                + ", missing=" + prefix + ']');
                        break;
                    }
                }
                final long missingAnswered = prefix < BATCHES ? answered.get(writer * BATCHES + prefix) : 0L;
                if (missingAnswered != 0L && missingAnswered < start) {
                    note("a read misses a batch answered before it began [writer=" + writer + ", batch=" + prefix
                            + ']');
                }
                final long lastStarted = prefix > 0 ? started.get(writer * BATCHES + prefix - 1) : 0L;
                if (prefix > 0 && (lastStarted == 0L || lastStarted > end)) {
                    note("a read sees a batch started after it ended [writer=" + writer + ", batch=" + (prefix - 1)
                            + ']');
                }
            }
        }

        // the line protocol names b, n, s and x on every line: a column a change dropped or renamed away comes back
        private void appendByLine(final long batch) {
            try (final CdbAppender ilp = store.appender()) {
                for (int n = 0; n < ROWS; n++) {
                    ilp.table(TABLE).symbol("s", "s" + batch % 5L).longColumn("b", batch).longColumn("n", n)
                            .longColumn("x", batch * 10L + n).at(micros(batch, n));
                }
                ilp.flush();
            } catch (final CdbException e) {
                note("the line protocol refused a batch [batch=" + batch + ", error=" + e + ']');
            }
        }

        // the TableWriter writes each column the definition under its lease has, by the name it has
        private void appendByWriter(final long batch) {
            try (final TableWriter writer = store.writer(TABLE, WAIT_MILLIS)) {
                final TableDef def = writer.def();
                for (int n = 0; n < ROWS; n++) {
                    writer.newRow(micros(batch, n));
                    writer.putLong(def.columnIndex("b"), batch);
                    writer.putLong(def.columnIndex("n"), n);
                    for (final String name : new String[] {"x", "y"}) {
                        if (def.columnIndex(name) >= 0) {
                            writer.putLong(def.columnIndex(name), batch * 10L + n);
                        }
                    }
                    for (final String name : new String[] {"s", "t"}) {
                        if (def.columnIndex(name) >= 0) {
                            writer.putText(def.columnIndex(name), "s" + batch % 5L);
                        }
                    }
                    writer.endRow();
                }
                writer.commit();
            } catch (final CdbException e) {
                note("the table writer refused a batch [batch=" + batch + ", error=" + e + ']');
            }
        }

        // the row's anomaly, or null: b and n set, every other value NULL or the one b and n imply
        private static String judge(final TableDef def, final String row) {
            final String[] values = row.split("\\|", -1);
            final int b = def.columnIndex("b");
            final int n = def.columnIndex("n");
            if ("null".equals(values[b]) || "null".equals(values[n])) {
                return "a read sees a row without its batch [row=" + row + ']';
            }
            final long batch = Long.parseLong(values[b]);
            final long x = batch * 10L + Long.parseLong(values[n]);
            final String symbol = "\"s" + batch % 5L + '"';
            for (int c = 0; c < def.columns().size(); c++) {
                final String name = def.columns().get(c).name();
                final boolean fits = switch (name) {
                    case "x", "y" -> "null".equals(values[c]) || Long.toString(x).equals(values[c]);
                    case "s", "t" -> "null".equals(values[c]) || symbol.equals(values[c]);
                    default -> true;
                };
                if (!fits) {
                    return "a read sees a value its column does not hold [column=" + name + ", row=" + row
                            + ", definition=" + names(def) + ']';
                }
            }
            return null;
        }

        private void note(final String anomaly) {
            if (anomalies.size() < MAX_ANOMALIES) {
                anomalies.add(anomaly);
            }
        }
    }
}
