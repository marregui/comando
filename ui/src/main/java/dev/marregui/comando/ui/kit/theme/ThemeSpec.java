package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;

/**
 * A theme as its JSON file gives it, before it is checked for reading (record 126): an id, a label, a short
 * description of the theme's effect (record 147), the mode (dark, the default, or light: a pale ground under dark text,
 * record 127), the style (plain, the default, or console: square corners, framed regions and the technical face,
 * record 151), the ground and the tint's three steps. The fourteen built in and every file in the data root's
 * {@code themes} folder have this shape:
 *
 * <pre>{@code
 * {
 *   "id": "graphite_gold",
 *   "label": "Graphite and gold",
 *   "description": "Vivid gold on neutral black: alert and commanding. Easiest on the eyes in a dim room.",
 *   "mode": "dark",
 *   "ground": {"label": "Graphite", "color": "#121212"},
 *   "tint": {"label": "Treasure gold", "highlight": "#FFEDAA", "body": "#E7C55A", "shade": "#9E8230"}
 * }
 * }</pre>
 *
 * The id is lower case letters, digits and underscores, and names the theme's command ({@code theme.graphite_gold});
 * the ground's and the tint's labels may be left out, and are then the theme's. {@code twin}, which may be left out,
 * names the theme of the other mode it pairs with, which the chooser shows beside it (record 128). {@code style}, which
 * may be left out, is {@code "plain"} or {@code "console"}; any other text leaves the file out. {@code description},
 * which may be left out, is at most {@value #DESCRIPTION_MAX} characters of plain text, shown in the theme's tooltips.
 * The tint's steps run from the one that stands out most from the ground, the highlight, to the one that stands out
 * least, the shade: pale to deep on a dark ground, deep to pale on a light one.
 */
record ThemeSpec(String id,
                 String label,
                 Optional<String> description,
                 boolean light,
                 Optional<String> twin,
                 Style style,
                 String groundLabel,
                 Color ground,
                 String tintLabel,
                 Color highlight,
                 Color body,
                 Color shade) {

    /** The longest description: two short sentences on the tooltip's second line; the longest built-in is 89. */
    static final int DESCRIPTION_MAX = 140;
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    /** The theme {@code json} gives; an {@link IllegalArgumentException} naming the first thing wrong when it gives none. */
    static ThemeSpec parse(final String json) {
        JsonNode root;
        try {
            root = Json.readTree(json);
        } catch (final JsonException e) {
            throw new IllegalArgumentException("not JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        final String id = text(root, "id", null);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("\"id\" must be lower case letters, digits and underscores: " + id);
        }
        final String label = text(root, "label", null);
        final Optional<String> description = Optional.ofNullable(root.get("description"))
                .map(v -> text(root, "description", null));
        if (description.isPresent() && description.get().length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException("\"description\" must be at most " + DESCRIPTION_MAX + " characters: "
                    + description.get().length());
        }
        final String mode = text(root, "mode", "dark");
        if (!mode.equals("dark") && !mode.equals("light")) {
            throw new IllegalArgumentException("\"mode\" must be \"dark\" or \"light\": " + mode);
        }
        final Optional<String> twin = Optional.ofNullable(root.get("twin")).map(v -> text(root, "twin", null));
        if (twin.isPresent() && !ID.matcher(twin.get()).matches()) {
            throw new IllegalArgumentException("\"twin\" must be another theme's id: " + twin.get());
        }
        final String style = text(root, "style", "plain");
        if (!style.equals("plain") && !style.equals("console")) {
            throw new IllegalArgumentException("\"style\" must be \"plain\" or \"console\": " + style);
        }
        final JsonNode ground = object(root, "ground");
        final JsonNode tint = object(root, "tint");
        return new ThemeSpec(id, label, description, mode.equals("light"), twin,
                style.equals("console") ? Style.CONSOLE : Style.PLAIN, text(ground, "label", label),
                colour(ground, "ground.color"), text(tint, "label", label), colour(tint, "tint.highlight"),
                colour(tint, "tint.body"), colour(tint, "tint.shade"));
    }

    /** Field {@code name} of {@code node} as text: {@code otherwise} when it is missing, which null forbids. */
    private static String text(final JsonNode node, final String name, final String otherwise) {
        final JsonNode v = node.get(name);
        if (v == null && otherwise != null) {
            return otherwise;
        }
        if (v == null || !v.isString() || v.asString().isBlank()) {
            throw new IllegalArgumentException("\"" + name + "\" must be a text");
        }
        return v.asString().strip();
    }

    private static JsonNode object(final JsonNode node, final String name) {
        final JsonNode v = node.get(name);
        if (v == null || !v.isObject()) {
            throw new IllegalArgumentException("\"" + name + "\" must be an object");
        }
        return v;
    }

    /** The colour at {@code path} ({@code tint.body}), written {@code #RRGGBB}. */
    private static Color colour(final JsonNode node, final String path) {
        final String field = path.substring(path.indexOf('.') + 1);
        final JsonNode v = node.get(field);
        if (v == null || !v.isString() || !HEX.matcher(v.asString()).matches()) {
            throw new IllegalArgumentException("\"" + path + "\" must be a colour written #RRGGBB");
        }
        return new Color(Integer.parseInt(v.asString().substring(1), 16));
    }
}
