package dev.marregui.comando.core.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.marregui.comando.core.model.PanelId;

/**
 * The HTML pages the editor panels show rendered, as agents reach them: what the user sees, as a picture and as text.
 * Every method may be called from any thread but the event dispatch thread, and returns within a few seconds. Answers
 * that refuse begin with {@code refused}, as {@link TerminalPort}'s do.
 */
public interface PagePort {

    /** Every editor panel whose file is an HTML page, rendered or shown as text, in the workspace's panel order. */
    List<Page> list();

    /**
     * Draws the panel's rendered page, as the user sees it now, into a PNG file and answers its absolute path. Refused
     * when the panel holds no HTML page or shows its text.
     */
    String snapshot(PanelId panel);

    /** The rendered page's text, at most {@code maxChars} characters; empty when the panel shows no rendered page. */
    Optional<Reading> read(PanelId panel, int maxChars);

    /**
     * One panel's page.
     *
     * @param rendered the panel shows the page rendered, not its text
     */
    record Page(PanelId panel, String title, Path file, boolean rendered) {
    }

    /**
     * What a rendered page holds.
     *
     * @param title the document's {@code <title>}, empty when it has none
     * @param selection the text the user has selected on the page, empty when none
     * @param text the page's visible text, cut at the {@code maxChars} asked for
     * @param more text after the cut that {@code maxChars} left out
     */
    record Reading(String title, Path file, String selection, String text, boolean more) {
    }
}
