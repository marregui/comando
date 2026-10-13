package dev.marregui.comando.core.resource;

import java.util.List;
import java.util.Objects;

/**
 * One editable attribute of a resource. The resource dialog builds its form from these, so connections
 * and sessions share one dialog.
 *
 * @param hint     shown under the field
 * @param editable for a choice: whether the user may type a value that is not in {@code choices}
 * @param advanced shown in the form's collapsed "Advanced" section: values the application usually sets
 */
public record Attribute(String key, String label, Kind kind, List<String> choices, boolean required, String hint,
                        boolean editable, boolean advanced) {

    public enum Kind { TEXT, SECRET, INTEGER, DECIMAL, BOOLEAN, CHOICE, DIRECTORY, DIRECTORIES }

    public Attribute {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        choices = List.copyOf(choices);
        Objects.requireNonNull(hint, "hint");
    }

    public Attribute(String key, String label, Kind kind, List<String> choices, boolean required, String hint) {
        this(key, label, kind, choices, required, hint, kind != Kind.CHOICE, false);
    }

    public static Attribute of(String key, String label, Kind kind, boolean required, String hint) {
        return new Attribute(key, label, kind, List.of(), required, hint);
    }

    /** A choice the user picks from the list only. */
    public static Attribute choice(String key, String label, List<String> choices, String hint) {
        return new Attribute(key, label, Kind.CHOICE, choices, false, hint, false, false);
    }

    /** A choice whose list holds suggestions: the user may also type another value. */
    public static Attribute openChoice(String key, String label, List<String> choices, String hint) {
        return new Attribute(key, label, Kind.CHOICE, choices, false, hint, true, false);
    }

    /** The same attribute in the form's "Advanced" section. */
    public Attribute inAdvanced() {
        return new Attribute(key, label, kind, choices, required, hint, editable, true);
    }
}
