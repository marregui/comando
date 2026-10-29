package dev.marregui.comando.ui.kit.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What differs between the desktops comando runs on, decided in one place: whether this is macOS, which keys it uses
 * (macOS's or the other desktops'), the modifier of menu shortcuts, and where the PATH says a program is.
 */
public final class Platform {

    private static final boolean MAC = isMac(System.getProperty("os.name", ""));
    /** Chooses the keys of macOS ({@code mac}) or of the other desktops ({@code linux}) on any desktop; for tests. */
    public static final String KEYS_PROPERTY = "comando.keys";

    private Platform() {
    }

    /** True on macOS. */
    public static boolean isMac() {
        return MAC;
    }

    static boolean isMac(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * True when comando uses macOS's keys: ⌘ for its commands, ⌃ left to the shell. Elsewhere Ctrl takes ⌘'s place.
     * The desktop decides, unless {@link #KEYS_PROPERTY} says {@code mac} or {@code linux}, so either set is tested on
     * any desktop.
     */
    public static boolean macKeys() {
        String keys = System.getProperty(KEYS_PROPERTY, "");
        return keys.equals("mac") || !keys.equals("linux") && MAC;
    }

    /**
     * The modifier of menu shortcuts: the toolkit's where there is a display; without one (tests), or with the keys
     * chosen by {@link #KEYS_PROPERTY}, ⌘ for macOS's keys and Ctrl for the others', as the toolkit would answer.
     */
    public static int menuShortcutMask() {
        return GraphicsEnvironment.isHeadless() || !System.getProperty(KEYS_PROPERTY, "").isEmpty()
                ? menuShortcutMask(macKeys())
                : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    static int menuShortcutMask(boolean mac) {
        return mac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    /** {@code program} in each folder of the PATH, in the PATH's order; the caller checks which exist. */
    public static List<Path> onPath(String program) {
        return onPath(program, System.getenv("PATH"));
    }

    static List<Path> onPath(String program, String path) {
        List<Path> found = new ArrayList<>();
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    found.add(Path.of(dir, program));
                }
            }
        }
        return found;
    }
}
