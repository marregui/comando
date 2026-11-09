package dev.marregui.comando.ui.widgets.terminal;

import java.util.ArrayList;
import java.util.List;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.port.TerminalPort;

import static org.assertj.core.api.Assertions.assertThat;

/// [LineLedger] numbers a terminal's output in logical lines, on JediTerm's own emulator and buffer.
/// - a wrapped line is one line, and keeps its number when the width changes and changes back
/// - rows trimmed off a full history leave the numbers of the rest as they were; an old `since` reads as skipped
/// - a cleared history (ED 3, RIS) and a new shell's buffer number above every line seen before
/// - the last `n` lines, a page cut by `maxChars`, and the alternate screen
class LineLedgerTest {

    private static final int ALL = 1 << 20;

    /// A 107-character line among short ones keeps its number, and stays one line, through width 120, then 40 (where
    /// it spans three rows), then 120 again; so do the lines after it.
    @Test
    void aWrappedLineKeepsItsNumberThroughAWidthChange() {
        final Emulator e = new Emulator(new LineLedger(), 120, 10, 1_000);
        for (int i = 0; i < 30; i++) {
            e.line("line-" + i);
        }
        final String marker = "MARKER " + "x".repeat(100);
        e.line(marker);
        for (int i = 0; i < 5; i++) {
            e.line("tail-" + i);
        }
        e.prompt();
        final long at = e.numberOf(marker);
        final long tail = e.numberOf("tail-4");
        assertThat(e.numberOf("line-0")).isZero();
        assertThat(at).isEqualTo(30);

        e.resize(40, 10);
        assertThat(e.buffer.getHistoryLinesCount() + e.buffer.getScreenLinesCount()).as("rows at width 40")
                .isGreaterThan(37);
        assertThat(e.numberOf(marker)).as("one line, same number, at width 40").isEqualTo(at);
        assertThat(e.numberOf("tail-4")).isEqualTo(tail);

        e.resize(120, 10);
        assertThat(e.numberOf(marker)).as("back at width 120").isEqualTo(at);
        assertThat(e.numberOf("tail-4")).isEqualTo(tail);
        assertThat(e.numberOf("line-0")).isZero();
    }

    /// 200 lines into a 50-row history: a `since` of 0 reads as skipped from the oldest line held, and every line
    /// `line-N` carries the number N, before and after 100 more lines, so no number moved or was given to another.
    @Test
    void rowsTrimmedOffTheHistoryLeaveTheOtherNumbersAsTheyWere() {
        final Emulator e = new Emulator(new LineLedger(), 80, 10, 50);
        for (int i = 0; i < 200; i++) {
            e.line("line-" + i);
        }
        e.prompt();
        TerminalPort.Page p = e.page(0);
        assertThat(p.skipped()).isPositive();
        assertThat(p.oldest()).isEqualTo(p.skipped()).isEqualTo(p.first());
        assertNumberedByName(p);
        assertThat(lines(p).getFirst()).isEqualTo("line-" + p.first());

        for (int i = 200; i < 300; i++) {
            e.line("line-" + i);
        }
        e.prompt();
        final TerminalPort.Page later = e.page(p.first());
        assertThat(later.skipped()).as("trimmed since the last read").isPositive();
        assertNumberedByName(later);
        p = e.page(-1, 3);
        assertThat(lines(p)).hasSize(3).startsWith("line-298", "line-299");
        assertThat(lines(p).getLast()).startsWith("$");
        assertThat(p.first()).isEqualTo(298);
    }

    /// After `clear` (ED 2, then ED 3), and after a full reset (RIS), the lines on the screen carry numbers above every
    /// number read before, and a `since` read before reads as skipped.
    @Test
    void aClearedHistoryNeverGivesANumberToAnotherLine() {
        final Emulator e = new Emulator(new LineLedger(), 80, 10, 1_000);
        for (int i = 0; i < 20; i++) {
            e.line("line-" + i);
        }
        e.prompt();
        final TerminalPort.Page before = e.page(0);
        final long highest = Math.max(before.next(), before.first() + lines(before).size() - 1);

        e.terminal.eraseInDisplay(2);
        e.terminal.eraseInDisplay(3);
        e.terminal.cursorPosition(1, 1);
        e.line("after-clear");
        e.prompt();
        final TerminalPort.Page cleared = e.page(before.next());
        assertThat(cleared.skipped()).isPositive();
        assertThat(cleared.first()).isGreaterThan(highest);
        assertThat(cleared.text()).contains("after-clear").doesNotContain("line-");

        final long highestCleared = Math.max(cleared.next(), cleared.first() + lines(cleared).size() - 1);
        e.terminal.reset(true);
        e.line("after-reset");
        e.prompt();
        final TerminalPort.Page reset = e.page(cleared.next());
        assertThat(reset.first()).isGreaterThan(highestCleared);
        assertThat(lines(reset).getFirst()).isEqualTo("after-reset");
    }

    /// A new shell's buffer numbers its first line above every line the old buffer held, though none was read; a
    /// read between the two finds no text and the old lines skipped.
    @Test
    void aRestartCountsOnAboveTheLastNumber() {
        final LineLedger ledger = new LineLedger();
        assertThat(ledger.page(0, 10, ALL, "")).as("before the first start").isEmpty();
        assertThat(ledger.next()).isZero();
        final Emulator first = new Emulator(ledger, 80, 10, 1_000);
        for (int i = 0; i < 5; i++) {
            first.line("old-" + i);
        }
        first.prompt();
        ledger.detach();
        final TerminalPort.Page between = ledger.page(0, 10, ALL, "").orElseThrow();
        assertThat(between.text()).isEmpty();
        assertThat(between.skipped()).isEqualTo(6);

        final Emulator second = new Emulator(ledger, 80, 10, 1_000);
        second.line("fresh");
        second.prompt();
        assertThat(second.numberOf("fresh")).as("old-0 to old-4 and the prompt were 0 to 5").isEqualTo(6);
    }

    /// A page cut at 25 characters says `more` and its `next` is the first line left out; the pages read on from each
    /// `next` join to the whole text. A line longer than the page is cut and read once.
    @Test
    void maxCharsCutsThePageAndNextGoesOnWhereItStopped() {
        final Emulator e = new Emulator(new LineLedger(), 80, 10, 1_000);
        for (int i = 0; i < 12; i++) {
            e.line("line-" + i);
        }
        e.line("y".repeat(40));
        e.prompt();
        final String whole = e.page(0).text();
        final StringBuilder joined = new StringBuilder();
        long since = 0;
        TerminalPort.Page p;
        do {
            p = e.ledger.page(since, 0, 25, "").orElseThrow();
            assertThat(p.text().length()).isLessThanOrEqualTo(25);
            assertThat(p.first()).isEqualTo(since);
            joined.append(p.text());
            since = p.next();
        } while (p.more());
        assertThat(e.ledger.page(0, 0, 25, "").orElseThrow().more()).isTrue();
        assertThat(joined.toString()).isEqualTo(whole.replace("y".repeat(40) + "\n", "y".repeat(25)));
    }

    /// With the alternate screen in use, a page is that screen alone, flagged, and `next` stays the main screen's; back
    /// on the main screen, its lines read as before.
    @Test
    void theAlternateScreenIsFlaggedAndLeavesNextAlone() {
        final Emulator e = new Emulator(new LineLedger(), 80, 10, 1_000);
        for (int i = 0; i < 15; i++) {
            e.line("line-" + i);
        }
        e.prompt();
        final TerminalPort.Page main = e.page(0);
        assertThat(main.alternate()).isFalse();
        assertThat(e.ledger.alternate()).isFalse();

        // as a full-screen program does on entry (CSI ? 1049 h) and exit (CSI ? 1049 l)
        e.terminal.saveCursor();
        e.terminal.useAlternateBuffer(true);
        e.terminal.cursorPosition(1, 1);
        e.line("vim-screen");
        final TerminalPort.Page alternate = e.page(main.next());
        assertThat(alternate.alternate()).isTrue();
        assertThat(e.ledger.alternate()).isTrue();
        assertThat(alternate.text()).contains("vim-screen").doesNotContain("line-");
        assertThat(alternate.next()).isEqualTo(main.next());
        assertThat(e.ledger.next()).isEqualTo(main.next());

        e.terminal.useAlternateBuffer(false);
        e.terminal.restoreCursor();
        final TerminalPort.Page back = e.page(0);
        assertThat(back.alternate()).isFalse();
        assertThat(back.text()).isEqualTo(main.text());
        assertThat(back.next()).isEqualTo(main.next());
    }

    /// Every line of `p` named `line-N` sits at number N.
    private static void assertNumberedByName(final TerminalPort.Page p) {
        final List<String> lines = lines(p);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("line-")) {
                assertThat(lines.get(i)).as("line at %d", p.first() + i).isEqualTo("line-" + (p.first() + i));
            }
        }
    }

    private static List<String> lines(final TerminalPort.Page p) {
        final List<String> lines = new ArrayList<>(List.of(p.text().split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    /// JediTerm's emulator on its own buffer, numbered by a ledger; text is written as a shell's output would be.
    private static final class Emulator {

        private final LineLedger ledger;
        private final TerminalTextBuffer buffer;
        private final JediTerminal terminal;

        Emulator(final LineLedger ledger, final int columns, final int rows, final int historyRows) {
            this.ledger = ledger;
            final StyleState style = new StyleState();
            buffer = new TerminalTextBuffer(columns, rows, style, historyRows);
            terminal = new JediTerminal(new Display(), buffer, style);
            ledger.attach(buffer, terminal, historyRows);
        }

        void line(final String text) {
            terminal.writeCharacters(text);
            terminal.crnl();
        }

        void prompt() {
            terminal.writeCharacters("$ ");
        }

        void resize(final int columns, final int rows) {
            terminal.resize(new TermSize(columns, rows), RequestOrigin.User);
        }

        TerminalPort.Page page(final long since) {
            return ledger.page(since, 0, ALL, "").orElseThrow();
        }

        TerminalPort.Page page(final long since, final int lines) {
            return ledger.page(since, lines, ALL, "").orElseThrow();
        }

        /// The number of the line that reads `text`.
        long numberOf(final String text) {
            final TerminalPort.Page p = page(0);
            final List<String> lines = lines(p);
            assertThat(lines).contains(text);
            return p.first() + lines.indexOf(text);
        }
    }

    /// A display that shows nothing.
    private static final class Display implements TerminalDisplay {

        @Override
        public void setCursor(final int x, final int y) {
            // nothing shown
        }

        @Override
        public void setCursorShape(final CursorShape shape) {
            // nothing shown
        }

        @Override
        public void beep() {
            // nothing heard
        }

        @Override
        public void scrollArea(final int scrollRegionTop, final int scrollRegionSize, final int dy) {
            // nothing shown
        }

        @Override
        public void setCursorVisible(final boolean visible) {
            // nothing shown
        }

        @Override
        public void useAlternateScreenBuffer(final boolean useAlternateScreenBuffer) {
            // nothing shown
        }

        @Override
        public String getWindowTitle() {
            return "";
        }

        @Override
        public void setWindowTitle(final String name) {
            // nothing shown
        }

        @Override
        public TerminalSelection getSelection() {
            return null;
        }

        @Override
        public void terminalMouseModeSet(final MouseMode mode) {
            // no mouse
        }

        @Override
        public void setMouseFormat(final MouseFormat format) {
            // no mouse
        }

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }
    }
}
