package dev.marregui.comando.cdb.plan;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;

import static dev.marregui.comando.cdb.CdbType.BOOLEAN;
import static dev.marregui.comando.cdb.CdbType.DOUBLE;
import static dev.marregui.comando.cdb.CdbType.LONG;
import static dev.marregui.comando.cdb.CdbType.SYMBOL;
import static dev.marregui.comando.cdb.CdbType.TIMESTAMP;
import static dev.marregui.comando.cdb.CdbType.VARCHAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The type of each function the corpus calls, for the arguments the corpus passes it.
class SignaturesTest {

    /// Every function name the corpus queries call, in the order of first use.
    private static final List<String> CORPUS = List.of(
            "count", "sum", "now", "dateadd", "max", "round", "min", "approx_percentile", "to_str", "timestamp",
            "count_distinct", "tables", "long_sequence", "datediff", "timestamp_floor", "last", "percentile_disc",
            "table_columns", "concat", "avg", "substring", "replace", "lag", "hour", "date_trunc", "approx_median");

    /// The table functions: their fields come from the catalog, not from a signature.
    private static final Set<String> SOURCES = Set.of("tables", "long_sequence", "table_columns");

    /// A typical argument set per scalar or aggregate function and the type QuestDB gives it.
    private static final Map<String, Case> SCALARS = Map.ofEntries(
            Map.entry("count", new Case(List.of(), LONG)),
            Map.entry("sum", new Case(List.of(DOUBLE), DOUBLE)),
            Map.entry("now", new Case(List.of(), TIMESTAMP)),
            Map.entry("dateadd", new Case(List.of(VARCHAR, LONG, TIMESTAMP), TIMESTAMP)),
            Map.entry("max", new Case(List.of(TIMESTAMP), TIMESTAMP)),
            Map.entry("round", new Case(List.of(DOUBLE, LONG), DOUBLE)),
            Map.entry("min", new Case(List.of(SYMBOL), VARCHAR)),
            Map.entry("approx_percentile", new Case(List.of(DOUBLE, DOUBLE), DOUBLE)),
            Map.entry("to_str", new Case(List.of(TIMESTAMP, VARCHAR), VARCHAR)),
            Map.entry("count_distinct", new Case(List.of(SYMBOL), LONG)),
            Map.entry("datediff", new Case(List.of(VARCHAR, TIMESTAMP, TIMESTAMP), LONG)),
            Map.entry("timestamp_floor", new Case(List.of(VARCHAR, TIMESTAMP), TIMESTAMP)),
            Map.entry("last", new Case(List.of(DOUBLE), DOUBLE)),
            Map.entry("percentile_disc", new Case(List.of(DOUBLE, DOUBLE), DOUBLE)),
            Map.entry("concat", new Case(List.of(VARCHAR, SYMBOL, LONG), VARCHAR)),
            Map.entry("avg", new Case(List.of(LONG), DOUBLE)),
            Map.entry("substring", new Case(List.of(VARCHAR, LONG, LONG), VARCHAR)),
            Map.entry("replace", new Case(List.of(VARCHAR, VARCHAR, VARCHAR), VARCHAR)),
            Map.entry("hour", new Case(List.of(TIMESTAMP), LONG)),
            Map.entry("date_trunc", new Case(List.of(VARCHAR, TIMESTAMP), TIMESTAMP)),
            Map.entry("approx_median", new Case(List.of(DOUBLE), DOUBLE)));

    /// One argument set and its result type.
    private record Case(List<CdbType> args, CdbType type) {
    }

    /// Each corpus function has a type: by signature, as a window value, as a table function's fields, or, for
    /// `timestamp`, as the DDL type it names.
    @Test
    void everyCorpusFunctionHasAType() {
        for (final String function : CORPUS) {
            if (SOURCES.contains(function)) {
                final List<Field> fields = PlannerTest.sourceFields(function, List.of("trades"));
                assertThat(fields).as(function).isNotEmpty();
            } else if ("lag".equals(function)) {
                assertThat(Signatures.windowType(function, List.of(DOUBLE))).as(function).isEqualTo(DOUBLE);
            } else if ("timestamp".equals(function)) {
                assertThat(Ddl.type(function, 0)).as(function).isEqualTo(TIMESTAMP);
            } else {
                final Case c = SCALARS.get(function);
                assertThat(c).as(function).isNotNull();
                assertThat(Signatures.returnType(function, c.args())).as(function).isEqualTo(c.type());
            }
        }
    }

    /// Operators follow QuestDB: LONG and DOUBLE widen to DOUBLE, a comparison is BOOLEAN, a TIMESTAMP plus a LONG
    /// is a TIMESTAMP.
    @Test
    void operators() {
        assertThat(Signatures.returnType("+", List.of(LONG, DOUBLE))).isEqualTo(DOUBLE);
        assertThat(Signatures.returnType("+", List.of(TIMESTAMP, LONG))).isEqualTo(TIMESTAMP);
        assertThat(Signatures.returnType("<", List.of(TIMESTAMP, TIMESTAMP))).isEqualTo(BOOLEAN);
        assertThat(Signatures.returnType("||", List.of(VARCHAR, LONG))).isEqualTo(VARCHAR);
    }

    /// A TIMESTAMP meets a text in either order and in `in` and `between`, as QuestDB reads the text as a timestamp
    /// (`ts = '2026-11-14T11:00:00+01:00'` is a BOOLEAN); a number meeting a text is still refused here, because the
    /// binder reads a text constant as the number before the types are checked.
    @Test
    void timestampMeetsText() {
        assertThat(Signatures.returnType("=", List.of(TIMESTAMP, VARCHAR))).isEqualTo(BOOLEAN);
        assertThat(Signatures.returnType("<", List.of(SYMBOL, TIMESTAMP))).isEqualTo(BOOLEAN);
        assertThat(Signatures.returnType("in", List.of(TIMESTAMP, VARCHAR, VARCHAR))).isEqualTo(BOOLEAN);
        assertThat(Signatures.returnType("between", List.of(TIMESTAMP, VARCHAR, TIMESTAMP))).isEqualTo(BOOLEAN);
        assertThatThrownBy(() -> Signatures.returnType("=", List.of(LONG, VARCHAR))).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Signatures.returnType("=", List.of(VARCHAR, DOUBLE))).isInstanceOf(CdbException.class);
    }

    /// Arguments of the wrong type or number, and an unknown function, are refused.
    @Test
    void mismatchIsRefused() {
        assertThatThrownBy(() -> Signatures.returnType("hour", List.of(LONG))).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Signatures.returnType("sum", List.of(VARCHAR))).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Signatures.returnType("no_such_function", List.of()))
                .isInstanceOf(CdbException.class);
    }
}
