package dev.marregui.comando.cdb.plan;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.catalog.Catalog;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Executor;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static org.assertj.core.api.Assertions.assertThat;

/// Random tables and random queries over them: every query answers the same rows in the same order with the scan's
/// terms, newest-first reads and sort limits as planned, and with all of them taken out of the plan. Seed in the
/// output; replay with `-Dcdb.scan.seed=<seed>`.
class ScanDifferentialTest {

    private static final long NOW = Times.parse("2026-11-15T12:00:00", 0);
    private static final long BASE = Times.parse("2026-11-15T00:00:00", 0);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String[] OPS = {"=", "!=", "<", "<=", ">", ">="};
    private static final int TABLES = 10;
    private static final int QUERIES = 60;

    @TempDir
    private Path root;

    private static String iso(final long micros) {
        return '\'' + ISO.format(Instant.EPOCH.plusNanos(micros * 1_000)) + '\'';
    }

    private static void run(final Store store, final String sql) {
        Executor.execute((Plan.Change) Planner.plan(Parser.parse(sql), store::def, NOW), store, NOW);
    }

    /// Every row of `plan` run on `store`, each cell as its raw value (NULL as its sentinel).
    private static List<List<Object>> rows(final Plan plan, final Store store) {
        final List<List<Object>> out = new ArrayList<>();
        try (Cursor cursor = Executor.open(plan, store, NOW)) {
            Page page;
            while ((page = cursor.next()) != null) {
                for (int r = 0; r < page.size; r++) {
                    final List<Object> row = new ArrayList<>(page.columns.length);
                    for (final Vector v : page.columns) {
                        row.add(switch (v.type) {
                            case LONG, TIMESTAMP -> v.getLong(r);
                            case DOUBLE -> v.getDouble(r);
                            case BOOLEAN -> v.getBoolean(r);
                            case SYMBOL, VARCHAR -> String.valueOf(v.textAt(r));
                        });
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    /// `plan` with no scan terms, every scan and LATEST ON in time order, and every sort unbounded.
    private static Plan plain(final Plan plan) {
        return switch (plan) {
            case Plan.Scan(final String table, final int[] columns, final long from, final long to,
                           final List<Plan.ScanTerm> _, final boolean _, final List<?> _) ->
                    new Plan.Scan(table, columns, from, to, List.of(), false, plan.fields());
            case Plan.Filter(final Plan child, final Bound predicate) -> new Plan.Filter(plain(child), predicate);
            case Plan.Project(final Plan child, final List<Bound> exprs, final List<?> _) ->
                    new Plan.Project(plain(child), exprs, plan.fields());
            case Plan.LatestOn(final Plan child, final int ts, final int[] partition, final boolean _) ->
                    new Plan.LatestOn(plain(child), ts, partition, false);
            case Plan.Sort(final Plan child, final List<Plan.SortKey> keys, final long _) ->
                    new Plan.Sort(plain(child), keys, -1);
            case Plan.Limit(final Plan child, final long lo, final long hi) -> new Plan.Limit(plain(child), lo, hi);
            case Plan.Window(final Plan child, final List<Bound.WindowCall> calls, final List<?> _) ->
                    new Plan.Window(plain(child), calls, plan.fields());
            case Plan.Source _, Plan.Aggregate _, Plan.SampleBy _, Plan.Distinct _, Plan.SetOp _, Plan.Join _,
                 Plan.CreateTable _, Plan.Update _, Plan.Insert _, Plan.AddColumns _, Plan.SetTtl _ -> plan;
        };
    }

    private static int termCount(final Plan plan) {
        return switch (plan) {
            case Plan.Scan(final String _, final int[] _, final long _, final long _,
                           final List<Plan.ScanTerm> terms, final boolean _, final List<?> _) -> terms.size();
            case Plan.Filter(final Plan child, final Bound _) -> termCount(child);
            case Plan.Project(final Plan child, final List<Bound> _, final List<?> _) -> termCount(child);
            case Plan.LatestOn(final Plan child, final int _, final int[] _, final boolean _) -> termCount(child);
            case Plan.Sort(final Plan child, final List<Plan.SortKey> _, final long _) -> termCount(child);
            case Plan.Limit(final Plan child, final long _, final long _) -> termCount(child);
            case Plan.Window(final Plan child, final List<Bound.WindowCall> _, final List<?> _) -> termCount(child);
            case Plan.Source _, Plan.Aggregate _, Plan.SampleBy _, Plan.Distinct _, Plan.SetOp _, Plan.Join _,
                 Plan.CreateTable _, Plan.Update _, Plan.Insert _, Plan.AddColumns _, Plan.SetTtl _ -> 0;
        };
    }

    private static String pick(final Random random, final String... options) {
        return options[random.nextInt(options.length)];
    }

    /// A table of random rows over a few hours, one partition an hour; timestamps strictly rising when `unique`, else
    /// with runs of equal ones. Returns its last timestamp.
    private static long table(final Store store, final String name, final Random random, final boolean unique) {
        run(store, "CREATE TABLE " + name + " (ts TIMESTAMP, x LONG, d DOUBLE, s SYMBOL, v VARCHAR, b BOOLEAN,"
                + " at TIMESTAMP) TIMESTAMP(ts) PARTITION BY HOUR");
        final int rows = 1 + random.nextInt(random.nextBoolean() ? 40 : 3000);
        final long step = 1 + random.nextInt(20_000_000);
        long ts = BASE + random.nextInt(1_000_000);
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            ts += unique ? 1 + random.nextLong(step) : random.nextInt(3) == 0 ? 0 : random.nextLong(step);
            sql.append(sql.isEmpty() ? "INSERT INTO " + name + " VALUES " : ", ").append('(').append(iso(ts))
                    .append(", ").append(random.nextInt(6) == 0 ? "NULL" : String.valueOf(random.nextInt(11) - 5))
                    .append(", ").append(pick(random, "NULL", "0.0", "-0.0", "1.5", "-2.5", "3.0", "0.5", "-4.0"))
                    .append(", ").append(pick(random, "NULL", "'a'", "'b'", "'c'"))
                    .append(", ").append(pick(random, "NULL", "'p'", "'q'"))
                    .append(", ").append(random.nextBoolean())
                    .append(", ").append(random.nextInt(4) == 0 ? "NULL" : iso(BASE + random.nextLong(10_800_000_000L)))
                    .append(')');
            if (sql.length() > 50_000 || i == rows - 1) {
                run(store, sql.toString());
                sql = new StringBuilder();
            }
        }
        return ts;
    }

    private static String term(final Random random, final long first, final long last) {
        final String op = pick(random, OPS);
        final long at = first + random.nextLong(Math.max(1, last - first + 2));
        return switch (random.nextInt(14)) {
            case 0 -> "x " + op + ' ' + (random.nextInt(13) - 6);
            case 1 -> (random.nextInt(13) - 6) + " " + op + " x";
            case 2 -> "d " + op + ' ' + pick(random, "1.5", "-2.5", "0.0", "-0.0", "2", "0", "3.0", "-1");
            case 3 -> "s " + pick(random, "=", "!=") + ' ' + pick(random, "'a'", "'b'", "'zz'", "NULL");
            case 4 -> "v " + pick(random, "=", "!=", "<", ">=") + ' ' + pick(random, "'p'", "'q'", "'zz'");
            case 5 -> "x BETWEEN " + (random.nextInt(13) - 6) + " AND " + (random.nextInt(13) - 6);
            case 6 -> "d BETWEEN " + pick(random, "-2.5", "0.5", "-0.0") + " AND " + pick(random, "3.0", "-1.5", "1");
            case 7 -> "ts " + op + ' ' + iso(at);
            case 8 -> "at " + op + ' ' + iso(at);
            case 9 -> "(x " + op + " 1 OR s = 'a')";
            case 10 -> "x * 2 " + op + " 2";
            case 11 -> "x " + op + " NULL";
            case 12 -> "b = " + random.nextBoolean();
            default -> "ts BETWEEN " + iso(at) + " AND " + iso(first + random.nextLong(Math.max(1, last - first + 2)));
        };
    }

    private static String query(final Random random, final String table, final boolean unique, final long last) {
        final StringBuilder sql = new StringBuilder("SELECT ")
                .append(pick(random, "*", "x, d, s", "ts, v, at"))
                .append(" FROM ").append(table);
        final int terms = random.nextInt(4);
        for (int i = 0; i < terms; i++) {
            sql.append(i == 0 ? " WHERE " : " AND ").append(term(random, BASE, last));
        }
        final int tail = random.nextInt(unique ? 4 : 3);
        if (tail == 1) {
            sql.append(" ORDER BY ").append(pick(random, "x", "x DESC", "d, x", "s DESC"));
            sql.append(random.nextBoolean() ? " LIMIT " + random.nextInt(50)
                    : " LIMIT " + random.nextInt(10) + ", " + random.nextInt(60));
        } else if (tail == 2) {
            sql.append(" LIMIT ").append(random.nextBoolean() ? random.nextInt(40) : -1 - random.nextInt(40));
        } else if (tail == 3) {
            // equal timestamps pick a row per order of reading until LATEST ON reads newest first, so only on unique
            sql.replace(0, sql.indexOf(" FROM "), "SELECT *");
            sql.append(" LATEST ON ts PARTITION BY ").append(pick(random, "s", "v", "s, v"));
        }
        return sql.toString();
    }

    /// 600 queries over 10 tables, half with equal timestamps: the planned plan and the plain one (planned without scan
    /// shortcuts, every filter kept, sorts unbounded) answer alike, and the
    /// planned plans do carry terms.
    @Test
    void termsAndNewestFirstNeverChangeAnAnswer() {
        final long seed = Long.getLong("cdb.scan.seed", System.nanoTime());
        System.out.println("termsAndNewestFirstNeverChangeAnAnswer seed=" + seed);
        final Random random = new Random(seed);
        int queries = 0;
        int withTerms = 0;
        int answered = 0;
        try (Store store = Store.open(root)) {
            for (int t = 0; t < TABLES; t++) {
                final boolean unique = t % 2 == 0;
                final String name = "r" + t;
                final long last = table(store, name, random, unique);
                for (int q = 0; q < QUERIES; q++) {
                    final String sql = query(random, name, unique, last);
                    final Plan planned = Planner.plan(Parser.parse(sql), store::def, NOW);
                    final Plan reference = Planner.plan(Parser.parse(sql), store::def, NOW, Catalog::fields, false);
                    final List<List<Object>> want = rows(plain(reference), store);
                    assertThat(rows(planned, store)).as("seed=%d %s", seed, sql).isEqualTo(want);
                    queries++;
                    withTerms += termCount(planned) > 0 ? 1 : 0;
                    answered += want.isEmpty() ? 0 : 1;
                }
            }
        }
        System.out.println("termsAndNewestFirstNeverChangeAnAnswer queries=" + queries + " withTerms=" + withTerms
                + " answered=" + answered);
        assertThat(queries).isEqualTo(TABLES * QUERIES);
        assertThat(withTerms).isGreaterThan(queries / 4);
        assertThat(answered).isGreaterThan(queries / 4);
    }
}
