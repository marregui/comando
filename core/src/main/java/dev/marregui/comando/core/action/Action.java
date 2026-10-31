package dev.marregui.comando.core.action;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import dev.marregui.comando.core.intent.Intent;

/**
 * One thing a person can do in comando's window, offered to agents as the MCP tool {@link #tool} (record 121). The
 * registry {@link Actions} lists them; the {@code mcp} module builds each tool's schema from {@link #args} and performs
 * it by sending the intents named in {@link #intents}.
 *
 * @param tool        the MCP tool's name, {@code <area>_<verb>}
 * @param kind        read, act or destructive: a destructive action runs only for a caller the user granted it
 * @param description what the tool does, in the words the window uses
 * @param args        the tool's arguments, in order
 * @param intents     the intents the action sends, or whose effect it has (a layout tool covers the window's own layout
 *                    intents); the parity test checks that every intent the window sends is in one
 */
public record Action(String tool, Kind kind, String description, List<Arg> args, Set<Class<? extends Intent>> intents) {

    /** What an action does to the user's work, which decides who may call it. */
    public enum Kind {
        /** Reads or shows; changes nothing of the user's. */
        READ,
        /** Changes the window or a session, and can be undone or redone by the user. */
        ACT,
        /** Removes or discards work: needs the user's grant (record 121, rule 2). */
        DESTRUCTIVE
    }

    /** The JSON types an argument takes. */
    public enum Type { STRING, INTEGER, BOOLEAN, STRINGS, OBJECT, ANY }

    /**
     * One argument of an action.
     *
     * @param choices the values a string takes, or empty for any
     */
    public record Arg(String name, Type type, boolean required, String description, List<String> choices) {
        public Arg {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            description = description == null ? "" : description;
            choices = choices == null ? List.of() : List.copyOf(choices);
        }

        public static Arg required(final String name, final Type type, final String description) {
            return new Arg(name, type, true, description, List.of());
        }

        public static Arg optional(final String name, final Type type, final String description) {
            return new Arg(name, type, false, description, List.of());
        }

        public Arg among(final List<String> values) {
            return new Arg(name, type, required, description, values);
        }
    }

    public Action {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
        args = List.copyOf(args);
        intents = Set.copyOf(intents);
    }

    /** The area of the window the action belongs to: the tool name's first word. */
    public String area() {
        final int cut = tool.indexOf('_');
        return cut < 0 ? tool : tool.substring(0, cut);
    }
}
