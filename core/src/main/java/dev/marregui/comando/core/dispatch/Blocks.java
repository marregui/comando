package dev.marregui.comando.core.dispatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits editor text into blocks: runs of non-blank lines separated by blank lines. Fences carry no meaning
 * here: a stray fence must not join the statements between it and the next one, so text comando inserts
 * (attached results) contains no blank line instead.
 */
public final class Blocks {

    private Blocks() {
    }

    /**
     * A block of text.
     *
     * @param start     offset of the first character
     * @param end       offset after the last character (excluding the trailing newline)
     * @param firstLine 0-based line number of the first line
     */
    public record Block(int start, int end, int firstLine) {
        public String text(final String source) {
            return source.substring(start, end);
        }
    }

    /** The blocks of {@code text}, in order. */
    public static List<Block> all(final String text) {
        final List<Block> blocks = new ArrayList<>();
        int line = 0;
        int blockStart = -1;
        int blockLine = 0;
        int lastNonBlankEnd = -1;
        int pos = 0;
        final int n = text.length();
        while (pos <= n) {
            final int eol = text.indexOf('\n', pos);
            final int lineEnd = eol < 0 ? n : eol;
            final boolean blank = isBlank(text, pos, lineEnd);
            if (!blank) {
                if (blockStart < 0) {
                    blockStart = pos;
                    blockLine = line;
                }
                lastNonBlankEnd = lineEnd;
            } else if (blockStart >= 0) {
                blocks.add(new Block(blockStart, lastNonBlankEnd, blockLine));
                blockStart = -1;
            }
            if (eol < 0) {
                break;
            }
            pos = eol + 1;
            line++;
        }
        if (blockStart >= 0) {
            blocks.add(new Block(blockStart, lastNonBlankEnd, blockLine));
        }
        return blocks;
    }

    /** The block holding {@code caret}, or the nearest block above it, or null when there is none. */
    public static Block at(final String text, final int caret) {
        Block best = null;
        for (final Block b : all(text)) {
            if (b.start() <= caret) {
                best = b;
            }
            if (caret <= b.end() && caret >= b.start()) {
                return b;
            }
        }
        return best;
    }

    private static boolean isBlank(final String text, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
