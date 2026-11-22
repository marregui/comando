package dev.marregui.comando.mcp;

import java.util.Objects;
import java.util.Optional;

import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.port.PagePort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;

/**
 * The page tools: the HTML pages the editor panels show, reached through the {@link PagePort} the application sets
 * once its window is built; until then every tool answers {@link #NOT_READY}. An MCP answer is text only, so a
 * snapshot answers the PNG's path for the agent to look at with its own Read tool.
 */
final class PageTools {

    static final String NOT_READY = "refused: pages are not ready";
    /** A read's text without {@code max_chars}: as much as one terminal read answers (record 148). */
    static final int DEFAULT_CHARS = 65_536;
    /** The most text one read answers, four default reads: a long page whole in one answer (2026-11-22). */
    static final int MAX_CHARS = 262_144;
    private static final String CLOSING = "[chars=";

    /** Written once by the application's thread through {@link #port}; read by every MCP request thread. */
    private volatile PagePort port;

    void port(final PagePort port) {
        this.port = port;
    }

    String list() {
        final PagePort p = port;
        if (p == null) {
            return NOT_READY;
        }
        final ArrayNode out = Json.array();
        for (final PagePort.Page page : p.list()) {
            out.addObject()
                    .put("title", page.title())
                    .put("id", page.panel().value())
                    .put("file", Objects.toString(page.file(), ""))
                    .put("rendered", page.rendered());
        }
        return out.toString();
    }

    /** The PNG's path and how to see it, or the port's refusal as it gave it. */
    String snapshot(final AppState s, final JsonNode args) {
        final PagePort p = port;
        if (p == null) {
            return NOT_READY;
        }
        final PanelState panel = ActionTools.panel(s, args, false);
        final String png = p.snapshot(panel.id());
        if (png.startsWith("refused")) {
            return png;
        }
        return png + "\nthe page of panel " + panel.title()
                + " as the user sees it; look at the PNG with the Read tool";
    }

    /**
     * The page's title, file and the user's selection, then its text, cut at {@code max_chars} if the port gave more,
     * then the closing line saying whether text was cut.
     */
    String read(final AppState s, final JsonNode args) {
        final PagePort p = port;
        if (p == null) {
            return NOT_READY;
        }
        final PanelState panel = ActionTools.panel(s, args, false);
        final int maxChars = maxChars(args);
        final Optional<PagePort.Reading> reading;
        try {
            reading = p.read(panel.id(), maxChars);
        } catch (final IllegalStateException e) {
            // the page or the window did not answer in time, or the page closed meanwhile: a refusal, as a snapshot's
            return "refused: " + e.getMessage();
        }
        if (reading.isEmpty()) {
            return "refused: panel " + panel.title()
                    + " shows no rendered page; page_list lists the panels that hold one";
        }
        return answer(reading.get(), maxChars);
    }

    private static int maxChars(final JsonNode args) {
        final JsonNode v = args.get("max_chars");
        if (v == null || v.isNull()) {
            return DEFAULT_CHARS;
        }
        final long n = v.asLong(DEFAULT_CHARS);
        if (n < 1 || n > MAX_CHARS) {
            throw new IllegalArgumentException("max_chars is out of range [max_chars=" + n + ", min=1, max="
                    + MAX_CHARS + ']');
        }
        return (int) n;
    }

    private static String answer(final PagePort.Reading r, final int maxChars) {
        String text = r.text();
        boolean more = r.more();
        if (text.length() > maxChars) {
            final int cut = Character.isHighSurrogate(text.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
            text = text.substring(0, cut);
            more = true;
        }
        final StringBuilder out = new StringBuilder(text.length() + r.selection().length() + 256)
                .append("title: ").append(r.title()).append('\n')
                .append("file: ").append(Objects.toString(r.file(), "")).append('\n');
        if (r.selection().isEmpty()) {
            out.append("selection: none\n");
        } else {
            out.append("selection (").append(r.selection().length()).append(" characters):\n")
                    .append(r.selection()).append('\n');
        }
        out.append("text (").append(text.length()).append(" characters):\n").append(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            out.append('\n');
        }
        return out.append(CLOSING).append(text.length())
                .append(", max_chars=").append(maxChars)
                .append(", more=").append(more)
                .append(']').toString();
    }
}
