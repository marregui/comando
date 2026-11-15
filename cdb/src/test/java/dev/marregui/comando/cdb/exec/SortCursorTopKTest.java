package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.plan.Plan;

import static org.assertj.core.api.Assertions.assertThat;

/// [SortCursor] with a limit answers exactly the first rows of the full stable sort, NULLs where QuestDB puts them,
/// checked against the whole-input sort of [ReferenceCursors] over random rows with ties and NULLs.
class SortCursorTopKTest {

    private static final int CASES = 1_000;

    /// One LONG key, ascending or descending, few or many distinct values.
    @Test
    void longKeyMatchesTheFullSort() {
        final Random random = ReferenceCursors.random("longKeyMatchesTheFullSort");
        for (int i = 0; i < CASES; i++) {
            check(random, List.of(new Plan.SortKey(2, random.nextBoolean())), limit(random, 0));
        }
    }

    /// One DOUBLE key, whose NULL sorts above every number, with many NULLs.
    @Test
    void doubleKeyWithNullsMatchesTheFullSort() {
        final Random random = ReferenceCursors.random("doubleKeyWithNullsMatchesTheFullSort");
        for (int i = 0; i < CASES; i++) {
            check(random, List.of(new Plan.SortKey(3, random.nextBoolean())), limit(random, 0));
        }
    }

    /// Two or three keys of text, BOOLEAN and timestamp, each in its own direction.
    @Test
    void severalKeysMatchTheFullSort() {
        final Random random = ReferenceCursors.random("severalKeysMatchTheFullSort");
        for (int i = 0; i < CASES; i++) {
            final List<Plan.SortKey> keys = new ArrayList<>();
            keys.add(new Plan.SortKey(random.nextBoolean() ? 1 : 5, random.nextBoolean()));
            keys.add(new Plan.SortKey(4, random.nextBoolean()));
            if (random.nextBoolean()) {
                keys.add(new Plan.SortKey(0, random.nextBoolean()));
            }
            check(random, keys, limit(random, 0));
        }
    }

    /// The limits at the edges: 0, 1, exactly the row count, one more, and one past what an int holds.
    @Test
    void edgeLimitsMatchTheFullSort() {
        final Random random = ReferenceCursors.random("edgeLimitsMatchTheFullSort");
        for (int i = 0; i < CASES; i++) {
            check(random, List.of(new Plan.SortKey(1 + random.nextInt(5), random.nextBoolean())),
                    limit(random, 1 + random.nextInt(5)));
        }
    }

    /// With no key the order is the input's, so a limit keeps the first rows read.
    @Test
    void noKeyKeepsTheFirstRows() {
        final Random random = ReferenceCursors.random("noKeyKeepsTheFirstRows");
        for (int i = 0; i < CASES; i++) {
            check(random, List.of(), limit(random, 0));
        }
    }

    /// `edge` 0 draws any limit; 1 to 5 picks 0, 1, the row count, one more, or one past the int range.
    private static long limit(final Random random, final int edge) {
        return switch (edge == 0 ? random.nextInt(6) : edge) {
            case 1 -> 0;
            case 2 -> 1;
            case 3 -> -2;
            case 4 -> -3;
            case 5 -> Integer.MAX_VALUE + 1L;
            default -> random.nextInt(1_100);
        };
    }

    /// Sorts random rows both ways and compares; a limit of -2 is the row count, -3 one more.
    private static void check(final Random random, final List<Plan.SortKey> keys, final long drawn) {
        final int n = ReferenceCursors.size(random);
        final Object[][] rows = ReferenceCursors.rows(random, n, 1 + random.nextInt(n < 50 ? 4 : 40),
                random.nextInt(40), random.nextBoolean());
        final int pageSize = ReferenceCursors.pageSize(random);
        final long limit = drawn == -2 ? n : drawn == -3 ? n + 1 : drawn;
        final List<List<Object>> full = ReferenceCursors.drain(
                ReferenceCursors.sort(ReferenceCursors.cursor(rows, pageSize, false), keys));
        final List<List<Object>> expected = full.subList(0, (int) Math.min(limit, full.size()));
        final RowsFakes.ListCursor child = ReferenceCursors.cursor(rows, pageSize, false);
        final RowsFakes.Drained out = RowsFakes.drain(new SortCursor(child, keys, limit));
        assertThat(out.rows()).as("n=%d, keys=%s, limit=%d, pageSize=%d", n, keys, limit, pageSize)
                .isEqualTo(expected);
        assertThat(out.packed()).isTrue();
        assertThat(child.pulled()).as("the whole input is read").isEqualTo((n + pageSize - 1) / pageSize);
    }
}
