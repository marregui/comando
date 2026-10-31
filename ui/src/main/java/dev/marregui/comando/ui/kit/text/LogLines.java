package dev.marregui.comando.ui.kit.text;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Finds an entry in comando's log by its time. Each entry starts a line with its {@link Instant} then a space; the
 * lines after it that do not start with one (a stack, a message's later lines) belong to it.
 */
public final class LogLines {

    /**
     * How long after the time a notice names its entry may have been written: the pause between reading and logging.
     */
    public static final long SLACK_MILLIS = 1_000;

    private LogLines() {
    }

    /**
     * Where the line of the first entry written at {@code atMillis} or up to {@link #SLACK_MILLIS} after it starts, as
     * an offset into the text; -1 when the text holds none. The text is read backwards from its end, where a recent
     * entry is, and the reading stops at an entry more than {@link #SLACK_MILLIS} older: threads take an entry's time
     * before they wait for the file, so a neighbour can be written in a slightly different order.
     */
    public static int offsetOf(final String text, final long atMillis) {
        int found = -1;
        int end = text.length(); // the end of the line read, its newline excluded
        while (true) {
            final int start = text.lastIndexOf('\n', end - 1) + 1;
            final long t = timeOf(text, start, end);
            if (t != Long.MIN_VALUE) {
                if (t < atMillis - SLACK_MILLIS) {
                    break;
                }
                if (t >= atMillis && t <= atMillis + SLACK_MILLIS) {
                    found = start;
                }
            }
            if (start == 0) {
                break;
            }
            end = start - 1;
        }
        return found;
    }

    /** The time an entry's line starts with, in epoch milliseconds; {@link Long#MIN_VALUE} for any other line. */
    static long timeOf(final String text, final int start, final int end) {
        if (start >= end || !Character.isDigit(text.charAt(start))) {
            return Long.MIN_VALUE;
        }
        final int space = text.indexOf(' ', start);
        if (space < 0 || space > end) {
            return Long.MIN_VALUE;
        }
        try {
            return Instant.parse(text.substring(start, space)).toEpochMilli();
        } catch (final DateTimeParseException e) {
            return Long.MIN_VALUE;
        }
    }
}
