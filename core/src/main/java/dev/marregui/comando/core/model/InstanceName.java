package dev.marregui.comando.core.model;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The name of a comando instance, so several on one machine tell themselves apart: in the Dock, the menu bar, the
 * window title and the icon's badge. Given by {@code -Dcomando.name}, {@code COMANDO_NAME} or the data root's
 * {@link #FILE}; else taken from the data root's folder: {@code ~/.comando} is the main instance and has no name,
 * {@code ~/.comando_b} or {@code ~/.comando-b} is {@code b}, any other folder is its own name.
 */
public final class InstanceName {

    /** The system property {@code Main} sets for the window to read; empty for the main instance. */
    public static final String PROPERTY = "comando.instance";
    /** The data root's file naming the instance: {@code {"name": "Comando Expert"}}. */
    public static final String FILE = "instance.json";
    private static final String APP = "comando";
    /** The main instance's id in the peers' topics: the slug of no name. */
    private static final String MAIN = "main";
    /**
     * Names no instance but the main one may carry: the main instance's id, the role its headers mark, and the
     * application's own name, which {@link #display} shows as the main instance's.
     */
    private static final Set<String> RESERVED = Set.of(MAIN, "overseer", APP);
    /** The separator {@link #display} and the peers' headers put between the parts of a name. */
    private static final String SEPARATOR = "·";

    private InstanceName() {
    }

    /**
     * The instance's name: {@code explicit} when given, else from {@code dataRoot}'s folder name; empty for the main
     * instance.
     */
    public static String of(final Path dataRoot, final String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        final Path last = dataRoot == null ? null : dataRoot.toAbsolutePath().normalize().getFileName();
        if (last == null) {
            return "";
        }
        final String folder = last.toString();
        final String bare = folder.startsWith(".") ? folder.substring(1) : folder;
        if (bare.equalsIgnoreCase(APP)) {
            return "";
        }
        final String lower = bare.toLowerCase(Locale.ROOT);
        if ((lower.startsWith(APP + "_") || lower.startsWith(APP + "-")) && bare.length() > APP.length() + 1) {
            return bare.substring(APP.length() + 1);
        }
        return bare;
    }

    /**
     * What the Dock, the menu bar and the window call the application: {@code comando}, {@code comando · b}, or a name
     * that already says comando as it is ({@code Comando Expert}).
     */
    public static String display(final String name) {
        if (name == null || name.isBlank()) {
            return APP;
        }
        return name.toLowerCase(Locale.ROOT).contains(APP) ? name : APP + " · " + name;
    }

    /**
     * The preferences node the window keeps its layout in: {@code dev/marregui/comando} for the main instance, one of
     * its own for a named one.
     */
    public static String prefsNode(final String name) {
        return name == null || name.isBlank() ? "dev/marregui/comando" : "dev/marregui/comando-" + name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /**
     * An instance's id in the peers' topics: its name in lowercase letters, digits and dashes; {@code main} when
     * unnamed.
     */
    public static String slug(final String name) {
        final String s = name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return s.isEmpty() ? MAIN : s;
    }

    /**
     * Why {@code name} cannot name an instance, or empty when it can. Only the main instance, which has no name, is
     * {@code main} to the others and the overseer their headers mark: a name equal to {@code main}, {@code overseer} or
     * {@code comando} in any case, or whose id is {@code main}, would pass for it, and one holding {@code ·} would
     * pass for a role or a session in a header.
     */
    public static Optional<String> refusal(final String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        if (RESERVED.contains(name.strip().toLowerCase(Locale.ROOT)) || MAIN.equals(slug(name))) {
            return Optional.of("refused: only the main instance, which has no name, is known by this one; choose"
                    + " another [name=" + name + ", reserved=" + String.join("|", RESERVED.stream().sorted().toList())
                    + ']');
        }
        if (name.contains(SEPARATOR)) {
            return Optional.of("refused: the name holds the separator of a header's parts; remove it [name=" + name
                    + ", separator=U+00B7]");
        }
        return Optional.empty();
    }

    /** The name {@code Main} recorded for this process; empty for the main instance or before it is set. */
    public static String current() {
        return System.getProperty(PROPERTY, "");
    }
}
