package dev.marregui.comando.ui.kit.theme;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A theme as its JSON file gives it, before it is checked for reading (record 126): an id, a label, the mode (dark, the
 * default, or light: a pale ground under dark text, record 127), the ground and the tint's three steps. The six built in and every file in the data root's {@code themes} folder have
 * this shape:
 *
 * <pre>{@code
 * {
 *   "id": "graphite_gold",
 *   "label": "Graphite and gold",
 *   "mode": "dark",
 *   "ground": {"label": "Graphite", "color": "#121212"},
 *   "tint": {"label": "Treasure gold", "highlight": "#FFEDAA", "body": "#E7C55A", "shade": "#9E8230"}
 * }
 * }</pre>
 *
 * The id is lower case letters, digits and underscores, and names the theme's command ({@code theme.graphite_gold});
 * the ground's and the tint's labels may be left out, and are then the theme's. {@code twin}, which may be left out,
 * names the theme of the other mode it pairs with, which the chooser shows beside it (record 128). The tint's steps run from the one
 * that stands out most from the ground, the highlight, to the one that stands out least, the shade: pale to deep on a
 * dark ground, deep to pale on a light one.
 */
record ThemeSpec(String id, String label, boolean light, Optional<String> twin, String groundLabel, Color ground,
                 String tintLabel, Color highlight, Color body, Color shade) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    /** The theme {@code json} gives; an {@link IllegalArgumentException} naming the first thing wrong when it gives none. */
    static ThemeSpec parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        String id = text(root, "id", null);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("\"id\" must be lower case letters, digits and underscores: " + id);
        }
        String label = text(root, "label", null);
        String mode = text(root, "mode", "dark");
        if (!mode.equals("dark") && !mode.equals("light")) {
            throw new IllegalArgumentException("\"mode\" must be \"dark\" or \"light\": " + mode);
        }
        Optional<String> twin = Optional.ofNullable(root.get("twin")).map(v -> text(root, "twin", null));
        if (twin.isPresent() && !ID.matcher(twin.get()).matches()) {
            throw new IllegalArgumentException("\"twin\" must be another theme's id: " + twin.get());
        }
        JsonNode ground = object(root, "ground");
        JsonNode tint = object(root, "tint");
        return new ThemeSpec(id, label, mode.equals("light"), twin, text(ground, "label", label), colour(ground, "ground.color"),
                text(tint, "label", label), colour(tint, "tint.highlight"), colour(tint, "tint.body"), colour(tint, "tint.shade"));
    }

    /** Field {@code name} of {@code node} as text: {@code otherwise} when it is missing, which null forbids. */
    private static String text(JsonNode node, String name, String otherwise) {
        JsonNode v = node.get(name);
        if (v == null && otherwise != null) {
            return otherwise;
        }
        if (v == null || !v.isString() || v.asString().isBlank()) {
            throw new IllegalArgumentException("\"" + name + "\" must be a text");
        }
        return v.asString().strip();
    }

    private static JsonNode object(JsonNode node, String name) {
        JsonNode v = node.get(name);
        if (v == null || !v.isObject()) {
            throw new IllegalArgumentException("\"" + name + "\" must be an object");
        }
        return v;
    }

    /** The colour at {@code path} ({@code tint.body}), written {@code #RRGGBB}. */
    private static Color colour(JsonNode node, String path) {
        String field = path.substring(path.indexOf('.') + 1);
        JsonNode v = node.get(field);
        if (v == null || !v.isString() || !HEX.matcher(v.asString()).matches()) {
            throw new IllegalArgumentException("\"" + path + "\" must be a colour written #RRGGBB");
        }
        return new Color(Integer.parseInt(v.asString().substring(1), 16));
    }
}
