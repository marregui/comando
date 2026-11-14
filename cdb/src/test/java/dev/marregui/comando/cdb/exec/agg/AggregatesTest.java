package dev.marregui.comando.cdb.exec.agg;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [Aggregates] builds one accumulator per aggregate call. Expected values marked QuestDB were read from the local
/// QuestDB's `/exec` for the query in the comment.
class AggregatesTest {

    private static final Bound X = new Bound.Column(0, CdbType.LONG);
    private static final Bound D = new Bound.Column(0, CdbType.DOUBLE);

    /// QuestDB: `SELECT approx_percentile(x, 1.0), approx_percentile(x, 0.0), percentile_disc(x, 0.0),
    /// percentile_disc(x, 1.0) FROM long_sequence(2500)`: the ends of the range, the top one rounded up to the
    /// highest value of its histogram bucket.
    @Test
    void percentileEndsMatchQuestDb() {
        final Vector in = Vector.of(CdbType.LONG, 2500);
        for (int i = 0; i < 2500; i++) {
            in.longs[i] = i + 1;
        }
        assertThat(one(agg("approx_percentile", CdbType.DOUBLE, X, dbl(1.0)), in).doubles[0]).isEqualTo(2559.0);
        assertThat(one(agg("approx_percentile", CdbType.DOUBLE, X, dbl(0.0)), in).doubles[0]).isEqualTo(1.0);
        assertThat(one(agg("percentile_disc", CdbType.LONG, X, dbl(0.0)), in).longs[0]).isEqualTo(1L);
        assertThat(one(agg("percentile_disc", CdbType.LONG, X, dbl(1.0)), in).longs[0]).isEqualTo(2500L);
    }

    /// QuestDB: `SELECT percentile_disc(x / 3.0, 0.5) FROM long_sequence(2500)`: a DOUBLE percentile is exact.
    @Test
    void aDoublePercentileMatchesQuestDb() {
        final Vector in = Vector.of(CdbType.DOUBLE, 2500);
        for (int i = 0; i < 2500; i++) {
            in.doubles[i] = (i + 1) / 3.0;
        }
        assertThat(one(agg("percentile_disc", CdbType.DOUBLE, D, dbl(0.5)), in).doubles[0])
                .isEqualTo(416.6666666666667);
    }

    /// Several groups fed in one batch keep their own state.
    @Test
    void groupsFedInABatchKeepTheirOwnState() {
        final Accumulator sum = Aggregates.create(agg("sum", CdbType.LONG, X));
        final Vector in = Vector.of(CdbType.LONG, 6);
        for (int i = 0; i < 6; i++) {
            in.longs[i] = i + 1;
        }
        sum.ensure(3);
        sum.addAll(new int[] {0, 1, 0, 1, 0, 1}, in, 6);
        final Vector out = Vector.of(CdbType.LONG, 3);
        for (int g = 0; g < 3; g++) {
            sum.write(g, out, g);
        }
        assertThat(out.longs[0]).isEqualTo(9L);
        assertThat(out.longs[1]).isEqualTo(12L);
        assertThat(out.isNull(2)).isTrue();
    }

    /// DISTINCT outside count, an unknown aggregate, a fraction outside [0, 1] and a precision outside 0 to 5 fail.
    @Test
    void unsupportedCallsFail() {
        assertThatThrownBy(() -> Aggregates.create(new Bound.Agg("sum", List.of(X), true, CdbType.LONG)))
                .isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Aggregates.create(agg("median_of_nothing", CdbType.LONG, X)))
                .isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Aggregates.create(agg("percentile_disc", CdbType.LONG, X, dbl(1.5))))
                .isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Aggregates.create(agg("approx_percentile", CdbType.DOUBLE, X, dbl(0.5),
                new Bound.Constant(6L, CdbType.LONG)))).isInstanceOf(CdbException.class);
    }

    private static Vector one(final Bound.Agg agg, final Vector in) {
        final Accumulator accumulator = Aggregates.create(agg);
        accumulator.ensure(1);
        final int size = in.longs != null ? in.longs.length : in.doubles.length;
        for (int i = 0; i < size; i++) {
            accumulator.add(0, in, i);
        }
        final Vector out = Vector.of(agg.type(), 1);
        accumulator.write(0, out, 0);
        return out;
    }

    private static Bound.Agg agg(final String function, final CdbType type, final Bound... args) {
        return new Bound.Agg(function, List.of(args), false, type);
    }

    private static Bound dbl(final double value) {
        return new Bound.Constant(value, CdbType.DOUBLE);
    }
}
