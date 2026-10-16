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
 * What differs between the desktops comando runs on, decided in one place: whether this is macOS, the modifier of
 * menu shortcuts, and where the PATH says a program is.
 */
public final class Platform {

    private static final boolean MAC = isMac(System.getProperty("os.name", ""));

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
     * The modifier of menu shortcuts: the toolkit's where there is a display; without one (tests), ⌘ on macOS and
     * Ctrl elsewhere, as the toolkit would answer.
     */
    public static int menuShortcutMask() {
        return GraphicsEnvironment.isHeadless() ? menuShortcutMask(MAC) : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    static int menuShortcutMask(boolean mac) {
        return mac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    /** {@code program} in each folder of the PATH, in the PATH's order; whether each exists is the caller's question. */
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
