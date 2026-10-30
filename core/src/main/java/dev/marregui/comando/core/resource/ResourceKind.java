package dev.marregui.comando.core.resource;

import dev.marregui.comando.core.state.AppState;

import java.util.List;
import java.util.Map;

/**
 * The generic part of a configurable resource: its attributes and the conversion between a spec and the
 * attribute map a form edits. Connections, sessions and the Files tab's folders are its kinds.
 *
 * @param <S> the spec record
 */
public interface ResourceKind<S> {

    /** "connection", "session", "folder". */
    String noun();

    List<Attribute> attributes();

    Map<String, String> toAttributes(S spec);

    /** Builds a spec from a form; throws {@link IllegalArgumentException} with a message for the user. */
    S fromAttributes(Map<String, String> attributes);

    String nameOf(S spec);

    S withName(S spec, String name);

    /** A copy for the Clone button; by default the same spec under a new name. */
    default S cloneOf(S spec, String name) {
        return withName(spec, name);
    }

    /** A new spec with defaults. */
    S template(String name);

    /**
     * The {@link dev.marregui.comando.core.state.Notice#subject() subject} of notices about the resource {@code name}.
     */
    String noticeSubject(String name);

    /** The question asked before removing {@code name}, naming what the removal does in state {@code s}. */
    String removalQuestion(AppState s, String name);

    /** The name of the section the dialog folds its {@link Attribute#advanced()} attributes into. */
    default String advancedLabel() {
        return "Advanced";
    }

    /** A line the dialog shows above the form: what holds for every resource of the kind; empty for none. */
    default String context() {
        return "";
    }

    /** Whether a form holding {@code values} opens its "Advanced" section. */
    default boolean showAdvanced(Map<String, String> values) {
        return false;
    }

    /** A warning about what the form's values will do, or empty. */
    default String advice(Map<String, String> values) {
        return "";
    }

    /** "a, b and c". */
    static String sentence(List<String> parts) {
        if (parts.size() <= 1) {
            return String.join("", parts);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    /** "1 panel", "2 panels". */
    static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
