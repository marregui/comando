package dev.marregui.comando.core.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.marregui.comando.core.model.PanelId;

/**
 * The Terminals of the session panels, as agents reach them (record 148). Every method may be called from any thread
 * but the event dispatch thread, and returns within a few seconds. Answers that refuse begin with {@code refused}, as
 * {@link ServerSwitch#want} does.
 *
 * <p>A terminal's output is numbered in logical lines (a wrapped line counts once). The numbers only grow, across
 * history trimming, clears and restarts of the same panel's shell, so a {@code since} an agent kept stays valid: lines
 * trimmed or cleared since are reported as skipped, never renumbered.
 */
public interface TerminalPort {

    /** Every editor panel's terminal, started or not, in the workspace's panel order. */
    List<Terminal> list();

    /**
     * Starts the panel's shell in {@code directory} (empty: the panel's folder) without bringing its Terminal to the
     * front. A running shell is left as it is, and the answer says so.
     */
    String start(PanelId panel, Optional<Path> directory);

    /**
     * Types {@code text} into the running shell, then Enter ({@code \r}) when {@code enter}. Refused when the shell is
     * not running; never starts one.
     */
    String send(PanelId panel, String text, boolean enter);

    /** Writes Ctrl-C, which the line discipline delivers to the foreground job only. Refused when no job runs. */
    String interrupt(PanelId panel);

    /** Ends the shell and what runs under it; its scrollback is lost. */
    String stop(PanelId panel);

    /**
     * The lines from {@code since}, or the last {@code lines} lines when {@code since} is negative, at most
     * {@code maxChars} characters. Empty when the panel has no terminal yet.
     */
    Optional<Page> read(PanelId panel, long since, int lines, int maxChars);

    /** {@link System#nanoTime()} of the shell's last output, for a wait on quiet output; empty when none ran. */
    Optional<Long> lastOutputNanos(PanelId panel);

    /**
     * One panel's terminal.
     *
     * @param job the foreground job's command line, empty when the shell waits at its prompt
     * @param next the number of the line the cursor is on: the {@code since} that reads from where a page would end
     * @param alternate a full-screen program (vim, less) holds the screen
     */
    record Terminal(PanelId panel, String title, boolean started, boolean running, long pid, String job, Path directory, long next,
            boolean alternate) {
    }

    /**
     * A page of a terminal's text.
     *
     * @param first the number of the page's first line
     * @param next the {@code since} of the following read: the line the cursor is on, read again next time
     * @param oldest the number of the oldest line still held
     * @param skipped how many lines asked for were trimmed or cleared before the read
     * @param more lines after the page that {@code maxChars} left out
     * @param alternate a full-screen program holds the screen: the page is the screen alone and {@code next} does not move
     */
    record Page(String text, long first, long next, long oldest, long skipped, boolean more, String job, boolean alternate) {
    }
}
