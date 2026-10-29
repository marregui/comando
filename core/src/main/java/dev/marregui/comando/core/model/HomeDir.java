package dev.marregui.comando.core.model;

import java.nio.file.Path;

/**
 * The user's home folder in paths a person types or reads: {@code ~} and {@code ~/x} expanded, and a path under home
 * shortened back to {@code ~/x}; and Claude Code's folder, the kit root. The one place each is decided, so every field,
 * tool and label agrees.
 */
public final class HomeDir {

    private HomeDir() {
    }

    public static String home() {
        return System.getProperty("user.home");
    }

    /**
     * Claude Code's folder comando runs its sessions on: the {@link KitRoot#current kit root}. It holds the settings,
     * the stored conversations ({@code projects}) and the plugins.
     */
    public static Path claudeDir() {
        return KitRoot.current().dir();
    }

    /** {@code ~} as the home folder and {@code ~/x} under it; any other text unchanged. */
    public static String expand(String path) {
        if (path.equals("~")) {
            return home();
        }
        return path.startsWith("~/") ? Path.of(home(), path.substring(2)).toString() : path;
    }

    /** {@code ~/x} for a path under the home folder, {@code ~} for the home folder itself; any other path unchanged. */
    public static String shorten(String path) {
        String home = home();
        if (path.equals(home)) {
            return "~";
        }
        return path.startsWith(home + "/") ? "~" + path.substring(home.length()) : path;
    }
}
