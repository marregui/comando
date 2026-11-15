package dev.marregui.comando.cdb.exec;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.plan.Plan;

import static org.assertj.core.api.Assertions.assertThat;

/// [LatestOnCursor] answers what the whole-input LATEST ON of [ReferenceCursors] answers over random rows with
/// timestamp ties and NULL keys, fed in timestamp order, in any order, or newest first by a reversing stub.
class LatestOnCursorDifferentialTest {

    private static final int CASES = 1_000;

    /// Timestamp-ordered input with ties, one SYMBOL key of few values.
    @Test
    void orderedInputMatchesTheReference() {
        final Random random = ReferenceCursors.random("orderedInputMatchesTheReference");
        for (int i = 0; i < CASES; i++) {
            check(random, new int[] {1}, 1 + random.nextInt(5), true, false);
        }
    }

    /// Input in any timestamp order, many keys over one or two columns, NULL keys among them.
    @Test
    void unorderedInputWithManyKeysMatchesTheReference() {
        final Random random = ReferenceCursors.random("unorderedInputWithManyKeysMatchesTheReference");
        for (int i = 0; i < CASES; i++) {
            check(random, partition(random), 1 + random.nextInt(200), false, false);
        }
    }

    /// Newest-first input, the exact reverse of a timestamp-ordered one, few keys and many timestamp ties.
    @Test
    void newestFirstInputMatchesTheReference() {
        final Random random = ReferenceCursors.random("newestFirstInputMatchesTheReference");
        for (int i = 0; i < CASES; i++) {
            check(random, new int[] {1}, 1 + random.nextInt(5), true, true);
        }
    }

    /// Newest-first input with one SYMBOL key of up to 40 values, so the scan of a few seen keys hands over to the hash
    /// lookup part way through.
    @Test
    void newestFirstOneTextKeyAcrossTheFewKeysLimit() {
        final Random random = ReferenceCursors.random("newestFirstOneTextKeyAcrossTheFewKeysLimit");
        for (int i = 0; i < CASES; i++) {
            check(random, new int[] {1}, 10 + random.nextInt(31), true, true);
        }
    }

    /// Newest-first input with many keys, no key column, a BOOLEAN key or two key columns.
    @Test
    void newestFirstInputWithAnyKeysMatchesTheReference() {
        final Random random = ReferenceCursors.random("newestFirstInputWithAnyKeysMatchesTheReference");
        for (int i = 0; i < CASES; i++) {
            check(random, partition(random), 1 + random.nextInt(200), true, true);
        }
    }

    private static int[] partition(final Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> new int[0];
            case 1 -> new int[] {4};
            case 2 -> new int[] {1, 2};
            case 3 -> new int[] {5, 4};
            default -> new int[] {3};
        };
    }

    /// The reference reads the rows in their order; the cursor reads them newest first when `newestFirst`.
    private static void check(
            final Random random,
            final int[] partition,
            final int cardinality,
            final boolean ascending,
            final boolean newestFirst
    ) {
        final int n = ReferenceCursors.size(random);
        final Object[][] rows = ReferenceCursors.rows(random, n, cardinality, random.nextInt(30), ascending);
        final int pageSize = ReferenceCursors.pageSize(random);
        final List<List<Object>> expected = ReferenceCursors.drain(
                ReferenceCursors.latestOn(ReferenceCursors.cursor(rows, pageSize, false), 0, partition));
        final Plan.LatestOn spec = new Plan.LatestOn(new Plan.Distinct(null), 0, partition, newestFirst);
        final RowsFakes.Drained out = RowsFakes.drain(
                new LatestOnCursor(ReferenceCursors.cursor(rows, pageSize, newestFirst), spec));
        assertThat(out.rows()).as("n=%d, keys=%d, pageSize=%d", n, cardinality, pageSize).isEqualTo(expected);
        assertThat(out.packed()).isTrue();
    }
}
