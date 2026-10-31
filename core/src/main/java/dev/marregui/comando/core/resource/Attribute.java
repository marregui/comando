package dev.marregui.comando.core.resource;

import java.util.List;
import java.util.Objects;

/**
 * One editable attribute of a resource. The resource dialog builds its form from these for connections and sessions;
 * the folders window edits the folders' by their keys.
 *
 * @param hint     shown under the field
 * @param editable for a choice: whether the user may type a value that is not in {@code choices}
 * @param advanced  shown in the form's collapsed "Advanced" section: values the application usually sets
 * @param about     what the attribute means and does, at length: the ⓘ beside its label shows it; empty for none
 * @param whenEmpty for a choice: what its empty value means, shown in its place (the default it stands for); empty
 *                  to show a blank
 */
public record Attribute(String key, String label, Kind kind, List<String> choices, boolean required, String hint,
                        boolean editable, boolean advanced, String about, String whenEmpty) {

    /**
     * {@code CONVERSATION}: a session's conversation, picked from those Claude Code stored, with how the next start
     * reaches it; the form edits it as {@code claudeSessionId} and {@code startMode}.
     */
    public enum Kind { TEXT, SECRET, INTEGER, DECIMAL, BOOLEAN, CHOICE, DIRECTORY, DIRECTORIES, CONVERSATION }

    public Attribute {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        choices = List.copyOf(choices);
        Objects.requireNonNull(hint, "hint");
        about = about == null ? "" : about;
        whenEmpty = whenEmpty == null ? "" : whenEmpty;
    }

    public Attribute(final String key, final String label, final Kind kind, final List<String> choices, final boolean required, final String hint) {
        this(key, label, kind, choices, required, hint, kind != Kind.CHOICE, false, "", "");
    }

    public static Attribute of(final String key, final String label, final Kind kind, final boolean required, final String hint) {
        return new Attribute(key, label, kind, List.of(), required, hint);
    }

    /** A choice the user picks from the list only. */
    public static Attribute choice(final String key, final String label, final List<String> choices, final String hint) {
        return new Attribute(key, label, Kind.CHOICE, choices, false, hint, false, false, "", "");
    }

    /** A choice whose list holds suggestions: the user may also type another value. */
    public static Attribute openChoice(final String key, final String label, final List<String> choices, final String hint) {
        return new Attribute(key, label, Kind.CHOICE, choices, false, hint, true, false, "", "");
    }

    /** The same attribute in the form's "Advanced" section. */
    public Attribute inAdvanced() {
        return new Attribute(key, label, kind, choices, required, hint, editable, true, about, whenEmpty);
    }

    /** The same attribute with {@code text} behind the ⓘ beside its label. */
    public Attribute about(final String text) {
        return new Attribute(key, label, kind, choices, required, hint, editable, advanced, text, whenEmpty);
    }

    /** The same choice showing {@code text} for its empty value: the default an empty value stands for. */
    public Attribute whenEmpty(final String text) {
        return new Attribute(key, label, kind, choices, required, hint, editable, advanced, about, text);
    }
}
