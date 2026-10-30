package dev.marregui.comando.ui.kit.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The themes that ship with comando, read from the JSON files under {@code /themes} on the class path (record 126), in
 * the swatch's order. Read once, when the first ground, tint or theme is named; it names none itself, so that those
 * classes can make their constants from it.
 */
final class BuiltInThemes {

    /** The built-in themes' ids, in the order the swatch's menu lists them. */
    static final List<String> IDS = List.of("graphite_gold", "espresso_champagne", "aubergine_gold", "midnight_blue",
            "evergreen_forest", "slate_silver", "paper_ochre");

    private static final Map<String, ThemeSpec> SPECS = read();

    private BuiltInThemes() {
    }

    /** The built-in theme {@code id}, as its file gives it. */
    static ThemeSpec spec(String id) {
        ThemeSpec s = SPECS.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no built-in theme " + id);
        }
        return s;
    }

    private static Map<String, ThemeSpec> read() {
        Map<String, ThemeSpec> specs = new LinkedHashMap<>();
        for (String id : IDS) {
            String resource = "/themes/" + id + ".json";
            try (InputStream in = BuiltInThemes.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("missing " + resource);
                }
                ThemeSpec s = ThemeSpec.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!s.id().equals(id)) {
                    throw new IllegalStateException(resource + " names itself " + s.id());
                }
                specs.put(id, s);
            } catch (IOException e) {
                throw new UncheckedIOException(resource, e);
            }
        }
        return specs;
    }
}
