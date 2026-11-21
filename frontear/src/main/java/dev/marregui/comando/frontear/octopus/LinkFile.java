package dev.marregui.comando.frontear.octopus;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;

/**
 * A link's spec as it is kept in its folder, {@value #NAME}: the {@code PutLink} body plus a {@code version}, so a
 * restarted node starts the links it had. Version 1 is the only one; a file of another version, with a property this
 * version does not know or missing one, is refused whole rather than read in part.
 */
final class LinkFile {

    /** The file's name in the link's folder, beside its log. */
    static final String NAME = "link.json";
    static final int VERSION = 1;
    private static final Set<String> PROPERTIES = Set.of("version", "id", "host", "port", "out", "in", "hopLimit");

    private LinkFile() {
    }

    /** The file's bytes for {@code spec}; the filters are written sorted, so the same spec gives the same bytes. */
    static byte[] write(final LinkSpec spec) {
        final ObjectNode root = Json.object();
        root.put("version", VERSION);
        root.put("id", spec.id());
        root.put("host", spec.host());
        root.put("port", spec.port());
        filters(root.putArray("out"), spec.out());
        filters(root.putArray("in"), spec.in());
        root.put("hopLimit", spec.hopLimit());
        return Json.writeBytes(root);
    }

    /**
     * The spec {@code bytes} hold. The values are not range-checked here; {@link Links} checks them as it checks a
     * spec it is given.
     *
     * @throws IllegalArgumentException when the bytes are not this version's object
     */
    static LinkSpec read(final byte[] bytes) {
        final JsonNode root;
        try {
            root = Json.readTree(bytes);
        } catch (final JsonException e) {
            throw new IllegalArgumentException("link file is not JSON", e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("link file is not an object");
        }
        for (final Map.Entry<String, JsonNode> property : root.properties()) {
            if (!PROPERTIES.contains(property.getKey())) {
                throw new IllegalArgumentException("unknown link file property [name=" + property.getKey() + ']');
            }
        }
        final int version = integer(root, "version");
        if (version != VERSION) {
            throw new IllegalArgumentException("link file version not read [version=" + version + ", read="
                    + VERSION + ']');
        }
        return new LinkSpec(text(root, "id"), text(root, "host"), integer(root, "port"), filters(root, "out"),
                filters(root, "in"), integer(root, "hopLimit"));
    }

    private static void filters(final ArrayNode array, final Set<String> filters) {
        for (final String f : new TreeSet<>(filters)) {
            array.add(f);
        }
    }

    private static String text(final JsonNode root, final String name) {
        final JsonNode value = root.path(name);
        if (!value.isString()) {
            throw new IllegalArgumentException("link file property is not a string [name=" + name + ']');
        }
        return value.stringValue();
    }

    private static int integer(final JsonNode root, final String name) {
        final JsonNode value = root.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("link file property is not an int [name=" + name + ']');
        }
        return value.intValue();
    }

    private static Set<String> filters(final JsonNode root, final String name) {
        final JsonNode value = root.path(name);
        if (!value.isArray()) {
            throw new IllegalArgumentException("link file property is not an array [name=" + name + ']');
        }
        final Set<String> filters = new HashSet<>();
        for (final JsonNode element : value.values()) {
            if (!element.isString()) {
                throw new IllegalArgumentException("link file filter is not a string [name=" + name + ']');
            }
            filters.add(element.stringValue());
        }
        return filters;
    }
}
