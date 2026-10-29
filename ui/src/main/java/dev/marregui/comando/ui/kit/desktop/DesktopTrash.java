package dev.marregui.comando.ui.kit.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The desktop's Trash, from which a file or folder moved there can be taken back: the Finder's on macOS, none without
 * a display.
 */
public final class DesktopTrash {

    private DesktopTrash() {
    }

    /** Whether this desktop has a Trash comando can move files and folders to. */
    public static boolean available() {
        return !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
    }

    /**
     * Moves {@code file}, a file or a folder, to the Trash; false when this desktop has none. A Trash that refuses the
     * file (locked, no permission) is a failure of that file, not a missing Trash.
     */
    public static boolean moveToTrash(Path file) throws IOException {
        if (!available()) {
            return false;
        }
        if (!Desktop.getDesktop().moveToTrash(file.toFile())) {
            throw new IOException("the Trash refused it");
        }
        return true;
    }
}
