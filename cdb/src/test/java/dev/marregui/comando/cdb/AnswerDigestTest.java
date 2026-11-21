package dev.marregui.comando.cdb;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.exec.Executor;
import dev.marregui.comando.cdb.exec.QueryContext;
import dev.marregui.comando.cdb.exec.QueryPool;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static org.assertj.core.api.Assertions.assertThat;

/// The engine's answers, pinned: a seeded table of three daily partitions and 50,000 rows of every column type, with
/// NULLs and non-ASCII text (Latin-1, CJK, U+E000 to U+FFFF, supplementary characters, the empty string), and a
/// small table to join it with, written through SQL and the line-protocol appender. Each query's answer, every
/// value rendered as text through [CdbResult] in result order, hashes to the SHA-256 recorded here, on pools of 1, 2
/// and 11 workers. A change to the engine that changes any answer, or its order, fails here.
class AnswerDigestTest {

    private static final int[] POOL_SIZES = {1, 2, 11};
    private static final int ROWS = 50_000;
    private static final int DAYS = 3;
    private static final long DAY_MICROS = 86_400_000_000L;
    // a midnight, so the rows fill three daily partitions
    private static final long START_MICROS = 1_793_491_200_000_000L;
    // fixed, so the plans do not depend on the clock
    private static final long NOW_MICROS = START_MICROS + 10 * DAY_MICROS;
    private static final long STEP_MICROS = DAYS * DAY_MICROS / ROWS;
    private static final String PUA = cp(0xE000);
    private static final String REPLACEMENT = cp(0xFFFD);
    private static final String SMILE = cp(0x1F600);
    private static final List<String> SYMBOLS = symbols();
    private static final List<String> TEXTS = texts();
    private static final List<Query> QUERIES = List.of(
            new Query("order-seq-limit", "SELECT * FROM t ORDER BY seq LIMIT 500", 0L,
                    "633ad699667f2a721b8076aad7b1a16437c4bc79a4065c8e2936a4d0a94f5f88"),
            new Query("bench-e", "SELECT * FROM t WHERE seq > 1000 ORDER BY seq LIMIT 500", 0L,
                    "6798e321c5afab899821dd57c11b8c31a7a9bf9a13039bab8c5eb2c9c04c68e7"),
            new Query("long-eq", "SELECT seq, n, s FROM t WHERE n = 7", 0L,
                    "825b450127399831a62f1ea8ecac5c56deee5c5c54c2615c989ea74544f41b2d"),
            new Query("long-ne-lt", "SELECT seq, n FROM t WHERE n != 0 AND n < -90", 0L,
                    "cdb66eeb171c4ad7a900d8674929b5b104d90a6e6e5968141c823096451abd03"),
            new Query("double-lt", "SELECT seq, d FROM t WHERE d < -240.5", 0L,
                    "28c5e884984021b0aa21c5633d49ffd9695469a2e7bff0c8ab11c57e9781b1e2"),
            new Query("double-eq-ne", "SELECT seq, d, sym FROM t WHERE d = 12.125 OR (d != 0.5 AND d > 249)", 0L,
                    "7795bc28bbeb3f82bc00f90577cad8e51ca0c10e8ab0d0117488f5681d470a46"),
            new Query("boolean", "SELECT seq, flag FROM t WHERE flag = false AND n = 3", 0L,
                    "ee3e5f943496bb5e072656e76faddf9785d259c16ed600ac371c3382b95ea783"),
            new Query("timestamp", "SELECT seq, ts, at FROM t WHERE ts < '" + iso(START_MICROS + DAY_MICROS / 3)
                    + "' AND at > '" + iso(START_MICROS + 2 * DAY_MICROS) + '\'', 0L,
                    "f07b63830128b6e1ad2752824434b43812bcfe89eb829064a38db2291449a217"),
            new Query("symbol-eq", "SELECT seq, sym, s FROM t WHERE sym = 'caf" + cp(0xE9) + '\'', 0L,
                    "9033f0824180a817d7f7731203b507fcc2a0c78ac44bfc4678e0b8bf8384c628"),
            new Query("symbol-ne-like", "SELECT seq, sym FROM t WHERE sym != 'k03' AND sym LIKE '%1%'", 0L,
                    "310371321930d158a2378e1062f6f3262aa2215abeb575f93c5564ad7123d570"),
            new Query("symbol-null", "SELECT seq, sym, s FROM t WHERE sym IS NULL OR s IS NULL", 0L,
                    "a1098524e59b9eb5bb939eb4904c65b29b9adadd15fa65a4f69079631c6fe722"),
            new Query("varchar-eq", "SELECT seq, s FROM t WHERE s = 'x" + REPLACEMENT + "' OR s = ''", 0L,
                    "8219a47ce37fe113489dc29927de2cc2ac98ae76728d12daef136526673d2c38"),
            new Query("varchar-like", "SELECT seq, s, sym FROM t WHERE s LIKE 'v1%' OR s LIKE '%" + cp(0x6587)
                    + "%'", 0L,
                    "49d6ee60e87c8e53303049dfd903b45a7624d3be5e5581c3699a0fe087c5704a"),
            new Query("varchar-lt-ne", "SELECT seq, s FROM t WHERE s < 'x" + PUA + "' AND s != 'v7'", 0L,
                    "8ea69b70c0c88d6038eddac4834243bf5a4bd51b0d00c3c014e557fa41c9b2a3"),
            new Query("varchar-gt", "SELECT seq, s FROM t WHERE s > 'x" + SMILE + '\'', 0L,
                    "4f3c6afaeb93a04c62c41d031842a3db42a1f837d26e8bffed854e2bc5c1b246"),
            new Query("group-symbol", "SELECT sym, " + aggregates() + " FROM t GROUP BY sym", 0L,
                    "f66c09383cabfd4fe04b2d299724c261941825f57a329ae0d2a0a9db31d09a38"),
            new Query("group-varchar", "SELECT s, " + aggregates() + " FROM t GROUP BY s", 0L,
                    "f5d5962e81b3bf381d1251b83a1846855a757981beff4e969ab38e810de1203e"),
            new Query("group-two-keys", "SELECT sym, flag, count() c, sum(n) sum_n, last(s) last_s FROM t"
                    + " GROUP BY sym, flag", 0L,
                    "342352f92a607b8d508090c66e365820ad1b6e3d3b4e90dde4c488d644c1686d"),
            new Query("group-text-keys", "SELECT s, sym, count() c, min(d) min_d, first(seq) first_seq FROM t"
                    + " GROUP BY s, sym", 0L,
                    "167b25aee5a3811b56ff2bc173dc1c644939b4bd3bfdbc683f23ced60ee96df0"),
            new Query("sample-by", "SELECT ts, count() c, avg(d) avg_d, first(n) first_n, last(s) last_s,"
                    + " last(sym) last_sym, max(at) max_at FROM t SAMPLE BY 1h", 0L,
                    "1d3d3277f0ae9c4307164c51a46cb8ec0e7ceb1c4eaea3d68fdff395063a254f"),
            new Query("sample-by-key", "SELECT ts, sym, count() c, sum(d) sum_d FROM t SAMPLE BY 1d", 0L,
                    "7a12ec8b49f94c05882e86a0bf8a21ce46f826d27b1e550b7c746288721137cc"),
            new Query("latest-symbol", "SELECT * FROM t LATEST ON ts PARTITION BY sym", 0L,
                    "831115c7a892f8f1bb23309047f887bc4b65572e213fc286273c72ff9d8d5af8"),
            new Query("latest-varchar", "SELECT * FROM t LATEST ON ts PARTITION BY s", 0L,
                    "bc15413f228ce59b84bf9b419d4af9af42e83f63b9247482b2cf172e5c62e878"),
            new Query("distinct", "SELECT DISTINCT sym, flag FROM t", 0L,
                    "3cf148e977d8228e38ac46e0cf1518c16cabedec28f387241a3226625cc17e41"),
            new Query("distinct-varchar", "SELECT DISTINCT s FROM t WHERE n > 0", 0L,
                    "542e7be63f8f3133a3ed6b02f4d49c804249f0ae5be71a15cd5c83e10278e0ff"),
            new Query("union", "SELECT sym FROM t WHERE n > 90 UNION SELECT sym FROM dim", 0L,
                    "c4b1b7d9ef260ca9606e9fa4db20fd530d334be251d98e23db15168b1c1050cd"),
            new Query("union-all", "SELECT s, sym FROM t WHERE n = 1 UNION ALL SELECT s, sym FROM dim", 0L,
                    "df4c8bf4e91a4113fdd82da126b919faa176baf9d62f5b1c2b028532b65ac558"),
            new Query("except", "SELECT s FROM t EXCEPT SELECT s FROM dim", 0L,
                    "e0cc16b295267a91fad20556a4b796822c8422c823d9f67543f8d56e9a9447f0"),
            new Query("intersect", "SELECT s, sym FROM t INTERSECT SELECT s, sym FROM dim", 0L,
                    "7ecad0e509d93c6540fbe38dfa0794480910c9fbac64487537d8c9de53a16c4f"),
            new Query("join-symbol", "SELECT t.seq, t.sym, t.n, dim.label, dim.w FROM t JOIN dim ON t.sym = dim.sym",
                    0L,
                    "8b98731d16df311e9d4c3ae8e7622ce12fc91b4fd34bf55395cb8c472a5342b3"),
            new Query("join-varchar", "SELECT t.seq, t.s, dim.label, dim.sym FROM t LEFT JOIN dim ON t.s = dim.s",
                    0L,
                    "0fe4e38faaaad699e80a32916697a9ccb90b65f12d0e5f30611634f7f477ff05"),
            new Query("window", "SELECT seq, sym, s, row_number() OVER (PARTITION BY sym ORDER BY ts DESC) rn,"
                    + " sum(n) OVER (PARTITION BY s) sum_w, lag(n) OVER (PARTITION BY s ORDER BY ts) lag_n,"
                    + " count() OVER (PARTITION BY flag) cw FROM t", 0L,
                    "41ad0fb27755374ec7858e65e0520f9959899653c396de0487472065d0824c0d"),
            new Query("order-long-ties", "SELECT seq, n, s FROM t ORDER BY n LIMIT 1000", 0L,
                    "8aee071e00fbce7bdb031b0172dfcdb08a32466f2a5a54db8f2ba5f2fbc1b64f"),
            new Query("order-long-desc", "SELECT seq, n, sym FROM t ORDER BY n DESC, seq LIMIT 777", 0L,
                    "5265982794952938c22b6ace77345edfa7df480fd45d6800b8733133bb96d2cb"),
            new Query("order-double", "SELECT seq, d FROM t ORDER BY d DESC LIMIT 600", 0L,
                    "c83d129e8d2fdffd61164a52c59e34894f9ff0094f8215ccf633d72a94464e64"),
            new Query("order-boolean", "SELECT seq, flag, n FROM t ORDER BY flag, n DESC LIMIT 900", 0L,
                    "7018281877fe8052ab5eaeff63d67ca47cbaf08c491ff2c7673cb17689641c2f"),
            new Query("order-timestamp", "SELECT seq, at FROM t ORDER BY at DESC LIMIT 400", 0L,
                    "f2cdc19cf03f0f60f52b7c5b3af7db9b4b975b3c63557df06da67a86f26a87c2"),
            new Query("order-ts", "SELECT * FROM t ORDER BY ts DESC LIMIT 300", 0L,
                    "654a6c2004207f7e94f75ad7b6b3e3dc1f3d568cfdf18d142e26a10e9d635d62"),
            new Query("order-symbol", "SELECT seq, sym FROM t ORDER BY sym LIMIT 2000", 0L,
                    "449d02e25165d174180cd92ae954e92896d32c9007e60b6be8de93ed1d934376"),
            new Query("order-symbol-desc", "SELECT seq, sym, s FROM t ORDER BY sym DESC, s LIMIT 1500", 0L,
                    "c9717ad839a40bba644c68b55163c9d21ff3658094686d8ac9e2e51f3d76d528"),
            new Query("order-varchar", "SELECT seq, s FROM t ORDER BY s", 0L,
                    "6ca4d4e76b19eb156eec353a1d7e7fb7df736d88ebb027daf4cbde74ffc1bc9d"),
            new Query("order-varchar-desc", "SELECT * FROM t ORDER BY s DESC LIMIT 500", 0L,
                    "61baf5c4af872300a4beb16fa313dc0e58564f2c9817ccdcffeb02898991aa1f"),
            new Query("limit-range", "SELECT * FROM t ORDER BY seq LIMIT 100, 600", 0L,
                    "8313098e0a743f81f524db339415a7c207d2c1eabb0b091bdb47cbff52449233"),
            new Query("offset", "SELECT seq, sym, s FROM t WHERE flag", 1_234L,
                    "846e58b629c73bc82dbf015cc2090b4ec022581358f72599b205e0361c5a0c76"));

    @TempDir
    static Path root;

    private static Store store;
    private static List<QueryPool> pools;

    @BeforeAll
    static void writeTables() {
        try (Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, seq LONG, n LONG, d DOUBLE, flag BOOLEAN, at TIMESTAMP,"
                    + " sym SYMBOL, s VARCHAR) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("CREATE TABLE dim (ts TIMESTAMP, sym SYMBOL, s VARCHAR, label VARCHAR, w DOUBLE) TIMESTAMP(ts)"
                    + " PARTITION BY DAY");
            try (CdbAppender appender = cdb.appender()) {
                for (int i = 0; i < ROWS; i++) {
                    appendRow(appender, i);
                    if (i % 5_000 == 4_999) {
                        appender.flush();
                    }
                }
                for (int k = 0; k < 48; k++) {
                    appender.table("dim")
                            .symbol("sym", SYMBOLS.get(k % SYMBOLS.size()))
                            .stringColumn("s", TEXTS.get(k * 3 % TEXTS.size()))
                            .stringColumn("label", "label" + k + TEXTS.get(TEXTS.size() - 1 - k % 16))
                            .doubleColumn("w", k / 4.0)
                            .at(START_MICROS + k);
                }
                appender.table("dim").doubleColumn("w", -1.0).at(START_MICROS + 48);
                appender.flush();
            }
            assertThat(cdb.run("SELECT count() FROM t").getLong(0, 0)).isEqualTo(ROWS);
        }
        store = Store.open(root);
        pools = new ArrayList<>();
        for (final int size : POOL_SIZES) {
            pools.add(QueryPool.of(size));
        }
    }

    @AfterAll
    static void closeAll() {
        try {
            if (pools != null) {
                for (final QueryPool pool : pools) {
                    pool.close();
                }
            }
        } finally {
            if (store != null) {
                store.close();
            }
        }
    }

    /// Every query hashes to its recorded digest on every pool size; the failure lists each query and pool whose
    /// digest differs, with the digest it gave.
    @Test
    void everyQueryGivesItsRecordedAnswerOnEveryPoolSize() {
        final List<String> differences = new ArrayList<>();
        for (final Query query : QUERIES) {
            for (final QueryPool pool : pools) {
                final CdbResult result = run(query, pool);
                final String digest = digest(result);
                System.out.println("digest [query=" + query.name() + ", pool=" + pool.threads() + ", rows="
                        + result.rowCount() + ", sha256=" + digest + ']');
                if (!digest.equals(query.digest())) {
                    differences.add(query.name() + " pool=" + pool.threads() + " sha256=" + digest);
                }
            }
        }
        assertThat(differences).isEmpty();
    }

    // ts strictly increasing over three days, seq unique; the other columns from a mix of the row number, with NULLs
    private static void appendRow(final CdbAppender appender, final int i) {
        final long h = mix(i);
        appender.table("t").longColumn("seq", i);
        if (h % 17 != 0) {
            appender.longColumn("n", (h >>> 8) % 201 - 100);
        }
        // eighths, so sums are exact in any order; an omitted DOUBLE is NULL (NaN)
        if ((h >>> 16) % 13 != 0) {
            appender.doubleColumn("d", ((h >>> 20) % 4_001 - 2_000) / 8.0);
        }
        appender.booleanColumn("flag", (h >>> 24 & 1L) == 1L);
        if ((h >>> 28) % 11 != 0) {
            appender.timestampColumn("at", START_MICROS + mix(i + ROWS) % (DAYS * DAY_MICROS));
        }
        if ((h >>> 36) % 19 != 0) {
            appender.symbol("sym", SYMBOLS.get((int) ((h >>> 40) % SYMBOLS.size())));
        }
        if ((h >>> 44) % 23 != 0) {
            appender.stringColumn("s", TEXTS.get((int) ((h >>> 48) % TEXTS.size())));
        }
        appender.at(START_MICROS + i * STEP_MICROS);
    }

    private static CdbResult run(final Query query, final QueryPool pool) {
        final Planner.Planned planned = Planner.planned(Parser.parse(query.sql()), store::def, NOW_MICROS);
        return CdbResult.drain(Executor.open(planned.plan(), store, NOW_MICROS, new QueryContext(null, pool)),
                query.offset(), 0, null);
    }

    // every column's name and type, then every cell in result order: text with its length, so no two answers render
    // alike, doubles by their bits
    private static String digest(final CdbResult result) {
        final MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("refuse a JDK without SHA-256", e);
        }
        final StringBuilder line = new StringBuilder();
        for (int c = 0; c < result.columnCount(); c++) {
            line.append(result.columnName(c)).append(':').append(result.type(c)).append('\t');
        }
        line.append(result.rowCount()).append('\n');
        sha.update(line.toString().getBytes(StandardCharsets.UTF_8));
        for (int r = 0; r < result.rowCount(); r++) {
            line.setLength(0);
            for (int c = 0; c < result.columnCount(); c++) {
                cell(result, r, c, line);
                line.append('\t');
            }
            line.append('\n');
            sha.update(line.toString().getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void cell(final CdbResult result, final int r, final int c, final StringBuilder line) {
        switch (result.type(c)) {
            case BOOLEAN -> line.append(result.getBoolean(r, c));
            case LONG, TIMESTAMP -> {
                if (result.isNull(r, c)) {
                    line.append("NULL");
                } else {
                    line.append(result.getLong(r, c));
                }
            }
            case DOUBLE -> line.append(Long.toHexString(Double.doubleToLongBits(result.getDouble(r, c))));
            case SYMBOL, VARCHAR, JSON -> {
                final String text = result.getText(r, c);
                if (text == null) {
                    line.append("NULL");
                } else {
                    line.append(text.length()).append(':').append(text);
                }
            }
        }
    }

    private static String aggregates() {
        return "count() c, count(n) cn, sum(n) sum_n, sum(d) sum_d, avg(n) avg_n, avg(d) avg_d, min(d) min_d,"
                + " max(d) max_d, min(ts) min_ts, max(at) max_at, first(n) first_n, last(s) last_s,"
                + " first(sym) first_sym, last(d) last_d, count_distinct(s) cd_s, count_distinct(n) cd_n,"
                + " approx_percentile(d, 0.9) ap_d, percentile_disc(n, 0.5) pd_n, percentile_cont(d, 0.5) pc_d";
    }

    // 32 ASCII values and 8 with Latin-1, CJK, private-use, U+FFFD and supplementary characters
    private static List<String> symbols() {
        final List<String> symbols = new ArrayList<>();
        for (int k = 0; k < 32; k++) {
            symbols.add(String.format("k%02d", k));
        }
        symbols.add("caf" + cp(0xE9));
        symbols.add("Stra" + cp(0xDF) + 'e');
        symbols.add(cp(0x65E5) + cp(0x672C));
        symbols.add(cp(0x6771) + cp(0x4EAC));
        symbols.add("k" + PUA);
        symbols.add("k" + REPLACEMENT);
        symbols.add("k" + SMILE);
        symbols.add(cp(0x10000) + "1");
        return List.copyOf(symbols);
    }

    // 120 ASCII values, the empty string, and values whose String.compareTo order differs from their UTF-8 byte order
    // (a char in U+E000 to U+FFFF against a supplementary one)
    private static List<String> texts() {
        final List<String> texts = new ArrayList<>();
        for (int k = 0; k < 120; k++) {
            texts.add("v" + k);
        }
        texts.add("");
        texts.add(cp(0xE9));
        texts.add(cp(0xFF));
        texts.add(cp(0xE0) + "bc");
        texts.add(cp(0x4E2D) + cp(0x6587));
        texts.add(cp(0x4E2D));
        texts.add(cp(0x6587) + cp(0x5B57));
        texts.add("x" + PUA);
        texts.add("x" + cp(0xF8FF));
        texts.add("x" + REPLACEMENT);
        texts.add("x" + cp(0xFFFF));
        texts.add("x" + SMILE);
        texts.add("x" + cp(0x10000));
        texts.add("x" + cp(0x10FFFF));
        texts.add(SMILE);
        texts.add(PUA);
        return List.copyOf(texts);
    }

    private static String cp(final int codePoint) {
        return Character.toString(codePoint);
    }

    private static String iso(final long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
                .toString();
    }

    // the splitmix64 finaliser, non-negative: the same values on every run, with no seed to replay
    private static long mix(final long i) {
        long z = i * 0x9E3779B97F4A7C15L;
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return (z ^ z >>> 31) >>> 1;
    }

    /// A query, the rows its result skips, and the SHA-256 of its answer at the code it was recorded on.
    private record Query(String name, String sql, long offset, String digest) {
    }
}
