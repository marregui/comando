package dev.marregui.comando.cdb.plan;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A text literal that meets a LONG or DOUBLE in a comparison or an `in` list is read as that number, as QuestDB reads
/// it. Each row names the number the text is read as; the comment above each group holds the QuestDB queries whose
/// counts were equal, run on 2026-11-14 over `kit_run` (`findings` is LONG, 471 of its 1,419 rows are NULL) and
/// `comando_usage` (`pct` is DOUBLE) with `ts < '2026-11-14'`, or the ones that failed.
class BinderQuestDbParityTest {

    private static final CdbType L = CdbType.LONG;
    private static final CdbType D = CdbType.DOUBLE;
    private static final CdbType V = CdbType.VARCHAR;

    static Stream<Arguments> read() {
        return Stream.of(
                // SELECT count() FROM kit_run WHERE findings = '' -> 27, as findings = 0; findings = ' ' and
                // findings = '-' -> 27 too, and findings != '' -> 947; findings = '7' -> 29, as findings = 7;
                // findings = 'a' -> 21, as findings = 9
                Arguments.of("=", L, "", 0L),
                Arguments.of("=", L, " ", 0L),
                Arguments.of("=", L, "-", 0L),
                Arguments.of("!=", L, "", 0L),
                Arguments.of("=", L, "7", 7L),
                Arguments.of("=", L, "a", 9L),
                // findings = '12' -> 11, as findings = 12; findings = '1_2' and findings = '12L' -> 11 too;
                // findings < '5' -> 216, as findings < 5; findings >= '0' -> 503; findings > '1_0' -> 133, as
                // findings > 10
                Arguments.of("=", L, "12", 12L),
                Arguments.of("=", L, "1_2", 12L),
                Arguments.of("=", L, "12L", 12L),
                Arguments.of("<", L, "5", 5L),
                Arguments.of(">=", L, "0", 0L),
                Arguments.of(">", L, "1_0", 10L),
                // findings = '-0' -> 27, as findings = 0; findings = '-9223372036854775808' -> 471, as
                // findings IS NULL
                Arguments.of("=", L, "-0", 0L),
                Arguments.of("=", L, "-9223372036854775808", Long.MIN_VALUE),
                Arguments.of("=", L, "9223372036854775807", Long.MAX_VALUE),
                // findings IN ('12') -> 11, as findings IN (12)
                Arguments.of("in", L, "12", 12L),
                // SELECT count() FROM comando_usage WHERE pct = '5' -> 460, as pct = 5; pct = ' 5 ' -> 460; pct = '5.'
                // -> 460; pct = '1e1' -> 250, as pct = 10; pct > '1e1' -> 4382, as pct > 10; pct = '5.5' -> 0, as
                // pct = 5.5; pct IN ('5.5') -> 0
                Arguments.of("=", D, "5", 5.0),
                Arguments.of("=", D, " 5 ", 5.0),
                Arguments.of("=", D, "5.", 5.0),
                Arguments.of("=", D, "1e1", 10.0),
                Arguments.of(">", D, "1e1", 10.0),
                Arguments.of("=", D, "5.5", 5.5),
                Arguments.of("in", D, "5.5", 5.5)
        );
    }

    /// The text constant meeting a numeric column is replaced by the number QuestDB reads it as.
    @ParameterizedTest(name = "{0} {1} column, text ''{2}''")
    @MethodSource("read")
    void textIsReadAsNumber(final String function, final CdbType type, final String text, final Object expected) {
        final Bound.Call call = bind(function, type, text);
        assertThat(call.args().get(0)).isEqualTo(new Bound.Column(0, type));
        assertThat(call.args().get(1)).isEqualTo(new Bound.Constant(expected, type));
    }

    /// The text is the left operand: QuestDB's `'12' = findings` counts 11 rows as `findings = 12` does, and
    /// `'' = findings` counts 27 as `findings = 0` does.
    @Test
    void textOnTheLeftIsReadToo() {
        final Bound.Call twelve = (Bound.Call) Binder.call("=",
                List.of(new Bound.Constant("12", V), new Bound.Column(0, L)), 0);
        final Bound.Call empty = (Bound.Call) Binder.call("=",
                List.of(new Bound.Constant("", V), new Bound.Column(0, L)), 0);
        assertThat(twelve.args().get(0)).isEqualTo(new Bound.Constant(12L, L));
        assertThat(empty.args().get(0)).isEqualTo(new Bound.Constant(0L, L));
    }

    static Stream<Arguments> unreadable() {
        return Stream.of(
                // SELECT count() FROM kit_run WHERE findings < '' -> error; findings < 'a' -> error
                Arguments.of("<", L, ""),
                Arguments.of("<", L, "a"),
                // findings IN ('5') -> error; findings IN ('5', '6') -> error; findings IN ('') -> error; pct IN ('5')
                // -> error
                Arguments.of("in", L, "5"),
                Arguments.of("in", L, ""),
                Arguments.of("in", D, "5"),
                // findings = '9223372036854775808' -> error; findings = '+5' -> error; findings = '9.5' -> error
                Arguments.of("=", L, "9223372036854775808"),
                Arguments.of("=", L, "+5"),
                Arguments.of("=", L, "9.5"),
                Arguments.of("=", L, "1__0"),
                Arguments.of("=", L, "_10"),
                // pct = '' -> error; pct = 'a' -> error; pct = '1_0' -> error
                Arguments.of("=", D, ""),
                Arguments.of("=", D, "a"),
                Arguments.of("=", D, "1_0")
        );
    }

    /// A text QuestDB refuses to read as the number fails to bind.
    @ParameterizedTest(name = "{0} {1} column, text ''{2}''")
    @MethodSource("unreadable")
    void unreadableTextFails(final String function, final CdbType type, final String text) {
        assertThatThrownBy(() -> bind(function, type, text)).isInstanceOf(CdbException.class);
    }

    /// Text meeting text is left as it is.
    @Test
    void textMeetingTextStays() {
        final Bound.Call call = (Bound.Call) Binder.call("=",
                List.of(new Bound.Constant("a", V), new Bound.Constant("b", V)), 0);
        assertThat(call.args()).containsExactly(new Bound.Constant("a", V), new Bound.Constant("b", V));
    }

    private static Bound.Call bind(final String function, final CdbType type, final String text) {
        return (Bound.Call) Binder.call(function,
                List.of(new Bound.Column(0, type), new Bound.Constant(text, V)), 0);
    }
}
