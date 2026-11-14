package dev.marregui.comando.ui.kit.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The differences between two sequences by Myers' greedy algorithm ("An O(ND) Difference Algorithm and Its
 * Variations", 1986): a shortest edit script in time O((N+M)D), D the number of elements inserted and deleted. The
 * search runs forward, one round per edit; on each diagonal it steps down (an insertion) from the diagonal above
 * unless the one below reaches further, and a difference is every edit between two stretches both sides keep. Those
 * choices fix which of several shortest scripts comes out: the one the diffs comando showed before had.
 */
final class Myers {

    private Myers() {
    }

    /** {@link #diff(List, List, Runnable)} with nothing run between rounds. */
    static List<SplitDiff.Chunk> diff(final List<?> a, final List<?> b) {
        return diff(a, b, () -> {
        });
    }

    /**
     * The differences that turn {@code a} into {@code b}, in order, elements compared by {@code equals}.
     * {@code round} runs before each round of the search, D + 1 of them: it stops the search by throwing, which is how
     * a caller bounds the time a diff takes.
     */
    static List<SplitDiff.Chunk> diff(final List<?> a, final List<?> b, final Runnable round) {
        final int n = a.size();
        final int m = b.size();
        final int max = n + m + 1;
        final int middle = max;
        // diagonal k = i - j sits at middle + k, with one more on each side for a round's outermost neighbours
        final Node[] diagonal = new Node[2 * max + 1];
        diagonal[middle + 1] = Node.START;
        for (int d = 0; d < max; d++) {
            round.run();
            for (int k = -d; k <= d; k += 2) {
                final int at = middle + k;
                final Node from;
                int i;
                if (k == -d || k != d && diagonal[at - 1].i < diagonal[at + 1].i) {
                    from = diagonal[at + 1];
                    i = from.i;
                } else {
                    from = diagonal[at - 1];
                    i = from.i + 1;
                }
                // the diagonal below is not read again this round or the next: its path may go
                diagonal[at - 1] = null;
                int j = i - k;
                final Node step = new Node(i, j, false, from.anchor());
                while (i < n && j < m && a.get(i).equals(b.get(j))) {
                    i++;
                    j++;
                }
                final Node end = i == step.i ? step : new Node(i, j, true, step);
                diagonal[at] = end;
                if (i >= n && j >= m) {
                    return chunks(end);
                }
            }
            diagonal[middle + d - 1] = null;
        }
        throw new IllegalStateException("found no edit script [n=" + n + ", m=" + m + "]");
    }

    /** The differences along the path that ends at {@code end}, from the first. */
    private static List<SplitDiff.Chunk> chunks(final Node end) {
        final List<SplitDiff.Chunk> out = new ArrayList<>();
        Node path = end.snake ? end.prev : end;
        while (path != null && path.prev != null) {
            final Node start = path.prev;
            out.add(new SplitDiff.Chunk(start.i, path.i, start.j, path.j));
            path = start.snake ? start.prev : start;
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * A point of the search: {@code i} elements of the first sequence and {@code j} of the second consumed. A snake
     * ends a stretch both sides keep and links to the step it extends; a step links to the snake, or the first step,
     * its edits start from, so the edits between two snakes make one difference.
     */
    private static final class Node {

        /** Before the first round: the diagonal the first step comes down from. */
        static final Node START = new Node(0, -1, true, null);

        final int i;
        final int j;
        final Node prev;
        final boolean snake;

        Node(final int i, final int j, final boolean snake, final Node prev) {
            this.i = i;
            this.j = j;
            this.snake = snake;
            this.prev = prev;
        }

        /** Where the edits of a step taken from here start: nowhere before the first step. */
        Node anchor() {
            if (this == START) {
                return null;
            }
            return snake || prev == null ? this : prev;
        }
    }
}
