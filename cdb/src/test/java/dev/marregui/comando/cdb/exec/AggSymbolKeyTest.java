package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static org.assertj.core.api.Assertions.assertThat;

/// Grouping by a SYMBOL column, whose pages may carry dictionary keys, answers what grouping by a VARCHAR column of the
/// same values answers, at pools of 1 and 11 threads: the same groups in the same first-seen order, the same counts,
/// distinct counts, minima, maxima, first and last values. The table holds three day partitions of 100,000 rows
/// each, so every partition is cut into several frames and the per-frame tables merge; keys are first seen in a later
/// frame and a later partition, NULL keys occur, and the values include a supplementary character and one from
/// U+E000 to U+FFFF, whose UTF-16 order differs from their UTF-8 byte order. Seed in the output; replay with
/// `-Dcdb.symbolkey.seed=<seed>`.
class AggSymbolKeyTest {

    private static final long SEED = Long.getLong("cdb.symbolkey.seed", System.nanoTime());
    private static final long DAY = 86_400_000_000L;
    private static final long DAY0 = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli() * 1_000L;
    private static final long NOW = DAY0 + 10 * DAY;
    // over 65,536 rows a partition, so each partition makes at least two frames
    private static final int ROWS_PER_DAY = 100_000;
    private static final int[] POOL_SIZES = {1, 11};
    private static final String[] KEYS = {
        "a", "b", "c", "d", "e", "f", "g", "h", "été", "日本", Character.toString(0x1D11E),
        Character.toString(0xE000), "zz"
    };

    @TempDir
    private static Path root;

    // per key, NULL among them, its rows in first-seen order: what GROUP BY k answers
    private static Map<String, Long> counts;
    private static List<QueryPool> pools;
    private static Store store;

    /// Opens the store and writes the table `t`, whose VARCHAR `v` holds the value of its SYMBOL `k` on every row.
    @BeforeAll
    static void fill() {
        System.out.println("AggSymbolKeyTest seed=" + SEED);
        store = Store.open(root);
        pools = new ArrayList<>();
        for (final int size : POOL_SIZES) {
            pools.add(QueryPool.of(size));
        }
        counts = new LinkedHashMap<>();
        run("CREATE TABLE t (ts TIMESTAMP, k SYMBOL, v VARCHAR, x LONG) TIMESTAMP(ts) PARTITION BY DAY");
        final Random random = new Random(SEED);
        try (CdbAppender appender = store.appender()) {
            String key = KEYS[0];
            for (int day = 0; day < 3; day++) {
                for (int i = 0; i < ROWS_PER_DAY; i++) {
                    if (random.nextInt(8) == 0) {
                        key = KEYS[random.nextInt(KEYS.length)];
                    }
                    String rowKey = key;
                    if (day == 0 && i >= 70_000 && random.nextInt(50) == 0) {
                        rowKey = "late";
                    } else if (day == 2 && i >= 90_000 && random.nextInt(20) == 0) {
                        rowKey = "tail";
                    } else if (random.nextInt(100) == 0) {
                        rowKey = null;
                    }
                    appender.table("t");
                    if (rowKey != null) {
                        appender.symbol("k", rowKey).stringColumn("v", rowKey);
                    }
                    appender.longColumn("x", random.nextInt(1_000));
                    appender.at(DAY0 + day * DAY + i * 500_000L);
                    counts.merge(rowKey == null ? "<null>" : rowKey, 1L, Long::sum);
                }
            }
            appender.flush();
        }
    }

    /// Closes the pools and the store.
    @AfterAll
    static void close() {
        for (final QueryPool pool : pools) {
            pool.close();
        }
        store.close();
    }

    /// GROUP BY the symbol answers each key with its row count, in first-seen order, at every pool size.
    @Test
    void groupBySymbolCountsEachKey() {
        final List<List<Object>> expected = new ArrayList<>();
        for (final Map.Entry<String, Long> entry : counts.entrySet()) {
            expected.add(List.of(entry.getKey(), entry.getValue()));
        }
        for (final QueryPool pool : pools) {
            final List<List<Object>> rows = new ArrayList<>();
            for (final List<Object> row : rows("SELECT k, count() FROM t GROUP BY k", pool)) {
                rows.add(List.of(row.get(0) == null ? "<null>" : row.get(0), row.get(1)));
            }
            assertThat(rows).as("threads=%d", pool.threads()).isEqualTo(expected);
        }
    }

    /// Every aggregate of a text, grouped by the symbol, answers as the same query grouped by the VARCHAR copy.
    @Test
    void groupBySymbolAnswersAsGroupByText() {
        alike("SELECT %s, count(), sum(x), count_distinct(%s), min(%s), max(%s), first(%s), last(%s) FROM t"
                + " GROUP BY %s");
        alike("SELECT %s, x % 3, count(), count_distinct(%s), last(%s) FROM t GROUP BY %s, x % 3");
        alike("SELECT x % 3, %s, count(), min(%s) FROM t GROUP BY x % 3, %s");
    }

    /// count_distinct of the symbol per numeric group answers as count_distinct of the VARCHAR copy.
    @Test
    void countDistinctSymbolAnswersAsText() {
        alike("SELECT x % 7, count_distinct(%s), min(%s), max(%s) FROM t GROUP BY x % 7");
        alike("SELECT count_distinct(%s), first(%s), last(%s) FROM t");
    }

    /// SAMPLE BY keyed by the symbol answers as SAMPLE BY keyed by the VARCHAR copy.
    @Test
    void sampleBySymbolAnswersAsText() {
        alike("SELECT ts, %s, count(), count_distinct(%s), max(%s) FROM t SAMPLE BY 6h");
    }

    private static void run(final String sql) {
        Executor.execute((Plan.Change) Planner.plan(Parser.parse(sql), store::def, NOW), store, NOW);
    }

    // the query with every %s the symbol k, then the varchar v: equal answers at every pool size, and the same answer
    // at each
    private static void alike(final String template) {
        final String bySymbol = template.replace("%s", "k");
        final String byText = template.replace("%s", "v");
        final List<List<Object>> first = rows(bySymbol, pools.getFirst());
        assertThat(first).as("%s", bySymbol).isNotEmpty();
        for (final QueryPool pool : pools) {
            assertThat(rows(bySymbol, pool)).as("%s threads=%d", bySymbol, pool.threads()).isEqualTo(first);
            assertThat(rows(byText, pool)).as("%s threads=%d", byText, pool.threads()).isEqualTo(first);
        }
    }

    // every row of the query at the pool, each cell its value: a text as its string, a double as its bits
    private static List<List<Object>> rows(final String sql, final QueryPool pool) {
        final Plan plan = Planner.plan(Parser.parse(sql), store::def, NOW);
        final List<List<Object>> out = new ArrayList<>();
        try (Cursor cursor = Executor.open(plan, store, NOW, new QueryContext(null, pool))) {
            Page page;
            while ((page = cursor.next()) != null) {
                for (int r = 0; r < page.size; r++) {
                    final List<Object> row = new ArrayList<>(page.columns.length);
                    for (final Vector v : page.columns) {
                        row.add(switch (v.type) {
                            case LONG, TIMESTAMP -> v.getLong(r);
                            case DOUBLE -> Double.doubleToRawLongBits(v.getDouble(r));
                            case BOOLEAN -> v.getBoolean(r);
                            case SYMBOL, VARCHAR -> v.textAt(r);
                        });
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }
}
