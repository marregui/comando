package dev.marregui.comando.cdb.plan;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.TableDef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The plans of `DROP TABLE`, `TRUNCATE TABLE`, `DELETE`, `ALTER TABLE ... DROP PARTITION`, `EXPLAIN`,
/// `SHOW PARTITIONS` and `SHOW WAL`, parsed from SQL, and every refusal the planner makes for them.
class DestructivePlanTest {

    private static final long NOW = Times.parse("2026-11-14T12:00:00", 0);

    private static final Map<String, TableDef> TABLES = Map.of(
            "trades", table("trades", PartitionBy.DAY),
            "hourly", table("hourly", PartitionBy.HOUR),
            "weekly", table("weekly", PartitionBy.WEEK),
            "monthly", table("monthly", PartitionBy.MONTH),
            "yearly", table("yearly", PartitionBy.YEAR),
            "flat", table("flat", PartitionBy.NONE),
            "plain", new TableDef("plain", List.of(
                    new ColumnDef("ts", CdbType.TIMESTAMP),
                    new ColumnDef("qty", CdbType.LONG)), -1, PartitionBy.NONE, List.of(), 0)
    );

    /// `DROP TABLE` names the table as the store has it; a missing one is refused unless `IF EXISTS`, which keeps the
    /// name as written for the runner to find absent.
    @Test
    void dropTable() {
        assertThat(plan("DROP TABLE TRADES")).isEqualTo(new Plan.DropTable("trades", false));
        assertThat(plan("DROP TABLE IF EXISTS trades")).isEqualTo(new Plan.DropTable("trades", true));
        assertThat(plan("DROP TABLE IF EXISTS Nope")).isEqualTo(new Plan.DropTable("Nope", true));
        assertThatThrownBy(() -> plan("DROP TABLE nope"))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot find table [table=nope]");
    }

    /// `TRUNCATE TABLE` of a table that exists; a missing one is refused.
    @Test
    void truncate() {
        assertThat(plan("TRUNCATE TABLE Trades")).isEqualTo(new Plan.Truncate("trades"));
        assertThatThrownBy(() -> plan("TRUNCATE TABLE nope"))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot find table [table=nope]");
    }

    /// `DELETE` without a condition deletes every row; with one, the condition is bound over the table's columns. A
    /// table without designated timestamp can be deleted from.
    @Test
    void delete() {
        assertThat(plan("DELETE FROM trades")).isEqualTo(new Plan.Delete("trades", null));
        final Plan where = plan("DELETE FROM trades WHERE qty > 5 AND sym = 'a'");
        assertThat(where).isInstanceOfSatisfying(Plan.Delete.class, d -> {
            assertThat(d.table()).isEqualTo("trades");
            assertThat(d.where().type()).isEqualTo(CdbType.BOOLEAN);
        });
        assertThat(PlanText.of(where)).isEqualTo("""
                Delete trades where and(>($3, 5), =($1, 'a'))
                """);
        assertThat(plan("DELETE FROM plain WHERE qty = 1")).isInstanceOf(Plan.Delete.class);
    }

    /// `DELETE` refuses a missing table, a condition that is not BOOLEAN and a column the table lacks.
    @Test
    void deleteRefusals() {
        assertThatThrownBy(() -> plan("DELETE FROM nope"))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot find table [table=nope]");
        assertThatThrownBy(() -> plan("DELETE FROM trades WHERE qty"))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot delete by non-boolean condition [table=trades, type=LONG]");
        assertThatThrownBy(() -> plan("DELETE FROM trades WHERE missing = 1"))
                .isInstanceOf(CdbException.class);
    }

    /// A LIST name is read at the table's unit to its partition's range, one range per name in order.
    @Test
    void dropPartitionListPerUnit() {
        assertRanges(plan("ALTER TABLE hourly DROP PARTITION LIST '2026-11-14T10'"),
                "2026-11-14T10:00:00", "2026-11-14T11:00:00");
        assertRanges(plan("ALTER TABLE trades DROP PARTITION LIST '2026-11-14', '2026-02-28'"),
                "2026-11-14", "2026-11-15", "2026-02-28", "2026-03-01");
        assertRanges(plan("ALTER TABLE monthly DROP PARTITION LIST '2026-12'"), "2026-12", "2027-01");
        assertRanges(plan("ALTER TABLE yearly DROP PARTITION LIST '2026'"), "2026", "2027");
    }

    /// A WEEK name is the ISO week-based year and week, its range Monday to Monday: 2026-W46 starts on Monday
    /// 2026-11-09, 2026-W01 on 2025-12-29 (the week holding 2026's first Thursday), and 2026 has a 53rd week.
    @Test
    void dropPartitionListOfWeeks() {
        assertRanges(plan("ALTER TABLE weekly DROP PARTITION LIST '2026-W46', '2026-W01', '2026-W53'"),
                "2026-11-09", "2026-11-16", "2025-12-29", "2026-01-05", "2026-12-28", "2027-01-04");
    }

    /// A name of another shape than the table's unit, or of a date that does not exist, is refused.
    @Test
    void dropPartitionListRefusesOtherNames() {
        assertBadName("trades", "2026-10", PartitionBy.DAY);
        assertBadName("trades", "2026-13-01", PartitionBy.DAY);
        assertBadName("trades", "2026-02-30", PartitionBy.DAY);
        assertBadName("trades", "2026-11-00", PartitionBy.DAY);
        assertBadName("monthly", "2026-00", PartitionBy.MONTH);
        assertBadName("trades", "2026-11-14Z", PartitionBy.DAY);
        assertBadName("trades", "2026-11-14T10", PartitionBy.DAY);
        assertBadName("hourly", "2026-11-14T24", PartitionBy.HOUR);
        assertBadName("hourly", "2026-11-14 10", PartitionBy.HOUR);
        assertBadName("weekly", "2025-W53", PartitionBy.WEEK);
        assertBadName("weekly", "2026-W00", PartitionBy.WEEK);
        assertBadName("weekly", "2026-11-09", PartitionBy.WEEK);
        assertBadName("monthly", "2026-1", PartitionBy.MONTH);
        assertBadName("yearly", "26", PartitionBy.YEAR);
    }

    /// A WHERE on the designated timestamp is narrowed to one range: a bound, two ANDed, an interval, a BETWEEN, a
    /// bound from `now()`, and a comparison written with the column on the right.
    @Test
    void dropPartitionWhereRanges() {
        assertRange(plan("ALTER TABLE trades DROP PARTITION WHERE ts < '2026-11'"),
                Long.MIN_VALUE, at("2026-11"));
        assertRange(plan("ALTER TABLE trades DROP PARTITION WHERE ts >= '2026-10-01' AND ts < '2026-11-01'"),
                at("2026-10-01"), at("2026-11-01"));
        assertRange(plan("ALTER TABLE trades DROP PARTITION WHERE ts IN '2026-10'"),
                at("2026-10"), at("2026-11"));
        assertRange(plan("ALTER TABLE trades DROP PARTITION WHERE ts BETWEEN '2026-10-31' AND '2026-10-01'"),
                at("2026-10-01"), at("2026-10-31") + 1);
        assertRange(plan("ALTER TABLE trades DROP PARTITION WHERE ts < dateadd('d', -7, now())"),
                Long.MIN_VALUE, NOW - TimeUnit.DAYS.toMicros(7));
        assertRange(plan("ALTER TABLE trades DROP PARTITION WHERE '2026-11' > ts"),
                Long.MIN_VALUE, at("2026-11"));
        assertRange(plan("ALTER TABLE trades DROP PARTITION WHERE ts > '2026-11-01'"),
                at("2026-11-01") + 1, Long.MAX_VALUE);
    }

    /// Later than the last instant is nothing: the range is empty, not the whole table, as `value + 1` past
    /// `Long.MAX_VALUE` would make it. The last instant is reached as `now()` plus the micros left to it.
    @Test
    void dropPartitionAfterTheLastInstantIsEmpty() {
        final Plan p = plan("ALTER TABLE trades DROP PARTITION WHERE ts > now() + " + (Long.MAX_VALUE - NOW));
        assertThat(p).isInstanceOfSatisfying(Plan.DropPartitions.class,
                d -> assertThat(d.fromMicros()[0]).isGreaterThanOrEqualTo(d.toMicros()[0]));
    }

    /// DROP PARTITION refuses a missing table, a table without designated timestamp, a table not partitioned, a
    /// condition that is not BOOLEAN, one on other columns, and one on the timestamp that is not a time range.
    @Test
    void dropPartitionRefusals() {
        assertRefused("ALTER TABLE nope DROP PARTITION LIST '2026-10'", "cannot find table [table=nope]");
        assertRefused("ALTER TABLE plain DROP PARTITION WHERE ts < '2026-11'",
                "cannot drop partitions of a table without designated timestamp [table=plain]");
        assertRefused("ALTER TABLE flat DROP PARTITION LIST 'default'",
                "cannot drop partitions of a table not partitioned [table=flat]");
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE qty",
                "cannot drop partitions by non-boolean condition [table=trades, type=LONG]");
        final String others = "cannot drop partitions by a condition on other columns [table=trades]";
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE sym = 'a'", others);
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE ts < '2026-11' AND qty > 1", others);
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE ts < '2026-11' OR price > 1", others);
        final String notRange = "cannot drop partitions by a condition other than a time range [table=trades]";
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE ts != '2026-11-01'", notRange);
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE ts < '2026-10' OR ts > '2026-11'", notRange);
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE NOT ts < '2026-10'", notRange);
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE ts < '2026-11' AND true", notRange);
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE ts < ts", notRange);
        assertRefused("ALTER TABLE trades DROP PARTITION WHERE ts = NULL", notRange);
    }

    /// EXPLAIN plans its statement, a change included, without running it, under one VARCHAR field `QUERY PLAN`; a
    /// statement that cannot be planned cannot be explained.
    @Test
    void explain() {
        assertThat(plan("EXPLAIN SELECT sym FROM trades")).isInstanceOfSatisfying(Plan.Explain.class, e -> {
            assertThat(PlanText.of(e.plan())).isEqualTo(PlanText.of(plan("SELECT sym FROM trades")));
            assertThat(e.fields()).containsExactly(new Field("QUERY PLAN", CdbType.VARCHAR));
        });
        assertThat(plan("EXPLAIN TRUNCATE TABLE trades")).isEqualTo(new Plan.Explain(new Plan.Truncate("trades"),
                List.of(new Field("QUERY PLAN", CdbType.VARCHAR))));
        assertThat(PlanText.of(plan("EXPLAIN DELETE FROM trades"))).isEqualTo("""
                Explain
                  Delete trades
                """);
        assertRefused("EXPLAIN DROP TABLE nope", "cannot find table [table=nope]");
    }

    /// SHOW PARTITIONS reads `table_partitions` of the table as the store names it; SHOW WAL reads `show_wal` of every
    /// table or of one.
    @Test
    void showPartitionsAndWal() {
        assertThat(plan("SHOW PARTITIONS FROM TRADES")).isEqualTo(new Plan.Source("table_partitions",
                List.of("trades"), sourceFields("table_partitions", List.of("trades"))));
        assertThat(plan("SHOW WAL")).isEqualTo(new Plan.Source("show_wal", List.of(),
                sourceFields("show_wal", List.of())));
        assertThat(plan("SHOW WAL FROM Trades")).isEqualTo(new Plan.Source("show_wal", List.of("trades"),
                sourceFields("show_wal", List.of("trades"))));
        assertRefused("SHOW PARTITIONS FROM nope", "cannot find table [table=nope]");
        assertRefused("SHOW WAL FROM nope", "cannot find table [table=nope]");
    }

    private static TableDef table(final String name, final PartitionBy partitionBy) {
        return new TableDef(name, List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("sym", CdbType.SYMBOL),
                new ColumnDef("price", CdbType.DOUBLE),
                new ColumnDef("qty", CdbType.LONG)), 0, partitionBy, List.of(), 0);
    }

    /// Test stand-in for the catalog's listing fields.
    private static List<Field> sourceFields(final String function, final List<String> args) {
        return switch (function) {
            case "table_partitions" -> List.of(new Field("index", CdbType.LONG), new Field("name", CdbType.VARCHAR));
            case "show_wal" -> List.of(new Field("name", CdbType.VARCHAR), new Field("pendingRows", CdbType.LONG));
            default -> throw new IllegalArgumentException(
                    "cannot find source fields [function=" + function + ", args=" + args + ']');
        };
    }

    private static Plan plan(final String sql) {
        return Planner.plan(Parser.parse(sql), name -> TABLES.get(name.toLowerCase(Locale.ROOT)), NOW,
                DestructivePlanTest::sourceFields);
    }

    private static long at(final String text) {
        return Times.parse(text, 0);
    }

    /// `bounds` are pairs of instants, each the from and the to of one range of the plan, in order.
    private static void assertRanges(final Plan plan, final String... bounds) {
        assertThat(plan).isInstanceOfSatisfying(Plan.DropPartitions.class, d -> {
            final long[] from = new long[bounds.length / 2];
            final long[] to = new long[from.length];
            for (int i = 0; i < from.length; i++) {
                from[i] = at(bounds[2 * i]);
                to[i] = at(bounds[2 * i + 1]);
            }
            assertThat(d.fromMicros()).containsExactly(from);
            assertThat(d.toMicros()).containsExactly(to);
        });
    }

    private static void assertRange(final Plan plan, final long from, final long to) {
        assertThat(plan).isInstanceOfSatisfying(Plan.DropPartitions.class, d -> {
            assertThat(d.table()).isEqualTo("trades");
            assertThat(d.fromMicros()).containsExactly(from);
            assertThat(d.toMicros()).containsExactly(to);
        });
    }

    private static void assertBadName(final String table, final String name, final PartitionBy unit) {
        assertRefused("ALTER TABLE " + table + " DROP PARTITION LIST '" + name + "'",
                "cannot parse partition name [table=" + table + ", name=" + name + ", partitionBy=" + unit + ']');
    }

    private static void assertRefused(final String sql, final String message) {
        assertThatThrownBy(() -> plan(sql)).as(sql)
                .isInstanceOf(CdbException.class)
                .hasMessage(message);
    }
}
