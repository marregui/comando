package dev.marregui.comando.cdb.exec.expr;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

import static dev.marregui.comando.cdb.exec.expr.Exprs.NOW_MICROS;
import static dev.marregui.comando.cdb.exec.expr.Exprs.call;
import static dev.marregui.comando.cdb.exec.expr.Exprs.col;
import static dev.marregui.comando.cdb.exec.expr.Exprs.lit;
import static dev.marregui.comando.cdb.exec.expr.Exprs.nul;
import static org.assertj.core.api.Assertions.assertThat;

/// A text constant compared with a text column takes a path of its own, matched against each row in place; these
/// tests hold it to the answers of the column-against-column path and of `String.compareTo`, with NULL rows, a
/// constant on either side, and the two characters whose UTF-16 order differs from their UTF-8 order.
class TextConstantComparisonTest {

    private static final String PRIVATE_USE = String.valueOf((char) 0xE000);
    private static final String SUPPLEMENTARY = Character.toString(0x1F600);
    private static final String[] VALUES = {"apple", "banana", null, "", "apples", PRIVATE_USE, SUPPLEMENTARY, "b"};
    private static final String[] OPERATORS = {"=", "!=", "<", "<=", ">", ">="};
    private static final Bound TEXT = col(0, CdbType.VARCHAR);
    private static final Bound COPY = col(1, CdbType.VARCHAR);

    /// For every operator and every constant, `x op 'k'` and `'k' op x` give the booleans that comparing the column
    /// with a second column holding `k` on every row gives.
    @Test
    void constantOnEitherSideMatchesTheColumnPath() {
        for (final String constant : VALUES) {
            if (constant == null) {
                continue;
            }
            final Page page = page(constant);
            for (final String op : OPERATORS) {
                assertThat(rows(call(op, CdbType.BOOLEAN, TEXT, lit(constant)), page))
                        .as("x %s '%s'", op, constant)
                        .containsExactlyElementsOf(rows(call(op, CdbType.BOOLEAN, TEXT, COPY), page));
                assertThat(rows(call(op, CdbType.BOOLEAN, lit(constant), TEXT), page))
                        .as("'%s' %s x", constant, op)
                        .containsExactlyElementsOf(rows(call(op, CdbType.BOOLEAN, COPY, TEXT), page));
            }
        }
    }

    /// `<` against a constant follows `String.compareTo`: the private-use character sorts after the supplementary
    /// one in UTF-16, though its UTF-8 bytes sort first; a NULL row is false.
    @Test
    void orderIsStringOrder() {
        final Page page = page(PRIVATE_USE);
        final List<Boolean> expected = new ArrayList<>();
        for (final String value : VALUES) {
            expected.add(value != null && value.compareTo(PRIVATE_USE) < 0);
        }
        assertThat(rows(call("<", CdbType.BOOLEAN, TEXT, lit(PRIVATE_USE)), page)).containsExactlyElementsOf(expected);
        assertThat(rows(call("<", CdbType.BOOLEAN, TEXT, lit(PRIVATE_USE)), page).get(6)).isTrue();
    }

    /// `in` and `not_in` over text constants, with a column among the values: a NULL row is in no list of non-NULL
    /// values, and a NULL constant matches a NULL row, as two NULLs compare equal.
    @Test
    void inMatchesConstantsAndColumns() {
        final Page page = page("b");
        assertThat(rows(call("in", CdbType.BOOLEAN, TEXT, lit("apple"), lit(SUPPLEMENTARY)), page))
                .containsExactly(true, false, false, false, false, false, true, false);
        assertThat(rows(call("in", CdbType.BOOLEAN, TEXT, lit("apple"), COPY), page))
                .containsExactly(true, false, false, false, false, false, false, true);
        assertThat(rows(call("not_in", CdbType.BOOLEAN, TEXT, lit(""), nul(CdbType.VARCHAR)), page))
                .containsExactly(true, true, false, false, true, true, true, true);
    }

    private static Page page(final String constant) {
        final Vector texts = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        final Vector copies = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        for (int i = 0; i < VALUES.length; i++) {
            texts.setText(i, VALUES[i]);
            copies.setText(i, constant);
        }
        return new Page(new Vector[] {texts, copies}, VALUES.length);
    }

    private static List<Boolean> rows(final Bound expr, final Page page) {
        final Vector out = Functions.compile(expr, NOW_MICROS).eval(page);
        final List<Boolean> rows = new ArrayList<>();
        for (int i = 0; i < page.size; i++) {
            rows.add(out.getBoolean(i));
        }
        return rows;
    }
}
