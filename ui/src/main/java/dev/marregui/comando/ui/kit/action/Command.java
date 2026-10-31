package dev.marregui.comando.ui.kit.action;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.KeyStroke;

/**
 * One user action: menus, key bindings and the command palette are all built from these.
 *
 * @param group the menu path the command sits at ("Actions"), set when the menu bar places it; for a command in no
 *              menu, reached by its key or the palette, the area it belongs to ("Session"); empty until either is set
 * @param key   key stroke, or null
 */
public record Command(String id, String label, String icon, KeyStroke key, String group, Runnable run, BooleanSupplier enabled) {

    public Command {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(enabled, "enabled");
    }

    /** The same command at menu path {@code path}. */
    public Command withGroup(final String path) {
        return new Command(id, label, icon, key, path, run, enabled);
    }

    public static Command of(final String id, final String label, final String icon, final KeyStroke key, final String group, final Runnable run) {
        return new Command(id, label, icon, key, group, run, () -> true);
    }

    /** A command that can run only while {@code enabled} holds; its key then goes on to the focused component. */
    public static Command of(final String id, final String label, final String icon, final KeyStroke key, final String group, final Runnable run, final BooleanSupplier enabled) {
        return new Command(id, label, icon, key, group, run, enabled);
    }
}
