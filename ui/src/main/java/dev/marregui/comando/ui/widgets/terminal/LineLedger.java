package dev.marregui.comando.ui.widgets.terminal;

import java.util.List;
import java.util.Optional;

import com.jediterm.terminal.Terminal;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.model.TextBufferChangesListener;
import com.jediterm.terminal.util.CharUtils;

import dev.marregui.comando.core.port.TerminalPort;

/**
 * Numbers one terminal's output in logical lines, across the buffers of its successive shells (record 148). A row
 * JediTerm marks wrapped continues on the next row: the two are one line. A line's number is {@code base} plus its
 * index over the history, then the screen down to its last non-empty row or the cursor's row, whichever is lower.
 * <ul>
 *   <li>Rows trimmed off the top of the history add the lines they end to {@code base}, so numbers never move.</li>
 *   <li>A cleared history (ED 3, RIS) and a new shell's buffer start at {@code issued}, above every number a reader
 *       was given and, for a new buffer, above every line the old one held: a number is never given to another
 *       line.</li>
 *   <li>A change of width only re-wraps rows, so numbers hold, unless the re-wrapped history overflowed its capacity:
 *       JediTerm then drops its oldest rows without an event, and the buffer is numbered from {@code issued}, as if
 *       cleared.</li>
 *   <li>While a full-screen program holds the alternate screen its events are ignored; a page is that screen alone,
 *       and its {@code next} stays the one the main screen had last.</li>
 * </ul>
 * Lock order: the buffer's lock, then this ledger's monitor, never the other way. The emulator's thread calls the
 * listener with the buffer's lock held; readers take it before the monitor, on any thread but the EDT's waits.
 */
final class LineLedger {

    /** Guarded by this; the buffer read now, null before the first {@link #attach} and after {@link #detach}. */
    private TerminalTextBuffer buffer;
    /** Guarded by this; the emulator of {@link #buffer}, for its cursor. */
    private Terminal terminal;
    /** Guarded by this; the listener registered on {@link #buffer}. */
    private Watch watch;
    /** Guarded by this; the most rows {@link #buffer}'s history holds before it drops the oldest. */
    private int historyCapacity;
    /** Guarded by this; a buffer was attached once. */
    private boolean started;
    /** Guarded by this; the number of the buffer's first logical line. */
    private long base;
    /** Guarded by this; one past the highest number handed out, or held by a buffer since let go. */
    private long issued;
    /** Guarded by this; the {@code next} of the main screen's last read, kept while the alternate screen shows. */
    private long mainNext;

    /** Numbers {@code buffer} from here on; on the EDT, before its emulator starts. */
    void attach(final TerminalTextBuffer buffer, final Terminal terminal, final int historyCapacity) {
        final Watch w = new Watch(buffer);
        synchronized (this) {
            this.buffer = buffer;
            this.terminal = terminal;
            this.watch = w;
            this.historyCapacity = historyCapacity;
            this.started = true;
            this.mainNext = base;
        }
        buffer.addChangesListener(w);
    }

    /** Lets the buffer go before its emulator closes: the next buffer counts on above every line this one held. */
    void detach() {
        final TerminalTextBuffer b;
        final Watch w;
        synchronized (this) {
            b = buffer;
            w = watch;
        }
        if (b == null) {
            return;
        }
        b.lock();
        try {
            synchronized (this) {
                if (b != buffer) {
                    return;
                }
                if (b.isUsingAlternateBuffer()) {
                    issued = Math.max(issued, mainNext + 1);
                } else {
                    issued = Math.max(issued, base + rows(b).count);
                }
                base = Math.max(base, issued);
                buffer = null;
                terminal = null;
                watch = null;
            }
            b.removeChangesListener(w);
        } finally {
            b.unlock();
        }
    }

    /**
     * The text from line {@code since}, or the last {@code lines} lines when {@code since} is negative, at most
     * {@code maxChars} characters, each line ended by {@code \n}; a single line longer than that is cut. Empty before
     * the first {@link #attach}; after a {@link #detach}, a page with no text whose lines asked for are skipped.
     */
    Optional<TerminalPort.Page> page(final long since, final int lines, final int maxChars, final String job) {
        final TerminalTextBuffer b;
        synchronized (this) {
            b = buffer;
            if (b == null) {
                return started ? Optional.of(gone(since, job)) : Optional.empty();
            }
        }
        b.lock();
        try {
            synchronized (this) {
                if (b != buffer) {
                    return Optional.of(gone(since, job));
                }
                return Optional.of(b.isUsingAlternateBuffer() ? alternatePage(b, maxChars, job) : mainPage(b, since, lines, maxChars, job));
            }
        } finally {
            b.unlock();
        }
    }

    /** The number of the line the cursor is on; 0 before the first {@link #attach}. */
    long next() {
        final TerminalTextBuffer b;
        synchronized (this) {
            b = buffer;
            if (b == null) {
                return base;
            }
        }
        b.lock();
        try {
            synchronized (this) {
                if (b != buffer) {
                    return base;
                }
                if (b.isUsingAlternateBuffer()) {
                    return mainNext;
                }
                final long next = base + rows(b).cursorLine;
                handedOut(next + 1, next);
                return next;
            }
        } finally {
            b.unlock();
        }
    }

    /** Whether a full-screen program holds the alternate screen. */
    boolean alternate() {
        final TerminalTextBuffer b;
        synchronized (this) {
            b = buffer;
        }
        if (b == null) {
            return false;
        }
        b.lock();
        try {
            return b.isUsingAlternateBuffer();
        } finally {
            b.unlock();
        }
    }

    /**
     * Appends the logical lines from index {@code from} while they fit in {@code maxChars}; returns the index of the
     * first left out. A first line longer than {@code maxChars} is cut and counts as read.
     */
    private static int fill(final TerminalTextBuffer b, final Rows rows, final int from, final int maxChars, final StringBuilder text) {
        int i = from;
        for (; i < rows.count; i++) {
            final String line = rows.text(b, i);
            if (text.length() + line.length() + 1 > maxChars) {
                if (text.isEmpty() && maxChars > 0) {
                    text.append(line, 0, Math.min(line.length(), maxChars));
                    i++;
                }
                break;
            }
            text.append(line).append('\n');
        }
        return i;
    }

    /** The page of a terminal whose buffer is gone: no text, every line asked for skipped. */
    private TerminalPort.Page gone(final long since, final String job) {
        final long skipped = since >= 0 && since < base ? base - since : 0;
        return new TerminalPort.Page("", base, base, base, skipped, false, job, false);
    }

    private TerminalPort.Page mainPage(final TerminalTextBuffer b, final long since, final int lines, final int maxChars, final String job) {
        final Rows rows = rows(b);
        final long end = base + rows.count;
        final long cursor = base + rows.cursorLine;
        final long from;
        long skipped = 0;
        if (since < 0) {
            from = Math.max(base, end - Math.max(0, lines));
        } else {
            from = Math.min(Math.max(since, base), end);
            skipped = since < base ? base - since : 0;
        }
        final StringBuilder text = new StringBuilder();
        final long after = fill(b, rows, (int) (from - base), maxChars, text) + base;
        final boolean more = after < end;
        // a cut page goes on where it stopped; a whole one at the cursor's line, which is read again
        final long next = more ? after : cursor;
        handedOut(after, next);
        return new TerminalPort.Page(text.toString(), from, next, base, skipped, more, job, false);
    }

    private TerminalPort.Page alternatePage(final TerminalTextBuffer b, final int maxChars, final String job) {
        final Rows rows = rows(b);
        final StringBuilder text = new StringBuilder();
        final boolean more = fill(b, rows, 0, maxChars, text) < rows.count;
        return new TerminalPort.Page(text.toString(), mainNext, mainNext, base, 0, more, job, true);
    }

    /** Records numbers given to a reader: {@code after} is one past the last line returned, {@code next} its next. */
    private void handedOut(final long after, final long next) {
        issued = Math.max(issued, Math.max(after, next + 1));
        mainNext = next;
    }

    private void discarded(final TerminalTextBuffer of, final List<TerminalLine> lines) {
        synchronized (this) {
            if (of != buffer || of.isUsingAlternateBuffer()) {
                return;
            }
            for (final TerminalLine line : lines) {
                if (!line.isWrapped()) {
                    base++;
                }
            }
        }
    }

    private void cleared(final TerminalTextBuffer of) {
        synchronized (this) {
            if (of == buffer && !of.isUsingAlternateBuffer()) {
                base = Math.max(base, issued);
            }
        }
    }

    private void resized(final TerminalTextBuffer of) {
        synchronized (this) {
            if (of == buffer && !of.isUsingAlternateBuffer() && of.getHistoryLinesCount() >= historyCapacity) {
                base = Math.max(base, issued);
            }
        }
    }

    /**
     * The buffer's rows as logical lines: history then screen, or the screen alone on the alternate screen, down to
     * the last non-empty row or the cursor's, whichever is lower. Read under the buffer's lock; rows past those the
     * screen holds are empty and never read, since reading them would create them.
     */
    private Rows rows(final TerminalTextBuffer b) {
        final int top = b.isUsingAlternateBuffer() ? 0 : -b.getHistoryLinesCount();
        final int held = Math.min(b.getScreenLinesCount(), b.getHeight());
        final int cursorRow = Math.min(Math.max(0, terminal.getCursorY() - 1), b.getHeight() - 1);
        int bottom = held - 1;
        while (bottom >= 0 && b.getLine(bottom).isNulOrEmpty()) {
            bottom--;
        }
        bottom = Math.max(bottom, cursorRow);
        final int[] starts = new int[bottom - top + 2];
        int count = 0;
        int cursorLine = 0;
        for (int r = top; r <= bottom; r++) {
            if (r == top || !(r - 1 < held && b.getLine(r - 1).isWrapped())) {
                starts[count++] = r;
            }
            if (r == cursorRow) {
                cursorLine = count - 1;
            }
        }
        starts[count] = bottom + 1;
        return new Rows(starts, count, cursorLine, held);
    }

    /**
     * Logical lines over rows: line {@code i} spans rows {@code starts[i]} to {@code starts[i + 1] - 1}.
     *
     * @param held how many screen rows the buffer holds; rows at and past it are empty
     */
    private record Rows(int[] starts, int count, int cursorLine, int held) {

        String text(final TerminalTextBuffer b, final int line) {
            final StringBuilder sb = new StringBuilder();
            for (int r = starts[line]; r < starts[line + 1]; r++) {
                if (r < held) {
                    sb.append(b.getLine(r).getText());
                }
            }
            // a wide character fills two cells; the second holds this placeholder
            final String dwc = String.valueOf(CharUtils.DWC);
            return sb.indexOf(dwc) < 0 ? sb.toString() : sb.toString().replace(dwc, "");
        }
    }

    /** One buffer's events, told apart from a later buffer's. */
    private final class Watch implements TextBufferChangesListener {

        private final TerminalTextBuffer of;

        Watch(final TerminalTextBuffer of) {
            this.of = of;
        }

        @Override
        public void linesDiscardedFromHistory(final List<TerminalLine> lines) {
            discarded(of, lines);
        }

        @Override
        public void historyCleared() {
            cleared(of);
        }

        @Override
        public void widthResized() {
            resized(of);
        }
    }
}
