package dev.marregui.comando.cdb.exec.expr;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

import static dev.marregui.comando.cdb.exec.expr.Exprs.NOW_MICROS;
import static dev.marregui.comando.cdb.exec.expr.Exprs.call;
import static dev.marregui.comando.cdb.exec.expr.Exprs.col;
import static dev.marregui.comando.cdb.exec.expr.Exprs.eval;
import static dev.marregui.comando.cdb.exec.expr.Exprs.lit;
import static dev.marregui.comando.cdb.exec.expr.Exprs.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Functions over a page of several rows, the reuse of their output vector, and the errors they raise at compile
/// time. The per-row values come from QuestDB over `long_sequence(5)`, whose `x` runs 1 to 5.
class FunctionsTest {

    private static final Bound X = col(0, CdbType.LONG);

    /// `SELECT x / 3, x % 3, round(x / 3.0, 2), concat('n', x), x IN (2, 4), x BETWEEN 2 AND 4, CAST(x AS VARCHAR)
    /// FROM long_sequence(5)` gave these columns, row by row.
    @Test
    void evaluatesEveryRowOfThePage() {
        assertThat(rows(call("/", CdbType.LONG, X, lit(3)))).containsExactly("0", "0", "1", "1", "1");
        assertThat(rows(call("%", CdbType.LONG, X, lit(3)))).containsExactly("1", "2", "0", "1", "2");
        assertThat(rows(call("round", CdbType.DOUBLE, call("/", CdbType.DOUBLE, X, lit(3.0)), lit(2))))
                .containsExactly("0.33", "0.67", "1.0", "1.33", "1.67");
        assertThat(rows(call("concat", CdbType.VARCHAR, lit("n"), X))).containsExactly("n1", "n2", "n3", "n4", "n5");
        assertThat(rows(call("in", CdbType.BOOLEAN, X, lit(2), lit(4))))
                .containsExactly("false", "true", "false", "true", "false");
        assertThat(rows(call("between", CdbType.BOOLEAN, X, lit(2), lit(4))))
                .containsExactly("false", "true", "true", "true", "false");
        assertThat(rows(call("cast", CdbType.VARCHAR, X))).containsExactly("1", "2", "3", "4", "5");
    }

    /// A pattern read from a column is compiled per row, and `%` backtracks past an early partial match.
    @Test
    void likeMatchesAPatternFromAColumn() {
        final Vector patterns = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        patterns.texts[0] = "%b%c";
        patterns.texts[1] = "a%";
        patterns.texts[2] = "%c";
        patterns.setNull(3);
        final Vector texts = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        for (int i = 0; i < 4; i++) {
            texts.texts[i] = "abxbyc";
        }
        final Page page = new Page(new Vector[] {texts, patterns}, 4);
        final Vector out = Functions.compile(call("like", CdbType.BOOLEAN, col(0, CdbType.VARCHAR),
                col(1, CdbType.VARCHAR)), NOW_MICROS).eval(page);
        assertThat(List.of(out.booleans[0], out.booleans[1], out.booleans[2], out.booleans[3]))
                .containsExactly(true, true, true, false);
    }

    /// Two evaluations return the same output vector, rewritten with the second page's values.
    @Test
    void reusesItsOutputVector() {
        final VectorFn fn = Functions.compile(call("+", CdbType.LONG, X, lit(1)), NOW_MICROS);
        final Vector first = fn.eval(page(1, 2));
        assertThat(first.longs[0]).isEqualTo(2);
        final Vector second = fn.eval(page(10, 20));
        assertThat(second).isSameAs(first);
        assertThat(second.longs[1]).isEqualTo(21);
    }

    /// `now()` is the statement's instant, the same on every row.
    @Test
    void nowIsTheStatementInstant() {
        assertThat(eval(call("now", CdbType.TIMESTAMP))).isEqualTo("2026-11-14T10:49:45.097165Z");
    }

    /// An unknown function, a wrong argument count, a non-constant format, a bad unit and a negative substring
    /// length are rejected, and an aggregate is not compiled as a scalar.
    @Test
    void rejectsWhatItCannotEvaluate() {
        assertThatThrownBy(() -> eval(call("nope", CdbType.LONG, lit(1)))).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> eval(call("abs", CdbType.LONG, lit(1), lit(2)))).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Functions.compile(call("to_str", CdbType.VARCHAR, call("now", CdbType.TIMESTAMP),
                col(0, CdbType.VARCHAR)), NOW_MICROS)).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> eval(call("dateadd", CdbType.TIMESTAMP, lit("q"), lit(1),
                call("now", CdbType.TIMESTAMP)))).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> eval(call("timestamp_floor", CdbType.TIMESTAMP, lit("0m"),
                call("now", CdbType.TIMESTAMP)))).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> eval(call("substring", CdbType.VARCHAR, lit("hello"), lit(1), lit(-1))))
                .isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Functions.compile(new Bound.Agg("sum", List.of(X), false, CdbType.LONG), NOW_MICROS))
                .isInstanceOf(CdbException.class);
    }

    private static List<String> rows(final Bound expr) {
        final Vector out = Functions.compile(expr, NOW_MICROS).eval(page(1, 2, 3, 4, 5));
        final List<String> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(render(out, i));
        }
        return rows;
    }

    private static Page page(final long... values) {
        final Vector x = Vector.of(CdbType.LONG, Page.CAPACITY);
        System.arraycopy(values, 0, x.longs, 0, values.length);
        return new Page(new Vector[] {x}, values.length);
    }
}
