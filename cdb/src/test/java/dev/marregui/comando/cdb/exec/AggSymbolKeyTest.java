package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbType;
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
/// U+E000 to U+FFFF, whose UTF-16 order differs from their UTF-8 byte order. The key table of one SYMBOL key, fed
/// vectors that carry dictionary keys, numbers the groups the hashed path numbers for the same texts, NULL, a second
/// dictionary, text rows and merges among them. Seed in the output; replay with `-Dcdb.symbolkey.seed=<seed>`.
class AggSymbolKeyTest {

    private static final long SEED = Long.getLong("cdb.symbolkey.seed", System.nanoTime());
    private static final long DAY = 86_400_000_000L;
    private static final long DAY0 = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli() * 1_000L;
    private static final long NOW = DAY0 + 10 * DAY;
    // over 65,536 rows a partition, so each partition makes at least two frames
    private static final int ROWS_PER_DAY = 100_000;
    private static final int[] POOL_SIZES = {1, 11};
    private static final int DENSE_ROWS = 10_000;
    private static final int DENSE_SYMBOLS = 300;
    private static final int PAGE_ROWS = 1_000;
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

    /// One SYMBOL key carried by key: 10,000 rows over 300 symbols first seen in shuffled order number their groups
    /// in first-seen order, with the row counts of the hashed path the same values take as text, and the dictionary
    /// resolves a value only when its group is made, not once per row.
    @Test
    void symbolKeysGroupAsTheirTextsWithoutResolvingEachRow() {
        final Random random = new Random(SEED ^ 0x5EED_0001L);
        final List<String> order = symbols(DENSE_SYMBOLS);
        Collections.shuffle(order, random);
        final String[] values = new String[DENSE_ROWS];
        for (int r = 0; r < values.length; r++) {
            values[r] = r < order.size() ? order.get(r) : order.get(random.nextInt(order.size()));
        }
        final Dictionary dictionary = new Dictionary(symbols(DENSE_SYMBOLS));
        final AggKeys dense = symbolKeys();
        final int[] denseGroups = groups(dense, values, dictionary, null);
        final int resolved = dictionary.resolved;
        final AggKeys hashed = symbolKeys();
        final int[] hashedGroups = groups(hashed, values, null, null);
        assertThat(denseGroups).isEqualTo(firstSeenGroups(values));
        assertThat(hashedGroups).isEqualTo(denseGroups);
        assertThat(counts(dense, denseGroups)).isEqualTo(counts(hashed, hashedGroups));
        assertThat(texts(dense)).isEqualTo(order);
        assertThat(resolved).isLessThanOrEqualTo(2 * DENSE_SYMBOLS);
    }

    /// NULL rows among rows carried by key form one group of their own, numbered where the first NULL is seen.
    @Test
    void nullSymbolFormsOneGroupBesideKeyedRows() {
        final Random random = new Random(SEED ^ 0x5EED_0002L);
        final List<String> names = symbols(40);
        final String[] values = new String[3_000];
        for (int r = 0; r < values.length; r++) {
            values[r] = r == 7 || random.nextInt(10) == 0 ? null : names.get(random.nextInt(names.size()));
        }
        final AggKeys dense = symbolKeys();
        final int[] grouped = groups(dense, values, new Dictionary(names), null);
        assertThat(grouped).isEqualTo(firstSeenGroups(values));
        assertThat(grouped).isEqualTo(groups(symbolKeys(), values, null, null));
        assertThat(texts(dense)).containsOnlyOnce((String) null);
        assertThat(texts(dense).get(grouped[7])).isNull();
    }

    /// Pages of two dictionaries that key the same values differently, and rows of the table's dictionary set as text
    /// before their key is first carried, group by text: each value one group, in first-seen order.
    @Test
    void twoDictionariesGroupByText() {
        final Random random = new Random(SEED ^ 0x5EED_0003L);
        final List<String> names = symbols(120);
        final String[] values = new String[6_000];
        for (int r = 0; r < values.length; r++) {
            values[r] = random.nextInt(25) == 0 ? null : names.get(random.nextInt(names.size()));
        }
        final Dictionary first = new Dictionary(names);
        final Dictionary second = new Dictionary(names.reversed());
        final AggKeys keys = symbolKeys();
        final int[] grouped = groups(keys, values, first, second);
        assertThat(grouped).isEqualTo(firstSeenGroups(values));
        assertThat(grouped).isEqualTo(groups(symbolKeys(), values, null, null));
        assertThat(keys.size()).isEqualTo(new HashSet<>(Arrays.asList(values)).size());
    }

    /// Two tables fed by key, one of the first half of the rows and one of the second, merged by group answer the
    /// table fed every row: the same groups in the same order; rows fed after the merge find the merged groups, among
    /// them the values only the second half holds. Both halves of one dictionary, and of two.
    @Test
    void mergeOfKeyedTablesAnswersOneTable() {
        final Random random = new Random(SEED ^ 0x5EED_0004L);
        final List<String> names = symbols(200);
        final String[] values = new String[8_000];
        for (int r = 0; r < values.length; r++) {
            // the first half draws from the first 150 names only
            final int bound = r < values.length / 2 ? 150 : names.size();
            values[r] = random.nextInt(50) == 0 ? null : names.get(random.nextInt(bound));
        }
        final String[] head = Arrays.copyOfRange(values, 0, values.length / 2);
        final String[] tail = Arrays.copyOfRange(values, values.length / 2, values.length);
        final Dictionary dictionary = new Dictionary(names);
        for (final Dictionary tailDictionary : List.of(dictionary, new Dictionary(names.reversed()))) {
            final AggKeys one = symbolKeys();
            groups(one, values, dictionary, null);
            final AggKeys merged = symbolKeys();
            groups(merged, head, dictionary, null);
            final AggKeys other = symbolKeys();
            groups(other, tail, tailDictionary, null);
            for (int g = 0; g < other.size(); g++) {
                merged.groupOf(other, g);
            }
            assertThat(texts(merged)).isEqualTo(texts(one));
            assertThat(groups(merged, values, tailDictionary, null))
                    .isEqualTo(groups(one, values, tailDictionary, null));
            assertThat(merged.size()).isEqualTo(one.size());
        }
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

    private static AggKeys symbolKeys() {
        return new AggKeys(List.of(CdbType.SYMBOL));
    }

    // the names s0 to s<n - 1>, in a list the caller may reorder
    private static List<String> symbols(final int n) {
        final List<String> names = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            names.add("s" + i);
        }
        return names;
    }

    // feeds values to keys in SYMBOL pages of PAGE_ROWS and answers each row's group. With no dictionary every value
    // is set as text. With one, every value is set by its key. With two, the odd pages are set by keys of other, and
    // in the even pages every third row too, after the page's other rows bound it to dictionary, so those rows hold
    // their value as text
    private static int[] groups(
            final AggKeys keys,
            final String[] values,
            final Dictionary dictionary,
            final Dictionary other
    ) {
        final int[] grouped = new int[values.length];
        final Vector[] columns = new Vector[1];
        for (int start = 0; start < values.length; start += PAGE_ROWS) {
            final int rows = Math.min(PAGE_ROWS, values.length - start);
            final boolean odd = (start / PAGE_ROWS & 1) == 1;
            final Vector page = Vector.of(CdbType.SYMBOL, rows);
            for (int pass = 0; pass < 2; pass++) {
                for (int r = 0; r < rows; r++) {
                    // the second pass sets the even pages' rows of other, once the first has bound the page
                    final boolean late = other != null && !odd && r % 3 == 0;
                    if (late != (pass == 1)) {
                        continue;
                    }
                    final Dictionary by = other != null && (odd || late) ? other : dictionary;
                    final String value = values[start + r];
                    if (value == null || by == null) {
                        page.setText(r, value);
                    } else {
                        page.setSymbol(r, by, by.keyOf(value));
                    }
                }
            }
            columns[0] = page;
            for (int r = 0; r < rows; r++) {
                grouped[start + r] = keys.groupOf(columns, r);
            }
        }
        return grouped;
    }

    // per row the index of its value, NULL among them, in the order the values are first seen
    private static int[] firstSeenGroups(final String[] values) {
        final Map<String, Integer> seen = new HashMap<>();
        final int[] grouped = new int[values.length];
        for (int r = 0; r < values.length; r++) {
            if (!seen.containsKey(values[r])) {
                seen.put(values[r], seen.size());
            }
            grouped[r] = seen.get(values[r]);
        }
        return grouped;
    }

    // per group of keys the number of rows in it
    private static long[] counts(final AggKeys keys, final int[] groups) {
        final long[] counts = new long[keys.size()];
        for (final int group : groups) {
            counts[group]++;
        }
        return counts;
    }

    // the key of every group of keys, in group order, null for the NULL group
    private static List<String> texts(final AggKeys keys) {
        final Vector out = Vector.of(CdbType.SYMBOL, keys.size());
        final List<String> texts = new ArrayList<>(keys.size());
        for (int g = 0; g < keys.size(); g++) {
            keys.write(g, 0, out, g);
            texts.add(out.textAt(g));
        }
        return texts;
    }

    /// A dictionary that keys its values densely from 0 in list order and counts how often it resolves a key.
    private static final class Dictionary implements SymbolDictionary {

        private final Map<String, Integer> keys = new HashMap<>();
        private final List<String> values;
        private int resolved;

        Dictionary(final List<String> values) {
            this.values = List.copyOf(values);
            for (int k = 0; k < this.values.size(); k++) {
                keys.put(this.values.get(k), k);
            }
        }

        @Override
        public String valueOf(final int key) {
            resolved++;
            return values.get(key);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public int keyOf(final String value) {
            final Integer key = keys.get(value);
            return key == null ? -1 : key;
        }
    }
}
