package dev.marregui.comando.ui.kit.action;

import javax.swing.KeyStroke;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * One user action: menus, key bindings and the command palette are all built from these.
 *
 * @param group menu the command appears in; empty for palette only
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

    public static Command of(String id, String label, String icon, KeyStroke key, String group, Runnable run) {
        return new Command(id, label, icon, key, group, run, () -> true);
    }
}
