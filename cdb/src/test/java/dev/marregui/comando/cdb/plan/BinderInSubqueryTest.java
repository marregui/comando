package dev.marregui.comando.cdb.plan;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.TableDef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `x [NOT] IN (SELECT c …)` planned from SQL: the subquery becomes a plan inside an `in_query` call of the filter,
/// shown under the filter's line; it is refused when it names a column of the query around it, when it answers other
/// than one column, outside a WHERE, and when its column's type is one an IN list of that type would refuse.
class BinderInSubqueryTest {

    private static final long NOW = Times.parse("2026-11-19T12:00:00", 0);

    private static final Map<String, TableDef> TABLES = Map.of(
            "a", new TableDef("a", List.of(
                    new ColumnDef("ts", CdbType.TIMESTAMP),
                    new ColumnDef("k", CdbType.SYMBOL),
                    new ColumnDef("l", CdbType.LONG),
                    new ColumnDef("d", CdbType.DOUBLE),
                    new ColumnDef("s", CdbType.VARCHAR)), 0, PartitionBy.DAY, List.of(), 0),
            "b", new TableDef("b", List.of(
                    new ColumnDef("ts", CdbType.TIMESTAMP),
                    new ColumnDef("bk", CdbType.SYMBOL),
                    new ColumnDef("bl", CdbType.LONG),
                    new ColumnDef("bd", CdbType.DOUBLE),
                    new ColumnDef("bs", CdbType.VARCHAR)), 0, PartitionBy.DAY, List.of(), 0)
    );

    /// The subquery's plan sits under a `Subquery` line below the filter, before the filter's input; the predicate is
    /// `in_query` of the operand and the subquery.
    @Test
    void theSubqueryIsPlannedUnderItsFilter() {
        assertThat(text("SELECT l FROM a WHERE l IN (SELECT bl FROM b)")).isEqualTo("""
                Project [l:LONG=$2]
                  Filter in_query($2, subquery)
                    Subquery
                      Project [bl:LONG=$2]
                        Scan b [0, 1, 2, 3, 4] [-inf, +inf)
                    Scan a [0, 1, 2, 3, 4] [-inf, +inf)
                """);
    }

    /// NOT IN is the negation of the same call, as NOT IN over a list is.
    @Test
    void notInNegatesTheCall() {
        assertThat(predicate("SELECT l FROM a WHERE l NOT IN (SELECT bl FROM b)"))
                .isEqualTo("not(in_query($2, subquery))");
    }

    /// Each filter's subquery is a plan of its own: a WHERE of the subquery may hold another IN subquery, and a
    /// common table expression in scope is read by name inside it.
    @Test
    void subqueriesNestAndReadCommonTableExpressions() {
        final Plan nested = plan("SELECT l FROM a WHERE l IN (SELECT bl FROM b WHERE bd IN (SELECT d FROM a))");
        assertThat(PlanText.of(nested)).contains("Filter in_query($3, subquery)");
        assertThat(PlanText.of(nested).split("Subquery", -1)).hasSize(3);
        assertThat(text("WITH c AS (SELECT bl FROM b WHERE bl > 0) SELECT l FROM a WHERE l IN (SELECT bl FROM c)"))
                .contains("Filter in_query($2, subquery)", "Filter >($2, 0)");
    }

    /// A subquery of more than one column, `*` over several included, is refused when planned.
    @Test
    void severalColumnsAreRefused() {
        assertRefused("SELECT l FROM a WHERE l IN (SELECT bl, bd FROM b)");
        assertRefused("SELECT l FROM a WHERE l IN (SELECT * FROM b)");
    }

    /// A subquery that names a column only the query around it has, bare or qualified, in its WHERE, its select list
    /// or a subquery nested in it, is correlated and refused.
    @Test
    void correlatedSubqueriesAreRefused() {
        assertRefused("SELECT l FROM a WHERE l IN (SELECT bl FROM b WHERE bd = d)");
        assertRefused("SELECT l FROM a WHERE l IN (SELECT bl FROM b WHERE bd = a.d)");
        assertRefused("SELECT l FROM a WHERE l IN (SELECT d FROM b)");
        assertRefused("SELECT l FROM a WHERE l IN (SELECT bl + l FROM b)");
        assertRefused("SELECT l FROM a WHERE l IN (SELECT bl FROM b WHERE bl IN (SELECT x FROM long_sequence(3)"
                + " WHERE x = l))");
    }

    /// A name both inputs have is the subquery's own, so the subquery stays uncorrelated; a name neither has is
    /// refused as an unknown column.
    @Test
    void theSubquerysOwnColumnsWin() {
        assertThat(text("SELECT l FROM a WHERE l IN (SELECT bl FROM b WHERE ts > '2026-11-19')"))
                .contains("Filter in_query($2, subquery)");
        assertRefused("SELECT l FROM a WHERE l IN (SELECT bl FROM b WHERE nope = 1)");
    }

    /// The operand and the subquery's column widen as the operand and the values of an IN list do: LONG with DOUBLE,
    /// SYMBOL with VARCHAR; a NULL operand takes the column's type.
    @Test
    void typesWidenAsTheInListWidens() {
        assertThat(predicate("SELECT l FROM a WHERE l IN (SELECT bd FROM b)")).isEqualTo("in_query($2, subquery)");
        assertThat(predicate("SELECT l FROM a WHERE d IN (SELECT bl FROM b)")).isEqualTo("in_query($3, subquery)");
        assertThat(predicate("SELECT l FROM a WHERE k IN (SELECT bs FROM b)")).isEqualTo("in_query($1, subquery)");
        assertThat(predicate("SELECT l FROM a WHERE NULL IN (SELECT bl FROM b)"))
                .isEqualTo("in_query(NULL:LONG, subquery)");
    }

    /// A column type the operand's IN list would refuse is refused: text against a number, a number against a
    /// timestamp; and text against a timestamp, which a written-out list reads as intervals at plan time.
    @Test
    void incomparableTypesAreRefused() {
        assertRefused("SELECT l FROM a WHERE l IN (SELECT bs FROM b)");
        assertRefused("SELECT l FROM a WHERE s IN (SELECT bd FROM b)");
        assertRefused("SELECT l FROM a WHERE ts IN (SELECT bl FROM b)");
        assertRefused("SELECT l FROM a WHERE ts IN (SELECT bs FROM b)");
    }

    /// An IN subquery outside a WHERE, in a select list or a join's ON, is refused when planned.
    @Test
    void outsideAWhereIsRefused() {
        assertRefused("SELECT l IN (SELECT bl FROM b) FROM a");
        assertRefused("SELECT l FROM a JOIN b ON l IN (SELECT bl FROM b)");
    }

    private static Plan plan(final String sql) {
        return Planner.plan(Parser.parse(sql), name -> TABLES.get(name.toLowerCase(Locale.ROOT)), NOW,
                PlannerTest::sourceFields);
    }

    private static String text(final String sql) {
        return PlanText.of(plan(sql));
    }

    /// The predicate of the plan's first filter.
    private static String predicate(final String sql) {
        final String line = text(sql).lines().filter(l -> l.trim().startsWith("Filter ")).findFirst().orElseThrow();
        return line.trim().substring("Filter ".length());
    }

    private static void assertRefused(final String sql) {
        assertThatThrownBy(() -> plan(sql)).as(sql).isInstanceOf(CdbException.class);
    }
}
