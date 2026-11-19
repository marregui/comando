package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `x [NOT] IN (SELECT c …)` run: the rows the same statement with the subquery's values written out as an IN list
/// answers, NULLs and widened types included, in SELECT, UPDATE and DELETE; the subquery runs once, before the query's
/// first page; past a result's row limit it is refused; EXPLAIN shows it and never runs it.
class InSubqueryTest {

    private static final long NOW = 1_795_089_600_000_000L;
    // one row a day over four days, so the outer query reads several frames; the fourth row is NULL but for ts
    private static final String A_ROWS = "('2026-11-15T10:00:00.000000Z', 'x', 1, 1.0, 'x'),"
            + " ('2026-11-16T10:00:00.000000Z', 'y', 2, 2.0, 'y'),"
            + " ('2026-11-17T10:00:00.000000Z', 'z', 3, -0.0, 'z'),"
            + " ('2026-11-17T11:00:00.000000Z', NULL, NULL, NULL, NULL),"
            + " ('2026-11-18T10:00:00.000000Z', 'w', 4, 4.5, 'w')";
    // its first ts is a's second, its values repeat, and none is NULL
    private static final String B_ROWS = "('2026-11-16T10:00:00.000000Z', 'y', 2, 0.0, 'y'),"
            + " ('2026-11-16T11:00:00.000000Z', 'w', 4, 4.5, 'w'),"
            + " ('2026-11-16T12:00:00.000000Z', 'y', 2, 2.0, 'q')";

    @TempDir
    private Path root;

    /// IN keeps the rows whose value the subquery answers, NOT IN the others, a NULL operand among them, as the
    /// written-out list does.
    @Test
    void inAndNotInAnswerAsTheWrittenOutList() {
        try (Cdb cdb = tables()) {
            assertSameRows(cdb, "l IN (SELECT bl FROM b)", "l IN (2, 4, 2)", "2", "4");
            assertSameRows(cdb, "l NOT IN (SELECT bl FROM b)", "l NOT IN (2, 4, 2)", "1", "3", "");
        }
    }

    /// A NULL the subquery answers is in the set, so a NULL operand is found by IN and dropped by NOT IN, as with a
    /// NULL in the list.
    @Test
    void aNullInTheSetFindsTheNullOperand() {
        try (Cdb cdb = tables()) {
            cdb.run("CREATE TABLE n (ts TIMESTAMP, nl LONG) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("INSERT INTO n VALUES ('2026-11-16T10:00:00.000000Z', 1), ('2026-11-16T11:00:00.000000Z', NULL)");
            assertSameRows(cdb, "l IN (SELECT nl FROM n)", "l IN (1, NULL)", "1", "");
            assertSameRows(cdb, "l NOT IN (SELECT nl FROM n)", "l NOT IN (1, NULL)", "2", "3", "4");
        }
    }

    /// An empty subquery finds nothing for IN and keeps every row for NOT IN.
    @Test
    void anEmptySubqueryFindsNothing() {
        try (Cdb cdb = tables()) {
            assertThat(rows(cdb, "l IN (SELECT bl FROM b WHERE bl > 100)")).isEmpty();
            assertThat(rows(cdb, "l NOT IN (SELECT bl FROM b WHERE bl > 100)"))
                    .containsExactlyInAnyOrder("1", "2", "3", "", "4");
        }
    }

    /// The operand and the subquery's values meet in the type they widen to, as with a list: LONG and DOUBLE either
    /// way, -0.0 equal to 0.0, SYMBOL and VARCHAR either way, TIMESTAMP with TIMESTAMP.
    @Test
    void typesWidenAsTheWrittenOutList() {
        try (Cdb cdb = tables()) {
            assertSameRows(cdb, "l IN (SELECT bd FROM b)", "l IN (0.0, 4.5, 2.0)", "2");
            assertSameRows(cdb, "d IN (SELECT bl FROM b)", "d IN (2, 4, 2)", "2");
            assertSameRows(cdb, "d IN (SELECT bd FROM b)", "d IN (0.0, 4.5, 2.0)", "2", "3", "4");
            assertSameRows(cdb, "k IN (SELECT bs FROM b)", "k IN ('y', 'w', 'q')", "2", "4");
            assertSameRows(cdb, "s IN (SELECT bk FROM b)", "s IN ('y', 'w', 'y')", "2", "4");
            assertSameRows(cdb, "ts IN (SELECT ts FROM b)",
                    "ts IN ('2026-11-16T10:00:00.000000Z'::TIMESTAMP, '2026-11-16T11:00:00.000000Z'::TIMESTAMP)", "2");
        }
    }

    /// UPDATE and DELETE change the rows their IN subquery selects; a subquery over the changed table reads it as it
    /// was before the change.
    @Test
    void updateAndDeleteChangeTheRowsTheSubquerySelects() {
        try (Cdb cdb = tables()) {
            assertThat(cdb.run("UPDATE a SET d = 9.0 WHERE l IN (SELECT bl FROM b)").updateCount()).isEqualTo(2);
            assertThat(rows(cdb, "d = 9.0")).containsExactlyInAnyOrder("2", "4");
            assertThat(cdb.run("DELETE FROM a WHERE l IN (SELECT l FROM a WHERE l > 2)").updateCount()).isEqualTo(2);
            assertThat(rows(cdb, "true")).containsExactlyInAnyOrder("1", "2", "");
            assertThat(cdb.run("DELETE FROM a WHERE l NOT IN (SELECT bl FROM b)").updateCount()).isEqualTo(2);
            assertThat(rows(cdb, "true")).containsExactly("2");
        }
    }

    /// The set is read when the query is opened, before its first page, and every frame of the query reads that one
    /// set: rows the subquery's table gains after the open are not in it, and a query opened later sees them.
    @Test
    void theSubqueryRunsOnceBeforeTheFirstPage() {
        try (Store store = Store.open(root)) {
            change(store, "CREATE TABLE a (ts TIMESTAMP, k SYMBOL, l LONG, d DOUBLE, s VARCHAR) TIMESTAMP(ts)"
                    + " PARTITION BY DAY");
            change(store, "INSERT INTO a VALUES " + A_ROWS);
            change(store, "CREATE TABLE b (ts TIMESTAMP, bl LONG) TIMESTAMP(ts) PARTITION BY DAY");
            change(store, "INSERT INTO b VALUES ('2026-11-16T10:00:00.000000Z', 2)");
            final Plan query = plan(store, "SELECT l FROM a WHERE l IN (SELECT bl FROM b)");
            try (Cursor cursor = Executor.open(query, store, NOW)) {
                change(store, "INSERT INTO b VALUES ('2026-11-16T11:00:00.000000Z', 4)");
                assertThat(longs(cursor)).containsExactly(2L);
            }
            try (Cursor cursor = Executor.open(query, store, NOW)) {
                assertThat(longs(cursor)).containsExactly(2L, 4L);
            }
        }
    }

    /// A subquery of more rows than a result holds (1,000,000) is refused, in a SELECT and in a DELETE, which then
    /// deletes nothing; one of exactly that many rows runs.
    @Test
    void theSubqueryIsBoundedByTheResultRowLimit() {
        try (Cdb cdb = tables()) {
            assertThatThrownBy(() -> rows(cdb, "l IN (SELECT x FROM long_sequence(1000001))"))
                    .isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("DELETE FROM a WHERE l IN (SELECT x FROM long_sequence(1000001))"))
                    .isInstanceOf(CdbException.class);
            assertThat(rows(cdb, "true")).hasSize(5);
            assertThat(rows(cdb, "l IN (SELECT x FROM long_sequence(1000000))"))
                    .containsExactlyInAnyOrder("1", "2", "3", "4");
        }
    }

    /// EXPLAIN shows the subquery's plan, with the columns its scan reads, under the filter, and under a DELETE's
    /// line; it does not run it, so a subquery past the row limit explains.
    @Test
    void explainShowsTheSubqueryAndDoesNotRunIt() {
        try (Cdb cdb = tables()) {
            assertThat(cdb.explain("SELECT l FROM a WHERE l IN (SELECT bl FROM b)").split("\n")).containsExactly(
                    "Project [l:LONG=$2] -- per frame on the pool",
                    "  Filter in_query($2, subquery) -- per frame on the pool",
                    "    Subquery",
                    "      Project [bl:LONG=$2] -- per frame on the pool",
                    "        Scan b [0, 1, 2, 3, 4] [-inf, +inf) -- reads [bl], frames on the pool",
                    "    Scan a [0, 1, 2, 3, 4] [-inf, +inf) -- reads [l], frames on the pool"
            );
            assertThat(cdb.explain("DELETE FROM a WHERE l NOT IN (SELECT bl FROM b)").split("\n")).containsExactly(
                    "Delete a where not(in_query($2, subquery))",
                    "  Subquery",
                    "    Project [bl:LONG=$2] -- per frame on the pool",
                    "      Scan b [0, 1, 2, 3, 4] [-inf, +inf) -- reads [bl], frames on the pool"
            );
            assertThat(SqlRows.rows(cdb.run("EXPLAIN SELECT l FROM a WHERE l IN (SELECT x FROM"
                    + " long_sequence(1000001))"))).contains("    Subquery");
        }
    }

    /// An IN subquery whose operand holds an IN subquery of its own runs both, and answers as the written-out lists
    /// do.
    @Test
    void anOperandHoldingASubqueryRunsItToo() {
        try (Cdb cdb = tables()) {
            assertSameRows(cdb, "(l IN (SELECT bl FROM b)) IN (SELECT bl = 2 FROM b)", "(l IN (2, 4)) IN (true, false)",
                    "1", "2", "3", "", "4");
            assertSameRows(cdb, "(l IN (SELECT bl FROM b)) IN (SELECT bl > 10 FROM b)", "(l IN (2, 4)) IN (false)",
                    "1", "3", "");
        }
    }

    /// A timestamp against an IN subquery of text is refused at plan time: a written-out text reads as an interval
    /// (`'2026-11-15'` is that day), which the subquery's texts, known only when it runs, cannot be.
    @Test
    void aTimestampAgainstASubqueryOfTextIsRefused() {
        try (Cdb cdb = tables()) {
            assertThatThrownBy(() -> rows(cdb, "ts IN (SELECT bs FROM b)"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot compare a timestamp with an IN subquery of text; cast its column to TIMESTAMP");
            assertThat(rows(cdb, "ts IN (SELECT ts FROM b)")).containsExactly("2");
        }
    }

    /// A store holding tables `a` and `b` of [#A_ROWS] and [#B_ROWS]; the caller closes it.
    private Cdb tables() {
        final Cdb cdb = Cdb.open(root);
        cdb.run("CREATE TABLE a (ts TIMESTAMP, k SYMBOL, l LONG, d DOUBLE, s VARCHAR) TIMESTAMP(ts) PARTITION BY DAY");
        cdb.run("INSERT INTO a VALUES " + A_ROWS);
        cdb.run("CREATE TABLE b (ts TIMESTAMP, bk SYMBOL, bl LONG, bd DOUBLE, bs VARCHAR) TIMESTAMP(ts)"
                + " PARTITION BY DAY");
        cdb.run("INSERT INTO b VALUES " + B_ROWS);
        return cdb;
    }

    /// `a`'s `l` where `condition` holds, a NULL as the empty text.
    private static List<String> rows(final Cdb cdb, final String condition) {
        return SqlRows.rows(cdb.run("SELECT l FROM a WHERE " + condition));
    }

    /// The subquery condition and the written-out list answer the same rows, and those are `expected`.
    private static void assertSameRows(
            final Cdb cdb,
            final String viaSubquery,
            final String viaList,
            final String... expected
    ) {
        assertThat(rows(cdb, viaSubquery)).as(viaSubquery).containsExactlyInAnyOrder(expected);
        assertThat(rows(cdb, viaList)).as(viaList).containsExactlyInAnyOrder(expected);
    }

    private static void change(final Store store, final String sql) {
        Executor.execute((Plan.Change) plan(store, sql), store, NOW);
    }

    private static Plan plan(final Store store, final String sql) {
        return Planner.plan(Parser.parse(sql), store::def, NOW);
    }

    /// Every value of the cursor's one LONG column, in order.
    private static List<Long> longs(final Cursor cursor) {
        final List<Long> out = new ArrayList<>();
        for (Page page = cursor.next(); page != null; page = cursor.next()) {
            for (int r = 0; r < page.size; r++) {
                out.add(page.columns[0].getLong(r));
            }
        }
        return out;
    }
}
