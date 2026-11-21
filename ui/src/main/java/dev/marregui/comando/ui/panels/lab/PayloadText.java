package dev.marregui.comando.ui.panels.lab;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;

/**
 * A packet's payload as the frontear tab shows it: a JSON object or array indented by two spaces, one field per line;
 * any other text, a scalar or a text too long to parse on the EDT as it came.
 */
public final class PayloadText {

    /**
     * The longest text that is parsed, in characters; a longer one is shown as it came. The parse runs on the EDT, and
     * this keeps it well inside the 200 ms pause the EDT's watch logs as a stall.
     */
    static final int PARSED_CHARS = 256 * 1024;

    private PayloadText() {
    }

    /** {@code text} indented when it is one JSON object or array, else {@code text} itself. */
    public static String shown(final String text) {
        if (text.length() > PARSED_CHARS || !startsLikeJson(text)) {
            return text;
        }
        try {
            final JsonNode tree = Json.readTree(text);
            return tree.isContainer() ? Json.pretty(tree) : text;
        } catch (final JsonException _) {
            return text;
        }
    }

    /** Whether the first character that is not whitespace opens an object or an array, so plain text is not parsed. */
    private static boolean startsLikeJson(final String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '{' || c == '[';
            }
        }
        return false;
    }
}
