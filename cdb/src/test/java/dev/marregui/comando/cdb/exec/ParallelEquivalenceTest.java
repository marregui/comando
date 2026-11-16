package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableWriter;

import static org.assertj.core.api.Assertions.assertThat;

/// A query answers the same bits on pools of 0, 1, 3 and 11 workers, and the serial operator's answer, but for the
/// last digits of a double sum or average: random tables of 1 to 4 daily partitions and up to 300,000 rows, every
/// column type, NULLs, duplicate timestamps and symbols, queried by random queries of every operator over a scan,
/// filtered or projected. Seeds `cdb.parallel.seed0` and `cdb.parallel.seed1`; `cdb.parallel.queries` queries
/// (200 by default).
class ParallelEquivalenceTest {

    private static final int QUERIES = Integer.getInteger("cdb.parallel.queries", 200);
    private static final int[] POOL_SIZES = {0, 1, 3, 11};
    private static final long DAY_MICROS = 86_400_000_000L;
    private static final long START_MICROS = 1_793_491_200_000_000L;
    // fixed, so a replayed seed plans the same now()
    private static final long NOW_MICROS = START_MICROS + 10 * DAY_MICROS;
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    private static final String[] TABLES = {"t0", "t1", "t2"};
    private static final int DIM_KEYS = 50;

    @TempDir
    static Path root;

    private static Store store;
    private static List<QueryPool> pools;
    private static TwoSeedRandom random;
    // per table: its days
    private static long[] spans;

    @BeforeAll
    static void writeTables() {
        random = TwoSeedRandom.of("cdb.parallel", "ParallelEquivalenceTest");
        pools = new ArrayList<>();
        for (final int size : POOL_SIZES) {
            pools.add(QueryPool.of(size));
        }
        store = Store.open(root);
        spans = new long[TABLES.length];
        for (int i = 0; i < TABLES.length; i++) {
            // the first table spans several frames, the others any size down to empty
            final int rows = switch (i) {
                case 0 -> random.between(2 * Frames.FRAME_ROWS + 1, 300_000);
                case 1 -> random.nextBoolean() ? random.nextInt(5_000) : random.nextInt(300_001);
                default -> random.nextInt(3_001);
            };
            final int days = random.between(1, 4);
            spans[i] = days;
            write(TABLES[i], rows, days);
        }
        writeDim();
    }

    @AfterAll
    static void closeAll() {
        RuntimeException failure = null;
        if (pools != null) {
            for (final QueryPool pool : pools) {
                try {
                    pool.close();
                } catch (final RuntimeException e) {
                    failure = add(failure, e);
                }
            }
        }
        if (store != null) {
            try {
                store.close();
            } catch (final RuntimeException e) {
                failure = add(failure, e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Every generated query, of every operator kind in turn, answers the same bits on every pool size, and the
    /// serial answer within the sum tolerance.
    @Test
    void everyOperatorAnswersTheSameBitsOnEveryPoolSize() {
        final int[] kinds = new int[Kind.values().length];
        for (int q = 0; q < QUERIES; q++) {
            final Kind kind = Kind.values()[q % Kind.values().length];
            final String sql = query(kind);
            check(sql);
            kinds[kind.ordinal()]++;
        }
        assertThat(kinds).as("queries per operator kind").doesNotContain(0);
    }

    /// LIMIT and ORDER BY ... LIMIT at the edges of a frame, and a GROUP BY over exactly the first frame, on the table
    /// that spans several frames.
    @Test
    void frameEdgesAnswerTheSameBits() {
        for (final int edge : new int[] {Frames.FRAME_ROWS - 1, Frames.FRAME_ROWS, Frames.FRAME_ROWS + 1}) {
            check("SELECT * FROM t0 LIMIT " + edge);
            check("SELECT * FROM t0 LIMIT " + (edge - 3) + ", " + (edge + 3));
            check("SELECT * FROM t0 LIMIT -" + edge);
            check("SELECT * FROM t0 ORDER BY n, ts DESC LIMIT " + edge);
        }
        check("SELECT sym, count() c, sum(p) sum_p, first(n) first_n, last(s) last_s FROM t0 GROUP BY sym");
    }

    private static void check(final String sql) {
        final Plan plan = ParallelRows.plan(store, sql, NOW_MICROS);
        final ParallelRows.Answer serial = ParallelRows.drain(SerialPlans.open(plan, store, NOW_MICROS));
        ParallelRows.Answer first = null;
        for (int i = 0; i < pools.size(); i++) {
            final QueryPool pool = pools.get(i);
            final ParallelRows.Answer answer = ParallelRows.drain(
                    Executor.open(plan, store, NOW_MICROS, new QueryContext(null, pool)));
            if (first == null) {
                first = answer;
                assertThat(answer.differenceFrom(serial, true))
                        .as("pool of %d against the serial answer: %s", pool.threads(), sql)
                        .isNull();
            } else {
                assertThat(answer.differenceFrom(first, false))
                        .as("pool of %d against the pool of %d: %s", pool.threads(), POOL_SIZES[0], sql)
                        .isNull();
            }
        }
    }

    /// The operator kinds the generator draws from.
    private enum Kind {
        GROUP_BY, SAMPLE_BY, LATEST_ON, DISTINCT, SORT, LIMIT, SET_OP, JOIN, WINDOW
    }

    private static String query(final Kind kind) {
        final int t = random.nextInt(10) < 6 ? 0 : random.between(1, 2);
        final String table = TABLES[t];
        return switch (kind) {
            case GROUP_BY -> groupBy(table, t);
            case SAMPLE_BY -> sampleBy(table, t);
            case LATEST_ON -> "SELECT * FROM " + table + where(t) + " LATEST ON ts PARTITION BY "
                    + pick("sym", "s", "sym, flag", "n", "flag");
            case DISTINCT -> "SELECT DISTINCT " + pick("sym", "sym, flag", "s", "n", "flag, sym, n") + " FROM " + table
                    + where(t);
            case SORT -> "SELECT * FROM " + table + where(t) + " ORDER BY "
                    + pick("n DESC, ts", "s, ts DESC", "sym, n", "d", "at DESC", "p, sym") + limit(true);
            case LIMIT -> "SELECT * FROM " + table + where(t) + limit(false);
            case SET_OP -> {
                final String columns = pick("sym, flag", "sym", "s", "n");
                final int other = random.nextInt(TABLES.length);
                yield "SELECT " + columns + " FROM " + table + where(t) + ' '
                        + pick("UNION", "UNION ALL", "EXCEPT", "INTERSECT") + " SELECT " + columns + " FROM "
                        + TABLES[other] + where(other);
            }
            case JOIN -> random.nextInt(4) == 0
                    ? "SELECT * FROM t2 CROSS JOIN dim"
                    : "SELECT " + table + ".ts, " + table + ".sym, " + table + ".n, " + table + ".p, dim.label, dim.w"
                            + " FROM " + table + ' ' + pick("JOIN", "LEFT JOIN") + " dim ON " + table
                            + ".sym = dim.sym";
            case WINDOW -> "SELECT ts, sym, n, " + pick(
                    "row_number() OVER (PARTITION BY sym ORDER BY ts DESC) rn",
                    "sum(n) OVER (PARTITION BY sym) lsum_w",
                    "avg(p) OVER (PARTITION BY flag ORDER BY ts) avg_w",
                    "lag(n) OVER (PARTITION BY s ORDER BY ts) lag_n",
                    "count() OVER (PARTITION BY sym) cw") + " FROM " + table + where(t);
        };
    }

    private static String groupBy(final String table, final int t) {
        final List<String> keys = new ArrayList<>();
        for (final String key : new String[] {"sym", "flag", "s", "n % 7 k7"}) {
            if (random.percent(30)) {
                keys.add(key);
            }
        }
        final List<String> select = new ArrayList<>(keys);
        final int aggs = random.between(1, 6);
        for (int i = 0; i < aggs; i++) {
            select.add(pick("count() c", "count(n) cn", "sum(n) lsum_n", "sum(d) sum_d", "sum(p) sum_p",
                    "avg(n) avg_n", "avg(p) avg_p", "min(d) min_d", "max(p) max_p", "min(ts) min_ts",
                    "max(at) max_at", "first(n) first_n", "last(s) last_s", "first(sym) first_sym", "last(d) last_d",
                    "count_distinct(s) cd_s", "count_distinct(n) cd_n", "approx_percentile(p, 0.9) ap_p",
                    "percentile_disc(n, 0.5) pd_n", "percentile_cont(p, 0.5) pc_p") + i);
        }
        final StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", select));
        if (random.percent(20)) {
            sql.append(" FROM (SELECT ts, sym, n, d * 2 d, p + 1 p, flag, s, at FROM ").append(table)
                    .append(where(t)).append(')');
        } else {
            sql.append(" FROM ").append(table).append(where(t));
        }
        if (!keys.isEmpty()) {
            final List<String> names = new ArrayList<>(keys.size());
            for (final String key : keys) {
                names.add(key.endsWith(" k7") ? "k7" : key);
            }
            sql.append(" GROUP BY ").append(String.join(", ", names));
        }
        return sql.toString();
    }

    private static String sampleBy(final String table, final int t) {
        final String keys = random.percent(30) ? "sym, " : "";
        return "SELECT ts, " + keys + "count() c, avg(p) avg_p, max(d) max_d, first(n) first_n, last(s) last_s,"
                + " sum(p) sum_p, min(at) min_at FROM " + table + where(t) + " SAMPLE BY "
                + pick("15m", "1h", "6h", "1d");
    }

    // a WHERE clause of table t, or none
    private static String where(final int t) {
        return switch (random.nextInt(7)) {
            case 0 -> " WHERE n > " + random.between(-1_000, 1_000);
            case 1 -> " WHERE sym = 's" + random.nextInt(DIM_KEYS) + '\'';
            case 2 -> {
                final long days = spans[t];
                final long fromMicros = START_MICROS + (long) (random.nextDouble() * days * DAY_MICROS);
                final long toMicros = fromMicros + (long) (random.nextDouble() * days * DAY_MICROS);
                yield " WHERE ts >= '" + iso(fromMicros) + "' AND ts < '" + iso(toMicros) + '\'';
            }
            case 3 -> " WHERE d > 0 OR flag";
            case 4 -> " WHERE s IS NOT NULL AND p < 500";
            default -> "";
        };
    }

    private static String limit(final boolean optional) {
        if (optional && random.nextBoolean()) {
            return "";
        }
        final int[] edges = {1, 10, 1_000, Frames.FRAME_ROWS - 1, Frames.FRAME_ROWS, Frames.FRAME_ROWS + 1};
        final int edge = edges[random.nextInt(edges.length)];
        return switch (random.nextInt(3)) {
            case 0 -> " LIMIT " + edge;
            case 1 -> " LIMIT -" + edge;
            default -> " LIMIT " + edge + ", " + (edge + random.nextInt(2_000));
        };
    }

    private static String pick(final String... choices) {
        return choices[random.nextInt(choices.length)];
    }

    private static String iso(final long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
                .toString();
    }

    // ts, sym, n, d, p, flag, s, at: rows spread evenly over the days, one in eight on the previous row's timestamp
    private static void write(final String table, final int rows, final int days) {
        store.create(new TableDef(table, List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("sym", CdbType.SYMBOL),
                new ColumnDef("n", CdbType.LONG),
                new ColumnDef("d", CdbType.DOUBLE),
                new ColumnDef("p", CdbType.DOUBLE),
                new ColumnDef("flag", CdbType.BOOLEAN),
                new ColumnDef("s", CdbType.VARCHAR),
                new ColumnDef("at", CdbType.TIMESTAMP)), 0, PartitionBy.DAY, List.of(), 0L), false);
        final int symbols = random.between(1, DIM_KEYS);
        final int texts = random.between(1, 500);
        final int nullPercent = random.nextInt(30);
        final long spanMicros = days * DAY_MICROS - 1;
        try (final TableWriter writer = store.writer(table, WAIT_MILLIS)) {
            long tsMicros = START_MICROS;
            for (int r = 0; r < rows; r++) {
                if (r == 0 || !random.percent(12)) {
                    tsMicros = START_MICROS + (long) ((double) r / rows * spanMicros);
                }
                writer.newRow(tsMicros);
                if (!random.percent(nullPercent)) {
                    writer.putText(1, "s" + random.nextInt(symbols));
                }
                if (!random.percent(nullPercent)) {
                    writer.putLong(2, random.between(-1_000, 1_000));
                }
                // eighths, so sums of d are exact in any order; NaN is the DOUBLE NULL
                writer.putDouble(3, random.percent(5) ? Double.NaN : random.between(-4_000, 4_000) / 8.0);
                if (!random.percent(nullPercent)) {
                    writer.putDouble(4, random.nextDouble() * 1_000);
                }
                writer.putBoolean(5, random.nextBoolean());
                if (!random.percent(nullPercent)) {
                    writer.putText(6, "v" + random.nextInt(texts));
                }
                if (!random.percent(nullPercent)) {
                    writer.putLong(7, START_MICROS + (long) (random.nextDouble() * spanMicros));
                }
                writer.endRow();
            }
            writer.commit();
        }
        System.out.println("table " + table + " [rows=" + rows + ", days=" + days + ", symbols=" + symbols + ']');
    }

    // one row per symbol key, the join's right side
    private static void writeDim() {
        store.create(new TableDef("dim", List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("sym", CdbType.SYMBOL),
                new ColumnDef("label", CdbType.VARCHAR),
                new ColumnDef("w", CdbType.DOUBLE)), 0, PartitionBy.NONE, List.of(), 0L), false);
        try (final TableWriter writer = store.writer("dim", WAIT_MILLIS)) {
            for (int k = 0; k < DIM_KEYS; k++) {
                writer.newRow(START_MICROS + k);
                writer.putText(1, "s" + k);
                writer.putText(2, "label" + k);
                writer.putDouble(3, k / 4.0);
                writer.endRow();
            }
            writer.commit();
        }
    }

    private static RuntimeException add(final RuntimeException failure, final RuntimeException e) {
        if (failure == null) {
            return e;
        }
        failure.addSuppressed(e);
        return failure;
    }
}
