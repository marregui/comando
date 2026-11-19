package dev.marregui.comando.cdb.exec.expr;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;

import static dev.marregui.comando.cdb.exec.expr.Exprs.NOW_MICROS;
import static dev.marregui.comando.cdb.exec.expr.Exprs.call;
import static dev.marregui.comando.cdb.exec.expr.Exprs.col;
import static dev.marregui.comando.cdb.exec.expr.Exprs.eval;
import static dev.marregui.comando.cdb.exec.expr.Exprs.lit;
import static dev.marregui.comando.cdb.exec.expr.Exprs.ts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The edges of text handling where QuestDB's own readers decide: the `KK` hour of `to_str`, an empty or
/// escape-ended LIKE pattern, and the long and double literals QuestDB's `Numbers` and `FastDoubleParser` read.
class TextEdgesTest {

    private static final CdbType B = CdbType.BOOLEAN;
    private static final CdbType V = CdbType.VARCHAR;

    /// `KK` is the 0-based 12-hour clock in two digits, as QuestDB's `MicrosFormatCompiler` pads it; `K` stays one.
    @Test
    void kkPadsTheTwelveHourClock() {
        assertThat(eval(call("to_str", V, ts("2026-11-14T03:05:00.000000Z"), lit("KK")))).isEqualTo("03");
        assertThat(eval(call("to_str", V, ts("2026-11-14T15:05:00.000000Z"), lit("KK:mm")))).isEqualTo("03:05");
        assertThat(eval(call("to_str", V, ts("2026-11-14T03:05:00.000000Z"), lit("K")))).isEqualTo("3");
        assertThat(eval(call("to_str", V, ts("2026-11-14T22:05:00.000000Z"), lit("KK")))).isEqualTo("10");
    }

    /// An empty pattern matches nothing, not even empty text.
    @Test
    void anEmptyLikePatternMatchesNothing() {
        assertThat(eval(call("like", B, lit(""), lit("")))).isEqualTo("false");
        assertThat(eval(call("like", B, lit("a"), lit("")))).isEqualTo("false");
        assertThat(eval(call("ilike", B, lit(""), lit("")))).isEqualTo("false");
    }

    /// A constant pattern that ends with the escape character is refused; one read per row matches nothing; an
    /// escaped backslash at the end is a literal backslash.
    @Test
    void aPatternEndingWithTheEscapeCharacter() {
        assertThatThrownBy(() -> eval(call("like", B, lit("a\\"), lit("a\\")))).isInstanceOf(CdbException.class);
        assertThat(eval(call("like", B, lit("a\\"), lit("a\\\\")))).isEqualTo("true");
        final Vector patterns = Vector.of(V, Page.CAPACITY);
        patterns.setText(0, "a\\");
        patterns.setText(1, "a%");
        final Vector texts = Vector.of(V, Page.CAPACITY);
        texts.setText(0, "a\\");
        texts.setText(1, "a\\");
        final Vector out = Functions.compile(call("like", B, col(0, V), col(1, V)), NOW_MICROS)
                .eval(new Page(new Vector[] {texts, patterns}, 2));
        assertThat(out.getBoolean(0)).isFalse();
        assertThat(out.getBoolean(1)).isTrue();
    }

    /// Text to LONG takes a trailing `L` or `l` after a digit and `_` between groups of digits, as QuestDB's
    /// `Numbers.parseLong` does; anything else stays NULL.
    @Test
    void textToLongReadsQuestDbsLongLiterals() {
        final CdbType l = CdbType.LONG;
        assertThat(eval(call("cast", l, lit("12L")))).isEqualTo("12");
        assertThat(eval(call("cast", l, lit("-5l")))).isEqualTo("-5");
        assertThat(eval(call("cast", l, lit("1_000_000")))).isEqualTo("1000000");
        assertThat(eval(call("cast", l, lit("1_2L")))).isEqualTo("12");
        for (final String refused : new String[] {"L", "-L", "_1", "1__2", "12_", "1_L", "1L2", "12LL", " 12"}) {
            assertThat(eval(call("cast", l, lit(refused)))).as(refused).isEqualTo("null");
        }
    }

    /// Text to DOUBLE ignores control characters and spaces at both ends, and a literal past the DOUBLE range is NaN
    /// where Java would round it to an infinity or to 0; the word `Infinity` and a zero literal keep their value.
    @Test
    void textToDoubleReadsQuestDbsDoubleLiterals() {
        final CdbType d = CdbType.DOUBLE;
        assertThat(eval(call("cast", d, lit(" 1.5 ")))).isEqualTo("1.5");
        assertThat(eval(call("cast", d, lit("\t2\n")))).isEqualTo("2.0");
        for (final String refused : new String[] {"1e400", "-1e400", "1e-400", "0.0001e-400", "0x1p-2000", "0x1p2000",
                " ", ""}) {
            assertThat(eval(call("cast", d, lit(refused)))).as(refused).isEqualTo("null");
        }
        assertThat(eval(call("cast", d, lit("0e-400")))).isEqualTo("0.0");
        assertThat(eval(call("cast", d, lit("-0.0")))).isEqualTo("-0.0");
        assertThat(eval(call("cast", d, lit("Infinity")))).isEqualTo("Infinity");
        assertThat(eval(call("cast", d, lit("-Infinity")))).isEqualTo("-Infinity");
    }
}
