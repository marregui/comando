package dev.marregui.comando.ui.kit.format;

import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;

/** JSON read for display, such as a tool call's input: parsed leniently and printed indented. */
public final class Json {

    private Json() {
    }

    /** The tree of {@code json}, or an empty object when it is not JSON. */
    public static JsonNode parse(final String json) {
        try {
            return dev.marregui.comando.core.json.Json.readTree(json);
        } catch (final JsonException _) {
            return dev.marregui.comando.core.json.Json.object();
        }
    }

    /** {@code node} indented, one field per line. */
    public static String pretty(final JsonNode node) {
        try {
            return dev.marregui.comando.core.json.Json.pretty(node);
        } catch (final JsonException _) {
            return node.toString();
        }
    }
}
