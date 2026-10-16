package dev.marregui.comando.ui.kit.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

/** The desktop's Trash, where a deleted file can be taken back from: the Finder's on macOS, none without a display. */
public final class DesktopTrash {

    private DesktopTrash() {
    }

    /** Whether this desktop has a Trash comando can move files to. */
    public static boolean available() {
        return !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
    }

    /** Moves {@code file} to the Trash; false when there is none or the desktop refused. */
    public static boolean moveToTrash(Path file) {
        return available() && Desktop.getDesktop().moveToTrash(file.toFile());
    }
}
