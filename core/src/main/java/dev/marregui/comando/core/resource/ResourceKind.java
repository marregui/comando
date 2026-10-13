package dev.marregui.comando.core.resource;

import java.util.List;
import java.util.Map;

/**
 * The generic part of a configurable resource: its attributes and the conversion between a spec and the
 * attribute map a form edits. Connections and sessions are the two kinds.
 *
 * @param <S> the spec record
 */
public interface ResourceKind<S> {

    /** "connection", "session". */
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
}
