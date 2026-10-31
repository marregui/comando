package dev.marregui.comando.ui.kit.format;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JSON read for display, such as a tool call's input: parsed leniently and printed indented. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** The tree of {@code json}, or an empty object when it is not JSON. */
    public static JsonNode parse(final String json) {
        try {
            return MAPPER.readTree(json);
        } catch (final JacksonException e) {
            return MAPPER.createObjectNode();
        }
    }

    /** {@code node} indented, one field per line. */
    public static String pretty(final JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (final JacksonException e) {
            return node.toString();
        }
    }
}
