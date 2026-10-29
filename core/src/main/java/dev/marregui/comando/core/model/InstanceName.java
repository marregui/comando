package dev.marregui.comando.core.model;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The name of a comando instance, so several on one machine tell themselves apart: in the Dock, the menu bar, the window
 * title and the icon's badge. Given by {@code -Dcomando.name}, {@code COMANDO_NAME} or the data root's {@link #FILE}; else
 * taken from the data root's folder: {@code ~/.comando} is the main instance and has no name, {@code ~/.comando_b} or {@code ~/.comando-b} is {@code b},
 * any other folder is its own name.
 */
public final class InstanceName {

    /** The system property {@code Main} sets for the window to read; empty for the main instance. */
    public static final String PROPERTY = "comando.instance";
    /** The data root's file naming the instance: {@code {"name": "Comando Expert"}}. */
    public static final String FILE = "instance.json";
    private static final String APP = "comando";

    private InstanceName() {
    }

    /** The instance's name: {@code explicit} when given, else from {@code dataRoot}'s folder name; empty for the main instance. */
    public static String of(Path dataRoot, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        Path last = dataRoot == null ? null : dataRoot.toAbsolutePath().normalize().getFileName();
        if (last == null) {
            return "";
        }
        String folder = last.toString();
        String bare = folder.startsWith(".") ? folder.substring(1) : folder;
        if (bare.equalsIgnoreCase(APP)) {
            return "";
        }
        String lower = bare.toLowerCase(Locale.ROOT);
        if ((lower.startsWith(APP + "_") || lower.startsWith(APP + "-")) && bare.length() > APP.length() + 1) {
            return bare.substring(APP.length() + 1);
        }
        return bare;
    }

    /** What the Dock, the menu bar and the window call the application: {@code comando}, {@code comando · b}, or a name
     * that already says comando as it is ({@code Comando Expert}). */
    public static String display(String name) {
        if (name == null || name.isBlank()) {
            return APP;
        }
        return name.toLowerCase(Locale.ROOT).contains(APP) ? name : APP + " · " + name;
    }

    /** The preferences node the window keeps its layout in: the main instance's as before, a named one's of its own. */
    public static String prefsNode(String name) {
        return name == null || name.isBlank() ? "dev/marregui/comando" : "dev/marregui/comando-" + name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** The name {@code Main} recorded for this process; empty for the main instance or before it is set. */
    public static String current() {
        return System.getProperty(PROPERTY, "");
    }
}
