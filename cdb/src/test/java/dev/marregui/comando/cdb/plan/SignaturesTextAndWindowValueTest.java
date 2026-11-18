package dev.marregui.comando.cdb.plan;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.CdbType.BOOLEAN;
import static dev.marregui.comando.cdb.CdbType.DOUBLE;
import static dev.marregui.comando.cdb.CdbType.LONG;
import static dev.marregui.comando.cdb.CdbType.SYMBOL;
import static dev.marregui.comando.cdb.CdbType.TIMESTAMP;
import static dev.marregui.comando.cdb.CdbType.VARCHAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The types of `abs`, `length`, `upper` and `lower`, which the executor already runs, and of the window value
/// functions `lead`, `first_value` and `last_value`, as QuestDB types them (its INT is LONG here, its STRING VARCHAR).
class SignaturesTextAndWindowValueTest {

    /// `abs` keeps its number's type: LONG stays LONG, DOUBLE stays DOUBLE.
    @Test
    void absKeepsItsArgumentsType() {
        assertThat(Signatures.returnType("abs", List.of(LONG))).isEqualTo(LONG);
        assertThat(Signatures.returnType("abs", List.of(DOUBLE))).isEqualTo(DOUBLE);
    }

    /// `abs` of a non-number, or of two arguments, is refused at plan time.
    @Test
    void absRefusesWhatIsNotOneNumber() {
        assertThatThrownBy(() -> Signatures.returnType("abs", List.of(VARCHAR))).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Signatures.returnType("abs", List.of(LONG, LONG))).isInstanceOf(CdbException.class);
    }

    /// `length` of any text is LONG; `upper` and `lower` of any text are VARCHAR.
    @Test
    void textFunctionsTakeAnyText() {
        for (final CdbType text : List.of(VARCHAR, SYMBOL)) {
            assertThat(Signatures.returnType("length", List.of(text))).isEqualTo(LONG);
            assertThat(Signatures.returnType("upper", List.of(text))).isEqualTo(VARCHAR);
            assertThat(Signatures.returnType("lower", List.of(text))).isEqualTo(VARCHAR);
        }
    }

    /// `length`, `upper` and `lower` refuse a number, a timestamp, a boolean and a second argument.
    @Test
    void textFunctionsRefuseWhatIsNotOneText() {
        for (final String function : List.of("length", "upper", "lower")) {
            for (final CdbType other : List.of(LONG, DOUBLE, TIMESTAMP, BOOLEAN)) {
                assertThatThrownBy(() -> Signatures.returnType(function, List.of(other)))
                        .isInstanceOf(CdbException.class);
            }
            assertThatThrownBy(() -> Signatures.returnType(function, List.of(VARCHAR, VARCHAR)))
                    .isInstanceOf(CdbException.class);
        }
    }

    /// `lead(x[, n[, default]])` is typed as `lag`: the type of `x`, `n` a LONG.
    @Test
    void leadIsTypedAsLag() {
        assertThat(Signatures.windowType("lead", List.of(DOUBLE))).isEqualTo(DOUBLE);
        assertThat(Signatures.windowType("lead", List.of(TIMESTAMP, LONG, TIMESTAMP))).isEqualTo(TIMESTAMP);
        assertThatThrownBy(() -> Signatures.windowType("lead", List.of(LONG, DOUBLE)))
                .isInstanceOf(CdbException.class);
    }

    /// `first_value(x)` and `last_value(x)` are the type of `x` and take exactly one argument.
    @Test
    void firstAndLastValueTakeOneArgument() {
        for (final String function : List.of("first_value", "last_value")) {
            assertThat(Signatures.windowType(function, List.of(SYMBOL))).isEqualTo(SYMBOL);
            assertThat(Signatures.returnType(function, List.of(LONG))).isEqualTo(LONG);
            assertThatThrownBy(() -> Signatures.windowType(function, List.of(LONG, LONG)))
                    .isInstanceOf(CdbException.class);
        }
    }
}
