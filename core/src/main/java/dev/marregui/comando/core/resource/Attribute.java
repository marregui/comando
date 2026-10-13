package dev.marregui.comando.core.resource;

import java.util.List;
import java.util.Objects;

/**
 * One editable attribute of a resource. The resource dialog builds its form from these, so connections
 * and sessions share one dialog.
 */
public record Attribute(String key, String label, Kind kind, List<String> choices, boolean required, String hint) {

    public enum Kind { TEXT, SECRET, INTEGER, DECIMAL, BOOLEAN, CHOICE, DIRECTORY, DIRECTORIES }

    public Attribute {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        choices = List.copyOf(choices);
        Objects.requireNonNull(hint, "hint");
    }

    public static Attribute of(String key, String label, Kind kind, boolean required, String hint) {
        return new Attribute(key, label, kind, List.of(), required, hint);
    }

    public static Attribute choice(String key, String label, List<String> choices, String hint) {
        return new Attribute(key, label, Kind.CHOICE, choices, false, hint);
    }
}
