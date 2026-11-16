package dev.marregui.comando.cdb.exec.agg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Vector;

import static org.assertj.core.api.Assertions.assertThat;

/// [Select#kthDouble] over ranges long enough for its radix passes answers as [Arrays#sort], bit for bit, and leaves
/// the array partitioned around the rank: values that share most of their bits, values spread over every exponent,
/// -0.0 beside 0.0, infinities, NaN, runs of equal values. Seed in the output; replay with `-Dcdb.agg.seed=<seed>`.
class SelectRadixTest {

    private static final long SEED = Long.getLong("cdb.agg.seed", System.nanoTime());
    private static final int[] SIZES = {4_095, 4_096, 4_097, 20_000, 100_003};

    private Random random;

    /// A generator per test, from the run's seed and the test's name, so one test replays alone.
    @BeforeEach
    void seed(final TestInfo info) {
        System.out.println(info.getDisplayName() + " seed=" + SEED);
        random = new Random(SEED ^ info.getDisplayName().hashCode());
    }

    /// Every shape at ranks 0, 1, the middle, 95 % and the last two, and 20 random ranks.
    @Test
    void radixSelectionMatchesTheSort() {
        for (final double[] input : shapes()) {
            final double[] sorted = input.clone();
            Arrays.sort(sorted);
            final int n = input.length;
            final List<Integer> ranks = new ArrayList<>(List.of(0, 1, n / 2, (int) (0.95 * (n - 1)), n - 2, n - 1));
            for (int r = 0; r < 20; r++) {
                ranks.add(random.nextInt(n));
            }
            for (final int k : ranks) {
                final double[] a = input.clone();
                final double kth = Select.kthDouble(a, n, k);
                assertThat(Double.doubleToLongBits(kth)).as("n=%d k=%d", n, k)
                        .isEqualTo(Double.doubleToLongBits(sorted[k]));
                assertThat(Double.doubleToLongBits(a[k])).isEqualTo(Double.doubleToLongBits(kth));
                for (int i = 0; i < n; i++) {
                    final int c = Double.compare(a[i], kth);
                    assertThat(i < k && c > 0 || i > k && c < 0).as("n=%d k=%d i=%d on the wrong side", n, k, i)
                            .isFalse();
                }
                final double[] values = a.clone();
                Arrays.sort(values);
                assertThat(values).as("n=%d k=%d the same values", n, k).isEqualTo(sorted);
            }
        }
    }

    /// percentile_cont over a group past the radix size interpolates between the two ranks the sort gives.
    @Test
    void continuousPercentileOverALargeGroupMatchesTheSort() {
        for (final double[] input : shapes()) {
            final ContinuousPercentileAccumulator accumulator = new ContinuousPercentileAccumulator(0.95);
            accumulator.ensure(1);
            final Vector value = Vector.of(CdbType.DOUBLE, input.length);
            for (int i = 0; i < input.length; i++) {
                value.setDouble(i, input[i]);
            }
            accumulator.addRange(0, value, 0, input.length);
            final Vector out = Vector.of(CdbType.DOUBLE, 1);
            accumulator.write(0, out, 0);
            final double[] sorted = Arrays.stream(input).filter(v -> !Double.isNaN(v)).sorted().toArray();
            final double position = 0.95 * (sorted.length - 1);
            final int lo = (int) Math.floor(position);
            final int hi = (int) Math.ceil(position);
            final double expected = sorted[lo] + (position - lo) * (sorted[hi] - sorted[lo]);
            assertThat(Double.doubleToRawLongBits(out.getDouble(0))).isEqualTo(Double.doubleToRawLongBits(expected));
        }
    }

    private List<double[]> shapes() {
        final List<double[]> shapes = new ArrayList<>();
        for (final int n : SIZES) {
            final double[] narrow = new double[n];
            final double[] wide = new double[n];
            final double[] zeros = new double[n];
            final double[] few = new double[n];
            final double[] edges = new double[n];
            final double[] constant = new double[n];
            final double[] descending = new double[n];
            for (int i = 0; i < n; i++) {
                narrow[i] = 20 + random.nextGaussian() * 3;
                wide[i] = Double.longBitsToDouble(random.nextLong());
                zeros[i] = random.nextBoolean() ? -0.0 : 0.0;
                few[i] = random.nextInt(3) - 1;
                final int pick = random.nextInt(8);
                edges[i] = pick == 0 ? Double.NEGATIVE_INFINITY : pick == 1 ? Double.POSITIVE_INFINITY
                        : pick == 2 ? Double.MIN_VALUE : pick == 3 ? -Double.MIN_VALUE : pick == 4 ? -0.0
                        : random.nextDouble() - 0.5;
                constant[i] = 3.5;
                descending[i] = n - i;
            }
            shapes.addAll(List.of(narrow, wide, zeros, few, edges, constant, descending));
        }
        return shapes;
    }
}
