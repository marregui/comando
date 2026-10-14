package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Which files open in a panel: text small enough to edit; the rest shows read-only in the side pane. */
final class OpenFiles {

    /** The largest file opened as a panel. */
    static final long MAX_PANEL_BYTES = 2L * 1024 * 1024;

    private OpenFiles() {
    }

    /** At most {@link #MAX_PANEL_BYTES} and without a NUL byte in its first 8 kB; called off the EDT. */
    static boolean isText(Path file) {
        try {
            if (Files.size(file) > MAX_PANEL_BYTES) {
                return false;
            }
            try (InputStream in = Files.newInputStream(file)) {
                for (byte b : in.readNBytes(8192)) {
                    if (b == 0) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return true; // the panel's own read says why it cannot open
        }
    }
}
