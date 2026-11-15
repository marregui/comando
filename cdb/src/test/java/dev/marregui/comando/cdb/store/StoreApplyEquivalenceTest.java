package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// The applier's guarantee: a batch of queued commits publishes the same rows as the same commits applied one at a
/// time. Each seeded workload is committed to three stores:
///
/// - one commit at a time: every commit applied on its own thread before it returns (`applyOnCommit`)
/// - one batch: the applier paused for the whole workload, then one read
/// - random splits: the applier paused and resumed at random, lag-0 reads between
///
/// The table under test has a designated timestamp, dedup keys on it and a SYMBOL, a TTL, out-of-order rows, NULL and
/// empty texts; a second table has no designated timestamp and keeps the written order. The three stores read
/// row-for-row equal, live and after a reopen. The seed is printed; `-Dcdb.apply.seed=<seed>` replays it.
class StoreApplyEquivalenceTest {

    private static final long SEED = Long.getLong("cdb.apply.seed", System.nanoTime());
    // the suite's long run, as StorePowerCutTest's: 200 workloads instead of 20
    private static final boolean FULL = Boolean.getBoolean("cdb.powercut.full");
    private static final int WORKLOADS = FULL ? 200 : 20;
    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    private static final long MINUTE_MICROS = TimeUnit.MINUTES.toMicros(1L);
    private static final int TS = 0;
    private static final int SENSOR = 1;
    private static final int NOTE = 2;
    private static final int VALUE = 3;
    private static final int N = 4;
    private static final TableDef T = new TableDef("t", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("sensor", CdbType.SYMBOL),
            new ColumnDef("note", CdbType.VARCHAR),
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("n", CdbType.LONG)), TS, PartitionBy.DAY, List.of("ts", "sensor"),
            TimeUnit.DAYS.toMicros(2L));
    private static final TableDef P = new TableDef("p", List.of(
            new ColumnDef("sensor", CdbType.SYMBOL),
            new ColumnDef("note", CdbType.VARCHAR),
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("n", CdbType.LONG)), -1, PartitionBy.NONE, List.of(), 0L);
    private static final String[] SENSORS = {"a", "b", "c", "d"};
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;

    /// Every seeded workload reads the same in the three stores, live and reopened; the table without a designated
    /// timestamp reads exactly the rows written, in the order written; across the workloads, dedup and the TTL drop
    /// rows, so the equality covers them.
    @Test
    void batchesReadTheSameAsOneCommitAtATime(@TempDir final Path dir) {
        System.out.println("StoreApplyEquivalenceTest seed=" + SEED + ", workloads=" + WORKLOADS);
        long written = 0L;
        long kept = 0L;
        for (int w = 0; w < WORKLOADS; w++) {
            final long seed = mix(SEED * 1_000_003L + w);
            final String as = "seed=" + SEED + ", workload=" + w + ", workloadSeed=" + seed;
            final List<List<Row>> workload = workload(new Random(seed));
            final Path base = dir.resolve("w" + w);
            final Path one = base.resolve("one");
            final Path batch = base.resolve("batch");
            final Path split = base.resolve("split");
            final List<String> expectedT;
            final List<String> expectedP;
            try (final Store store = Store.open(one)) {
                create(store);
                store.applyOnCommit = true;
                for (final List<Row> commit : workload) {
                    commit(store, commit);
                }
                expectedT = read(store, T);
                expectedP = read(store, P);
            }
            assertThat(expectedP).as("no-timestamp table, written order [%s]", as).isEqualTo(written(workload));
            try (final Store store = Store.open(batch)) {
                create(store);
                store.applierPaused = true;
                for (final List<Row> commit : workload) {
                    commit(store, commit);
                }
                store.applierPaused = false;
                assertThat(read(store, T)).as("one batch, table t [%s]", as).isEqualTo(expectedT);
                assertThat(read(store, P)).as("one batch, table p [%s]", as).isEqualTo(expectedP);
                // the counters move right after the publish that woke the read, one write each: wait for the last the
                // assertions read, bounded by 30 s, StoreApplyTest's bound for the applier thread to act
                final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
                while (store.walStatus().stream().anyMatch(st -> st.appliedTxns() < workload.size())) {
                    if (System.nanoTime() - deadlineNanos > 0L) {
                        throw new AssertionError("batch counters not reached [" + as + ']');
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
                }
                for (final WalStatus status : store.walStatus()) {
                    assertThat(status.applies()).as("batches of table %s [%s]", status.table(), as).isEqualTo(1L);
                    assertThat(status.appliedTxns()).as("commits of table %s [%s]", status.table(), as)
                            .isEqualTo(workload.size());
                }
            }
            final Random splits = new Random(mix(seed));
            try (final Store store = Store.open(split)) {
                create(store);
                for (final List<Row> commit : workload) {
                    if (splits.nextInt(5) == 0) {
                        store.applierPaused = !store.applierPaused;
                    }
                    if (!store.applierPaused && splits.nextInt(6) == 0) {
                        read(store, splits.nextBoolean() ? T : P);
                    }
                    commit(store, commit);
                }
                store.applierPaused = false;
                assertThat(read(store, T)).as("random splits, table t [%s]", as).isEqualTo(expectedT);
                assertThat(read(store, P)).as("random splits, table p [%s]", as).isEqualTo(expectedP);
            }
            for (final Path root : List.of(one, batch, split)) {
                try (final Store store = Store.open(root)) {
                    assertThat(read(store, T)).as("reopened %s, table t [%s]", root.getFileName(), as)
                            .isEqualTo(expectedT);
                    assertThat(read(store, P)).as("reopened %s, table p [%s]", root.getFileName(), as)
                            .isEqualTo(expectedP);
                }
            }
            written += expectedP.size();
            kept += expectedT.size();
        }
        assertThat(kept).as("rows kept of those written, across the workloads [seed=%d]", SEED).isLessThan(written);
    }

    /// SplitMix64's finaliser: neighbouring inputs give unrelated seeds, so each workload differs
    /// (`java.util.Random`'s first draws barely move between seeds that differ by a few).
    private static long mix(final long x) {
        long z = x + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /// 30 to 59 commits of 1 to 6 rows over about four days: timestamps on a minute grid, a third of them up to a
    /// day and a half before the newest so far, one row in five reusing an earlier row's key with new values.
    private static List<List<Row>> workload(final Random random) {
        final int commits = 30 + random.nextInt(30);
        final List<List<Row>> workload = new ArrayList<>(commits);
        final List<Row> all = new ArrayList<>();
        long clockMicros = DAY1;
        for (int c = 0; c < commits; c++) {
            clockMicros += random.nextInt(240) * MINUTE_MICROS;
            final int n = 1 + random.nextInt(6);
            final List<Row> commit = new ArrayList<>(n);
            for (int r = 0; r < n; r++) {
                final long ts;
                final String sensor;
                if (!all.isEmpty() && random.nextInt(5) == 0) {
                    final Row earlier = all.get(random.nextInt(all.size()));
                    ts = earlier.ts();
                    sensor = earlier.sensor();
                } else {
                    ts = random.nextInt(3) == 0 ? clockMicros - random.nextInt(36 * 60) * MINUTE_MICROS
                            : clockMicros - random.nextInt(10) * MINUTE_MICROS;
                    sensor = random.nextInt(10) == 0 ? null : SENSORS[random.nextInt(SENSORS.length)];
                }
                final int kind = random.nextInt(10);
                final String note = kind < 2 ? null : kind == 2 ? "" : "n" + random.nextInt(1_000);
                final long value = random.nextInt(10) == 0 ? Long.MIN_VALUE : random.nextLong();
                final Row row = new Row(ts, sensor, note, random.nextInt(4_000) / 4.0, value);
                commit.add(row);
                all.add(row);
            }
            workload.add(commit);
        }
        return workload;
    }

    private static void create(final Store store) {
        store.create(T, false);
        store.create(P, false);
    }

    // one commit of the workload: its rows to t, then the same rows, without their timestamps, to p
    private static void commit(final Store store, final List<Row> rows) {
        try (final TableWriter writer = store.writer(T.name(), WAIT_MILLIS)) {
            for (final Row row : rows) {
                writer.newRow(row.ts());
                writer.putText(SENSOR, row.sensor());
                writer.putText(NOTE, row.note());
                writer.putDouble(VALUE, row.value());
                writer.putLong(N, row.n());
                writer.endRow();
            }
            writer.commit();
        }
        try (final TableWriter writer = store.writer(P.name(), WAIT_MILLIS)) {
            for (final Row row : rows) {
                writer.newRow(Long.MIN_VALUE);
                writer.putText(SENSOR - 1, row.sensor());
                writer.putText(NOTE - 1, row.note());
                writer.putDouble(VALUE - 1, row.value());
                writer.putLong(N - 1, row.n());
                writer.endRow();
            }
            writer.commit();
        }
    }

    // the rows of p as StoreRows prints them, in the order the workload wrote them
    private static List<String> written(final List<List<Row>> workload) {
        final List<String> out = new ArrayList<>();
        for (final List<Row> commit : workload) {
            for (final Row row : commit) {
                out.add(text(row.sensor()) + '|' + text(row.note()) + '|' + row.value() + '|'
                        + (row.n() == Long.MIN_VALUE ? "null" : Long.toString(row.n())));
            }
        }
        return out;
    }

    private static String text(final String value) {
        return value == null ? "null" : '"' + value + '"';
    }

    private static List<String> read(final Store store, final TableDef def) {
        try (final TableReader reader = store.reader(def.name())) {
            return rows(reader);
        }
    }

    /// One row of a workload; `n` is `Long.MIN_VALUE` for NULL.
    private record Row(long ts, String sensor, String note, double value, long n) {
    }
}
